import crypto from "node:crypto";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

const COLLECTION = "organizationProduction";

const DEFAULTS = {
  status: "idle",
  aiDirectorEnabled: true,
  activeSourceId: null,
  activeSceneId: null,
  safeSourceId: null,
  sources: [],
  scenes: [],
  destinations: [],
  rules: {
    autoSwitchEnabled: false,
    minimumConfidence: 0.8,
    fallbackEnabled: true,
    operatorOverride: true,
    holdLastValidOutput: true
  },
  training: {
    programType: "",
    instructions: [],
    cameraRules: [],
    brandingRules: [],
    prohibitedActions: []
  },
  health: {
    source: "unknown",
    output: "idle",
    lastError: null,
    reconnectAttempts: 0
  },
  updatedAt: null
};

function clean(value, max = 500) {
  return String(value ?? "").trim().slice(0, max);
}

function refFor(uid) {
  return getFirestore().collection(COLLECTION).doc(uid);
}

export async function getProduction(uid) {
  const ref = refFor(uid);
  const snap = await ref.get();
  if (!snap.exists) {
    await ref.set({ uid, ...DEFAULTS, createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() });
    return { uid, ...DEFAULTS };
  }
  return { exists: true, ...snap.data() };
}

export async function updateProductionConfig(uid, patch = {}) {
  const ref = refFor(uid);
  const current = await getProduction(uid);
  const rules = { ...(current.rules || {}) };
  let aiDirectorEnabled = current.aiDirectorEnabled !== false;
  if (patch.aiDirectorEnabled != null) aiDirectorEnabled = patch.aiDirectorEnabled === true;
  if (patch.autoSwitchEnabled != null) rules.autoSwitchEnabled = patch.autoSwitchEnabled === true;
  if (patch.minimumConfidence != null) rules.minimumConfidence = Math.min(1, Math.max(0, Number(patch.minimumConfidence)));
  if (patch.fallbackEnabled != null) rules.fallbackEnabled = patch.fallbackEnabled === true;
  if (patch.operatorOverride != null) rules.operatorOverride = patch.operatorOverride !== false;
  if (patch.holdLastValidOutput != null) rules.holdLastValidOutput = patch.holdLastValidOutput !== false;

  const training = { ...(current.training || {}) };
  for (const key of ["programType"]) if (patch[key] != null) training[key] = clean(patch[key], 200);
  for (const key of ["instructions", "cameraRules", "brandingRules", "prohibitedActions"]) {
    if (Array.isArray(patch[key])) training[key] = patch[key].map(x => clean(x, 2000)).filter(Boolean).slice(-100);
  }

  await ref.set({ aiDirectorEnabled, rules, training, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getProduction(uid);
}

export async function upsertSource(uid, source = {}) {
  const ref = refFor(uid);
  const current = await getProduction(uid);
  const id = clean(source.id, 120) || crypto.randomUUID();
  const next = {
    id,
    name: clean(source.name, 160) || "Camera source",
    type: clean(source.type, 60) || "camera",
    endpoint: clean(source.endpoint, 2000),
    enabled: source.enabled !== false,
    fallbackPriority: Math.max(0, Number(source.fallbackPriority || 0)),
    role: clean(source.role, 100),
    health: "unknown",
    lastError: null,
    updatedAt: new Date().toISOString()
  };
  const sources = Array.isArray(current.sources) ? current.sources.filter(x => x.id !== id) : [];
  sources.push(next);
  await ref.set({ sources: sources.slice(-32), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getProduction(uid);
}

export async function removeSource(uid, sourceId) {
  const ref = refFor(uid);
  const current = await getProduction(uid);
  const id = clean(sourceId, 120);
  const sources = (current.sources || []).filter(x => x.id !== id);
  const patch = { sources, updatedAt: FieldValue.serverTimestamp() };
  if (current.activeSourceId === id) patch.activeSourceId = current.safeSourceId && current.safeSourceId !== id ? current.safeSourceId : null;
  if (current.safeSourceId === id) patch.safeSourceId = null;
  await ref.set(patch, { merge: true });
  return getProduction(uid);
}

export async function upsertScene(uid, scene = {}) {
  const ref = refFor(uid);
  const current = await getProduction(uid);
  const id = clean(scene.id, 120) || crypto.randomUUID();
  const next = {
    id,
    name: clean(scene.name, 160) || "Scene",
    type: clean(scene.type, 80) || "camera",
    sourceIds: Array.isArray(scene.sourceIds) ? scene.sourceIds.map(x => clean(x, 120)).filter(Boolean).slice(0, 16) : [],
    overlay: clean(scene.overlay, 2000),
    captionEnabled: scene.captionEnabled === true,
    enabled: scene.enabled !== false,
    updatedAt: new Date().toISOString()
  };
  const scenes = Array.isArray(current.scenes) ? current.scenes.filter(x => x.id !== id) : [];
  scenes.push(next);
  await ref.set({ scenes: scenes.slice(-64), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getProduction(uid);
}

export async function upsertDestination(uid, destination = {}) {
  const ref = refFor(uid);
  const current = await getProduction(uid);
  const id = clean(destination.id, 120) || crypto.randomUUID();
  const type = clean(destination.type, 80) || "social";
  const allowed = new Set(["youtube", "facebook", "tiktok", "instagram", "rtmp", "rtmps", "srt", "broadcast"]);
  if (!allowed.has(type)) throw new Error("unsupported_destination_type");
  const next = {
    id,
    platform: type,
    name: clean(destination.name, 160) || type,
    enabled: destination.enabled === true,
    authentication: destination.authentication === "connected" ? "connected" : "not_connected",
    outputState: "idle",
    health: "unknown",
    lastError: null,
    // Secrets/stream keys are intentionally never accepted or stored here.
    credentialRef: clean(destination.credentialRef, 160) || null,
    updatedAt: new Date().toISOString()
  };
  const destinations = Array.isArray(current.destinations) ? current.destinations.filter(x => x.id !== id) : [];
  destinations.push(next);
  await ref.set({ destinations: destinations.slice(-32), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getProduction(uid);
}

export async function setProductionState(uid, patch = {}) {
  const ref = refFor(uid);
  const current = await getProduction(uid);
  const allowedStates = new Set(["idle", "ready", "live", "stopping", "error"]);
  const nextStatus = allowedStates.has(clean(patch.status, 30)) ? clean(patch.status, 30) : current.status;
  const sources = current.sources || [];
  const scenes = current.scenes || [];
  const activeSourceId = patch.activeSourceId == null ? current.activeSourceId : clean(patch.activeSourceId, 120);
  const activeSceneId = patch.activeSceneId == null ? current.activeSceneId : clean(patch.activeSceneId, 120);
  if (activeSourceId && !sources.some(x => x.id === activeSourceId)) throw new Error("source_not_found");
  if (activeSceneId && !scenes.some(x => x.id === activeSceneId)) throw new Error("scene_not_found");
  await ref.set({
    status: nextStatus,
    activeSourceId,
    activeSceneId,
    safeSourceId: patch.safeSourceId == null ? current.safeSourceId : clean(patch.safeSourceId, 120),
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });
  return getProduction(uid);
}

export async function updateHealth(uid, health = {}) {
  const ref = refFor(uid);
  const current = await getProduction(uid);
  const next = {
    source: clean(health.source, 40) || current.health?.source || "unknown",
    output: clean(health.output, 40) || current.health?.output || "idle",
    lastError: health.lastError ? clean(health.lastError, 1000) : null,
    reconnectAttempts: Math.max(0, Number(health.reconnectAttempts || 0)),
    checkedAt: new Date().toISOString()
  };
  await ref.set({ health: next, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getProduction(uid);
}

export async function getOrganizationAiLiveConfig(uid) {
  const production = await getProduction(uid);
  return {
    mode: production.aiLive?.mode || "host_only",
    privacyAccepted: production.aiLive?.privacyAccepted === true,
    privacyVersion: String(production.aiLive?.privacyVersion || ""),
    viewerReplies: production.aiLive?.viewerReplies === true,
    screenAwareness: production.aiLive?.screenAwareness === true,
    commentModeration: production.aiLive?.commentModeration || "strict",
    hostOnly: production.aiLive?.mode === "host_only",
    creditRatePerMinute: Number(production.aiLive?.creditRatePerMinute || 0.25)
  };
}

export async function updateOrganizationAiLiveConfig(uid, patch = {}) {
  const current = await getOrganizationAiLiveConfig(uid);
  const allowedModes = new Set(["host_only", "moderated_audience", "full_audience"]);
  const mode = allowedModes.has(String(patch.mode || "")) ? String(patch.mode) : current.mode;
  const modeRules = {
    host_only: { viewerReplies: false, screenAwareness: false, commentModeration: "strict", creditRatePerMinute: 0.25 },
    moderated_audience: { viewerReplies: true, screenAwareness: false, commentModeration: "strict", creditRatePerMinute: 0.55 },
    full_audience: { viewerReplies: true, screenAwareness: true, commentModeration: "strict", creditRatePerMinute: 0.95 }
  };
  const defaults = modeRules[mode];
  if (patch.privacyAccepted !== true && current.privacyAccepted !== true) {
    throw new Error("organization_ai_live_privacy_required");
  }
  const aiLive = {
    mode,
    privacyAccepted: true,
    privacyVersion: clean(patch.privacyVersion || current.privacyVersion || "2026-09", 40),
    viewerReplies: mode === "host_only" ? false : patch.viewerReplies !== false,
    screenAwareness: mode === "full_audience" ? patch.screenAwareness !== false : false,
    commentModeration: "strict",
    creditRatePerMinute: defaults.creditRatePerMinute,
    updatedAt: new Date().toISOString()
  };
  await refFor(uid).set({ aiLive, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getOrganizationAiLiveConfig(uid);
}

export async function addProductionEvent(uid, event = {}) {
  const db = getFirestore();
  const ref = db.collection("organizationProductionEvents").doc();
  await ref.set({
    uid,
    eventId: ref.id,
    type: clean(event.type, 100),
    status: clean(event.status, 40) || "info",
    sourceId: clean(event.sourceId, 120) || null,
    destinationId: clean(event.destinationId, 120) || null,
    message: clean(event.message, 2000),
    metadata: event.metadata && typeof event.metadata === "object" ? event.metadata : {},
    createdAt: FieldValue.serverTimestamp()
  });
  return { ok: true, eventId: ref.id };
}
