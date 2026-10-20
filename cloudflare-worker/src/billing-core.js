/**
 * Canonical billing math. This module is intentionally pure: callers load and
 * cache Firestore configuration, then pass the resolved policy and measured
 * provider usage here. Never pass client-estimated token counts.
 */
export function round5(value) {
  return Math.round((Number(value) + Number.EPSILON) * 100000) / 100000;
}

function nonNegative(value, fallback = 0) {
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? n : fallback;
}

export function computeTokenCredits(input, policy, rates) {
  const markupPercent = nonNegative(policy?.markupPercent, 600);
  const feePercent = nonNegative(policy?.feePercent, 0);
  const usdPerCredit = nonNegative(policy?.usdPerCredit, 0.1199);
  if (usdPerCredit <= 0) throw new Error("billing_usd_per_credit_invalid");
  const uncached = nonNegative(input?.uncachedInputTokens);
  const cached = nonNegative(input?.cachedInputTokens);
  const output = nonNegative(input?.outputTokens);
  const providerCostUsd =
    uncached * nonNegative(rates?.inputUsdPerToken) +
    cached * nonNegative(rates?.cachedInputUsdPerToken, nonNegative(rates?.inputUsdPerToken)) +
    output * nonNegative(rates?.outputUsdPerToken);
  const customerUsd = providerCostUsd * (1 + markupPercent / 100) * (1 + feePercent / 100);
  return { providerCostUsd, customerUsd, markupPercent, feePercent, credits: round5(customerUsd / usdPerCredit) };
}

export function computeMinuteCredits(input, policy, rateUsdPerMinute, overrideCreditsPerMinute = null) {
  const seconds = nonNegative(input?.elapsedSeconds);
  if (overrideCreditsPerMinute !== null && overrideCreditsPerMinute !== undefined && overrideCreditsPerMinute !== "") {
    const override = nonNegative(overrideCreditsPerMinute);
    return { providerCostUsd: seconds / 60 * nonNegative(rateUsdPerMinute), credits: round5(seconds / 60 * override), source: "creditsPerMinute" };
  }
  const result = computeTokenCredits({ uncachedInputTokens: 0, cachedInputTokens: 0, outputTokens: 0 },
    policy, { inputUsdPerToken: 0, cachedInputUsdPerToken: 0, outputUsdPerToken: 0 });
  const providerCostUsd = seconds / 60 * nonNegative(rateUsdPerMinute);
  const customerUsd = providerCostUsd * (1 + result.markupPercent / 100) * (1 + result.feePercent / 100);
  return { providerCostUsd, customerUsd, markupPercent: result.markupPercent, feePercent: result.feePercent, credits: round5(customerUsd / nonNegative(policy?.usdPerCredit, 0.1199)), source: "providerRate" };
}

export function computeFixedCredits(configuredCredits) {
  return round5(nonNegative(configuredCredits));
}

/**
 * Fetch the canonical website billing documents through the Worker Firestore
 * adapter. The caller should cache this object for ~60 seconds and surface
 * warnings whenever required documents are absent.
 */
export async function loadBillingConfig(firestoreGet, now = Date.now()) {
  const [usageBilling, providerConnections, pricing, aiModels] = await Promise.all([
    firestoreGet("config/usageBilling"),
    firestoreGet("config/providerConnections"),
    firestoreGet("config/pricing"),
    firestoreGet("config/aiModels")
  ]);
  const warnings = [];
  if (!usageBilling) warnings.push("config/usageBilling missing; canonical billing defaults are active");
  if (!providerConnections) warnings.push("config/providerConnections missing; live-media defaults require admin review");
  if (!pricing) warnings.push("config/pricing missing; catalog defaults require admin review");
  if (!aiModels) warnings.push("config/aiModels missing; model rates require admin review");
  return {
    loadedAt: now,
    usageBilling: {
      markupPercent: nonNegative(usageBilling?.markupPercent, 600),
      feePercent: nonNegative(usageBilling?.feePercent, 0),
      usdPerCredit: nonNegative(usageBilling?.usdPerCredit, 0.1199) || 0.1199
    },
    providerConnections: providerConnections || {},
    pricing: pricing || {},
    aiModels: aiModels || {},
    warnings
  };
}
