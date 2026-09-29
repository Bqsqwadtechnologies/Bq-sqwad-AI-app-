import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { COMMERCIAL_CATALOG } from "./plan-catalog.js";

function requirementHash(value) {
  let hash = 2166136261;
  for (let i = 0; i < value.length; i++) hash = Math.imul(hash ^ value.charCodeAt(i), 16777619);
  return (hash >>> 0).toString(16);
}

function dayKey() {
  return new Date().toISOString().slice(0, 10);
}

function monthKey() {
  return new Date().toISOString().slice(0, 7);
}

const DEFAULT_ALLOWED_PLAN_IDS = {
  capability_truth_guard: ["truthguardfree", "tgplus", "tgpro", "tgteam"],
  capability_life_matters: ["lifemathisfree", "lmstandard", "lmultimate"],
  capability_frontier_lab: ["frontierexplorer", "frontierpro", "frontierenterprise"],
  capability_companion: ["companionfree", "companionbasic", "companionplus", "companionpremium", "companionultimate"],
  capability_companion_checkin: ["companionfree", "companionbasic", "companionplus", "companionpremium", "companionultimate"],
  capability_companion_memory: ["companionbasic", "companionplus", "companionpremium", "companionultimate"],
  capability_companion_voice: ["companionplus", "companionpremium", "companionultimate"],
  capability_companion_camera: ["companionpremium", "companionultimate"]
};

const DEFAULT_MONTHLY_LIMITS = {
  capability_life_matters: {
    lifemathisfree: 10,
    lmstandard: 300
  },
  capability_truth_guard: {
    truthguardfree: 5,
    tgplus: 150,
    tgpro: 1000
  }
};

const PAYG_CAPABILITIES = new Set([
  // Organization AI is credit-funded pay-as-you-go; it does not require a
  // subscription plan. Admin capability controls still govern availability,
  // rollout, rate limits and the per-request credit price.
  "capability_organization_studio"
]);

const DEFAULT_RUNTIME_CAPABILITIES = new Set([
  "capability_chat",
  "capability_deep_research",
  "capability_knowledge_graphs",
  "capability_semantic_file_search",
  "capability_conversation_intelligence",
  "capability_project_workspaces",
  "capability_ai_generated_reports",
  "capability_scheduled_ai_tasks",
  "capability_truth_guard",
  "capability_learning_studio",
  "capability_life_matters",
  "capability_frontier_lab",
  "capability_personal_intelligence",
  "capability_companion",
  "capability_companion_checkin",
  "capability_companion_memory",
  "capability_companion_voice",
  "capability_companion_camera",
  "capability_codron",
  "capability_web_research",
  "capability_multimodal_images",
  "capability_multifile_reasoning",
  "capability_document_analysis",
  "capability_audio_analysis",
  "capability_video_analysis",
  "capability_organization_studio"
]);


