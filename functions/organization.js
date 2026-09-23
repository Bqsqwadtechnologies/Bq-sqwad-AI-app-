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

export async function updateOrganizationSettings(uid, patch = {}) {
  const ref = getFirestore().collection(COLLECTION).doc(uid);
  const snap = await ref.get();
  if (!snap.exists) throw new Error("organization_not_found");
  const current = snap.data() || {};
  const settings = { ...(current.settings || {}) };
  if (patch.assistantEnabled != null) settings.assistantEnabled = patch.assistantEnabled === true;
  if (patch.sharedMemoryEnabled != null) settings.sharedMemoryEnabled = patch.sharedMemoryEnabled === true;
  if (patch.sharedKnowledgeEnabled != null) settings.sharedKnowledgeEnabled = patch.sharedKnowledgeEnabled === true;
  await ref.set({ settings, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getOrganization(uid);
}

export async function addOrganizationKnowledge(uid, text, source = "manual") {
  const value = String(text || "").trim().slice(0, 12000);
  if (!value) throw new Error("organization_knowledge_required");
  const ref = getFirestore().collection(COLLECTION).doc(uid);
  const snap = await ref.get();
  const data = snap.exists ? snap.data() : {};
  const knowledge = Array.isArray(data.knowledge) ? data.knowledge : [];
  knowledge.push({
    id: crypto.randomUUID(),
    text: value,
    source: String(source || "manual").slice(0, 80),
    createdAt: new Date().toISOString()
  });
  await ref.set({ uid, knowledge: knowledge.slice(-500), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getOrganization(uid);
}

export async function upsertOrganizationMember(uid, memberId, role = "member") {
  const id = String(memberId || "").trim();
  if (!id) throw new Error("member_id_required");
  const allowedRoles = new Set(["owner", "admin", "editor", "viewer", "member"]);
  const nextRole = allowedRoles.has(String(role)) ? String(role) : "member";
  const ref = getFirestore().collection(COLLECTION).doc(uid);
  const snap = await ref.get();
  if (!snap.exists) throw new Error("organization_not_found");
  const members = Array.isArray(snap.data()?.members) ? snap.data().members : [];
  const existing = members.find(member => member.id === id);
  const next = existing
    ? members.map(member => member.id === id ? { ...member, role: nextRole, status: "active" } : member)
    : [...members, { id, role: nextRole, status: "active", addedAt: new Date().toISOString() }];
  await ref.set({ members: next.slice(-100), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getOrganization(uid);
}

export async function setOrganizationMemberStatus(uid, memberId, status) {
  const id = String(memberId || "").trim();
  if (!id) throw new Error("member_id_required");
  const nextStatus = String(status || "").toLowerCase() === "inactive" ? "inactive" : "active";
  const ref = getFirestore().collection(COLLECTION).doc(uid);
  const snap = await ref.get();
  if (!snap.exists) throw new Error("organization_not_found");
  const members = Array.isArray(snap.data()?.members) ? snap.data().members : [];
  const next = members.map(member => member.id === id && member.role !== "owner" ? { ...member, status: nextStatus } : member);
  await ref.set({ members: next, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
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
