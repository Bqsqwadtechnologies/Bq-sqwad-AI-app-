import { firestoreGet, firestoreSet, firestoreQuery, firestoreCreate } from "./firebase.js";

const FEATURE_CAPABILITIES = {
  "text-chat": "chat",
  "live-camera": "live_camera",
  "image-generation": "image_generation",
  "device-commands": "device_commands",
  "video-generation": "video_generation",
  "live-voice": "live_voice"
};

const PROVIDERS = new Set(["openai", "gemini"]);

function normalizeModel(raw, actorUid) {
  const id = String(raw?.id || "").trim().toLowerCase();
  const name = String(raw?.name || "").trim().slice(0, 100);
  const providerId = String(raw?.providerId || "").trim().toLowerCase();
  const modelId = String(raw?.modelId || "").trim();
  const feature = String(raw?.feature || "text-chat").trim().toLowerCase();
  const creditCost = Number(raw?.creditCost);
  const priceUsd = Number(raw?.priceUsd ?? 0);
  const creditsPerMinute = raw?.creditsPerMinute == null || raw?.creditsPerMinute === "" ? null : Number(raw.creditsPerMinute);
  const billingMode = ["fixed_credit", "token", "provider_cost"].includes(String(raw?.billingMode || "fixed_credit")) ? String(raw?.billingMode || "fixed_credit") : "fixed_credit";
  const providerCostUnit = String(raw?.providerCostUnit || (feature === "image-generation" ? "image" : ["video-generation", "live-camera"].includes(feature) ? "second" : feature === "live-voice" ? "minute" : "request"));
  const providerCostTrackingEnabled = raw?.providerCostTrackingEnabled === true;
  const inputPriceUsdPerMillion = Number(raw?.inputPriceUsdPerMillion ?? 0);
  const outputPriceUsdPerMillion = Number(raw?.outputPriceUsdPerMillion ?? 0);
  const cachedInputPriceUsdPerMillion = raw?.cachedInputPriceUsdPerMillion == null || raw?.cachedInputPriceUsdPerMillion === "" ? null : Number(raw.cachedInputPriceUsdPerMillion);
  const creditReferenceValueUsd = Number(raw?.creditReferenceValueUsd ?? 0.1199);
  const customerMultiplier = Number(raw?.customerMultiplier ?? 1.8);
  const estimatedFeePercent = Number(raw?.estimatedFeePercent ?? 15);
  const maxOutputTokens = Number(raw?.maxOutputTokens ?? 2048);
  if (!/^[a-z0-9][a-z0-9-]{1,79}$/.test(id)) throw new Error("model_id_invalid");
  if (!name) throw new Error("model_name_required");
  if (!PROVIDERS.has(providerId)) throw new Error("model_provider_unsupported");
  if (!FEATURE_CAPABILITIES[feature]) throw new Error("model_feature_unsupported");
  if (!modelId) throw new Error("model_provider_model_required");
  const credit = Number.isFinite(creditCost) && creditCost >= 0 ? Math.round(creditCost * 1e5) / 1e5 : 0;
  return {
    id, name, providerId, modelId, feature,
    creditCost: credit,
    priceUsd: Math.max(0, Number.isFinite(priceUsd) ? priceUsd : 0),
    creditsPerMinute: creditsPerMinute == null || !Number.isFinite(creditsPerMinute) ? null : Math.max(0, creditsPerMinute),
    billingMode, providerCostUnit, providerCostTrackingEnabled,
    inputPriceUsdPerMillion: Math.max(0, Number.isFinite(inputPriceUsdPerMillion) ? inputPriceUsdPerMillion : 0),
    outputPriceUsdPerMillion: Math.max(0, Number.isFinite(outputPriceUsdPerMillion) ? outputPriceUsdPerMillion : 0),
    cachedInputPriceUsdPerMillion: cachedInputPriceUsdPerMillion == null || !Number.isFinite(cachedInputPriceUsdPerMillion) ? null : Math.max(0, cachedInputPriceUsdPerMillion),
    creditReferenceValueUsd: Math.max(0.000001, Number.isFinite(creditReferenceValueUsd) ? creditReferenceValueUsd : 0.1199),
    customerMultiplier: Math.max(1, Number.isFinite(customerMultiplier) ? customerMultiplier : 1.8),
    estimatedFeePercent: Math.min(100, Math.max(0, Number.isFinite(estimatedFeePercent) ? estimatedFeePercent : 15)),
    maxOutputTokens: Math.min(8192, Math.max(128, Number.isFinite(maxOutputTokens) ? maxOutputTokens : 2048)),
    active: raw?.active !== false,
    published: raw?.published === true,
    isDefault: raw?.isDefault === true,
    updatedAt: new Date(),
    updatedBy: actorUid || null
  };
}

export async function listAiModels() {
  const rows = await firestoreQuery("aiModelRegistry", { limit: 200 });
  return rows.map(row => ({
    ...row,
    id: String(row.id || String(row.__name || "").split("/").pop())
  }));
}

export async function ensureCurrentModelRegistry() {
  return { ok: true };
}

