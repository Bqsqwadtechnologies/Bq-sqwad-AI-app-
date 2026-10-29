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

const SAFE_OPENAI_MODEL_BY_FEATURE = {"text-chat":"gpt-6-luna","device-commands":"gpt-6-luna","image-generation":"gpt-image-1-mini","live-camera":"gpt-realtime-2.1-mini","live-voice":"gpt-realtime-2.1-mini"};
const ALLOWED_OPENAI_MODELS_BY_FEATURE = {
  "text-chat": new Set(["gpt-6-luna"]),
  "device-commands": new Set(["gpt-6-luna"]),
  "image-generation": new Set(["gpt-image-1-mini"]),
  "live-camera": new Set(["gpt-realtime-2.1-mini"]),
  "live-voice": new Set(["gpt-realtime-2.1-mini"]),
  "video-generation": new Set()
};
function isAllowedOpenAiModel(modelId, feature) {
  return ALLOWED_OPENAI_MODELS_BY_FEATURE[String(feature || "").trim().toLowerCase()]?.has(String(modelId || "").trim().toLowerCase()) === true;
}

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
  if (!/^[a-z0-9][a-z0-9-]{1,79}$/.test(id)) throw new Error("model_id_invalid");
  if (!name) throw new Error("model_name_required");
  if (!PROVIDERS.has(providerId)) throw new Error("model_provider_unsupported");
  if (providerId === "openai" && !isAllowedOpenAiModel(modelId, feature)) throw new Error("openai_model_not_allowed_by_project_policy");
  const requiredProvider = ["image-generation", "video-generation", "live-voice"].includes(feature) ? "openai" : null;
  if (requiredProvider && providerId !== requiredProvider) throw new Error("model_provider_incompatible_with_feature");

  if (!/^[a-zA-Z0-9._:-]{1,120}$/.test(modelId)) throw new Error("provider_model_id_invalid");
  if (!FEATURE_CAPABILITIES[feature]) throw new Error("model_feature_unsupported");
  const allowedProviders = {
    "text-chat": new Set(["openai", "gemini"]),
    "live-camera": new Set(["openai"]),
    "image-generation": new Set(["openai"]),
    "live-voice": new Set(["openai"]),
    "device-commands": new Set(["openai", "gemini"]),
    "video-generation": new Set(["openai"])
  };
  if (!allowedProviders[feature].has(providerId)) throw new Error("provider_not_supported_for_feature");
  if (!Number.isFinite(creditCost) || creditCost < 0 || creditCost > 100000) throw new Error("model_credit_cost_invalid");
  if (!Number.isFinite(priceUsd) || priceUsd < 0 || priceUsd > 100000) throw new Error("model_price_invalid");
  if (![inputPriceUsdPerMillion, outputPriceUsdPerMillion].every(value => Number.isFinite(value) && value >= 0 && value <= 1000000)) throw new Error("model_token_price_invalid");
  if (cachedInputPriceUsdPerMillion != null && (!Number.isFinite(cachedInputPriceUsdPerMillion) || cachedInputPriceUsdPerMillion < 0 || cachedInputPriceUsdPerMillion > 1000000)) throw new Error("model_cached_token_price_invalid");
  if (!Number.isFinite(creditReferenceValueUsd) || creditReferenceValueUsd <= 0 || creditReferenceValueUsd > 1000) throw new Error("credit_reference_value_invalid");
  if (!Number.isFinite(customerMultiplier) || customerMultiplier < 1 || customerMultiplier > 1000) throw new Error("customer_multiplier_invalid");
  if (!Number.isFinite(estimatedFeePercent) || estimatedFeePercent < 0 || estimatedFeePercent > 100) throw new Error("estimated_fee_percent_invalid");
  if (billingMode === "token" && !["text-chat", "device-commands"].includes(feature)) throw new Error("token_billing_only_supported_for_text_model_features");
  if (billingMode === "token" && raw?.active === true && inputPriceUsdPerMillion + outputPriceUsdPerMillion <= 0) throw new Error("token_pricing_required_for_active_token_billing");
  if (!["request", "image", "second", "minute"].includes(providerCostUnit)) throw new Error("provider_cost_unit_invalid");
  if (providerCostTrackingEnabled && !["live-camera", "live-voice"].includes(feature)) throw new Error("provider_cost_tracking_only_supported_for_live_media");
  if (providerCostTrackingEnabled && raw?.active === true && priceUsd <= 0) throw new Error("live_provider_cost_required");
  if (providerCostTrackingEnabled && feature === "live-camera" && providerCostUnit !== "second") throw new Error("camera_provider_cost_unit_must_be_second");
  if (providerCostTrackingEnabled && feature === "live-voice" && providerCostUnit !== "minute") throw new Error("voice_provider_cost_unit_must_be_minute");
  if (billingMode === "provider_cost" && !["image-generation", "video-generation"].includes(feature)) throw new Error("provider_cost_billing_only_supported_for_media_features");
  if (billingMode === "provider_cost" && raw?.active === true && priceUsd <= 0) throw new Error("provider_operation_cost_required");
  if (billingMode === "provider_cost" && feature === "image-generation" && providerCostUnit !== "image") throw new Error("image_provider_cost_unit_must_be_image");
  if (billingMode === "provider_cost" && feature === "video-generation" && providerCostUnit !== "second") throw new Error("video_provider_cost_unit_must_be_second");
  if (creditsPerMinute != null && (!Number.isFinite(creditsPerMinute) || creditsPerMinute < 0 || creditsPerMinute > 100000)) throw new Error("model_minute_credit_cost_invalid");
  if (raw?.isDefault === true && raw?.active !== true) throw new Error("default_model_must_be_enabled");
  if (raw?.isDefault === true && raw?.published !== true) throw new Error("default_model_must_be_published");
  return {
    id, name, providerId, modelId, feature,
    description: String(raw?.description || "").trim().slice(0, 300),
    active: raw?.active === true,
    isDefault: raw?.isDefault === true,
    published: raw?.published === true,
    creditCost, priceUsd, creditsPerMinute,
    billingMode, providerCostUnit, providerCostTrackingEnabled, inputPriceUsdPerMillion, outputPriceUsdPerMillion,
    cachedInputPriceUsdPerMillion, creditReferenceValueUsd, customerMultiplier, estimatedFeePercent,
    validation: raw?.validation && raw.validation.ok === true ? { ok: true, providerModelName: String(raw.validation.providerModelName || modelId).slice(0, 160), validatedAt: String(raw.validation.validatedAt || new Date().toISOString()) } : null,
    updatedBy: actorUid, updatedAt: new Date()
  };
}

