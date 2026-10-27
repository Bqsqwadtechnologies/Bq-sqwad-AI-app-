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
  // Bill measured tokens using the configured provider rates, customer markup,
  // fee and credit value. This is the shared website/Worker billing formula.
  // Explicit zero rates remain valid; absent or invalid rates use conservative
  // reference rates rather than turning a metered chat into a fixed one-credit charge.
  const uncached = nonNegative(input?.uncachedInputTokens);
  const cached = nonNegative(input?.cachedInputTokens);
  const output = nonNegative(input?.outputTokens);
  const rate = (direct, legacy, fallback) => {
    if (direct !== null && direct !== undefined && Number.isFinite(Number(direct)) && Number(direct) >= 0) return Number(direct);
    if (legacy !== null && legacy !== undefined && Number.isFinite(Number(legacy)) && Number(legacy) >= 0) return Number(legacy);
    return fallback;
  };
  const inputRate = rate(rates?.inputUsdPerToken, rates?.inputUsdPerMillionTokens != null ? Number(rates.inputUsdPerMillionTokens) / 1_000_000 : undefined, 0.00000005);
  const cachedRate = rate(rates?.cachedInputUsdPerToken, rates?.cachedInputUsdPerMillionTokens != null ? Number(rates.cachedInputUsdPerMillionTokens) / 1_000_000 : undefined, inputRate * 0.1);
  const outputRate = rate(rates?.outputUsdPerToken, rates?.outputUsdPerMillionTokens != null ? Number(rates.outputUsdPerMillionTokens) / 1_000_000 : undefined, 0.00000025);
  const providerCostUsd = uncached * inputRate + cached * cachedRate + output * outputRate;
  const markupPercent = nonNegative(policy?.markupPercent, 600);
  const feePercent = nonNegative(policy?.feePercent, 0);
  const usdPerCredit = nonNegative(policy?.usdPerCredit, 0.1199) || 0.1199;
  const customerUsd = providerCostUsd * (1 + markupPercent / 100) * (1 + feePercent / 100);
  const credits = round5(customerUsd / usdPerCredit);
  return { providerCostUsd, customerUsd, markupPercent, feePercent, credits };
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
function unwrapConfigDocument(value, name, warnings) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return value || {};
  // The website's config/pricing document stores the public pricing object in
  // a JSON string. Other canonical config documents are normally plain maps.
  if (typeof value.json === "string") {
    try {
      const parsed = JSON.parse(value.json);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed;
      warnings.push(name + " contains invalid JSON config; defaults may apply");
      return {};
    } catch {
      warnings.push(name + " contains invalid JSON config; defaults may apply");
      return {};
    }
  }
  return value;
}

export async function loadBillingConfig(firestoreGet, now = Date.now()) {
  const [usageBillingDoc, providerConnectionsDoc, pricingDoc, aiModelsDoc] = await Promise.all([
    firestoreGet("config/usageBilling"),
    firestoreGet("config/providerConnections"),
    firestoreGet("config/pricing"),
    firestoreGet("config/aiModels")
  ]);
  const warnings = [];
  if (!usageBillingDoc) warnings.push("config/usageBilling missing; canonical billing defaults are active");
  if (!providerConnectionsDoc) warnings.push("config/providerConnections missing; live-media defaults require admin review");
  if (!pricingDoc) warnings.push("config/pricing missing; catalog defaults require admin review");
  if (!aiModelsDoc) warnings.push("config/aiModels missing; model rates require admin review");
  const usageBilling = unwrapConfigDocument(usageBillingDoc, "config/usageBilling", warnings);
  const providerConnections = unwrapConfigDocument(providerConnectionsDoc, "config/providerConnections", warnings);
  const pricing = unwrapConfigDocument(pricingDoc, "config/pricing", warnings);
  const aiModels = unwrapConfigDocument(aiModelsDoc, "config/aiModels", warnings);
  return {
    loadedAt: now,
    usageBilling: {
      markupPercent: nonNegative(usageBilling?.markupPercent, 600),
      feePercent: nonNegative(usageBilling?.feePercent, 0),
      usdPerCredit: nonNegative(usageBilling?.usdPerCredit, 0.1199) || 0.1199
    },
    providerConnections,
    pricing,
    aiModels,
    warnings
  };
}
