import { getFirestore, FieldValue } from "firebase-admin/firestore";
import crypto from "node:crypto";

const COLLECTION = "userMemory";

export async function getUserMemory(uid, limit = 80) {
  const snap = await getFirestore().collection(COLLECTION).doc(uid).get();
  if (!snap.exists) return { enabled: true, items: [] };
  const data = snap.data() || {};
  const items = Array.isArray(data.items) ? data.items.slice(-Math.min(200, Math.max(1, limit))) : [];
  return { enabled: data.enabled !== false, items };
}

export async function rememberUser(uid, text, source = "chat") {
  const value = String(text || "").trim().slice(0, 4000);
  if (!value) throw new Error("memory_text_required");
  const db = getFirestore();
  const ref = db.collection(COLLECTION).doc(uid);
  const snap = await ref.get();
  const data = snap.exists ? snap.data() : {};
  const items = Array.isArray(data.items) ? data.items : [];
  const item = {
    id: crypto.randomUUID(),
    text: value,
    source: String(source || "chat").slice(0, 40),
    createdAt: new Date().toISOString()
  };
  items.push(item);
  await ref.set({
    uid,
    enabled: data.enabled !== false,
    items: items.slice(-200),
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });
  return item;
}

export async function setMemoryEnabled(uid, enabled) {
  await getFirestore().collection(COLLECTION).doc(uid).set({
    uid,
    enabled: enabled === true,
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });
  return { enabled: enabled === true };
}

export async function clearUserMemory(uid) {
  // Memory is explicitly cleared only by the user's action; subscription expiry never calls this.
  await getFirestore().collection(COLLECTION).doc(uid).set({
    uid,
    enabled: true,
    items: [],
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });
  return { ok: true, items: [] };
}

export async function memoryContext(uid, maxItems = 30) {
  const memory = await getUserMemory(uid, maxItems);
  if (!memory.enabled || !memory.items.length) return "";
  return memory.items
    .map(item => String(item?.text || "").trim())
    .filter(Boolean)
    .slice(-maxItems)
    .join("\n");
}