export async function ensureCurrentModelRegistry(defaultModels = {}) {
  const definitions = [
    { feature: "text-chat", capability: "chat", providerId: "openai", modelId: defaultModels.openai || "gpt-6-luna", id: "current-text-chat", name: "GPT-6 Luna" },
    { feature: "image-generation", capability: "image_generation", providerId: "openai", modelId: "gpt-image-1-mini", id: "current-image-generation", name: "GPT Image 1 Mini" },
    { feature: "live-camera", capability: "live_camera", providerId: "openai", modelId: "gpt-realtime-2.1-mini", id: "current-live-camera", name: "GPT Realtime 2.1 Mini — Live Camera" },
    { feature: "live-voice", capability: "live_voice", providerId: "openai", modelId: "gpt-realtime-2.1-mini", id: "current-live-voice", name: "GPT Realtime 2.1 Mini — Live Voice" },
    { feature: "device-commands", capability: "device_commands", providerId: "gemini", modelId: defaultModels.gemini || "gemini-2.5-flash", id: "current-device-commands", name: "Gemini 2.5 Flash — Device Intelligence" }
  ];
  const existingRows = await firestoreQuery("aiModelRegistry", { limit: 200 });
  const now = new Date();
  for (const definition of definitions) {
    const featureRows = existingRows.filter(row => row.feature === definition.feature);
    if (featureRows.some(row => row.isDefault === true && row.active === true && row.published === true && !(row.providerId === "openai" && !isAllowedOpenAiModel(row.modelId, row.feature)))) continue;

    const capability = await firestoreGet("adminAdvancedOptions/capability_" + definition.capability) || {};
    const voiceConfig = definition.feature === "live-voice"
      ? (await firestoreGet("adminAdvancedOptions/live_voice_pricing") || {})
      : null;
    let modelId = String(
      (definition.feature === "live-voice" ? voiceConfig?.modelId : null) ||
      capability.modelId ||
      definition.modelId
    );
    let providerId = String(capability.providerId || definition.providerId).toLowerCase();
    if (definition.feature === "text-chat") {
      // Main chat must use the explicitly allowed GPT-6 Luna model. Ignore stale
      // provider/model overrides here so a blocked or more expensive model can
      // never be selected by legacy adminAdvancedOptions.
      providerId = "openai";
      modelId = "gpt-6-luna";
    }
    if (["live-camera", "live-voice", "image-generation", "video-generation"].includes(definition.feature)) providerId = "openai";
    if (["live-camera", "live-voice"].includes(definition.feature) && !/^(?:gpt-realtime|gpt-4o-realtime)/i.test(modelId)) modelId = definition.modelId;
    if (definition.feature === "image-generation" && !modelId.startsWith("gpt-image")) modelId = definition.modelId;
    if (definition.feature === "video-generation" && !modelId.startsWith("sora")) modelId = definition.modelId;
    if (definition.feature === "device-commands") {
      providerId = "gemini";
      if (!modelId.startsWith("gemini")) modelId = defaultModels.gemini || "gemini-2.5-flash";
    }
    if (providerId === "openai" && !isAllowedOpenAiModel(modelId, definition.feature)) {
      modelId = SAFE_OPENAI_MODEL_BY_FEATURE[definition.feature] || "gpt-6-luna";
    }

    const matching = featureRows.find(row => row.modelId === modelId && row.providerId === providerId);
    const id = String(matching?.id || String(matching?.__name || "").split("/").pop() || definition.id);
    const active = capability.enabled !== false && capability.killSwitch !== true;
    const model = {
      ...(matching || {}),
      id,
      name: String(matching?.name || (modelId === definition.modelId ? definition.name : modelId)),
      providerId,
      modelId,
      feature: definition.feature,
      description: String(matching?.description || "Existing BQ Sqwad AI server model"),
      active,
      isDefault: active,
      published: matching?.published !== false,
      creditCost: Math.max(0, Number(matching?.creditCost ?? capability.creditCost ?? 0)),
      priceUsd: Math.max(0, Number(matching?.priceUsd ?? capability.priceUsd ?? 0)),
      creditsPerMinute: matching?.creditsPerMinute == null
        ? (capability.creditsPerMinute == null ? null : Math.max(0, Number(capability.creditsPerMinute)))
        : Math.max(0, Number(matching.creditsPerMinute)),
      billingMode: String(matching?.billingMode || capability.billingMode || "fixed_credit"),
      providerCostUnit: String(matching?.providerCostUnit || capability.providerCostUnit || (definition.feature === "image-generation" ? "image" : ["video-generation", "live-camera"].includes(definition.feature) ? "second" : definition.feature === "live-voice" ? "minute" : "request")),
      providerCostTrackingEnabled: matching?.providerCostTrackingEnabled === true || capability.providerCostTrackingEnabled === true,
      inputPriceUsdPerMillion: Math.max(0, Number(matching?.inputPriceUsdPerMillion ?? capability.inputPriceUsdPerMillion ?? 0)),
      outputPriceUsdPerMillion: Math.max(0, Number(matching?.outputPriceUsdPerMillion ?? capability.outputPriceUsdPerMillion ?? 0)),
      cachedInputPriceUsdPerMillion: matching?.cachedInputPriceUsdPerMillion == null && capability.cachedInputPriceUsdPerMillion == null
        ? null : Math.max(0, Number(matching?.cachedInputPriceUsdPerMillion ?? capability.cachedInputPriceUsdPerMillion ?? 0)),
      creditReferenceValueUsd: Math.max(0.000001, Number(matching?.creditReferenceValueUsd ?? capability.creditReferenceValueUsd ?? 0.1199)),
      customerMultiplier: Math.max(1, Number(matching?.customerMultiplier ?? capability.customerMultiplier ?? 1.8)),
      estimatedFeePercent: Math.min(100, Math.max(0, Number(matching?.estimatedFeePercent ?? capability.estimatedFeePercent ?? 15))),
      updatedAt: now,
      updatedBy: "system-bootstrap"
    };
    if (model.isDefault && model.published) {
      await firestoreSet("adminAdvancedOptions/capability_" + definition.capability, {
        ...capability,
        id: "capability_" + definition.capability,
        enabled: capability.enabled !== false && capability.killSwitch !== true,
        providerId,
        modelId,
        creditCost: model.creditCost,
        priceUsd: model.priceUsd,
        creditsPerMinute: model.creditsPerMinute,
        billingMode: model.billingMode,
        providerCostUnit: model.providerCostUnit,
        providerCostTrackingEnabled: model.providerCostTrackingEnabled,
        inputPriceUsdPerMillion: model.inputPriceUsdPerMillion,
        outputPriceUsdPerMillion: model.outputPriceUsdPerMillion,
        cachedInputPriceUsdPerMillion: model.cachedInputPriceUsdPerMillion,
        creditReferenceValueUsd: model.creditReferenceValueUsd,
        customerMultiplier: model.customerMultiplier,
        estimatedFeePercent: model.estimatedFeePercent,
        selectedRegistryModelId: id,
        selectedRegistryModelName: model.name,
        updatedAt: now
      }, true);
      if (definition.feature === "live-voice") {
        await firestoreSet("adminAdvancedOptions/live_voice_pricing", {
          ...voiceConfig,
          modelId,
          updatedAt: now
        }, true);
      }
      for (const other of featureRows) {
        const otherId = String(other.id || String(other.__name || "").split("/").pop());
        if (otherId && otherId !== id && other.isDefault === true) {
          await firestoreSet("aiModelRegistry/" + otherId, { ...other, isDefault: false, updatedAt: now }, true);
        }
      }
    } else {
      model.isDefault = false;
    }
    await firestoreSet("aiModelRegistry/" + id, model, true);
  }
  return { seeded: definitions.length, ok: true };
}

