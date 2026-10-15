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
  const creditReferenceValueUsd = Number(raw?.creditReferenceValueUsd ?? 0.07999);
  const customerMultiplier = Number(raw?.customerMultiplier ?? 8);
  const estimatedFeePercent = Number(raw?.estimatedFeePercent ?? 15);
  const maxOutputTokens = Number(raw?.maxOutputTokens ?? 2048);
  if (!/^[a-z0-9][a-z0-9-]{1,79}$/.test(id)) throw new Error("model_id_invalid");
  if (!name) throw new Error("model_name_required");
  if (!PROVIDERS.has(providerId)) throw new Error("model_provider_unsupported");
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
  if (!Number.isFinite(creditCost) || creditCost < 1 || creditCost > 100000 || !Number.isInteger(creditCost)) throw new Error("model_credit_cost_invalid");
  if (!Number.isFinite(priceUsd) || priceUsd < 0 || priceUsd > 100000) throw new Error("model_price_invalid");
  if (![inputPriceUsdPerMillion, outputPriceUsdPerMillion].every(value => Number.isFinite(value) && value >= 0 && value <= 1000000)) throw new Error("model_token_price_invalid");
  if (cachedInputPriceUsdPerMillion != null && (!Number.isFinite(cachedInputPriceUsdPerMillion) || cachedInputPriceUsdPerMillion < 0 || cachedInputPriceUsdPerMillion > 1000000)) throw new Error("model_cached_token_price_invalid");
  if (!Number.isFinite(creditReferenceValueUsd) || creditReferenceValueUsd <= 0 || creditReferenceValueUsd > 1000) throw new Error("credit_reference_value_invalid");
  if (!Number.isFinite(customerMultiplier) || customerMultiplier < 1 || customerMultiplier > 1000) throw new Error("customer_multiplier_invalid");
  if (!Number.isFinite(estimatedFeePercent) || estimatedFeePercent < 0 || estimatedFeePercent > 100) throw new Error("estimated_fee_percent_invalid");
  if (!Number.isInteger(maxOutputTokens) || maxOutputTokens < 128 || maxOutputTokens > 8192) throw new Error("max_output_tokens_invalid");
  if (billingMode === "token" && !["text-chat", "device-commands"].includes(feature)) throw new Error("token_billing_only_supported_for_text_model_features");
  if (billingMode === "token" && raw?.active === true && inputPriceUsdPerMillion + outputPriceUsdPerMillion <= 0) throw new Error("token_pricing_required_for_active_token_billing");
  if (!["request", "image", "second", "minute"].includes(providerCostUnit)) throw new Error("provider_cost_unit_invalid");
  if (providerCostTrackingEnabled && !["live-camera", "live-voice"].includes(feature)) throw new Error("provider_cost_tracking_only_supported_for_live_media");
  if (providerCostTrackingEnabled && priceUsd <= 0) throw new Error("live_provider_cost_required");
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
    cachedInputPriceUsdPerMillion, creditReferenceValueUsd, customerMultiplier, estimatedFeePercent, maxOutputTokens,
    validation: raw?.validation && raw.validation.ok === true ? { ok: true, providerModelName: String(raw.validation.providerModelName || modelId).slice(0, 160), validatedAt: String(raw.validation.validatedAt || new Date().toISOString()) } : null,
    updatedBy: actorUid, updatedAt: new Date()
  };
}

