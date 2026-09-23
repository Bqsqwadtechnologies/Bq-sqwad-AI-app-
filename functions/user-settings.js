import { getFirestore, FieldValue } from "firebase-admin/firestore";

const ALLOWED_KEYS = new Set([
  "web_search",
  "memory",
  "notifications",
  "history",
  "auto_run",
  "streaming",
  "voice_responses",
  "save_files",
  "personalization",
  "background_jobs"
]);

function cleanSettings(input = {}) {
  const source = input && typeof input === "object" ? input : {};
  const out = {};
  for (const key of ALLOWED_KEYS) {
    if (typeof source[key] === "boolean") out[key] = source[key];
  }
  return out;
}

export async function getUserFeatureSettings(uid) {
  const ref = getFirestore().collection("userFeatureSettings").doc(uid);
  const snap = await ref.get();
  return {
    uid,
    settings: snap.exists ? (snap.data()?.settings || {}) : {},
    exists: snap.exists
  };
}

export async function updateUserFeatureSettings(uid, workspaceId, settings = {}) {
  const cleaned = cleanSettings(settings);
  const ref = getFirestore().collection("userFeatureSettings").doc(uid);
  const snap = await ref.get();
  const current = snap.exists ? (snap.data()?.settings || {}) : {};
  const workspace = String(workspaceId || "global").slice(0, 80);
  const next = {
    ...current,
    [workspace]: {
      ...(current[workspace] || {}),
      ...cleaned
    }
  };
  await ref.set({
    uid,
    settings: next,
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });
  return { uid, settings: next };
}