export async function listAiModels({ admin = false } = {}) {
  const rows = await firestoreQuery("aiModelRegistry", { limit: 200 });
  const models = rows.map(x => ({
    id: String(x.id || String(x.__name || "").split("/").pop()),
    name: String(x.name || ""),
    providerId: String(x.providerId || ""),
    modelId: String(x.modelId || ""),
    feature: String(x.feature || "text-chat"),
    description: String(x.description || ""),
    active: x.active === true,
    isDefault: x.isDefault === true,
    published: x.published === true,
    creditCost: (function(){ const n = Number(x.creditCost); return Number.isFinite(n) && n >= 0 ? Math.round(n*1e5)/1e5 : 1; })(),
    priceUsd: Math.max(0, Number(x.priceUsd || 0)),
    creditsPerMinute: x.creditsPerMinute == null ? null : Math.max(0, Number(x.creditsPerMinute || 0)),
    validation: x.validation?.ok === true ? { ok: true, providerModelName: String(x.validation.providerModelName || x.modelId || ""), validatedAt: x.validation.validatedAt || null } : null,
    providerStatus: String(x.providerStatus || "not_checked"),
    providerStatusMessage: String(x.providerStatusMessage || ""),
    providerCheckedAt: x.providerCheckedAt || null,
    updatedAt: x.updatedAt || null
  }));
  return { models: models.filter(x => (x.providerId !== "openai" || isAllowedOpenAiModel(x.modelId, x.feature)) && (admin || (x.active && x.published))).sort((a,b) => a.name.localeCompare(b.name)), updatedAt: new Date().toISOString() };
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
  if (providerId === "openai" && !isAllowedOpenAiModel(modelId, feature)) {
    throw Object.assign(new Error("openai_model_not_allowed_by_project_policy"), { httpStatus: 403, safeError: "model_blocked_by_project_policy" });
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
  const rows = (await firestoreQuery("aiModelRegistry", { limit: 200 }))
    .filter(x => x.providerId !== "openai" || isAllowedOpenAiModel(x.modelId, x.feature));
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
    estimatedFeePercent: Math.min(100, Math.max(0, Number(model.estimatedFeePercent || 15)))
  };
}
