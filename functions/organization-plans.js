import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

export const ORGANIZATION_PLAN_CATALOG = [
  {
    id: "orgcore",
    name: "Organization Core",
    description: "Organization AI assistant, shared knowledge, channels, members and scheduled AI operations.",
    billingModel: "pay_as_you_go",
    price: null,
    features: ["Organization assistant", "Shared knowledge", "Channels", "Member roles", "Scheduled AI"],
    capabilities: ["assistant", "knowledge", "channels", "members", "scheduled"]
  },
  {
    id: "orgproduction",
    name: "Organization Production",
    description: "Professional production controls, source and scene management, AI Director and health controls.",
    billingModel: "pay_as_you_go",
    price: null,
    features: ["Everything in Core", "Production sources", "Scenes", "AI Director", "Production health", "Operator override"],
    capabilities: ["assistant", "knowledge", "channels", "members", "scheduled", "production"]
  },
  {
    id: "orgbroadcast",
    name: "Organization Broadcast",
    description: "Live broadcast operations with YouTube, live chat moderation and continuous Organization usage billing.",
    billingModel: "pay_as_you_go",
    price: null,
    features: ["Everything in Production", "YouTube Live", "Live chat", "Audience modes", "Continuous live billing", "Protected-content controls"],
    capabilities: ["assistant", "knowledge", "channels", "members", "scheduled", "production", "youtube_live", "live_chat"]
  },
  {
    id: "orgenterprise",
    name: "Organization Enterprise",
    description: "Full Organization control surface for advanced governance, production and broadcast operations.",
    billingModel: "pay_as_you_go",
    price: null,
    features: ["Everything in Broadcast", "Advanced governance", "Audit-ready controls", "Enterprise production policy", "Full admin test surface"],
    capabilities: ["assistant", "knowledge", "channels", "members", "scheduled", "production", "youtube_live", "live_chat", "governance"]
  }
];

function adminAllowed(user) {
  const claims = user.customClaims || {};
  return user.email === "isaacoshiomole0@gmail.com" &&
    (claims.admin === true || claims.superAdmin === true || claims.role === "admin" || claims.role === "super_admin");
}

export function findOrganizationPlan(planId) {
  return ORGANIZATION_PLAN_CATALOG.find(plan => plan.id === String(planId || "").trim()) || null;
}

export async function getOrganizationCatalog() {
  const db = getFirestore();
  const snap = await db.collection("organizationPlanStatus").get();
  const status = new Map(snap.docs.map(doc => [doc.id, doc.data() || {}]));
  return ORGANIZATION_PLAN_CATALOG.map(plan => ({
    ...plan,
    active: status.get(plan.id)?.active === true,
    price: null,
    currency: "USD",
    pricingLabel: "Pay-as-you-go usage",
    activatedAt: status.get(plan.id)?.activatedAt || null,
    updatedAt: status.get(plan.id)?.updatedAt || null
  }));
}

export async function getOrganizationAccess(uid, options = {}) {
  const db = getFirestore();
  const entitlement = await db.collection("organizationEntitlements").doc(uid).get();
  const entitlementData = entitlement.exists ? entitlement.data() || {} : {};
  const test = options.testPlanId ? findOrganizationPlan(options.testPlanId) : null;
  const planId = test?.id || String(entitlementData.planId || "");
  const plan = findOrganizationPlan(planId);
  const statuses = await db.collection("organizationPlanStatus").get();
  const activeIds = new Set(statuses.docs.filter(doc => doc.data()?.active === true).map(doc => doc.id));
  const active = Boolean(plan && activeIds.has(plan.id));
  return {
    uid,
    activated: active,
    planId: active ? plan.id : null,
    plan: active ? plan : null,
    testMode: Boolean(test),
    source: test ? "admin_test_session" : entitlement.exists ? "admin_activation" : "not_activated",
    activatedAt: entitlementData.activatedAt || null,
    expiresAt: entitlementData.expiresAt || null
  };
}

export async function setOrganizationPlanStatus(adminUid, planId, active, confirmDeactivation = false) {
  const admin = await getAuth().getUser(adminUid);
  if (!adminAllowed(admin)) throw new Error("Admin access required");
  const plan = findOrganizationPlan(planId);
  if (!plan) throw new Error("organization_plan_not_found");
  if (!active && !confirmDeactivation) throw new Error("organization_plan_deactivation_confirmation_required");
  await getFirestore().collection("organizationPlanStatus").doc(plan.id).set({
    planId: plan.id,
    active: active === true,
    updatedBy: adminUid,
    updatedAt: FieldValue.serverTimestamp(),
    ...(active ? { activatedAt: FieldValue.serverTimestamp() } : {})
  }, { merge: true });
  await getFirestore().collection("adminConfigAudit").add({
    type: "organization_plan_status",
    planId: plan.id,
    active: active === true,
    actorUid: adminUid,
    createdAt: FieldValue.serverTimestamp()
  });
  return { ok: true, planId: plan.id, active: active === true };
}

