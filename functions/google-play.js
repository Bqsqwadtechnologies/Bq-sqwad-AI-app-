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

function billingPeriodFromBasePlan(basePlanId) {
  const value = String(basePlanId || "").toLowerCase();
  return value.includes("year") ? "yearly" : value.includes("term") ? "term" : "monthly";
}

function playPurchaseId(purchaseToken) {
  return crypto.createHash("sha256").update(String(purchaseToken)).digest("hex");
}

function playRenewalId(purchaseToken, orderId, eventId) {
  const source = String(purchaseToken) + ":" + String(orderId || eventId || "unknown");
  return crypto.createHash("sha256").update(source).digest("hex");
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

export async function verifyPlayPurchase({ uid, productId, purchaseToken, obfuscatedAccountId, requestedBasePlanId, requestedOfferId }) {
  if (!uid || !productId || !purchaseToken) throw new Error("play_purchase_fields_required");
  if (obfuscatedAccountId) {
    const expected = crypto.createHash("sha256").update(String(uid)).digest("hex");
    if (String(obfuscatedAccountId) !== expected) throw new Error("play_account_mismatch");
  }
  const db = getFirestore();
  const catalogSnap = await db.collection("playCatalog").doc("config").get();
  const configuredProducts = catalogSnap.exists && Array.isArray(catalogSnap.data()?.products) ? catalogSnap.data().products : [];
  const canonicalProducts = canonicalPlayProducts();
  const configuredIds = new Set(configuredProducts.map(p => String(p.productId || "")));
  const products = [
    ...configuredProducts,
    ...canonicalProducts.filter(p => !configuredIds.has(String(p.productId || "")) && p.organizationWalletTopup === true)
  ];
  const productMappings = products.filter(p => p.productId === productId && p.enabled === true);
  if (!productMappings.length) throw new Error("play_product_not_configured");

  // The admin plan catalog is authoritative for commercial availability.
  // A stale Play projection must never reopen a plan that the admin deactivated.
  const planCatalogSnap = await db.collection("planCatalog").doc("config").get();
  if (planCatalogSnap.exists) {
    const sections = planCatalogSnap.data()?.catalog?.sections || {};
    const allPlans = Object.values(sections).flat().filter(Boolean);
    const plan = allPlans.find(p => String(p.id || "") === String(productMappings[0].planId || ""));
    if (plan && plan.active === false) throw new Error("play_plan_deactivated");
  }
  const requestedMapping = productMappings.find(p => !requestedBasePlanId || String(p.basePlanId || "") === String(requestedBasePlanId)) || productMappings[0];

  const existingRef = db.collection("playPurchases").doc(crypto.createHash("sha256").update(purchaseToken).digest("hex"));
  const existing = await existingRef.get();
  if (existing.exists && existing.data()?.uid !== uid) throw new Error("play_purchase_owned_by_another_account");
  if (existing.exists && existing.data()?.uid === uid && existing.data()?.verified === true) {
    return { ok: true, alreadyProcessed: true, state: existing.data()?.state || "verified" };
  }
  if (requestedMapping.planId) {
    const entitlementSnap = await db.collection("entitlements").doc(uid).get();
    const entitlement = entitlementSnap.exists ? entitlementSnap.data() : null;
    const existingPlan = entitlement?.planId ? String(entitlement.planId) : "";
    const existingState = entitlement?.state ? String(entitlement.state) : "";
    if (existingPlan && existingPlan === String(requestedMapping.planId) && ["active","grace","on_hold","paused"].includes(existingState) && entitlement?.source === "legacy") {
      throw new Error("legacy_entitlement_already_active");
    }
  }

  const verified = requestedMapping.productType === "subs"
    ? await playApi("purchases/subscriptionsv2/tokens/" + encodeURIComponent(purchaseToken))
    : await playApi("purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken));

  const subscriptionState = requestedMapping.productType === "subs"
    ? String(verified.subscriptionState || "")
    : "";
  const purchaseState = requestedMapping.productType === "inapp"
    ? Number(verified.purchaseState ?? -1)
    : 0;
  const lineItem = requestedMapping.productType === "subs" ? (verified.lineItems?.[0] || {}) : {};
  const autoRenewEnabled = requestedMapping.productType === "subs"
    ? lineItem.autoRenewingPlan?.autoRenewEnabled !== false
    : false;
  const verifiedBasePlanId = String(lineItem.offerDetails?.basePlanId || requestedMapping.basePlanId || "");
  const verifiedOfferId = String(lineItem.offerDetails?.offerId || requestedOfferId || "");
  const mapping = productMappings.find(p => String(p.basePlanId || "") === verifiedBasePlanId) || requestedMapping;
  if (requestedBasePlanId && verifiedBasePlanId && String(requestedBasePlanId) !== verifiedBasePlanId) {
    throw new Error("play_base_plan_mismatch");
  }
  const valid = mapping.productType === "subs"
    ? ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"].includes(subscriptionState)
    : purchaseState === 0;
  if (!valid) throw new Error("play_purchase_not_active");

  await db.runTransaction(async tx => {
    const fresh = await tx.get(existingRef);
    if (fresh.exists && fresh.data()?.verified === true) return;
    const entitlementRef = db.collection("entitlements").doc(uid);
    const playEntitlementRef = db.collection("playEntitlements").doc(
      crypto.createHash("sha256").update(String(productId) + ":" + verifiedBasePlanId + ":" + String(purchaseToken)).digest("hex")
    );
    const userRef = db.collection("users").doc(uid);
    const balanceRef = db.collection("creditBalances").doc(uid);
    const userSnap = await tx.get(userRef);
    if (!userSnap.exists) throw new Error("Account not found.");
    const profile = userSnap.data() || {};
    const balanceSnap = await tx.get(balanceRef);
    const balance = balanceSnap.exists ? balanceSnap.data() : { available: 0, reserved: 0 };
    const creditGrant = Math.max(0, Number(mapping.creditGrant || 0));
    const quantity = Math.max(1, Number(mapping.quantity || 1));
    const isSubscription = mapping.productType === "subs";
    const expiryDate = isSubscription && verified.lineItems?.[0]?.expiryTime ? new Date(verified.lineItems[0].expiryTime) : null;
    const period = billingPeriodFromBasePlan(verifiedBasePlanId);
    const latestSuccessfulOrderId = String(verified.lineItems?.[0]?.latestSuccessfulOrderId || verified.latestOrderId || "");
    const currentPermanent = Math.max(0, Number(profile.credits || 0));
    const currentSub = Math.max(0, Number(profile.subCredits || 0));
    const liveSub = isSubscription && profile.subExpiresAt && Date.parse(String(profile.subExpiresAt)) > Date.now() ? currentSub : 0;
    const nextPermanent = currentPermanent + (isSubscription ? 0 : creditGrant);
    const nextSub = liveSub + (isSubscription ? creditGrant : 0);

    tx.set(existingRef, {
      uid, productId, planId: String(mapping.planId || ""), productType: mapping.productType,
      basePlanId: verifiedBasePlanId || null, offerId: verifiedOfferId || null, purchaseToken,
      verified: true, state: isSubscription ? subscriptionState : "PURCHASED",
      verifiedAt: FieldValue.serverTimestamp(), latestSuccessfulOrderId: latestSuccessfulOrderId || null, rawState: verified
    }, { merge: true });

    if (mapping.planId) {
      const entitlementData = {
        uid, productId, planId: String(mapping.planId || ""), product: String(mapping.product || productId),
        basePlanId: verifiedBasePlanId || null, offerId: verifiedOfferId || null, tierId: mapping.tierId || null,
        quantity, source: "play", providerReference: purchaseToken, purchaseToken, state: "active",
        renewalState: isSubscription ? subscriptionState : "non_renewing",
        autoRenewEnabled: isSubscription ? autoRenewEnabled : false,
        startDate: isSubscription && verified.startTime ? new Date(verified.startTime) : FieldValue.serverTimestamp(),
        endDate: expiryDate, allowance: mapping.allowance ?? null, remainingAllowance: mapping.allowance ?? null,
        creditBalance: creditGrant, updatedAt: FieldValue.serverTimestamp()
      };
      tx.set(entitlementRef, entitlementData, { merge: true });
      tx.set(playEntitlementRef, entitlementData, { merge: true });
    }

    tx.set(userRef, {
      uid,
      ...(isSubscription ? {
        credits: nextPermanent, subCredits: nextSub, subscriptionPlan: String(mapping.planId || productId),
        subscriptionPeriod: period, ...(expiryDate ? { subExpiresAt: expiryDate } : {})
      } : { credits: nextPermanent }),
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });

    if (creditGrant > 0) {
      tx.set(balanceRef, {
        uid, available: Number(balance.available || 0) + creditGrant,
        reserved: Number(balance.reserved || 0), updatedAt: FieldValue.serverTimestamp()
      }, { merge: true });
      tx.create(db.collection("creditLedger").doc(), {
        uid, amount: creditGrant, kind: "play_purchase_grant", providerId: "google_play",
        providerReference: purchaseToken, createdAt: FieldValue.serverTimestamp()
      });
      tx.create(db.collection("creditTransactions").doc(), {
        uid, type: "purchase", feature: "google-play", credits: creditGrant,
        balanceAfter: nextPermanent + nextSub,
        amount: Number(mapping.priceUsd || mapping.amount || mapping.price || 0),
        currency: String(mapping.currency || "USD"), reference: purchaseToken,
        note: "Google Play purchase", reason: "Verified Google Play purchase",
        createdAt: FieldValue.serverTimestamp()
      });
    }

    const paymentAmount = Number(mapping.priceUsd || mapping.amount || mapping.price || 0);
    const paymentCurrency = String(mapping.currency || "USD");
    const paymentReference = "GOOGLEPLAY-" + playPurchaseId(purchaseToken);
    tx.create(db.collection("billingReceipts").doc(paymentReference), {
      uid, module: String(mapping.product || "general"), planName: String(mapping.name || mapping.planId || productId),
      cycle: isSubscription ? period : "one_time", amount: paymentAmount, currency: paymentCurrency,
      reference: paymentReference, kind: walletTopupCents > 0 ? "organization_wallet_deposit" : (isSubscription ? "subscription" : "credit_purchase"),
      status: "paid", provider: "google_play", productId, planId: String(mapping.planId || ""),
      basePlanId: verifiedBasePlanId || null, createdAt: FieldValue.serverTimestamp()
    });
    tx.create(db.collection("payments").doc(paymentReference), {
      uid, email: String(profile.email || ""), amount: paymentAmount, currency: paymentCurrency,
      kind: String(mapping.product || "general"), provider: "google_play", reference: paymentReference,
      productId, planId: String(mapping.planId || ""), basePlanId: verifiedBasePlanId || null,
      createdAt: FieldValue.serverTimestamp()
    });
  });

  if (mapping.productType === "subs") {
    await playApi("purchases/subscriptions/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":acknowledge", "POST", {});
  } else if (mapping.consumable === true) {
    await playApi("purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":consume", "POST", {});
  } else {
    await playApi("purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":acknowledge", "POST", {});
  }

  await restoreServicesAfterRenewal(db, uid);
  return { ok: true, alreadyProcessed: false, state: "verified", organizationWalletTopupCents: Number(mapping.walletTopupCents || 0) };
}


export async function processPlayRtdn(event) {
  const message = event?.data?.message;
  if (!message?.data) return { ok: true, ignored: true };
  const eventId = String(event.id || message.messageId || "");
  const db = getFirestore();
  if (eventId) {
    const eventRef = db.collection("playRtdnEvents").doc(eventId);
    if ((await eventRef.get()).exists) return { ok: true, duplicate: true };
    await eventRef.create({ messageId: eventId, receivedAt: FieldValue.serverTimestamp() });
  }

  const decoded = JSON.parse(Buffer.from(message.data, "base64").toString("utf8"));
  if (decoded.packageName && String(decoded.packageName) !== PACKAGE_NAME) return { ok: true, ignored: true, reason: "package_mismatch" };
  const notification = decoded.subscriptionNotification;
  if (!notification?.purchaseToken) {
    return { ok: true, ignored: true, type: decoded.testNotification ? "test" : decoded.oneTimeProductNotification ? "one_time" : decoded.voidedPurchaseNotification ? "voided" : "unsupported" };
  }

  const purchaseToken = String(notification.purchaseToken);
  const purchaseRef = db.collection("playPurchases").doc(playPurchaseId(purchaseToken));
  const purchase = await purchaseRef.get();
  if (!purchase.exists || !purchase.data()?.uid) return { ok: true, pendingPurchase: true };

  const uid = String(purchase.data().uid);
  const verified = await playApi("purchases/subscriptionsv2/tokens/" + encodeURIComponent(purchaseToken));
  const state = String(verified.subscriptionState || "SUBSCRIPTION_STATE_UNSPECIFIED");
  const normalized = state === "SUBSCRIPTION_STATE_ACTIVE" ? "active"
    : state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" ? "grace"
    : state === "SUBSCRIPTION_STATE_ON_HOLD" ? "on_hold"
    : state === "SUBSCRIPTION_STATE_PAUSED" ? "paused"
    : state === "SUBSCRIPTION_STATE_CANCELED" ? "cancelled"
    : state === "SUBSCRIPTION_STATE_EXPIRED" ? "expired" : "pending";

  const lineItem = verified.lineItems?.find(x => !notification.subscriptionId || String(x.productId || "") === String(notification.subscriptionId)) || verified.lineItems?.[0] || {};
  const productId = String(lineItem.productId || notification.subscriptionId || purchase.data()?.productId || "");
  const verifiedBasePlanId = String(lineItem.offerDetails?.basePlanId || purchase.data()?.basePlanId || "");
  const autoRenewEnabled = lineItem.autoRenewingPlan?.autoRenewEnabled === true;
  const endDate = lineItem.expiryTime ? new Date(lineItem.expiryTime) : null;
  const latestSuccessfulOrderId = String(lineItem.latestSuccessfulOrderId || verified.latestOrderId || "");
  const notificationType = Number(notification.notificationType || 0);

  const catalogSnap = await db.collection("playCatalog").doc("config").get();
  const catalogProducts = catalogSnap.exists && Array.isArray(catalogSnap.data()?.products) && catalogSnap.data().products.length ? catalogSnap.data().products : canonicalPlayProducts();
  const mappings = catalogProducts.filter(p => String(p.productId || "") === productId && p.enabled === true);
  const mapping = mappings.find(p => !verifiedBasePlanId || String(p.basePlanId || "") === verifiedBasePlanId) || mappings[0] || null;
  const planId = String(mapping?.planId || purchase.data()?.planId || "");
  const creditGrant = Math.max(0, Number(mapping?.creditGrant || 0));
  const isRenewal = notificationType === 2;
  const renewalId = playRenewalId(purchaseToken, latestSuccessfulOrderId, eventId);
  let renewalProcessed = false;

  await db.runTransaction(async tx => {
    const freshPurchase = await tx.get(purchaseRef);
    if (!freshPurchase.exists || String(freshPurchase.data()?.uid || "") !== uid) throw new Error("play_purchase_record_missing");
    const entitlementRef = db.collection("entitlements").doc(uid);
    const userRef = db.collection("users").doc(uid);
    const balanceRef = db.collection("creditBalances").doc(uid);
    const renewalRef = db.collection("playRenewals").doc(renewalId);
    const userSnap = await tx.get(userRef);
    const balanceSnap = await tx.get(balanceRef);
    const renewalSnap = await tx.get(renewalRef);
    const profile = userSnap.exists ? (userSnap.data() || {}) : {};
    const balance = balanceSnap.exists ? (balanceSnap.data() || {}) : { available: 0, reserved: 0 };
    const previousPurchase = freshPurchase.data() || {};
    const period = billingPeriodFromBasePlan(verifiedBasePlanId || previousPurchase.basePlanId);
    const shouldGrantRenewal = isRenewal && creditGrant > 0 && Boolean(latestSuccessfulOrderId) && !renewalSnap.exists && String(previousPurchase.lastSuccessfulOrderId || "") !== latestSuccessfulOrderId;
    renewalProcessed = shouldGrantRenewal;
    let nextPermanent = Math.max(0, Number(profile.credits || 0));
    let nextSub = Math.max(0, Number(profile.subCredits || 0));
    if (shouldGrantRenewal) {
      const subExpiresAt = profile.subExpiresAt ? Date.parse(String(profile.subExpiresAt)) : NaN;
      const liveSub = Number.isFinite(subExpiresAt) && subExpiresAt > Date.now() ? nextSub : 0;
      nextSub = liveSub + creditGrant;
    }

    tx.set(purchaseRef, {
      uid, productId, planId, productType: "subs", basePlanId: verifiedBasePlanId || previousPurchase.basePlanId || null,
      purchaseToken, verified: true, state,
      latestSuccessfulOrderId: latestSuccessfulOrderId || previousPurchase.lastSuccessfulOrderId || null,
      lastRtdnNotificationType: notificationType, lastRtdnEventId: eventId || null,
      lastRtdnAt: FieldValue.serverTimestamp(), rawState: verified
    }, { merge: true });

    tx.set(entitlementRef, {
      uid, productId, planId, product: String(mapping?.product || productId),
      basePlanId: verifiedBasePlanId || previousPurchase.basePlanId || null,
      offerId: lineItem.offerDetails?.offerId || null, source: "play",
      providerReference: purchaseToken, purchaseToken, state: normalized,
      renewalState: state, autoRenewEnabled, endDate, updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });

    const expiresAt = endDate ? endDate.getTime() : NaN;
    tx.set(userRef, {
      uid, subscriptionPlan: planId || String(profile.subscriptionPlan || "free"),
      subscriptionPeriod: period,
      subCredits: shouldGrantRenewal ? nextSub : (normalized === "expired" || (normalized === "cancelled" && Number.isFinite(expiresAt) && expiresAt <= Date.now()) ? 0 : nextSub),
      ...(endDate ? { subExpiresAt: endDate } : {}),
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });

    if (shouldGrantRenewal) {
      tx.create(renewalRef, {
        uid, purchaseToken, productId, planId, basePlanId: verifiedBasePlanId || null,
        orderId: latestSuccessfulOrderId, notificationType, creditGrant, processedAt: FieldValue.serverTimestamp()
      });
      tx.set(balanceRef, {
        uid, available: Number(balance.available || 0) + creditGrant,
        reserved: Number(balance.reserved || 0), updatedAt: FieldValue.serverTimestamp()
      }, { merge: true });
      tx.create(db.collection("creditLedger").doc(), {
        uid, amount: creditGrant, kind: "play_subscription_renewal", providerId: "google_play",
        providerReference: purchaseToken + ":" + latestSuccessfulOrderId, createdAt: FieldValue.serverTimestamp()
      });
      tx.create(db.collection("creditTransactions").doc(), {
        uid, type: "renewal", feature: "google-play", credits: creditGrant,
        balanceAfter: nextPermanent + nextSub,
        amount: Number(mapping?.priceUsd || mapping?.amount || mapping?.price || 0),
        currency: String(mapping?.currency || "USD"), reference: purchaseToken + ":" + latestSuccessfulOrderId,
        note: "Google Play subscription renewal", reason: "Verified Google Play renewal",
        createdAt: FieldValue.serverTimestamp()
      });
      const paymentReference = "GOOGLEPLAY-" + renewalId;
      tx.create(db.collection("billingReceipts").doc(paymentReference), {
        uid, module: String(mapping?.product || "general"), planName: String(mapping?.name || planId || productId),
        cycle: period, amount: Number(mapping?.priceUsd || mapping?.amount || mapping?.price || 0),
        currency: String(mapping?.currency || "USD"), reference: paymentReference, kind: "subscription_renewal",
        status: "paid", provider: "google_play", productId, planId,
        basePlanId: verifiedBasePlanId || null, orderId: latestSuccessfulOrderId || null,
        createdAt: FieldValue.serverTimestamp()
      });
      tx.create(db.collection("payments").doc(paymentReference), {
        uid, email: String(profile.email || ""), amount: Number(mapping?.priceUsd || mapping?.amount || mapping?.price || 0),
        currency: String(mapping?.currency || "USD"), kind: "subscription_renewal", provider: "google_play",
        reference: paymentReference, productId, planId, basePlanId: verifiedBasePlanId || null,
        orderId: latestSuccessfulOrderId || null, createdAt: FieldValue.serverTimestamp()
      });
    }
  });

  if (["active", "grace", "on_hold", "paused"].includes(normalized)) {
    await restoreServicesAfterRenewal(db, uid);
  } else if (normalized === "expired" || (normalized === "cancelled" && endDate && endDate.getTime() <= Date.now())) {
    await deactivateExpiredUserServices(db, uid);
  }
  return { ok: true, state: normalized, notificationType, renewalProcessed };
}\n