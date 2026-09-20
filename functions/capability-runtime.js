import { getFirestore, FieldValue } from "firebase-admin/firestore";

function requirementHash(value) {
  let hash = 2166136261;
  for (let i = 0; i < value.length; i++) hash = Math.imul(hash ^ value.charCodeAt(i), 16777619);
  return (hash >>> 0).toString(16);
}

function dayKey() {
  return new Date().toISOString().slice(0, 10);
}

export async function authorizeCapability(uid, controlId) {
  const db = getFirestore();
  const snap = await db.collection("adminAdvancedOptions").doc(controlId).get();
  const control = snap.exists ? { id: controlId, ...snap.data() } : null;

  if (!control) throw new Error("capability_not_configured:" + controlId);
  if (control.enabled === false || control.killSwitch === true) {
    throw new Error("capability_disabled:" + controlId);
  }

  const rollout = Math.min(100, Math.max(0, Number(control.rolloutPercent ?? 100)));
  if (rollout <= 0) throw new Error("capability_not_rolled_out:" + controlId);
  if (rollout < 100) {
    const bucket = Number.parseInt(requirementHash(uid + ":" + controlId), 16) % 100;
    if (bucket >= rollout) throw new Error("capability_not_rolled_out:" + controlId);
  }

  const entitlement = await db.collection("entitlements").doc(uid).get();
  const planId = entitlement.exists ? String(entitlement.data()?.planId || "") : "";
  const plans = Array.isArray(control.allowedPlanIds)
    ? control.allowedPlanIds.map(String).filter(Boolean)
    : [];
  if (plans.length && !plans.includes(planId)) throw new Error("plan_not_entitled");

  const dailyLimit = control.dailyLimit == null ? null : Math.max(0, Number(control.dailyLimit));
  let dailyRef = null;
  if (dailyLimit !== null) {
    dailyRef = db.collection("capabilityUsage").doc(uid + "_" + controlId + "_" + dayKey());
    await db.runTransaction(async tx => {
      const usage = await tx.get(dailyRef);
      const used = Number(usage.exists ? usage.data()?.count || 0 : 0);
      if (used >= dailyLimit) throw new Error("daily_capability_limit_reached");
      tx.set(dailyRef, {
        uid,
        capability: controlId,
        date: dayKey(),
        count: used + 1,
        updatedAt: FieldValue.serverTimestamp()
      }, { merge: true });
    });
  }

  const concurrencyLimit = control.concurrencyLimit == null
    ? null
    : Math.max(0, Number(control.concurrencyLimit));
  let concurrencyRef = null;
  if (concurrencyLimit !== null && concurrencyLimit > 0) {
    concurrencyRef = db.collection("capabilityConcurrency").doc(uid + "_" + controlId);
    try {
      await db.runTransaction(async tx => {
        const usage = await tx.get(concurrencyRef);
        const active = Number(usage.exists ? usage.data()?.active || 0 : 0);
        if (active >= concurrencyLimit) throw new Error("concurrency_limit_reached");
        tx.set(concurrencyRef, {
          uid,
          capability: controlId,
          active: active + 1,
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
      });
    } catch (error) {
      if (dailyRef) {
        await releaseDailyUsage(db, dailyRef, uid, controlId);
      }
      throw error;
    }
  }

  let released = false;
  return {
    control,
    planId,
    creditCost: Math.max(0, Number(control.creditCost || 0)),
    release: async () => {
      if (released) return;
      released = true;
      if (concurrencyRef) await releaseConcurrency(db, concurrencyRef);
      if (dailyRef) await releaseDailyUsage(db, dailyRef, uid, controlId);
    }
  };
}

async function releaseConcurrency(db, ref) {
  await db.runTransaction(async tx => {
    const snap = await tx.get(ref);
    if (!snap.exists) return;
    tx.update(ref, {
      active: Math.max(0, Number(snap.data()?.active || 0) - 1),
      updatedAt: FieldValue.serverTimestamp()
    });
  });
}

async function releaseDailyUsage(db, ref, uid, controlId) {
  await db.runTransaction(async tx => {
    const snap = await tx.get(ref);
    if (!snap.exists) return;
    const current = Number(snap.data()?.count || 0);
    if (current <= 1) {
      tx.delete(ref);
      return;
    }
    tx.update(ref, {
      uid,
      capability: controlId,
      date: dayKey(),
      count: current - 1,
      updatedAt: FieldValue.serverTimestamp()
    });
  });
}
