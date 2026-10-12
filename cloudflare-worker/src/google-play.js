import { COMMERCIAL_CATALOG, canonicalPlayMappings } from "./plan-catalog-native.js";
const PACKAGE_NAME = "com.bqsqwad.ai";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const PLAY_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE_NAME;

function b64urlBytes(bytes) {
  let binary = "";
  const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  for (let i = 0; i < arr.length; i += 0x8000) binary += String.fromCharCode(...arr.subarray(i, i + 0x8000));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function b64urlText(value) {
  return b64urlBytes(new TextEncoder().encode(value));
}
function pemToBytes(pem) {
  const base64 = String(pem).replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

let tokenCache = { token: null, exp: 0 };

async function accessToken(env) {
  const now = Math.floor(Date.now() / 1000);
  if (tokenCache.token && tokenCache.exp > now + 60) return tokenCache.token;
  const raw = env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON;
  if (!raw) throw new Error("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_not_configured");
  const account = JSON.parse(raw);
  if (!account.client_email || !account.private_key) throw new Error("invalid_google_play_service_account");
  const header = b64urlText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64urlText(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: TOKEN_URL,
    iat: now,
    exp: now + 3600
  }));
  const unsigned = header + "." + claim;
  const key = await crypto.subtle.importKey("pkcs8", pemToBytes(account.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: unsigned + "." + b64urlBytes(signature) })
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || !body.access_token) throw new Error("google_play_access_token_failed");
  tokenCache = { token: String(body.access_token), exp: now + Number(body.expires_in || 3600) };
  return tokenCache.token;
}

