/**
 * Prompt J Part 1 — primary (OpenAI) then at most one Gemini fallback.
 * Fall back only on 429/5xx/timeout/empty/malformed; never on auth/validation.
 * Cost: fallback only if enabled, priced, and not more expensive unless admin allows.
 */
import { callGemini, callOpenAI, DEFAULT_MODELS } from "./providers.js";

const FALLBACK_ELIGIBLE = /provider_http_(429|5\d\d)|timeout|empty|malformed|blocked|missing model|no first token/i;
const NO_FALLBACK = /auth|unauthorized|forbidden|validation|invalid_request|sign.?in/i;

export function shouldFallback(error) {
  const msg = String(error?.message || error || "");
  if (NO_FALLBACK.test(msg)) return false;
  return FALLBACK_ELIGIBLE.test(msg) || /empty response/i.test(msg);
}

export function estimateCostUsd(pricing, inputTokens, outputTokens) {
  if (!pricing) return 0;
  const inRate = Number(pricing.inputPer1kUsd || pricing.inputUsdPer1k || 0);
  const outRate = Number(pricing.outputPer1kUsd || pricing.outputUsdPer1k || 0);
  return (inputTokens / 1000) * inRate + (outputTokens / 1000) * outRate;
}

/**
 * @param {object} route admin routing: primaryProviderId, primaryModelId, fallbackEnabled,
 *   fallbackProviderId, fallbackModelId, allowCostlierFallback, primaryPricing, fallbackPricing
 */
export async function runWithFallback(env, route, input) {
  const primaryModel = route.primaryModelId || DEFAULT_MODELS.openai;
  const primaryProvider = route.primaryProviderId || "openai";
  let lastError;
  try {
    if (primaryProvider === "openai") {
      const response = await callOpenAI(env, { ...input, model: primaryModel, stream: input.stream === true });
      if (!response.ok) {
        const raw = await response.text();
        throw new Error("provider_http_" + response.status + ":" + raw.slice(0, 400));
      }
      if (input.stream) {
        return { response, providerId: "openai", modelId: primaryModel, route: "primary", streamed: true };
      }
      const body = await response.json();
      const text = body.output_text || body.output?.flatMap((x) => x.content || []).map((x) => x.text || "").join("") || "";
      if (!String(text).trim()) throw new Error("empty response from primary");
      return { text, providerId: "openai", modelId: primaryModel, usage: body.usage || {}, route: "primary", streamed: false };
    }
    if (primaryProvider === "gemini") {
      const result = await callGemini(env, { ...input, model: primaryModel });
      if (!result.text?.trim()) throw new Error("empty response from primary");
      return { ...result, route: "primary" };
    }
  } catch (error) {
    lastError = error;
    if (!route.fallbackEnabled || !shouldFallback(error)) throw error;
    const pCost = estimateCostUsd(route.primaryPricing, input.estimateInputTokens || 1000, input.estimateOutputTokens || 256);
    const fCost = estimateCostUsd(route.fallbackPricing, input.estimateInputTokens || 1000, input.estimateOutputTokens || 256);
    if (fCost > pCost && !route.allowCostlierFallback) throw error;
  }

  const fbModel = route.fallbackModelId || DEFAULT_MODELS.gemini;
  try {
    const result = await callGemini(env, { ...input, model: fbModel });
    if (!result.text?.trim()) throw lastError || new Error("empty response from fallback");
    return { ...result, route: "fallback" };
  } catch (error) {
    throw lastError || error;
  }
}

export function settlementLedgerEntry(meta) {
  return {
    provider: meta.providerId,
    model: meta.modelId,
    route: meta.route || "primary",
    inputTokens: Number(meta.inputTokens || 0),
    cachedInputTokens: Number(meta.cachedInputTokens || 0),
    outputTokens: Number(meta.outputTokens || 0),
    estimatedCostUsd: Number(meta.estimatedCostUsd || 0),
    reportedCostUsd: Number(meta.reportedCostUsd || 0),
    credits: Number(meta.credits || 0),
    status: meta.status || "ok",
    requestId: meta.requestId || null,
    at: new Date().toISOString(),
  };
}
