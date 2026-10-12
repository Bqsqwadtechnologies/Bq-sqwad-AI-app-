export const ADMIN_ONLY_AI_CAPABILITIES = new Set([
  "ai_agent", "admin_experimentation", "feature_rollout_percentages", "emergency_kill_switches",
  "provider_health_checks", "ai_incident_replay", "model_version_rollback", "admin_capability_marketplace",
  "security_event_monitoring", "ai_abuse_risk_detection", "rate_limiting", "abuse_protection",
  "incident_system", "ai_evaluation_center", "ai_evaluation_laboratory", "provider_cost_optimizer",
  "automatic_quality_fallback", "credit_reservation", "credit_reversal", "secure_file_pipeline",
  "offline_retry_queue", "model_routing", "provider_failover", "ai_orchestration"
]);

export function assertPublicCapabilityAllowed(name) {
  const normalizedName = String(name || "").trim().toLowerCase().replace(/[\s-]+/g, "_");
  if (ADMIN_ONLY_AI_CAPABILITIES.has(normalizedName)) {
    throw Object.assign(new Error("capability_requires_admin_console"), { httpStatus: 403 });
  }
}
