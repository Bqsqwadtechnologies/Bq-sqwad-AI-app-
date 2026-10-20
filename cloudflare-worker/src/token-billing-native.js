import { firestoreCreate, firestoreGet, firestoreQuery } from "./firebase.js";
import { computeTokenCredits } from "./billing-core.js";

const MONTHLY_PLAN_PRICE_USD = 11.99;
const MONTHLY_PLAN_CREDITS = 100;
const DEFAULT_CREDIT_REFERENCE_USD = MONTHLY_PLAN_PRICE_USD / MONTHLY_PLAN_CREDITS;
const DEFAULT_CUSTOMER_MULTIPLIER = 1.0;

/**
 * Allocate a reservation refund back to the same balance buckets that funded it.
 * Any mismatch is rejected rather than silently creating or losing credits.
 */
export function allocateReservationRefund(reservation = {}, refundNeeded = 0) {
  const requested = Number(refundNeeded);
  const allocations = ["fromPermanent", "fromSubscription", "fromTrial", "fromDaily"]
    .map(key => Number(reservation[key] ?? 0));
  if (!Number.isFinite(requested) || requested < 0 ||
      allocations.some(value => !Number.isFinite(value) || value < 0)) {
    throw Object.assign(new Error("credit_reservation_refund_mismatch"), { httpStatus: 409, safeError: "credit_reservation_refund_mismatch" });
  }
  let remaining = requested;
  const [fromPermanent, fromSubscription, fromTrial, fromDaily] = allocations;
  const refundPermanent = Math.min(remaining, fromPermanent); remaining -= refundPermanent;
  const refundSubscription = Math.min(remaining, fromSubscription); remaining -= refundSubscription;
  const refundTrial = Math.min(remaining, fromTrial); remaining -= refundTrial;
  const refundDaily = Math.min(remaining, fromDaily); remaining -= refundDaily;
  if (remaining > 0.0000001) {
    throw Object.assign(new Error("credit_reservation_refund_mismatch"), { httpStatus: 409, safeError: "credit_reservation_refund_mismatch" });
  }
  return { refundPermanent, refundSubscription, refundTrial, refundDaily };
}

export function normalizeProviderUsage(providerId, raw = {}) {
  const provider = String(providerId || "").toLowerCase();
  if (provider === "openai") {
    const details = raw.input_tokens_details || raw.prompt_tokens_details || {};
    return {
      inputTokens: Math.max(0, Number(raw.input_tokens ?? raw.prompt_tokens ?? 0)),
      outputTokens: Math.max(0, Number(raw.output_tokens ?? raw.completion_tokens ?? 0)),
      cachedInputTokens: Math.max(0, Number(details.cached_tokens ?? details.cache_read_input_tokens ?? 0)),
      reasoningTokens: Math.max(0, Number(raw.output_tokens_details?.reasoning_tokens ?? 0)),
      modalityUsage: {}
    };
  }
  if (provider === "gemini") {
    return {
      inputTokens: Math.max(0, Number(raw.promptTokenCount ?? raw.prompt_token_count ?? 0)),
      outputTokens: Math.max(0, Number(raw.candidatesTokenCount ?? raw.candidates_token_count ?? 0)),
      cachedInputTokens: Math.max(0, Number(raw.cachedContentTokenCount ?? raw.cached_content_token_count ?? 0)),
      reasoningTokens: Math.max(0, Number(raw.thoughtsTokenCount ?? raw.thoughts_token_count ?? 0)),
      modalityUsage: {
        promptTokensDetails: raw.promptTokensDetails || null,
        candidatesTokensDetails: raw.candidatesTokensDetails || null
      }
    };
  }
  return {
    inputTokens: Math.max(0, Number(raw.inputTokens ?? raw.input_tokens ?? 0)),
    outputTokens: Math.max(0, Number(raw.outputTokens ?? raw.output_tokens ?? 0)),
    cachedInputTokens: Math.max(0, Number(raw.cachedInputTokens ?? raw.cached_input_tokens ?? 0)),
    reasoningTokens: Math.max(0, Number(raw.reasoningTokens ?? raw.reasoning_tokens ?? 0)),
    modalityUsage: {}
  };
}

