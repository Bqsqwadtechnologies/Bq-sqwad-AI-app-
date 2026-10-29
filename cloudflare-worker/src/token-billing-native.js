import { firestoreCreate, firestoreGet, firestoreQuery } from "./firebase.js";
import { computeTokenCredits } from "./billing-core.js";

// Canonical credit value: the 1,000-credit monthly plan is $79.99.
const MONTHLY_PLAN_PRICE_USD = 79.99;
const MONTHLY_PLAN_CREDITS = 1000;
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
  if (["gemini", "google", "google-gemini"].includes(provider)) {
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
    throw Object.assign(new Error("token_pricing_not_configured"), { httpStatus: 409, safeError: "provider_unavailable" });
  }
  const uncachedInputTokens = Math.max(0, inputTokens - cachedInputTokens);
  const inputCostUsd = (uncachedInputTokens / 1_000_000) * inputRate;
  const cachedInputCostUsd = (cachedInputTokens / 1_000_000) * cachedRate;
  const outputCostUsd = (outputTokens / 1_000_000) * outputRate;
  const providerCostUsd = inputCostUsd + cachedInputCostUsd + outputCostUsd;
  const creditValue = Number(creditReferenceValueUsd);
  if (!Number.isFinite(creditValue) || creditValue <= 0) {
    throw Object.assign(new Error("token_billing_config_invalid"), { httpStatus: 409, safeError: "provider_unavailable" });
  }
  if (!Number.isFinite(estimatedFeePercent) || estimatedFeePercent < 0 || estimatedFeePercent > 100) {
    throw Object.assign(new Error("estimated_fee_percent_invalid"), { httpStatus: 409, safeError: "provider_unavailable" });
  }
  const providerCostCredits = providerCostUsd / creditValue;

  // Main Chat uses the website's canonical per-token credit meter when it is
  // available. Provider USD rates are retained for usage/profit reporting, but
  // must not override the website's configured credits-per-token policy.
  const sharedMeter = pricing?.sharedCreditMetering;
  if (sharedMeter && typeof sharedMeter === "object") {
    const uncachedInputTokens = Math.max(0, inputTokens - cachedInputTokens);
    const inputCreditRate = Number(sharedMeter.creditsPerInputToken);
    const cachedCreditRate = Number(sharedMeter.creditsPerCachedInputToken);
    const outputCreditRate = Number(sharedMeter.creditsPerOutputToken);
    const conversionRate = Number(sharedMeter.conversionRate ?? 1);
    const minimumCredits = Number(sharedMeter.minimumCredits ?? 0);
    const precision = Number(sharedMeter.precision ?? 4);
    const modelWeight = Number(pricing.sharedModelMultiplier ?? 1);
    const sharedValues = [inputCreditRate, cachedCreditRate, outputCreditRate, conversionRate, minimumCredits, precision, modelWeight];
    if (!sharedValues.every(Number.isFinite) ||
        inputCreditRate < 0 || cachedCreditRate < 0 || outputCreditRate < 0 ||
        conversionRate < 0 || minimumCredits < 0 ||
        !Number.isInteger(precision) || precision < 0 || precision > 8 ||
        modelWeight <= 0 || modelWeight > 10) {
      throw Object.assign(new Error("shared_token_metering_invalid"), { httpStatus: 409, safeError: "provider_unavailable" });
    }
    const rawCredits = (
      uncachedInputTokens * inputCreditRate +
      cachedInputTokens * cachedCreditRate +
      outputTokens * outputCreditRate
    ) * conversionRate * modelWeight;
    const roundedCredits = rawCredits > 0
      ? Math.max(minimumCredits, Math.round((rawCredits + Number.EPSILON) * (10 ** precision)) / (10 ** precision))
      : 0;
    const grossUsageValueUsd = roundedCredits * creditValue;
    const estimatedFeeUsd = grossUsageValueUsd * (estimatedFeePercent / 100);
    const estimatedGrossProfitUsd = grossUsageValueUsd - providerCostUsd;
    return {
      inputTokens, outputTokens, cachedInputTokens,
      inputCostUsd, cachedInputCostUsd, outputCostUsd, providerCostUsd,
      creditReferenceValueUsd: creditValue,
      customerMultiplier: modelWeight,
      estimatedFeePercent,
      estimatedFeeUsd,
      providerCostCredits,
      customerCredits: roundedCredits,
      grossUsageValueUsd,
      estimatedGrossProfitUsd,
      estimatedNetProfitUsd: estimatedGrossProfitUsd - estimatedFeeUsd
    };
  }

  // USD cost-plus is the fallback for features/providers that do not use the
  // website's shared per-token credit meter.
  const effectiveMultiplier = Number(multiplier);
  if (!Number.isFinite(effectiveMultiplier) || effectiveMultiplier < 1 || effectiveMultiplier > 10000) {
    throw Object.assign(new Error("effective_model_multiplier_invalid"), { httpStatus: 409, safeError: "provider_unavailable" });
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
  // Match the website's canonical cost-plus engine: use the active tokenPricingRules
  // provider rates for uncached input, cached input and output tokens, then apply
  // config/usageBilling markup, fee and USD-per-credit settings exactly once.
  // Never turn a Main Chat request into a fixed one-credit charge.
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
  if (!document) {
    // Safe, user-approved defaults apply only when the canonical document is
    // genuinely absent. Existing admin values remain authoritative.
    const fallback = { markupPercent: 600, feePercent: 0, usdPerCredit: 0.1199 };
    const hour = new Date().toISOString().slice(0, 13).replace(/[^0-9]/g, "");
    const warningId = "billing_config_config_usageBilling_" + hour;
    await firestoreCreate("adminIssues/" + warningId, {
      id: warningId,
      category: "billing_configuration",
      severity: "warning",
      state: "open",
      status: "open",
      document: "config/usageBilling",
      source: "token-billing-native",
      message: "config/usageBilling is missing; website-aligned defaults are active.",
      createdAt: new Date().toISOString()
    }).catch(() => {});
    console.warn("[BQ Billing] config/usageBilling missing; using the website-aligned fallback defaults.");
    canonicalUsageBillingCache = fallback;
    canonicalUsageBillingCacheUntil = Date.now() + 60000;
    return canonicalUsageBillingCache;
  }
  let policy = document;
  if (typeof document.json === "string") {
    try { policy = JSON.parse(document.json); } catch { policy = null; }
  }
  const markupPercent = Number(policy?.markupPercent);
  const feePercent = Number(policy?.feePercent);
  const usdPerCredit = Number(policy?.usdPerCredit);
  const valid = !!policy && Number.isFinite(markupPercent) && markupPercent >= 0 && markupPercent <= 10000 &&
    Number.isFinite(feePercent) && feePercent >= 0 && feePercent <= 100 &&
    Number.isFinite(usdPerCredit) && usdPerCredit > 0;
  if (!valid) {
    // A malformed admin document must not take chat offline. Keep the bad
    // document intact for diagnosis and use the website's conservative defaults
    // for this request; only document names and the validation category are logged.
    const fallback = { markupPercent: 600, feePercent: 0, usdPerCredit: 0.1199 };
    const hour = new Date().toISOString().slice(0, 13).replace(/[^0-9]/g, "");
    const warningId = "billing_config_config_usageBilling_" + hour;
    await firestoreCreate("adminIssues/" + warningId, {
      id: warningId,
      category: "billing_configuration",
      severity: "warning",
      state: "open",
      status: "open",
      document: "config/usageBilling",
      source: "token-billing-native",
      message: "config/usageBilling is invalid; website-aligned defaults are active.",
      createdAt: new Date().toISOString()
    }).catch(() => {});
    console.warn("[BQ Billing] config/usageBilling invalid; request uses defaults.");
    canonicalUsageBillingCache = fallback;
    canonicalUsageBillingCacheUntil = Date.now() + 60000;
    return canonicalUsageBillingCache;
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
  const featureAliases = new Set([String(feature || "")]);
  // The website's token-pricing engine uses "chat"; older model-registry and
  // native callers may label the same Main Chat feature "text-chat". Treat
  // those as aliases so a scoped website price rule is not silently skipped.
  if (featureAliases.has("chat")) featureAliases.add("text-chat");
  if (featureAliases.has("text-chat")) featureAliases.add("chat");
  const tokenRule = (Array.isArray(tokenRules) ? tokenRules : []).filter(rule => {
    if (String(rule.status || "inactive") !== "active") return false;
    if (!((["gemini", "google", "google-gemini"].includes(String(providerId || "").toLowerCase()) && ["gemini", "google", "google-gemini"].includes(String(rule.provider || "").toLowerCase())) || (["lovable-gateway", "lovable", "gateway"].includes(String(providerId || "").toLowerCase()) && ["lovable-gateway", "lovable", "gateway"].includes(String(rule.provider || "").toLowerCase())) || (String(rule.provider || "").toLowerCase() === String(providerId || "").toLowerCase()))) return false;
    if (String(rule.model || "*") !== "*" && String(rule.model) !== String(modelId || "")) return false;
    if (Array.isArray(rule.features) && rule.features.length && !rule.features.some(name => featureAliases.has(String(name)))) return false;
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
    : {
        // Match the website's ai-models.server.ts defaults when the canonical
        // shared config has not yet been created in Firestore.
        creditsPerInputToken: 0.00003,
        creditsPerCachedInputToken: 0.000003,
        creditsPerOutputToken: 0.00024,
        minimumCredits: 0.002,
        fallbackCredits: 0.05,
        conversionRate: 1,
        precision: 7,
        modelMultipliers: { luna: 1, terra: 3, sol: 8, gemini: 0.8 },
        maxRequestTokens: 8_000,
        maxOutputTokens: 512,
      };
  const boundedLimit = (value, fallback, min, max) => {
    const number = Number(value);
    return Number.isFinite(number) ? Math.min(max, Math.max(min, Math.round(number))) : fallback;
  };
  const maxRequestTokens = boundedLimit(sharedMetering.maxRequestTokens, 8_000, 1_000, 16_000);
  const maxOutputTokens = boundedLimit(sharedMetering.maxOutputTokens, 512, 128, 1_024);
  const modelIdLower = String(modelId || "").toLowerCase();
  const modelMultipliers = sharedMetering.modelMultipliers && typeof sharedMetering.modelMultipliers === "object"
    ? Object.entries(sharedMetering.modelMultipliers)
        // Multipliers are relative weights, not provider prices. Reject accidental
        // per-million or percentage values here so a bad admin entry cannot
        // turn one ordinary chat message into a full-balance charge.
        .filter(([key, value]) => String(key).trim() && Number.isFinite(Number(value)) && Number(value) > 0 && Number(value) <= 10)
        .sort(([a], [b]) => String(b).length - String(a).length)
    : [];
  const sharedModelMultiplier = modelMultipliers.find(([key]) => modelIdLower.includes(String(key).toLowerCase()));
  // Canonical defaults are USD per token; legacy admin/registry prices are
  // normalized to USD per million tokens for backwards compatibility. Provider
  // rates must be explicitly configured; never invent provider costs in code.
  const isOpenAi = String(providerId || "").toLowerCase() === "openai";
  const adminOpenAiRates = dynamicBilling.openAiRates && typeof dynamicBilling.openAiRates === "object" ? dynamicBilling.openAiRates : {};
  // The admin UI edits the per-million USD fields. Prefer those canonical
  // fields over legacy derived per-token mirrors, which can be stale after a
  // price edit. This makes saved Main Chat input/output/cache prices effective
  // immediately without requiring an admin to recreate the rule.
  const inputTokenRate = (tokenRule?.inputUsdPerMillion != null ? Number(tokenRule.inputUsdPerMillion) / 1_000_000 : undefined) ?? tokenRule?.inputUsdPerToken ?? embedded.inputPriceUsdPerToken ?? (isOpenAi ? adminOpenAiRates.inputPriceUsdPerToken : undefined) ?? model.inputPriceUsdPerToken ?? config.inputPriceUsdPerToken;
  const outputTokenRate = (tokenRule?.outputUsdPerMillion != null ? Number(tokenRule.outputUsdPerMillion) / 1_000_000 : undefined) ?? tokenRule?.outputUsdPerToken ?? embedded.outputPriceUsdPerToken ?? (isOpenAi ? adminOpenAiRates.outputPriceUsdPerToken : undefined) ?? model.outputPriceUsdPerToken ?? config.outputPriceUsdPerToken;
  const cachedTokenRate = (tokenRule?.cachedInputUsdPerMillion != null ? Number(tokenRule.cachedInputUsdPerMillion) / 1_000_000 : undefined) ?? tokenRule?.cachedInputUsdPerToken ?? embedded.cachedInputPriceUsdPerToken ?? (isOpenAi ? adminOpenAiRates.cachedInputPriceUsdPerToken : undefined) ?? model.cachedInputPriceUsdPerToken ?? config.cachedInputPriceUsdPerToken;
  // Non-OpenAI providers must have explicit provider/model rates in the registry,
  // tokenPricing, or provider-specific admin configuration. Never silently price
  // Gemini or another provider using a shared/OpenAI fallback rate.
  const inputPerMillionLegacy = (isOpenAi ? adminOpenAiRates.inputPriceUsdPerMillion : undefined) ?? model.inputPriceUsdPerMillion ?? config.inputPriceUsdPerMillion;
  const outputPerMillionLegacy = (isOpenAi ? adminOpenAiRates.outputPriceUsdPerMillion : undefined) ?? model.outputPriceUsdPerMillion ?? config.outputPriceUsdPerMillion;
  const cachedPerMillionLegacy = (isOpenAi ? adminOpenAiRates.cachedInputPriceUsdPerMillion : undefined) ?? model.cachedInputPriceUsdPerMillion ?? config.cachedInputPriceUsdPerMillion;
  // Match the website's private OpenAI reference rates when canonical pricing
  // documents/rules are absent. Other providers still require their own rates;
  // never substitute OpenAI prices for Gemini or an unrelated provider.
  const defaultOpenAiRates = {
    input: 0.00000005,
    output: 0.00000025,
    cached: 0.000000005
  };
  const fallbackInput = isOpenAi ? defaultOpenAiRates.input : undefined;
  const fallbackOutput = isOpenAi ? defaultOpenAiRates.output : undefined;
  const fallbackCached = isOpenAi ? defaultOpenAiRates.cached : undefined;
  const rates = {
    inputPriceUsdPerToken: inputTokenRate == null ? fallbackInput : Number(inputTokenRate),
    outputPriceUsdPerToken: outputTokenRate == null ? fallbackOutput : Number(outputTokenRate),
    cachedInputPriceUsdPerToken: cachedTokenRate == null ? fallbackCached : Number(cachedTokenRate),
    inputPriceUsdPerMillion: inputTokenRate != null ? Number(inputTokenRate) * 1_000_000 : Number(inputPerMillionLegacy ?? (fallbackInput == null ? undefined : fallbackInput * 1_000_000)),
    outputPriceUsdPerMillion: outputTokenRate != null ? Number(outputTokenRate) * 1_000_000 : Number(outputPerMillionLegacy ?? (fallbackOutput == null ? undefined : fallbackOutput * 1_000_000)),
    cachedInputPriceUsdPerMillion: cachedTokenRate != null ? Number(cachedTokenRate) * 1_000_000 : Number(cachedPerMillionLegacy ?? inputPerMillionLegacy ?? (inputTokenRate != null ? Number(inputTokenRate) * 1_000_000 : fallbackCached == null ? undefined : fallbackCached * 1_000_000))
  };
  // Convert provider USD cost into credits using the canonical $79.99 / 1,000-credit value.
  // This is the fallback conversion value; the shared Firebase billing policy remains authoritative.
  const creditReferenceValueUsd = canonicalUsageBilling.usdPerCredit;
  const baseCustomerMultiplier = (1 + profitMarkup / 100) * (1 + feeRate / 100);
  // Website model multipliers are deliberate relative cost weights and are
  // applied after the canonical markup and fee policy.
  const customerMultiplier = baseCustomerMultiplier * (sharedModelMultiplier ? Number(sharedModelMultiplier[1]) : 1);
  const estimatedFeePercent = feeRate;
  // Input/output ceilings are enforced by the shared website-admin token budget.
  // Sanity-check normalized USD-per-million rates. A per-million value
  // accidentally entered into a per-token field otherwise becomes millions
  // of times larger and can drain a balance on a short prompt.
  const configured = Number.isFinite(rates.inputPriceUsdPerMillion) && rates.inputPriceUsdPerMillion >= 0 && rates.inputPriceUsdPerMillion <= 100 &&
    Number.isFinite(rates.outputPriceUsdPerMillion) && rates.outputPriceUsdPerMillion >= 0 && rates.outputPriceUsdPerMillion <= 500 &&
    Number.isFinite(rates.cachedInputPriceUsdPerMillion) && rates.cachedInputPriceUsdPerMillion >= 0 && rates.cachedInputPriceUsdPerMillion <= 100 &&
    Number.isFinite(customerMultiplier) && customerMultiplier >= 1 && customerMultiplier <= 100 &&
    Number.isFinite(estimatedFeePercent) && estimatedFeePercent >= 0 && estimatedFeePercent <= 100;
  const isMainChatMeteredFeature = ["chat", "text-chat"].includes(String(feature || "").toLowerCase());
  // Website tokenPricingRules are the canonical customer-credit prices for
  // both website and Android. Use their per-1,000-token credit rates directly;
  // USD rates below remain private provider-cost accounting only.
  const hasCustomerCreditRule = !!tokenRule &&
    Number.isFinite(Number(tokenRule.inputCreditsPer1k)) && Number(tokenRule.inputCreditsPer1k) >= 0 &&
    Number.isFinite(Number(tokenRule.outputCreditsPer1k)) && Number(tokenRule.outputCreditsPer1k) >= 0;
  const sharedCreditMetering = isMainChatMeteredFeature ? {
    creditsPerInputToken: hasCustomerCreditRule
      ? Number(tokenRule.inputCreditsPer1k) / 1000
      : Number(sharedMetering.creditsPerInputToken ?? 0.00003),
    creditsPerOutputToken: hasCustomerCreditRule
      ? Number(tokenRule.outputCreditsPer1k) / 1000
      : Number(sharedMetering.creditsPerOutputToken ?? 0.00024),
    creditsPerCachedInputToken: hasCustomerCreditRule
      ? Number(tokenRule.cachedInputCreditsPer1k ?? 0.003) / 1000
      : Number(sharedMetering.creditsPerCachedInputToken ?? 0.000003),
    minimumCredits: hasCustomerCreditRule ? 0 : Number(sharedMetering.minimumCredits ?? 0.002),
    fallbackCredits: Number(sharedMetering.fallbackCredits ?? 0.05),
    precision: hasCustomerCreditRule ? 7 : Number(sharedMetering.precision ?? 4),
    conversionRate: hasCustomerCreditRule ? 1 : Number(sharedMetering.conversionRate ?? 1)
  } : null;
  return {
    ...rates, maxRequestTokens, maxOutputTokens, creditReferenceValueUsd, customerMultiplier, profitMarkupPercent: profitMarkup,
    applyFeeToUsage, estimatedFeePercent, configured,
    sharedCreditMetering,
    sharedModelMultiplier: hasCustomerCreditRule ? 1 : (sharedModelMultiplier ? Number(sharedModelMultiplier[1]) : 1),
    billingMode: isMainChatMeteredFeature && hasCustomerCreditRule ? "token" : String(model.billingMode || control.billingMode || "fixed_credit"),
    modelRegistryId: model.id || null
  };
}

export function estimateTokenReservation({ message, system, pricing }) {
  const inputTokens = Math.max(1, Math.ceil((String(message || "").length + String(system || "").length) / 3.5));
  // Reservation output estimate only; this value is not sent as a provider output cap.
  const outputTokens = Math.max(128, Number(pricing.maxOutputTokens || 1_024));
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
