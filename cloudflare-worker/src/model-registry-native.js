import { firestoreGet, firestoreSet, firestoreQuery, firestoreCreate } from "./firebase.js";

const FEATURE_CAPABILITIES = {
  "text-chat": "chat",
  "live-camera": "live_camera",
  "image-generation": "image_generation",
  "device-commands": "device_commands",
  "video-generation": "video_generation",
  "live-voice": "live_voice", "transcription": "dictation", "text-to-speech": "text_to_speech", "embeddings": "embeddings", "moderation": "moderation"
};

const PROVIDERS = new Set(["openai", "gemini"]);

const SAFE_OPENAI_MODEL_BY_FEATURE = {"text-chat":"gpt-6-luna","device-commands":"gpt-6-luna","image-generation":"gpt-image-2.5-sunburst","live-camera":"gpt-realtime-2.1-mini","live-voice":"gpt-realtime-2.1-mini","transcription":"whisper-1","text-to-speech":"gpt-4o-mini-tts","embeddings":"text-embedding-3-small","moderation":"omni-moderation-latest"};
const ALLOWED_OPENAI_MODELS_BY_FEATURE = {
  "text-chat": new Set(["gpt-6-luna", "gpt-5.6-luna"]),
  "device-commands": new Set(["gpt-6-luna"]),
  "image-generation": new Set(["gpt-image-2.5-sunburst", "gpt-image-1-mini"]),
  "live-camera": new Set(["gpt-realtime-2.1-mini"]),
  "live-voice": new Set(["gpt-realtime-2.1-mini"]),
  "transcription": new Set(["whisper-1", "gpt-4o-mini-transcribe"]),
  "text-to-speech": new Set(["gpt-4o-mini-tts"]),
  "embeddings": new Set(["text-embedding-3-small"]),
  "moderation": new Set(["omni-moderation-latest"]),
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
    "video-generation": new Set(["openai"]),
    "transcription": new Set(["openai"]),
    "text-to-speech": new Set(["openai"]),
    "embeddings": new Set(["openai"]),
    "moderation": new Set(["openai"])
  };
  if (!allowedProviders[feature].has(providerId)) throw new Error("provider_not_supported_for_feature");

  if (raw?.isDefault === true && raw?.active !== true) throw new Error("default_model_must_be_enabled");
  if (raw?.isDefault === true && raw?.published !== true) throw new Error("default_model_must_be_published");
  return {
    id, name, providerId, modelId, feature,
    description: String(raw?.description || "").trim().slice(0, 300),
    active: raw?.active === true,
    isDefault: raw?.isDefault === true,
    published: raw?.published === true,
    validation: raw?.validation && raw.validation.ok === true ? { ok: true, providerModelName: String(raw.validation.providerModelName || modelId).slice(0, 160), validatedAt: String(raw.validation.validatedAt || new Date().toISOString()) } : null,
    updatedBy: actorUid, updatedAt: new Date()
  };
}

export async function ensureCurrentModelRegistry(defaultModels = {}) {
  const definitions = [
    { feature: "text-chat", capability: "chat", providerId: "openai", modelId: defaultModels.openai || "gpt-6-luna", id: "current-text-chat", name: "BQ-7-Star" },
    { feature: "image-generation", capability: "image_generation", providerId: "openai", modelId: "gpt-image-2.5-sunburst", id: "current-image-generation", name: "GPT Image 2.5 Sunburst" },
    { feature: "live-camera", capability: "live_camera", providerId: "openai", modelId: "gpt-realtime-2.1-mini", id: "current-live-camera", name: "GPT Realtime 2.1 Mini — Live Camera" },
    { feature: "live-voice", capability: "live_voice", providerId: "openai", modelId: "gpt-realtime-2.1-mini", id: "current-live-voice", name: "GPT Realtime 2.1 Mini — Live Voice" },
    { feature: "transcription", capability: "dictation", providerId: "openai", modelId: "whisper-1", id: "current-transcription", name: "Whisper — Dictation" },
    { feature: "text-to-speech", capability: "text_to_speech", providerId: "openai", modelId: "gpt-4o-mini-tts", id: "current-tts", name: "GPT-4o Mini TTS" },
    { feature: "embeddings", capability: "embeddings", providerId: "openai", modelId: "text-embedding-3-small", id: "current-embeddings", name: "Text Embedding 3 Small" },
    { feature: "moderation", capability: "moderation", providerId: "openai", modelId: "omni-moderation-latest", id: "current-moderation", name: "Omni Moderation" },
    { feature: "device-commands", capability: "device_commands", providerId: "openai", modelId: "gpt-6-luna", id: "current-device-commands", name: "GPT-6 Luna — Device Intelligence" }
  ];
  const existingRows = await firestoreQuery("aiModelRegistry", { limit: 200 });
  const now = new Date();
  for (const definition of definitions) {
    const featureRows = existingRows.filter(row => row.feature === definition.feature);
    if (featureRows.some(row => row.isDefault === true && row.active === true && row.published === true && !(row.providerId === "openai" && !isAllowedOpenAiModel(row.modelId, row.feature)) && !(definition.feature === "device-commands" && (row.providerId !== "openai" || row.modelId !== "gpt-6-luna")))) continue;

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
      // GPT-6 Luna remains the default chat model. GPT-5.6 Luna (BQ-Codron)
      // is also an explicitly allowed selectable model; it is resolved from
      // the selected BQ catalogue entry and must not replace the default.
      providerId = "openai";
      modelId = "gpt-6-luna";
    }
    if (["live-camera", "live-voice", "image-generation", "video-generation"].includes(definition.feature)) providerId = "openai";
    if (["live-camera", "live-voice"].includes(definition.feature) && !/^gpt-realtime-2\.1-mini$/i.test(modelId)) modelId = definition.modelId;
    if (definition.feature === "image-generation" && !modelId.startsWith("gpt-image")) modelId = definition.modelId;
    if (definition.feature === "video-generation" && !modelId.startsWith("sora")) modelId = definition.modelId;
    if (definition.feature === "device-commands") {
      providerId = "openai";
      modelId = "gpt-6-luna";
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
        selectedRegistryModelId: id,
        selectedRegistryModelName: model.name,
        updatedAt: now
      }, false);
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
    await firestoreSet("aiModelRegistry/" + id, model, false);
  }
  return { seeded: definitions.length, ok: true };
}

