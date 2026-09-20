import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { assertControlEnabled, assertPlanAllowed, getCapabilityControl } from "./runtime-controls.js";

function requirementHash(value) {\n  let hash = 2166136261;\n  for (let i = 0; i < value.length; i++) hash = Math.imul(hash ^ value.charCodeAt(i), 16777619);\n  return (hash >>> 0).toString(16);\n}\n\nfunction dayKey() {
  return new Date().toISOString().slice(0, 10);
}

export async function authorizeCapability(uid, controlId) {
  const control = assertControlEnabled(await getCapabilityControl(controlId), controlId);
  const rollout = Math.min(100, Math.max(0, Number(control.rolloutPercent ?? 100)));
  if (rollout < 100) {\n    const bucket = Number.parseInt(requirementHash(uid + ":" + controlId), 16) % 100;\n    if (bucket >= rollout) throw new Error("capability_not_rolled_out:" + controlId);\n  }
  const db = getFirestore();
  const entitlement = await db.collection("entitlements").doc(uid).get();
  const planId = entitlement.exists ? String(entitlement.data()?.planId || "") : "";
  assertPlanAllowed(control, planId);

  const dailyLimit = control.dailyLimit == null ? null : Math.max(0, Number(control.dailyLimit));
  if (dailyLimit !== null) {
    const ref = db.collection("capabilityUsage").doc(uid + "_" + controlId + "_" + dayKey());
    await db.runTransaction(async tx => {
      const snap = await tx.get(ref);
      const used = Number(snap.exists ? snap.data()?.count || 0 : 0);
      if (used >= dailyLimit) throw new Error("daily_capability_limit_reached");
      tx.set(ref, { uid, capability: controlId, date: dayKey(), count: used + 1, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    });
  }

  const concurrencyLimit = control.concurrencyLimit == null ? null : Math.max(0, Number(control.concurrencyLimit));
  let concurrencyRef = null;
  if (concurrencyLimit !== null && concurrencyLimit > 0) {
    concurrencyRef = db.collection("capabilityConcurrency").doc(uid + "_" + controlId);
    await db.runTransaction(async tx => {
      const snap = await tx.get(concurrencyRef);
      const active = Number(snap.exists ? snap.data()?.active || 0 : 0);
      if (active >= concurrencyLimit) throw new Error("concurrency_limit_reached");
      tx.set(concurrencyRef, { uid, capability: controlId, active: active + 1, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    });
  }

  let released = false;
  return {
    control,
    planId,
    creditCost: Math.max(0, Number(control.creditCost || 0)),
    release: async () => {
      if (released || !concurrencyRef) return;
      released = true;
      await db.runTransaction(async tx => {
        const snap = await tx.get(concurrencyRef);
        if (!snap.exists) return;
        tx.update(concurrencyRef, { active: Math.max(0, Number(snap.data()?.active || 0) - 1), updatedAt: FieldValue.serverTimestamp() });
      });
    }
  };
}
