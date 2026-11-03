import { firestoreCreate, firestoreGet, firestoreQuery } from "./firebase.js";

// Canonical credit value: the 1,000-credit monthly plan is $79.99.
const MONTHLY_PLAN_PRICE_USD = 79.99;
const MONTHLY_PLAN_CREDITS = 1000;
const DEFAULT_CREDIT_REFERENCE_USD = MONTHLY_PLAN_PRICE_USD / MONTHLY_PLAN_CREDITS;

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

function modalityTokens(details, modality) {
  const key = String(modality || "").toLowerCase();
  if (Array.isArray(details)) {
    const item = details.find(row => String(row?.modality || row?.type || "").toLowerCase() === key);
    return Math.max(0, Number(item?.tokenCount ?? item?.token_count ?? item?.tokens ?? 0));
  }
  if (!details || typeof details !== "object") return 0;
  const aliases = key === "audio" ? ["audio_tokens", "audioTokenCount"] : key === "image" ? ["image_tokens", "imageTokenCount"] : [];
  for (const field of aliases) if (details[field] != null) return Math.max(0, Number(details[field]) || 0);
  return 0;
}

export function normalizeProviderUsage(providerId, raw = {}) {
  const provider = String(providerId || "").toLowerCase();
  if (provider === "openai") {
    const inputDetails = raw.input_token_details || raw.input_tokens_details || raw.prompt_tokens_details || {};
    const outputDetails = raw.output_token_details || raw.output_tokens_details || raw.completion_tokens_details || {};
    return {
      inputTokens: Math.max(0, Number(raw.input_tokens ?? raw.prompt_tokens ?? 0)),
      outputTokens: Math.max(0, Number(raw.output_tokens ?? raw.completion_tokens ?? 0)),
      cachedInputTokens: Math.max(0, Number(inputDetails.cached_tokens ?? inputDetails.cache_read_input_tokens ?? 0)),
      reasoningTokens: Math.max(0, Number(outputDetails.reasoning_tokens ?? 0)),
      inputAudioTokens: Math.max(0, Number(raw.input_audio_tokens ?? inputDetails.audio_tokens ?? 0)),
      outputAudioTokens: Math.max(0, Number(raw.output_audio_tokens ?? outputDetails.audio_tokens ?? 0)),
      inputImageTokens: Math.max(0, Number(raw.input_image_tokens ?? inputDetails.image_tokens ?? 0)),
      outputImageTokens: Math.max(0, Number(raw.output_image_tokens ?? outputDetails.image_tokens ?? 0)),
      modalityUsage: { input: inputDetails, output: outputDetails }
    };
  }
  if (["gemini", "google", "google-gemini"].includes(provider)) {
    const inputDetails = raw.promptTokensDetails || raw.prompt_tokens_details || [];
    const outputDetails = raw.candidatesTokensDetails || raw.candidates_tokens_details || [];
    return {
      inputTokens: Math.max(0, Number(raw.promptTokenCount ?? raw.prompt_token_count ?? 0)),
      outputTokens: Math.max(0, Number(raw.candidatesTokenCount ?? raw.candidates_token_count ?? 0)),
      cachedInputTokens: Math.max(0, Number(raw.cachedContentTokenCount ?? raw.cached_content_token_count ?? 0)),
      reasoningTokens: Math.max(0, Number(raw.thoughtsTokenCount ?? raw.thoughts_token_count ?? 0)),
      inputAudioTokens: modalityTokens(inputDetails, "audio"),
      outputAudioTokens: modalityTokens(outputDetails, "audio"),
      inputImageTokens: modalityTokens(inputDetails, "image"),
      outputImageTokens: modalityTokens(outputDetails, "image"),
      modalityUsage: { promptTokensDetails: inputDetails, candidatesTokensDetails: outputDetails }
    };
  }
  return {
    inputTokens: Math.max(0, Number(raw.inputTokens ?? raw.input_tokens ?? 0)),
    outputTokens: Math.max(0, Number(raw.outputTokens ?? raw.output_tokens ?? 0)),
    cachedInputTokens: Math.max(0, Number(raw.cachedInputTokens ?? raw.cached_input_tokens ?? 0)),
    reasoningTokens: Math.max(0, Number(raw.reasoningTokens ?? raw.reasoning_tokens ?? 0)),
    inputAudioTokens: Math.max(0, Number(raw.inputAudioTokens ?? raw.input_audio_tokens ?? 0)),
    outputAudioTokens: Math.max(0, Number(raw.outputAudioTokens ?? raw.output_audio_tokens ?? 0)),
    inputImageTokens: Math.max(0, Number(raw.inputImageTokens ?? raw.input_image_tokens ?? 0)),
    outputImageTokens: Math.max(0, Number(raw.outputImageTokens ?? raw.output_image_tokens ?? 0)),
    modalityUsage: raw.modalityUsage || {}
  };
}