export function calculateTokenCost({ usage, pricing, creditReferenceValueUsd = DEFAULT_CREDIT_REFERENCE_USD, multiplier = DEFAULT_CUSTOMER_MULTIPLIER, estimatedFeePercent = Number(pricing?.estimatedFeePercent ?? 0) }) {
  const inputTokens = Math.max(0, Number(usage?.inputTokens || 0));
  const outputTokens = Math.max(0, Number(usage?.outputTokens || 0));
  const cachedInputTokens = Math.min(inputTokens, Math.max(0, Number(usage?.cachedInputTokens || 0)));
  // Canonical provider prices are USD per token. The legacy per-million fields
  // remain supported for existing model registry/admin documents.
  const inputRate = pricing?.inputPriceUsdPerToken != null
    ? Number(pricing.inputPriceUsdPerToken) * 1_000_000
    : Number(pricing?.inputPriceUsdPerMillion);
  const outputRate = pricing?.outputPriceUsdPerToken != null
    ? Number(pricing.outputPriceUsdPerToken) * 1_000_000
    : Number(pricing?.outputPriceUsdPerMillion);
  const cachedRate = pricing?.cachedInputPriceUsdPerToken != null
    ? Number(pricing.cachedInputPriceUsdPerToken) * 1_000_000
    : pricing?.cachedInputPriceUsdPerMillion == null
      ? inputRate
      : Number(pricing.cachedInputPriceUsdPerMillion);
  if (![inputRate, outputRate, cachedRate].every(x => Number.isFinite(x) && x >= 0)) {
    throw Object.assign(new Error("token_pricing_not_configured"), { httpStatus: 409, safeError: "token_pricing_not_configured" });
  }
  const uncachedInputTokens = Math.max(0, inputTokens - cachedInputTokens);
  const inputCostUsd = (uncachedInputTokens / 1_000_000) * inputRate;
  const cachedInputCostUsd = (cachedInputTokens / 1_000_000) * cachedRate;
  const outputCostUsd = (outputTokens / 1_000_000) * outputRate;
  const providerCostUsd = inputCostUsd + cachedInputCostUsd + outputCostUsd;
  const creditValue = Number(creditReferenceValueUsd);
  if (!Number.isFinite(creditValue) || creditValue <= 0) {
    throw Object.assign(new Error("token_billing_config_invalid"), { httpStatus: 409, safeError: "token_billing_config_invalid" });
  }
  if (!Number.isFinite(estimatedFeePercent) || estimatedFeePercent < 0 || estimatedFeePercent > 100) {
    throw Object.assign(new Error("estimated_fee_percent_invalid"), { httpStatus: 409, safeError: "token_billing_config_invalid" });
  }
  const providerCostCredits = providerCostUsd / creditValue;
  // The shared pure billing engine performs the final customer-cost and credit
  // rounding used by every token-billed Worker path. The effective multiplier
  // already includes canonical markup, fee allowance, and model weight.
  const effectiveMultiplier = Number(multiplier);
  if (!Number.isFinite(effectiveMultiplier) || effectiveMultiplier < 1 || effectiveMultiplier > 10000) {
    throw Object.assign(new Error("effective_model_multiplier_invalid"), { httpStatus: 409, safeError: "token_billing_config_invalid" });
  }
  const canonicalCalculation = computeTokenCredits({
    uncachedInputTokens,
    cachedInputTokens,
    outputTokens
  }, {
    markupPercent: (effectiveMultiplier - 1) * 100,
    feePercent: 0,
    usdPerCredit: creditValue
  }, {
    inputUsdPerToken: inputRate / 1_000_000,
    cachedInputUsdPerToken: cachedRate / 1_000_000,
    outputUsdPerToken: outputRate / 1_000_000
  });
  const customerCredits = canonicalCalculation.credits;
  const grossUsageValueUsd = customerCredits * creditValue;
  const estimatedFeeUsd = grossUsageValueUsd * (estimatedFeePercent / 100);
  const estimatedGrossProfitUsd = grossUsageValueUsd - providerCostUsd;
  const markup = effectiveMultiplier;

  return {
    inputTokens, outputTokens, cachedInputTokens,
    inputCostUsd, cachedInputCostUsd, outputCostUsd, providerCostUsd,
    creditReferenceValueUsd: creditValue,
    customerMultiplier: markup,
    estimatedFeePercent,
    estimatedFeeUsd,
    providerCostCredits,
    customerCredits,
    grossUsageValueUsd,
    estimatedGrossProfitUsd,
    estimatedNetProfitUsd: estimatedGrossProfitUsd - estimatedFeeUsd
  };
}

