import crypto from "node:crypto";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

const PACKAGE_NAME = "com.bqsqwad.ai";

function base64url(value) {
  return Buffer.from(value).toString("base64").replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

async function accessToken() {
  const raw = process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON;
  if (!raw) throw new Error("Google Play backend verification is not configured");
  const account = JSON.parse(raw);
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = base64url(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  }));
  const unsigned = header + "." + claim;
  const signer = crypto.createSign("RSA-SHA256");
  signer.update(unsigned);
  signer.end();
  const signature = signer.sign(account.private_key);
  const assertion = unsigned + "." + base64url(signature);
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion
    })
  });
  const body = await response.json();
  if (!response.ok || !body.access_token) throw new Error("Google Play access token could not be created");
  return body.access_token;
}

async function playApi(path, method = "GET", body = undefined) {
  const token = await accessToken();
  const response = await fetch("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE_NAME + "/" + path, {
    method,
    headers: {
      Authorization: "Bearer " + token,
      "Content-Type": "application/json"
    },
    body: body == null ? undefined : JSON.stringify(body)
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error("Google Play verification failed: " + (payload.error?.message || response.status));
  }
  return payload;
}

export async function verifyPlayPurchase({ uid, productId, purchaseToken }) {
  if (!uid || !productId || !purchaseToken) throw new Error("play_purchase_fields_required");
  const db = getFirestore();
  const catalogSnap = await db.collection("playCatalog").doc("config").get();
  const products = catalogSnap.exists && Array.isArray(catalogSnap.data()?.products)
    ? catalogSnap.data().products
    : [];
  const mapping = products.find(p => p.productId === productId && p.enabled === true);
  if (!mapping) throw new Error("play_product_not_configured");

  const existingRef = db.collection("playPurchases").doc(purchaseToken);
  const existing = await existingRef.get();
  if (existing.exists && existing.data()?.uid === uid && existing.data()?.verified === true) {
    return { ok: true, alreadyProcessed: true, state: existing.data()?.state || "verified" };
  }

  const verified = mapping.productType === "subs"
    ? await playApi("purchases/subscriptionsv2/tokens/" + encodeURIComponent(purchaseToken))
    : await playApi("purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken));

  const subscriptionState = mapping.productType === "subs"
    ? String(verified.subscriptionState || "")
    : "";
  const purchaseState = mapping.productType === "inapp"
    ? Number(verified.purchaseState ?? -1)
    : 0;
  const valid = mapping.productType === "subs"
    ? ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"].includes(subscriptionState)
    : purchaseState === 0;
  if (!valid) throw new Error("play_purchase_not_active");

  await db.runTransaction(async tx => {
    const fresh = await tx.get(existingRef);
    if (fresh.exists && fresh.data()?.verified === true) return;

    const entitlementRef = db.collection("entitlements").doc(uid + "_" + String(mapping.planId));
    const creditGrant = Math.max(0, Number(mapping.creditGrant || 0));
    const balanceRef = db.collection("creditBalances").doc(uid);
    const balanceSnap = await tx.get(balanceRef);
    const balance = balanceSnap.exists ? balanceSnap.data() : { available: 0, reserved: 0 };

    tx.set(existingRef, {
      uid,
      productId,
      planId: String(mapping.planId || ""),
      productType: mapping.productType,
      purchaseToken,
      verified: true,
      state: mapping.productType === "subs" ? subscriptionState : "PURCHASED",
      verifiedAt: FieldValue.serverTimestamp(),
      rawState: verified
    }, { merge: true });

    if (mapping.planId) {
      tx.set(entitlementRef, {
        uid,
        planId: String(mapping.planId),
        product: String(mapping.product || ""),
        source: "play",
        providerReference: purchaseToken,
        purchaseToken,
        state: mapping.productType === "subs" ? "active" : "active",
        renewalState: mapping.productType === "subs" ? subscriptionState : "non_renewing",
        updatedAt: FieldValue.serverTimestamp()
      }, { merge: true });
    }

    if (creditGrant > 0) {
      tx.set(balanceRef, {
        available: Number(balance.available || 0) + creditGrant,
        reserved: Number(balance.reserved || 0),
        updatedAt: FieldValue.serverTimestamp()
      }, { merge: true });
      tx.create(db.collection("creditLedger").doc(), {
        uid,
        amount: creditGrant,
        kind: "play_purchase_grant",
        providerId: "google_play",
        providerReference: purchaseToken,
        createdAt: FieldValue.serverTimestamp()
      });
    }
  });

  if (mapping.productType === "subs") {
    await playApi("purchases/subscriptions/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":acknowledge", "POST", {});
  } else if (mapping.consumable === true) {
    await playApi("purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":consume", "POST", {});
  } else {
    await playApi("purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":acknowledge", "POST", {});
  }

  return { ok: true, alreadyProcessed: false, state: "verified" };
}