export async function ensureCurrentModelRegistry(defaultModels = {}) {
  const definitions = [
    { feature: "text-chat", capability: "chat", providerId: "openai", modelId: defaultModels.openai || "gpt-5.6-luna", id: "current-text-chat", name: "GPT-5.6 Luna" },
    { feature: "image-generation", capability: "image_generation", providerId: "openai", modelId: "gpt-image-2", id: "current-image-generation", name: "GPT Image 2" },
    { feature: "live-camera", capability: "live_camera", providerId: "openai", modelId: "gpt-realtime-2.1", id: "current-live-camera", name: "GPT Realtime 2.1 — Live Camera" },
    { feature: "live-voice", capability: "live_voice", providerId: "openai", modelId: "gpt-realtime-2.1", id: "current-live-voice", name: "GPT Realtime 2.1 — Live Voice" },
    { feature: "video-generation", capability: "video_generation", providerId: "openai", modelId: "sora-2", id: "current-video-generation", name: "Sora 2" },
    { feature: "device-commands", capability: "device_commands", providerId: "gemini", modelId: defaultModels.gemini || "gemini-2.5-flash", id: "current-device-commands", name: "Gemini 2.5 Flash — Device Intelligence" }
  ];
  const existingRows = await firestoreQuery("aiModelRegistry", { limit: 200 });
  const now = new Date();
  for (const definition of definitions) {
    const featureRows = existingRows.filter(row => row.feature === definition.feature);
    if (featureRows.some(row => row.isDefault === true && row.active === true && row.published === true)) continue;

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
      if (!["openai", "gemini"].includes(providerId)) providerId = "openai";
      if (!modelId) modelId = providerId === "gemini" ? (defaultModels.gemini || "gemini-2.5-flash") : (defaultModels.openai || "gpt-5.6-luna");
      if (providerId === "gemini" && !modelId.startsWith("gemini")) modelId = defaultModels.gemini || "gemini-2.5-flash";
    }
    if (["live-camera", "live-voice", "image-generation", "video-generation"].includes(definition.feature)) providerId = "openai";
    if (["live-camera", "live-voice"].includes(definition.feature) && !/^(?:gpt-realtime|gpt-4o-realtime)/i.test(modelId)) modelId = definition.modelId;
    if (definition.feature === "image-generation" && !modelId.startsWith("gpt-image")) modelId = definition.modelId;
    if (definition.feature === "video-generation" && !modelId.startsWith("sora")) modelId = definition.modelId;
    if (definition.feature === "device-commands") {
      providerId = "gemini";
      if (!modelId.startsWith("gemini")) modelId = defaultModels.gemini || "gemini-2.5-flash";
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
      creditCost: Math.max(1, Number(matching?.creditCost ?? capability.creditCost ?? 1)),
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
      creditReferenceValueUsd: Math.max(0.000001, Number(matching?.creditReferenceValueUsd ?? capability.creditReferenceValueUsd ?? 0.07999)),
      customerMultiplier: Math.max(1, Number(matching?.customerMultiplier ?? capability.customerMultiplier ?? 8)),
      estimatedFeePercent: Math.min(100, Math.max(0, Number(matching?.estimatedFeePercent ?? capability.estimatedFeePercent ?? 15))),
      maxOutputTokens: Math.min(8192, Math.max(128, Number(matching?.maxOutputTokens ?? capability.maxOutputTokens ?? 2048))),
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
        maxOutputTokens: model.maxOutputTokens,
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
    creditCost: Math.max(1, Number(x.creditCost || 1)),
    priceUsd: Math.max(0, Number(x.priceUsd || 0)),
    creditsPerMinute: x.creditsPerMinute == null ? null : Math.max(0, Number(x.creditsPerMinute || 0)),
    validation: x.validation?.ok === true ? { ok: true, providerModelName: String(x.validation.providerModelName || x.modelId || ""), validatedAt: x.validation.validatedAt || null } : null,
    providerStatus: String(x.providerStatus || "not_checked"),
    providerStatusMessage: String(x.providerStatusMessage || ""),
    providerCheckedAt: x.providerCheckedAt || null,
    updatedAt: x.updatedAt || null
  }));
  return { models: models.filter(x => admin || (x.active && x.published)).sort((a,b) => a.name.localeCompare(b.name)), updatedAt: new Date().toISOString() };
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
    const reason = String(body?.error?.message || "provider_model_validation_failed").slice(0, 220);
    throw Object.assign(new Error(reason), { httpStatus: response.status === 401 || response.status === 403 ? 502 : 400 });
  }
  return {
    ok: true,
    providerId,
    modelId,
    feature,
    providerModelName: String(body.displayName || body.name || body.id || modelId),
    validatedAt: new Date().toISOString()
  };
}


export async function checkAiModelStatus(env, id, actorUid) {
  const registryId = String(id || "").trim().toLowerCase();
  if (!/^[a-z0-9][a-z0-9-]{1,79}$/.test(registryId)) {
    throw Object.assign(new Error("model_id_invalid"), { httpStatus: 400 });
  }
  const existing = await firestoreGet("aiModelRegistry/" + registryId);
  if (!existing) throw Object.assign(new Error("model_not_found"), { httpStatus: 404 });
  const checkedAt = new Date();
  let status = "available";
  let message = "";
  let validation = null;
  try {
    validation = await validateAiModel(env, {
      providerId: existing.providerId,
      modelId: existing.modelId,
      feature: existing.feature
    });
    message = "Provider confirms this model ID is available to this API key.";
  } catch (error) {
    const reason = String(error?.message || "provider_status_check_failed").slice(0, 220);
    if (reason.includes("provider_not_configured")) status = "provider_not_configured";
    else if (reason.includes("timeout") || reason.includes("network_error")) status = "not_verified";
    else status = "unavailable";
    message = reason;
  }
  const updated = {
    ...existing,
    providerStatus: status,
    providerStatusMessage: message,
    providerCheckedAt: checkedAt,
    ...(validation ? { validation } : {}),
    providerStatusCheckedBy: String(actorUid || "")
  };
  await firestoreSet("aiModelRegistry/" + registryId, updated, true);
  return {
    id: registryId,
    providerId: String(existing.providerId || ""),
    modelId: String(existing.modelId || ""),
    feature: String(existing.feature || ""),
    providerStatus: status,
    providerStatusMessage: message,
    providerCheckedAt: checkedAt.toISOString(),
    validation
  };
}