export async function validateAiModel(env, raw) {
  const providerId = String(raw?.providerId || "").trim().toLowerCase();
  const modelId = String(raw?.modelId || "").trim();
  const feature = String(raw?.feature || "text-chat").trim().toLowerCase();
  const allowedProviders = {
    "text-chat": new Set(["openai", "gemini"]),
    "live-camera": new Set(["openai"]),
    "image-generation": new Set(["openai"]),
    "live-voice": new Set(["openai"]),
    "device-commands": new Set(["openai", "gemini"]),
    "video-generation": new Set(["openai"])
  };
  if (!FEATURE_CAPABILITIES[feature] || !allowedProviders[feature]?.has(providerId)) {
    throw Object.assign(new Error("provider_not_supported_for_feature"), { httpStatus: 400 });
  }
  const prefixRules = {
    "live-camera": /^(?:gpt-realtime|gpt-4o-realtime)/i,
    "live-voice": /^(?:gpt-realtime|gpt-4o-realtime)/i,
    "image-generation": /^gpt-image/i,
    "video-generation": /^sora/i,
  };
  if (prefixRules[feature] && !prefixRules[feature].test(modelId)) {
    throw Object.assign(new Error("model_id_does_not_match_feature"), { httpStatus: 400 });
  }
  let url, headers;
  if (providerId === "openai") {
    const key = String(env?.OPENAI_API_KEY || "");
    if (!key) throw Object.assign(new Error("openai_provider_not_configured"), { httpStatus: 503 });
    url = "https://api.openai.com/v1/models/" + encodeURIComponent(modelId);
    headers = { Authorization: "Bearer " + key };
  } else {
    const key = String(env?.GEMINI_API_KEY || "");
    if (!key) throw Object.assign(new Error("gemini_provider_not_configured"), { httpStatus: 503 });
    url = "https://generativelanguage.googleapis.com/v1beta/models/" + encodeURIComponent(modelId);
    headers = { "x-goog-api-key": key };
  }
  let response;
  try {
    response = await fetch(url, { method: "GET", headers, signal: AbortSignal.timeout(8000) });
  } catch (error) {
    const timedOut = error?.name === "TimeoutError" || error?.name === "AbortError";
    throw Object.assign(
      new Error(timedOut ? "provider_model_validation_timeout" : "provider_model_validation_network_error"),
      { httpStatus: 503 }
    );
  }
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const reason = String(body?.error?.message || "provider_model_not_found_or_unavailable");
    throw Object.assign(new Error(reason), {
      httpStatus: 404,
      safeError: "provider_model_not_found_or_unavailable"
    });
  }
  return {
    ok: true,
    modelId,
    providerModelName: String(body?.displayName || body?.id || body?.name || modelId),
    validatedAt: new Date().toISOString()
  };
}

export async function checkAiModelStatus(env, model) {
  try {
    const result = await validateAiModel(env, model);
    return { providerStatus: "available", providerStatusMessage: "", providerCheckedAt: result.validatedAt, validation: result };
  } catch (error) {
    return { providerStatus: "unavailable", providerStatusMessage: String(error?.safeError || error?.message || "unavailable"), providerCheckedAt: new Date().toISOString(), validation: null };
  }
}

export async function saveAiModel(env, raw, actorUid) {
  const model = normalizeModel(raw, actorUid);
  if (model.isDefault) {
    const rows = await firestoreQuery("aiModelRegistry", { limit: 200 });
    for (const row of rows) {
      const otherId = String(row.id || String(row.__name || "").split("/").pop());
      if (otherId && otherId !== model.id && row.feature === model.feature && row.isDefault === true) {
        await firestoreSet("aiModelRegistry/" + otherId, { ...row, isDefault: false, updatedAt: new Date() }, true);
      }
    }
  }
  await firestoreSet("aiModelRegistry/" + model.id, model, true);
  return { ok: true, model };
}

export async function resolvePublishedModel(modelId, expectedFeature) {
  const rows = await firestoreQuery("aiModelRegistry", { limit: 200 });
  let model = rows.find(x => String(x.id || String(x.__name || "").split("/").pop()) === String(modelId || ""));
  if (!model) model = rows.find(x => x.isDefault === true && x.active === true && x.published === true && (!expectedFeature || x.feature === expectedFeature));
  if (!model) model = rows.find(x => x.active === true && x.published === true && (!expectedFeature || x.feature === expectedFeature));
  if (!model) return null;
  return {
    id: String(model.id || String(model.__name || "").split("/").pop()),
    name: String(model.name || ""),
    providerId: String(model.providerId || ""),
    modelId: String(model.modelId || ""),
    feature: String(model.feature || ""),
    creditCost: (function(){ const n = Number(model.creditCost); return Number.isFinite(n) && n >= 0 ? Math.round(n*1e5)/1e5 : 1; })(),
    priceUsd: Math.max(0, Number(model.priceUsd || 0)),
    creditsPerMinute: model.creditsPerMinute == null ? null : Math.max(0, Number(model.creditsPerMinute || 0)),
    billingMode: String(model.billingMode || "fixed_credit"),
    providerCostUnit: String(model.providerCostUnit || "request"),
    providerCostTrackingEnabled: model.providerCostTrackingEnabled === true,
    inputPriceUsdPerMillion: Math.max(0, Number(model.inputPriceUsdPerMillion || 0)),
    outputPriceUsdPerMillion: Math.max(0, Number(model.outputPriceUsdPerMillion || 0)),
    cachedInputPriceUsdPerMillion: model.cachedInputPriceUsdPerMillion == null ? null : Math.max(0, Number(model.cachedInputPriceUsdPerMillion || 0)),
    creditReferenceValueUsd: Math.max(0.000001, Number(model.creditReferenceValueUsd || 0.1199)),
    customerMultiplier: Math.max(1, Number(model.customerMultiplier || 1.8)),
    estimatedFeePercent: Math.min(100, Math.max(0, Number(model.estimatedFeePercent || 15))),
    maxOutputTokens: Math.min(8192, Math.max(128, Number(model.maxOutputTokens || 2048)))
  };
}