async function playApi(env, path, method = "GET", body) {
  const response = await fetch(PLAY_BASE + "/" + path, {
    method,
    headers: { Authorization: "Bearer " + await accessToken(env), "Content-Type": "application/json" },
    body: body == null ? undefined : JSON.stringify(body)
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error("google_play_http_" + response.status + ":" + String(payload.error?.message || "").slice(0, 700));
  return payload;
}

export async function playCatalog(firestore, context) {
  const [config, planConfig] = await Promise.all([
    firestore.get("playCatalog/config"),
    firestore.get("planCatalog/config")
  ]);
  const configuredProducts = Array.isArray(config?.products) ? config.products : [];
  const configuredPlanKeys = new Set(configuredProducts.map(p => String(p.planId || "") + "|" + String(p.basePlanId || "")));
  const storedCatalog = planConfig?.catalog;
  const hasStoredPlans = Object.values(storedCatalog?.sections || {}).some(rows => Array.isArray(rows) && rows.length > 0);
  const planMappings = canonicalPlayMappings(hasStoredPlans ? storedCatalog : COMMERCIAL_CATALOG);
  const mergedProducts = [
    ...configuredProducts,
    ...planMappings.filter(p => !configuredPlanKeys.has(String(p.planId || "") + "|" + String(p.basePlanId || "")))
  ];
  const organizationContext = String(context || "").trim().toLowerCase() === "organization";
  const products = mergedProducts
    .filter(p => (organizationContext ? p.organizationWalletTopup === true : p.organizationWalletTopup !== true))
    .map(p => ({
      planId: String(p.planId || ""), productId: String(p.productId || ""),
      basePlanId: p.basePlanId ? String(p.basePlanId) : null, offerId: p.offerId ? String(p.offerId) : null,
      productType: String(p.productType || ""), enabled: p.enabled === true, consumable: p.consumable === true,
      trialOfferId: p.trialOfferId ? String(p.trialOfferId) : null, trialDays: Number(p.trialDays || 0),
      billingPeriod: String(p.billingPeriod || ""), priceUsd: p.priceUsd == null ? null : Number(p.priceUsd), organizationWalletTopup: p.organizationWalletTopup === true, organizationAmountCents: Math.max(0, Number(p.organizationAmountCents || (p.organizationWalletTopup ? Number(p.priceUsd || 0) * 100 : 0)))
    }))
    .filter(p => p.productId && /^[a-z0-9-]+$/.test(p.productId) && (p.productType === "subs" || p.productType === "inapp"));
  return { version: Number(config?.version || 3), products };
}

async function finalizePurchaseSideEffects(env, firestore, eventId, event) {
  const writes = [];
  if (event.receiptPath && event.receipt && !(await firestore.get(event.receiptPath))) {
    writes.push({ path: event.receiptPath, data: event.receipt, create: true });
  }
  if (event.paymentPath && event.payment && !(await firestore.get(event.paymentPath))) {
    writes.push({ path: event.paymentPath, data: event.payment, create: true });
  }
  if (writes.length) await firestore.commit(writes);
  if (event.acknowledged === true) return;
  const productId = String(event.productId || "");
  const purchaseToken = String(event.purchaseToken || "");
  try {
    if (event.productType === "subs") {
      await playApi(env, "purchases/subscriptions/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":acknowledge", "POST", {});
    } else if (event.consumable === true) {
      await playApi(env, "purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":consume", "POST", {});
    } else {
      await playApi(env, "purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":acknowledge", "POST", {});
    }
  } catch (error) {
    if (!/already acknowledged|already consumed|already been acknowledged|already been consumed/i.test(String(error?.message || ""))) throw error;
  }
  const updated = { ...event, acknowledged: true, acknowledgedAt: new Date() };
  delete updated.__name;
  await firestore.commit([{ path: "playPurchaseEvents/" + eventId, data: updated }]);
}

function subscriptionPeriodFromBasePlan(basePlanId) {
  const id = String(basePlanId || "").toLowerCase();
  if (/year|annual/.test(id)) return "yearly";
  if (/week/.test(id)) return "weekly";
  if (/term|semester|quarter/.test(id)) return "term";
  return "monthly";
}

export async function verifyPlayPurchase(env, firestore, input) {
  const { uid, productId, purchaseToken, obfuscatedAccountId, requestedBasePlanId, requestedOfferId } = input;
  if (!uid || !productId || !purchaseToken) throw new Error("play_purchase_fields_required");
  if (obfuscatedAccountId) {
    const expected = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(uid)));
    if (b64urlBytes(expected) !== String(obfuscatedAccountId).replace(/=+$/g, "")) {
      const hex = Array.from(new Uint8Array(expected)).map(x => x.toString(16).padStart(2, "0")).join("");
      if (String(obfuscatedAccountId) !== hex) throw new Error("play_account_mismatch");
    }
  }

  const [catalog, planCatalog] = await Promise.all([
    firestore.get("playCatalog/config"),
    firestore.get("planCatalog/config")
  ]);
  const configuredProducts = Array.isArray(catalog?.products) ? catalog.products : [];
  const configuredPlanKeys = new Set(configuredProducts.map(p => String(p.planId || "") + "|" + String(p.basePlanId || "")));
  const storedCatalog = planCatalog?.catalog;
  const hasStoredPlans = Object.values(storedCatalog?.sections || {}).some(rows => Array.isArray(rows) && rows.length > 0);
  const planMappings = canonicalPlayMappings(hasStoredPlans ? storedCatalog : COMMERCIAL_CATALOG);
  const products = [
    ...configuredProducts,
    ...planMappings.filter(p => !configuredPlanKeys.has(String(p.planId || "") + "|" + String(p.basePlanId || "")))
  ];
  const mappings = products.filter(p => String(p.productId || "") === String(productId) && p.enabled !== false);
  if (!mappings.length) throw new Error("play_product_not_configured");
  const initialMapping = mappings.find(p => requestedBasePlanId && String(p.basePlanId || "") === String(requestedBasePlanId)) || mappings[0];

  const purchaseId = await sha256Hex(purchaseToken);
  const existing = await firestore.get("playPurchases/" + purchaseId);
  if (existing?.uid && String(existing.uid) !== String(uid)) throw new Error("play_purchase_owned_by_another_account");
  if (existing?.verified === true && existing.productType !== "subs") {
    const existingEventId = String(existing.cycleId || purchaseId);
    const existingEvent = await firestore.get("playPurchaseEvents/" + existingEventId);
    if (existingEvent) await finalizePurchaseSideEffects(env, firestore, existingEventId, existingEvent);
    return { ok: true, alreadyProcessed: true, state: existing.state || "verified" };
  }

  // Subscription tokens are reused across renewals. Verify the latest line item
  // before deciding whether this billing cycle has already been granted.
  const verified = initialMapping.productType === "subs"
    ? await playApi(env, "purchases/subscriptionsv2/tokens/" + encodeURIComponent(purchaseToken))
    : await playApi(env, "purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken));
  const subscriptionState = initialMapping.productType === "subs" ? String(verified.subscriptionState || "") : "";
  const purchaseState = initialMapping.productType === "inapp" ? Number(verified.purchaseState ?? -1) : 0;
  const lineItem = initialMapping.productType === "subs" ? (verified.lineItems?.[0] || {}) : {};
  const actualBasePlanId = String(lineItem.offerDetails?.basePlanId || "");
  if (requestedBasePlanId && actualBasePlanId && String(requestedBasePlanId) !== actualBasePlanId) throw new Error("play_base_plan_mismatch");

  let mapping = initialMapping;
  if (initialMapping.productType === "subs") {
    const resolvedBasePlanId = actualBasePlanId || String(requestedBasePlanId || "");
    if (resolvedBasePlanId) {
      const exact = mappings.find(p => String(p.basePlanId || "") === resolvedBasePlanId);
      if (exact) mapping = exact;
      else throw new Error("play_base_plan_mismatch");
    } else if (new Set(mappings.map(p => String(p.planId || ""))).size > 1) {
      throw new Error("play_base_plan_unresolved");
    }
  }

  const verifiedBasePlanId = actualBasePlanId || String(mapping.basePlanId || "");
  const verifiedOfferId = String(lineItem.offerDetails?.offerId || requestedOfferId || "");
  const cycleReference = mapping.productType === "subs"
    ? String(lineItem.latestSuccessfulOrderId || verified.latestOrderId || lineItem.expiryTime || "")
    : purchaseToken;
  if (!cycleReference) throw new Error("play_subscription_cycle_unresolved");
  const cycleId = mapping.productType === "subs"
    ? await sha256Hex(purchaseToken + "|" + cycleReference)
    : purchaseId;
  const eventPath = "playPurchaseEvents/" + cycleId;
  const existingEvent = await firestore.get(eventPath);
  if (existingEvent) {
    await finalizePurchaseSideEffects(env, firestore, cycleId, existingEvent);
    return { ok: true, alreadyProcessed: true, state: existingEvent.state || "verified" };
  }

  // Migrate older verified subscription records without re-granting their current
  // cycle. Once migrated, later renewal order IDs can be granted independently.
  if (existing?.verified === true && !existing.cycleId && mapping.productType === "subs") {
    const oldEntitlement = await firestore.get("entitlements/" + uid);
    const previousExpiry = existing.expiryTime || oldEntitlement?.endDate || null;
    const currentExpiry = lineItem.expiryTime || null;
    const previousTime = previousExpiry ? Date.parse(String(previousExpiry)) : NaN;
    const currentTime = currentExpiry ? Date.parse(String(currentExpiry)) : NaN;
    if (!Number.isFinite(previousTime) || !Number.isFinite(currentTime) || previousTime === currentTime) {
      const migrated = { ...existing, cycleId, cycleReference, expiryTime: currentExpiry, updatedAt: new Date() };
      delete migrated.__name;
      await firestore.commit([
        { path: "playPurchases/" + purchaseId, data: migrated },
        { path: eventPath, data: { uid, purchaseId, productId, productType: "subs", cycleId, cycleReference, state: existing.state || "verified", verified: true, acknowledged: true, legacyMigrated: true }, create: true }
      ]);
      return { ok: true, alreadyProcessed: true, state: existing.state || "verified", migrated: true };
    }
  }
  if (existing?.verified === true && mapping.productType === "subs" && String(existing.cycleId || "") === cycleId) {
    const migrated = { ...existing, cycleId, cycleReference, expiryTime: lineItem.expiryTime || null, updatedAt: new Date() };
    delete migrated.__name;
    await firestore.commit([
      { path: "playPurchases/" + purchaseId, data: migrated },
      { path: eventPath, data: { uid, purchaseId, productId, productType: "subs", cycleId, cycleReference, state: existing.state || "verified", verified: true, acknowledged: true, legacyMigrated: true }, create: true }
    ]);
    return { ok: true, alreadyProcessed: true, state: existing.state || "verified", migrated: true };
  }

  const storedPlans = Object.values(planCatalog?.catalog?.sections || {}).flat().filter(Boolean);
  const configuredPlan = storedPlans.find(p => String(p.id || "") === String(mapping.planId || ""));
  if (configuredPlan?.active === false) throw new Error("play_plan_deactivated");
  const valid = mapping.productType === "subs"
    ? ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"].includes(subscriptionState)
    : purchaseState === 0;
  if (!valid) throw new Error("play_purchase_not_active");

  const creditGrant = Math.max(0, Number(mapping.creditGrant || 0));
  const profile = await firestore.get("users/" + uid);
  if (!profile) throw new Error("Account not found.");
  const balance = await firestore.get("creditBalances/" + uid);
  const entitlement = await firestore.get("entitlements/" + uid);
  const expiryTime = lineItem.expiryTime ? new Date(lineItem.expiryTime) : null;
  const period = subscriptionPeriodFromBasePlan(verifiedBasePlanId);
  const paymentAmount = Number(mapping.amount || mapping.price || 0);
  const paymentCurrency = String(mapping.currency || "USD");
  const paymentReference = "GOOGLEPLAY-" + cycleId;
  const purchaseData = {
    uid, productId, planId: String(mapping.planId || ""), productType: mapping.productType,
    basePlanId: verifiedBasePlanId || null, offerId: verifiedOfferId || null, purchaseToken,
    cycleId, cycleReference, expiryTime: lineItem.expiryTime || null,
    verified: true, state: mapping.productType === "subs" ? subscriptionState : "PURCHASED",
    verifiedAt: new Date(), rawState: verified
  };
  const entitlementData = {
    ...(entitlement || {}),
    uid, productId, planId: String(mapping.planId || ""), product: String(mapping.product || productId),
    basePlanId: verifiedBasePlanId || null, offerId: verifiedOfferId || null,
    source: "play", providerReference: purchaseToken, purchaseToken,
    state: "active",
    renewalState: mapping.productType === "subs" ? subscriptionState : "non_renewing",
    autoRenewEnabled: mapping.productType === "subs" ? lineItem.autoRenewingPlan?.autoRenewEnabled !== false : false,
    startDate: mapping.productType === "subs" && verified.startTime ? new Date(verified.startTime) : new Date(),
    endDate: mapping.productType === "subs" && lineItem.expiryTime ? new Date(lineItem.expiryTime) : null,
    updatedAt: new Date()
  };
  const writes = [
    { path: "playPurchases/" + purchaseId, data: purchaseData },
    { path: "entitlements/" + uid, data: entitlementData }
  ];
  if (mapping.productType === "subs" && creditGrant <= 0) {
    writes.push({
      path: "users/" + uid,
      data: {
        ...profile,
        uid,
        subscriptionPlan: String(mapping.planId || productId),
        subscriptionPeriod: period,
        ...(expiryTime ? { subExpiresAt: expiryTime } : {}),
        updatedAt: new Date()
      }
    });
  }
  if (creditGrant > 0) {
    const isSubscription = mapping.productType === "subs";
    const liveSubCredits = isSubscription && profile.subExpiresAt && Date.parse(String(profile.subExpiresAt)) > Date.now()
      ? Number(profile.subCredits || 0)
      : 0;
    const nextPermanent = Number(profile.credits || 0) + (isSubscription ? 0 : creditGrant);
    const nextSubCredits = liveSubCredits + (isSubscription ? creditGrant : 0);
    writes.push({
      path: "users/" + uid,
      data: {
        ...profile,
        uid,
        credits: nextPermanent,
        subCredits: nextSubCredits,
        ...(isSubscription ? {
          subscriptionPlan: String(mapping.planId || productId),
          subscriptionPeriod: period,
          ...(expiryTime ? { subExpiresAt: expiryTime } : {})
        } : {}),
        updatedAt: new Date()
      }
    });
    writes.push({
      path: "creditBalances/" + uid,
      data: {
        ...(balance || { uid, available: 0, reserved: 0 }),
        uid,
        available: Number(balance?.available || 0) + creditGrant,
        reserved: Number(balance?.reserved || 0),
        updatedAt: new Date()
      }
    });
    writes.push({
      path: "creditTransactions/" + crypto.randomUUID(),
      data: {
        uid, type: "purchase", feature: "google-play", credits: creditGrant,
        balanceAfter: nextPermanent + nextSubCredits, amount: paymentAmount,
        currency: paymentCurrency, reference: cycleId,
        note: "Google Play purchase", reason: "Verified Google Play purchase", createdAt: new Date()
      },
      create: true
    });
    writes.push({
      path: "creditLedger/" + crypto.randomUUID(),
      data: { uid, amount: creditGrant, kind: "play_purchase_grant", providerId: "google_play", providerReference: cycleId, createdAt: new Date() },
      create: true
    });
  }
  if (mapping.organizationWalletTopup === true) {
    const amountCents = Math.floor(Number(mapping.organizationAmountCents || 0));
    if (amountCents <= 0) throw new Error("organization_wallet_topup_amount_invalid");
    const wallet = await firestore.get("organizationWallets/" + uid);
    const before = Math.max(0, Number(wallet?.balanceCents || 0));
    const after = before + amountCents;
    writes.push({ path: "organizationWallets/" + uid, data: { uid, balanceCents: after, currency: "USD", status: "active", updatedAt: new Date() } });
    writes.push({
      path: "organizationWalletLedger/" + crypto.randomUUID(),
      data: { uid, type: "deposit", amountCents, balanceBeforeCents: before, balanceAfterCents: after, currency: "USD", productId, provider: "google_play", providerReference: cycleId, note: "Organization usage balance deposit", createdAt: new Date() },
      create: true
    });
  }

  const receiptPath = "billingReceipts/" + paymentReference + "-subscription";
  const paymentPath = "payments/" + paymentReference;
  const receipt = {
    uid, module: String(mapping.product || "general"), planName: String(mapping.name || mapping.planId || productId),
    cycle: mapping.productType === "subs" ? period : "one_time", amount: paymentAmount,
    currency: paymentCurrency, reference: paymentReference,
    kind: mapping.productType === "subs" ? "subscription" : "credit_purchase",
    status: "paid", provider: "google_play", productId, basePlanId: verifiedBasePlanId || null, createdAt: new Date()
  };
  const payment = {
    uid, email: String(profile.email || ""), amount: paymentAmount, currency: paymentCurrency,
    kind: String(mapping.product || "general"), provider: "google_play", reference: paymentReference,
    productId, planId: String(mapping.planId || ""), basePlanId: verifiedBasePlanId || null, createdAt: new Date()
  };
  const event = {
    uid, purchaseId, productId, productType: mapping.productType, purchaseToken,
    cycleId, cycleReference, expiryTime: lineItem.expiryTime || null,
    state: mapping.productType === "subs" ? subscriptionState : "PURCHASED",
    consumable: mapping.consumable === true, verified: true, acknowledged: false,
    receiptPath, paymentPath, receipt, payment, createdAt: new Date()
  };
  writes.push({ path: eventPath, data: event, create: true });
  await firestore.commit(writes);
  await finalizePurchaseSideEffects(env, firestore, cycleId, event);
  return { ok: true, alreadyProcessed: false, state: "verified" };
}

async function sha256Hex(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(value)));
  return Array.from(new Uint8Array(digest)).map(x => x.toString(16).padStart(2, "0")).join("");
}
