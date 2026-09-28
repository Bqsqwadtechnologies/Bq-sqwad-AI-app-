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

export async function playCatalog() {
  return null;
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

  const catalog = await firestore.get("playCatalog/config");
  const products = Array.isArray(catalog?.products) ? catalog.products : [];
  const mappings = products.filter(p => String(p.productId || "") === String(productId) && p.enabled !== false);
  if (!mappings.length) throw new Error("play_product_not_configured");
  const mapping = mappings.find(p => !requestedBasePlanId || String(p.basePlanId || "") === String(requestedBasePlanId)) || mappings[0];

  const planCatalog = await firestore.get("planCatalog/config");
  if (planCatalog) {
    const sections = planCatalog.catalog?.sections || {};
    const plans = Object.values(sections).flat().filter(Boolean);
    const plan = plans.find(p => String(p.id || "") === String(mapping.planId || ""));
    if (plan?.active === false) throw new Error("play_plan_deactivated");
  }

  const purchaseId = await sha256Hex(purchaseToken);
  const existing = await firestore.get("playPurchases/" + purchaseId);
  if (existing?.uid && String(existing.uid) !== String(uid)) throw new Error("play_purchase_owned_by_another_account");
  if (existing?.verified === true) return { ok: true, alreadyProcessed: true, state: existing.state || "verified" };

  const verified = mapping.productType === "subs"
    ? await playApi(env, "purchases/subscriptionsv2/tokens/" + encodeURIComponent(purchaseToken))
    : await playApi(env, "purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken));

  const subscriptionState = mapping.productType === "subs" ? String(verified.subscriptionState || "") : "";
  const purchaseState = mapping.productType === "inapp" ? Number(verified.purchaseState ?? -1) : 0;
  const lineItem = mapping.productType === "subs" ? (verified.lineItems?.[0] || {}) : {};
  const verifiedBasePlanId = String(lineItem.offerDetails?.basePlanId || mapping.basePlanId || "");
  const verifiedOfferId = String(lineItem.offerDetails?.offerId || requestedOfferId || "");
  if (requestedBasePlanId && verifiedBasePlanId && String(requestedBasePlanId) !== verifiedBasePlanId) throw new Error("play_base_plan_mismatch");

  const valid = mapping.productType === "subs"
    ? ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"].includes(subscriptionState)
    : purchaseState === 0;
  if (!valid) throw new Error("play_purchase_not_active");

  const creditGrant = Math.max(0, Number(mapping.creditGrant || 0));
  // Firebase users/{uid} is the website's canonical balance and subscription record.
  // Keep the legacy native mirror only if an older consumer still needs it.
  const profile = await firestore.get("users/" + uid);
  if (!profile) throw new Error("Account not found.");
  const balance = await firestore.get("creditBalances/" + uid);
  const entitlement = await firestore.get("entitlements/" + uid);
  const purchaseData = {
    uid, productId, planId: String(mapping.planId || ""), productType: mapping.productType,
    basePlanId: verifiedBasePlanId || null, offerId: verifiedOfferId || null, purchaseToken,
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
  if (creditGrant > 0) {
    const isSubscription = mapping.productType === "subs";
    const expiryTime = lineItem.expiryTime ? new Date(lineItem.expiryTime) : null;
    const liveSubCredits = isSubscription && profile.subExpiresAt && Date.parse(String(profile.subExpiresAt)) > Date.now()
      ? Number(profile.subCredits || 0)
      : 0;
    const nextPermanent = Number(profile.credits || 0) + (isSubscription ? 0 : creditGrant);
    const nextSubCredits = liveSubCredits + (isSubscription ? creditGrant : 0);
    const basePlan = String(verifiedBasePlanId || "");
    const period = /year/i.test(basePlan) ? "yearly" : "monthly";
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
    // Keep the legacy native mirror in sync for any older app build.
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
        uid,
        type: "purchase",
        feature: "google-play",
        credits: creditGrant,
        balanceAfter: nextPermanent + nextSubCredits,
        amount: Number(mapping.amount || mapping.price || 0),
        currency: String(mapping.currency || "USD"),
        reference: purchaseToken,
        note: "Google Play purchase",
        reason: "Verified Google Play purchase",
        createdAt: new Date()
      },
      create: true
    });
    writes.push({
      path: "creditLedger/" + crypto.randomUUID(),
      data: { uid, amount: creditGrant, kind: "play_purchase_grant", providerId: "google_play", providerReference: purchaseToken, createdAt: new Date() },
      create: true
    });
  }
  await firestore.commit(writes);

  // Shared website payment history. The website already reads these Firestore
  // collections for receipts and admin payment analytics.
  const paymentAmount = Number(mapping.amount || mapping.price || 0);
  const paymentCurrency = String(mapping.currency || "USD");
  const paymentReference = "GOOGLEPLAY-" + purchaseId;
  await firestore.commit([
    {
      path: "billingReceipts/" + paymentReference + "-subscription",
      data: {
        uid,
        module: String(mapping.product || "general"),
        planName: String(mapping.name || mapping.planId || productId),
        cycle: mapping.productType === "subs" ? (/year/i.test(String(verifiedBasePlanId)) ? "yearly" : "monthly") : "monthly",
        amount: paymentAmount,
        currency: paymentCurrency,
        reference: paymentReference,
        kind: "subscription",
        status: "paid",
        provider: "google_play",
        productId,
        basePlanId: verifiedBasePlanId || null,
        createdAt: new Date()
      },
      create: true
    },
    {
      path: "payments/" + paymentReference,
      data: {
        uid,
        email: String(profile.email || ""),
        amount: paymentAmount,
        currency: paymentCurrency,
        kind: String(mapping.product || "general"),
        provider: "google_play",
        reference: paymentReference,
        productId,
        planId: String(mapping.planId || ""),
        basePlanId: verifiedBasePlanId || null,
        createdAt: new Date()
      },
      create: true
    }
  ]);

  if (mapping.productType === "subs") {
    await playApi(env, "purchases/subscriptions/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":acknowledge", "POST", {});
  } else if (mapping.consumable === true) {
    await playApi(env, "purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":consume", "POST", {});
  } else {
    await playApi(env, "purchases/products/" + encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken) + ":acknowledge", "POST", {});
  }
  return { ok: true, alreadyProcessed: false, state: "verified" };
}

async function sha256Hex(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(value)));
  return Array.from(new Uint8Array(digest)).map(x => x.toString(16).padStart(2, "0")).join("");
}