export function calculateTokenCost({ usage, pricing, creditReferenceValueUsd = DEFAULT_CREDIT_REFERENCE_USD }) {
  const inputTokens = Math.max(0, Number(usage?.inputTokens || 0));
  const outputTokens = Math.max(0, Number(usage?.outputTokens || 0));
  const inputAudioTokens = Math.max(0, Number(usage?.inputAudioTokens || 0));
  const outputAudioTokens = Math.max(0, Number(usage?.outputAudioTokens || 0));
  const inputImageTokens = Math.max(0, Number(usage?.inputImageTokens || 0));
  const outputImageTokens = Math.max(0, Number(usage?.outputImageTokens || 0));
  if (inputAudioTokens + inputImageTokens > inputTokens || outputAudioTokens + outputImageTokens > outputTokens) {
    throw Object.assign(new Error("provider_modality_usage_invalid"), { httpStatus: 409, safeError: "provider_unavailable" });
  }
  const textInputTokens = inputTokens - inputAudioTokens - inputImageTokens;
  const textOutputTokens = outputTokens - outputAudioTokens - outputImageTokens;
  const cachedInputTokens = Math.min(textInputTokens, Math.max(0, Number(usage?.cachedInputTokens || 0)));
  const inputRate = pricing?.inputPriceUsdPerToken != null ? Number(pricing.inputPriceUsdPerToken) * 1_000_000 : Number(pricing?.inputPriceUsdPerMillion);
  const outputRate = pricing?.outputPriceUsdPerToken != null ? Number(pricing.outputPriceUsdPerToken) * 1_000_000 : Number(pricing?.outputPriceUsdPerMillion);
  const cachedRate = pricing?.cachedInputPriceUsdPerToken != null ? Number(pricing.cachedInputPriceUsdPerToken) * 1_000_000
    : pricing?.cachedInputPriceUsdPerMillion == null ? inputRate : Number(pricing.cachedInputPriceUsdPerMillion);
  const providerRatesConfigured = [inputRate, outputRate, cachedRate].every(x => Number.isFinite(x) && x >= 0);
  const uncachedInputTokens = Math.max(0, textInputTokens - cachedInputTokens);
  const inputCostUsd = providerRatesConfigured ? (uncachedInputTokens / 1_000_000) * inputRate : 0;
  const cachedInputCostUsd = providerRatesConfigured ? (cachedInputTokens / 1_000_000) * cachedRate : 0;
  const outputCostUsd = providerRatesConfigured ? (textOutputTokens / 1_000_000) * outputRate : 0;
  const providerCostUsd =
    inputCostUsd + cachedInputCostUsd + outputCostUsd +
    (inputAudioTokens / 1_000_000) * Math.max(0, Number(pricing?.inputAudioPriceUsdPerMillion || 0)) +
    (outputAudioTokens / 1_000_000) * Math.max(0, Number(pricing?.outputAudioPriceUsdPerMillion || 0)) +
    (inputImageTokens / 1_000_000) * Math.max(0, Number(pricing?.inputImagePriceUsdPerMillion || 0)) +
    (outputImageTokens / 1_000_000) * Math.max(0, Number(pricing?.outputImagePriceUsdPerMillion || 0));
  const meter = pricing?.sharedCreditMetering;
  if (!meter || typeof meter !== "object") {
    throw Object.assign(new Error("token_pricing_not_configured"), { httpStatus: 409, safeError: "provider_unavailable" });
  }
  const inputCreditRate = Number(meter.creditsPerInputToken);
  const cachedCreditRate = Number(meter.creditsPerCachedInputToken);
  const outputCreditRate = Number(meter.creditsPerOutputToken);
  const conversionRate = Number(meter.conversionRate ?? 1);
  const minimumCredits = Number(meter.minimumCredits ?? 0);
  const precision = Number(meter.precision ?? 7);
  const modalityRates = [
    ["input audio", inputAudioTokens, meter.creditsPerInputAudioToken],
    ["output audio", outputAudioTokens, meter.creditsPerOutputAudioToken],
    ["input image", inputImageTokens, meter.creditsPerInputImageToken],
    ["output image", outputImageTokens, meter.creditsPerOutputImageToken]
  ];
  for (const [label, count, rate] of modalityRates) {
    if (count > 0 && (rate == null || !Number.isFinite(Number(rate)) || Number(rate) < 0)) {
      throw Object.assign(new Error("modality_token_pricing_not_configured:" + label), { httpStatus: 409, safeError: "provider_unavailable" });
    }
  }
  const values = [inputCreditRate, cachedCreditRate, outputCreditRate, conversionRate, minimumCredits, precision];
  if (!values.every(Number.isFinite) || inputCreditRate < 0 || cachedCreditRate < 0 || outputCreditRate < 0 ||
      conversionRate < 0 || minimumCredits < 0 || !Number.isInteger(precision) || precision < 0 || precision > 8) {
    throw Object.assign(new Error("shared_token_metering_invalid"), { httpStatus: 409, safeError: "provider_unavailable" });
  }
  const rawCredits =
    uncachedInputTokens * inputCreditRate +
    cachedInputTokens * cachedCreditRate +
    textOutputTokens * outputCreditRate +
    inputAudioTokens * Number(meter.creditsPerInputAudioToken || 0) +
    outputAudioTokens * Number(meter.creditsPerOutputAudioToken || 0) +
    inputImageTokens * Number(meter.creditsPerInputImageToken || 0) +
    outputImageTokens * Number(meter.creditsPerOutputImageToken || 0);
  const roundedCredits = rawCredits > 0
    ? Math.max(minimumCredits, Math.round((rawCredits * conversionRate + Number.EPSILON) * (10 ** precision)) / (10 ** precision))
    : 0;
  const creditValue = Number(creditReferenceValueUsd);
  const safeCreditValue = Number.isFinite(creditValue) && creditValue > 0 ? creditValue : DEFAULT_CREDIT_REFERENCE_USD;
  const grossUsageValueUsd = roundedCredits * safeCreditValue;
  const providerCostCredits = providerCostUsd / safeCreditValue;
  const estimatedGrossProfitUsd = grossUsageValueUsd - providerCostUsd;
  return {
    inputTokens, outputTokens, cachedInputTokens, inputAudioTokens, outputAudioTokens, inputImageTokens, outputImageTokens,
    inputCostUsd, cachedInputCostUsd, outputCostUsd, providerCostUsd,
    creditReferenceValueUsd: safeCreditValue, customerMultiplier: 1, estimatedFeePercent: 0, estimatedFeeUsd: 0,
    providerCostCredits, customerCredits: roundedCredits, grossUsageValueUsd, estimatedGrossProfitUsd,
    estimatedNetProfitUsd: estimatedGrossProfitUsd
  };
}