export async function saveAiModel(raw, actorUid) {
  const model = normalizeModel(raw, actorUid);
  const existing = await firestoreGet("aiModelRegistry/" + model.id);
  if (existing?.isDefault === true && model.isDefault !== true) {
    const capabilityName = FEATURE_CAPABILITIES[model.feature];
    const activeCapability = await firestoreGet("adminAdvancedOptions/capability_" + capabilityName);
    if (String(activeCapability?.selectedRegistryModelId || "") === model.id) {
      throw new Error("select_replacement_model_before_changing_active_default");
    }
  }
  await firestoreSet("aiModelRegistry/" + model.id, model, true);
  if (model.isDefault) {
    const capability = FEATURE_CAPABILITIES[model.feature];
    const previous = await firestoreGet("adminAdvancedOptions/capability_" + capability) || {};
    // Every feature uses the server-side capability resolver, so model switches
    // apply to subsequent requests without an Android app update.
    await firestoreSet("adminAdvancedOptions/capability_" + capability, {
      ...previous,
      id: "capability_" + capability,
      enabled: true,
      providerId: model.providerId,
      modelId: model.modelId,
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
      maxOutputTokens: model.maxOutputTokens,
      selectedRegistryModelId: model.id,
      selectedRegistryModelName: model.name,
      updatedBy: actorUid,
      updatedAt: new Date(),
      version: Number(previous.version || 0) + 1
    }, true);
    if (model.feature === "live-voice") {
      const voicePricing = await firestoreGet("adminAdvancedOptions/live_voice_pricing") || {};
      await firestoreSet("adminAdvancedOptions/live_voice_pricing", {
        ...voicePricing,
        enabled: true,
        modelId: model.modelId,
        updatedBy: actorUid,
        updatedAt: new Date(),
        version: Number(voicePricing.version || 0) + 1
      }, true);
    }
    const others = await firestoreQuery("aiModelRegistry", { limit: 200 });
    for (const other of others) {
      if (String(other.id || "") !== model.id && other.feature === model.feature && other.isDefault === true) {
        await firestoreSet("aiModelRegistry/" + String(other.id || String(other.__name || "").split("/").pop()), {
          ...other, isDefault: false, updatedBy: actorUid, updatedAt: new Date()
        }, true);
      }
    }
  }
  await firestoreCreate("adminConfigAudit/" + crypto.randomUUID(), {
    type: "ai_model_registry_update",
    modelId: model.id,
    feature: model.feature,
    active: model.active,
    published: model.published,
    actorUid,
    previousExists: Boolean(existing),
    createdAt: new Date()
  });
  return { ok: true, model };
}

export async function resolvePublishedModel(modelId, expectedFeature) {
  const id = String(modelId || "").trim().toLowerCase();
  if (!/^[a-z0-9][a-z0-9-]{1,79}$/.test(id)) throw Object.assign(new Error("model_id_invalid"), { httpStatus: 400 });
  const model = await firestoreGet("aiModelRegistry/" + id);
  if (!model || model.active !== true || model.published !== true) {
    throw Object.assign(new Error("model_not_available"), { httpStatus: 404 });
  }
  if (String(model.feature || "") !== expectedFeature) {
    throw Object.assign(new Error("model_feature_mismatch"), { httpStatus: 400 });
  }
  return {
    id,
    name: String(model.name || id),
    providerId: String(model.providerId || ""),
    modelId: String(model.modelId || ""),
    feature: String(model.feature || ""),
    creditCost: Math.max(1, Number(model.creditCost || 1)),
    priceUsd: Math.max(0, Number(model.priceUsd || 0)),
    creditsPerMinute: model.creditsPerMinute == null ? null : Math.max(0, Number(model.creditsPerMinute || 0)),
    billingMode: String(model.billingMode || "fixed_credit"),
    providerCostUnit: String(model.providerCostUnit || (model.feature === "image-generation" ? "image" : ["video-generation", "live-camera"].includes(model.feature) ? "second" : model.feature === "live-voice" ? "minute" : "request")),
    providerCostTrackingEnabled: model.providerCostTrackingEnabled === true,
    inputPriceUsdPerMillion: Math.max(0, Number(model.inputPriceUsdPerMillion || 0)),
    outputPriceUsdPerMillion: Math.max(0, Number(model.outputPriceUsdPerMillion || 0)),
    cachedInputPriceUsdPerMillion: model.cachedInputPriceUsdPerMillion == null ? null : Math.max(0, Number(model.cachedInputPriceUsdPerMillion || 0)),
    creditReferenceValueUsd: Math.max(0.000001, Number(model.creditReferenceValueUsd || 0.07999)),
    customerMultiplier: Math.max(1, Number(model.customerMultiplier || 8)),
    estimatedFeePercent: Math.min(100, Math.max(0, Number(model.estimatedFeePercent ?? 15))),
    maxOutputTokens: Math.min(8192, Math.max(128, Number(model.maxOutputTokens || 2048)))
  };
}
