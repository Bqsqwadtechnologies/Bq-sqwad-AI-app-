import crypto from "node:crypto";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

const COLLECTION = "organizationWorkspaces";

export async function getOrganization(uid) {
  const ref = getFirestore().collection(COLLECTION).doc(uid);
  const snap = await ref.get();
  if (!snap.exists) {
    const initial = {
      uid,
      name: "My Organization",
      description: "",
      members: [{ id: uid, role: "owner", status: "active" }],
      channels: [],
      settings: { assistantEnabled: true, sharedMemoryEnabled: false },
      knowledge: [],
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp()
    };
    await ref.set(initial);
    return { uid, name: initial.name, description: "", members: initial.members, channels: [], settings: initial.settings, knowledge: [] };
  }
  return { exists: true, ...snap.data() };
}

export async function updateOrganization(uid, patch = {}) {
  const ref = getFirestore().collection(COLLECTION).doc(uid);
  const allowed = {
    name: String(patch.name || "").trim().slice(0, 160),
    description: String(patch.description || "").trim().slice(0, 2000)
  };
  if (!allowed.name) throw new Error("organization_name_required");
  await ref.set({ ...allowed, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  const snap = await ref.get();
  return { exists: true, ...snap.data() };
}

export async function addOrganizationChannel(uid, name, type = "chat") {
  const ref = getFirestore().collection(COLLECTION).doc(uid);
  const snap = await ref.get();
  const data = snap.exists ? snap.data() : {};
  const channels = Array.isArray(data.channels) ? data.channels : [];
  channels.push({
    id: crypto.randomUUID(),
    name: String(name || "").trim().slice(0, 120),
    type: String(type || "chat").slice(0, 40),
    enabled: true,
    createdAt: new Date().toISOString()
  });
  await ref.set({ uid, channels: channels.slice(-100), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getOrganization(uid);
}

export async function setOrganizationChannel(uid, channelId, enabled) {
  const ref = getFirestore().collection(COLLECTION).doc(uid);
  const snap = await ref.get();
  if (!snap.exists) throw new Error("organization_not_found");
  const channels = Array.isArray(snap.data()?.channels) ? snap.data().channels : [];
  const next = channels.map(channel => channel.id === channelId ? { ...channel, enabled: enabled === true } : channel);
  await ref.set({ channels: next, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getOrganization(uid);
}
