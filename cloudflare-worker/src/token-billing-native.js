import { firestoreCreate, firestoreGet, firestoreQuery } from "./firebase.js";

const DEFAULT_CREDIT_REFERENCE_USD = 0.07999;
const DEFAULT_CUSTOMER_MULTIPLIER = 8;

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

export function calculateTokenCost({ usage, pricing, creditReferenceValueUsd = DEFAULT_CREDIT_REFERENCE_USD, multiplier = DEFAULT_CUSTOMER_MULTIPLIER, estimatedFeePercent = Number(pricing?.estimatedFeePercent ?? 15) }) {
  const inputTokens = Math.max(0, Number(usage?.inputTokens || 0));
  const outputTokens = Math.max(0, Number(usage?.outputTokens || 0));
  const cachedInputTokens = Math.min(inputTokens, Math.max(0, Number(usage?.cachedInputTokens || 0)));
  const inputRate = Number(pricing?.inputPriceUsdPerMillion);
  const outputRate = Number(pricing?.outputPriceUsdPerMillion);
  const cachedRate = pricing?.cachedInputPriceUsdPerMillion == null
    ? inputRate
    : Number(pricing.cachedInputPriceUsdPerMillion);
  if (![inputRate, outputRate, cachedRate].every(x => Number.isFinite(x) && x >= 0) ||
      inputRate + outputRate + cachedRate <= 0) {
    throw Object.assign(new Error("token_pricing_not_configured"), { httpStatus: 409, safeError: "token_pricing_not_configured" });
  }
  const uncachedInputTokens = Math.max(0, inputTokens - cachedInputTokens);
  const inputCostUsd = (uncachedInputTokens / 1_000_000) * inputRate;
  const cachedInputCostUsd = (cachedInputTokens / 1_000_000) * cachedRate;
  const outputCostUsd = (outputTokens / 1_000_000) * outputRate;
  const providerCostUsd = inputCostUsd + cachedInputCostUsd + outputCostUsd;
  const creditValue = Number(creditReferenceValueUsd);
  const markup = Number(multiplier);
  if (!Number.isFinite(creditValue) || creditValue <= 0 || !Number.isFinite(markup) || markup < 1) {
    throw Object.assign(new Error("token_billing_config_invalid"), { httpStatus: 409, safeError: "token_billing_config_invalid" });
  }
  if (!Number.isFinite(estimatedFeePercent) || estimatedFeePercent < 0 || estimatedFeePercent > 100) {
    throw Object.assign(new Error("estimated_fee_percent_invalid"), { httpStatus: 409, safeError: "token_billing_config_invalid" });
  }
  const providerCostCredits = providerCostUsd / creditValue;
  const customerCredits = providerCostCredits * markup;
  const grossUsageValueUsd = customerCredits * creditValue;
  const estimatedFeeUsd = grossUsageValueUsd * (estimatedFeePercent / 100);
  const estimatedGrossProfitUsd = grossUsageValueUsd - providerCostUsd;
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
  const rates = {
    inputPriceUsdPerMillion: Number(embedded.inputPriceUsdPerMillion ?? model.inputPriceUsdPerMillion ?? config.inputPriceUsdPerMillion ?? 0),
    outputPriceUsdPerMillion: Number(embedded.outputPriceUsdPerMillion ?? model.outputPriceUsdPerMillion ?? config.outputPriceUsdPerMillion ?? 0),
    cachedInputPriceUsdPerMillion: embedded.cachedInputPriceUsdPerMillion ?? model.cachedInputPriceUsdPerMillion ?? config.cachedInputPriceUsdPerMillion
  };
  const creditReferenceValueUsd = Number(embedded.creditReferenceValueUsd ?? model.creditReferenceValueUsd ?? config.creditReferenceValueUsd ?? DEFAULT_CREDIT_REFERENCE_USD);
  const customerMultiplier = Number(embedded.customerMultiplier ?? model.customerMultiplier ?? config.customerMultiplier ?? DEFAULT_CUSTOMER_MULTIPLIER);
  const estimatedFeePercent = Number(embedded.estimatedFeePercent ?? model.estimatedFeePercent ?? config.estimatedFeePercent ?? 15);
  const maxOutputTokens = Math.min(8192, Math.max(128, Number(embedded.maxOutputTokens ?? model.maxOutputTokens ?? config.maxOutputTokens ?? 2048)));
  const configured = Number.isFinite(rates.inputPriceUsdPerMillion) && rates.inputPriceUsdPerMillion >= 0 &&
    Number.isFinite(rates.outputPriceUsdPerMillion) && rates.outputPriceUsdPerMillion >= 0 &&
    (rates.inputPriceUsdPerMillion + rates.outputPriceUsdPerMillion > 0) &&
    Number.isFinite(customerMultiplier) && customerMultiplier >= 1 &&
    Number.isFinite(estimatedFeePercent) && estimatedFeePercent >= 0 && estimatedFeePercent <= 100;
  return {
    ...rates, creditReferenceValueUsd, customerMultiplier, estimatedFeePercent, maxOutputTokens, configured,
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
  const recordId = crypto.randomUUID();
  const createdAt = new Date();
  const actualChargedCredits = Math.max(0, Number(chargedCredits || 0));
  const creditValue = Math.max(0.000001, Number(pricing?.creditReferenceValueUsd || DEFAULT_CREDIT_REFERENCE_USD));
  const grossUsageValueUsd = actualChargedCredits * creditValue;
  const estimatedFeePercent = Math.min(100, Math.max(0, Number(calculation?.estimatedFeePercent ?? pricing?.estimatedFeePercent ?? 15)));
  const estimatedFeeUsd = grossUsageValueUsd * estimatedFeePercent / 100;
  const estimatedGrossProfitUsd = grossUsageValueUsd - Number(calculation?.providerCostUsd || 0);
  const estimatedNetProfitUsd = estimatedGrossProfitUsd - estimatedFeeUsd;
  await firestoreCreate("aiUsageLedger/" + recordId, {
    uid: String(uid || ""),
    feature: String(feature || ""),
    providerId: String(providerId || ""),
    modelId: String(modelId || ""),
    modality,
    unitCount: Math.max(0, Number(unitCount || 0)),
    requestId: String(requestId || recordId),
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
    costStatus: "estimated_from_provider_usage_and_configured_rates",
    createdAt
  });
  return { id: recordId, createdAt: createdAt.toISOString() };
}

export async function getTokenUsageReport({ limit = 100 } = {}) {
  const requestedLimit = Math.min(500, Math.max(1, Number(limit) || 100));
  const rows = await firestoreQuery("aiUsageLedger", { limit: 500 });
  rows.sort((a, b) => {
    const aTime = Date.parse(a.createdAt?.toDate?.() || a.createdAt || 0) || 0;
    const bTime = Date.parse(b.createdAt?.toDate?.() || b.createdAt || 0) || 0;
    return bTime - aTime;
  });
  const items = rows.slice(0, requestedLimit).map(row => ({
    id: String(row.id || String(row.__name || "").split("/").pop()),
    uid: String(row.uid || ""),
    feature: String(row.feature || ""),
    providerId: String(row.providerId || ""),
    modelId: String(row.modelId || ""),
    inputTokens: Number(row.inputTokens || 0),
    outputTokens: Number(row.outputTokens || 0),
    cachedInputTokens: Number(row.cachedInputTokens || 0),
    unitCount: Number(row.unitCount || 0),
    providerCostUsd: Number(row.providerCostUsd || 0),
    chargedCredits: Number(row.chargedCredits || 0),
    grossUsageValueUsd: Number(row.grossUsageValueUsd || 0),
    estimatedGrossProfitUsd: Number(row.estimatedGrossProfitUsd || 0),
    estimatedFeeUsd: Number(row.estimatedFeeUsd || 0),
    estimatedNetProfitUsd: Number(row.estimatedNetProfitUsd || 0),
    costStatus: String(row.costStatus || "unknown"),
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
