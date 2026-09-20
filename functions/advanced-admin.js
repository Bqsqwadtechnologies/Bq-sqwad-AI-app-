import { getFirestore, FieldValue } from "firebase-admin/firestore";

export const ADMIN_CONTROL_SECTIONS = [
  ["ai_models", "AI & Models"],
  ["providers", "Providers"],
  ["routing", "Routing"],
  ["capabilities", "Capabilities"],
  ["credits", "Credits"],
  ["pricing", "Pricing"],
  ["plans", "Plans"],
  ["payments", "Payments"],
  ["subscriptions", "Subscriptions"],
  ["business", "Business"],
  ["agents", "Agents"],
  ["device", "Device Intelligence"],
  ["privacy", "Privacy"],
  ["security", "Security"],
  ["users", "Users"],
  ["notifications", "Notifications"],
  ["referrals", "Referrals"],
  ["codron", "Codron"],
  ["plugins", "Plugins"],
  ["experiments", "Experiments"],
  ["rollouts", "Rollouts"],
  ["monitoring", "Monitoring"],
  ["incidents", "Incidents"],
  ["audit", "Audit"],
  ["data", "Data"],
  ["system", "System"],
  ["emergency", "Emergency Controls"]
];

export const CORE_CAPABILITY_OPTIONS = [
  ["capability_chat","Main Chat capability","capabilities","openai","gpt-5.6-luna",["gemini"]],
  ["capability_device_commands","Device commands capability","device","gemini","gemini-3.5-flash",[]],
  ["capability_web_search","Web Search capability","capabilities","tavily",null,[]],
  ["capability_deep_research","Deep Research capability","capabilities","tavily",null,[]],
  ["capability_live_camera","AI Live Camera capability","capabilities","openai",null,["gemini"]],
  ["capability_live_voice","AI Live Voice capability","capabilities","openai",null,["gemini"]],
  ["capability_image_generation","Image generation capability","capabilities","openai",null,["gemini"]],
  ["capability_video_generation","Video generation capability","capabilities","openai",null,["gemini"]],
  ["capability_file_analysis","File analysis capability","capabilities","openai",null,["gemini"]],
  ["capability_organization_studio","Organization Studio AI capability","capabilities","openai",null,["gemini"]]
];

export const ADVANCED_ADMIN_OPTIONS = [
  ["provider_cost_optimizer","Provider cost optimizer","providers"],
  ["provider_health_monitor","Provider health monitor","monitoring"],
  ["model_version_management","Model version management","ai_models"],
  ["evaluation_lab","AI evaluation laboratory","experiments"],
  ["prompt_config_versioning","Prompt/config versioning","ai_models"],
  ["capability_credit_pricing","Capability-level credit pricing","pricing"],
  ["search_domain_controls","Search domain controls and caching","providers"],
  ["device_approval_policies","Device approval and per-device capabilities","device"],
  ["ai_activity_timeline","AI activity timeline","audit"],
  ["action_preview","Action preview","security"],
  ["human_approval","Human approval checkpoints","security"],
  ["undoable_ai_actions","Undoable AI actions","security"],
  ["agent_sandbox","Agent sandbox","agents"],
  ["plugin_marketplace","Plugin marketplace and permissions","plugins"],
  ["background_jobs","Background jobs and queues","system"],
  ["scheduled_tasks","Scheduled AI tasks","system"],
  ["monitoring_agents","Monitoring agents","monitoring"],
  ["usage_forecasting","Usage forecasting","monitoring"],
  ["automatic_quality_fallback","Automatic quality fallback","routing"],
  ["emergency_kill_switches","Emergency kill switches","emergency"],
  ["incident_replay","AI incident replay","incidents"],
  ["security_risk_monitoring","Security monitoring and AI risk detection","security"],
  ["config_snapshots_rollback","Configuration snapshots and rollback","system"],
  ["regional_pricing_promos","Regional pricing, promotions, trials and coupons","pricing"],
  ["subscription_lifecycle","Subscription lifecycle management","subscriptions"],
  ["business_ai_employees","Business AI employee system","business"],
  ["agent_to_agent_workflows","Agent-to-agent workflows","agents"],
  ["cross_device_sync","Cross-device conversation and project sync","data"],
  ["secure_sensitive_vault","Secure temporary sensitive-data vault","privacy"],
  ["resumable_uploads","Large-file resumable uploads","system"],
  ["multilingual_ui_voice","Multilingual UI and voice","capabilities"],
  ["accessibility_controls","Accessibility-first AI controls","capabilities"],
  ["passkeys_sessions_devices","Passkeys, sessions and device management","security"],
  ["data_export_deletion","Data export and account deletion","data"],
  ["provider_routing","Provider routing","routing"],
  ["model_routing","Model routing","routing"],
  ["provider_secret_management","Provider secret management","providers"],
  ["web_search_pricing","Web Search pricing","pricing"],
  ["feature_rollouts","Feature rollout percentages","rollouts"],
  ["admin_testing_mode","Admin testing mode","experiments"]
];

const ALL_OPTIONS = [
  ...CORE_CAPABILITY_OPTIONS.map(([id,label,section]) => [id,label,section]),
  ...ADVANCED_ADMIN_OPTIONS
];

const BASE_DEFAULTS = {
  enabled: true,
  rolloutPercent: 100,
  killSwitch: false,
  requiresApproval: false,
  creditCost: 0,
  dailyLimit: null,
  concurrencyLimit: null,
  providerCapabilityId: null,
  providerId: null,
  modelId: null,
  allowedPlanIds: [],
  version: 1,
  schedule: null,
  alertThreshold: null
};

