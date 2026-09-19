import { getFirestore, FieldValue } from "firebase-admin/firestore";

export const ADVANCED_ADMIN_OPTIONS = [
  ["ai_cost_optimizer","AI cost optimizer"],["provider_health_monitor","Provider health monitor"],
  ["model_version_management","Model version management"],["evaluation_lab","Evaluation laboratory"],
  ["prompt_config_versioning","Prompt/config versioning"],["capability_credit_pricing","Capability-level credit pricing"],
  ["search_controls_caching","Search controls and caching"],["device_approval_policies","Device approval policies"],
  ["activity_timeline","AI activity timeline"],["action_preview","Action preview"],
  ["human_approval","Human approval checkpoints"],["undoable_actions","Undoable AI actions"],
  ["agent_sandbox","Agent sandbox"],["plugin_marketplace","Plugin marketplace and permissions"],
  ["background_jobs","Background jobs and queues"],["scheduled_tasks","Scheduled AI tasks"],
  ["monitoring_agents","Monitoring agents"],["usage_forecasting","Usage forecasting"],
  ["quality_fallback","Automatic quality fallback"],["emergency_kill_switches","Emergency kill switches"],
  ["incident_replay","AI incident replay"],["security_monitoring","Security monitoring"],
  ["risk_detection","AI abuse/risk detection"],["config_snapshots","Configuration snapshots and rollback"],
  ["regional_pricing","Regional pricing/promotions"],["subscription_lifecycle","Subscription lifecycle controls"],
  ["business_ai_employees","Business AI employees"],["agent_to_agent_workflows","Agent-to-agent workflows"],
  ["cross_device_sync","Cross-device conversation/project sync"],["secure_vault","Secure temporary file vault"],
  ["resumable_uploads","Large-file resumable uploads"],["multilingual_voice","Multilingual UI and voice"],
  ["accessibility","Accessibility-first AI controls"],["passkeys_sessions","Passkeys/session/device management"],
  ["data_export_deletion","Data export and account deletion"],["provider_route_override","Provider/model route override"],
  ["feature_rollouts","Feature rollout percentages"],["ai_incident_controls","Incident controls and replay"],
  ["capability_marketplace","Admin-controlled capability marketplace"],["data_retention_controls","Data retention and deletion policy controls"]
];

function defaults(id, label) {
  return {
    id, label, enabled: true, rolloutPercent: 100, killSwitch: false,
    requiresApproval: false, creditCost: 0, dailyLimit: null, concurrencyLimit: null,
    providerCapabilityId: id, providerId: null, modelId: null,
    allowedPlanIds: [], version: 1, schedule: null,
    alertThreshold: null, updatedAt: null
  };
}

export async function listAdvancedAdminOptions() {
  const db = getFirestore();
  const snapshot = await db.collection("adminAdvancedOptions").get();
  const stored = new Map(snapshot.docs.map(d => [d.id, d.data()]));
  return ADVANCED_ADMIN_OPTIONS.map(([id,label]) => ({
    ...defaults(id,label), ...(stored.get(id) || {}), id, label
  }));
}

export async function updateAdvancedAdminOption(input, adminUid) {
  const id = String(input.id || "");
  const found = ADVANCED_ADMIN_OPTIONS.find(x => x[0] === id);
  if (!found) throw new Error("Unknown advanced admin option: " + id);
  const option = {
    ...defaults(id, found[1]),
    enabled: input.enabled !== false,
    rolloutPercent: Math.min(100, Math.max(0, Number(input.rolloutPercent ?? 100))),
    killSwitch: Boolean(input.killSwitch),
    requiresApproval: Boolean(input.requiresApproval),
    creditCost: Math.max(0, Number(input.creditCost ?? 0)),
    dailyLimit: input.dailyLimit == null ? null : Math.max(0, Number(input.dailyLimit)),
    concurrencyLimit: input.concurrencyLimit == null ? null : Math.max(0, Number(input.concurrencyLimit)),
    providerCapabilityId: input.providerCapabilityId ? String(input.providerCapabilityId) : id,
    providerId: input.providerId ? String(input.providerId) : null,
    modelId: input.modelId ? String(input.modelId) : null,
    allowedPlanIds: Array.isArray(input.allowedPlanIds) ? input.allowedPlanIds.map(String) : [],
    version: Math.max(1, Number(input.version ?? 1)),
    schedule: input.schedule == null ? null : String(input.schedule),
    alertThreshold: input.alertThreshold == null ? null : Math.max(0, Number(input.alertThreshold)),
    updatedAt: FieldValue.serverTimestamp(),
    updatedBy: adminUid
  };
  await getFirestore().collection("adminAdvancedOptions").doc(id).set(option, { merge: true });
  return { ...option, id, label: found[1] };
}
