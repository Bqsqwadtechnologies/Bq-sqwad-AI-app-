import crypto from "node:crypto";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { COMMERCIAL_CATALOG } from "./plan-catalog.js";

const db = () => getFirestore();

const clean = (v, max = 500) => String(v ?? "").trim().slice(0, max);
const arr = (v, max = 100) => Array.isArray(v) ? v.map(x => clean(x, 300)).filter(Boolean).slice(0, max) : [];
const obj = v => v && typeof v === "object" && !Array.isArray(v) ? v : {};

export const BUSINESS_COLLECTIONS = Object.freeze({
  workspaces: "businessWorkspaces",
  members: "businessWorkspaceMembers",
  assistants: "businessAssistants",
  knowledge: "businessKnowledge",
  contacts: "businessContacts",
  leads: "businessLeads",
  pipelines: "businessPipelines",
  appointments: "businessAppointments",
  activities: "businessCrmActivities",
  faqs: "businessFaqs",
  vault: "businessVault",
  inbox: "businessInbox",
  automations: "businessAutomations",
  leadCaptures: "businessLeadCaptures"
});

function workspaceRef(id) { return db().collection(BUSINESS_COLLECTIONS.workspaces).doc(id); }

const BUSINESS_FEATURE_GROUPS = Object.freeze({
  assistant: ["AI Chat", "Business AI Employee", "Custom AI Assistants"],
  knowledge: ["Knowledge Base", "Knowledge Vault", "FAQ Knowledge Base", "Business Knowledge", "Custom AI Training"],
  crm: ["CRM", "CRM Integration", "Lead Capture", "Appointment Booking", "Business CRM"],
  channels: ["One-Click Multi-Channel Deploy", "Shared Inbox", "Omnichannel AI", "Business Channels"],
  automation: ["AI Workflow Automation", "Business Automation", "Business Workflows"],
  analytics: ["Analytics Dashboard", "Advanced Analytics", "Business Analytics"],
  advanced: ["Voice AI", "OCR Document Reading", "PDF Analysis", "Image Understanding", "AI Agent Builder"]
});

function businessCatalogPlans() {
  return Array.isArray(COMMERCIAL_CATALOG?.sections?.business) ? COMMERCIAL_CATALOG.sections.business : [];
}

async function effectiveBusinessPlan(uid, workspace) {
  const dbi = db();
  const adminUser = await getAuth().getUser(uid);
  const claims = adminUser.customClaims || {};
  const isAdmin = adminUser.email === "isaacoshiomole0@gmail.com" &&
    (claims.admin === true || claims.superAdmin === true || claims.role === "admin" || claims.role === "super_admin");
  if (isAdmin) {
    const testSnap = await dbi.collection("adminBusinessTestSessions").doc(uid).get();
    if (testSnap.exists) {
      const test = testSnap.data() || {};
      const expires = test.expiresAt?.toDate?.()?.getTime?.() || Number(test.expiresAt || 0);
      if (test.enabled === true && (!expires || expires > Date.now())) {
        const testPlan = businessCatalogPlans().find(p => p.id === String(test.planId || ""));
        if (testPlan && testPlan.active !== false) {
          return { plan: testPlan, source: "admin_test", adminTest: true, expiresAt: test.expiresAt || null, features: expandInheritedBusinessFeatures(testPlan.id) };
        }
      }
    }
  }

  const planId = clean(workspace?.planId, 100);
  let plan = businessCatalogPlans().find(p => p.id === planId);
  if (!plan || plan.active === false) throw new Error("business_plan_unavailable");
  const runtimePlan = await dbi.collection("planCatalog").doc("config").get();
  const runtimeRows = runtimePlan.exists ? (runtimePlan.data()?.catalog?.sections?.business || runtimePlan.data()?.catalog?.business || []) : [];
  const runtime = Array.isArray(runtimeRows) ? runtimeRows.find(p => String(p.id || "") === planId) : null;
  if (runtime) plan = { ...plan, ...runtime, limits: { ...(plan.limits || {}), ...(runtime.limits || {}) }, features: Array.isArray(runtime.features) ? runtime.features : plan.features };
  const featureRows = Array.isArray(runtimeRows) && runtimeRows.length ? runtimeRows : businessCatalogPlans();
  const statusSnap = await dbi.collection("businessPlanStatus").doc(planId).get();
  if (statusSnap.exists && statusSnap.data()?.active === false) throw new Error("business_plan_unavailable");

  const entitlementSnap = await dbi.collection("entitlements").doc(uid).get();
  const entitlement = entitlementSnap.exists ? (entitlementSnap.data() || {}) : {};
  const entitlementPlanId = clean(entitlement.planId, 100);
  const state = clean(entitlement.status || entitlement.state, 40).toLowerCase();
  const active = entitlement.active !== false && ["", "active", "trialing", "grace"].includes(state);
  const end = entitlement.endDate?.toDate?.()?.getTime?.() || Number(entitlement.endDate || 0);
  if (!entitlementPlanId || entitlementPlanId !== planId || !active || (end && end <= Date.now())) {
    throw new Error("business_plan_entitlement_required");
  }
  return { plan, source: String(entitlement.source || "website"), adminTest: false, expiresAt: entitlement.endDate || null, features: expandFeaturesFromRows(planId, featureRows) };
}