let canonicalUsageBillingCache = null;
let canonicalUsageBillingCacheUntil = 0;

async function loadCanonicalUsageBilling() {
  if (canonicalUsageBillingCache && Date.now() < canonicalUsageBillingCacheUntil) return canonicalUsageBillingCache;
  const document = await firestoreGet("config/usageBilling");
  let policy = document;
  if (typeof document?.json === "string") {
    try { policy = JSON.parse(document.json); } catch { policy = null; }
  }
  const markupPercent = Number(policy?.markupPercent);
  const feePercent = Number(policy?.feePercent);
  const usdPerCredit = Number(policy?.usdPerCredit);
  if (!policy || !Number.isFinite(markupPercent) || markupPercent < 0 || markupPercent > 10000 ||
      !Number.isFinite(feePercent) || feePercent < 0 || feePercent > 100 ||
      !Number.isFinite(usdPerCredit) || usdPerCredit <= 0) {
    throw Object.assign(new Error("canonical_billing_config_invalid"), { httpStatus: 409, safeError: "billing_config_invalid" });
  }
  canonicalUsageBillingCache = { markupPercent, feePercent, usdPerCredit };
  canonicalUsageBillingCacheUntil = Date.now() + 60000;
  return canonicalUsageBillingCache;
}

export async function resolveTokenPricing(providerId, modelId, feature, control = {}) {
  const embedded = control.tokenPricing && typeof control.tokenPricing === "object" ? control.tokenPricing : {};
  let model = null;
  const registryId = String(control.selectedRegistryModelId || control.modelRegistryId || control.id || "");
  if (/^[a-z0-9][a-z0-9-]{1,79}$/i.test(registryId)) {
    const candidate = await firestoreGet("aiModelRegistry/" + registryId);
    if (candidate &&
        String(candidate.providerId || "").toLowerCase() === String(providerId || "").toLowerCase() &&
        String(candidate.modelId || "") === String(modelId || "") &&
        (!feature || String(candidate.feature || "") === String(feature))) model = candidate;
  }
  if (!model) {
    const rows = await firestoreQuery("aiModelRegistry", { limit: 200 });
    model = rows.find(row =>
      String(row.providerId || "").toLowerCase() === String(providerId || "").toLowerCase() &&
      String(row.modelId || "") === String(modelId || "") &&
      (!feature || String(row.feature || "") === String(feature))
    ) || {};
  }
  const config = control.config && typeof control.config === "object" ? control.config : {};
  const canonicalUsageBilling = await loadCanonicalUsageBilling();
  const tokenRules = await firestoreQuery("tokenPricingRules", { limit: 200 }).catch(() => []);
  const tokenRule = (Array.isArray(tokenRules) ? tokenRules : []).filter(rule => {
    if (String(rule.status || "inactive") !== "active") return false;
    if (String(rule.provider || "").toLowerCase() !== String(providerId || "").toLowerCase()) return false;
    if (String(rule.model || "*") !== "*" && String(rule.model) !== String(modelId || "")) return false;
    if (Array.isArray(rule.features) && rule.features.length && !rule.features.includes(String(feature || ""))) return false;
    if (Array.isArray(rule.plans) && rule.plans.length && (!control.planId || !rule.plans.includes(String(control.planId)))) return false;
    if (rule.effectiveFrom && new Date(rule.effectiveFrom).getTime() > Date.now()) return false;
    return true;
  }).sort((a, b) => {
    const specificity = (String(a.model || "*") === "*" ? 1 : 0) - (String(b.model || "*") === "*" ? 1 : 0);
    if (specificity !== 0) return specificity;
    const effective = new Date(b.effectiveFrom || 0).getTime() - new Date(a.effectiveFrom || 0).getTime();
    if (Number.isFinite(effective) && effective !== 0) return effective;
    return Number(b.version || 0) - Number(a.version || 0);
  })[0] || null;
  const dynamicBilling = await firestoreGet("adminAdvancedOptions/dynamic_billing_config").catch(() => null)
    || await firestoreGet("adminAdvancedOptions/dynamicBillingConfig").catch(() => null)
    || {};
  const profitMarkup = canonicalUsageBilling.markupPercent;
  const feeRate = canonicalUsageBilling.feePercent;
  const applyFeeToUsage = false;

  // The website admin dashboard and the Android app share Firebase project
  // bqsqwadai. Website AI model metering is persisted at config/aiModels;
  // use it as the common fallback when this Worker has no per-model registry
  // price, while preserving an explicitly configured registry price as the
  // highest-priority source.
  const sharedAiConfig = await firestoreGet("config/aiModels").catch(() => null);
  const sharedMetering = sharedAiConfig?.metering && typeof sharedAiConfig.metering === "object"
    ? sharedAiConfig.metering
    : {};
  const modelIdLower = String(modelId || "").toLowerCase();
  const modelMultipliers = sharedMetering.modelMultipliers && typeof sharedMetering.modelMultipliers === "object"
    ? Object.entries(sharedMetering.modelMultipliers)
        .filter(([key, value]) => String(key).trim() && Number.isFinite(Number(value)) && Number(value) > 0)
        .sort(([a], [b]) => String(b).length - String(a).length)
    : [];
  const sharedModelMultiplier = modelMultipliers.find(([key]) => modelIdLower.includes(String(key).toLowerCase()));
  // Canonical defaults are USD per token; legacy admin/registry prices are
  // normalized to USD per million tokens for backwards compatibility. Provider
  // rates must be explicitly configured; never invent provider costs in code.
  const isOpenAi = String(providerId || "").toLowerCase() === "openai";
  const adminOpenAiRates = dynamicBilling.openAiRates && typeof dynamicBilling.openAiRates === "object" ? dynamicBilling.openAiRates : {};
  const inputTokenRate = tokenRule?.inputUsdPerToken ?? (tokenRule?.inputUsdPerMillion != null ? Number(tokenRule.inputUsdPerMillion) / 1_000_000 : undefined) ?? embedded.inputPriceUsdPerToken ?? (isOpenAi ? adminOpenAiRates.inputPriceUsdPerToken : undefined) ?? model.inputPriceUsdPerToken ?? config.inputPriceUsdPerToken;
  const outputTokenRate = tokenRule?.outputUsdPerToken ?? (tokenRule?.outputUsdPerMillion != null ? Number(tokenRule.outputUsdPerMillion) / 1_000_000 : undefined) ?? embedded.outputPriceUsdPerToken ?? (isOpenAi ? adminOpenAiRates.outputPriceUsdPerToken : undefined) ?? model.outputPriceUsdPerToken ?? config.outputPriceUsdPerToken;
  const cachedTokenRate = tokenRule?.cachedInputUsdPerToken ?? (tokenRule?.cachedInputUsdPerMillion != null ? Number(tokenRule.cachedInputUsdPerMillion) / 1_000_000 : undefined) ?? embedded.cachedInputPriceUsdPerToken ?? (isOpenAi ? adminOpenAiRates.cachedInputPriceUsdPerToken : undefined) ?? model.cachedInputPriceUsdPerToken ?? config.cachedInputPriceUsdPerToken;
  // Non-OpenAI providers must have explicit provider/model rates in the registry,
  // tokenPricing, or provider-specific admin configuration. Never silently price
  // Gemini or another provider using a shared/OpenAI fallback rate.
  const inputPerMillionLegacy = (isOpenAi ? adminOpenAiRates.inputPriceUsdPerMillion : undefined) ?? model.inputPriceUsdPerMillion ?? config.inputPriceUsdPerMillion;
  const outputPerMillionLegacy = (isOpenAi ? adminOpenAiRates.outputPriceUsdPerMillion : undefined) ?? model.outputPriceUsdPerMillion ?? config.outputPriceUsdPerMillion;
  const cachedPerMillionLegacy = (isOpenAi ? adminOpenAiRates.cachedInputPriceUsdPerMillion : undefined) ?? model.cachedInputPriceUsdPerMillion ?? config.cachedInputPriceUsdPerMillion;
  const rates = {
    inputPriceUsdPerToken: inputTokenRate == null ? undefined : Number(inputTokenRate),
    outputPriceUsdPerToken: outputTokenRate == null ? undefined : Number(outputTokenRate),
    cachedInputPriceUsdPerToken: cachedTokenRate == null ? undefined : Number(cachedTokenRate),
    inputPriceUsdPerMillion: inputTokenRate != null ? Number(inputTokenRate) * 1_000_000 : Number(inputPerMillionLegacy),
    outputPriceUsdPerMillion: outputTokenRate != null ? Number(outputTokenRate) * 1_000_000 : Number(outputPerMillionLegacy),
    cachedInputPriceUsdPerMillion: cachedTokenRate != null ? Number(cachedTokenRate) * 1_000_000 : Number(cachedPerMillionLegacy ?? inputPerMillionLegacy ?? (inputTokenRate != null ? Number(inputTokenRate) * 1_000_000 : undefined))
  };
  // Convert provider USD cost into credits using the agreed monthly plan: $11.99 for 100 credits.
  // This is the canonical conversion value; stale per-model/config values must not override it.
  const creditReferenceValueUsd = canonicalUsageBilling.usdPerCredit;
  const baseCustomerMultiplier = (1 + profitMarkup / 100) * (1 + feeRate / 100);
  // Website model multipliers are deliberate relative cost weights and are
  // applied after the canonical markup and fee policy.
  const customerMultiplier = baseCustomerMultiplier * (sharedModelMultiplier ? Number(sharedModelMultiplier[1]) : 1);
  const estimatedFeePercent = feeRate;
  const maxOutputTokens = Math.min(8192, Math.max(128, Number(embedded.maxOutputTokens ?? model.maxOutputTokens ?? config.maxOutputTokens ?? 2048)));
  const configured = Number.isFinite(rates.inputPriceUsdPerMillion) && rates.inputPriceUsdPerMillion >= 0 &&
    Number.isFinite(rates.outputPriceUsdPerMillion) && rates.outputPriceUsdPerMillion >= 0 &&
    Number.isFinite(rates.cachedInputPriceUsdPerMillion) && rates.cachedInputPriceUsdPerMillion >= 0 &&
    Number.isFinite(customerMultiplier) && customerMultiplier >= 1 &&
    Number.isFinite(estimatedFeePercent) && estimatedFeePercent >= 0 && estimatedFeePercent <= 100;
  const sharedCreditMetering = sharedAiConfig?.metering && typeof sharedAiConfig.metering === "object"
    ? {
        creditsPerInputToken: Number(sharedMetering.creditsPerInputToken ?? 0.00003),
        creditsPerOutputToken: Number(sharedMetering.creditsPerOutputToken ?? 0.00024),
        creditsPerCachedInputToken: Number(sharedMetering.creditsPerCachedInputToken ?? 0.000003),
        minimumCredits: Number(sharedMetering.minimumCredits ?? 0.002),
        fallbackCredits: Number(sharedMetering.fallbackCredits ?? 0.05),
        precision: Number(sharedMetering.precision ?? 4),
        conversionRate: Number(sharedMetering.conversionRate ?? 1)
      }
    : null;
  return {
    ...rates, creditReferenceValueUsd, customerMultiplier, profitMarkupPercent: profitMarkup,
    applyFeeToUsage, estimatedFeePercent, maxOutputTokens, configured,
    sharedCreditMetering,
    sharedModelMultiplier: sharedModelMultiplier ? Number(sharedModelMultiplier[1]) : 1,
    billingMode: String(model.billingMode || control.billingMode || "fixed_credit"),
    modelRegistryId: model.id || null
  };
}