export async function authorizeCapability(uid, controlId) {
  const db = getFirestore();
  const adminUser = await getAuth().getUser(uid);
  const adminClaims = adminUser.customClaims || {};
  const isSuperAdmin = adminUser.email === "isaacoshiomole0@gmail.com" && (adminClaims.admin === true || adminClaims.superAdmin === true || adminClaims.role === "admin" || adminClaims.role === "super_admin");
  const snap = await db.collection("adminAdvancedOptions").doc(controlId).get();
  const testModeSnap = await db.collection("adminAdvancedOptions").doc("admin_testing_mode").get();
  const adminTestMode = isSuperAdmin && testModeSnap.exists && testModeSnap.data()?.enabled === true;
  const control = snap.exists ? { id: controlId, ...snap.data() } : null;

  if (!control && !isSuperAdmin && !DEFAULT_RUNTIME_CAPABILITIES.has(controlId)) {
    throw new Error("capability_not_configured:" + controlId);
  }
  const effectiveControl = control || {
    id: controlId,
    enabled: true,
    killSwitch: false,
    rolloutPercent: 100,
    creditCost: PAYG_CAPABILITIES.has(controlId) ? 1 : 0,
    allowedPlanIds: [],
    config: { fallbackEnabled: true, fallbackProviderIds: ["gemini"] }
  };
  // The sole super-admin is always entitled to operate without consuming customer credits.
  // Test Mode remains available for QA-specific behavior, but is not required for normal admin use.
  if (isSuperAdmin) return { control: effectiveControl, planId: "admin-unlimited", creditCost: 0, commit: () => {}, release: async () => {} };

  if (effectiveControl.enabled === false || effectiveControl.killSwitch === true) {
    throw new Error("capability_disabled:" + controlId);
  }

  const operational = effectiveControl.config && typeof effectiveControl.config === "object" ? effectiveControl.config : {};
  if (operational.maintenanceMode === true) {
    throw new Error("capability_maintenance:" + controlId);
  }

  const rollout = Math.min(100, Math.max(0, Number(effectiveControl.rolloutPercent ?? 100)));
  if (rollout <= 0) throw new Error("capability_not_rolled_out:" + controlId);
  if (rollout < 100) {
    const bucket = Number.parseInt(requirementHash(uid + ":" + controlId), 16) % 100;
    if (bucket >= rollout) throw new Error("capability_not_rolled_out:" + controlId);
  }

  const isPaygCapability = PAYG_CAPABILITIES.has(controlId);

  // Subscription expiry/plan entitlement is deliberately skipped for PAYG
  // Organization AI. The user pays from the shared Firebase credit balance.
  let planId = "";
  if (!isPaygCapability) {
    const entitlement = await db.collection("entitlements").doc(uid).get();
    const entitlementData = entitlement.exists ? (entitlement.data() || {}) : {};
    planId = entitlement.exists ? String(entitlementData.planId || "") : "";
    if (planId) {
      const state = String(entitlementData.state || "");
      const endDateRaw = entitlementData.endDate;
      const endMillis = typeof endDateRaw?.toMillis === "function"
        ? endDateRaw.toMillis()
        : entitlementData.endDate instanceof Date
          ? entitlementData.endDate.getTime()
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
  }

  const configuredPlans = Array.isArray(effectiveControl.allowedPlanIds)
    ? effectiveControl.allowedPlanIds.map(String).filter(Boolean)
    : [];
  const plans = configuredPlans.length ? configuredPlans : (DEFAULT_ALLOWED_PLAN_IDS[controlId] || []);
  let effectivePlanId = planId || "";

  if (!isPaygCapability) {
    const planCatalogSnap = await db.collection("planCatalog").doc("config").get();
    const catalogSections = planCatalogSnap.exists && planCatalogSnap.data()?.catalog?.sections
      ? planCatalogSnap.data().catalog.sections
      : COMMERCIAL_CATALOG.sections;
    const catalogPlans = Object.values(catalogSections || {}).flat().filter(Boolean);
    const catalogById = new Map(catalogPlans.map(item => [String(item.id || ""), item]));
    const activePlans = plans.filter(id => catalogById.get(id)?.active !== false);
    const freePlanId = activePlans.find(id => {
      const item = catalogById.get(id);
      return item?.productType === "free" || item?.price?.free != null || item?.paymentChannel === "free";
    }) || null;

    if (plans.length) {
      if (planId) {
        if (!plans.includes(planId)) throw new Error("plan_not_entitled");
      } else if (!freePlanId) {
        throw new Error("plan_not_entitled");
      }
    }
    effectivePlanId = planId || freePlanId || "";
  }

  const monthlyLimit = DEFAULT_MONTHLY_LIMITS[controlId]?.[effectivePlanId] ?? null;
  let monthlyRef = null;
  if (monthlyLimit !== null && Number.isFinite(Number(monthlyLimit))) {
    monthlyRef = db.collection("capabilityUsageMonthly").doc(uid + "_" + controlId + "_" + monthKey());
    await db.runTransaction(async tx => {
      const usage = await tx.get(monthlyRef);
      const used = Number(usage.exists ? usage.data()?.count || 0 : 0);
      if (used >= Number(monthlyLimit)) throw new Error("monthly_capability_limit_reached");
      tx.set(monthlyRef, {
        uid,
        capability: controlId,
        planId: effectivePlanId || null,
        month: monthKey(),
        count: used + 1,
        updatedAt: FieldValue.serverTimestamp()
      }, { merge: true });
    });
  }

  const dailyLimit = effectiveControl.dailyLimit == null ? null : Math.max(0, Number(effectiveControl.dailyLimit));
  let dailyRef = null;
  if (dailyLimit !== null) {
    dailyRef = db.collection("capabilityUsage").doc(uid + "_" + controlId + "_" + dayKey());
    try {
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
    } catch (error) {
      if (monthlyRef) await releaseMonthlyUsage(db, monthlyRef, uid, controlId);
      throw error;
    }
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
      if (monthlyRef) await releaseMonthlyUsage(db, monthlyRef, uid, controlId);
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
      if (monthlyRef) await releaseMonthlyUsage(db, monthlyRef, uid, controlId);
      if (dailyRef) await releaseDailyUsage(db, dailyRef, uid, controlId);
      if (minuteRef) await releaseRateLimit(db, minuteRef);
      throw error;
    }
  }

  let released = false;
  let committed = false;
  return {
    control,
    planId: effectivePlanId || null,
    creditCost: isPaygCapability ? Math.max(1, Number(effectiveControl.creditCost || 1)) : Math.max(0, Number(effectiveControl.creditCost || 0)),
    commit: () => { committed = true; },
    release: async () => {
      if (released) return;
      released = true;
      if (concurrencyRef) await releaseConcurrency(db, concurrencyRef);
      if (monthlyRef && !committed) {
        await releaseMonthlyUsage(db, monthlyRef, uid, controlId);
      }
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

async function releaseRateLimit(db, ref) {
  await db.runTransaction(async tx => {
    const snap = await tx.get(ref);
    if (!snap.exists) return;
    const current = Number(snap.data()?.count || 0);
    if (current <= 1) tx.delete(ref);
    else tx.update(ref, { count: current - 1, updatedAt: FieldValue.serverTimestamp() });
  });
}

async function releaseMonthlyUsage(db, ref, uid, controlId) {
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
      month: monthKey(),
      count: current - 1,
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
