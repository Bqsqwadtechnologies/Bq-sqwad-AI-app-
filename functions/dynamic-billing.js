import { getFirestore, FieldValue } from "firebase-admin/firestore";

const CONFIG_PATH = "aiBillingConfig/current";
const DEFAULTS = Object.freeze({
  enabled: true,
  version: 1,
  profitMarkup: 0.80,
  feeRate: 0.15,
  applyFeeToUsage: false,
  creditValueUsd: 0.07999,
  reservationSafetyMargin: 0.20,
  minimumCredits: 0.01,
  maximumCredits: 100000,
  // Admins may enter providerCostUsd per capability. When absent, the
  // existing server-configured capability credit cost is used as a conservative
  // cost proxy and explicitly marked estimated; it is never labelled actual.
  capabilityRates: {},
  providerModelRates: {}
});

function finite(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

function validateConfig(raw = {}) {
  const config = { ...DEFAULTS, ...(raw && typeof raw === "object" ? raw : {}) };
  config.profitMarkup = finite(config.profitMarkup, DEFAULTS.profitMarkup);
  config.feeRate = finite(config.feeRate, DEFAULTS.feeRate);
  config.creditValueUsd = finite(config.creditValueUsd, DEFAULTS.creditValueUsd);
  config.reservationSafetyMargin = finite(config.reservationSafetyMargin, DEFAULTS.reservationSafetyMargin);
  config.minimumCredits = finite(config.minimumCredits, DEFAULTS.minimumCredits);
  config.maximumCredits = finite(config.maximumCredits, DEFAULTS.maximumCredits);
  if (config.profitMarkup < 0 || config.profitMarkup > 10) throw new Error("billing_markup_out_of_range");
  if (config.feeRate < 0 || config.feeRate >= 1) throw new Error("billing_fee_rate_out_of_range");
  if (config.creditValueUsd <= 0 || config.creditValueUsd > 1000) throw new Error("billing_credit_value_invalid");
  if (config.reservationSafetyMargin < 0 || config.reservationSafetyMargin > 5) throw new Error("billing_safety_margin_out_of_range");
  if (config.minimumCredits < 0 || config.maximumCredits < config.minimumCredits) throw new Error("billing_credit_limits_invalid");
  if (!config.capabilityRates || typeof config.capabilityRates !== "object" || Array.isArray(config.capabilityRates)) {
    throw new Error("billing_capability_rates_invalid");
  }
  for (const [key, rate] of Object.entries(config.capabilityRates)) {
    if (!/^[a-z0-9_-]{1,80}$/i.test(key) || !rate || typeof rate !== "object") throw new Error("billing_capability_rate_invalid");
    if (rate.providerCostUsd != null && (!Number.isFinite(Number(rate.providerCostUsd)) || Number(rate.providerCostUsd) < 0)) {
      throw new Error("billing_provider_cost_invalid");
    }
  }
  if (!config.providerModelRates || typeof config.providerModelRates !== "object" || Array.isArray(config.providerModelRates)) {
    throw new Error("billing_provider_model_rates_invalid");
  }
  for (const [key, rate] of Object.entries(config.providerModelRates)) {
    if (!/^[a-z0-9_.:-]{1,180}$/i.test(key) || !rate || typeof rate !== "object") throw new Error("billing_provider_model_rate_invalid");
    for (const field of ["providerCostUsd", "inputUsdPer1M", "cachedInputUsdPer1M", "outputUsdPer1M", "estimatedInputTokens", "estimatedOutputTokens"]) {
      if (rate[field] != null && (!Number.isFinite(Number(rate[field])) || Number(rate[field]) < 0)) throw new Error("billing_provider_model_rate_invalid");
    }
  }
  return config;
}

export async function getDynamicBillingConfig() {
  const snap = await getFirestore().doc(CONFIG_PATH).get();
  const raw = snap.exists ? (snap.data() || {}) : {};
  return validateConfig({ ...DEFAULTS, ...raw, version: Math.max(1, Number(raw.version || DEFAULTS.version)) });
}

export function calculateDynamicCharge({ providerCostUsd, config, estimated = true }) {
  const cfg = validateConfig(config);
  const cost = Number(providerCostUsd);
  if (!Number.isFinite(cost) || cost < 0) throw new Error("billing_provider_cost_invalid");
  const feeRate = cfg.applyFeeToUsage === true ? cfg.feeRate : 0;
  const sellingPriceUsd = cost * (1 + cfg.profitMarkup) / (1 - feeRate);
  const rawCredits = sellingPriceUsd / cfg.creditValueUsd;
  const credits = cost === 0 ? 0 : Math.min(cfg.maximumCredits, Math.max(cfg.minimumCredits, Math.ceil(rawCredits * 100) / 100));
  return {
    providerCostUsd: cost,
    sellingPriceUsd,
    credits,
    profitMarkup: cfg.profitMarkup,
    feeRateApplied: feeRate,
    creditValueUsd: cfg.creditValueUsd,
    estimated: Boolean(estimated),
    pricingVersion: cfg.version
  };
}

export async function priceOperation(capability, legacyCreditEstimate = 0) {
  const config = await getDynamicBillingConfig();
  const key = String(capability || "chat").trim().toLowerCase().replace(/[^a-z0-9_-]/g, "_").slice(0, 80);
  const db = getFirestore();
  const routeSnap = await db.collection("adminAdvancedOptions").doc("capability_" + key).get();
  const route = routeSnap.exists ? (routeSnap.data() || {}) : {};
  const providerId = String(route.providerId || "openai").toLowerCase();
  const modelId = String(route.modelId || "unknown").toLowerCase();
  const modelRate = config.providerModelRates[providerId + ":" + modelId] || config.providerModelRates[providerId];
  const capabilityRate = config.capabilityRates[key];
  const configured = modelRate || capabilityRate;
  const legacy = Math.max(0, finite(legacyCreditEstimate, 0));
  let providerCostUsd;
  if (configured && configured.providerCostUsd != null) {
    providerCostUsd = Number(configured.providerCostUsd);
  } else if (modelRate && ["inputUsdPer1M", "cachedInputUsdPer1M", "outputUsdPer1M"].some(field => modelRate[field] != null)) {
    const inputTokens = finite(modelRate.estimatedInputTokens, 1000);
    const outputTokens = finite(modelRate.estimatedOutputTokens, 500);
    providerCostUsd = (
      inputTokens * finite(modelRate.inputUsdPer1M, 0) +
      inputTokens * finite(modelRate.cachedInputUsdPer1M, 0) +
      outputTokens * finite(modelRate.outputUsdPer1M, 0)
    ) / 1_000_000;
  } else {
    providerCostUsd = legacy * config.creditValueUsd;
  }
  const base = calculateDynamicCharge({ providerCostUsd, config, estimated: true });
  // Without a model-specific provider rate, retain the existing server-owned
  // capability estimate as a floor. The resulting amount remains explicitly estimated.
  const credits = configured
    ? base.credits
    : Math.max(base.credits, Math.ceil(legacy * (1 + config.profitMarkup) * 100) / 100);
  const safetyCredits = Math.ceil(credits * (1 + config.reservationSafetyMargin) * 100) / 100;
  return {
    ...base,
    capability: key,
    providerId,
    modelId,
    estimateSource: modelRate ? "admin_configured_provider_model_rate" : (capabilityRate && capabilityRate.providerCostUsd != null ? "admin_configured_provider_cost" : "legacy_capability_estimate"),
    credits,
    reservedCredits: Math.min(config.maximumCredits, safetyCredits),
    pricingVersion: config.version
  };
}

export async function recordBillingTransaction(id, data) {
  try {
    const ref = getFirestore().collection("aiBillingTransactions").doc(String(id));
    await getFirestore().runTransaction(async tx => {
      const existing = await tx.get(ref);
      if (existing.exists) return;
      tx.create(ref, {
        transactionId: String(id),
        uid: String(data.uid || ""),
        capability: String(data.capability || "unknown"),
        providerId: data.providerId || null,
        modelId: data.modelId || null,
        providerCostUsd: Number(data.providerCostUsd || 0),
        costType: data.costType || "estimated",
        sellingPriceUsd: Number(data.sellingPriceUsd || 0),
        profitMarkup: Number(data.profitMarkup || 0),
        feeRateApplied: Number(data.feeRateApplied || 0),
        creditValueUsd: Number(data.creditValueUsd || DEFAULTS.creditValueUsd),
        creditsReserved: Number(data.creditsReserved || 0),
        creditsCharged: Number(data.creditsCharged || 0),
        creditsRefunded: Number(data.creditsRefunded || 0),
        pricingVersion: Number(data.pricingVersion || 1),
        exemptType: data.exemptType || "credit_billed",
        status: data.status || "reserved",
        estimateSource: data.estimateSource || "estimated",
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp()
      });
    });
  } catch (error) {
    // Usage accounting must not turn a successful AI operation into a failed
    // response. Log only a stable error code; never log request data or secrets.
    console.error("ai_billing_analytics_write_failed", error?.code || "unknown");
  }
}

export async function updateBillingTransaction(id, patch = {}) {
  if (!id) return;
  try {
    const ref = getFirestore().collection("aiBillingTransactions").doc(String(id));
    await ref.set({ ...patch, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  } catch (error) {
    console.error("ai_billing_settlement_write_failed", error?.code || "unknown");
  }
}

export async function saveDynamicBillingConfig(input, adminUid) {
  const db = getFirestore();
  const ref = db.doc(CONFIG_PATH);
  const beforeSnap = await ref.get();
  const before = validateConfig({ ...DEFAULTS, ...(beforeSnap.exists ? beforeSnap.data() || {} : {}) });
  const next = validateConfig({ ...before, ...input, version: Number(before.version || 1) + 1 });
  const safeNext = { ...next, updatedBy: String(adminUid || ""), updatedAt: FieldValue.serverTimestamp() };
  await ref.set(safeNext);
  await db.collection("aiBillingConfigAudit").add({
    actorUid: String(adminUid || ""),
    previousVersion: before.version,
    newVersion: next.version,
    previous: before,
    next: { ...next },
    createdAt: FieldValue.serverTimestamp()
  });
  return next;
}

function asDate(value) {
  if (value?.toDate) return value.toDate();
  if (value instanceof Date) return value;
  const d = new Date(value || 0);
  return Number.isNaN(d.getTime()) ? new Date(0) : d;
}

export async function getBillingAnalytics(limit = 500) {
  const db = getFirestore();
  const bounded = Math.max(1, Math.min(1000, Number(limit) || 500));
  const snap = await db.collection("aiBillingTransactions").orderBy("createdAt", "desc").limit(bounded).get();
  const now = new Date();
  const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1);
  const totals = () => ({ providerCostUsd: 0, revenueUsd: 0, estimatedProfitUsd: 0, creditsCharged: 0, creditsRefunded: 0, requests: 0, succeeded: 0, failed: 0, adminCostUsd: 0, subscriptionCostUsd: 0, outstandingReservations: 0, unsettled: 0 });
  const today = totals(), month = totals(), all = totals();
  const byProvider = {}, byCapability = {}, transactions = [];
  for (const doc of snap.docs) {
    const x = doc.data() || {};
    const cost = Math.max(0, finite(x.providerCostUsd, 0));
    const charged = Math.max(0, finite(x.creditsCharged, 0));
    const refunded = Math.max(0, finite(x.creditsRefunded, 0));
    const creditValue = Math.max(0, finite(x.creditValueUsd, DEFAULTS.creditValueUsd));
    const exempt = x.exemptType === "admin_exempt";
    const subscription = x.exemptType === "subscription_included";
    const revenue = exempt || subscription ? 0 : Math.max(0, finite(x.sellingPriceUsd, charged * creditValue));
    const fee = Math.max(0, finite(x.feeRateApplied, 0)) * revenue;
    const customerCost = exempt || subscription ? 0 : cost;
    const profit = revenue - customerCost - fee;
    const created = asDate(x.createdAt);
    const status = String(x.status || "reserved");
    const add = target => {
      target.providerCostUsd += cost;
      target.customerProviderCostUsd += customerCost;
      target.revenueUsd += revenue;
      target.estimatedProfitUsd += profit;
      target.creditsCharged += charged;
      target.creditsRefunded += refunded;
      target.requests += 1;
      if (status === "committed") target.succeeded += 1;
      if (status === "failed" || status === "reversed") target.failed += 1;
      if (exempt) target.adminCostUsd += cost;
      if (subscription) target.subscriptionCostUsd += cost;
      if (status === "reserved") target.outstandingReservations += 1;
      if (status !== "committed" && status !== "failed" && status !== "reversed") target.unsettled += 1;
    };
    add(all);
    if (created >= dayStart) add(today);
    if (created >= monthStart) add(month);
    const provider = String(x.providerId || "unknown");
    const capability = String(x.capability || "unknown");
    for (const [map, key] of [[byProvider, provider], [byCapability, capability]]) {
      const row = map[key] || (map[key] = { requests: 0, providerCostUsd: 0, revenueUsd: 0, estimatedProfitUsd: 0 });
      row.requests += 1; row.providerCostUsd += cost; row.revenueUsd += revenue; row.estimatedProfitUsd += profit;
    }
    transactions.push({
      transactionId: doc.id, uid: x.uid || "", capability, providerId: x.providerId || null,
      modelId: x.modelId || null, providerCostUsd: cost, costType: x.costType || "estimated",
      sellingPriceUsd: revenue, creditsReserved: finite(x.creditsReserved, 0),
      creditsCharged: charged, creditsRefunded: refunded, status, exemptType: x.exemptType || "credit_billed",
      pricingVersion: finite(x.pricingVersion, 1), usage: x.usage || null, createdAt: created.toISOString()
    });
  }
  const round = obj => Object.fromEntries(Object.entries(obj).map(([k,v]) => [k, typeof v === "number" ? Math.round(v * 10000) / 10000 : v]));
  return {
    generatedAt: new Date().toISOString(), source: "latest_transactions_bounded",
    transactionCount: transactions.length, limit: bounded,
    today: round(today), month: round(month), all: round(all),
    byProvider: Object.entries(byProvider).map(([providerId, value]) => ({ providerId, ...round(value) })).sort((a,b)=>b.providerCostUsd-a.providerCostUsd),
    byCapability: Object.entries(byCapability).map(([capability, value]) => ({ capability, ...round(value) })).sort((a,b)=>b.providerCostUsd-a.providerCostUsd),
    transactions
  };
}