function expandInheritedBusinessFeatures(planId, seen = new Set()) {
  if (seen.has(planId)) return [];
  seen.add(planId);
  const plan = businessCatalogPlans().find(p => p.id === planId);
  if (!plan) return [];
  const own = Array.isArray(plan.features) ? plan.features.map(String) : [];
  const inherited = [];
  for (const feature of own) {
    const match = feature.match(/^Everything in (.+)$/i);
    if (!match) continue;
    const parent = businessCatalogPlans().find(p => p.name.toLowerCase() === match[1].trim().toLowerCase());
    if (parent) inherited.push(...expandInheritedBusinessFeatures(parent.id, seen));
  }
  return Array.from(new Set([...inherited, ...own.filter(x => !/^Everything in /i.test(x))]));
}

function expandFeaturesFromRows(planId, rows, seen = new Set()) {
  if (seen.has(planId)) return [];
  seen.add(planId);
  const plan = rows.find(p => String(p.id || "") === String(planId));
  if (!plan) return [];
  const own = Array.isArray(plan.features) ? plan.features.map(String) : [];
  const inherited = [];
  for (const feature of own) {
    const match = feature.match(/^Everything in (.+)$/i);
    if (!match) continue;
    const parent = rows.find(p => String(p.name || "").toLowerCase() === match[1].trim().toLowerCase());
    if (parent) inherited.push(...expandFeaturesFromRows(String(parent.id), rows, seen));
  }
  return Array.from(new Set([...inherited, ...own.filter(x => !/^Everything in /i.test(x))]));
}

function planHasBusinessFeature(planId, group) {
  const features = expandInheritedBusinessFeatures(planId).map(x => x.toLowerCase());
  const wanted = BUSINESS_FEATURE_GROUPS[group] || [];
  return wanted.some(item => features.includes(item.toLowerCase()));
}

