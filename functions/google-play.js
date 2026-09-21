import { restoreServicesAfterRenewal, deactivateExpiredUserServices } from "./capability-runtime.js";
import crypto from "node:crypto";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { canonicalPlayProducts } from "./play-catalog.js";

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

export async function verifyPlayPurchase({ uid, productId, purchaseToken, obfuscatedAccountId }) {
  if (!uid || !productId || !purchaseToken) throw new Error("play_purchase_fields_required");
  if (obfuscatedAccountId) {
    const expected = crypto.createHash("sha256").update(String(uid)).digest("hex");
    if (String(obfuscatedAccountId) !== expected) throw new Error("play_account_mismatch");
  }
  const db = getFirestore();
  const catalogSnap = await db.collection("playCatalog").doc("config").get();
  const products = catalogSnap.exists && Array.isArray(catalogSnap.data()?.products) && catalogSnap.data().products.length
    ? catalogSnap.data().products
    : canonicalPlayProducts();
  const mapping = products.find(p => p.productId === productId && p.enabled === true);
  if (!mapping) throw new Error("play_product_not_configured");

  const existingRef = db.collection("playPurchases").doc(crypto.createHash("sha256").update(purchaseToken).digest("hex"));
  const existing = await existingRef.get();
  if (existing.exists && existing.data()?.uid !== uid) throw new Error("play_purchase_owned_by_another_account");
  if (existing.exists && existing.data()?.uid === uid && existing.data()?.verified === true) {
    return { ok: true, alreadyProcessed: true, state: existing.data()?.state || "verified" };
  }
  if (mapping.planId) {
    const entitlementSnap = await db.collection("entitlements").doc(uid).get();
    const entitlement = entitlementSnap.exists ? entitlementSnap.data() : null;
    const existingPlan = entitlement?.planId ? String(entitlement.planId) : "";
    const existingState = entitlement?.state ? String(entitlement.state) : "";
    if (existingPlan && existingPlan === String(mapping.planId) && ["active","grace","on_hold","paused"].includes(existingState) && entitlement?.source === "legacy") {
      throw new Error("legacy_entitlement_already_active");
    }
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

    const entitlementRef = db.collection("entitlements").doc(uid);
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
        startDate: mapping.productType === "subs" && verified.startTime ? new Date(verified.startTime) : FieldValue.serverTimestamp(),
        endDate: mapping.productType === "subs" && verified.lineItems?.[0]?.expiryTime ? new Date(verified.lineItems[0].expiryTime) : null,
        allowance: mapping.allowance ?? null,
        remainingAllowance: mapping.allowance ?? null,
        creditBalance: creditGrant,
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

  await restoreServicesAfterRenewal(db, uid);
  return { ok: true, alreadyProcessed: false, state: "verified" };
}


export async function processPlayRtdn(event) {
  const message = event?.data?.message;
  if (!message?.data) return { ok: true, ignored: true };
  const eventId = String(event.id || message.messageId || "");
  const db = getFirestore();
  if (eventId) {
    const eventRef = db.collection("playRtdnEvents").doc(eventId);
    if ((await eventRef.get()).exists) return { ok: true, duplicate: true };
    await eventRef.create({ receivedAt: FieldValue.serverTimestamp() });
  }
  const decoded = JSON.parse(Buffer.from(message.data, "base64").toString("utf8"));
  const notification = decoded.subscriptionNotification;
  if (!notification?.purchaseToken || !notification?.subscriptionId) return { ok: true, ignored: true };
  const purchaseRef = db.collection("playPurchases").doc(crypto.createHash("sha256").update(String(notification.purchaseToken)).digest("hex"));
  const purchase = await purchaseRef.get();
  if (!purchase.exists || !purchase.data()?.uid) return { ok: true, pendingPurchase: true };
  const verified = await playApi("purchases/subscriptionsv2/tokens/" + encodeURIComponent(notification.purchaseToken));
  const state = String(verified.subscriptionState || "SUBSCRIPTION_STATE_UNSPECIFIED");
  const normalized = state === "SUBSCRIPTION_STATE_ACTIVE" ? "active" : state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" ? "grace" : state === "SUBSCRIPTION_STATE_ON_HOLD" ? "on_hold" : state === "SUBSCRIPTION_STATE_PAUSED" ? "paused" : state === "SUBSCRIPTION_STATE_CANCELED" ? "cancelled" : state === "SUBSCRIPTION_STATE_EXPIRED" ? "expired" : "pending";
  const uid = String(purchase.data().uid);
  const endDate = verified.lineItems?.[0]?.expiryTime ? new Date(verified.lineItems[0].expiryTime) : null;
  await db.collection("entitlements").doc(uid).set({
    state: normalized,
    renewalState: state,
    source: "play",
    providerReference: notification.purchaseToken,
    endDate,
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });
  if (["active", "grace", "on_hold", "paused"].includes(normalized)) {
    await restoreServicesAfterRenewal(db, uid);
  } else if (normalized === "expired" || (normalized === "cancelled" && endDate && endDate.getTime() <= Date.now())) {
    await deactivateExpiredUserServices(db, uid);
  }
  return { ok: true, state: normalized };
}