export function estimateTokenReservation({ message, system, pricing }) {
  const inputTokens = Math.max(1, Math.ceil((String(message || "").length + String(system || "").length) / 3.5));
  const outputTokens = Math.max(128, Number(pricing.maxOutputTokens || 2048));
  const estimated = calculateTokenCost({
    usage: { inputTokens, outputTokens, cachedInputTokens: 0 },
    pricing,
    creditReferenceValueUsd: pricing.creditReferenceValueUsd,
    multiplier: pricing.customerMultiplier
  });
  // Reserve a safety margin for tokenizer differences, then settle to exact
  // provider-reported usage after the request completes.
  return Math.max(0.000001, estimated.customerCredits * 1.35);
}

export async function recordTokenUsage({ uid, feature, providerId, modelId, usage, pricing, calculation, requestId, chargedCredits, planCovered = false, modality = "text", unitCount = 0 }) {
  const requestKey = String(requestId || "").trim();
  let recordId = crypto.randomUUID();
  if (requestKey) {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(uid || "") + ":" + requestKey));
    recordId = Array.from(new Uint8Array(digest)).map(value => value.toString(16).padStart(2, "0")).join("");
  }
  const createdAt = new Date();
  const actualChargedCredits = Math.max(0, Number(chargedCredits || 0));
  const reservation = requestKey ? await firestoreGet("creditReservations/" + requestKey).catch(error => { console.error("usage reservation snapshot read failed", error?.message || "unknown"); return null; }) : null;
  const creditsBefore = Math.max(0, Number(reservation?.balanceBefore || 0));
  const creditsAfter = reservation ? Math.max(0, creditsBefore - actualChargedCredits) : 0;
  const creditValue = Math.max(0.000001, Number(pricing?.creditReferenceValueUsd || DEFAULT_CREDIT_REFERENCE_USD));
  const grossUsageValueUsd = actualChargedCredits * creditValue;
  const estimatedFeePercent = Math.min(100, Math.max(0, Number(calculation?.estimatedFeePercent ?? pricing?.estimatedFeePercent ?? 15)));
  const estimatedFeeUsd = grossUsageValueUsd * estimatedFeePercent / 100;
  const estimatedGrossProfitUsd = grossUsageValueUsd - Number(calculation?.providerCostUsd || 0);
  const estimatedNetProfitUsd = estimatedGrossProfitUsd - estimatedFeeUsd;
  const entry = {
    uid: String(uid || ""),
    feature: String(feature || ""),
    providerId: String(providerId || ""),
    modelId: String(modelId || ""),
    provider: String(providerId || ""),
    model: String(modelId || ""),
    modality,
    unitCount: Math.max(0, Number(unitCount || 0)),
    requestId: String(requestId || recordId),
    reservationId: reservation ? requestKey : "",
    source: "android",
    estimatedApiCostUsd: Number(calculation?.providerCostUsd || 0),
    creditsCharged: actualChargedCredits,
    creditsBefore,
    creditsAfter,
    freeRequest: actualChargedCredits === 0,
    adminBypass: false,
    status: "completed",
    totalTokens: Number(usage?.inputTokens || 0) + Number(usage?.outputTokens || 0),
    inputTokens: Number(usage?.inputTokens || 0),
    outputTokens: Number(usage?.outputTokens || 0),
    cachedInputTokens: Number(usage?.cachedInputTokens || 0),
    reasoningTokens: Number(usage?.reasoningTokens || 0),
    providerCostUsd: Number(calculation?.providerCostUsd || 0),
    providerCostCredits: Number(calculation?.providerCostCredits || 0),
    chargedCredits: actualChargedCredits,
    grossUsageValueUsd,
    estimatedGrossProfitUsd,
    estimatedFeePercent,
    estimatedFeeUsd,
    estimatedNetProfitUsd,
    creditReferenceValueUsd: creditValue,
    customerMultiplier: Number(pricing?.customerMultiplier || DEFAULT_CUSTOMER_MULTIPLIER),
    planCovered: planCovered === true,
    costStatus: modality === "text" ? "estimated_from_provider_usage_and_configured_rates" : "estimated_from_configured_provider_unit_rate",
    createdAt
  };
  const path = "providerUsage/" + recordId;
  try {
    await firestoreCreate(path, entry);
  } catch (error) {
    const existing = await firestoreGet(path).catch(() => null);
    if (existing &&
        String(existing.uid || "") === String(uid || "") &&
        String(existing.requestId || "") === String(requestKey || recordId) &&
        String(existing.feature || "") === String(feature || "") &&
        String(existing.providerId || "") === String(providerId || "") &&
        String(existing.modelId || "") === String(modelId || "") &&
        Number(existing.chargedCredits || 0) === actualChargedCredits &&
        Number(existing.inputTokens || 0) === Number(usage?.inputTokens || 0) &&
        Number(existing.outputTokens || 0) === Number(usage?.outputTokens || 0) &&
        String(existing.modality || "text") === String(modality || "text")) {
      return { id: recordId, createdAt: String(existing.createdAt || createdAt.toISOString()), duplicate: true };
    }
    throw error;
  }
  return { id: recordId, createdAt: createdAt.toISOString(), duplicate: false };
}