export async function listAiModels({ admin = false } = {}) {
  const rows = await firestoreQuery("aiModelRegistry", { limit: 200 });
  const visibleRows = rows.filter(x =>
    (String(x.providerId || "") !== "openai" || isAllowedOpenAiModel(x.modelId, x.feature)) &&
    (admin || (x.active === true && x.published === true))
  );
  const models = visibleRows.map(x => {
    const id = String(x.id || String(x.__name || "").split("/").pop());
    const modelId = String(x.modelId || "");
    const feature = String(x.feature || "text-chat");
    const bqDisplayName = modelId === "gpt-6-luna" ? "BQ-7-Star"
      : modelId === "gpt-5.6-luna" ? "BQ-Codron"
      : String(x.name || "");
    if (!admin) {
      // Public clients select by registry ID/model key. Provider IDs, provider
      // names, provider status details, and all pricing remain server-side.
      return {
        id,
        name: bqDisplayName,
        feature,
        description: String(x.description || ""),
        active: x.active === true,
        isDefault: x.isDefault === true,
        published: x.published === true
      };
    }
    return {
      id,
      name: String(x.name || bqDisplayName),
      providerId: String(x.providerId || ""),
      modelId,
      feature,
      description: String(x.description || ""),
      active: x.active === true,
      isDefault: x.isDefault === true,
      published: x.published === true,
      validation: x.validation?.ok === true ? { ok: true, providerModelName: String(x.validation.providerModelName || modelId), validatedAt: x.validation.validatedAt || null } : null,
      providerStatus: String(x.providerStatus || "not_checked"),
      providerStatusMessage: String(x.providerStatusMessage || ""),
      providerCheckedAt: x.providerCheckedAt || null,
      updatedAt: x.updatedAt || null
    };
  });
  return { models: models.sort((a,b) => a.name.localeCompare(b.name)), updatedAt: new Date().toISOString() };
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
    "video-generation": new Set(["openai"]),
    "transcription": new Set(["openai"]),
    "text-to-speech": new Set(["openai"]),
    "embeddings": new Set(["openai"]),
    "moderation": new Set(["openai"])
  };
  if (!FEATURE_CAPABILITIES[feature] || !allowedProviders[feature]?.has(providerId)) {
    throw Object.assign(new Error("provider_not_supported_for_feature"), { httpStatus: 400 });
  }
  if (providerId === "openai" && !isAllowedOpenAiModel(modelId, feature)) {
    throw Object.assign(new Error("openai_model_not_allowed_by_project_policy"), { httpStatus: 403, safeError: "model_blocked_by_project_policy" });
  }
  const prefixRules = {
    "live-camera": /^gpt-realtime-2\.1-mini$/i,
    "live-voice": /^gpt-realtime-2\.1-mini$/i,
    "transcription": /^(?:whisper|gpt-4o(?:-mini)?-transcribe)/i,
    "text-to-speech": /^gpt-4o-mini-tts/i,
    "embeddings": /^text-embedding/i,
    "moderation": /^omni-moderation/i,
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
  await firestoreSet("aiModelRegistry/" + model.id, model, false);
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
  };
}