const CONFIG_DEFAULTS = {
  responseMaxTokens: null,
  contextWindowLimit: null,
  maxToolCalls: 10,
  maxExecutionSeconds: 120,
  temperature: null,
  confidenceRequired: false,
  sourceVerificationRequired: false,
  systemPromptVersion: null,
  rateLimitPerMinute: null,
  rateLimitPerDay: null,
  maintenanceMode: false,
  backgroundAllowed: false,
  confirmationRequired: false,
  permissionRequired: false,
  dataRetentionDays: null,
  fileRetentionHours: null,
  domainAllowList: [],
  domainDenyList: [],
  fallbackEnabled: true,
  fallbackProviderIds: [],
  latencyBudgetMs: null,
  errorThresholdPercent: null,
  queueLimit: null,
  maxFileSizeMb: null,
  maxUploadConcurrency: null,
  notificationEnabled: true,
  auditRequired: true,
  planOverrides: {},
  creditsPerSearch: 1,
  creditsPerDeepResearch: 5,
  creditsPerResultPage: 0,
  maxResults: 10,
  dailyUserLimit: null
};

function defaults(id, label, section, providerId = null, modelId = null, fallbackProviderIds = []) {
  return {
    id,
    label,
    section,
    ...BASE_DEFAULTS,
    providerCapabilityId: id,
    providerId,
    modelId,
    config: { ...CONFIG_DEFAULTS, fallbackProviderIds },
    updatedAt: null,
    updatedBy: null
  };
}

function normalize(input, existing, adminUid, found) {
  const core = CORE_CAPABILITY_OPTIONS.find(x => x[0] === found[0]);
  const base = defaults(found[0], found[1], found[2], core?.[3] || null, core?.[4] || null, core?.[5] || []);
  const current = existing || {};
  const incomingConfig = input.config && typeof input.config === "object" ? input.config : {};
  const config = { ...CONFIG_DEFAULTS, ...(current.config || {}), ...incomingConfig };
  return {
    ...base,
    ...current,
    ...input,
    id: found[0],
    label: found[1],
    section: found[2],
    enabled: input.enabled == null ? current.enabled !== false : Boolean(input.enabled),
    rolloutPercent: Math.min(100, Math.max(0, Number(input.rolloutPercent ?? current.rolloutPercent ?? 100))),
    killSwitch: input.killSwitch == null ? Boolean(current.killSwitch) : Boolean(input.killSwitch),
    requiresApproval: input.requiresApproval == null ? Boolean(current.requiresApproval) : Boolean(input.requiresApproval),
    creditCost: Math.max(0, Number(input.creditCost ?? current.creditCost ?? 0)),
    dailyLimit: input.dailyLimit == null ? (current.dailyLimit ?? null) : Math.max(0, Number(input.dailyLimit)),
    concurrencyLimit: input.concurrencyLimit == null ? (current.concurrencyLimit ?? null) : Math.max(0, Number(input.concurrencyLimit)),
    providerCapabilityId: input.providerCapabilityId ? String(input.providerCapabilityId) : (current.providerCapabilityId || found[0]),
    providerId: input.providerId ? String(input.providerId) : (current.providerId || null),
    modelId: input.modelId ? String(input.modelId) : (current.modelId || null),
    allowedPlanIds: Array.isArray(input.allowedPlanIds) ? input.allowedPlanIds.map(String) : (Array.isArray(current.allowedPlanIds) ? current.allowedPlanIds : []),
    version: Math.max(1, Number(input.version ?? current.version ?? 1)),
    schedule: input.schedule == null ? (current.schedule ?? null) : String(input.schedule),
    alertThreshold: input.alertThreshold == null ? (current.alertThreshold ?? null) : Math.max(0, Number(input.alertThreshold)),
    config,
    updatedAt: FieldValue.serverTimestamp(),
    updatedBy: adminUid
  };
}

export async function listAdvancedAdminOptions() {
  const db = getFirestore();
  const snapshot = await db.collection("adminAdvancedOptions").get();
  const stored = new Map(snapshot.docs.map(d => [d.id, d.data()]));
  return ALL_OPTIONS.map(([id, label, section]) => {
    const core = CORE_CAPABILITY_OPTIONS.find(x => x[0] === id);
    return {
    ...defaults(id, label, section, core?.[3] || null, core?.[4] || null, core?.[5] || []),
    ...(stored.get(id) || {}),
    id,
    label,
    section,
    config: { ...CONFIG_DEFAULTS, ...((stored.get(id) || {}).config || {}) }
  };
  });
}

export async function updateAdvancedAdminOption(input, adminUid) {
  const id = String(input.id || "");
  const found = ALL_OPTIONS.find(x => x[0] === id);
  if (!found) throw new Error("Unknown advanced admin option: " + id);

  const db = getFirestore();
  const ref = db.collection("adminAdvancedOptions").doc(id);
  const snap = await ref.get();
  const option = normalize(input, snap.exists ? snap.data() : null, adminUid, found);
  await ref.set(option, { merge: true });
  return option;
}

export async function getAdminControlConfig() {
  return {
    sections: ADMIN_CONTROL_SECTIONS.map(([id, label]) => ({ id, label })),
    options: await listAdvancedAdminOptions()
  };
}
