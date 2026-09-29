import crypto from "node:crypto";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { runAi } from "./provider-gateway.js";
import { getOrganizationWallet, chargeOrganizationWallet } from "./organization-billing.js";

function due(task, now = Date.now()) {
  if (task.enabled === false) return false;
  const next = Date.parse(String(task.nextRunAt || ""));
  return Number.isFinite(next) && next <= now;
}

function nextRun(schedule, from = new Date()) {
  const value = String(schedule || "").trim().toLowerCase();
  if (value === "hourly") return new Date(from.getTime() + 60 * 60 * 1000).toISOString();
  if (value === "daily") return new Date(from.getTime() + 24 * 60 * 60 * 1000).toISOString();
  if (value === "weekly") return new Date(from.getTime() + 7 * 24 * 60 * 60 * 1000).toISOString();
  const minutes = Number(value.match(/^(\d+)m$/)?.[1] || 0);
  if (minutes >= 5) return new Date(from.getTime() + minutes * 60 * 1000).toISOString();
  throw new Error("unsupported_schedule");
}

export async function listOrganizationScheduledTasks(uid) {
  const snap = await getFirestore().collection("organizationScheduledTasks")
    .where("uid", "==", uid).limit(100).get();
  return snap.docs.map(doc => ({ id: doc.id, ...doc.data() }));
}

export async function createOrganizationScheduledTask(uid, input = {}) {
  const name = String(input.name || "").trim().slice(0, 160);
  const prompt = String(input.prompt || "").trim().slice(0, 8000);
  const schedule = String(input.schedule || "daily").trim().toLowerCase().slice(0, 40);
  if (!name || !prompt) throw new Error("scheduled_task_name_and_prompt_required");
  const nextRunAt = nextRun(schedule);
  const ref = getFirestore().collection("organizationScheduledTasks").doc();
  await ref.set({
    id: ref.id, uid, name, prompt, schedule, enabled: input.enabled !== false,
    nextRunAt, lastRunAt: null, lastStatus: "pending", lastResult: "",
    createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp()
  });
  return { id: ref.id, ...(await ref.get()).data() };
}

export async function updateOrganizationScheduledTask(uid, id, patch = {}) {
  const ref = getFirestore().collection("organizationScheduledTasks").doc(String(id || ""));
  const snap = await ref.get();
  if (!snap.exists || snap.data()?.uid !== uid) throw new Error("scheduled_task_not_found");
  const next = {};
  if (patch.name != null) next.name = String(patch.name).trim().slice(0, 160);
  if (patch.prompt != null) next.prompt = String(patch.prompt).trim().slice(0, 8000);
  if (patch.enabled != null) next.enabled = patch.enabled === true;
  if (patch.schedule != null) next.schedule = String(patch.schedule).trim().toLowerCase().slice(0, 40);
  if (next.schedule) next.nextRunAt = nextRun(next.schedule);
  next.updatedAt = FieldValue.serverTimestamp();
  await ref.set(next, { merge: true });
  return { id: ref.id, ...(await ref.get()).data() };
}

export async function runOrganizationScheduledTask(uid, id) {
  const ref = getFirestore().collection("organizationScheduledTasks").doc(String(id || ""));
  const snap = await ref.get();
  if (!snap.exists || snap.data()?.uid !== uid) throw new Error("scheduled_task_not_found");
  const task = snap.data();
  try {
    const wallet = await getOrganizationWallet(uid);
    if (wallet.balanceCents < wallet.assistantPriceCents) throw new Error("organization_insufficient_balance");
    const result = await runAi({
      capability: "organization_studio",
      message: String(task.prompt || ""),
      system: "You are executing an organization-scoped scheduled AI task. Do not invent organization facts. Return a concise useful result."
    });
    const charge = await chargeOrganizationWallet(uid, wallet.assistantPriceCents, "organization_scheduled_task", { providerId: result.providerId || null, taskId: ref.id });
    const now = new Date();
    await ref.set({
      lastRunAt: now.toISOString(),
      lastStatus: "success",
      lastResult: String(result.text || "").slice(0, 20000),
      nextRunAt: nextRun(task.schedule, now),
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
    return { ok: true, result: result.text, nextRunAt: nextRun(task.schedule, now), amountChargedCents: charge.chargedCents, balanceCents: charge.balanceCents };
  } catch (error) {
    await ref.set({
      lastRunAt: new Date().toISOString(),
      lastStatus: "error",
      lastResult: String(error?.message || "scheduled_task_failed").slice(0, 2000),
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
    throw error;
  }
}

async function claimDueTask(doc) {
  const db = getFirestore();
  const ref = db.collection("organizationScheduledTasks").doc(doc.id);
  const lockId = crypto.randomUUID();
  const now = new Date();
  const claimed = await db.runTransaction(async tx => {
    const snap = await tx.get(ref);
    if (!snap.exists) return false;
    const task = snap.data() || {};
    if (!due(task)) return false;
    // Scheduled invocations can overlap. Claim the task atomically so the same
    // due task cannot be executed twice by concurrent scheduler invocations.
    tx.set(ref, {
      lastStatus: "running",
      runLockId: lockId,
      runStartedAt: now.toISOString(),
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
    return true;
  });
  return claimed;
}

export async function runDueOrganizationScheduledTasks() {
  const db = getFirestore();
  const snap = await db.collection("organizationScheduledTasks").where("enabled", "==", true).limit(200).get();
  const results = [];
  for (const doc of snap.docs) {
    if (!(await claimDueTask(doc))) continue;
    const task = doc.data() || {};
    try {
      results.push({ id: doc.id, ok: true, ...(await runOrganizationScheduledTask(task.uid, doc.id)) });
    } catch (error) {
      results.push({ id: doc.id, ok: false, error: error?.message || "scheduled_task_failed" });
    }
  }
  return results;
}