export function matchesCustomerClass(rule, customerClass = "paid") {
  const configuredClass = String(rule?.customerClass || "all").toLowerCase();
  const requestedClass = customerClass === "free" ? "free" : "paid";
  return configuredClass === "all" || configuredClass === requestedClass;
}

function customerCreditsPerToken(rule, tokenField, legacyField) {
  const direct = rule?.[tokenField];
  if (typeof direct === "number" && Number.isFinite(direct)) return direct;
  const legacy = rule?.[legacyField];
  return typeof legacy === "number" && Number.isFinite(legacy) ? legacy / 1000 : Number.NaN;
}

export async function resolveTokenPricing(providerId, modelId, feature, control = {}) {
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
  const tokenRules = await firestoreQuery("tokenPricingRules", { limit: 200 }).catch(() => []);
  const featureAliases = new Set([String(feature || "")]);
  // The website's token-pricing engine uses "chat"; older model-registry and
  // native callers may label the same Main Chat feature "text-chat". Treat
  // those as aliases so a scoped website price rule is not silently skipped.
  if (featureAliases.has("chat")) featureAliases.add("text-chat");
  if (featureAliases.has("text-chat")) featureAliases.add("chat");
  const tokenRule = (Array.isArray(tokenRules) ? tokenRules : []).filter(rule => {
    if (String(rule.status || "inactive") !== "active") return false;
    if (!matchesCustomerClass(rule, control.customerClass || "paid")) return false;
    if (!((["gemini", "google", "google-gemini"].includes(String(providerId || "").toLowerCase()) && ["gemini", "google", "google-gemini"].includes(String(rule.provider || "").toLowerCase())) || (["lovable-gateway", "lovable", "gateway"].includes(String(providerId || "").toLowerCase()) && ["lovable-gateway", "lovable", "gateway"].includes(String(rule.provider || "").toLowerCase())) || (String(rule.provider || "").toLowerCase() === String(providerId || "").toLowerCase()))) return false;
    if (String(rule.model || "*") !== "*" && String(rule.model) !== String(modelId || "")) return false;
    if (Array.isArray(rule.features) && rule.features.length && !rule.features.some(name => featureAliases.has(String(name)))) return false;
    if (Array.isArray(rule.plans) && rule.plans.length && (!control.planId || !rule.plans.includes(String(control.planId)))) return false;
    if (rule.effectiveFrom && new Date(rule.effectiveFrom).getTime() > Date.now()) return false;
    return true;
  }).sort((a, b) => {
    const specificity = (String(a.model || "*") === "*" ? 1 : 0) - (String(b.model || "*") === "*" ? 1 : 0);
    if (specificity !== 0) return specificity;
    const classSpecificity = (String(a.customerClass || "all") === "all" ? 1 : 0) - (String(b.customerClass || "all") === "all" ? 1 : 0);
    if (classSpecificity !== 0) return classSpecificity;
    const effective = new Date(b.effectiveFrom || 0).getTime() - new Date(a.effectiveFrom || 0).getTime();
    if (Number.isFinite(effective) && effective !== 0) return effective;
    return Number(b.version || 0) - Number(a.version || 0);
  })[0] || null;
  const applyFeeToUsage = false;

  // The website admin dashboard and the Android app share Firebase project
  // bqsqwadai. Website AI model metering is persisted at config/aiModels;
  // use it as the common fallback when this Worker has no per-model registry
  // price, while preserving an explicitly configured registry price as the
  // highest-priority source.
  const sharedAiConfig = await firestoreGet("config/aiModels").catch(() => null);
  const sharedMetering = sharedAiConfig?.metering && typeof sharedAiConfig.metering === "object" ? sharedAiConfig.metering : { maxRequestTokens: 8_000, maxOutputTokens: 512 };
  const boundedLimit = (value, fallback, min, max) => {
    const number = Number(value);
    return Number.isFinite(number) ? Math.min(max, Math.max(min, Math.round(number))) : fallback;
  };
  const maxRequestTokens = boundedLimit(sharedMetering.maxRequestTokens, 8_000, 1_000, 16_000);
  const maxOutputTokens = boundedLimit(sharedMetering.maxOutputTokens, 512, 128, 1_024);
  // Provider USD rates are private cost telemetry. Admin Live Credit Pricing
  // stores them per million tokens; customer deductions still use the separate
  // credits-per-token values in sharedCreditMetering below.
  const providerInputUsdPerMillion = Number(tokenRule?.inputUsdPerMillion);
  const providerOutputUsdPerMillion = Number(tokenRule?.outputUsdPerMillion);
  const providerCachedUsdPerMillion = Number(tokenRule?.cachedInputUsdPerMillion);
  const rates = Number.isFinite(providerInputUsdPerMillion) && providerInputUsdPerMillion >= 0 &&
    Number.isFinite(providerOutputUsdPerMillion) && providerOutputUsdPerMillion >= 0
    ? {
        inputPriceUsdPerMillion: providerInputUsdPerMillion,
        outputPriceUsdPerMillion: providerOutputUsdPerMillion,
        cachedInputPriceUsdPerMillion: Number.isFinite(providerCachedUsdPerMillion) && providerCachedUsdPerMillion >= 0
          ? providerCachedUsdPerMillion
          : providerInputUsdPerMillion
      }
    : {};
  const modalityProviderRates = {
    inputAudioPriceUsdPerMillion: Number(tokenRule?.inputAudioUsdPerMillion || 0),
    outputAudioPriceUsdPerMillion: Number(tokenRule?.outputAudioUsdPerMillion || 0),
    inputImagePriceUsdPerMillion: Number(tokenRule?.inputImageUsdPerMillion || 0),
    outputImagePriceUsdPerMillion: Number(tokenRule?.outputImageUsdPerMillion || 0)
  };
  const creditReferenceValueUsd = DEFAULT_CREDIT_REFERENCE_USD;
  const customerMultiplier = 1;
  const estimatedFeePercent = 0;
  // Input/output ceilings are enforced by the shared website-admin token budget.
  // Sanity-check normalized USD-per-million rates. A per-million value
  // accidentally entered into a per-token field otherwise becomes millions
  // of times larger and can drain a balance on a short prompt.
  const featureKey = String(feature || "").toLowerCase();
  const isTokenMeteredFeature = ["chat", "text-chat", "vision", "chat_attachment", "personal_intelligence", "team_chat", "deep_research"].includes(featureKey);
  // Website tokenPricingRules are the canonical customer-credit prices for
  // both website and Android. All three customer rates must be explicit.
  const customerInputCreditsPerToken = customerCreditsPerToken(tokenRule, "inputCreditsPerToken", "inputCreditsPer1k");
  const customerCachedCreditsPerToken = customerCreditsPerToken(tokenRule, "cachedInputCreditsPerToken", "cachedInputCreditsPer1k");
  const customerOutputCreditsPerToken = customerCreditsPerToken(tokenRule, "outputCreditsPerToken", "outputCreditsPer1k");
  const hasCustomerCreditRule = !!tokenRule &&
    [customerInputCreditsPerToken, customerCachedCreditsPerToken, customerOutputCreditsPerToken].every(rate => Number.isFinite(rate) && rate >= 0);
  if (isTokenMeteredFeature && !hasCustomerCreditRule) {
    throw Object.assign(new Error("token_pricing_not_configured"), { httpStatus: 503, safeError: "feature_temporarily_unavailable" });
  }
  const configured = isTokenMeteredFeature && hasCustomerCreditRule;
  const sharedCreditMetering = isTokenMeteredFeature ? {
    creditsPerInputToken: customerInputCreditsPerToken,
    creditsPerOutputToken: customerOutputCreditsPerToken,
    creditsPerCachedInputToken: customerCachedCreditsPerToken,
    creditsPerInputAudioToken: typeof tokenRule.inputAudioCreditsPerToken === "number" ? tokenRule.inputAudioCreditsPerToken : null,
    creditsPerOutputAudioToken: typeof tokenRule.outputAudioCreditsPerToken === "number" ? tokenRule.outputAudioCreditsPerToken : null,
    creditsPerInputImageToken: typeof tokenRule.inputImageCreditsPerToken === "number" ? tokenRule.inputImageCreditsPerToken : null,
    creditsPerOutputImageToken: typeof tokenRule.outputImageCreditsPerToken === "number" ? tokenRule.outputImageCreditsPerToken : null,
    minimumCredits: Math.max(0, Number(tokenRule.minimumCredits ?? 0)),
    fallbackCredits: 0,
    precision: 7,
    conversionRate: 1
  } : null;
  return {
    ...rates, ...modalityProviderRates, maxRequestTokens, maxOutputTokens, creditReferenceValueUsd, customerMultiplier,
    applyFeeToUsage, estimatedFeePercent, configured,
    sharedCreditMetering,
    sharedModelMultiplier: 1,
    billingMode: isTokenMeteredFeature && hasCustomerCreditRule ? "token" : String(model.billingMode || control.billingMode || "fixed_credit"),
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
  const estimatedFeePercent = Math.min(100, Math.max(0, Number(calculation?.estimatedFeePercent ?? pricing?.estimatedFeePercent ?? 0)));
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
    inputAudioTokens: Number(usage?.inputAudioTokens || 0),
    outputAudioTokens: Number(usage?.outputAudioTokens || 0),
    inputImageTokens: Number(usage?.inputImageTokens || 0),
    outputImageTokens: Number(usage?.outputImageTokens || 0),
    modalityUsage: usage?.modalityUsage && typeof usage.modalityUsage === "object" ? usage.modalityUsage : {},
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