export async function getTokenUsageReport({ limit = 100 } = {}) {
  const requestedLimit = Math.min(500, Math.max(1, Number(limit) || 100));
  const [canonicalRows, legacyRows] = await Promise.all([
    firestoreQuery("providerUsage", { limit: 500 }),
    firestoreQuery("aiUsageLedger", { limit: 500 }).catch(() => [])
  ]);
  // Canonical website rows win on duplicate request IDs; legacy rows remain
  // readable during the non-destructive migration window.
  const byRequest = new Map();
  const keyOf = row => {
    const uid = String(row.uid || "");
    const requestId = String(row.requestId || "");
    return requestId ? uid + ":" + requestId : uid + ":doc:" + String(row.id || row.__name || "");
  };
  for (const row of canonicalRows) byRequest.set(keyOf(row), row);
  for (const row of legacyRows) if (!byRequest.has(keyOf(row))) byRequest.set(keyOf(row), row);
  const rows = [...byRequest.values()].sort((a, b) => {
    const aTime = Date.parse(a.createdAt?.toDate?.() || a.createdAt || 0) || 0;
    const bTime = Date.parse(b.createdAt?.toDate?.() || b.createdAt || 0) || 0;
    return bTime - aTime;
  });
  const items = rows.slice(0, requestedLimit).map(row => ({
    id: String(row.id || String(row.__name || "").split("/").pop()),
    uid: String(row.uid || ""),
    feature: String(row.feature || ""),
    modality: String(row.modality || "text"),
    providerId: String(row.providerId || row.provider || ""),
    modelId: String(row.modelId || row.model || ""),
    inputTokens: Number(row.inputTokens || 0),
    outputTokens: Number(row.outputTokens || 0),
    cachedInputTokens: Number(row.cachedInputTokens || 0),
    unitCount: Number(row.unitCount || 0),
    providerCostUsd: Number(row.providerCostUsd ?? row.estimatedApiCostUsd ?? 0),
    chargedCredits: Number(row.chargedCredits ?? row.creditsCharged ?? 0),
    grossUsageValueUsd: Number(row.grossUsageValueUsd || 0),
    estimatedGrossProfitUsd: Number(row.estimatedGrossProfitUsd || 0),
    estimatedFeeUsd: Number(row.estimatedFeeUsd || 0),
    estimatedNetProfitUsd: Number(row.estimatedNetProfitUsd || 0),
    costStatus: String(row.costStatus || row.status || "unknown"),
    createdAt: row.createdAt || null
  }));
  return {
    items,
    totals: items.reduce((total, item) => ({
      requests: total.requests + 1,
      inputTokens: total.inputTokens + item.inputTokens,
      outputTokens: total.outputTokens + item.outputTokens,
      cachedInputTokens: total.cachedInputTokens + item.cachedInputTokens,
      units: total.units + item.unitCount,
      providerCostUsd: total.providerCostUsd + item.providerCostUsd,
      chargedCredits: total.chargedCredits + item.chargedCredits,
      grossUsageValueUsd: total.grossUsageValueUsd + item.grossUsageValueUsd,
      estimatedGrossProfitUsd: total.estimatedGrossProfitUsd + item.estimatedGrossProfitUsd,
      estimatedFeeUsd: total.estimatedFeeUsd + item.estimatedFeeUsd,
      estimatedNetProfitUsd: total.estimatedNetProfitUsd + item.estimatedNetProfitUsd
    }), { requests: 0, inputTokens: 0, outputTokens: 0, cachedInputTokens: 0, units: 0, providerCostUsd: 0, chargedCredits: 0, grossUsageValueUsd: 0, estimatedGrossProfitUsd: 0, estimatedFeeUsd: 0, estimatedNetProfitUsd: 0 })
  };
}
