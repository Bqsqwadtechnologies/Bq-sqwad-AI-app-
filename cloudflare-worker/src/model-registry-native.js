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
  if (!/^[a-zA-Z0-9._:-]{1,120}$/.test(modelId)) throw new Error("provider_model_id_invalid");
  if (!FEATURE_CAPABILITIES[feature]) throw new Error("model_feature_unsupported");
  if (!Number.isFinite(creditCost) || creditCost < 0 || creditCost > 100000 || !Number.isInteger(creditCost)) throw new Error("model_credit_cost_invalid");
  if (!Number.isFinite(priceUsd) || priceUsd < 0 || priceUsd > 100000) throw new Error("model_price_invalid");
  return {
    id, name, providerId, modelId, feature,
    description: String(raw?.description || "").trim().slice(0, 300),
    active: raw?.active === true,
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
  await firestoreSet("aiModelRegistry/" + model.id, model, true);
  if (model.active) {
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
    const others = await firestoreQuery("aiModelRegistry", { limit: 200 });
    for (const other of others) {
      if (String(other.id || "") !== model.id && other.feature === model.feature && other.active === true) {
        await firestoreSet("aiModelRegistry/" + String(other.id || String(other.__name || "").split("/").pop()), {
          ...other, active: false, updatedBy: actorUid, updatedAt: new Date()
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
