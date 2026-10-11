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
    "device-commands": new Set(["gemini"]),
    "video-generation": new Set(["openai"])
  };
  if (!allowedProviders[feature].has(providerId)) throw new Error("provider_not_supported_for_feature");
  if (!Number.isFinite(creditCost) || creditCost < 0 || creditCost > 100000 || !Number.isInteger(creditCost)) throw new Error("model_credit_cost_invalid");
  if (!Number.isFinite(priceUsd) || priceUsd < 0 || priceUsd > 100000) throw new Error("model_price_invalid");
  if (raw?.isDefault === true && raw?.active !== true) throw new Error("default_model_must_be_enabled");
  if (raw?.isDefault === true && raw?.published !== true) throw new Error("default_model_must_be_published");
  return {
    id, name, providerId, modelId, feature,
    description: String(raw?.description || "").trim().slice(0, 300),
    active: raw?.active === true,
    isDefault: raw?.isDefault === true,
    published: raw?.published === true,
    creditCost, priceUsd,
    updatedBy: actorUid, updatedAt: new Date()
  };
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
    creditCost: Math.max(0, Number(x.creditCost || 0)),
    priceUsd: Math.max(0, Number(x.priceUsd || 0)),
    updatedAt: x.updatedAt || null
  }));
  return { models: models.filter(x => admin || (x.active && x.published)).sort((a,b) => a.name.localeCompare(b.name)), updatedAt: new Date().toISOString() };
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
    creditCost: Math.max(0, Number(model.creditCost || 0)),
    priceUsd: Math.max(0, Number(model.priceUsd || 0))
  };
}
