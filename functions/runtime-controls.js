import { getFirestore, FieldValue } from "firebase-admin/firestore";

const COLLECTIONS = Object.freeze({
  jobs: "aiJobs",
  workflows: "aiWorkflows",
  agents: "aiAgents",
  evaluations: "aiEvaluations",
  plugins: "aiPlugins",
  audit: "aiActivity"
});

function cleanString(value, max = 200) {
  return String(value ?? "").trim().slice(0, max);
}

export async function getCapabilityControl(capabilityId) {
  const id = String(capabilityId || "");
  if (!id) throw new Error("capability_required");
  const snap = await getFirestore().collection("adminAdvancedOptions").doc(id).get();
  if (!snap.exists) return null;
  return { id, ...snap.data() };
}

export function assertControlEnabled(control, capabilityId) {
  if (!control) throw new Error("capability_not_configured:" + capabilityId);
  if (control.enabled === false || control.killSwitch === true) {
    throw new Error("capability_disabled:" + capabilityId);
  }
  const rollout = Number(control.rolloutPercent ?? 100);
  if (rollout <= 0) throw new Error("capability_not_rolled_out:" + capabilityId);
  return control;
}

export function assertPlanAllowed(control, planId) {
  const plans = Array.isArray(control?.allowedPlanIds) ? control.allowedPlanIds.map(String).filter(Boolean) : [];
  if (plans.length && !plans.includes(String(planId || ""))) {
    throw new Error("plan_not_entitled");
  }
}

export async function writeActivity({ uid, capability, action, status, metadata = {} }) {
  const safeMetadata = Object.fromEntries(
    Object.entries(metadata).slice(0, 30).map(([key, value]) => [cleanString(key, 80), cleanString(value, 500)])
  );
  await getFirestore().collection(COLLECTIONS.audit).add({
    uid: cleanString(uid, 128),
    capability: cleanString(capability, 120),
    action: cleanString(action, 120),
    status: cleanString(status, 40),
    metadata: safeMetadata,
    createdAt: FieldValue.serverTimestamp()
  });
}

export async function createManagedRecord(collectionKey, uid, input) {
  const collection = COLLECTIONS[collectionKey];
  if (!collection) throw new Error("unsupported_runtime_collection");
  const ref = getFirestore().collection(collection).doc();
  const now = FieldValue.serverTimestamp();
  const payload = {
    uid,
    name: cleanString(input.name, 160),
    description: cleanString(input.description, 1000),
    status: "draft",
    version: 1,
    config: input.config && typeof input.config === "object" ? input.config : {},
    createdAt: now,
    updatedAt: now
  };
  await ref.set(payload);
  return { id: ref.id, ...payload };
}

export async function updateManagedRecord(collectionKey, uid, id, patch) {
  const collection = COLLECTIONS[collectionKey];
  if (!collection) throw new Error("unsupported_runtime_collection");
  const ref = getFirestore().collection(collection).doc(String(id));
  const snap = await ref.get();
  if (!snap.exists || snap.data()?.uid !== uid) throw new Error("runtime_record_not_found");
  const update = {
    ...(patch.name != null ? { name: cleanString(patch.name, 160) } : {}),
    ...(patch.description != null ? { description: cleanString(patch.description, 1000) } : {}),
    ...(patch.status != null ? { status: cleanString(patch.status, 40) } : {}),
    ...(patch.config && typeof patch.config === "object" ? { config: patch.config } : {}),
    version: Number(snap.data()?.version || 1) + 1,
    updatedAt: FieldValue.serverTimestamp()
  };
  await ref.update(update);
  return { id: ref.id, ...snap.data(), ...update };
}

export { COLLECTIONS };