function limitValue(plan, key) {
  const value = plan?.limits?.[key];
  if (value == null || value === "unlimited" || String(value).includes("unlimited")) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

export async function requireBusinessPlan(uid, workspaceId, group = null) {
  const ref = workspaceRef(workspaceId);
  const snap = await ref.get();
  if (!snap.exists) throw new Error("business_workspace_not_found");
  const context = await effectiveBusinessPlan(uid, snap.data());
  if (group && !(context.features || expandInheritedBusinessFeatures(context.plan.id)).some(feature => (BUSINESS_FEATURE_GROUPS[group] || []).some(w => String(feature).toLowerCase() === String(w).toLowerCase()))) {
    throw new Error("business_feature_not_in_plan");
  }
  return context;
}

async function enforceBusinessLimit(uid, workspaceId, key, currentValue) {
  const context = await requireBusinessPlan(uid, workspaceId);
  const limit = limitValue(context.plan, key);
  if (limit != null && Number(currentValue || 0) >= limit) {
    throw new Error("business_limit_reached:" + key);
  }
  return context;
}

function usageWarnings(workspace, plan) {
  const usage = obj(workspace?.usage);
  const limits = obj(plan?.limits);
  const candidates = [
    ["messages", Number(usage.messages || 0), limitValue(plan, "aiMessagesPerMonth"), "AI messages"],
    ["storage", Number(usage.storageGb || 0), limitValue(plan, "storageGb"), "storage"],
    ["assistants", Number(usage.assistants || 0), limitValue(plan, "assistants"), "assistants"],
    ["teamMembers", Number(usage.teamMembers || 0), limitValue(plan, "teamMembers"), "team members"]
  ];
  return candidates.filter(([,used,limit]) => limit != null && limit > 0 && used / limit >= 0.8).map(([key,used,limit,label]) => ({
    key, used, limit, percentage: Math.min(100, Math.round(used / limit * 100)), severity: used >= limit ? "limit" : "warning",
    message: used >= limit ? label + " limit reached." : label + " is approaching the plan limit.",
    recommendation: "Review eligible add-ons or upgrade the Business plan before the current billing period ends."
  }));
}

export async function runBusinessUsageWarnings() {
  const snap = await db().collection(BUSINESS_COLLECTIONS.workspaces).limit(500).get();
  const monthKey = new Date().toISOString().slice(0, 7);
  let checked = 0;
  let created = 0;
  for (const doc of snap.docs) {
    const workspace = doc.data() || {};
    const uid = clean(workspace.uid, 160);
    if (!uid) continue;
    checked++;
    try {
      const context = await effectiveBusinessPlan(uid, workspace);
      const warnings = usageWarnings(workspace, context.plan);
      if (!warnings.length) continue;
      const addOns = await eligibleBusinessAddOns(context.plan.id);
      for (const warning of warnings) {
        const recommendations = addOns.filter(a => {
          const id = String(a.id || "").toLowerCase();
          return warning.key === "messages" ? id.includes("msgs") :
            warning.key === "storage" ? id.includes("storage") :
            warning.key === "assistants" ? id.includes("assistant") :
            warning.key === "teamMembers" ? id.includes("team") || id.includes("user") : false;
        }).slice(0, 4).map(a => ({ id: String(a.id), name: String(a.name || a.id), price: a.price || null }));
        const notificationId = crypto.createHash("sha256").update(
          [uid, doc.id, warning.key, monthKey, warning.severity].join("|")
        ).digest("hex");
        const ref = db().collection("businessUsageNotifications").doc(notificationId);
        if ((await ref.get()).exists) continue;
        await ref.set({
          uid, workspaceId: doc.id, planId: context.plan.id,
          kind: "business_usage_threshold", thresholdPercent: 80,
          warning, recommendations, unread: true,
          createdAt: FieldValue.serverTimestamp(), monthKey
        });
        created++;
      }
    } catch (error) {
      console.error("business_usage_warning_failed", { workspaceId: doc.id, message: error?.message || "warning_failed" });
    }
  }
  return { ok: true, checked, created };
}

async function eligibleBusinessAddOns(planId) {
  const configured = await db().collection("addOns").where("enabled", "==", true).get();
  const fallback = COMMERCIAL_CATALOG.sections.businessAddOns || [];
  const rows = configured.empty ? fallback : configured.docs.map(d => ({ id: d.id, ...d.data() }));
  return rows.filter(x => !Array.isArray(x.eligiblePlanIds) || x.eligiblePlanIds.length === 0 || x.eligiblePlanIds.map(String).includes(planId))
    .slice(0, 20)
    .map(x => ({ id: String(x.id), name: String(x.name || x.id), description: String(x.description || ""), quantityTiers: Array.isArray(x.quantityTiers) ? x.quantityTiers : [], price: x.price || x.priceUsd || null }));
}


async function memberRole(uid, workspaceId) {
  const snap = await db().collection(BUSINESS_COLLECTIONS.members).doc(workspaceId + "_" + uid).get();
  if (snap.exists && snap.data()?.status !== "disabled") return String(snap.data()?.role || "member");
  const own = await workspaceRef(workspaceId).get();
  if (own.exists && own.data()?.ownerUid === uid) return "owner";
  return null;
}

export async function requireBusinessAccess(uid, workspaceId, minimum = "member") {
  const id = clean(workspaceId, 120);
  if (!id) throw new Error("business_workspace_required");
  const role = await memberRole(uid, id);
  const ranks = { viewer: 1, member: 2, manager: 3, admin: 4, owner: 5 };
  if (!role || (ranks[role] || 0) < (ranks[minimum] || 1)) throw new Error("business_workspace_access_denied");
  return role;
}

async function ensureWorkspace(uid, input = {}) {
  const requested = clean(input.workspaceId, 120);
  if (requested) {
    await requireBusinessAccess(uid, requested);
    return requested;
  }
  const own = await db().collection(BUSINESS_COLLECTIONS.workspaces).where("ownerUid", "==", uid).limit(1).get();
  if (!own.empty) return own.docs[0].id;
  const ref = db().collection(BUSINESS_COLLECTIONS.workspaces).doc();
  const now = FieldValue.serverTimestamp();
  await ref.set({
    ownerUid: uid,
    name: clean(input.name, 180) || "My Business",
    slug: clean(input.slug, 100) || ref.id,
    planId: clean(input.planId, 100) || "bizstarter",
    status: "active",
    settings: {
      timezone: clean(obj(input.settings).timezone, 80) || "UTC",
      currency: "USD",
      leadStatuses: ["new", "contacted", "qualified", "proposal", "won", "lost"]
    },
    usage: { messages: 0, contacts: 0, leads: 0, knowledgeItems: 0 },
    createdAt: now,
    updatedAt: now
  });
  await db().collection(BUSINESS_COLLECTIONS.members).doc(ref.id + "_" + uid).set({
    workspaceId: ref.id, uid, role: "owner", status: "active", permissions: ["*"],
    createdAt: now, updatedAt: now
  });
  return ref.id;
}

function sanitizeDoc(id, data) {
  const x = { id, ...data };
  if (x.createdAt?.toDate) x.createdAt = x.createdAt.toDate().toISOString();
  if (x.updatedAt?.toDate) x.updatedAt = x.updatedAt.toDate().toISOString();
  return x;
}

async function listOwned(collection, workspaceId, limit = 100) {
  const snap = await db().collection(collection).where("workspaceId", "==", workspaceId).limit(Math.min(200, Math.max(1, limit))).get();
  return snap.docs.map(d => sanitizeDoc(d.id, d.data())).sort((a,b) => String(b.updatedAt || b.createdAt || "").localeCompare(String(a.updatedAt || a.createdAt || "")));
}

export async function businessKnowledgeForWorkspace(uid, workspaceId, limit = 50) {
  await requireBusinessAccess(uid, workspaceId);
  return listOwned(BUSINESS_COLLECTIONS.knowledge, workspaceId, limit);
}

export async function businessWorkspace(uid, input = {}) {
  const workspaceId = await ensureWorkspace(uid, input);
  await requireBusinessAccess(uid, workspaceId);
  await requireBusinessPlan(uid, workspaceId);
  const [workspace, members, assistants, knowledge, contacts, leads, pipelines, appointments, inbox, automations, leadCaptures] = await Promise.all([
    workspaceRef(workspaceId).get(),
    listOwned(BUSINESS_COLLECTIONS.members, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.assistants, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.knowledge, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.contacts, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.leads, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.pipelines, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.appointments, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.inbox, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.automations, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.leadCaptures, workspaceId)
  ]);
  if (!workspace.exists) throw new Error("business_workspace_not_found");
  const workspaceData = sanitizeDoc(workspace.id, workspace.data());
  const planContext = await effectiveBusinessPlan(uid, workspaceData);
  const warnings = usageWarnings(workspaceData, planContext.plan);
  const recommendedAddOns = warnings.length ? await eligibleBusinessAddOns(planContext.plan.id) : [];
  return {
    workspaceId,
    ...workspaceData,
    workspace: workspaceData,
    assistantName: assistants[0]?.name || "Business AI Assistant",
    members, assistants, knowledge, contacts, leads, pipelines, appointments,
    inbox: inbox || [],
    automations: automations || [],
    leadCaptures: leadCaptures || [],
    trainingAssets: Array.isArray(workspaceData.trainingAssets) ? workspaceData.trainingAssets : [],
    planContext: {
      id: planContext.plan.id,
      name: planContext.plan.name,
      source: planContext.source,
      adminTest: planContext.adminTest === true,
      expiresAt: planContext.expiresAt || null,
      trialDays: Number(planContext.plan.trialDays || 0),
      trialEligible: planContext.plan.trialEligible === true,
      limits: planContext.plan.limits || {},
      features: Array.isArray(planContext.features) ? planContext.features : [],
      popular: planContext.plan.popular === true
    },
    enabledFeatureGroups: Object.keys(BUSINESS_FEATURE_GROUPS).filter(key => planHasBusinessFeature(planContext.plan.id, key)),
    usageWarnings: warnings,
    recommendedAddOns,
    analytics: {
      members: members.length, assistants: assistants.length, knowledge: knowledge.length,
      contacts: contacts.length, leads: leads.length, pipelines: pipelines.length, appointments: appointments.length,
      inbox: (inbox || []).length, automations: (automations || []).length, leadCaptures: (leadCaptures || []).length,
      requests: Number(workspaceData.usage?.messages || 0)
    }
  };
}

export async function createBusinessRecord(uid, type, input) {
  const map = {
    assistant: BUSINESS_COLLECTIONS.assistants,
    knowledge: BUSINESS_COLLECTIONS.knowledge,
    contact: BUSINESS_COLLECTIONS.contacts,
    lead: BUSINESS_COLLECTIONS.leads,
    pipeline: BUSINESS_COLLECTIONS.pipelines,
    appointment: BUSINESS_COLLECTIONS.appointments,
    activity: BUSINESS_COLLECTIONS.activities
  };
  const collection = map[type];
  if (!collection) throw new Error("unsupported_business_record");
  const workspaceId = await ensureWorkspace(uid, input);
  await requireBusinessAccess(uid, workspaceId, type === "activity" ? "member" : "manager");
  const group = ["assistant"].includes(type) ? "assistant" : ["knowledge"].includes(type) ? "knowledge" : ["contact","lead","pipeline","appointment"].includes(type) ? "crm" : null;
  const planContext = await requireBusinessPlan(uid, workspaceId, group);
  if (type === "assistant") await enforceBusinessLimit(uid, workspaceId, "assistants", (await listOwned(BUSINESS_COLLECTIONS.assistants, workspaceId)).length);
  const ref = db().collection(collection).doc();
  const payload = { ...obj(input), workspaceId, ownerUid: uid, createdBy: uid, createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() };
  delete payload.workspaceId;
  payload.workspaceId = workspaceId;
  if (type === "assistant") {
    payload.name = clean(input.name, 160) || "Business Assistant";
    payload.instructions = clean(input.instructions, 12000);
    payload.tools = arr(input.tools);
    payload.enabled = input.enabled !== false;
  } else if (type === "knowledge") {
    payload.title = clean(input.title, 200);
    payload.content = clean(input.content, 30000);
    payload.type = clean(input.type, 40) || "note";
    payload.tags = arr(input.tags);
    payload.sourceUrl = clean(input.sourceUrl, 1000);
  } else if (type === "contact") {
    payload.name = clean(input.name, 180);
    payload.email = clean(input.email, 320);
    payload.phone = clean(input.phone, 80);
    payload.company = clean(input.company, 180);
    payload.tags = arr(input.tags);
  } else if (type === "lead") {
    payload.contactId = clean(input.contactId, 120);
    payload.name = clean(input.name, 180);
    payload.email = clean(input.email, 320);
    payload.phone = clean(input.phone, 80);
    payload.status = clean(input.status, 40) || "new";
    payload.value = Math.max(0, Number(input.value || 0));
    payload.notes = clean(input.notes, 6000);
    payload.source = clean(input.source, 120);
    payload.tags = arr(input.tags);
  } else if (type === "pipeline") {
    payload.name = clean(input.name, 160) || "Sales Pipeline";
    payload.stages = Array.isArray(input.stages) && input.stages.length ? input.stages.slice(0, 30).map((s,i) => ({ id: clean(s.id,60) || "stage" + (i+1), name: clean(s.name,120) || "Stage " + (i+1), order: i })) : [
      {id:"new",name:"New",order:0},{id:"qualified",name:"Qualified",order:1},{id:"proposal",name:"Proposal",order:2},{id:"won",name:"Won",order:3},{id:"lost",name:"Lost",order:4}
    ];
  } else if (type === "appointment") {
    payload.title = clean(input.title, 200);
    payload.contactId = clean(input.contactId, 120);
    payload.startAt = clean(input.startAt, 80);
    payload.endAt = clean(input.endAt, 80);
    payload.status = clean(input.status, 40) || "scheduled";
    payload.notes = clean(input.notes, 6000);
  } else {
    payload.entityType = clean(input.entityType, 60);
    payload.entityId = clean(input.entityId, 120);
    payload.action = clean(input.action, 120);
    payload.note = clean(input.note, 3000);
  }
  await ref.set(payload);
  const usagePatch = {};
  if (type === "contact") usagePatch.contacts = FieldValue.increment(1);
  if (type === "lead") usagePatch.leads = FieldValue.increment(1);
  if (type === "knowledge") usagePatch.knowledgeItems = FieldValue.increment(1);
  if (type === "assistant") usagePatch.assistants = FieldValue.increment(1);
  await workspaceRef(workspaceId).set({ usage: usagePatch, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return sanitizeDoc(ref.id, (await ref.get()).data());
}

export async function updateBusinessRecord(uid, type, id, input) {
  const map = { assistant: BUSINESS_COLLECTIONS.assistants, knowledge: BUSINESS_COLLECTIONS.knowledge, contact: BUSINESS_COLLECTIONS.contacts, lead: BUSINESS_COLLECTIONS.leads, pipeline: BUSINESS_COLLECTIONS.pipelines, appointment: BUSINESS_COLLECTIONS.appointments };
  const collection = map[type];
  if (!collection || !id) throw new Error("unsupported_business_record");
  const ref = db().collection(collection).doc(id);
  const snap = await ref.get();
  if (!snap.exists) throw new Error("business_record_not_found");
  const workspaceId = clean(snap.data()?.workspaceId, 120);
  await requireBusinessAccess(uid, workspaceId, "manager");
  await requireBusinessPlan(uid, workspaceId);
  const allowed = obj(input);
  delete allowed.workspaceId; delete allowed.ownerUid; delete allowed.createdBy; delete allowed.createdAt; delete allowed.updatedAt; delete allowed.id;
  allowed.updatedAt = FieldValue.serverTimestamp();
  await ref.set(allowed, { merge: true });
  return sanitizeDoc(id, (await ref.get()).data());
}

export async function deleteBusinessRecord(uid, type, id) {
  const map = { assistant: BUSINESS_COLLECTIONS.assistants, knowledge: BUSINESS_COLLECTIONS.knowledge, contact: BUSINESS_COLLECTIONS.contacts, lead: BUSINESS_COLLECTIONS.leads, pipeline: BUSINESS_COLLECTIONS.pipelines, appointment: BUSINESS_COLLECTIONS.appointments };
  const collection = map[type];
  if (!collection || !id) throw new Error("unsupported_business_record");
  const ref = db().collection(collection).doc(id);
  const snap = await ref.get();
  if (!snap.exists) throw new Error("business_record_not_found");
  const workspaceId = clean(snap.data()?.workspaceId, 120);
  await requireBusinessAccess(uid, workspaceId, "manager");
  await requireBusinessPlan(uid, workspaceId);
  await ref.delete();
  return { ok: true, id };
}

export async function addBusinessMember(uid, input) {
  const workspaceId = clean(input.workspaceId, 120);
  await requireBusinessAccess(uid, workspaceId, "admin");
  await requireBusinessPlan(uid, workspaceId);
  const memberUid = clean(input.uid, 160);
  if (!memberUid) throw new Error("member_uid_required");
  const role = ["viewer","member","manager","admin"].includes(clean(input.role,30)) ? clean(input.role,30) : "member";
  const ref = db().collection(BUSINESS_COLLECTIONS.members).doc(workspaceId + "_" + memberUid);
  await ref.set({workspaceId, uid: memberUid, role, status:"active", permissions:arr(input.permissions,100), invitedBy:uid, updatedAt:FieldValue.serverTimestamp(), createdAt:FieldValue.serverTimestamp()},{merge:true});
  return sanitizeDoc(ref.id, (await ref.get()).data());
}


export async function updateBusinessSettings(uid, workspaceId, patch = {}) {
  const id = clean(workspaceId, 120);
  await requireBusinessAccess(uid, id, "manager");
  await requireBusinessPlan(uid, id);
  const ref = workspaceRef(id);
  const snap = await ref.get();
  if (!snap.exists) throw new Error("business_workspace_not_found");
  const current = snap.data() || {};
  const currentSettings = obj(current.settings);
  const settings = {
    ...currentSettings,
    timezone: clean(patch.timezone, 80) || currentSettings.timezone || "UTC",
    currency: "USD",
    assistantEnabled: patch.assistantEnabled == null ? currentSettings.assistantEnabled !== false : patch.assistantEnabled === true,
    automationEnabled: patch.automationEnabled == null ? currentSettings.automationEnabled !== false : patch.automationEnabled === true,
    notificationsEnabled: patch.notificationsEnabled == null ? currentSettings.notificationsEnabled !== false : patch.notificationsEnabled === true,
    webSearchEnabled: patch.webSearchEnabled == null ? currentSettings.webSearchEnabled !== false : patch.webSearchEnabled === true
  };
  await ref.set({ settings, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return businessWorkspace(uid, { workspaceId: id });
}

export async function listBusinessExtended(uid, workspaceId) {
  await requireBusinessAccess(uid, workspaceId);
  await requireBusinessPlan(uid, workspaceId);
  const [faqs, vault, inbox, automations, leadCaptures, activities] = await Promise.all([
    listOwned(BUSINESS_COLLECTIONS.faqs, workspaceId), listOwned(BUSINESS_COLLECTIONS.vault, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.inbox, workspaceId), listOwned(BUSINESS_COLLECTIONS.automations, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.leadCaptures, workspaceId), listOwned(BUSINESS_COLLECTIONS.activities, workspaceId)
  ]);
  const ws = await workspaceRef(workspaceId).get(); const usage = ws.exists ? (ws.data()?.usage || {}) : {};
  return { faqs, vault, inbox, automations, leadCaptures, activities, usage, forecast: { messagesUsed:Number(usage.messages||0), contactsUsed:Number(usage.contacts||0), leadsUsed:Number(usage.leads||0), knowledgeUsed:Number(usage.knowledgeItems||0), period:'current' } };
}

export async function createBusinessExtended(uid, type, input) {
  const map={faq:BUSINESS_COLLECTIONS.faqs,vault:BUSINESS_COLLECTIONS.vault,inbox:BUSINESS_COLLECTIONS.inbox,automation:BUSINESS_COLLECTIONS.automations,leadCapture:BUSINESS_COLLECTIONS.leadCaptures};
  const collection=map[type]; if(!collection) throw new Error('unsupported_business_extended_record');
  const workspaceId=await ensureWorkspace(uid,input); await requireBusinessAccess(uid,workspaceId,'manager');
  const group = type === 'automation' ? 'automation' : type === 'inbox' ? 'channels' : type === 'leadCapture' ? 'crm' : (type === 'faq' || type === 'vault' ? 'knowledge' : null);
  await requireBusinessPlan(uid, workspaceId, group);
  const ref=db().collection(collection).doc(); const now=FieldValue.serverTimestamp(); const p={workspaceId,ownerUid:uid,createdBy:uid,createdAt:now,updatedAt:now};
  if(type==='faq'){p.question=clean(input.question,1000);p.answer=clean(input.answer,12000);p.tags=arr(input.tags);if(!p.question||!p.answer)throw new Error('faq_question_and_answer_required');}
  else if(type==='vault'){p.title=clean(input.title,200);p.content=clean(input.content,30000);p.visibility=clean(input.visibility,30)||'workspace';p.tags=arr(input.tags);if(!p.title||!p.content)throw new Error('vault_title_and_content_required');}
  else if(type==='inbox'){p.contactId=clean(input.contactId,120);p.channel=clean(input.channel,60)||'manual';p.sender=clean(input.sender,320);p.message=clean(input.message,12000);p.status=clean(input.status,30)||'open';if(!p.message)throw new Error('inbox_message_required');}
  else if(type==='automation'){p.name=clean(input.name,180);p.enabled=input.enabled!==false;p.trigger=obj(input.trigger);p.conditions=Array.isArray(input.conditions)?input.conditions.slice(0,30):[];p.actions=Array.isArray(input.actions)?input.actions.slice(0,50):[];if(!p.name)throw new Error('automation_name_required');}
  else {p.name=clean(input.name,180);p.source=clean(input.source,80)||'web';p.fields=Array.isArray(input.fields)?input.fields.slice(0,50):[];p.status='active';p.token=crypto.randomUUID();p.webhookToken=crypto.randomUUID();}
  await ref.set(p);
  const usagePatch = {};
  if (type === "leadCapture") usagePatch.leads = FieldValue.increment(1);
  if (type === "inbox") usagePatch.messages = FieldValue.increment(1);
  if (["faq","vault"].includes(type)) usagePatch.knowledgeItems = FieldValue.increment(1);
  await workspaceRef(workspaceId).set({usage:usagePatch,updatedAt:now},{merge:true});
  return sanitizeDoc(ref.id,(await ref.get()).data());
}


export async function runBusinessAutomation(uid, automationId, event = {}) {
  const ref = db().collection(BUSINESS_COLLECTIONS.automations).doc(clean(automationId, 160));
  const snap = await ref.get();
  if (!snap.exists) throw new Error("business_automation_not_found");
  const automation = snap.data();
  const workspaceId = clean(automation.workspaceId, 120);
  await requireBusinessAccess(uid, workspaceId, "manager");
  await requireBusinessPlan(uid, workspaceId, "automation");
  if (automation.enabled === false) return { ok: true, skipped: true, reason: "disabled" };
  const conditions = Array.isArray(automation.conditions) ? automation.conditions.slice(0, 30) : [];
  const matches = conditions.every(c => {
    const field = clean(c?.field, 120);
    const op = clean(c?.operator, 30) || "equals";
    const actual = event?.[field];
    const expected = c?.value;
    if (op === "exists") return actual !== undefined && actual !== null && actual !== "";
    if (op === "contains") return String(actual ?? "").toLowerCase().includes(String(expected ?? "").toLowerCase());
    if (op === "not_equals") return String(actual ?? "") !== String(expected ?? "");
    return String(actual ?? "") === String(expected ?? "");
  });
  if (!matches) return { ok: true, skipped: true, reason: "conditions_not_met" };
  const actions = Array.isArray(automation.actions) ? automation.actions.slice(0, 50) : [];
  const results = [];
  for (const action of actions) {
    const type = clean(action?.type, 40);
    if (type === "create_inbox") {
      results.push(await createBusinessExtended(uid, "inbox", {
        workspaceId, contactId: action.contactId || event.contactId || "", channel: action.channel || event.channel || "automation",
        sender: action.sender || event.sender || "", message: action.message || event.message || "Automation message", status: "open"
      }));
    } else if (type === "create_lead") {
      results.push(await createBusinessRecord(uid, "lead", {
        workspaceId, contactId: action.contactId || event.contactId || "", name: action.name || event.name || "",
        email: action.email || event.email || "", phone: action.phone || event.phone || "", source: action.source || "automation",
        notes: action.notes || "", status: action.status || "new"
      }));
    } else if (type === "update_record") {
      const typeName = clean(action.recordType, 40), id = clean(action.recordId || event.recordId, 160);
      if (["assistant","knowledge","contact","lead","pipeline","appointment"].includes(typeName) && id) {
        results.push(await updateBusinessRecord(uid, typeName, id, obj(action.patch)));
      }
    } else if (type === "log_activity") {
      results.push(await createBusinessRecord(uid, "activity", { workspaceId, entityType: action.entityType || event.entityType || "automation", entityId: action.entityId || event.entityId || "", action: action.action || automation.name, note: action.note || "Automation executed" }));
    }
  }
  await ref.set({ lastRunAt: FieldValue.serverTimestamp(), lastRunStatus: "success", lastRunEvent: event || {}, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return { ok: true, skipped: false, automationId, results };
}

export async function receiveBusinessAutomationWebhook(token, event = {}) {
  const snap = await db().collection(BUSINESS_COLLECTIONS.automations).where("webhookToken", "==", clean(token, 200)).limit(1).get();
  if (snap.empty) throw new Error("business_automation_webhook_not_found");
  const automation = snap.docs[0].data();
  if (automation.enabled === false) return { ok: true, skipped: true, reason: "disabled" };
  const uid = clean(automation.ownerUid, 160);
  return runBusinessAutomation(uid, snap.docs[0].id, event);
}

export async function updateBusinessExtended(uid,type,id,input){
  const map={faq:BUSINESS_COLLECTIONS.faqs,vault:BUSINESS_COLLECTIONS.vault,inbox:BUSINESS_COLLECTIONS.inbox,automation:BUSINESS_COLLECTIONS.automations,leadCapture:BUSINESS_COLLECTIONS.leadCaptures}; const collection=map[type]; if(!collection||!id)throw new Error('unsupported_business_extended_record');
  const ref=db().collection(collection).doc(id); const snap=await ref.get(); if(!snap.exists)throw new Error('business_record_not_found'); const workspaceId=clean(snap.data()?.workspaceId,120); await requireBusinessAccess(uid,workspaceId,'manager'); await requireBusinessPlan(uid,workspaceId);
  const p=obj(input); delete p.workspaceId;delete p.ownerUid;delete p.createdBy;delete p.createdAt;delete p.updatedAt;delete p.id;p.updatedAt=FieldValue.serverTimestamp(); await ref.set(p,{merge:true}); return sanitizeDoc(id,(await ref.get()).data());
}


export async function getBusinessTestSession(uid) {
  const snap = await db().collection("adminBusinessTestSessions").doc(uid).get();
  if (!snap.exists) return { enabled: false };
  const x = snap.data() || {};
  const expires = x.expiresAt?.toDate?.()?.getTime?.() || Number(x.expiresAt || 0);
  if (x.enabled !== true || (expires && expires <= Date.now())) return { enabled: false, planId: String(x.planId || ""), expired: true };
  return { enabled: true, planId: String(x.planId || ""), expiresAt: x.expiresAt || null, startedAt: x.startedAt || null };
}

export async function setBusinessTestSession(uid, planId, enabled = true, durationMinutes = 120) {
  const id = clean(planId, 100);
  const plan = businessCatalogPlans().find(p => p.id === id);
  if (!plan || plan.module !== "business") throw new Error("business_test_plan_not_found");
  const ref = db().collection("adminBusinessTestSessions").doc(uid);
  if (!enabled) {
    await ref.set({ enabled: false, planId: id, endedAt: FieldValue.serverTimestamp(), updatedBy: uid }, { merge: true });
    return { enabled: false, planId: id };
  }
  const minutes = Math.min(24 * 60, Math.max(5, Number(durationMinutes || 120)));
  await ref.set({
    enabled: true,
    planId: id,
    startedAt: FieldValue.serverTimestamp(),
    expiresAt: new Date(Date.now() + minutes * 60 * 1000),
    updatedBy: uid
  }, { merge: true });
  return getBusinessTestSession(uid);
}

export async function runBusinessPlanTestSuite(uid, planId) {
  const session = await getBusinessTestSession(uid);
  if (!session.enabled || session.planId !== String(planId || "")) throw new Error("business_test_session_required");
  const plan = businessCatalogPlans().find(p => p.id === session.planId);
  if (!plan) throw new Error("business_test_plan_not_found");
  const checks = [];
  const workspaceId = "admin-test-" + crypto.randomUUID();
  const now = FieldValue.serverTimestamp();
  const workspace = workspaceRef(workspaceId);
  await workspace.set({
    ownerUid: uid, name: "Admin Business Test — " + plan.name, slug: workspaceId,
    planId: plan.id, status: "active",
    settings: { timezone: "UTC", currency: "USD", assistantEnabled: true, automationEnabled: true, notificationsEnabled: true, webSearchEnabled: true },
    usage: { messages: 0, contacts: 0, leads: 0, knowledgeItems: 0, assistants: 0 },
    createdAt: now, updatedAt: now
  });
  await db().collection(BUSINESS_COLLECTIONS.members).doc(workspaceId + "_" + uid).set({ workspaceId, uid, role: "owner", status: "active", permissions: ["*"], createdAt: now, updatedAt: now });

  async function check(name, fn) {
    try { const value = await fn(); checks.push({ name, status: "passed", value: value == null ? null : value }); }
    catch (error) { checks.push({ name, status: "failed", error: error?.message || String(error) }); }
  }

  try {
    const context = await requireBusinessPlan(uid, workspaceId);
    checks.push({ name: "plan entitlement", status: "passed", planId: context.plan.id, source: context.source });
    await check("workspace read", async () => (await businessWorkspace(uid, { workspaceId })).workspaceId === workspaceId);
    if (planHasBusinessFeature(plan.id, "assistant")) await check("assistant CRUD", async () => (await createBusinessRecord(uid, "assistant", { workspaceId, name: "Admin Test Assistant", instructions: "Test only" })).id);
    if (planHasBusinessFeature(plan.id, "knowledge")) await check("knowledge CRUD", async () => (await createBusinessRecord(uid, "knowledge", { workspaceId, title: "Admin Test Knowledge", content: "Test only" })).id);
    if (planHasBusinessFeature(plan.id, "crm")) {
      await check("CRM contact", async () => (await createBusinessRecord(uid, "contact", { workspaceId, name: "Admin Test Contact", email: "admin-test@example.invalid" })).id);
      await check("CRM lead", async () => (await createBusinessRecord(uid, "lead", { workspaceId, name: "Admin Test Lead", source: "admin-test" })).id);
      await check("CRM pipeline", async () => (await createBusinessRecord(uid, "pipeline", { workspaceId, name: "Admin Test Pipeline" })).id);
      await check("CRM appointment", async () => (await createBusinessRecord(uid, "appointment", { workspaceId, title: "Admin Test Appointment", startAt: new Date().toISOString(), endAt: new Date(Date.now()+3600000).toISOString() })).id);
      await check("lead capture", async () => (await createBusinessExtended(uid, "leadCapture", { workspaceId, name: "Admin Test Form", source: "test", fields: ["name","email"] })).id);
    }
    if (planHasBusinessFeature(plan.id, "channels")) {
      await check("unified inbox", async () => (await createBusinessExtended(uid, "inbox", { workspaceId, sender: "admin-test", channel: "test", message: "Test inbox message" })).id);
    }
    if (planHasBusinessFeature(plan.id, "automation")) {
      const automation = await createBusinessExtended(uid, "automation", { workspaceId, name: "Admin Test Automation", enabled: true, conditions: [{ field: "source", operator: "equals", value: "admin-test" }], actions: [{ type: "create_lead", name: "Automation Test Lead", source: "admin-test" }] });
      await check("automation execution", async () => (await runBusinessAutomation(uid, automation.id, { source: "admin-test" })).ok === true);
    }
    const snapshot = await businessWorkspace(uid, { workspaceId });
    checks.push({ name: "usage and warnings", status: "passed", warnings: snapshot.usageWarnings || [], recommendedAddOns: snapshot.recommendedAddOns || [] });
  } finally {
    const collections = Object.values(BUSINESS_COLLECTIONS);
    for (const collection of collections) {
      const snap = await db().collection(collection).where("workspaceId", "==", workspaceId).limit(200).get();
      if (!snap.empty) {
        const batch = db().batch();
        snap.docs.forEach(d => batch.delete(d.ref));
        await batch.commit();
      }
    }
    await workspace.delete();
  }
  return { ok: checks.every(x => x.status === "passed"), planId: plan.id, planName: plan.name, checks };
}
