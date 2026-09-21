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

  const operational = control.config && typeof control.config === "object" ? control.config : {};
  if (operational.maintenanceMode === true) {
    throw new Error("capability_maintenance:" + controlId);
  }

  const rollout = Math.min(100, Math.max(0, Number(control.rolloutPercent ?? 100)));
  if (rollout <= 0) throw new Error("capability_not_rolled_out:" + controlId);
  if (rollout < 100) {
    const bucket = Number.parseInt(requirementHash(uid + ":" + controlId), 16) % 100;
    if (bucket >= rollout) throw new Error("capability_not_rolled_out:" + controlId);
  }

  const entitlement = await db.collection("entitlements").doc(uid).get();
  const entitlementData = entitlement.exists ? (entitlement.data() || {}) : {};
  const planId = entitlement.exists ? String(entitlementData.planId || "") : "";
  if (planId) {
    const state = String(entitlementData.state || "");
    const endDateRaw = entitlementData.endDate;
    const endMillis = typeof endDateRaw?.toMillis === "function"
      ? endDateRaw.toMillis()
      : endDateRaw instanceof Date
        ? endDateRaw.getTime()
        : endDateRaw
          ? Date.parse(String(endDateRaw))
          : NaN;
    const hasFutureEndDate = Number.isFinite(endMillis) && endMillis > Date.now();
    const activeStates = new Set(["active", "grace", "on_hold", "paused"]);
    const cancelledButStillPaid = state === "cancelled" && hasFutureEndDate;
    const validByEndDate = !Number.isFinite(endMillis) || hasFutureEndDate;
    if ((!activeStates.has(state) && !cancelledButStillPaid) || !validByEndDate) {
      await deactivateExpiredUserServices(db, uid);
      throw new Error("plan_expired");
    }
  }
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

  const minuteLimit = operational.rateLimitPerMinute == null
    ? null
    : Math.max(0, Number(operational.rateLimitPerMinute));
  let minuteRef = null;
  if (minuteLimit !== null && minuteLimit > 0) {
    const minuteKey = new Date().toISOString().slice(0, 16).replace(/[:]/g, "-");
    minuteRef = db.collection("capabilityRateLimits").doc(uid + "_" + controlId + "_" + minuteKey);
    try {
      await db.runTransaction(async tx => {
        const usage = await tx.get(minuteRef);
        const used = Number(usage.exists ? usage.data()?.count || 0 : 0);
        if (used >= minuteLimit) throw new Error("rate_limit_per_minute_reached");
        tx.set(minuteRef, { uid, capability: controlId, minute: minuteKey, count: used + 1, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      });
    } catch (error) {
      if (dailyRef) await releaseDailyUsage(db, dailyRef, uid, controlId);
      throw error;
    }
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
      if (dailyRef) await releaseDailyUsage(db, dailyRef, uid, controlId);
      throw error;
    }
  }

  let released = false;
  let committed = false;
  return {
    control,
    planId,
    creditCost: Math.max(0, Number(control.creditCost || 0)),
    commit: () => { committed = true; },
    release: async () => {
      if (released) return;
      released = true;
      if (concurrencyRef) await releaseConcurrency(db, concurrencyRef);
      if (dailyRef && !committed) {
        await releaseDailyUsage(db, dailyRef, uid, controlId);
      }
      if (minuteRef && !committed) {
        await releaseRateLimit(db, minuteRef);
      }
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

async function releaseRateLimit(db, ref) {\n  await db.runTransaction(async tx => {\n    const snap = await tx.get(ref);\n    if (!snap.exists) return;\n    const current = Number(snap.data()?.count || 0);\n    if (current <= 1) tx.delete(ref);\n    else tx.update(ref, { count: current - 1, updatedAt: FieldValue.serverTimestamp() });\n  });\n}\n\nasync function releaseRateLimit(db, ref) {
  await db.runTransaction(async tx => {
    const snap = await tx.get(ref);
    if (!snap.exists) return;
    const current = Number(snap.data()?.count || 0);
    if (current <= 1) tx.delete(ref);
    else tx.update(ref, { count: current - 1, updatedAt: FieldValue.serverTimestamp() });
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


export async function deactivateExpiredUserServices(db, uid) {
  const organizationRef = db.collection("organizationWorkspaces").doc(uid);
  const organization = await organizationRef.get();
  if (organization.exists) {
    const data = organization.data() || {};
    const channels = Array.isArray(data.channels)
      ? data.channels.map(channel => ({ ...channel, enabled: false, deactivatedByPlanExpiry: true }))
      : [];
    await organizationRef.set({
      channels,
      settings: { ...(data.settings || {}), assistantEnabled: false, assistantDisabledByPlanExpiry: true },
      servicesDeactivatedAt: FieldValue.serverTimestamp(),
      servicesDeactivationReason: "plan_expired",
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
  }

  const businessRef = db.collection("businessWorkspaces").doc(uid);
  const business = await businessRef.get();
  if (business.exists) {
    const data = business.data() || {};
    const channels = Array.isArray(data.channels)
      ? data.channels.map(channel => ({ ...channel, enabled: false, deactivatedByPlanExpiry: true }))
      : [];
    await businessRef.set({
      channels,
      assistantEnabled: false,
      servicesDeactivatedAt: FieldValue.serverTimestamp(),
      servicesDeactivationReason: "plan_expired",
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
  }
}


export async function restoreServicesAfterRenewal(db, uid) {
  const organizationRef = db.collection("organizationWorkspaces").doc(uid);
  const organization = await organizationRef.get();
  if (organization.exists) {
    const data = organization.data() || {};
    const settings = { ...(data.settings || {}) };
    if (settings.assistantDisabledByPlanExpiry === true) {
      settings.assistantEnabled = true;
      delete settings.assistantDisabledByPlanExpiry;
    }
    const channels = Array.isArray(data.channels)
      ? data.channels.map(channel => channel.deactivatedByPlanExpiry === true
          ? (() => { const next = { ...channel, enabled: true }; delete next.deactivatedByPlanExpiry; return next; })()
          : channel)
      : [];
    await organizationRef.set({
      channels,
      settings,
      servicesDeactivatedAt: null,
      servicesDeactivationReason: null,
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
  }

  const businessRef = db.collection("businessWorkspaces").doc(uid);
  const business = await businessRef.get();
  if (business.exists) {
    const data = business.data() || {};
    const channels = Array.isArray(data.channels)
      ? data.channels.map(channel => channel.deactivatedByPlanExpiry === true
          ? (() => { const next = { ...channel, enabled: true }; delete next.deactivatedByPlanExpiry; return next; })()
          : channel)
      : [];
    const patch = {
      channels,
      servicesDeactivatedAt: null,
      servicesDeactivationReason: null,
      updatedAt: FieldValue.serverTimestamp()
    };
    if (data.assistantDisabledByPlanExpiry === true) {
      patch.assistantEnabled = true;
      patch.assistantDisabledByPlanExpiry = null;
    }
    await businessRef.set(patch, { merge: true });
  }
}
