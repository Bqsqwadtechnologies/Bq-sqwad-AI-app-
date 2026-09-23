import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { runAi, callOpenAIVision, getRoute } from "./provider-gateway.js";
import { requireBusinessAccess, requireBusinessPlan } from "./business-phases.js";

const db = () => getFirestore();
const clean = (v, n = 1000) => String(v ?? "").trim().slice(0, n);
const obj = v => v && typeof v === "object" && !Array.isArray(v) ? v : {};

export async function advancedAiTask(uid, input = {}) {
  const workspaceId = clean(input.workspaceId, 120);
  if (workspaceId) { await requireBusinessAccess(uid, workspaceId); await requireBusinessPlan(uid, workspaceId, "advanced"); }
  const capability = clean(input.capability, 60) || "chat";
  const message = clean(input.message, 12000);
  if (!message) throw new Error("advanced_ai_message_required");
  const route = await getRoute(capability);
  if (route.enabled === false) throw new Error("advanced_ai_capability_disabled");
  let result;
  if (input.imageBase64) {
    result = await callOpenAIVision({
      model: route.primaryModelId,
      message,
      imageBase64: clean(input.imageBase64, 8_000_000),
      mimeType: clean(input.mimeType, 120) || "image/jpeg",
      system: clean(input.system, 8000)
    });
  } else {
    result = await runAi({ capability, message, system: clean(input.system, 8000) });
  }
  const ref = db().collection("businessAdvancedAiJobs").doc();
  await ref.set({
    uid, workspaceId: workspaceId || null, capability, status: "completed",
    inputType: input.imageBase64 ? "image" : (clean(input.fileType, 80) || "text"),
    providerId: result.providerId || route.primaryProviderId,
    modelId: result.modelId || route.primaryModelId,
    resultText: result.text || "",
    createdAt: FieldValue.serverTimestamp()
  });
  return { ok: true, jobId: ref.id, text: result.text || "", providerId: result.providerId || null, modelId: result.modelId || null };
}

export async function listAdvancedAiJobs(uid, workspaceId) {
  if (workspaceId) await requireBusinessAccess(uid, workspaceId);
  const snap = await db().collection("businessAdvancedAiJobs").where("uid", "==", uid).limit(50).get();
  return snap.docs.map(d => ({ id: d.id, ...d.data(), createdAt: d.data().createdAt?.toDate?.()?.toISOString?.() || null }));
}

const INTEGRATIONS = Object.freeze([
  "google_workspace","microsoft_365","google_calendar","microsoft_calendar",
  "shopify","woocommerce","wordpress","zapier","crm_api","rest_api",
  "webhooks","email","sms"
]);

const NO_CREDENTIAL_INTEGRATIONS = new Set(["webhooks", "rest_api"]);
export function supportedBusinessIntegrations() {
  return INTEGRATIONS.map(id => ({
    id,
    status: NO_CREDENTIAL_INTEGRATIONS.has(id) ? "available_without_provider_keys" : "admin_configurable",
    requiresProviderCredentials: !NO_CREDENTIAL_INTEGRATIONS.has(id),
    description: {
      webhooks: "Connect external services to Business AI automations through a signed workspace webhook endpoint.",
      rest_api: "Use the authenticated Business AI API from your own server or automation without storing a provider API key in the app."
    }[id] || "Provider credentials are managed server-side by an administrator."
  }));
}

export async function getBusinessIntegrationConfig(uid, workspaceId) {
  await requireBusinessAccess(uid, workspaceId);
  await requireBusinessPlan(uid, workspaceId, "channels");
  const snap = await db().collection("businessIntegrationConfigs").where("workspaceId", "==", workspaceId).limit(100).get();
  return { integrations: supportedBusinessIntegrations(), configured: snap.docs.map(d => ({ id:d.id, integration:d.data().integration, enabled:d.data().enabled === true, settings:d.data().publicSettings || {} })) };
}

export async function configureBusinessIntegration(uid, input = {}) {
  const workspaceId = clean(input.workspaceId, 120);
  await requireBusinessAccess(uid, workspaceId, "admin");
  await requireBusinessPlan(uid, workspaceId, "channels");
  const integration = clean(input.integration, 80);
  if (!INTEGRATIONS.includes(integration)) throw new Error("unsupported_business_integration");
  const ref = db().collection("businessIntegrationConfigs").doc(workspaceId + "_" + integration);
  await ref.set({
    workspaceId, integration, enabled: input.enabled === true,
    publicSettings: obj(input.publicSettings),
    configuredBy: uid, updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });
  return { ok: true, integration, enabled: input.enabled === true };
}

export async function getEnterpriseConfig(uid, workspaceId) {
  await requireBusinessAccess(uid, workspaceId);
  const plan = await requireBusinessPlan(uid, workspaceId);
  if (!["bizenterprise","businessenterprise"].includes(plan.plan.id)) throw new Error("business_enterprise_plan_required");
  const ref = db().collection("businessEnterpriseConfigs").doc(workspaceId);
  const snap = await ref.get();
  return { workspaceId, config: snap.exists ? snap.data() : {
    sso: { enabled:false, provider:"", domain:"" },
    auditLogs: { enabled:true, retentionDays:90 },
    departments: [],
    whiteLabel: { enabled:false, brandName:"", logoUrl:"", customDomain:"" },
    sla: { tier:"standard", targetPercent:99.9 },
    security: { ipAllowlist: [], sessionMinutes:480, requireMfa:false }
  }};
}

export async function updateEnterpriseConfig(uid, workspaceId, patch = {}) {
  await requireBusinessAccess(uid, workspaceId, "owner");
  const plan = await requireBusinessPlan(uid, workspaceId);
  if (!["bizenterprise","businessenterprise"].includes(plan.plan.id)) throw new Error("business_enterprise_plan_required");
  const ref = db().collection("businessEnterpriseConfigs").doc(workspaceId);
  const current = await getEnterpriseConfig(uid, workspaceId);
  const next = { ...current.config, ...obj(patch), updatedBy: uid, updatedAt: FieldValue.serverTimestamp() };
  await ref.set(next, { merge: true });
  await db().collection("businessAuditLogs").add({
    workspaceId, uid, action:"enterprise_config_updated", changes: Object.keys(obj(patch)).slice(0,50), createdAt: FieldValue.serverTimestamp()
  });
  return getEnterpriseConfig(uid, workspaceId);
}