export async function setOrganizationEntitlement(adminUid, uid, planId, active, confirmDeactivation = false) {
  const admin = await getAuth().getUser(adminUid);
  if (!adminAllowed(admin)) throw new Error("Admin access required");
  const plan = findOrganizationPlan(planId);
  if (!plan) throw new Error("organization_plan_not_found");
  const ref = getFirestore().collection("organizationEntitlements").doc(String(uid || "").trim());
  if (!String(uid || "").trim()) throw new Error("organization_user_required");
  if (!active && !confirmDeactivation) throw new Error("organization_entitlement_deactivation_confirmation_required");
  if (active) {
    const status = await getFirestore().collection("organizationPlanStatus").doc(plan.id).get();
    if (!status.exists || status.data()?.active !== true) throw new Error("organization_plan_not_activated_globally");
    await ref.set({
      uid,
      planId: plan.id,
      state: "active",
      activatedBy: adminUid,
      activatedAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
  } else {
    await ref.set({
      uid,
      planId: plan.id,
      state: "inactive",
      deactivatedBy: adminUid,
      deactivatedAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
  }
  await getFirestore().collection("adminConfigAudit").add({
    type: "organization_entitlement",
    uid,
    planId: plan.id,
    active: active === true,
    actorUid: adminUid,
    createdAt: FieldValue.serverTimestamp()
  });
  return { ok: true, uid, planId: plan.id, active: active === true };
}

export async function listOrganizationEntitlements(adminUid) {
  const admin = await getAuth().getUser(adminUid);
  if (!adminAllowed(admin)) throw new Error("Admin access required");
  const snap = await getFirestore().collection("organizationEntitlements").limit(500).get();
  return snap.docs.map(doc => ({ uid: doc.id, ...doc.data() }));
}

async function testSessionRef(adminUid) {
  return getFirestore().collection("organizationTestSessions").doc(adminUid);
}

export async function getOrganizationTestSession(adminUid) {
  const admin = await getAuth().getUser(adminUid);
  if (!adminAllowed(admin)) throw new Error("Admin access required");
  const ref = await testSessionRef(adminUid);
  const snap = await ref.get();
  if (!snap.exists) return { enabled: false, planId: null };
  const data = snap.data() || {};
  const expiresMs = data.expiresAt?.toMillis?.() || Date.parse(String(data.expiresAt || ""));
  if (data.enabled !== true || (Number.isFinite(expiresMs) && expiresMs <= Date.now())) {
    await ref.set({ enabled: false, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    return { enabled: false, planId: null };
  }
  const plan = findOrganizationPlan(data.planId);
  return { enabled: true, planId: plan?.id || null, plan, expiresAt: data.expiresAt || null };
}

export async function setOrganizationTestSession(adminUid, planId, enabled = true, durationMinutes = 120) {
  const admin = await getAuth().getUser(adminUid);
  if (!adminAllowed(admin)) throw new Error("Admin access required");
  const plan = findOrganizationPlan(planId);
  if (enabled && !plan) throw new Error("organization_plan_not_found");
  const ref = await testSessionRef(adminUid);
  if (!enabled) {
    await ref.set({ enabled: false, planId: null, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    return { enabled: false, planId: null };
  }
  const expiresAt = new Date(Date.now() + Math.min(Math.max(Number(durationMinutes) || 120, 5), 1440) * 60000);
  await ref.set({ enabled: true, planId: plan.id, expiresAt, updatedAt: FieldValue.serverTimestamp(), updatedBy: adminUid }, { merge: true });
  return { enabled: true, planId: plan.id, plan, expiresAt: expiresAt.toISOString() };
}

export async function getAdminOrganizationControl(adminUid) {
  const admin = await getAuth().getUser(adminUid);
  if (!adminAllowed(admin)) throw new Error("Admin access required");
  return {
    plans: await getOrganizationCatalog(),
    entitlements: await listOrganizationEntitlements(adminUid),
    testSession: await getOrganizationTestSession(adminUid)
  };
}
