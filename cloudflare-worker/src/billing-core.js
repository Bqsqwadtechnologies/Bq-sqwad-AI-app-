/**
 * Pure fixed-credit rounding.
 * Token, live media, search and other unit prices are resolved from shared
 * model-specific tokenPricingRules or generationCostRules records.
 */
export function round7(value) {
  return Math.round((Number(value) + Number.EPSILON) * 10_000_000) / 10_000_000;
}
function nonNegative(value, fallback = 0) {
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? n : fallback;
}
export function computeFixedCredits(configuredCredits) {
  return round7(nonNegative(configuredCredits));
}
