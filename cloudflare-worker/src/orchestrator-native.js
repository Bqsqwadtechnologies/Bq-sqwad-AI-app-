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
  // resolveTokenPricing returns USD per token; legacy admin routes may return
  // USD per 1K. Normalize both without ever substituting another provider's rate.
  const inputPer1k = pricing.inputPer1kUsd != null
    ? Number(pricing.inputPer1kUsd)
    : pricing.inputUsdPer1k != null
      ? Number(pricing.inputUsdPer1k)
      : Number(pricing.inputPriceUsdPerToken || 0) * 1000;
  const outputPer1k = pricing.outputPer1kUsd != null
    ? Number(pricing.outputPer1kUsd)
    : pricing.outputUsdPer1k != null
      ? Number(pricing.outputUsdPer1k)
      : Number(pricing.outputPriceUsdPerToken || 0) * 1000;
  return (Math.max(0, inputTokens) / 1000) * Math.max(0, inputPer1k)
    + (Math.max(0, outputTokens) / 1000) * Math.max(0, outputPer1k);
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

  const fallbackProvider = route.fallbackProviderId || route.fallbackProviderIds?.[0] || "gemini";
  const fbModel = route.fallbackModelId || route.fallbackModels?.[fallbackProvider] || DEFAULT_MODELS.gemini;
  try {
    if (fallbackProvider !== "gemini") throw new Error("unsupported_fallback_provider:" + fallbackProvider);
    // Streaming fallback is deliberately non-streaming: this branch is reached
    // only before primary stream bytes have been returned to the client.
    const result = await callGemini(env, { ...input, stream: false, model: fbModel });
    if (!result.text?.trim()) throw lastError || new Error("empty response from fallback");
    return { ...result, route: "fallback", streamed: false };
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
