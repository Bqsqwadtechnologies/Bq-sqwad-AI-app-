const DEFAULT_REALTIME_MODEL = "gpt-realtime";

import { configureFirebaseEnv, verifyFirebaseUser, firestoreGet, firestoreSet, firestoreCreate, firestoreCommit, firestoreDelete, firestoreQuery, deleteFirebaseAuthUser } from "./firebase.js";
import { assertPublicCapabilityAllowed } from "./capability-policy.js";
import { normalizeHistoryMessages } from "./history-policy.js";
import { dailyCreditDateKey, currentDailyFreeCredits, shouldRestoreDailyFreeCredits, dailyFreeCreditGrant, DEFAULT_FREE_CREDIT_TIME_ZONE } from "./credit-cycle.js";
import { playCatalog, verifyPlayPurchase } from "./google-play.js";
import { COMMERCIAL_CATALOG, canonicalPlayMappings, mergeCommercialCatalog } from "./plan-catalog-native.js";
import { runProvider, runProviderStream, callOpenAI, callTavily, providerSecretStatus, DEFAULT_MODELS } from "./providers.js";
import { getOrganizationCatalog, getOrganizationAccess, setOrganizationPlanStatus, setOrganizationEntitlement, getOrganizationTestSession, setOrganizationTestSession, getAdminOrganizationControl } from "./organization-plans-native.js";
import { listOrganizationScheduledTasks, createOrganizationScheduledTask, updateOrganizationScheduledTask, runOrganizationScheduledTask } from "./scheduler-native.js";
import { getProduction, updateProductionConfig, upsertSource, removeSource, upsertScene, upsertDestination, setProductionState, updateHealth, addProductionEvent, getOrganizationAiLiveConfig, updateOrganizationAiLiveConfig } from "./production-native.js";
import { businessWorkspace, createBusinessRecord, updateBusinessRecord, deleteBusinessRecord, addBusinessMember, requireBusinessAccess, requireBusinessPlan, listBusinessExtended, createBusinessExtended, updateBusinessExtended, businessKnowledgeForWorkspace, runBusinessAutomation, receiveBusinessAutomationWebhook, updateBusinessSettings, getBusinessTestSession, setBusinessTestSession, runBusinessPlanTestSuite, listAdminBusinessSubscriptions, setAdminBusinessSubscriptionStatus } from "./business-native.js";
import { companionCatalog, companionProfile, saveCompanionProfile, companionHistory, companionChat, companionCheckins, companionCheckin, companionSettings, saveCompanionSettings, learningCatalog, learningProgress, learningSession, truthguardCatalog, truthguardSettings, saveTruthguardSettings, truthguardHistory, truthguardCheck, organizationGet, organizationUpdate, organizationSettings, organizationChannel, organizationKnowledge, organizationMember, organizationMemberState, organizationChannelState, organizationAssistant, workspaceScopeRedirect, workspaceScopeSystemPrompt } from "./legacy-native.js";
import { generateImageNative, editImageNative, createVideoNative, getVideoNative, getVideoContentNative } from "./generation-native.js";
import { listAiModels, saveAiModel, resolvePublishedModel, ensureCurrentModelRegistry, validateAiModel, checkAiModelStatus } from "./model-registry-native.js";
import { normalizeProviderUsage, calculateTokenCost, resolveTokenPricing, estimateTokenReservation, recordTokenUsage, getTokenUsageReport } from "./token-billing-native.js";
import { securityStatusNative, configurePasswordNative, beginTotpNative, confirmTotpNative, unlockAdminNative, biometricStatusNative, registerBiometricNative, removeBiometricNative, beginBiometricNative, verifyBiometricNative, adminSessionStatusNative, requireAdminSessionNative } from "./admin-security-native.js";
import { broadcast } from "./notifications-native.js";
import { advancedAiNative, advancedJobs, supportedIntegrations, integrationConfig, configureIntegration, enterpriseConfig, enterpriseUpdate } from "./business-advanced-native.js";
import { passkeyStatus, registrationOptionsNative, verifyRegistrationNative, authenticationOptionsNative, verifyAuthenticationNative, removePasskeyNative } from "./passkeys-native.js";
import { orgWallet, orgTopUps, orgLiveStart, orgChargeAssistant, orgLiveGet, orgLiveHeartbeat, orgLiveStop, orgBillingConfig } from "./organization-billing-native.js";
import { youtubeChannel, youtubeCreateLive, youtubeTransition, youtubeStatus, youtubeChat, youtubeReply } from "./youtube-native.js";
import { runtimeCreate, runtimeList, runtimeUpdate, runtimeAudit } from "./runtime-native.js";
import { createTrainingAssetUploadSession, inspectTrainingAssetObject, deleteTrainingAssetObject, createTrainingAssetDownloadAuthorization, downloadTrainingAssetObject, uploadTrainingAssetObject } from "./training-assets.js";
import { socialStart, socialCallback, listSocialAccounts, manageXAction, tiktokProfile, tiktokVideos, publishTikTok } from "./social.js";

const rateBuckets = new Map();

async function requestKeyHash(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(value || "")));
  return Array.from(new Uint8Array(digest)).map(x => x.toString(16).padStart(2, "0")).join("");
}

function enforceRateLimit(uid, limitOverride = null) {
  const limit = Math.max(1, Number(limitOverride || globalThis.__bqEnv?.RATE_LIMIT_PER_MINUTE || 60));
  const now = Date.now();
  const bucket = rateBuckets.get(uid);
  if (!bucket || now - bucket.startedAt >= 60000) {
    rateBuckets.set(uid, { startedAt: now, count: 1 });
    return;
  }
  if (bucket.count >= limit) {
    const retryAfter = Math.max(1, Math.ceil((60000 - (now - bucket.startedAt)) / 1000));
    const error = new Error("rate_limited");
    error.retryAfter = retryAfter;
    throw error;
  }
  bucket.count += 1;
}

async function loadIdempotentResponse(request, user, path) {
  const key = request.headers.get("Idempotency-Key")?.trim();
  if (!key || key.length > 200) return null;
  const hash = await requestKeyHash(user.uid + ":" + path + ":" + key);
  const record = await firestoreGet("idempotency/" + hash);
  if (!record || record.uid !== user.uid) return { hash, key, fresh: true };
  if (record.status === "completed" && record.body) return { hash, replay: json(record.body, Number(record.httpStatus || 200)) };
  if (record.status === "pending") throw Object.assign(new Error("idempotency_in_progress"), { httpStatus: 409 });
  return { hash, key, fresh: true };
}


function hasWorkerFirebaseConfig(env) {
  return Boolean(env?.BQ_FIREBASE_SERVICE_ACCOUNT_JSON);
}

function isSuperAdmin(user, env) {
  const claims = user?.customAttributes || {};
  const email = String(user?.email || "").toLowerCase();
  const owner = String(env?.SUPER_ADMIN_EMAIL || "").toLowerCase();
  // General admin authorization supports delegated administrators.
  return Boolean(owner) && (
    (email === owner && user?.emailVerified === true) ||
    claims.admin === true ||
    claims.superAdmin === true ||
    claims.role === "admin" ||
    claims.role === "super_admin"
  );
}

export function assertSuperAdminIdentity(user, env) {
  if (!isSuperAdmin(user, env)) {
    throw Object.assign(new Error("Admin access required"), { httpStatus: 403, safeError: "admin_access_required" });
  }
  return user;
}

export function isAdminUnlockRequired(policy) {
  return policy?.unlockRequired === true;
}

export function isRootSuperAdmin(user, env) {
  const claims = user?.customAttributes || {};
  const email = String(user?.email || "").toLowerCase();
  const owner = String(env?.SUPER_ADMIN_EMAIL || "").toLowerCase();
  // Test-mode entitlement bypass is restricted to the verified configured owner
  // or an explicitly designated super-admin claim; delegated admins cannot enable it.
  return Boolean(
    (owner && email === owner && user?.emailVerified === true) ||
    claims.superAdmin === true ||
    claims.role === "super_admin"
  );
}

function diagnosticFeatureForRoute(route) {
  const path = String(route || "/").split("?")[0];
  if (path === "/ai" || path === "/ai/stream") return "chat";
  if (path === "/ai/image") return "image";
  if (path === "/ai/vision") return "vision";
  if (path === "/live-camera") return "live_camera";
  if (path === "/screen-share") return "screen_share";
  if (path === "/live-voice" || path === "/realtime/session" || path === "/realtime/heartbeat") return "voice_session";
  if (path === "/native/catalog" || path === "/play/catalog" || path === "/plans") return "catalog";
  return "other";
}

function safeDiagnosticCode(errorCode) {
  const value = String(errorCode || "").toLowerCase();
  if (value.includes("flashlight_unavailable")) return "flashlight_unavailable";
  if (value.includes("camera_permission_missing")) return "camera_permission_missing";
  if (value.includes("camera_busy")) return "camera_busy";
  if (value.includes("camera_unavailable")) return "camera_unavailable";
  if (value.includes("microphone_permission_missing")) return "microphone_permission_missing";
  if (value.includes("screen_capture_permission_missing")) return "screen_capture_permission_missing";
  if (value.includes("live_camera_timeout")) return "live_camera_timeout";
  if (value.includes("live_camera_runtime_error")) return "live_camera_runtime_error";
  if (value.includes("screen_share_runtime_error")) return "screen_share_runtime_error";
  if (value.includes("live_voice_runtime_error")) return "live_voice_runtime_error";
  if (value.includes("realtime_session_create_failed")) return "realtime_session_create_failed";
  if (value.includes("realtime_fallback_rejected")) return "realtime_fallback_rejected";
  if (value.includes("realtime_session_failed")) return "realtime_session_failed";
  if (value.includes("live_session_timeout")) return "live_session_timeout";
  if (value.includes("short_live_session")) return "short_live_session";
  if (value.includes("insufficient_credits") || value.includes("credit_balance")) return "insufficient_credits";
  if (value.includes("provider_not_configured")) return "provider_not_configured";
  if (value.includes("openai http")) return "openai_provider_http_error";
  if (value.includes("gemini") || value.includes("google ai")) return "gemini_provider_error";
  if (value.includes("tavily")) return "search_provider_error";
  if (value.includes("firestore") || value.includes("firebase")) return "data_store_error";
  if (value.includes("not_configured") || value.includes("not configured")) return "provider_not_configured";
  if (value.includes("timeout") || value.includes("timed out")) return "request_timeout";
  if (value.includes("rate_limited")) return "rate_limited";
  if (value.includes("capability_disabled") || value.includes("disabled")) return "capability_disabled";
  if (value.includes("route_not_migrated")) return "route_not_migrated";
  return "server_request_failed";
}

async function adminDiagnosticSnapshot() {
  const features = ["chat", "image", "vision", "live_camera", "screen_share", "voice_session", "catalog"];
  const records = await Promise.all(features.map(async feature => {
    const rows = await firestoreQuery("adminDiagnostics", {
      filters: [{ field: { fieldPath: "feature" }, op: "EQUAL", value: { stringValue: feature } }],
      orderBy: { field: "createdAt", direction: "DESCENDING" },
      limit: 50
    });
    rows.sort((a, b) => String(b.createdAt || "").localeCompare(String(a.createdAt || "")));
    return [feature, rows.slice(0, 50).map(x => ({
      id: String(x.id || String(x.__name || "").split("/").pop()),
      feature,
      route: String(x.route || "/").slice(0, 120),
      status: Number(x.status || 500),
      code: String(x.code || "server_request_failed").slice(0, 80),
      providerMessage: sanitizeProviderMessage(x.providerMessage || ""),
      timelineStatus: String(x.timelineStatus || "").slice(0, 24),
      createdAt: x.createdAt || null
    }))];
  }));
  return Object.fromEntries(records);
}

async function adminReadinessSelfTest(env, adminUser) {
  const providerProbeCache = new Map();
  const probeProvider = (provider) => {
    if (providerProbeCache.has(provider)) return providerProbeCache.get(provider);
    const probe = (async () => {
      const key = provider === "openai" ? env?.OPENAI_API_KEY : provider === "gemini" ? env?.GEMINI_API_KEY : null;
      if (!key) return { status: "missing_key", reachable: false };
      try {
        const url = provider === "openai"
          ? "https://api.openai.com/v1/models"
          : "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1";
        const headers = provider === "openai"
          ? { Authorization: "Bearer " + String(key) }
          : { "x-goog-api-key": String(key) };
        const response = await fetch(url, { method: "GET", headers, signal: AbortSignal.timeout(5000) });
        return {
          status: response.ok ? "reachable" : (response.status === 401 || response.status === 403 ? "authentication_failed" : "provider_error"),
          reachable: response.ok
        };
      } catch (error) {
        return { status: error?.name === "TimeoutError" ? "timeout" : "network_error", reachable: false };
      }
    })();
    providerProbeCache.set(provider, probe);
    return probe;
  };
  const probeOpenAiModel = async (model) => {
    if (!env?.OPENAI_API_KEY) return { status: "missing_key", available: false };
    if (!/^[a-zA-Z0-9._:-]{1,80}$/.test(model)) return { status: "invalid_model_id", available: false };
    try {
      const response = await fetch("https://api.openai.com/v1/models/" + encodeURIComponent(model), {
        method: "GET",
        headers: { Authorization: "Bearer " + String(env.OPENAI_API_KEY) },
        signal: AbortSignal.timeout(5000)
      });
      if (response.ok) return { status: "model_available", available: true };
      if (response.status === 401 || response.status === 403) return { status: "model_access_denied", available: false };
      if (response.status === 404) return { status: "model_unavailable", available: false };
      return { status: "model_check_failed", available: false };
    } catch (error) {
      return { status: error?.name === "TimeoutError" ? "model_check_timeout" : "model_check_network_error", available: false };
    }
  };
  const probeConfiguredModel = async (provider, model) => {
    if (provider === "openai") return probeOpenAiModel(model);
    if (provider !== "gemini") return { status: "unsupported_provider", available: false };
    if (!env?.GEMINI_API_KEY) return { status: "missing_key", available: false };
    const cleanModel = String(model || "").replace(/^models\//, "");
    if (!/^[a-zA-Z0-9._:-]{1,80}$/.test(cleanModel)) return { status: "invalid_model_id", available: false };
    try {
      const response = await fetch("https://generativelanguage.googleapis.com/v1beta/models/" + encodeURIComponent(cleanModel), {
        method: "GET",
        headers: { "x-goog-api-key": String(env.GEMINI_API_KEY) },
        signal: AbortSignal.timeout(5000)
      });
      if (response.ok) return { status: "model_available", available: true };
      if (response.status === 401 || response.status === 403) return { status: "model_access_denied", available: false };
      if (response.status === 404) return { status: "model_unavailable", available: false };
      return { status: "model_check_failed", available: false };
    } catch (error) {
      return { status: error?.name === "TimeoutError" ? "model_check_timeout" : "model_check_network_error", available: false };
    }
  };
  const probeRealtimeSessionCreation = async (uid) => {
    try {
      const config = await firestoreGet("adminAdvancedOptions/live_voice_pricing");
      if (config?.enabled === false || config?.killSwitch === true) return { status: "disabled", model: String(config?.modelId || "") };
      if (!env?.OPENAI_API_KEY) return { status: "missing_provider_key", model: String(config?.modelId || "") };
      const capabilityConfig = await firestoreGet("adminAdvancedOptions/capability_live_voice");
      if (capabilityConfig?.enabled === false || capabilityConfig?.killSwitch === true) {
        return { status: "disabled", model: String(config?.modelId || capabilityConfig?.modelId || "") };
      }
      const model = String(config?.modelId || capabilityConfig?.modelId || DEFAULT_REALTIME_MODEL).slice(0, 80);
      const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(uid || "admin-self-test")));
      const safetyIdentifier = Array.from(new Uint8Array(digest)).map(value => value.toString(16).padStart(2, "0")).join("");
      const response = await fetch("https://api.openai.com/v1/realtime/client_secrets", {
        method: "POST",
        headers: {
          Authorization: "Bearer " + String(env.OPENAI_API_KEY),
          "Content-Type": "application/json",
          "OpenAI-Safety-Identifier": safetyIdentifier
        },
        body: JSON.stringify({
          session: {
            type: "realtime", model, output_modalities: ["audio"],
            audio: {
              input: { format: { type: "audio/pcm", rate: 24000 }, turn_detection: { type: "server_vad", interrupt_response: true, create_response: true } },
              output: { format: { type: "audio/pcm", rate: 24000 }, voice: "marin" }
            }
          }
        }),
        signal: AbortSignal.timeout(7000)
      });
      const raw = await response.text();
      let body = {};
      try { body = raw ? JSON.parse(raw) : {}; } catch {}
      if (!response.ok) {
        return {
          status: response.status === 401 ? "provider_key_missing"
            : response.status === 429 ? "provider_billing"
            : response.status === 400 || response.status === 422 ? "realtime_request_rejected"
            : response.status === 403 ? "realtime_model_rejected"
            : response.status === 404 ? "model_or_endpoint_unavailable" : "session_creation_failed",
          model
        };
      }
      const secret = body?.value || body?.client_secret?.value;
      // Deliberately discard the short-lived client secret. Never return or log it.
      return { status: secret ? "session_created_secret_discarded" : "session_response_missing_secret", model };
    } catch (error) {
      return { status: error?.name === "TimeoutError" ? "session_creation_timeout" : "session_creation_failed" };
    }
  };
  const check = async (feature, capabilityName) => {
    try {
      const config = await firestoreGet("adminAdvancedOptions/capability_" + capabilityName);
      const disabled = config?.enabled === false || config?.killSwitch === true;
      const provider = String(config?.providerId || "openai").toLowerCase();
      const providerSecret = provider === "openai" ? "OPENAI_API_KEY"
        : provider === "gemini" ? "GEMINI_API_KEY" : null;
      const supportedForFeature = feature === "chat" || feature === "vision"
        ? ["openai", "gemini"].includes(provider)
        : feature === "image"
          ? provider === "openai"
          : false;
      const providerStatus = disabled
        ? { status: "not_checked_disabled", reachable: false }
        : !supportedForFeature || !providerSecret
          ? { status: "unsupported_provider", reachable: false }
          : await probeProvider(provider);
      const configuredModel = String(config?.modelId || (feature === "image" ? "gpt-image-2" : DEFAULT_MODELS[provider] || "")).slice(0, 80);
      const model = feature === "vision" && configuredModel.startsWith("gpt-realtime")
        ? "gpt-4.1-mini"
        : configuredModel;
      const modelProbe = ["chat", "image", "vision"].includes(feature) && !disabled && supportedForFeature && env?.[providerSecret]
        ? await probeConfiguredModel(provider, model)
        : null;
      const status = disabled ? "disabled"
        : !supportedForFeature ? "unsupported_provider_configuration"
        : !providerSecret ? "unsupported_provider"
        : !env?.[providerSecret] ? "missing_provider_key"
        : providerStatus.status !== "reachable" ? providerStatus.status
        : modelProbe ? modelProbe.status
        : "provider_reachable_not_model_tested";
      return {
        feature, status, capabilityEnabled: !disabled,
        providerConfigured: Boolean(providerSecret && env?.[providerSecret]),
        providerStatus: providerStatus.status,
        modelStatus: modelProbe?.status || null,
        provider: /^[a-z0-9_-]{1,40}$/.test(provider) ? provider : "configured_provider",
        model: /^[a-zA-Z0-9._:-]{1,80}$/.test(model) ? model : ""
      };
    } catch {
      return { feature, status: "check_failed", capabilityEnabled: false, providerConfigured: false, providerStatus: "check_failed" };
    }
  };
  const [chat, image, vision, voice, catalog] = await Promise.all([
    check("chat", "chat"),
    check("image", "image_generation"),
    check("vision", "live_camera"),
    (async () => {
      try {
        const config = await firestoreGet("adminAdvancedOptions/live_voice_pricing");
        const enabled = config?.enabled !== false && config?.killSwitch !== true;
        if (!enabled) return { feature: "voice_session", status: "disabled", capabilityEnabled: false, providerConfigured: Boolean(env?.OPENAI_API_KEY) };
        const providerStatus = env?.OPENAI_API_KEY
          ? await probeRealtimeSessionCreation(adminUser?.uid || "admin-self-test")
          : { status: "missing_provider_key" };
        return {
          feature: "voice_session",
          status: providerStatus.status,
          capabilityEnabled: true,
          providerConfigured: Boolean(env?.OPENAI_API_KEY),
          model: /^[a-zA-Z0-9._:-]{1,80}$/.test(String(providerStatus.model || "")) ? String(providerStatus.model) : ""
        };
      } catch {
        return { feature: "voice_session", status: "config_check_failed", capabilityEnabled: false, providerConfigured: Boolean(env?.OPENAI_API_KEY) };
      }
    })(),
    (async () => {
      try {
        const data = await nativeCatalog();
        const planRows = Array.isArray(data.plans) ? data.plans : [];
        const planCount = planRows.length;
        const productCount = Array.isArray(data.products) ? data.products.length : 0;
        const degraded = data.catalogStatus?.degraded === true;
        const emptyFeaturePlans = planRows
          .filter(plan => !Array.isArray(plan.features) || plan.features.length === 0)
          .map(plan => String(plan.id || plan.name || "unknown_plan").replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 80))
          .filter(Boolean);
        const warnings = [
          ...(Array.isArray(data.catalogStatus?.warnings) ? data.catalogStatus.warnings : []),
          ...emptyFeaturePlans.map(id => "plan_missing_feature_list:" + id)
        ].slice(0, 20);
        const status = degraded ? "degraded"
          : (planCount + productCount) === 0 ? "empty"
          : emptyFeaturePlans.length > 0 ? "ready_with_warnings" : "ready";
        return { feature: "catalog", status, planCount, productCount, warnings };
      } catch {
        return { feature: "catalog", status: "check_failed", planCount: 0, productCount: 0, warnings: ["catalog_check_failed"] };
      }
    })()
  ]);
  const systemChecks = [
    { feature: "backend", status: "responding" },
    // This result can only be returned after the route's Firebase-token and super-admin checks pass.
    { feature: "admin_auth", status: "authenticated_super_admin" }
  ];
  try {
    await firestoreQuery("adminAdvancedOptions", { limit: 1 });
    systemChecks.push({ feature: "firestore", status: "reachable" });
  } catch {
    systemChecks.push({ feature: "firestore", status: "check_failed" });
  }
  return { checkedAt: new Date().toISOString(), mode: "readiness_no_audio_or_generated_output", checks: [...systemChecks, chat, image, vision, voice, catalog] };
}

function sanitizeProviderMessage(message) {
  return String(message || "")
    .replace(/https?:\/\/\S+/gi, "[redacted-url]")
    .replace(/Bearer\s+\S+/gi, "Bearer [redacted]")
    .replace(/\bsk-[A-Za-z0-9_-]{8,}\b/g, "[redacted-key]")
    .replace(/(api[_ -]?key|authorization|token|secret)\s*[:=]\s*\S+/gi, "$1=[redacted]")
    .replace(/[\r\n\t]+/g, " ")
    .replace(/\s{2,}/g, " ")
    .trim()
    .slice(0, 180);
}

async function notifyAdminOfIssue(env, route, status, errorCode, providerMessage = "", timelineStatus = "") {
  try {
    const issueId = crypto.randomUUID();
    const safeMessage = sanitizeProviderMessage(providerMessage);
    const opensIssue = Number(status || 500) >= 400;
    if (opensIssue) {
      await firestoreCreate("adminIssues/" + issueId, {
        id: issueId,
        route: String(route || "/").slice(0, 160),
        status: Number(status || 500),
        code: String(errorCode || "backend_unavailable").replace(/https?:\/\/\S+/gi, "[redacted-url]").slice(0, 160),
        ...(safeMessage ? { providerMessage: safeMessage } : {}),
        state: "open",
        createdAt: new Date()
      });
    }
    const feature = diagnosticFeatureForRoute(route);
    if (["chat", "image", "vision", "live_camera", "screen_share", "voice_session", "catalog"].includes(feature)) {
      await firestoreCreate("adminDiagnostics/" + issueId, {
        id: issueId,
        feature,
        route: String(route || "/").split("?")[0].slice(0, 120),
        status: Number(status || 500),
        code: safeDiagnosticCode(errorCode),
        ...(safeMessage ? { providerMessage: safeMessage } : {}),
        ...(timelineStatus ? { timelineStatus: String(timelineStatus).slice(0, 24), source: "client_timeline" } : {}),
        createdAt: new Date()
      });
    }
    if (!opensIssue) return;
    const ownerRaw = String(env?.SUPER_ADMIN_EMAIL || "").trim();
    const owner = ownerRaw.toLowerCase();
    if (!owner || !env?.BQ_FIREBASE_SERVICE_ACCOUNT_JSON) return;
    // Firestore string equality is case-sensitive. Try the configured spelling
    // first, then lowercase, because Firebase profile email casing may differ.
    let admins = await firestoreQuery("users", {
      filters: [{ field: { fieldPath: "email" }, op: "EQUAL", value: { stringValue: ownerRaw } }],
      limit: 1
    });
    if (!admins.length && ownerRaw !== owner) {
      admins = await firestoreQuery("users", {
        filters: [{ field: { fieldPath: "email" }, op: "EQUAL", value: { stringValue: owner } }],
        limit: 1
      });
    }
    const admin = admins[0];
    const adminUid = String(admin?.uid || String(admin?.__name || "").split("/").pop() || "");
    if (!adminUid) return;
    const docs = await firestoreQuery("notificationTokens", {
      filters: [{ field: { fieldPath: "uid" }, op: "EQUAL", value: { stringValue: adminUid } }],
      limit: 100
    });
    const tokens = docs.map(x => String(x.token || "")).filter(Boolean);
    if (tokens.length) {
      const service = JSON.parse(env.BQ_FIREBASE_SERVICE_ACCOUNT_JSON);
      await broadcast(env, service.project_id, tokens, "BQ Sqwad AI needs attention",
        "A service issue was detected. Open the Admin Dashboard to review the incident.",
        { type: "admin_issue", issueId });
    }
  } catch {
    // Issue reporting must never break the user's request.
  }
}

function corsHeaders(request = null) {
  const configured = String(globalThis.__bqEnv?.ALLOWED_ORIGINS || "").split(",").map(x => x.trim()).filter(Boolean);
  const origin = request?.headers?.get("Origin") || "";
  const allowOrigin = configured.length === 0 ? "*" : (configured.includes(origin) ? origin : configured[0]);
  return {
    "Access-Control-Allow-Origin": allowOrigin,
    "Access-Control-Allow-Headers": "Authorization, Content-Type, Accept, Idempotency-Key",
    "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff"
  };
}
function json(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { ...corsHeaders(), "content-type": "application/json; charset=utf-8" } });
}
async function readBody(request) {
  try { return await request.json(); } catch { return {}; }
}
function env(name) {
  const value = globalThis.__bqEnv?.[name];
  if (!value) throw new Error(name + " is not configured");
  return value;
}
async function openai(body, stream = false) {
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { Authorization: "Bearer " + env("OPENAI_API_KEY"), "Content-Type": "application/json" },
    body: JSON.stringify({ ...body, stream })
  });
  if (!response.ok) throw new Error("OpenAI HTTP " + response.status + ": " + (await response.text()).slice(0, 500));
  return response;
}
function outputText(result) {
  return result.output_text || result.output?.flatMap(x => x.content || []).map(x => x.text || "").join("") || "";
}
export function normalizeLivePricing(input = {}, existing = {}, feature = "live_voice") {
  const source = input && typeof input === "object" ? input : {};
  const previous = existing && typeof existing === "object" ? existing : {};
  const number = (key, fallback, maximum) => {
    const raw = source[key] == null ? previous[key] : source[key];
    const value = raw == null ? fallback : Number(raw);
    if (!Number.isFinite(value) || value < 0 || value > maximum) throw new Error("invalid_live_pricing_" + key);
    return value;
  };
  // Defaults use the shared billing card: $0.07999 per credit and 300% markup.
  // Live rates are credits per minute internally, settled as elapsed seconds.
  const normalizedFeature = String(feature || "").toLowerCase();
  const defaultCreditsPerMinute = normalizedFeature.includes("dictation") || normalizedFeature.includes("transcrib")
    ? 0.15001875234
    : (normalizedFeature.includes("camera") || normalizedFeature.includes("voice") ? 2.50031253907 : 0);
  const configuredCreditsPerMinute = number("creditsPerMinute", defaultCreditsPerMinute, 10000);
  // Migrate shipped 110% and 130% markup defaults to the new 300% card,
  // while preserving custom rates that differ from those known legacy defaults.
  const legacyDefaultRate = normalizedFeature.includes("dictation") || normalizedFeature.includes("transcrib")
    ? (Math.abs(configuredCreditsPerMinute - 0.0788) < 0.0000001 ? 0.0788 : 0.0862607826)
    : (normalizedFeature.includes("camera") || normalizedFeature.includes("voice")
      ? (Math.abs(configuredCreditsPerMinute - 1.3127) < 0.0000001 ? 1.3127 : 1.43767970996) : null);
  const creditsPerMinute = legacyDefaultRate != null && Math.abs(configuredCreditsPerMinute - legacyDefaultRate) < 0.0000001
    ? defaultCreditsPerMinute
    : configuredCreditsPerMinute;
  const defaultVoice = String(source.defaultVoice ?? previous.defaultVoice ?? "marin").toLowerCase();
  const allowedVoices = ["alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar"];
  return {
    enabled: source.enabled == null ? previous.enabled !== false : source.enabled === true,
    killSwitch: source.killSwitch == null ? previous.killSwitch === true : source.killSwitch === true,
    creditsPerMinute,
    startFeeCredits: number("startFeeCredits", 0, 10000),
    minimumBalanceToStart: number("minimumBalanceToStart", creditsPerMinute, 100000),
    maxSessionMinutes: Math.trunc(number("maxSessionMinutes", 120, 1440)),
    modelId: String(source.modelId ?? previous.modelId ?? "").trim().slice(0, 100),
    defaultVoice: allowedVoices.includes(defaultVoice) ? defaultVoice : "marin",
    transcriptionModel: String(source.transcriptionModel ?? previous.transcriptionModel ?? "gpt-4o-mini-transcribe").trim().slice(0, 100)
  };
}
export async function settleLiveStartReservation(uid, reservation, commit, reverse, createSession) {
  try {
    const result = await createSession();
    if (reservation?.reservationId) await commit(uid, reservation.reservationId, "openai");
    return result;
  } catch (error) {
    if (reservation?.reservationId) await reverse(uid, reservation.reservationId);
    throw error;
  }
}

export function liveStartChargePolicy(pricing = {}, balance = 0, superAdmin = false) {
  const rate = Math.max(0, Number(pricing.creditsPerMinute || 0));
  const startFee = Math.max(0, Number(pricing.startFeeCredits || 0));
  const minimum = Math.max(0, Number(pricing.minimumBalanceToStart ?? rate));
  const free = rate === 0 && startFee === 0;
  if (superAdmin || free) return { allowed: true, free, requiredBalance: 0, startFeeCredits: 0 };
  const available = Number(balance);
  return {
    allowed: Number.isFinite(available) && available >= minimum,
    free: false,
    requiredBalance: minimum,
    startFeeCredits: startFee
  };
}

async function capability(uid, name) {
  assertPublicCapabilityAllowed(name);
  const control = await firestoreGet("adminAdvancedOptions/capability_" + name);
  if (control?.enabled === false || control?.killSwitch === true) throw new Error("capability_disabled:capability_" + name);
  const defaults = {
    chat: { providerId: "openai", modelId: DEFAULT_MODELS.openai, fallbackProviderIds: ["gemini"] },
    image_generation: { providerId: "openai", modelId: "gpt-image-2", fallbackProviderIds: [] },
    live_camera: { providerId: "openai", modelId: DEFAULT_REALTIME_MODEL, fallbackProviderIds: [] },
    live_voice: { providerId: "openai", modelId: DEFAULT_REALTIME_MODEL, fallbackProviderIds: [] },
    video_generation: { providerId: "openai", modelId: "sora-2", fallbackProviderIds: [] },
    device_commands: { providerId: "gemini", modelId: DEFAULT_MODELS.gemini, fallbackProviderIds: [] },
    web_search: { providerId: "tavily", modelId: null, fallbackProviderIds: [] },
    deep_research: { providerId: "tavily", modelId: null, fallbackProviderIds: [] }
  };
  const d = defaults[name] || defaults.chat;
  const value = control || {};
  return {
    ...value,
    enabled: value.enabled !== false,
    providerId: value.providerId || d.providerId,
    modelId: value.modelId || d.modelId,
    creditCost: (name === "live_voice" || name === "live_camera")
      ? Math.max(0, Number(value.creditCost == null ? 0 : value.creditCost))
      : Math.max(1, Number(value.creditCost == null ? (name === "deep_research" ? 3 : (name === "web_search" ? 2 : 1)) : value.creditCost)),
    config: {
      ...(value.config || {}),
      fallbackProviderIds: Array.isArray(value.config?.fallbackProviderIds) ? value.config.fallbackProviderIds : d.fallbackProviderIds
    }
  };
}
async function requireConsent(uid) {
  const consent = await firestoreGet("userConsents/" + uid);
  if (!consent || consent.requiredAccepted !== true) throw new Error("required_consent_missing");
}
async function memoryContext(uid) {
  const memory = await firestoreGet("userMemory/" + uid);
  if (!memory || memory.enabled === false || !Array.isArray(memory.items)) return "";
  return memory.items.slice(-30).map(x => String(x?.text || "").trim()).filter(Boolean).join("\n");
}
function canonicalUserBalance(profile) {
  if (!profile) return null;
  const expiresAt = profile.subExpiresAt ? String(profile.subExpiresAt) : null;
  const subscriptionLive = expiresAt ? Date.parse(expiresAt) > Date.now() : false;
  const trialExpiresAt = profile.trialExpiresAt ? String(profile.trialExpiresAt) : null;
  const trialLive = trialExpiresAt ? Date.parse(trialExpiresAt) > Date.now() : false;
  const permanent = Math.max(0, Number(profile.credits || 0));
  const subscription = subscriptionLive ? Math.max(0, Number(profile.subCredits || 0)) : 0;
  const trial = trialLive ? Math.max(0, Number(profile.trialCredits || 0)) : 0;
  const timeZone = String(profile.dailyFreeCreditsTimeZone || globalThis.__bqEnv?.FREE_CREDIT_TIME_ZONE || DEFAULT_FREE_CREDIT_TIME_ZONE);
  const daily = currentDailyFreeCredits(profile, new Date(), timeZone);
  return {
    permanent, subscription, trial, daily,
    dailyDate: String(profile.dailyFreeCreditsDate || ""),
    dailyFreeCreditsTimeZone: timeZone,
    dailyFreeCreditsRenewalTime: String(profile.dailyFreeCreditsRenewalTime || globalThis.__bqEnv?.FREE_CREDIT_RENEWAL_TIME || "00:00"),
    total: permanent + subscription + trial + daily,
    subscriptionExpiresAt: subscriptionLive ? expiresAt : null,
    trialExpiresAt: trialLive ? trialExpiresAt : null,
    plan: String(profile.subscriptionPlan || "free")
  };
}

async function applyConfiguredCreditTrial(uid) {
  const config = await firestoreGet("adminAdvancedOptions/credit_trial_config");
  if (config?.enabled !== true) return;
  const credits = Math.max(0, Number(config.credits || 0));
  const days = Math.max(1, Math.min(30, Number(config.days || 5)));
  if (!credits) return;
  const profile = await firestoreGet("users/" + uid);
  if (!profile || profile.creditTrialGrantedAt) return;
  const now = new Date();
  const expiresAt = new Date(now.getTime() + days * 86400000);
  try {
    await firestoreCommit([
      { path: "users/" + uid, data: { trialCredits: credits, trialExpiresAt: expiresAt, creditTrialGrantedAt: now, updatedAt: now }, updateTime: profile.__updateTime },
      { path: "creditTrialGrants/" + uid, data: { uid, credits, days, grantedAt: now, expiresAt }, exists: false }
    ]);
  } catch {
    // A concurrent first request may have granted the trial already.
  }
}

async function applyConfiguredDailyFreeCredits(uid) {
  const config = await firestoreGet("adminAdvancedOptions/daily_free_credits_config");
  const enabled = config == null ? true : config.enabled === true;
  const credits = Math.max(0, Math.min(100000, Number(config?.credits ?? 5)));
  if (!enabled || credits <= 0) return;
  const profile = await firestoreGet("users/" + uid);
  if (!profile) return;
  const now = new Date();
  const timeZone = String(config?.timeZone || globalThis.__bqEnv?.FREE_CREDIT_TIME_ZONE || DEFAULT_FREE_CREDIT_TIME_ZONE);
  const renewalTime = String(config?.renewalTime || globalThis.__bqEnv?.FREE_CREDIT_RENEWAL_TIME || "00:00");
  const today = dailyCreditDateKey(now, timeZone, renewalTime);
  if (String(profile.dailyFreeCreditsDate || "") === today && String(profile.dailyFreeCreditsTimeZone || "") === timeZone) return;
  const grantId = uid + "_" + today;
  const grant = dailyFreeCreditGrant(credits, now, timeZone, renewalTime);
  try {
    await firestoreCommit([
      { path: "users/" + uid, data: { ...grant, updatedAt: now }, updateTime: profile.__updateTime },
      { path: "dailyFreeCreditGrants/" + grantId, data: { uid, date: today, timeZone, credits, grantedAt: now, source: "admin_configured_daily_allowance" }, exists: false },
      { path: "creditLedger/daily_" + grantId, data: { uid, capability: "daily_free_credits", amount: credits, kind: "daily_free_grant", reason: "Admin-configured daily allowance", createdAt: now }, exists: false }
    ]);
  } catch {
    // Concurrent requests can race for the same daily grant; only one may succeed.
  }
}

async function readCanonicalUserBalance(uid) {
  return firestoreGet("users/" + uid);
}

function isSubscriptionCoveredCapability(profile, capabilityName) {
  const expiresAt = Date.parse(String(profile?.subExpiresAt || ""));
  const planId = String(profile?.subscriptionPlan || "").trim().toLowerCase();
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now() || !planId || /^(free|none|trial-free)$/.test(planId)) return false;

  const capability = String(capabilityName || "").trim().toLowerCase().replace(/-/g, "_");
  const explicit = [
    ...(Array.isArray(profile?.planEntitlements) ? profile.planEntitlements : []),
    ...(Array.isArray(profile?.subscriptionFeatures) ? profile.subscriptionFeatures : []),
    ...(Array.isArray(profile?.includedCapabilities) ? profile.includedCapabilities : [])
  ].map(value => String(value || "").trim().toLowerCase().replace(/-/g, "_"));
  if (explicit.includes("*") || explicit.includes(capability)) return true;

  // Subscription-covered product workspaces are included in their matching
  // active plan; these requests must not consume daily, trial, subscription, or permanent credits.
  const category = capability.includes("truthguard") || capability.startsWith("tg_") ? "truthguard"
    : capability.includes("learning") || capability.startsWith("learn_") ? "learning"
    : capability.includes("companion") ? "companion"
    : capability.includes("business") || capability.startsWith("biz_") || capability.startsWith("organization_") ? "business"
    : null;
  if (!category) return false;
  const planMatches = {
    truthguard: /^(tg|truthguard)/,
    learning: /^(learn|learning|scholar|explorer|familyacademy|learnschool)/,
    companion: /^companion/,
    business: /^(biz|business|organization|enterprise)/
  };
  return planMatches[category].test(planId);
}

async function chargeCredits(uid, amount, capabilityName, adminUser = null, options = {}) {
  let charge = Math.max(0, Number(amount || 0));
  if (isSuperAdmin(adminUser, globalThis.__bqEnv)) return { reservationId: null, amount: 0, adminUnlimited: true };
  const currentProfile = await readCanonicalUserBalance(uid);
  if (!currentProfile) throw new Error("Account not found.");
  if (isSubscriptionCoveredCapability(currentProfile, capabilityName)) {
    return { reservationId: null, amount: 0, planCovered: true };
  }
  await applyConfiguredCreditTrial(uid);
  await applyConfiguredDailyFreeCredits(uid);
  if (!charge) {
    const configured = await firestoreGet("adminAdvancedOptions/capability_" + String(capabilityName || ""));
    charge = Math.max(0, Number(configured?.creditCost || 0));
  }
  // Apply shared admin markup to fixed-unit features. Token-billed calls are
  // explicitly marked as already priced from actual provider usage.
  if (charge > 0 && options.markupApplied !== true) {
    const billingConfig = await firestoreGet("adminAdvancedOptions/dynamic_billing_config").catch(() => null)
      || await firestoreGet("adminAdvancedOptions/dynamicBillingConfig").catch(() => null) || {};
    const markup = Math.max(0, Math.min(10, Number(billingConfig.pricingVersion >= 4 ? (billingConfig.profitMarkup ?? 3) : ([1.1, 1.3].includes(Number(billingConfig.profitMarkup)) || !Number.isFinite(Number(billingConfig.profitMarkup)) ? 3 : billingConfig.profitMarkup))));
    charge *= 1 + markup;
  }
  // Chat is never free because an old capability record is missing a price.
  if (String(capabilityName || "") === "chat" && charge < 1 && options.allowFractional !== true) charge = 1;
  if (!charge) {
    const profile = await readCanonicalUserBalance(uid);
    if (!profile) throw new Error("Account not found.");
    const balance = canonicalUserBalance(profile);
    if (!balance || balance.total <= 0) throw new Error("Insufficient credits");
    return { reservationId: null, amount: 0 };
  }

  const reservationId = crypto.randomUUID();
  for (let attempt = 0; attempt < 5; attempt++) {
    const profile = await readCanonicalUserBalance(uid);
    const balance = canonicalUserBalance(profile);
    if (!profile || !balance) throw new Error("Account not found.");
    if (Number(profile.suspended ? 1 : 0) === 1) throw new Error("Your account is suspended.");
    if (balance.total < charge) throw new Error("Insufficient credits");

    // The website's users/{uid} document is the shared credit authority.
    // Reserve the exact bucket(s) here so website and app cannot both spend
    // the same credits. The reservation is reversed if the AI call fails.
    const fromDaily = Math.min(balance.daily, charge);
    const afterDaily = charge - fromDaily;
    const fromTrial = Math.min(balance.trial, afterDaily);
    const afterTrial = afterDaily - fromTrial;
    const fromSubscription = Math.min(balance.subscription, afterTrial);
    const fromPermanent = afterTrial - fromSubscription;
    try {
      await firestoreCommit([
        {
          path: "users/" + uid,
          data: {
            credits: balance.permanent - fromPermanent,
            subCredits: Math.max(0, balance.subscription - fromSubscription),
            trialCredits: Math.max(0, balance.trial - fromTrial),
            dailyFreeCredits: Math.max(0, balance.daily - fromDaily),
            dailyFreeCreditsDate: balance.dailyDate,
          dailyFreeCreditsTimeZone: balance.dailyFreeCreditsTimeZone,
          dailyFreeCreditsRenewalTime: balance.dailyFreeCreditsRenewalTime,
            updatedAt: new Date()
          },
          updateTime: profile.__updateTime
        },
        {
          path: "creditReservations/" + reservationId,
          data: {
            uid,
            capability: capabilityName,
            amount: charge,
            fromDaily,
            dailyFreeCreditsDate: balance.dailyDate,
            dailyFreeCreditsTimeZone: balance.dailyFreeCreditsTimeZone,
            fromTrial,
            fromSubscription,
            fromPermanent,
            status: "reserved",
            createdAt: new Date()
          },
          exists: false
        }
      ]);
      return { reservationId, amount: charge };
    } catch (error) {
      if (attempt === 4) throw error;
    }
  }
  throw new Error("credit_reservation_failed");
}

async function commitCredits(uid, reservationId, providerId) {
  if (!reservationId) return;
  for (let attempt = 0; attempt < 5; attempt++) {
    const reservation = await firestoreGet("creditReservations/" + reservationId);
    if (!reservation || reservation.uid !== uid || reservation.status !== "reserved") return;
    const profile = await readCanonicalUserBalance(uid);
    const balance = canonicalUserBalance(profile);
    if (!profile || !balance) throw new Error("credit_balance_missing");
    try {
      await firestoreCommit([
        {
          path: "creditReservations/" + reservationId,
          data: {
            ...reservation,
            uid,
            status: "committed",
            providerId: providerId || null,
            committedAt: new Date()
          },
          updateTime: reservation.__updateTime
        },
        {
          path: "creditTransactions/" + crypto.randomUUID(),
          data: {
            uid,
            type: "usage",
            feature: reservation.capability,
            credits: -Number(reservation.amount || 0),
            balanceAfter: balance.total,
            note: "Native app usage",
            reason: "Cloudflare Worker AI request",
            providerId: providerId || null,
            requestId: reservationId,
            createdAt: new Date()
          },
          exists: false
        }
      ]);
      return;
    } catch (error) {
      if (attempt === 4) throw error;
    }
  }
}

async function settleCreditReservation(uid, reservationId, finalAmount, providerId) {
  if (!reservationId) return { amount: 0, adminUnlimited: true };
  const targetAmount = Math.max(0, Number(finalAmount || 0));
  for (let attempt = 0; attempt < 5; attempt++) {
    const reservation = await firestoreGet("creditReservations/" + reservationId);
    if (!reservation || reservation.uid !== uid || reservation.status !== "reserved") {
      throw new Error("credit_reservation_not_settleable");
    }
    const reservedAmount = Math.max(0, Number(reservation.amount || 0));
    if (targetAmount > reservedAmount + 0.000000001) {
      throw Object.assign(new Error("token_usage_exceeds_reserved_credits"), { httpStatus: 402, safeError: "insufficient_credits" });
    }
    const refundNeeded = Math.max(0, reservedAmount - targetAmount);
    if (refundNeeded <= 0.000000001) break;
    const profile = await readCanonicalUserBalance(uid);
    if (!profile) throw new Error("credit_balance_missing");
    let remaining = refundNeeded;
    const refundPermanent = Math.min(remaining, Math.max(0, Number(reservation.fromPermanent || 0))); remaining -= refundPermanent;
    const refundSubscription = Math.min(remaining, Math.max(0, Number(reservation.fromSubscription || 0))); remaining -= refundSubscription;
    const refundTrial = Math.min(remaining, Math.max(0, Number(reservation.fromTrial || 0))); remaining -= refundTrial;
    const refundDaily = Math.min(remaining, Math.max(0, Number(reservation.fromDaily || 0))); remaining -= refundDaily;
    if (remaining > 0.0000001) throw new Error("credit_reservation_refund_mismatch");
    try {
      await firestoreCommit([
        {
          path: "users/" + uid,
          data: {
            credits: Math.max(0, Number(profile.credits || 0)) + refundPermanent,
            subCredits: Math.max(0, Number(profile.subCredits || 0)) + refundSubscription,
            trialCredits: Math.max(0, Number(profile.trialCredits || 0)) + refundTrial,
            dailyFreeCredits: Math.max(0, Number(profile.dailyFreeCredits || 0)) + refundDaily,
            updatedAt: new Date()
          },
          updateTime: profile.__updateTime
        },
        {
          path: "creditReservations/" + reservationId,
          data: {
            ...reservation,
            amount: targetAmount,
            fromPermanent: Math.max(0, Number(reservation.fromPermanent || 0) - refundPermanent),
            fromSubscription: Math.max(0, Number(reservation.fromSubscription || 0) - refundSubscription),
            fromTrial: Math.max(0, Number(reservation.fromTrial || 0) - refundTrial),
            fromDaily: Math.max(0, Number(reservation.fromDaily || 0) - refundDaily),
            tokenSettledAt: new Date()
          },
          updateTime: reservation.__updateTime
        }
      ]);
      break;
    } catch (error) {
      if (attempt === 4) throw error;
    }
  }
  await commitCredits(uid, reservationId, providerId);
  return { amount: targetAmount };
}

async function reverseCredits(uid, reservationId) {
  if (!reservationId) return;
  for (let attempt = 0; attempt < 5; attempt++) {
    const reservation = await firestoreGet("creditReservations/" + reservationId);
    if (!reservation || reservation.uid !== uid || reservation.status !== "reserved") return;
    const profile = await readCanonicalUserBalance(uid);
    if (!profile) throw new Error("credit_balance_missing");
    try {
      await firestoreCommit([
        {
          path: "users/" + uid,
          data: {
            credits: Number(profile.credits || 0) + Number(reservation.fromPermanent || 0),
            subCredits: Number(profile.subCredits || 0) + Number(reservation.fromSubscription || 0),
            trialCredits: Number(profile.trialCredits || 0) + Number(reservation.fromTrial || 0),
            dailyFreeCredits: shouldRestoreDailyFreeCredits(profile, reservation, new Date(), globalThis.__bqEnv?.FREE_CREDIT_TIME_ZONE || DEFAULT_FREE_CREDIT_TIME_ZONE)
              ? Number(profile.dailyFreeCredits || 0) + Number(reservation.fromDaily || 0)
              : Number(profile.dailyFreeCredits || 0),
            updatedAt: new Date()
          },
          updateTime: profile.__updateTime
        },
        {
          path: "creditReservations/" + reservationId,
          data: {
            ...reservation,
            uid,
            status: "reversed",
            reversedAt: new Date()
          },
          updateTime: reservation.__updateTime
        }
      ]);
      return;
    } catch (error) {
      if (attempt === 4) throw error;
    }
  }
}

async function assertSharedCreditsAvailable(user) {
  if (isSuperAdmin(user, globalThis.__bqEnv)) return;
  await applyConfiguredCreditTrial(user.uid);
  await applyConfiguredDailyFreeCredits(user.uid);
  const profile = await readCanonicalUserBalance(user.uid);
  if (!profile) throw new Error("Account not found.");
  const balance = canonicalUserBalance(profile);
  if (!balance || balance.total <= 0) throw Object.assign(new Error("Insufficient credits"), { httpStatus: 402 });
}

async function withSharedCreditCharge(user, capabilityName, action, modelRegistryId = null) {
  let control = await capability(user.uid, capabilityName);
  if (modelRegistryId && capabilityName === "chat") {
    const selected = await resolvePublishedModel(modelRegistryId, "text-chat");
    control = { ...control, providerId: selected.providerId, modelId: selected.modelId, creditCost: selected.creditCost };
  }
  const reservation = await chargeCredits(user.uid, control.creditCost || 0, capabilityName, user);
  try {
    const result = await action(control);
    await commitCredits(user.uid, reservation.reservationId, result?.providerId || "openai");
    return { ...result, creditsCharged: Number(reservation.amount || 0) };
  } catch (error) {
    await reverseCredits(user.uid, reservation.reservationId);
    throw error;
  }
}

export function isPublicCatalogPlan(x) {
  if (!x.visible || !x.active) return false;
  const id = String(x.id || "").toLowerCase();
  const name = String(x.name || "").toLowerCase();
  const module = String(x.module || "").toLowerCase();
  const sharedCreditPack = new Set(["credit100", "credit250", "credit500", "credit1000"]).has(id);
  const searchable = (id + " " + name + " " + module).replace(/[^a-z0-9]+/g, " ").trim();
  const featureSpecificLiveCredit =
    (searchable.includes("live") && searchable.includes("credit")) ||
    /\bai live m\b/.test(searchable) ||
    /\bailivem\b/.test(searchable.replace(/ /g, ""));
  // All credit-consuming features use the one shared BQ Sqwad AI balance.
  // Never expose feature-specific live-camera/live-voice credit plans.
  if (featureSpecificLiveCredit) return false;
  if (module === "credits" && !sharedCreditPack) return false;
  if (/(^| )credit(s)?( |$)/.test(searchable) && !sharedCreditPack) return false;
  return true;
}

async function nativeCatalog() {
  // The canonical catalog is bundled with the Worker. A failure in an optional
  // Firestore collection must not blank the customer-facing catalog.
  const catalogWarnings = [];
  const readOptional = async (label, read, fallback) => {
    try { return await read(); }
    catch {
      catalogWarnings.push(label);
      return fallback;
    }
  };
  const [plans, planCatalog, playCatalogConfig, features, bindings, addOns, businessPricing] = await Promise.all([
    readOptional("legacy_plans_unavailable", () => firestoreQuery("plans", { filters: [{ field: { fieldPath: "active" }, op: "EQUAL", value: { booleanValue: true } }], limit: 100 }), []),
    readOptional("admin_catalog_unavailable", () => firestoreGet("planCatalog/config"), null),
    readOptional("play_mappings_unavailable", () => firestoreGet("playCatalog/config"), null),
    readOptional("feature_registry_unavailable", () => firestoreQuery("featureRegistry", { limit: 100 }), []),
    readOptional("feature_bindings_unavailable", () => firestoreQuery("planFeatureBindings", { limit: 100 }), []),
    readOptional("addons_unavailable", () => firestoreQuery("addOns", { filters: [{ field: { fieldPath: "enabled" }, op: "EQUAL", value: { booleanValue: true } }], limit: 100 }), []),
    readOptional("business_pricing_unavailable", () => firestoreGet("config/businessPricing"), null)
  ]);
  const safePlan = (id, data = {}) => ({
    id,
    name: String(data.name || ""),
    description: String(data.description || ""),
    module: String(data.module || data.product || ""),
    productId: data.productId ? String(data.productId) : null,
    basePlans: Array.isArray(data.basePlans) ? data.basePlans.map(String).filter(Boolean) : [],
    features: Array.isArray(data.features) ? data.features.map(String).slice(0, 100) : [],
    limits: data.limits && typeof data.limits === "object" ? data.limits : {},
    price: data.price && typeof data.price === "object" ? data.price : {},
    priceNgn: data.priceNgn && typeof data.priceNgn === "object" ? data.priceNgn : {},
    trialDays: Number(data.trialDays || 0),
    trialEligible: data.trialEligible === true,
    offerId: data.offerId ? String(data.offerId) : null,
    paymentChannel: String(data.paymentChannel || (data.playEligible ? "play" : (data.contactSales ? "contact_sales" : "website"))),
    playEligible: data.playEligible === true,
    contactSales: data.contactSales === true,
    popular: data.popular === true || data.badge === "popular",
    badge: data.badge ? String(data.badge) : null,
    highlighted: data.highlighted === true,
    sortOrder: Number(data.sortOrder || 0),
    visible: data.visible !== false,
    active: data.active !== false,
    version: Number(data.version || 1),
    effectiveDate: data.effectiveDate || null
  });
  const mergedCatalog = mergeCommercialCatalog(planCatalog?.catalog);
  const catalogPlans = Object.values(mergedCatalog.sections || {}).flat()
    .filter(item => item && typeof item === "object")
    .map(item => safePlan(item.id, item));
  const firestorePlans = plans.map(d => safePlan(String(d.__name || "").split("/").pop(), d));
  const planIds = new Set(catalogPlans.map(plan => plan.id));
  // Preserve additional active legacy plans without letting a partial legacy
  // collection replace or hide the canonical plans and saved admin overrides.
  const basePlans = [
    ...catalogPlans,
    ...firestorePlans.filter(plan => !planIds.has(plan.id))
  ];
  const publicPlans = basePlans.filter(isPublicCatalogPlan);
  const visiblePlanIds = new Set(publicPlans.map(p => p.id));
  const configuredProducts = Array.isArray(playCatalogConfig?.products) ? playCatalogConfig.products : [];
  const configuredPlanKeys = new Set(configuredProducts.map(p => String(p.planId || "") + "|" + String(p.basePlanId || "")));
  const storedCatalog = planCatalog?.catalog;
  const hasStoredPlans = Object.values(storedCatalog?.sections || {}).some(rows => Array.isArray(rows) && rows.length > 0);
  const planMappings = canonicalPlayMappings(hasStoredPlans ? storedCatalog : COMMERCIAL_CATALOG);
  const mergedProducts = [
    ...configuredProducts,
    ...planMappings.filter(p => !configuredPlanKeys.has(String(p.planId || "") + "|" + String(p.basePlanId || "")))
  ];
  // Return only checkout mappings attached to visible BQ Sqwad AI plans. Raw Play
  // products remain private to purchase verification and are never a second catalog.
  const products = mergedProducts.map(p => ({
    planId: String(p.planId || ""),
    productId: String(p.productId || ""),
    basePlanId: p.basePlanId ? String(p.basePlanId) : null,
    productType: String(p.productType || ""),
    enabled: p.enabled === true,
    consumable: p.consumable === true,
    billingPeriod: p.billingPeriod ? String(p.billingPeriod) : null,
    priceUsd: p.priceUsd == null ? null : Number(p.priceUsd)
  })).filter(p => p.enabled && p.productId && visiblePlanIds.has(p.planId) && /^[a-z0-9-]+$/.test(p.productId) && (p.productType === "subs" || p.productType === "inapp"));
  const featureRows = features.map(x => ({
    id: String(x.__name || "").split("/").pop(),
    name: String(x.name || x.title || String(x.__name || "").split("/").pop()),
    description: String(x.description || ""),
    group: String(x.group || x.category || ""),
    product: String(x.product || ""),
    defaultState: String(x.defaultState || "unavailable"),
    visible: x.visible !== false,
    comingSoon: x.comingSoon === true
  })).filter(x => x.visible);
  const bindingRows = bindings.map(x => ({
    id: String(x.__name || "").split("/").pop(),
    planId: String(x.planId || ""),
    featureId: String(x.featureId || ""),
    state: String(x.state || "unavailable"),
    quota: x.quota ?? null,
    creditCost: x.creditCost ?? null,
    note: x.note ? String(x.note) : null,
    version: Number(x.version || 1)
  }));
  const addOnRows = addOns.map(x => ({
    id: String(x.__name || "").split("/").pop(),
    name: String(x.name || String(x.__name || "").split("/").pop()),
    description: String(x.description || ""),
    type: String(x.type || ""),
    enabled: x.enabled !== false,
    eligiblePlanIds: Array.isArray(x.eligiblePlanIds) ? x.eligiblePlanIds.map(String) : [],
    quantityTiers: Array.isArray(x.quantityTiers) ? x.quantityTiers : []
  }));
  return {
    version: Math.max(1, Number(planCatalog?.version || playCatalogConfig?.version || COMMERCIAL_CATALOG.version || 1)),
    catalogStatus: { degraded: catalogWarnings.length > 0, warnings: catalogWarnings },
    plans: publicPlans,
    products,
    publicContactSales: publicPlans
      .filter(p => p.contactSales === true || p.paymentChannel === "contact_sales")
      .map(p => ({
        name: p.name,
        module: p.module,
        billingPeriod: "custom",
        price: "Contact Sales",
        features: p.features,
        limits: p.limits
      })),
    features: featureRows,
    bindings: bindingRows,
    addOns: addOnRows,
    businessPricingConfigured: businessPricing !== null,
    businessPricing: businessPricing || null
  };
}

const BQ_APP_KNOWLEDGE = [
  "You are BQ Sqwad AI, made by BQ Sqwad Technologies. Your tagline is: The AI That Grows With You.",
  "Help users understand and use the app honestly. Never claim a feature is working, enabled, included in a plan, or available on this device unless the current conversation or verified service result establishes that.",
  "Core app areas: Main Chat; image generation; file analysis; video generation; Web Search; TruthGuard AI; Frontier Lab; Data Analyst; AI Live Camera; screen sharing; Live Voice AI; Personal Intelligence; Life Matters; AI Companion; Learning Studio; Business AI; Organization Studio AI; account protection; chat history; saved conversations; Plans & Credits; admin operations.",
  "Registered advanced capability areas include deep/web research, source verification, semantic and multi-file search, document/audio/video analysis, knowledge graphs, project workspaces and memory, generated reports, coding workspace, custom assistants, agent planning and tool use, workflow building, scheduled tasks, approval checkpoints, action previews, permissions and privacy controls, activity timeline and undo, resumable uploads, background generation jobs, model routing and provider failover, provider health and quality evaluation, incident replay, model rollback, security monitoring, passkeys and session/device management, data export/deletion, accessibility and multilingual voice, cross-device sync, learning quizzes and document-to-course, business CRM/workflows/analytics/knowledge/channels/automation/training, calendar/reminder/device/media actions, notifications, referrals, credit reservation/reversal, rate limiting and abuse protection.",
  "The feature directory is a registry, not proof every advanced capability is fully implemented. Explain limitations and suggest the relevant workspace or settings without inventing availability.",
  "The customer-facing plan and product list is the BQ Sqwad AI catalog served by the native catalog endpoint. Google Play is the in-app payment and purchase-verification method, not the customer-facing plan catalog. Do not tell users to browse a Google Play billing catalog as the app's plan catalog.",
  "Text tools: help users draft, rewrite, correct, summarize, expand, shorten, translate, explain, structure, and reformat text; create headings, numbered lists, bullet lists, tables, templates, letters, reports, captions, and copy-ready messages. Use Markdown headings and bold emphasis when a user asks for larger or more prominent text; use concise plain text when they ask for smaller or simpler text. Use fenced code blocks for code and preserve indentation. Do not claim the AI can change Android font size itself; app-wide display size is controlled by Settings > Appearance > Text size.",
  "Recognize and explain the app's central brand controls: Settings > Brand & App Logo selects one shared in-app logo for all BqLogo components on this device, with the bundled BQ Sqwad AI logo as fallback. Do not claim this changes the Android launcher icon or other devices unless a verified service says so.",
  "Feature recognition should include Main Chat, image generation, file analysis, video generation, Web Search, TruthGuard AI, Frontier Lab, Data Analyst, AI Live Camera, screen sharing, Live Voice AI, Personal Intelligence, Life Matters, AI Companion, Learning Studio, Business AI, Organization Studio AI, Plans & Credits, Chat History, account protection, privacy/legal controls, admin operations, shared credits, and the registered advanced capabilities above. When asked what the app supports, list these clearly and distinguish shipped core features from registered/experimental features that still require verification.",
  "Format responses for readability with useful headings, short paragraphs, numbered steps, bullets, tables when helpful, and fenced code blocks for code. Keep text selectable and copyable, avoid decorative clutter, and follow a user's request for concise, detailed, or step-by-step output."
].join("\n");

async function configuredMediaProviderCost(control, units, expectedUnit, trackingOnly = false) {
  if (trackingOnly ? control?.providerCostTrackingEnabled !== true : String(control?.billingMode || "fixed_credit") !== "provider_cost") return null;
  const unit = String(control?.providerCostUnit || "");
  const count = Number(units);
  const unitPrice = Number(control?.priceUsd || 0);
  const creditValue = 0.07999;
  const billingConfig = await firestoreGet("adminAdvancedOptions/dynamic_billing_config").catch(() => null)
    || await firestoreGet("adminAdvancedOptions/dynamicBillingConfig").catch(() => null) || {};
  const markup = Math.max(0, Math.min(10, Number(billingConfig.pricingVersion >= 4 ? (billingConfig.profitMarkup ?? 3) : (Number(billingConfig.profitMarkup) === 1.3 || !Number.isFinite(Number(billingConfig.profitMarkup)) ? 3 : billingConfig.profitMarkup))));
  const multiplier = 1 + markup;
  const feePercent = 0;
  if (unit !== expectedUnit || !Number.isFinite(count) || count <= 0 ||
      !Number.isFinite(unitPrice) || unitPrice <= 0 ||
      !Number.isFinite(creditValue) || creditValue <= 0 ||
      !Number.isFinite(multiplier) || multiplier < 1 ||
      !Number.isFinite(feePercent) || feePercent < 0 || feePercent > 100) {
    throw Object.assign(new Error("media_provider_cost_not_configured"), { httpStatus: 409, safeError: "media_provider_cost_not_configured" });
  }
  const providerCostUsd = unitPrice * count;
  return {
    units: count, unit, providerCostUsd, creditValue, multiplier, feePercent,
    chargeCredits: (providerCostUsd / creditValue) * multiplier,
    providerCostCredits: providerCostUsd / creditValue
  };
}

async function recordConfiguredMediaCost(uid, feature, control, result, cost, modality) {
  if (!cost) return;
  await recordTokenUsage({
    uid, feature,
    providerId: result?.providerId || control.providerId,
    modelId: result?.modelId || control.modelId,
    usage: { inputTokens: 0, outputTokens: 0, cachedInputTokens: 0, reasoningTokens: 0 },
    pricing: {
      creditReferenceValueUsd: cost.creditValue,
      customerMultiplier: cost.multiplier,
      estimatedFeePercent: cost.feePercent
    },
    calculation: {
      providerCostUsd: cost.providerCostUsd,
      providerCostCredits: cost.providerCostCredits,
      estimatedFeePercent: cost.feePercent
    },
    requestId: result?.generationId || result?.jobId || crypto.randomUUID(),
    chargedCredits: Number(result?.creditsCharged || 0),
    planCovered: false,
    modality,
    unitCount: cost.units
  });
}

async function ai(request, user, body) {
  enforceRateLimit(user.uid);
  await requireConsent(user.uid);
  const capabilityName = String(body.capability || "chat").trim();
  const userMessage = String(body.message || "").trim();
  if (!userMessage) throw new Error("message_required");
  if (!/^[a-z0-9_-]{1,64}$/.test(capabilityName)) throw new Error("invalid_capability");
  const redirect = await workspaceScopeRedirect(capabilityName, userMessage);
  if (redirect) return json({ ok: true, redirect });
  const idem = await loadIdempotentResponse(request, user, "/ai");
  if (idem?.replay) return idem.replay;
  if (idem?.fresh) await firestoreCreate("idempotency/" + idem.hash, { uid:user.uid, status:"pending", createdAt:new Date(), expiresAt:new Date(Date.now()+600000) });
  let control = await capability(user.uid, capabilityName);
  if (body.modelRegistryId && capabilityName === "chat") {
    const selected = await resolvePublishedModel(body.modelRegistryId, "text-chat");
    control = { ...control, ...selected, config: control.config || {} };
  }
  const pricingFeature = capabilityName === "chat" ? "text-chat" : capabilityName.replace(/_/g, "-");
  // Text chat defaults to measured provider-cost billing. An explicit admin
  // setting can still opt a capability out; model pricing must be configured
  // or the request fails safely instead of silently charging one fixed credit.
  const tokenBilling = capabilityName === "chat" ||
    (["device_commands", "device-commands"].includes(capabilityName) &&
      String(control.billingMode || "fixed_credit") === "token");
  const tokenPricing = tokenBilling
    ? await resolveTokenPricing(control.providerId, control.modelId, pricingFeature, control)
    : null;
  if (tokenBilling && !tokenPricing.configured) {
    throw Object.assign(new Error("token_pricing_not_configured"), { httpStatus: 409, safeError: "token_pricing_not_configured" });
  }
  const memory = await memoryContext(user.uid);
  const system = [BQ_APP_KNOWLEDGE, body.system ? String(body.system) : "", workspaceScopeSystemPrompt(capabilityName), memory ? "Relevant user memory (use only when helpful and do not invent details):\n" + memory : ""].filter(Boolean).join("\n");
  const reservationAmount = tokenBilling
    ? estimateTokenReservation({ message: userMessage, system, pricing: tokenPricing })
    : (control.creditCost || 0);
  const reservation = await chargeCredits(user.uid, reservationAmount, capabilityName, user, { allowFractional: tokenBilling, markupApplied: tokenBilling });
  try {
    const result = await runProvider(globalThis.__bqEnv, {
      primaryProviderId: control.providerId,
      primaryModelId: control.modelId,
      fallbackProviderIds: tokenBilling && String(control.providerId || "").toLowerCase() === "openai" ? ["gemini"] : (control.config?.fallbackProviderIds || control.fallbackProviderIds || []),
      fallbackModels: control.config?.fallbackModels || {}
    }, { message: userMessage, system, maxOutputTokens: tokenBilling ? tokenPricing.maxOutputTokens : undefined });
    if (!result.text?.trim()) throw new Error("The AI service returned an empty response.");
    let creditsCharged = Number(reservation.amount || 0);
    let usageSummary = null;
    if (tokenBilling) {
      const usage = normalizeProviderUsage(result.providerId, result.usage || {});
      if (usage.inputTokens + usage.outputTokens <= 0) {
        throw Object.assign(new Error("provider_usage_unavailable"), { httpStatus: 502, safeError: "provider_usage_unavailable" });
      }
      const actualPricing = await resolveTokenPricing(result.providerId, result.modelId, pricingFeature, control);
      if (!actualPricing.configured) {
        throw Object.assign(new Error("token_pricing_not_configured_for_selected_model"), { httpStatus: 409, safeError: "token_pricing_not_configured" });
      }
      const calculation = calculateTokenCost({
        usage, pricing: actualPricing,
        creditReferenceValueUsd: actualPricing.creditReferenceValueUsd,
        multiplier: actualPricing.customerMultiplier
      });
      const settled = await settleCreditReservation(user.uid, reservation.reservationId, calculation.customerCredits, result.providerId);
      creditsCharged = Number(settled.amount || 0);
      usageSummary = {
        inputTokens: usage.inputTokens,
        outputTokens: usage.outputTokens,
        cachedInputTokens: usage.cachedInputTokens,
        providerCostUsd: calculation.providerCostUsd,
        providerCostCredits: calculation.providerCostCredits,
        creditsCharged,
        grossUsageValueUsd: calculation.grossUsageValueUsd,
        estimatedFeeUsd: calculation.estimatedFeeUsd,
        estimatedNetProfitUsd: calculation.estimatedNetProfitUsd
      };
      await recordTokenUsage({
        uid: user.uid, feature: capabilityName, providerId: result.providerId, modelId: result.modelId,
        usage, pricing: actualPricing, calculation, requestId: reservation.reservationId || crypto.randomUUID(),
        chargedCredits: creditsCharged, planCovered: reservation.planCovered === true, modality: "text"
      }).catch(error => notifyAdminOfIssue(globalThis.__bqEnv, "/ai", 500, "token_usage_ledger_write_failed"));
    } else {
      await commitCredits(user.uid, reservation.reservationId, result.providerId);
    }
    const responseBody = {
      text: result.text.trim(), providerId: result.providerId, modelId: result.modelId,
      creditsCharged, ...(usageSummary ? { tokenUsage: usageSummary } : {}),
      incidentId: crypto.randomUUID()
    };
    if (idem?.hash) await firestoreSet("idempotency/" + idem.hash, { uid:user.uid, status:"completed", body:responseBody, httpStatus:200, completedAt:new Date(), expiresAt:new Date(Date.now()+600000) }, true);
    return json(responseBody);
  } catch (error) {
    await reverseCredits(user.uid, reservation.reservationId);
    if (idem?.hash) await firestoreDelete("idempotency/" + idem.hash).catch(() => {});
    throw error;
  }
}
async function vision(request, user, body) {
  enforceRateLimit(user.uid);
  await requireConsent(user.uid);
  const message = String(body.message || "Describe what is visible in the attached image(s).").slice(0, 4000);
  const rawImages = Array.isArray(body.images)
    ? body.images
    : (body.imageBase64 ? [{ imageBase64: body.imageBase64, mimeType: body.mimeType }] : []);
  const rawFiles = Array.isArray(body.files) ? body.files : [];
  if (rawImages.length > 8 || rawFiles.length > 4 || rawImages.length + rawFiles.length < 1) {
    throw new Error("attachment_count_invalid");
  }
  const supportedImageTypes = new Set(["image/jpeg", "image/jpg", "image/png", "image/webp"]);
  const images = rawImages.map(item => {
    const requestedMime = String(item?.mimeType || "image/jpeg").slice(0, 80).toLowerCase();
    return {
      imageBase64: String(item?.imageBase64 || ""),
      mimeType: requestedMime === "image/jpg" ? "image/jpeg" : requestedMime
    };
  });
  const files = rawFiles.map(item => ({
    name: String(item?.name || "document.pdf").replace(/[\\/]/g, "_").slice(0, 180),
    mimeType: String(item?.mimeType || "application/pdf").slice(0, 80).toLowerCase(),
    fileBase64: String(item?.fileBase64 || "")
  }));
  if (images.some(image => !image.imageBase64)) throw new Error("image_required");
  if (images.some(image => !supportedImageTypes.has(image.mimeType))) throw new Error("image_type_invalid");
  if (files.some(file => !file.fileBase64)) throw new Error("file_required");
  if (files.some(file => file.mimeType !== "application/pdf")) throw new Error("file_type_invalid");
  const encodedSize = images.reduce((total, image) => total + image.imageBase64.length, 0)
    + files.reduce((total, file) => total + file.fileBase64.length, 0);
  if (encodedSize > 8000000) throw new Error("attachment_payload_too_large");
  // Keep live camera on its own capability by default, while allowing the
  // authenticated main-chat attachment path to use the selected chat capability.
  // The capability resolver remains the authority for availability and pricing.
  const capabilityName = String(body.capability || "live_camera").trim().slice(0, 100);
  const redirect = await workspaceScopeRedirect(capabilityName, message);
  if (redirect) return json({ ok: true, redirect });
  let control = await capability(user.uid, capabilityName);
  if (body.modelRegistryId && capabilityName === "live_camera") {
    const selected = await resolvePublishedModel(body.modelRegistryId, "live-camera");
    control = { ...control, providerId: selected.providerId, modelId: selected.modelId, creditCost: selected.creditCost };
  }
  const reservation = await chargeCredits(user.uid, control.creditCost || 0, capabilityName, user);
  try {
    const requestedSystem = String(body.system || "").slice(0, 12000).trim();
    const system = requestedSystem
      ? BQ_APP_KNOWLEDGE + "\n\n" + requestedSystem
      : BQ_APP_KNOWLEDGE;
    // Image understanding is supported by OpenAI and Gemini. If a capability
    // is configured for a non-vision provider (for example search), use the
    // default OpenAI vision model while still enforcing that capability's gate
    // and credit cost.
    const primaryProviderId = ["openai", "gemini"].includes(control.providerId) ? control.providerId : "openai";
    const configuredVisionModelRaw = String(control.config?.visionModelId || control.visionModelId || "").trim();
    const configuredVisionModel = configuredVisionModelRaw.startsWith("gpt-realtime") ? "" : configuredVisionModelRaw;
    const capabilityModel = String(control.modelId || "").trim();
    // The live-camera realtime model is for WebSocket voice/video sessions, not
    // the Responses API used by this single-frame vision endpoint.
    const compatibleCapabilityModel = capabilityModel && !capabilityModel.startsWith("gpt-realtime");
    const defaultVisionModel = primaryProviderId === "gemini" ? DEFAULT_MODELS.gemini : "gpt-4.1-mini";
    const primaryModelId = primaryProviderId === control.providerId
      ? (configuredVisionModel || (compatibleCapabilityModel ? capabilityModel : defaultVisionModel))
      : defaultVisionModel;
    const fallbackProviderIds = (control.config?.fallbackProviderIds || [])
      .filter(providerId => ["openai", "gemini"].includes(providerId) && providerId !== primaryProviderId);
    const result = await runProvider(globalThis.__bqEnv, {
      primaryProviderId,
      primaryModelId,
      fallbackProviderIds,
      fallbackModels: control.config?.fallbackModels || {}
    }, { message, system, images, files });
    const responseText = String(result.text || "").trim();
    if (!responseText) throw new Error("The AI service returned an empty response.");
    await commitCredits(user.uid, reservation.reservationId, result.providerId);
    return json({ text: responseText, providerId: result.providerId, modelId: result.modelId, creditsCharged: reservation.amount });
  } catch (error) {
    await reverseCredits(user.uid, reservation.reservationId);
    throw error;
  }
}
async function stream(request, user, body) {
  enforceRateLimit(user.uid);
  await requireConsent(user.uid);
  const capabilityName = String(body.capability || "chat").trim();
  const userMessage = String(body.message || "").trim();
  if (!userMessage) throw new Error("message_required");
  const redirect = await workspaceScopeRedirect(capabilityName, userMessage);
  if (redirect) return json({ ok: true, redirect });
  let control = await capability(user.uid, capabilityName);
  if (body.modelRegistryId && capabilityName === "chat") {
    const selected = await resolvePublishedModel(body.modelRegistryId, "text-chat");
    control = { ...control, ...selected, config: control.config || {} };
  }
  const pricingFeature = capabilityName === "chat" ? "text-chat" : capabilityName.replace(/_/g, "-");
  // Text chat defaults to measured provider-cost billing. Explicit admin settings
  // may opt out; missing model rates fail safely rather than silently charging 1 credit.
  const tokenBilling = capabilityName === "chat" ||
    (["device_commands", "device-commands"].includes(capabilityName) &&
      String(control.billingMode || "fixed_credit") === "token");
  const tokenPricing = tokenBilling
    ? await resolveTokenPricing(control.providerId, control.modelId, pricingFeature, control)
    : null;
  if (tokenBilling && !tokenPricing.configured) {
    throw Object.assign(new Error("token_pricing_not_configured"), { httpStatus: 409, safeError: "token_pricing_not_configured" });
  }
  const memory = await memoryContext(user.uid);
  const system = [BQ_APP_KNOWLEDGE, body.system ? String(body.system) : "", memory ? "Relevant user memory (use only when helpful and do not invent details):\n" + memory : ""].filter(Boolean).join("\n");
  const reservationAmount = tokenBilling
    ? estimateTokenReservation({ message: userMessage, system, pricing: tokenPricing })
    : (control.creditCost || 0);
  const reservation = await chargeCredits(user.uid, reservationAmount, capabilityName, user, { allowFractional: tokenBilling, markupApplied: tokenBilling });
  try {
    const provider = await runProviderStream(globalThis.__bqEnv, {
      primaryProviderId: control.providerId,
      primaryModelId: control.modelId,
      fallbackProviderIds: tokenBilling ? [] : (control.config?.fallbackProviderIds || []),
      fallbackModels: control.config?.fallbackModels || {}
    }, { message: userMessage, system, maxOutputTokens: tokenBilling ? tokenPricing.maxOutputTokens : undefined });
    const encoder = new TextEncoder();
    const output = new ReadableStream({
      async start(controller) {
        const emit = (value) => controller.enqueue(encoder.encode("data: " + JSON.stringify(value) + "\n\n"));
        let usageRaw = provider.usage || {};
        try {
          if (provider.streamed) {
            const reader = provider.response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = "";
            let completed = false;
            while (true) {
              const { value, done } = await reader.read();
              if (done) break;
              buffer += decoder.decode(value, { stream: true });
              const events = buffer.split(/\r?\n\r?\n/);
              buffer = events.pop() || "";
              for (const event of events) {
                const data = event.split(/\r?\n/).filter(x => x.startsWith("data:")).map(x => x.slice(5).trim()).join("\n");
                if (!data || data === "[DONE]") continue;
                let parsed; try { parsed = JSON.parse(data); } catch { continue; }
                if (parsed.type === "response.output_text.delta" && parsed.delta) {
                  emit({ type: "delta", text: parsed.delta });
                } else if (parsed.type === "response.completed") {
                  completed = true;
                  usageRaw = parsed.response?.usage || parsed.usage || usageRaw;
                } else if (parsed.type === "error") {
                  throw new Error(parsed.error?.message || "Streaming failed");
                }
              }
            }
            if (!completed) throw new Error("stream_incomplete");
          } else {
            emit({ type: "delta", text: provider.text });
          }
          let creditsCharged = Number(reservation.amount || 0);
          let tokenUsage = null;
          if (tokenBilling) {
            const usage = normalizeProviderUsage(provider.providerId, usageRaw);
            if (usage.inputTokens + usage.outputTokens <= 0) {
              throw Object.assign(new Error("provider_usage_unavailable"), { httpStatus: 502, safeError: "provider_usage_unavailable" });
            }
            const actualPricing = await resolveTokenPricing(provider.providerId, provider.modelId, pricingFeature, control);
            if (!actualPricing.configured) {
              throw Object.assign(new Error("token_pricing_not_configured_for_selected_model"), { httpStatus: 409, safeError: "token_pricing_not_configured" });
            }
            const calculation = calculateTokenCost({
              usage, pricing: actualPricing,
              creditReferenceValueUsd: actualPricing.creditReferenceValueUsd,
              multiplier: actualPricing.customerMultiplier
            });
            const settled = await settleCreditReservation(user.uid, reservation.reservationId, calculation.customerCredits, provider.providerId);
            creditsCharged = Number(settled.amount || 0);
            tokenUsage = {
              inputTokens: usage.inputTokens, outputTokens: usage.outputTokens,
              cachedInputTokens: usage.cachedInputTokens, providerCostUsd: calculation.providerCostUsd,
              providerCostCredits: calculation.providerCostCredits, creditsCharged,
              grossUsageValueUsd: calculation.grossUsageValueUsd,
              estimatedFeeUsd: calculation.estimatedFeeUsd,
              estimatedNetProfitUsd: calculation.estimatedNetProfitUsd
            };
            await recordTokenUsage({
              uid: user.uid, feature: capabilityName, providerId: provider.providerId, modelId: provider.modelId,
              usage, pricing: actualPricing, calculation, requestId: reservation.reservationId || crypto.randomUUID(),
              chargedCredits: creditsCharged, planCovered: reservation.planCovered === true, modality: "text"
            }).catch(() => notifyAdminOfIssue(globalThis.__bqEnv, "/ai/stream", 500, "token_usage_ledger_write_failed"));
          } else {
            await commitCredits(user.uid, reservation.reservationId, provider.providerId);
          }
          emit({
            type: "completed", providerId: provider.providerId, modelId: provider.modelId,
            creditsCharged, ...(tokenUsage ? { tokenUsage } : {})
          });
          controller.enqueue(encoder.encode("data: [DONE]\n\n"));
          controller.close();
        } catch (error) {
          await reverseCredits(user.uid, reservation.reservationId);
          await notifyAdminOfIssue(globalThis.__bqEnv, "/ai/stream", 500, error?.message || "stream_failed");
          emit({ type: "error", message: error?.safeError || "service_temporarily_unavailable" });
          controller.enqueue(encoder.encode("data: [DONE]\n\n"));
          controller.close();
        }
      }
    });
    return new Response(output, { status: 200, headers: { ...corsHeaders(request), "content-type": "text/event-stream", "cache-control": "no-cache" } });
  } catch (error) {
    await reverseCredits(user.uid, reservation.reservationId);
    throw error;
  }
}


async function permanentlyDeleteAccount(uid) {
  // Remove private File Box objects before the account identity is deleted.
  const fileBoxBeforeDeletion = await firestoreGet("fileBox/" + uid);
  for (const item of (Array.isArray(fileBoxBeforeDeletion?.items) ? fileBoxBeforeDeletion.items : [])) {
    if (item.objectPath) await deleteTrainingAssetObject(String(item.objectPath)).catch(() => {});
  }
  // Delete Auth first for the rest of the account-deletion operation; retries are safe.
  await deleteFirebaseAuthUser(uid);
  const cols = [
    "chatHistory", "fileBox", "fileBoxUploads", "businessTrainingAssets", "aiFeedback", "contactSalesRequests",
    "creditReservations", "creditLedger", "creditTransactions", "creditBalances",
    "billingReceipts", "payments", "playPurchases", "passkeys", "passkeyChallenges",
    "socialAccounts", "notificationTokens", "adminStepUp", "adminBiometricChallenges", "organizationProductionEvents", "aiJobs",
    "aiWorkflows", "aiAgents", "aiEvaluations", "aiPlugins", "aiActivity",
    "organizationScheduledTasks", "organizationWalletLedger", "organizationLiveSessions",
    "organizationProductionLiveSessions", "organizationWorkspaces", "learningSessions",
    "companionMessages", "companionCheckins", "companionProfiles", "companionSettings",
    "truthGuardChecks", "truthGuardSettings", "businessAdvancedAiJobs",
    "businessIntegrationConfigs", "businessWorkspaces"
  ];
  const serviceAccountJson = globalThis.__bqEnv?.BQ_FIREBASE_SERVICE_ACCOUNT_JSON;
  if (!serviceAccountJson) throw new Error("BQ_FIREBASE_SERVICE_ACCOUNT_JSON is not configured");
  const project = JSON.parse(serviceAccountJson).project_id;
  const fields = ["uid", "ownerUid"];
  for (const col of cols) {
    for (const field of fields) {
      let guard = 0;
      while (guard++ < 20) {
        const rows = await firestoreQuery(col, {
          filters: [{ field: { fieldPath: field }, op: "EQUAL", value: { stringValue: uid } }],
          limit: 100
        });
        if (!rows.length) break;
        for (const row of rows) {
          const name = String(row.__name || "");
          if (name) await firestoreDelete(name.replace("projects/" + project + "/databases/(default)/documents/", ""));
        }
        if (rows.length < 100) break;
      }
      const remaining = await firestoreQuery(col, {
        filters: [{ field: { fieldPath: field }, op: "EQUAL", value: { stringValue: uid } }],
        limit: 1
      });
      if (remaining.length) throw new Error("Account data cleanup incomplete for " + col);
    }
  }
  for (const [col, id] of [
    ["users", uid], ["creditBalances", uid], ["userConsents", uid], ["userMemory", uid],
    ["organizationWorkspaces", uid], ["organizationProduction", uid],
    ["organizationWallets", uid], ["organizationEntitlements", uid],
    ["organizationTestSessions", uid], ["entitlements", uid], ["userFeatureSettings", uid]
  ]) await firestoreDelete(col + "/" + id);
}

export default {
  async scheduled(controller, env) {
    configureFirebaseEnv(env);
    globalThis.__bqEnv = env;
    const now = Date.now();
    const pending = await firestoreQuery("userAccountStatus", {
      filters: [{ field: { fieldPath: "status" }, op: "EQUAL", value: { stringValue: "pending_deletion" } }],
      orderBy: { field: "scheduledDeletionAt", direction: "ASCENDING" },
      limit: 100
    });
    for (const account of pending) {
      const uid = String(account.uid || String(account.__name || "").split("/").pop() || "");
      if (!uid) continue;
      const statusPath = "userAccountStatus/" + uid;
      const latest = await firestoreGet(statusPath);
      const deadline = Date.parse(String(latest?.scheduledDeletionAt || ""));
      if (latest?.status !== "pending_deletion" || !Number.isFinite(deadline) || deadline > now) continue;
      try {
        // Compare-and-set against the document update time so cancellation cannot
        // race with the scheduler and be overwritten by a stale deletion job.
        const { __name: ignoredName, __updateTime: updateTime, ...latestData } = latest;
        if (!updateTime) throw new Error("deletion_status_update_time_missing");
        await firestoreCommit([{
          path: statusPath,
          data: { ...latestData, status: "deleting", deletionStartedAt: new Date(), updatedAt: new Date() },
          updateTime
        }]);
        await permanentlyDeleteAccount(uid);
        await firestoreSet(statusPath, { uid, status: "deleted", deletedAt: new Date(), updatedAt: new Date() }, true);
      } catch (error) {
        const current = await firestoreGet(statusPath);
        // A successful cancellation wins over a stale scheduler attempt.
        if (current?.status !== "pending_deletion" && current?.status !== "deleting") continue;
        await firestoreSet(statusPath, {
          uid,
          status: "pending_deletion",
          // Back off a failed deletion by 24 hours so a broken account does not
          // occupy the first page of the ordered queue and starve other accounts.
          scheduledDeletionAt: new Date(Date.now() + 24 * 60 * 60 * 1000),
          requestedAt: current.requestedAt || latest.requestedAt,
          lastDeletionAttemptAt: new Date(),
          lastDeletionError: String(error?.message || "deletion_failed").slice(0, 180),
          updatedAt: new Date()
        }, true);
      }
    }
    // Daily File Box expiry cleanup shares the existing scheduled handler with
    // account-deletion processing; do not replace or bypass deletion processing.
    try {
      const cursor = await firestoreGet("adminAdvancedOptions/fileBoxCleanupCursor") || {};
      let offset = Math.max(0, Math.floor(Number(cursor.offset || 0)));
      for (let page = 0; page < 5; page++) {
        const fileBoxRows = await firestoreQuery("fileBox", { limit:100, offset });
        if (!fileBoxRows.length) { offset = 0; break; }
        for (const row of fileBoxRows) {
          const uid = String(row.uid || "");
          if (!uid || !Array.isArray(row.items)) continue;
          const items = [];
          for (const item of row.items) {
            const expiry = Date.parse(String(item.expiresAt || ""));
            if (Number.isFinite(expiry) && expiry > Date.now()) {
              items.push(item);
            } else if (item?.objectPath) {
              // Expiry must remove the stored object as well as the Firestore index.
              await deleteTrainingAssetObject(String(item.objectPath)).catch(error => {
                console.error("file_box_object_expiry_failed", String(item.id || "").slice(0, 80), error?.message || "unknown");
              });
            }
          }
          if (items.length !== row.items.length) {
            await firestoreSet("fileBox/" + uid, {
              uid, items,
              retentionDays:[7,14,30,90].includes(Number(row.retentionDays)) ? Number(row.retentionDays) : 30,
              updatedAt:new Date()
            }, true);
          }
        }
        offset += fileBoxRows.length;
        if (fileBoxRows.length < 100) { offset = 0; break; }
      }
      await firestoreSet("adminAdvancedOptions/fileBoxCleanupCursor", { offset, updatedAt:new Date() }, true);
    } catch (error) {
      console.error("file_box_expiry_cleanup_failed", error?.message || "unknown");
    }
    // Remove abandoned upload sessions and any uploaded object after 24 hours.
    try {
      const staleBefore = Date.now() - 24 * 60 * 60 * 1000;
      for (let batch = 0; batch < 5; batch++) {
        const uploads = await firestoreQuery("fileBoxUploads", {
          filters: [{ field: { fieldPath: "uploadPending" }, op: "EQUAL", value: { booleanValue: true } }],
          orderBy: { field: "createdAt", direction: "ASCENDING" },
          limit: 100
        });
        if (!uploads.length) break;
        let removed = 0;
        for (const upload of uploads) {
          const created = Date.parse(String(upload.createdAt || ""));
          if (!Number.isFinite(created) || created > staleBefore) break;
          if (upload.objectPath) await deleteTrainingAssetObject(String(upload.objectPath)).catch(() => {});
          const id = String(upload.id || String(upload.__name || "").split("/").pop() || "");
          if (id) {
            await firestoreDelete("fileBoxUploads/" + id).catch(() => {});
            removed++;
          }
        }
        if (removed === 0) break;
      }
    } catch (error) {
      console.error("file_box_upload_cleanup_failed", error?.message || "unknown");
    }
  },
  async fetch(request, env) {
    configureFirebaseEnv(env);
    globalThis.__bqEnv = env;
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders(request) });
    if (url.pathname === "/" || url.pathname === "/health") return json({service:"BQ Sqwad AI", status:"ok", backend:"cloudflare-worker"});
    try {
      // The Worker is the sole active backend. Never proxy requests to retired Firebase Functions.
      // Every admin route except the identity/session bootstrap endpoints requires a fresh,
      // server-verified 30-minute step-up session.
      const adminBootstrapRoutes = new Set([
        "GET /admin/access",
        "GET /admin/security/status",
        "GET /admin/security/session",
        "POST /admin/security/unlock",
        "GET /admin/security/biometric",
        "POST /admin/security/biometric/challenge",
        "POST /admin/security/biometric/verify"
      ]);
      if (url.pathname.startsWith("/admin/")) {
        const adminIdentity = await verifyFirebaseUser(request);
        assertSuperAdminIdentity(adminIdentity, env);
        const routeKey = request.method + " " + url.pathname;
        const securityConfig = await securityStatusNative();
        const initialSecuritySetup = !securityConfig.passwordConfigured && !securityConfig.totpConfigured;
        const initialSetupRoute = initialSecuritySetup && [
          "POST /admin/security/password",
          "POST /admin/security/totp/start",
          "POST /admin/security/totp/confirm"
        ].includes(routeKey);
        const adminPolicy = await firestoreGet("adminAdvancedOptions/admin_security_policy").catch(() => null);
        const unlockRequired = isAdminUnlockRequired(adminPolicy);
        if (unlockRequired && !adminBootstrapRoutes.has(routeKey) && !initialSetupRoute) {
          await requireAdminSessionNative(adminIdentity.uid);
        }
        // Record admin API actions without request bodies, credentials, or provider secrets.
        const auditExclusions = new Set([
          "GET /admin/access",
          "GET /admin/security/status",
          "GET /admin/security/session",
          "POST /admin/security/unlock"
        ]);
        if (!auditExclusions.has(routeKey)) {
          await firestoreCreate("adminAuditLogs/" + crypto.randomUUID(), {
            uid: adminIdentity.uid,
            action: "admin_api_request",
            method: request.method,
            path: url.pathname,
            createdAt: new Date()
          });
        }
      }
      const protectedPaths = new Set(["/ai", "/ai/stream", "/ai/vision", "/web-search", "/credits", "/entitlements", "/entitlement"]);
      if (protectedPaths.has(url.pathname) && !request.headers.get("Authorization")) {
        return json({ error: "auth_required" }, 401);
      }

      if (url.pathname === "/ai" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        return await ai(request, user, await readBody(request));
      }
      if (url.pathname === "/ai/transcribe" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        enforceRateLimit(user.uid);
        await requireConsent(user.uid);
        const configDocument = await firestoreGet("adminAdvancedOptions/dictation_pricing") || {};
        const pricing = normalizeLivePricing(configDocument.config || configDocument, {}, "dictation_pricing");
        if (configDocument.enabled === false || configDocument.killSwitch === true || pricing.enabled === false || pricing.killSwitch === true) {
          throw Object.assign(new Error("dictation_disabled"), { httpStatus: 403, safeError: "dictation_disabled" });
        }
        const audioBytes = new Uint8Array(await request.arrayBuffer());
        if (audioBytes.byteLength < 44 || audioBytes.byteLength > 4000000) {
          throw Object.assign(new Error("invalid_dictation_audio_size"), { httpStatus: 400, safeError: "invalid_dictation_audio" });
        }
        const headerText = String.fromCharCode(...audioBytes.slice(0, 12));
        if (!headerText.startsWith("RIFF") || !headerText.includes("WAVE")) {
          throw Object.assign(new Error("invalid_dictation_wav"), { httpStatus: 400, safeError: "invalid_dictation_audio" });
        }
        const headerDuration = Number(request.headers.get("X-BQ-Audio-Duration-Seconds") || 0);
        const durationSeconds = Math.min(120, Math.max(1, Number.isFinite(headerDuration) && headerDuration > 0 ? headerDuration : Math.ceil((audioBytes.byteLength - 44) / 32000)));
        const rate = Math.max(0, Number(pricing.creditsPerMinute || 0));
        const startFee = Math.max(0, Number(pricing.startFeeCredits || 0));
        // Bill transcription proportionally to the actual recorded duration;
        // a short clip must not be rounded up to a whole minute.
        const billedMinutes = durationSeconds / 60;
        const chargeAmount = startFee + rate * billedMinutes;
        if (chargeAmount > 0 && !isSuperAdmin(user, env)) {
          const balance = canonicalUserBalance(await readCanonicalUserBalance(user.uid));
          const minimum = Math.max(Number(pricing.minimumBalanceToStart || 0), chargeAmount);
          if (!balance || balance.total < minimum) {
            throw Object.assign(new Error("out_of_credits"), { httpStatus: 402, safeError: "out_of_credits" });
          }
        }
        let reservation = null;
        if (chargeAmount > 0) reservation = await chargeCredits(user.uid, chargeAmount, "dictation", user);
        try {
          if (!env?.OPENAI_API_KEY) throw Object.assign(new Error("provider_key_missing"), { httpStatus: 503, safeError: "provider_key_missing" });
          const transcribeWithModel = async (model) => {
            const form = new FormData();
            form.append("file", new Blob([audioBytes], { type: "audio/wav" }), "dictation.wav");
            form.append("model", model);
            return fetch("https://api.openai.com/v1/audio/transcriptions", {
              method: "POST",
              headers: { Authorization: "Bearer " + String(env.OPENAI_API_KEY) },
              body: form
            });
          };
          let model = String(pricing.transcriptionModel || "gpt-4o-mini-transcribe").slice(0, 100);
          let response = await transcribeWithModel(model);
          let raw = await response.text();
          let transcription = {};
          try { transcription = raw ? JSON.parse(raw) : {}; } catch {}
          if (!response.ok && [400, 404, 422].includes(response.status) && model !== "whisper-1") {
            const providerCode = String(transcription?.error?.code || transcription?.error?.type || "model_rejected").replace(/[^a-zA-Z0-9_.-]/g, "").slice(0, 80);
            await notifyAdminOfIssue(env, url.pathname, 502, "dictation_model_rejected:" + model + ":" + providerCode);
            model = "whisper-1";
            response = await transcribeWithModel(model);
            raw = await response.text();
            transcription = {};
            try { transcription = raw ? JSON.parse(raw) : {}; } catch {}
          }
          if (!response.ok) {
            const providerCode = String(transcription?.error?.code || transcription?.error?.type || "provider_rejected").replace(/[^a-zA-Z0-9_.-]/g, "").slice(0, 80);
            await notifyAdminOfIssue(env, url.pathname, 502, "dictation_transcription_failed:" + response.status + ":" + providerCode);
            throw Object.assign(new Error("transcription_failed"), { httpStatus: 502, safeError: "transcription_failed", providerStatus: response.status });
          }
          const text = String(transcription?.text || "").trim().slice(0, 12000);
          if (!text) throw Object.assign(new Error("no_speech_detected"), { httpStatus: 422, safeError: "no_speech_detected" });
          if (reservation?.reservationId) await commitCredits(user.uid, reservation.reservationId, "openai_transcription");
          return json({ ok: true, text, charged: chargeAmount, free: chargeAmount === 0 });
        } catch (error) {
          if (reservation?.reservationId) await reverseCredits(user.uid, reservation.reservationId).catch(() => {});
          throw error;
        }
      }
      if (url.pathname === "/ai/vision" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        return await vision(request, user, await readBody(request));
      }
      if (url.pathname === "/ai/stream" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        return await stream(request, user, await readBody(request));
      }
      if (url.pathname === "/play/catalog" && request.method === "GET") {
        await verifyFirebaseUser(request);
        const firestore = { get: firestoreGet, commit: firestoreCommit };
        return json(await playCatalog(firestore, url.searchParams.get("context")));
      }

      if (url.pathname === "/ai/models" && request.method === "GET") {
        return json(await listAiModels({ admin: false }));
      }

      // Public plan/features catalogue: browsing must work before sign-in.
      // Purchase verification, entitlements and checkout remain authenticated below.
      if (url.pathname === "/native/catalog" && request.method === "GET") {
        return json(await nativeCatalog());
      }

      if (url.pathname === "/play/verify" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const firestore = { get: firestoreGet, commit: firestoreCommit };
        return json(await verifyPlayPurchase(env, firestore, {
          uid: user.uid,
          productId: String(body.productId || ""),
          purchaseToken: String(body.purchaseToken || ""),
          obfuscatedAccountId: String(body.obfuscatedAccountId || ""),
          requestedBasePlanId: String(body.basePlanId || ""),
          requestedOfferId: String(body.offerId || "")
        }));
      }

      if (url.pathname === "/credits" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        if (isSuperAdmin(user, env)) return json({ available: 0, reserved: 0, adminUnlimited: true, updatedAt: null, source: "admin_unlimited" });
        await applyConfiguredCreditTrial(user.uid);
        await applyConfiguredDailyFreeCredits(user.uid);
        // The website's Firebase backend is the canonical credit authority.
        // Both website and Android read the same users/{uid} balance.
        const profile = await readCanonicalUserBalance(user.uid);
        const balance = canonicalUserBalance(profile);
        if (!balance) throw new Error("Account not found.");
        return json({
          available: balance.total,
          reserved: 0,
          permanent: balance.permanent,
          subscription: balance.subscription,
          trial: balance.trial,
          dailyFreeCredits: balance.daily,
          dailyFreeCreditsDate: balance.dailyDate,
          dailyFreeCreditsTimeZone: balance.dailyFreeCreditsTimeZone,
          dailyFreeCreditsRenewalTime: balance.dailyFreeCreditsRenewalTime,
          plan: balance.plan,
          subscriptionExpiresAt: balance.subscriptionExpiresAt,
          trialExpiresAt: balance.trialExpiresAt,
          updatedAt: profile.updatedAt || null,
          source: "firebase_users"
        });
      }


      // Android reads these entitlement routes during access checks and catalog refresh.
      // Keep the response contract aligned with the retired Functions implementation.
      if (url.pathname === "/entitlement" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        const entitlement = await firestoreGet("entitlements/" + user.uid);
        return json(entitlement ? { exists: true, ...entitlement } : { exists: false });
      }

      if (url.pathname === "/entitlements" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        const [primary, playRows] = await Promise.all([
          firestoreGet("entitlements/" + user.uid),
          firestoreQuery("playEntitlements", {
            filters: [{ field: { fieldPath: "uid" }, op: "EQUAL", value: { stringValue: user.uid } }],
            limit: 100
          })
        ]);
        const rows = new Map();
        if (primary) {
          const key = String(primary.productId || primary.planId || "primary");
          rows.set(key, { ...primary, id: key });
        }
        for (const row of playRows) {
          const key = String(row.productId || row.planId || String(row.__name || "").split("/").pop());
          rows.set(key, { ...row, id: key });
        }
        return json({
          items: Array.from(rows.values()).map(row => ({
            id: String(row.id || ""),
            planId: String(row.planId || ""),
            productId: String(row.productId || ""),
            product: String(row.product || row.productId || ""),
            basePlanId: row.basePlanId ? String(row.basePlanId) : null,
            state: String(row.state || ""),
            renewalState: String(row.renewalState || ""),
            source: String(row.source || ""),
            startDate: row.startDate || null,
            endDate: row.endDate || null,
            updatedAt: row.updatedAt || null
          }))
        });
      }

      if (url.pathname === "/history" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        const limit = Math.min(100, Math.max(1, Number(url.searchParams.get("limit") || 50)));
        const docs = await firestoreQuery("chatHistory", { filters: [{ field: { fieldPath: "uid" }, op: "EQUAL", value: { stringValue: user.uid } }], limit: 100 });
        docs.sort((a,b) => String(b.updatedAt || "").localeCompare(String(a.updatedAt || "")));
        return json({ items: docs.slice(0, limit).map(item => ({
          id: String(item.__name || "").split("/").pop(),
          title: String(item.title || "New chat"),
          updatedAt: String(item.updatedAt || ""),
          messageCount: Array.isArray(item.messages) ? item.messages.length : 0
        })) });
      }

      if (url.pathname.startsWith("/history/") && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        const id = decodeURIComponent(url.pathname.slice("/history/".length)).trim();
        if (!id || id.includes("/")) return json({ error: "invalid_history_id" }, 400);
        const item = await firestoreGet("chatHistory/" + id);
        if (!item || String(item.uid || "") !== user.uid) return json({ error: "history_not_found" }, 404);
        return json({ id, title: String(item.title || "New chat"), messages: Array.isArray(item.messages) ? item.messages : [] });
      }

      if (url.pathname === "/history" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const requestedId = String(body.id || "").trim();
        const id = requestedId || crypto.randomUUID();
        if (!/^[A-Za-z0-9_-]{1,160}$/.test(id)) return json({ error: "invalid_history_id" }, 400);
        const existing = requestedId ? await firestoreGet("chatHistory/" + id) : null;
        if (existing && String(existing.uid || "") !== user.uid) return json({ error: "history_forbidden" }, 403);
        const rawMessages = Array.isArray(body.messages) ? body.messages : [];
        const messages = normalizeHistoryMessages(rawMessages);
        const title = String(body.title || messages.find(item => item.mine)?.text || "New chat").trim().slice(0, 180);
        await firestoreSet("chatHistory/" + id, { uid:user.uid, title, messages, createdAt:existing?.createdAt || new Date(), updatedAt:new Date() }, true);
        return json({ ok:true, id, title, messageCount:messages.length });
      }

      if (url.pathname.startsWith("/history/") && request.method === "DELETE") {
        const user = await verifyFirebaseUser(request);
        const id = decodeURIComponent(url.pathname.slice("/history/".length)).trim();
        if (!id || id.includes("/")) return json({ error: "invalid_history_id" }, 400);
        const item = await firestoreGet("chatHistory/" + id);
        if (!item || String(item.uid || "") !== user.uid) return json({ error: "history_not_found" }, 404);
        await firestoreDelete("chatHistory/" + id);
        return json({ ok:true });
      }


      if (url.pathname === "/chat-background/charge" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const uploadId = String(body.uploadId || "").trim();
        if (!/^[A-Za-z0-9_-]{8,100}$/.test(uploadId)) return json({ error: "invalid_upload_id" }, 400);
        const receiptPath = "chatBackgroundCharges/" + user.uid + "_" + uploadId;
        const existing = await firestoreGet(receiptPath);
        if (existing?.status === "charged" && String(existing.uid || "") === user.uid) {
          return json({ ok: true, alreadyCharged: true, creditsCharged: 2 });
        }
        if (existing?.status === "pending") return json({ error: "charge_in_progress" }, 409);
        try {
          await firestoreCreate(receiptPath, { uid: user.uid, uploadId, status: "pending", createdAt: new Date() });
        } catch {
          const concurrent = await firestoreGet(receiptPath);
          if (concurrent?.status === "charged" && String(concurrent.uid || "") === user.uid) return json({ ok: true, alreadyCharged: true, creditsCharged: 2 });
          return json({ error: "charge_in_progress" }, 409);
        }
        let reservation = null;
        try {
          reservation = await chargeCredits(user.uid, 2, "chat_background_upload", user);
          await commitCredits(user.uid, reservation.reservationId, "chat_background_upload");
          await firestoreSet(receiptPath, { uid: user.uid, uploadId, status: "charged", creditsCharged: 2, chargedAt: new Date() }, true);
          return json({ ok: true, creditsCharged: 2 });
        } catch (error) {
          if (reservation?.reservationId) await reverseCredits(user.uid, reservation.reservationId).catch(() => {});
          await firestoreSet(receiptPath, { uid: user.uid, uploadId, status: "failed", updatedAt: new Date() }, true).catch(() => {});
          throw error;
        }
      }
      if (url.pathname === "/file-box/upload-url" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const title = String(body.title || "").trim().slice(0, 180);
        const fileName = String(body.fileName || "").trim().slice(0, 160);
        const contentType = String(body.contentType || "application/octet-stream").trim().slice(0, 120);
        const size = Math.max(0, Number(body.size || 0));
        if (!title || !fileName || !size) return json({ error: "file_metadata_required" }, 400);
        if (size > 25 * 1024 * 1024) return json({ error: "file_too_large" }, 413);
        const id = crypto.randomUUID();
        const safeName = fileName.replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 120) || "upload";
        const objectPath = "file-box/" + user.uid + "/" + id + "-" + safeName;
        await firestoreSet("fileBoxUploads/" + id, {
          id, uid: user.uid, title, fileName: safeName, contentType, size, objectPath,
          uploadPending: true, createdAt: new Date().toISOString()
        }, false);
        return json({ ok: true, id });
      }
      if (url.pathname === "/file-box/upload" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const id = String(url.searchParams.get("id") || "").trim();
        const pending = await firestoreGet("fileBoxUploads/" + id);
        if (!pending || String(pending.uid || "") !== user.uid) return json({ error: "file_upload_not_found" }, 404);
        if (pending.uploadPending !== true) return json({ error: "file_upload_already_finalized" }, 409);
        const declaredSize = Number(request.headers.get("content-length") || 0);
        if (!Number.isSafeInteger(declaredSize) || declaredSize <= 0 || declaredSize > 25 * 1024 * 1024 || declaredSize !== Number(pending.size)) {
          return json({ error: "file_upload_size_mismatch" }, 400);
        }
        const sha1 = String(request.headers.get("x-bq-content-sha1") || "").toLowerCase();
        if (!/^[a-f0-9]{40}$/.test(sha1)) return json({ error: "file_upload_checksum_required" }, 400);
        const bytes = await request.arrayBuffer();
        if (bytes.byteLength !== declaredSize) return json({ error: "file_upload_size_mismatch" }, 400);
        const stored = await uploadTrainingAssetObject({
          objectPath: String(pending.objectPath || ""),
          contentType: String(pending.contentType || "application/octet-stream"),
          sha1,
          bytes
        });
        if (Number(stored.size) !== Number(pending.size)) return json({ error: "file_upload_size_mismatch" }, 409);
        return json({ ok: true, id, size: stored.size });
      }
      if (url.pathname === "/file-box/finalize" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const id = String(body.id || "").trim();
        const pending = await firestoreGet("fileBoxUploads/" + id);
        if (!pending || String(pending.uid || "") !== user.uid) return json({ error: "file_upload_not_found" }, 404);
        if (pending.uploadPending !== true) return json({ error: "file_upload_already_finalized" }, 409);
        const object = await inspectTrainingAssetObject(String(pending.objectPath || ""));
        if (!object || Number(object.size) !== Number(pending.size)) return json({ error: "file_upload_not_complete" }, 409);
        const doc = await firestoreGet("fileBox/" + user.uid) || {};
        const globalRetention = await firestoreGet("adminAdvancedOptions/fileBoxRetention") || {};
        const defaultRetention = [7,14,30,90].includes(Number(globalRetention.defaultRetentionDays)) ? Number(globalRetention.defaultRetentionDays) : 30;
        const retentionDays = [7,14,30,90].includes(Number(doc.retentionDays)) ? Number(doc.retentionDays) : defaultRetention;
        const now = new Date();
        const items = (Array.isArray(doc.items) ? doc.items : []).filter(item => Date.parse(String(item.expiresAt || "")) > now.getTime());
        const item = {
          id: pending.id, uid: user.uid, title: String(pending.title || pending.fileName || "Uploaded file"),
          fileName: String(pending.fileName || "upload"), mimeType: String(pending.contentType || object.contentType || "application/octet-stream"),
          size: Number(object.size), objectPath: String(pending.objectPath), content: "",
          createdAt: now.toISOString(), expiresAt: new Date(now.getTime() + retentionDays * 86400000).toISOString(), retentionDays
        };
        items.push(item);
        await firestoreSet("fileBox/" + user.uid, { uid: user.uid, items: items.slice(-20), retentionDays, updatedAt: now }, true);
        await firestoreSet("fileBoxUploads/" + id, { uploadPending: false, finalizedAt: now.toISOString() }, true);
        return json({ ok: true, item: { ...item, content: undefined }, count: Math.min(items.length, 20) });
      }
      if (url.pathname === "/file-box/download" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        const id = String(url.searchParams.get("id") || "").trim();
        const doc = await firestoreGet("fileBox/" + user.uid) || {};
        const item = (Array.isArray(doc.items) ? doc.items : []).find(entry => String(entry.id || "") === id);
        if (!item || !item.objectPath) return json({ error: "stored_file_not_found" }, 404);
        if (Date.parse(String(item.expiresAt || "")) <= Date.now()) return json({ error: "stored_file_expired" }, 410);
        const stored = await downloadTrainingAssetObject(String(item.objectPath));
        const safeName = String(item.fileName || item.title || "download").replace(/[\r\n"]/g, "_").slice(0, 160);
        return new Response(stored.response.body, { status: 200, headers: {
          ...corsHeaders(request), "content-type": stored.contentType,
          "content-disposition": "attachment; filename=\"" + safeName + "\"",
          "content-length": String(stored.size), "cache-control": "private, no-store"
        }});
      }

      if (url.pathname === "/file-box" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        const doc = await firestoreGet("fileBox/" + user.uid) || {};
        const globalRetention = await firestoreGet("adminAdvancedOptions/fileBoxRetention") || {};
        const defaultRetention = [7,14,30,90].includes(Number(globalRetention.defaultRetentionDays)) ? Number(globalRetention.defaultRetentionDays) : 30;
        const now = Date.now();
        const existingItems = Array.isArray(doc.items) ? doc.items : [];
        const expiredItems = existingItems.filter(item => !Number.isFinite(Date.parse(String(item.expiresAt || ""))) || Date.parse(String(item.expiresAt || "")) <= now);
        for (const expired of expiredItems) if (expired.objectPath) await deleteTrainingAssetObject(String(expired.objectPath)).catch(() => {});
        const items = existingItems.filter(item => {
          const expiry = Date.parse(String(item.expiresAt || ""));
          return Number.isFinite(expiry) && expiry > now;
        }).slice(-100);
        const retentionDays = [7,14,30,90].includes(Number(doc.retentionDays)) ? Number(doc.retentionDays) : defaultRetention;
        if (items.length !== (Array.isArray(doc.items) ? doc.items.length : 0)) await firestoreSet("fileBox/" + user.uid, { uid:user.uid, items, retentionDays, updatedAt:new Date() }, true);
        return json({ items, retentionDays, count:items.length });
      }
      if (url.pathname === "/file-box/settings" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const retentionDays = Number(body.retentionDays);
        if (![7,14,30,90].includes(retentionDays)) return json({ error:"invalid_retention_days" }, 400);
        const current = await firestoreGet("fileBox/" + user.uid) || {};
        const items = (Array.isArray(current.items) ? current.items : []).filter(item => {
          const expiry = Date.parse(String(item.expiresAt || ""));
          return Number.isFinite(expiry) && expiry > Date.now();
        }).map(item => ({ ...item, expiresAt:new Date(Date.parse(String(item.createdAt || new Date().toISOString())) + retentionDays*86400000).toISOString(), retentionDays }));
        await firestoreSet("fileBox/" + user.uid, { uid:user.uid, items, retentionDays, updatedAt:new Date() }, true);
        return json({ ok:true, retentionDays, count:items.length });
      }
      if (url.pathname === "/file-box" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const title = String(body.title || "").trim().slice(0,180);
        const content = String(body.content || "");
        const mimeType = String(body.mimeType || "text/plain").slice(0,100);
        if (!title || !content.trim()) return json({ error:"file_title_and_content_required" }, 400);
        if (content.length > 25000) return json({ error:"file_content_too_large" }, 413);
        const doc = await firestoreGet("fileBox/" + user.uid) || {};
        const globalRetention = await firestoreGet("adminAdvancedOptions/fileBoxRetention") || {};
        const defaultRetention = [7,14,30,90].includes(Number(globalRetention.defaultRetentionDays)) ? Number(globalRetention.defaultRetentionDays) : 30;
        const retentionDays = [7,14,30,90].includes(Number(doc.retentionDays)) ? Number(doc.retentionDays) : defaultRetention;
        const now = new Date();
        const items = (Array.isArray(doc.items) ? doc.items : []).filter(item => Date.parse(String(item.expiresAt || "")) > now.getTime());
        const item = { id:crypto.randomUUID(), uid:user.uid, title, mimeType, content, createdAt:now.toISOString(), expiresAt:new Date(now.getTime()+retentionDays*86400000).toISOString(), retentionDays, size:content.length };
        items.push(item);
        await firestoreSet("fileBox/" + user.uid, { uid:user.uid, items:items.slice(-20), retentionDays, updatedAt:now }, true);
        return json({ ok:true, item:{ ...item, content:undefined }, count:Math.min(items.length,20) });
      }
      if (url.pathname === "/file-box" && request.method === "DELETE") {
        const user = await verifyFirebaseUser(request);
        const id = String(url.searchParams.get("id") || "").trim();
        if (!id) return json({ error:"file_id_required" }, 400);
        const doc = await firestoreGet("fileBox/" + user.uid) || {};
        const existingItems = Array.isArray(doc.items) ? doc.items : [];
        const removed = existingItems.find(item => String(item.id || "") === id);
        if (removed?.objectPath) await deleteTrainingAssetObject(String(removed.objectPath));
        const items = existingItems.filter(item => item.id !== id);
        await firestoreSet("fileBox/" + user.uid, { ...doc, uid:user.uid, items, updatedAt:new Date() }, true);
        return json({ ok:true, count:items.length });
      }
      if (url.pathname === "/projects" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        const doc = await firestoreGet("userProjects/" + user.uid) || {};
        return json({ items:(Array.isArray(doc.items) ? doc.items : []).slice(-100) });
      }
      if (url.pathname === "/projects" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const title = String(body.title || "").trim().slice(0,160);
        const description = String(body.description || "").trim().slice(0,1500);
        const content = String(body.content || "").slice(0,5000);
        if (!title) return json({ error:"project_title_required" }, 400);
        const doc = await firestoreGet("userProjects/" + user.uid) || {};
        const items = Array.isArray(doc.items) ? doc.items : [];
        const requestedId = String(body.id || "").trim();
        const index = requestedId ? items.findIndex(item => item.id === requestedId) : -1;
        const now = new Date().toISOString();
        const item = { id:index >= 0 ? requestedId : crypto.randomUUID(), title, description, content, createdAt:index >= 0 ? items[index].createdAt : now, updatedAt:now };
        if (index >= 0) items[index] = item; else items.push(item);
        await firestoreSet("userProjects/" + user.uid, { uid:user.uid, items:items.slice(-100), updatedAt:new Date() }, true);
        return json({ ok:true, item });
      }
      if (url.pathname === "/projects" && request.method === "DELETE") {
        const user = await verifyFirebaseUser(request);
        const id = String(url.searchParams.get("id") || "").trim();
        if (!id) return json({ error:"project_id_required" }, 400);
        const doc = await firestoreGet("userProjects/" + user.uid) || {};
        const items = (Array.isArray(doc.items) ? doc.items : []).filter(item => item.id !== id);
        await firestoreSet("userProjects/" + user.uid, { uid:user.uid, items, updatedAt:new Date() }, true);
        return json({ ok:true, count:items.length });
      }

      if (url.pathname === "/memory" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        const memory = await firestoreGet("userMemory/" + user.uid);
        return json({ enabled:memory?.enabled !== false, items:Array.isArray(memory?.items) ? memory.items.slice(-80) : [] });
      }
      if (url.pathname === "/memory" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const value = String(body.text || "").trim().slice(0, 4000);
        if (!value) return json({ error:"memory_text_required" }, 400);
        const memory = await firestoreGet("userMemory/" + user.uid);
        const items = Array.isArray(memory?.items) ? memory.items.slice(-199) : [];
        const item = { id:crypto.randomUUID(), text:value, source:String(body.source || "chat").slice(0,40), createdAt:new Date() };
        items.push(item);
        await firestoreSet("userMemory/" + user.uid, { uid:user.uid, enabled:memory?.enabled !== false, items, updatedAt:new Date() }, true);
        return json({ ok:true, item });
      }
      if (url.pathname === "/memory/settings" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const enabled = body.enabled === true;
        await firestoreSet("userMemory/" + user.uid, { uid:user.uid, enabled, updatedAt:new Date() }, true);
        return json({ enabled });
      }
      if (url.pathname === "/memory" && request.method === "DELETE") {
        const user = await verifyFirebaseUser(request);
        await firestoreSet("userMemory/" + user.uid, { uid:user.uid, enabled:true, items:[], updatedAt:new Date() }, true);
        return json({ ok:true, items:[] });
      }
      if (url.pathname === "/feature-settings" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        const snap = await firestoreGet("userFeatureSettings/" + user.uid);
        return json({ uid:user.uid, settings:snap?.settings || {}, exists:Boolean(snap) });
      }
      if (url.pathname === "/feature-settings" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const workspace = String(body.workspaceId || "global").slice(0,80);
        const allowed = new Set(["web_search","memory","notifications","history","auto_run","streaming","voice_responses","save_files","personalization","background_jobs","camera_enhance","companion_memory","truthguard_sources","chat_history","auto_save","voice_replies","notifications_enabled","text_scale"]);
        const incoming = body.settings && typeof body.settings === "object" ? body.settings : {};
        const cleaned = {};
        for (const key of allowed) { if (typeof incoming[key] === "boolean") cleaned[key] = incoming[key]; else if (key === "text_scale" && Number.isFinite(Number(incoming[key]))) cleaned[key] = Math.max(0.85, Math.min(1.4, Number(incoming[key]))); }
        const snap = await firestoreGet("userFeatureSettings/" + user.uid);
        const current = snap?.settings || {};
        const next = { ...current, [workspace]: { ...(current[workspace] || {}), ...cleaned } };
        await firestoreSet("userFeatureSettings/" + user.uid, { uid:user.uid, settings:next, updatedAt:new Date() }, true);
        return json({ uid:user.uid, settings:next });
      }
      if (url.pathname === "/feedback" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const message = String(body.message || "");
        const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(message));
        const messageHash = Array.from(new Uint8Array(hash)).map(x => x.toString(16).padStart(2,"0")).join("");
        const rawComment = String(body.comment || "").slice(0,1000);
        const sanitizedComment = rawComment
          .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, "[email redacted]")
          .replace(/\b\+?\d[\d\s().-]{7,}\d\b/g, "[phone redacted]")
          .replace(/\b(?:sk-[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{20,}|Bearer\s+\S+)\b/gi, "[credential redacted]");
        await firestoreCreate("aiFeedback/" + crypto.randomUUID(), {
          uid:user.uid, messageId:body.messageId == null ? null : String(body.messageId).slice(0,120),
          category:String(body.category || "general").slice(0,40),
          sentiment:body.sentiment == null ? null : String(body.sentiment).slice(0,20),
          reason:body.reason == null ? null : String(body.reason).slice(0,80),
          comment:sanitizedComment || null,
          messageHash, createdAt:new Date()
        });
        return json({ ok:true });
      }


      if (url.pathname === "/notifications/token" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const token = String(body.token || "").trim();
        if (!token || token.length > 4096) return json({ error:"notification_token_required" }, 400);
        const platform = String(body.platform || "android").slice(0,40);
        const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
        const hash = Array.from(new Uint8Array(digest)).map(x => x.toString(16).padStart(2,"0")).join("");
        await firestoreSet("notificationTokens/" + user.uid + "_" + hash, { uid:user.uid, token, tokenHash:hash, platform, enabled:true, updatedAt:new Date() }, true);
        return json({ ok:true, registered:true });
      }
      if (url.pathname === "/notifications/token" && request.method === "DELETE") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const token = String(body.token || "").trim();
        if (!token) return json({ error:"notification_token_required" }, 400);
        const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
        const hash = Array.from(new Uint8Array(digest)).map(x => x.toString(16).padStart(2,"0")).join("");
        await firestoreDelete("notificationTokens/" + user.uid + "_" + hash);
        return json({ ok:true, removed:true });
      }

      if (url.pathname === "/issues/report" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        enforceRateLimit(user.uid);
        const body = await readBody(request);
        const requestedRoute = String(body.route || "/live-voice").slice(0, 80);
        const allowedRoutes = new Set(["/live-voice", "/live-camera", "/screen-share", "/ai/stream", "/ai/vision"]);
        if (!allowedRoutes.has(requestedRoute)) return json({ error: "invalid_issue_route" }, 400);
        const timelineStatus = ["failed", "short", "error", "stopped"].includes(String(body.status || "").toLowerCase())
          ? String(body.status).toLowerCase() : "failed";
        const numericStatus = timelineStatus === "failed" || timelineStatus === "error" ? 500 : 200;
        const code = String(body.code || "client_runtime_issue").replace(/[^a-zA-Z0-9_.-]/g, "").slice(0, 80) || "client_runtime_issue";
        await notifyAdminOfIssue(env, requestedRoute, numericStatus, code, "", timelineStatus);
        return json({ ok: true, received: true });
      }

      if (url.pathname === "/admin/credits/grant" && request.method === "POST") {
        const admin = await verifyFirebaseUser(request);
        if (!isSuperAdmin(admin, env)) throw new Error("Admin access required");
        const body = await readBody(request);
        const email = String(body.email || "").trim().toLowerCase();
        const amount = Number(body.amount);
        const reason = String(body.reason || "admin_grant").trim().slice(0, 200);
        if (!email || !email.includes("@") || !Number.isFinite(amount) || amount <= 0 || amount > 1000000) {
          return json({ error: "valid_email_and_credit_amount_required" }, 400);
        }
        const matches = await firestoreQuery("users", {
          filters: [{ field: { fieldPath: "email" }, op: "EQUAL", value: { stringValue: email } }],
          limit: 1
        });
        const target = matches[0];
        const targetUid = String(target?.uid || String(target?.__name || "").split("/").pop() || "");
        if (!targetUid) return json({ error: "credit_recipient_not_found" }, 404);
        const profile = await firestoreGet("users/" + targetUid);
        if (!profile) return json({ error: "credit_recipient_not_found" }, 404);
        const oldBalance = canonicalUserBalance(profile);
        const nextPermanent = oldBalance.permanent + amount;
        const legacy = await firestoreGet("creditBalances/" + targetUid);
        const entryId = crypto.randomUUID();
        await firestoreCommit([
          { path: "users/" + targetUid, data: { credits: nextPermanent, updatedAt: new Date() }, updateTime: profile.__updateTime },
          { path: "creditBalances/" + targetUid, data: { uid: targetUid, available: nextPermanent + oldBalance.subscription, reserved: Math.max(0, Number(legacy?.reserved || 0)), updatedAt: new Date() }, merge: true },
          { path: "creditLedger/" + entryId, data: { uid: targetUid, capability: "admin_grant", amount, kind: "admin_grant", reason, actorUid: admin.uid, createdAt: new Date() }, exists: false }
        ]);
        return json({ ok: true, uid: targetUid, email, amount, available: nextPermanent + oldBalance.subscription });
      }

      if (url.pathname === "/admin/training/contributions" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw Object.assign(new Error("Admin access required"), { httpStatus:403 });
        const [contributions, consentRows, auditRows] = await Promise.all([
          firestoreQuery("consented_contributions", { orderBy:{field:"createdAt",direction:"DESCENDING"}, limit:200 }).catch(() => []),
          firestoreQuery("userConsents", { limit:500 }).catch(() => []),
          firestoreQuery("trainingConsentAudit", { orderBy:{field:"createdAt",direction:"DESCENDING"}, limit:200 }).catch(() => [])
        ]);
        return json({
          contributions:contributions.map(x => ({
            id:String(x.id || String(x.__name || "").split("/").pop()),
            userId:String(x.userId || ""), dataType:String(x.dataType || "chat"),
            chatId:x.chatId || null, fileId:x.fileId || null, workspace:String(x.workspace || ""),
            status:String(x.status || "pending_review"), createdAt:x.createdAt || null,
            contentDeleted:x.contentDeleted === true
          })),
          consentedUsers:consentRows.filter(x => x.trainingConsent === true).length,
          contributionCount:contributions.filter(x => x.status !== "withdrawn").length,
          audit:auditRows.map(x => ({userId:String(x.userId || ""),action:String(x.action || ""),contributionId:x.contributionId || null,createdAt:x.createdAt || null,deletedCount:Number(x.deletedCount || 0)})),
          checkedAt:new Date().toISOString()
        });
      }

      if (url.pathname === "/admin/feedback" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw Object.assign(new Error("Admin access required"), { httpStatus:403 });
        const rows = await firestoreQuery("aiFeedback", {
          orderBy:{field:"createdAt",direction:"DESCENDING"},
          limit:250
        }).catch(() => []);
        const items = rows.map(x => ({
          id:String(x.id || String(x.__name || "").split("/").pop()).slice(0,120),
          userId:String(x.uid || "").slice(0,128),
          messageId:x.messageId == null ? null : String(x.messageId).slice(0,120),
          category:String(x.category || "general").slice(0,40),
          sentiment:x.sentiment == null ? null : String(x.sentiment).slice(0,20),
          reason:x.reason == null ? null : String(x.reason).slice(0,80),
          comment:x.comment == null ? null : String(x.comment).slice(0,1000),
          messageHash:String(x.messageHash || "").slice(0,64),
          createdAt:x.createdAt || null
        }));
        return json({
          items,
          helpfulCount:items.filter(x => x.sentiment === "helpful" || x.sentiment === "like").length,
          notHelpfulCount:items.filter(x => x.sentiment === "not_helpful" || x.sentiment === "dislike").length,
          complaintCount:items.filter(x => /complaint|report|safety|abuse/i.test(x.category) || Boolean(x.reason)).length,
          checkedAt:new Date().toISOString()
        });
      }

      if (url.pathname === "/admin/diagnostics" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        const features = await adminDiagnosticSnapshot();
        const timeline = Object.values(features)
          .flat()
          .sort((a, b) => String(b.createdAt || "").localeCompare(String(a.createdAt || "")))
          .slice(0, 80)
          .map(item => ({
            id: String(item.id || "").slice(0, 100),
            feature: String(item.feature || "unknown").slice(0, 40),
            route: String(item.route || "/").slice(0, 120),
            status: Number(item.status || 500),
            code: String(item.code || "server_request_failed").slice(0, 80),
            providerMessage: sanitizeProviderMessage(item.providerMessage || ""),
            timelineStatus: String(item.timelineStatus || "").slice(0, 24),
            createdAt: item.createdAt || null
          }));
        return json({ features, timeline, checkedAt: new Date().toISOString() });
      }

      if (url.pathname === "/admin/self-test" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        return json(await adminReadinessSelfTest(env, user));
      }

      if (url.pathname === "/admin/issues" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw new Error("Admin access required");
        const issues = await firestoreQuery("adminIssues", {
          filters: [{ field: { fieldPath: "state" }, op: "EQUAL", value: { stringValue: "open" } }],
          limit: 50
        });
        issues.sort((a, b) => String(b.createdAt || "").localeCompare(String(a.createdAt || "")));
        return json({ items: issues.map(x => ({
          id: String(x.id || String(x.__name || "").split("/").pop()),
          route: String(x.route || "/"),
          status: Number(x.status || 500),
          code: String(x.code || "backend_unavailable"),
          state: String(x.state || "open"),
          createdAt: x.createdAt || null
        })) });
      }

      if (url.pathname === "/admin/cloudflare/secrets" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw new Error("Admin access required");
        return json({
          configured: providerSecretStatus(env),
          checkedAt: new Date().toISOString()
        });
      }

      if (url.pathname === "/consent" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        const snap = await firestoreGet("userConsents/" + user.uid);
        return json({
          requiredAccepted: snap?.requiredAccepted === true,
          policyVersion: String(snap?.policyVersion || ""),
          trainingConsent: snap?.trainingConsent === true
        });
      }

      if (url.pathname === "/consent" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        if (body.requiredAccepted !== true) return json({error:"required_consent_missing"},400);
        // Never trust age/parental-consent booleans supplied by the client.
        // Training remains disabled unless Firebase custom attributes or a server-owned
        // verification record establishes an adult/parental gate.
        const ageRecord = await firestoreGet("userAgeVerification/" + user.uid);
        const parentalRecord = await firestoreGet("parentalConsent/" + user.uid);
        const ageGatePassed = user.customAttributes?.ageVerifiedAdult === true ||
          (ageRecord?.verified === true && ageRecord?.isAdult === true) ||
          parentalRecord?.verified === true;
        const trainingConsent = body.trainingConsent === true && ageGatePassed;
        const policyVersion = String(body.policyVersion || "2026-09").slice(0,40);
        const previous = await firestoreGet("userConsents/" + user.uid);
        const now = new Date();
        await firestoreSet("userConsents/" + user.uid, {
          uid:user.uid, requiredAccepted:true, policyVersion,
          requiredConsentAt:previous?.requiredAccepted === true ? (previous.requiredConsentAt || now) : now,
          trainingConsent, trainingAgeGatePassed:ageGatePassed,
          trainingConsentAt:trainingConsent ? now : (previous?.trainingConsentAt || null),
          trainingConsentWithdrawnAt:previous?.trainingConsent === true && !trainingConsent ? now : (previous?.trainingConsentWithdrawnAt || null),
          updatedAt:now
        }, true);
        if (previous?.trainingConsent === true && !trainingConsent) {
          const rows = await firestoreQuery("consented_contributions", {
            filters:[{field:{fieldPath:"userId"},op:"EQUAL",value:{stringValue:user.uid}}],
            limit:200
          }).catch(() => []);
          for (const row of rows) {
            const id = String(row.id || String(row.__name || "").split("/").pop());
            if (id) await firestoreSet("consented_contributions/" + id, {status:"withdrawn", withdrawnAt:now, contentDeleted:true, prompt:null, response:null, updatedAt:now}, true);
          }
          await firestoreCreate("trainingConsentAudit/" + crypto.randomUUID(), {userId:user.uid,action:"withdrawn",createdAt:now,source:"consent"});
        }
        return json({ok:true,policyVersion,trainingConsent,ageGateRequired:!ageGatePassed});
      }

      if (url.pathname === "/training/contributions" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        if (body.privateMode === true) return json({ accepted: false, reason: "private_mode" });
        const consent = await firestoreGet("userConsents/" + user.uid);
        if (consent?.trainingConsent !== true || consent?.trainingAgeGatePassed !== true) {
          return json({ accepted: false, reason: "training_consent_off_or_age_gate_missing" });
        }
        const prompt = String(body.prompt || "").trim().slice(0, 4000);
        const response = String(body.response || "").trim().slice(0, 4000);
        const workspace = String(body.workspace || "main_chat").trim().slice(0, 60);
        if (!prompt || !response) return json({ accepted: false, reason: "empty_example" }, 400);
        // Do not store obvious direct identifiers, phone numbers, or common API tokens.
        const sensitivePattern = /(?:[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,})|(?:\b\+?\d[\d\s().-]{7,}\d\b)|(?:\bsk-[A-Za-z0-9_-]{16,}\b)|(?:\bAIza[0-9A-Za-z_-]{20,}\b)|(?:\bBearer\s+\S+)/i;
        if (sensitivePattern.test(prompt) || sensitivePattern.test(response)) {
          return json({ accepted: false, reason: "sensitive_content_filtered" });
        }
        const now = new Date();
        const id = crypto.randomUUID();
        const dataType = String(body.dataType || "chat").slice(0,24);
        const chatId = body.chatId == null ? null : String(body.chatId).slice(0,120);
        const fileId = body.fileId == null ? null : String(body.fileId).slice(0,120);
        await firestoreCreate("consented_contributions/" + id, {
          id, userId:user.uid, consentTimestamp:consent.trainingConsentAt || now,
          dataType, chatId, fileId, workspace, status:"pending_review",
          prompt, response, createdAt:now, updatedAt:now
        });
        await firestoreCreate("trainingConsentAudit/" + crypto.randomUUID(), {
          userId:user.uid, contributionId:id, action:"contribution_submitted", dataType, createdAt:now
        });
        return json({ accepted: true, stored: true, contributionId:id, status:"pending_review" });
      }

      if (url.pathname === "/consent/training" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const trainingConsent = body.trainingConsent === true;
        const ageRecord = await firestoreGet("userAgeVerification/" + user.uid);
        const parentalRecord = await firestoreGet("parentalConsent/" + user.uid);
        const ageGatePassed = user.customAttributes?.ageVerifiedAdult === true ||
          (ageRecord?.verified === true && ageRecord?.isAdult === true) ||
          parentalRecord?.verified === true;
        if (trainingConsent && !ageGatePassed) return json({ok:false,trainingConsent:false,reason:"age_gate_required"},400);
        const previous = await firestoreGet("userConsents/" + user.uid);
        const now = new Date();
        await firestoreSet("userConsents/" + user.uid, {
          uid:user.uid,trainingConsent,trainingAgeGatePassed:trainingConsent && ageGatePassed,
          trainingConsentAt:trainingConsent ? now : (previous?.trainingConsentAt || null),
          trainingConsentWithdrawnAt:previous?.trainingConsent === true && !trainingConsent ? now : (previous?.trainingConsentWithdrawnAt || null),
          updatedAt:now
        }, true);
        let deletedCount = 0;
        if (!trainingConsent) {
          const rows = await firestoreQuery("consented_contributions", {
            filters:[{field:{fieldPath:"userId"},op:"EQUAL",value:{stringValue:user.uid}}],
            limit:200
          }).catch(() => []);
          for (const row of rows) {
            const id = String(row.id || String(row.__name || "").split("/").pop());
            if (!id) continue;
            await firestoreSet("consented_contributions/" + id, {status:"withdrawn",withdrawnAt:now,contentDeleted:true,prompt:null,response:null,updatedAt:now}, true);
            deletedCount++;
          }
          await firestoreCreate("trainingConsentAudit/" + crypto.randomUUID(), {userId:user.uid,action:"withdrawn",deletedCount,createdAt:now});
        } else {
          await firestoreCreate("trainingConsentAudit/" + crypto.randomUUID(), {userId:user.uid,action:"granted",createdAt:now});
        }
        return json({ok:true,trainingConsent,contributionsDeleted:deletedCount});
      }

      // Native legacy phase routes: Companion, Learning, TruthGuard, Organization.
      if (url.pathname === "/companion/memory" && request.method === "GET") { const u=await verifyFirebaseUser(request); const x=await firestoreGet("userMemory/"+u.uid); return json({enabled:x?.enabled!==false,items:Array.isArray(x?.items)?x.items.slice(-80):[]}); }
      if (url.pathname === "/companion/memory" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); if(b.enabled!=null){await firestoreSet("userMemory/"+u.uid,{uid:u.uid,enabled:b.enabled===true,updatedAt:new Date()},true);return json({enabled:b.enabled===true});} const textValue=String(b.text||"").trim().slice(0,4000);if(!textValue)throw new Error("memory_text_required");const x=await firestoreGet("userMemory/"+u.uid),items=Array.isArray(x?.items)?x.items:[],item={id:crypto.randomUUID(),text:textValue,source:String(b.source||"companion").slice(0,40),createdAt:new Date().toISOString()};items.push(item);await firestoreSet("userMemory/"+u.uid,{uid:u.uid,enabled:x?.enabled!==false,items:items.slice(-200),updatedAt:new Date()},true);return json(item); }
       if (url.pathname === "/realtime/heartbeat" && request.method === "POST") {
         const user = await verifyFirebaseUser(request);
         enforceRateLimit(user.uid);
         await requireConsent(user.uid);
         const heartbeatBody = await readBody(request);
         const heartbeatMode = String(heartbeatBody.mode || "voice").toLowerCase();
         const isCameraMode = heartbeatMode === "camera" || heartbeatMode === "screen";
         const capabilityName = isCameraMode ? "live_camera" : "live_voice";
         const expectedFeature = isCameraMode ? "live-camera" : "live-voice";
         const expectedCostUnit = "second";
         const config = await firestoreGet("adminAdvancedOptions/" + (isCameraMode ? "live_camera_pricing" : "live_voice_pricing")) || {};
         const pricing = normalizeLivePricing(config.config || config, {}, capabilityName);
         if (config.enabled === false || config.killSwitch === true || pricing.enabled === false || pricing.killSwitch === true) {
           throw Object.assign(new Error(isCameraMode ? "live_camera_disabled" : "live_voice_disabled"), { httpStatus: 403 });
         }
         let modelControl = await capability(user.uid, capabilityName);
         if (heartbeatBody.modelRegistryId) {
           const selected = await resolvePublishedModel(heartbeatBody.modelRegistryId, expectedFeature);
           modelControl = { ...modelControl, ...selected, config: modelControl.config || {} };
         } else if (modelControl.providerCostTrackingEnabled === true && modelControl.selectedRegistryModelId) {
           const selected = await resolvePublishedModel(modelControl.selectedRegistryModelId, expectedFeature);
           modelControl = { ...modelControl, ...selected, config: modelControl.config || {} };
         }
         let ratePerMinute = pricing.creditsPerMinute;
         if ((heartbeatBody.modelRegistryId || modelControl.selectedRegistryModelId) && modelControl.creditsPerMinute != null) {
           ratePerMinute = Math.max(0, Number(modelControl.creditsPerMinute));
         }
         // Heartbeats bill elapsed seconds, not whole minutes. The Android client
         // should heartbeat every second; elapsedSeconds is capped to prevent a
         // delayed/replayed request from producing an unexpectedly large charge.
         const elapsedSeconds = Math.min(10, Math.max(1, Math.trunc(Number(heartbeatBody.elapsedSeconds ?? 1))));
         const rate = ratePerMinute * elapsedSeconds / 60;
         if (rate > 0 && !isSuperAdmin(user, env)) {
           const balance = canonicalUserBalance(await readCanonicalUserBalance(user.uid));
           if (!balance || balance.total < rate) throw Object.assign(new Error("out_of_credits"), { httpStatus: 402, safeError: "out_of_credits" });
         }
         let charge = { reservationId: null, amount: 0 };
         if (rate > 0) charge = await chargeCredits(user.uid, rate, "live_session_second", user);
         try {
           if (charge.reservationId) await commitCredits(user.uid, charge.reservationId, "live_media_elapsed_seconds");
         } catch (error) {
           if (charge.reservationId) await reverseCredits(user.uid, charge.reservationId).catch(() => {});
           throw error;
         }
         const trackedCost = await configuredMediaProviderCost(modelControl, elapsedSeconds, expectedCostUnit, true);
         if (trackedCost) {
           await recordConfiguredMediaCost(user.uid, capabilityName, modelControl, {
             providerId: modelControl.providerId || "openai",
             modelId: modelControl.modelId || pricing.modelId || DEFAULT_REALTIME_MODEL,
             creditsCharged: Number(charge.amount || 0),
             jobId: crypto.randomUUID()
           }, trackedCost, isCameraMode ? "live_camera" : "live_voice").catch(() =>
             notifyAdminOfIssue(env, "/realtime/heartbeat", 500, "live_media_cost_ledger_write_failed"));
         }
         const updatedBalance = canonicalUserBalance(await readCanonicalUserBalance(user.uid));
         return json({
           ok: true,
           available: isSuperAdmin(user, env) ? null : Number(updatedBalance?.total || 0),
           charged: Number(charge.amount || 0),
           free: rate === 0,
           billingIntervalSeconds: 1,
           ...(trackedCost ? { estimatedProviderCostUsd: trackedCost.providerCostUsd } : {})
         });
       }
       
       if (url.pathname === "/realtime/session" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        enforceRateLimit(u.uid);
        try {
          await requireConsent(u.uid);
        } catch (error) {
          throw Object.assign(new Error("consent_required"), { httpStatus: 403, safeError: "consent_required" });
        }
        const b = await readBody(request);
        const organization = b.organization === true;
        const mode = String(b.mode || "voice").toLowerCase();
        const capabilityName = organization ? "organization_studio" : (mode === "camera" || mode === "screen" ? "live_camera" : "live_voice");
        let control = await capability(u.uid, capabilityName);
        if (!organization && b.modelRegistryId) {
          const expectedFeature = (mode === "camera" || mode === "screen") ? "live-camera" : "live-voice";
          const selected = await resolvePublishedModel(b.modelRegistryId, expectedFeature);
          control = { ...control, providerId: selected.providerId, modelId: selected.modelId, creditCost: selected.creditCost, creditsPerMinute: selected.creditsPerMinute };
        }
        const isCameraMode = mode === "camera" || mode === "screen";
         const pricingKey = isCameraMode ? "live_camera_pricing" : "live_voice_pricing";
         const cfg = await firestoreGet("adminAdvancedOptions/" + pricingKey) || {};
         const conf = normalizeLivePricing(cfg.config || cfg, {}, pricingKey);
         if (!organization && (cfg.enabled === false || cfg.killSwitch === true || conf.enabled === false || conf.killSwitch === true)) {
           throw Object.assign(new Error(isCameraMode ? "live_camera_disabled" : "live_voice_disabled"), { httpStatus: 403, safeError: isCameraMode ? "live_camera_disabled" : "live_voice_disabled" });
         }
         const requestedRate = Math.max(0, Number(conf.creditsPerMinute || 0));
         const startFee = Math.max(0, Number(conf.startFeeCredits || 0));
         const superAdmin = isSuperAdmin(u, env);
         let initialBalance = 0;
         if (!organization && !superAdmin && (requestedRate > 0 || startFee > 0)) {
           const balance = canonicalUserBalance(await readCanonicalUserBalance(u.uid));
           initialBalance = Number(balance?.total || 0);
         }
         const startPolicy = liveStartChargePolicy(conf, initialBalance, superAdmin);
         if (!organization && !startPolicy.allowed) {
           throw Object.assign(new Error("out_of_credits"), { httpStatus: 402, safeError: "out_of_credits" });
         }
         let reservation = null;
         if (!organization && !superAdmin && startPolicy.startFeeCredits > 0) {
           reservation = await chargeCredits(u.uid, startPolicy.startFeeCredits, capabilityName, u);
         }
         return await settleLiveStartReservation(u.uid, reservation, commitCredits, reverseCredits, async () => {
           let model = String(b.modelRegistryId ? control.modelId : (conf.modelId || control.modelId || DEFAULT_REALTIME_MODEL)).slice(0, 80);
           const voice0 = String(b.voice || conf.defaultVoice || "marin").toLowerCase();
          const voice = ["alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar"].includes(voice0)
            ? voice0 : String(conf.defaultVoice || "marin");
          if (!env?.OPENAI_API_KEY) {
            throw Object.assign(new Error("openai_provider_not_configured"), { httpStatus: 503, safeError: "openai_provider_not_configured" });
          }
          const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(u.uid));
          const safetyIdentifier = Array.from(new Uint8Array(digest)).map(value => value.toString(16).padStart(2, "0")).join("");
          const createRealtimeSecret = (sessionModel) => fetch("https://api.openai.com/v1/realtime/client_secrets", {
             method: "POST",
             headers: {
               Authorization: "Bearer " + String(env.OPENAI_API_KEY),
               "Content-Type": "application/json",
               "OpenAI-Safety-Identifier": safetyIdentifier
             },
             body: JSON.stringify({
               session: {
                 type: "realtime", model: sessionModel, output_modalities: ["audio"],
                 audio: {
                   input: { format: { type: "audio/pcm", rate: 24000 }, turn_detection: { type: "server_vad", interrupt_response: true, create_response: true } },
                   output: { format: { type: "audio/pcm", rate: 24000 }, voice }
                 }
               }
             })
           });
           let response = await createRealtimeSecret(model);
           let raw = await response.text();
           let out = {};
           try { out = raw ? JSON.parse(raw) : {}; } catch {}
           if (!response.ok && [400, 404, 422].includes(response.status) && model !== "gpt-realtime") {
             const rejectedModel = model;
             const providerCode = String(out?.error?.code || out?.error?.type || "model_rejected").replace(/[^a-zA-Z0-9_.-]/g, "").slice(0, 80);
             await notifyAdminOfIssue(env, url.pathname, 502, "realtime_model_rejected:" + rejectedModel + ":" + providerCode, out?.error?.message);
             const fallbackModel = "gpt-realtime";
             const fallbackResponse = await createRealtimeSecret(fallbackModel);
             const fallbackRaw = await fallbackResponse.text();
             let fallbackOut = {};
             try { fallbackOut = fallbackRaw ? JSON.parse(fallbackRaw) : {}; } catch {}
             if (fallbackResponse.ok) {
               model = fallbackModel;
               response = fallbackResponse;
               raw = fallbackRaw;
               out = fallbackOut;
             } else {
               const fallbackCode = String(fallbackOut?.error?.code || fallbackOut?.error?.type || "fallback_rejected").replace(/[^a-zA-Z0-9_.-]/g, "").slice(0, 80);
               await notifyAdminOfIssue(env, url.pathname, 502, "realtime_fallback_rejected:" + fallbackCode, fallbackOut?.error?.message);
               const fallbackSafeError = fallbackResponse.status === 401 ? "provider_key_missing"
                 : fallbackResponse.status === 429 ? "provider_billing"
                 : fallbackResponse.status === 400 || fallbackResponse.status === 422 ? "realtime_request_rejected"
                 : "realtime_model_rejected";
               throw Object.assign(new Error(fallbackSafeError), { httpStatus: 502, safeError: fallbackSafeError, providerStatus: fallbackResponse.status });
             }
           }
           if (!response.ok) {
             const providerCode = String(out?.error?.code || out?.error?.type || "provider_rejected").replace(/[^a-zA-Z0-9_.-]/g, "").slice(0, 80);
             await notifyAdminOfIssue(env, url.pathname, 502, "realtime_session_failed:" + response.status + ":" + providerCode, out?.error?.message);
             const safeError = response.status === 401 ? "provider_key_missing"
               : response.status === 429 ? "provider_billing"
               : response.status === 400 || response.status === 422 ? "realtime_request_rejected"
               : response.status === 403 || response.status === 404 ? "realtime_model_rejected"
               : "realtime_session_failed";
             throw Object.assign(new Error(safeError), { httpStatus: 502, safeError, providerStatus: response.status });
           }
          const secret = out?.value || out?.client_secret?.value;
          if (!secret) {
            throw Object.assign(new Error("realtime_client_secret_missing"), {
              httpStatus: 502, safeError: "realtime_client_secret_missing"
            });
          }
          if (organization) {
            const wallet = await orgWallet(u.uid);
            if (wallet.balanceCents <= 0) throw Object.assign(new Error("organization_insufficient_balance"), { httpStatus: 402 });
            await orgChargeAssistant(u.uid, "realtime-" + Date.now());
          }
          return json({
            clientSecret: String(secret), model, voice,
            pricing: { enabled: true, free: requestedRate === 0 && startFee === 0, creditsPerMinute: requestedRate, startFeeCredits: startFee, minimumBalanceToStart: Number(conf.minimumBalanceToStart || 0), maxSessionMinutes: Number(conf.maxSessionMinutes || 120) }
          });
        });
      }
      if (url.pathname === "/passkey/login/options" && request.method === "GET") { return json(await authenticationOptionsNative()); }
      if (url.pathname === "/passkey/login/verify" && request.method === "POST") { const b=await readBody(request); return json(await verifyAuthenticationNative(env,String(b.sessionId||""),b.response||{})); }
      if (url.pathname === "/security/passkeys" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await passkeyStatus(u.uid)); }
      if (url.pathname === "/security/passkeys/options" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await registrationOptionsNative(u.uid,u.email)); }
      if (url.pathname === "/security/passkeys/verify" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await verifyRegistrationNative(u.uid,b.response||{})); }
      if (url.pathname.startsWith("/security/passkeys/") && request.method === "DELETE") { const u=await verifyFirebaseUser(request); return json(await removePasskeyNative(u.uid,decodeURIComponent(url.pathname.slice("/security/passkeys/".length)))); }
      if (url.pathname === "/payments/preview" && request.method === "POST") { return json({error:"google_play_billing_required",message:"BQ Sqwad AI billing is Google Play only."},410); }
      if (url.pathname === "/payments/initialize" && request.method === "POST") { return json({error:"google_play_billing_required",message:"BQ Sqwad AI billing is Google Play only."},410); }
      if (url.pathname === "/payments/verify" && request.method === "POST") { return json({error:"google_play_billing_required",message:"BQ Sqwad AI billing is Google Play only."},410); }
      if (url.pathname === "/payments/auto-renew" && request.method === "POST") { return json({error:"google_play_billing_required",message:"Google Play controls subscription renewal."},410); }
      if (url.pathname === "/paystack/webhook" && request.method === "POST") { return json({error:"paystack_retired",message:"BQ Sqwad AI billing is Google Play only."},410); }
      if (["/payments/preview","/payments/initialize","/payments/verify","/payments/auto-renew"].includes(url.pathname) && request.method === "POST") { return json({error:"google_play_billing_required",message:"BQ Sqwad AI billing is handled through Google Play Billing."},410); }
      if (url.pathname === "/business/training-assets" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request),wid=String(b.workspaceId||"");const w=await businessWorkspace(u.uid,{workspaceId:wid}),type=String(b.type||"").toLowerCase();if(!["text","document","photo","voice","video","step_by_step","appearance_hair"].includes(type))throw new Error("unsupported_training_asset_type");const title=String(b.title||"").trim().slice(0,180),purpose=String(b.purpose||"").trim().slice(0,1000);if(!title||!purpose)throw new Error("training_asset_title_and_purpose_required");const id=crypto.randomUUID(),asset={id,workspaceId:w.workspaceId,type,title,purpose,status:"metadata_saved",createdAt:new Date().toISOString()};await firestoreSet("businessTrainingAssets/"+id,{...asset,uid:u.uid,createdAt:new Date(),updatedAt:new Date()},false);return json({ok:true,asset}); }
      if (url.pathname === "/business/training-assets/upload-url" && request.method === "POST") {
        const u=await verifyFirebaseUser(request),b=await readBody(request),workspace=await businessWorkspace(u.uid,{workspaceId:b.workspaceId});
        await requireBusinessAccess(u.uid,workspace.workspaceId,"manager");
        const type=String(b.type||"").trim().toLowerCase(),allowed=new Set(["text","document","photo","voice","video","step_by_step","appearance_hair"]);
        if(!allowed.has(type))return json({error:"unsupported_training_asset_type"},400);
        const title=String(b.title||"").trim().slice(0,180),purpose=String(b.purpose||"").trim().slice(0,1000),fileName=String(b.fileName||"training-asset").trim().slice(0,160),contentType=String(b.contentType||"application/octet-stream").trim().slice(0,120),size=Math.max(0,Number(b.size||0));
        if(!title||!purpose||!fileName||!size)return json({error:"training_asset_metadata_required"},400);
        if(size>500*1024*1024)return json({error:"training_asset_too_large"},413);
        const assetId=crypto.randomUUID(),safeName=fileName.replace(/[^a-zA-Z0-9._-]/g,"_").slice(0,120),objectPath="business-training/"+u.uid+"/"+workspace.workspaceId+"/"+assetId+"-"+safeName;
        const session=await createTrainingAssetUploadSession({fileName:safeName,contentType,objectPath});
        await firestoreSet("businessTrainingAssets/"+assetId,{id:assetId,uid:u.uid,workspaceId:workspace.workspaceId,type,title,purpose,fileName:safeName,contentType,size,objectPath,status:"upload_pending",createdAt:new Date(),updatedAt:new Date()},false);
        return json({ok:true,assetId,workspaceId:workspace.workspaceId,uploadUrl:session.uploadUrl,authorizationToken:session.authorizationToken,objectPath,expiresInSeconds:session.expiresInSeconds});
      }
      if (url.pathname === "/business/training-assets/finalize" && request.method === "POST") {
        const u=await verifyFirebaseUser(request),b=await readBody(request),assetId=String(b.assetId||"").trim();
        if(!assetId)return json({error:"training_asset_id_required"},400);
        const ref=await firestoreGet("businessTrainingAssets/"+assetId);
        if(!ref||ref.uid!==u.uid)return json({error:"training_asset_not_found"},404);
        await requireBusinessAccess(u.uid,String(ref.workspaceId||""),"manager");
        const object=await inspectTrainingAssetObject(String(ref.objectPath||""));
        if(!object)return json({error:"training_asset_upload_missing"},409);
        const expectedSize=Number(ref.size||0);
        if(!object.size||(expectedSize&&object.size!==expectedSize))return json({error:"training_asset_size_mismatch"},409);
        const entry={id:assetId,type:String(ref.type||""),title:String(ref.title||""),purpose:String(ref.purpose||""),fileName:String(ref.fileName||""),contentType:String(ref.contentType||object.contentType||""),size:object.size,objectPath:String(ref.objectPath||""),status:"uploaded",workspaceId:String(ref.workspaceId||""),createdAt:new Date().toISOString()};
        await firestoreSet("businessTrainingAssets/"+assetId,{status:"uploaded",uploadedAt:new Date(),updatedAt:new Date()},true);
        const wr=await firestoreGet("businessWorkspaces/"+String(ref.workspaceId||"")),existing=Array.isArray(wr?.trainingAssets)?wr.trainingAssets:[];
        if(!existing.some(x=>String(x?.id||"")===assetId))await firestoreSet("businessWorkspaces/"+String(ref.workspaceId||""),{trainingAssets:[...existing,entry],updatedAt:new Date()},true);
        return json({ok:true,asset:entry});
      }
      if (url.pathname === "/business/training-assets/download" && request.method === "GET") {
        const u=await verifyFirebaseUser(request),assetId=String(url.searchParams.get("assetId")||"").trim(),ref=await firestoreGet("businessTrainingAssets/"+assetId);
        if(!ref||ref.uid!==u.uid)return json({error:"training_asset_not_found"},404);
        await requireBusinessAccess(u.uid,String(ref.workspaceId||""),"manager");
        if(ref.status!=="uploaded")return json({error:"training_asset_not_uploaded"},409);
        const auth=await createTrainingAssetDownloadAuthorization({objectPath:String(ref.objectPath||""),durationSeconds:900});
        return json({ok:true,downloadUrl:auth.downloadUrl,authorizationToken:auth.authorizationToken,fileNamePrefix:auth.fileNamePrefix,expiresInSeconds:auth.expiresInSeconds});
      }
      if (url.pathname === "/business/training-assets" && request.method === "DELETE") {
        const u=await verifyFirebaseUser(request),b=await readBody(request),assetId=String(b.assetId||url.searchParams.get("assetId")||"").trim(),ref=await firestoreGet("businessTrainingAssets/"+assetId);
        if(!ref||ref.uid!==u.uid)return json({error:"training_asset_not_found"},404);
        await requireBusinessAccess(u.uid,String(ref.workspaceId||""),"manager");
        await deleteTrainingAssetObject(String(ref.objectPath||""));
        await firestoreDelete("businessTrainingAssets/"+assetId);
        const wr=await firestoreGet("businessWorkspaces/"+String(ref.workspaceId||"")),existing=Array.isArray(wr?.trainingAssets)?wr.trainingAssets.filter(x=>String(x?.id||"")!==assetId):[];
        await firestoreSet("businessWorkspaces/"+String(ref.workspaceId||""),{trainingAssets:existing,updatedAt:new Date()},true);
        return json({ok:true,deleted:true,assetId});
      }
      if (url.pathname === "/account" && request.method === "DELETE") {
        return json({ error: "immediate_account_deletion_disabled", message: "Account deletion now has a 30-day cancellation period. Use POST /account/schedule-deletion." }, 405);
      }
      if (url.pathname === "/account/deletion-status" && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        const status = await firestoreGet("userAccountStatus/" + u.uid);
        const deadline = Date.parse(String(status?.scheduledDeletionAt || ""));
        const pending = status?.status === "pending_deletion" && Number.isFinite(deadline) && deadline > Date.now();
        return json({ status: pending ? "pending_deletion" : String(status?.status || "active"), pending, scheduledDeletionAt: pending ? status.scheduledDeletionAt : null });
      }
      if (url.pathname === "/account/schedule-deletion" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        const existing = await firestoreGet("userAccountStatus/" + u.uid);
        if (existing?.status === "pending_deletion" && Date.parse(String(existing.scheduledDeletionAt || "")) > Date.now()) {
          return json({ ok: true, alreadyScheduled: true, scheduledDeletionAt: existing.scheduledDeletionAt });
        }
        const requestedAt = new Date();
        const scheduledDeletionAt = new Date(requestedAt.getTime() + 30 * 86400000);
        await firestoreSet("userAccountStatus/" + u.uid, {
          uid: u.uid,
          status: "pending_deletion",
          requestedAt,
          scheduledDeletionAt,
          updatedAt: requestedAt
        }, true);
        return json({ ok: true, status: "pending_deletion", requestedAt: requestedAt.toISOString(), scheduledDeletionAt: scheduledDeletionAt.toISOString(), cancellationAvailable: true });
      }
      if (url.pathname === "/account/cancel-deletion" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        const existing = await firestoreGet("userAccountStatus/" + u.uid);
        const deadline = Date.parse(String(existing?.scheduledDeletionAt || ""));
        if (existing?.status !== "pending_deletion" || !Number.isFinite(deadline) || deadline <= Date.now()) {
          return json({ ok: false, error: "no_cancellable_deletion" }, 409);
        }
        await firestoreSet("userAccountStatus/" + u.uid, {
          uid: u.uid,
          status: "active",
          cancellationRequestedAt: new Date(),
          updatedAt: new Date()
        }, true);
        return json({ ok: true, status: "active", cancelled: true });
      }
      if (url.pathname === "/public/app-config" && request.method === "GET") { const x=await firestoreGet("publicAppConfig/app"),d=x||{}; const ids=["CHAT","VIDEO","SEARCH","TRUTH_GUARD","FRONTIER_LAB","CAMERA","VOICE","PERSONAL","LIFE","COMPANION","LEARNING","BUSINESS","ORGANIZATION_STUDIO","DATA_ANALYSIS","ADMIN"]; const announcement=d.dailyFeatureAnnouncement&&typeof d.dailyFeatureAnnouncement==="object"?d.dailyFeatureAnnouncement:null; return json({authBackgroundVideoUrl:d.authBackgroundVideoUrl||null,authBackgroundVideoEnabled:d.authBackgroundVideoEnabled===true,chatBackgroundUrl:d.chatBackgroundUrl||null,dailyFeatureAnnouncement:announcement?{id:String(announcement.id||"").slice(0,120),title:String(announcement.title||"").slice(0,100),body:String(announcement.body||"").slice(0,500),feature:String(announcement.feature||"").slice(0,80),enabled:announcement.enabled===true}:null,workspaceOrder:Array.isArray(d.workspaceOrder)?d.workspaceOrder.filter(x=>ids.includes(String(x))):ids,workspaceEnabled:d.workspaceEnabled&&typeof d.workspaceEnabled==="object"?d.workspaceEnabled:Object.fromEntries(ids.map(x=>[x,true])),themeMode:["system","light","dark"].includes(String(d.themeMode||""))?String(d.themeMode):"system"}); }
      if (url.pathname === "/plans" && request.method === "GET") { return json({plans:await firestoreQuery("plans",{filters:[{field:{fieldPath:"active"},op:"EQUAL",value:{booleanValue:true}}],limit:100})}); }
      // Legacy duplicate kept for source-history compatibility; the earlier /credits handler is active.
      if (url.pathname === "/credits" && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        if (isSuperAdmin(u, env)) return json({ available: 0, reserved: 0, adminUnlimited: true, updatedAt: null, source: "admin_unlimited" });
        await applyConfiguredCreditTrial(u.uid);
        await applyConfiguredDailyFreeCredits(u.uid);
        const profile = await readCanonicalUserBalance(u.uid);
        const balance = canonicalUserBalance(profile);
        if (!balance) throw new Error("Account not found.");
        return json({
          available: balance.total, permanent: balance.permanent, subscription: balance.subscription,
          trial: balance.trial, dailyFreeCredits: balance.daily, dailyFreeCreditsDate: balance.dailyDate,
          dailyFreeCreditsTimeZone: balance.dailyFreeCreditsTimeZone, dailyFreeCreditsRenewalTime: balance.dailyFreeCreditsRenewalTime,
          reserved: 0, plan: balance.plan, updatedAt: profile.updatedAt || null, source: "firebase_users"
        });
      }
      if (url.pathname === "/usage" && request.method === "GET") { const u=await verifyFirebaseUser(request),limit=Math.min(100,Math.max(1,Number(url.searchParams.get("limit")||50))); return json({items:(await firestoreQuery("creditLedger",{filters:[{field:{fieldPath:"uid"},op:"EQUAL",value:{stringValue:u.uid}}],limit:100})).slice(0,limit)}); }
      if (url.pathname === "/play/subscription-management" && request.method === "GET") { const u=await verifyFirebaseUser(request),e=await firestoreGet("entitlements/"+u.uid),play=String(e?.source||"")==="play"&&Boolean(e?.purchaseToken)&&Boolean(e?.productId);return json({ok:true,provider:"google_play",hasSubscription:play,autoRenewEnabled:play?e.autoRenewEnabled!==false:false,state:String(e?.state||""),renewalState:String(e?.renewalState||""),endDate:e?.endDate||null,managementUrl:play?"https://play.google.com/store/account/subscriptions?sku="+encodeURIComponent(String(e.productId))+"&package="+encodeURIComponent("com.bqsqwad.ai"):null,paymentMethodManagementUrl:play?"https://play.google.com/store/paymentmethods":null,note:"Google Play is authoritative for auto-renewal and payment methods."}); }
      if (url.pathname === "/contact-sales" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request),email=String(b.email||u.email||"").trim().slice(0,320),companyName=String(b.companyName||"").trim().slice(0,180),companyEmail=String(b.companyEmail||"").trim().slice(0,320),planId=String(b.planId||"").trim().slice(0,100),planName=String(b.planName||"").trim().slice(0,180),billingPeriod=String(b.billingPeriod||"").trim().slice(0,40),message=String(b.message||"").trim().slice(0,6000);if(!email||!planId||!message)throw new Error("email_plan_and_message_required");const id=crypto.randomUUID();await firestoreCreate("contactSalesRequests/"+id,{uid:u.uid,email,companyName,companyEmail,planId,planName,billingPeriod,features:Array.isArray(b.features)?b.features.map(x=>String(x).slice(0,180)).slice(0,100):[],message,status:"new",createdAt:new Date(),updatedAt:new Date()});return json({ok:true,requestId:id,status:"new"}); }
      if (url.pathname === "/contact-sales/status" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json({requests:await firestoreQuery("contactSalesRequests",{filters:[{field:{fieldPath:"uid"},op:"EQUAL",value:{stringValue:u.uid}}],limit:10})}); }
      if (url.pathname === "/admin/contact-sales" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json({requests:await firestoreQuery("contactSalesRequests",{limit:100})}); }
      if (url.pathname === "/admin/contact-sales/status" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request),status=["seen","contacted","replied","closed"].includes(String(b.status))?String(b.status):"";if(!status)throw new Error("invalid_request_status");await firestoreSet("contactSalesRequests/"+String(b.requestId||""),{status,lastAdminAction:status,lastAdminActionAt:new Date(),updatedAt:new Date()},true);return json({ok:true,requestId:String(b.requestId||""),status}); }
      if (url.pathname === "/admin/play-catalog" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json(await playCatalog({get:firestoreGet,commit:firestoreCommit},"")); }
      if (url.pathname === "/organization/billing" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await orgWallet(u.uid)); }
      if (url.pathname === "/organization/billing/topups" && request.method === "GET") { return json({products:orgTopUps()}); }
      if (url.pathname === "/organization/production/live/billing/start" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await orgLiveStart(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/production/live/billing/heartbeat" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await orgLiveHeartbeat(u.uid,String(b.sessionId||""))); }
      if (url.pathname === "/organization/production/live/billing" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await orgLiveGet(u.uid,String(url.searchParams.get("sessionId")||""))); }
      if (url.pathname === "/organization/production/live/billing/stop" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await orgLiveStop(u.uid,String(b.sessionId||""),String(b.reason||"operator_stop"))); }
      if (url.pathname === "/admin/organization-billing" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json(await orgBillingConfig(u.uid,await readBody(request))); }
      if (url.pathname === "/media-rights" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request),id=String(b.id||crypto.randomUUID()).slice(0,120),basis=String(b.rightsBasis||"").trim(); if(!basis)throw new Error("media_rights_basis_required"); if(basis==="licensed"&&!String(b.licenseReference||"").trim())throw new Error("license_reference_required"); await firestoreCreate("mediaRights/"+id,{id,uid:u.uid,workspaceId:String(b.workspaceId||"").slice(0,120)||null,sourceType:String(b.sourceType||"user_media").slice(0,80),sourceUrl:String(b.sourceUrl||"").slice(0,4000)||null,contentTitle:String(b.contentTitle||"").slice(0,300)||null,rightsBasis:basis.slice(0,120),licenseReference:String(b.licenseReference||"").slice(0,1000)||null,licenseDocumentUrl:String(b.licenseDocumentUrl||"").slice(0,4000)||null,providerVerification:String(b.providerVerification||"").slice(0,120)||null,status:"pending_admin_review",submittedAt:new Date(),updatedAt:new Date()}); return json({id,status:"pending_admin_review"}); }
      if (url.pathname === "/admin/media-rights/pending" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json({items:await firestoreQuery("mediaRights",{filters:[{field:{fieldPath:"status"},op:"EQUAL",value:{stringValue:"pending_admin_review"}}],limit:100})}); }
      if (url.pathname === "/admin/media-rights/review" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request),status=["verified","rejected","needs_more_evidence"].includes(String(b.decision))?String(b.decision):"";if(!status)throw new Error("media_rights_review_status_invalid");await firestoreSet("mediaRights/"+String(b.rightsId||""),{status,reviewNote:String(b.note||"").slice(0,2000)||null,reviewedBy:u.uid,reviewedAt:new Date(),updatedAt:new Date()},true);return json({id:String(b.rightsId||""),status}); }
      if (url.pathname === "/organization/catalog" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json({plans:await getOrganizationCatalog(),pricing:{model:"pay_as_you_go",price:null}}); }
      if (url.pathname === "/organization/access" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await getOrganizationAccess(u.uid)); }
      if (url.pathname === "/admin/organization" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json(await getAdminOrganizationControl()); }
      if (url.pathname === "/admin/organization/plan-status" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request); return json(await setOrganizationPlanStatus(u.uid,String(b.planId||""),b.active===true,b.confirmDeactivation===true)); }
      if (url.pathname === "/admin/organization/entitlement" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request); return json(await setOrganizationEntitlement(u.uid,String(b.uid||""),String(b.planId||""),b.active===true,b.confirmDeactivation===true)); }
      if (url.pathname === "/admin/organization/test-session" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json(await getOrganizationTestSession(u.uid)); }
      if (url.pathname === "/admin/organization/test-session" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request); return json(await setOrganizationTestSession(u.uid,String(b.planId||""),b.enabled!==false,Number(b.durationMinutes||120))); }
      if (url.pathname === "/organization/scheduled-tasks" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json({tasks:await listOrganizationScheduledTasks(u.uid)}); }
      if (url.pathname === "/organization/scheduled-tasks" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await createOrganizationScheduledTask(u.uid,await readBody(request))); }
      if (url.pathname.startsWith("/organization/scheduled-tasks/") && url.pathname.endsWith("/run") && request.method === "POST") { const u=await verifyFirebaseUser(request),id=decodeURIComponent(url.pathname.slice("/organization/scheduled-tasks/".length,-4)); return json(await runOrganizationScheduledTask(env,u.uid,id)); }
      if (url.pathname.startsWith("/organization/scheduled-tasks/") && request.method === "PATCH") { const u=await verifyFirebaseUser(request),id=decodeURIComponent(url.pathname.slice("/organization/scheduled-tasks/".length)); return json(await updateOrganizationScheduledTask(u.uid,id,await readBody(request))); }

      if (url.pathname === "/consent" && request.method === "GET") { const u=await verifyFirebaseUser(request); const x=await firestoreGet("userConsents/"+u.uid); return json({requiredAccepted:x?.requiredAccepted===true,trainingConsent:x?.trainingConsent===true,policyVersion:String(x?.policyVersion||"")}); }
      if (url.pathname === "/consent" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); if(b.requiredAccepted!==true)return json({error:"required_consent_missing"},400); const now=new Date(); await firestoreSet("userConsents/"+u.uid,{uid:u.uid,requiredAccepted:true,requiredConsentAt:now,trainingConsent:b.trainingConsent===true,trainingConsentAt:now,policyVersion:String(b.policyVersion||"current").slice(0,80),updatedAt:now},true); return json({ok:true,policyVersion:String(b.policyVersion||"current"),trainingConsent:b.trainingConsent===true}); }
      if (url.pathname === "/consent/training" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request),trainingConsent=b.trainingConsent===true; await firestoreSet("userConsents/"+u.uid,{uid:u.uid,trainingConsent,trainingConsentAt:new Date(),updatedAt:new Date()},true); if(!trainingConsent)await firestoreDelete("aiTrainingContributions/"+u.uid); return json({ok:true,trainingConsent,contributionsDeleted:!trainingConsent}); }
      if (url.pathname === "/notifications/token" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request),token=String(b.token||"").trim(); if(!token||token.length>4096)return json({error:"notification_token_required"},400); const hash=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(token)); let h=""; for(const v of new Uint8Array(hash))h+=v.toString(16).padStart(2,"0"); await firestoreSet("notificationTokens/"+u.uid+"_"+h,{uid:u.uid,token,tokenHash:h,platform:String(b.platform||"android").slice(0,40),enabled:true,updatedAt:new Date()},true); return json({ok:true,registered:true}); }
      if (url.pathname === "/notifications/token" && request.method === "DELETE") { const u=await verifyFirebaseUser(request),b=await readBody(request),token=String(b.token||"").trim(); if(!token)return json({error:"notification_token_required"},400); const hash=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(token)); let h=""; for(const v of new Uint8Array(hash))h+=v.toString(16).padStart(2,"0"); await firestoreDelete("notificationTokens/"+u.uid+"_"+h); return json({ok:true,removed:true}); }
      if (url.pathname === "/video/generate" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        await requireConsent(u.uid);
        const b = await readBody(request);
        const videoPrompt = String(b.prompt || b.description || b.text || "").trim();
        if (videoPrompt) {
          const redirect = await workspaceScopeRedirect("video", videoPrompt);
          if (redirect) return json({ ok: true, redirect });
        }
        let control = await capability(u.uid, "video_generation");
        if (b.modelRegistryId) {
          const selected = await resolvePublishedModel(b.modelRegistryId, "video-generation");
          control = { ...control, ...selected, config: control.config || {} };
        }
        const mediaCost = await configuredMediaProviderCost(control, Number(b.seconds || 0), "second");
        b.creditCost = mediaCost ? mediaCost.chargeCredits : control.creditCost;
        b.model = control.modelId;
        const result = await createVideoNative(env, u, b,
          (uid, cost, name, user) => chargeCredits(uid, cost, name, user, { markupApplied: Boolean(mediaCost) }),
          (uid, id, provider) => commitCredits(uid, id, provider),
          (uid, id) => reverseCredits(uid, id));
        if (mediaCost) await recordConfiguredMediaCost(u.uid, "video_generation", control, result, mediaCost, "video")
          .catch(() => notifyAdminOfIssue(env, "/video/generate", 500, "media_cost_ledger_write_failed"));
        return json(result);
      }
      if (url.pathname.startsWith("/video/jobs/") && !url.pathname.endsWith("/content") && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await getVideoNative(env,u,decodeURIComponent(url.pathname.slice("/video/jobs/".length)))); }
      if (url.pathname.startsWith("/video/jobs/") && url.pathname.endsWith("/content") && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        await assertSharedCreditsAvailable(u);
        return await getVideoContentNative(env, u, decodeURIComponent(url.pathname.slice("/video/jobs/".length, -8)));
      }
      if (url.pathname === "/ai/image" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        await requireConsent(u.uid);
        let control = await capability(u.uid, "image_generation");
        const body = await readBody(request);
        if (body.modelRegistryId) {
          const selected = await resolvePublishedModel(body.modelRegistryId, "image-generation");
          control = { ...control, ...selected, config: control.config || {} };
        }
        body.model = control.modelId || "gpt-image-2";
        const mediaCost = await configuredMediaProviderCost(control, 1, "image");
        body.creditCost = mediaCost ? mediaCost.chargeCredits : control.creditCost;
        const result = await generateImageNative(
          env, u, body,
          (uid, cost, name, user) => chargeCredits(uid, cost, name, user, { markupApplied: Boolean(mediaCost) }),
          (uid, id, provider) => commitCredits(uid, id, provider),
          (uid, id) => reverseCredits(uid, id)
        );
        if (mediaCost) await recordConfiguredMediaCost(u.uid, "image_generation", control, result, mediaCost, "image")
          .catch(() => notifyAdminOfIssue(env, "/ai/image", 500, "media_cost_ledger_write_failed"));
        return json(result);
      }
      if (url.pathname === "/ai/image/edit" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        await requireConsent(u.uid);
        let control = await capability(u.uid, "image_generation");
        const body = await readBody(request);
        if (body.modelRegistryId) {
          const selected = await resolvePublishedModel(body.modelRegistryId, "image-generation");
          control = { ...control, ...selected, config: control.config || {} };
        }
        body.model = control.modelId || "gpt-image-2";
        const mediaCost = await configuredMediaProviderCost(control, 1, "image");
        body.creditCost = mediaCost ? mediaCost.chargeCredits : control.creditCost;
        const result = await editImageNative(
          env, u, body,
          (uid, cost, name, user) => chargeCredits(uid, cost, name, user, { markupApplied: Boolean(mediaCost) }),
          (uid, id, provider) => commitCredits(uid, id, provider),
          (uid, id) => reverseCredits(uid, id)
        );
        if (mediaCost) await recordConfiguredMediaCost(u.uid, "image_generation", control, result, mediaCost, "image_edit")
          .catch(() => notifyAdminOfIssue(env, "/ai/image/edit", 500, "media_cost_ledger_write_failed"));
        return json(result);
      }
      if (url.pathname === "/ai/video" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        await requireConsent(u.uid);
        let control = await capability(u.uid, "video_generation");
        const b = await readBody(request);
        if (b.modelRegistryId) {
          const selected = await resolvePublishedModel(b.modelRegistryId, "video-generation");
          control = { ...control, ...selected, config: control.config || {} };
        }
        const mediaCost = await configuredMediaProviderCost(control, Number(b.seconds || 0), "second");
        b.creditCost = mediaCost ? mediaCost.chargeCredits : control.creditCost;
        b.model = control.modelId;
        const result = await createVideoNative(env, u, b,
          (uid, cost, name, user) => chargeCredits(uid, cost, name, user, { markupApplied: Boolean(mediaCost) }),
          (uid, id, provider) => commitCredits(uid, id, provider),
          (uid, id) => reverseCredits(uid, id));
        if (mediaCost) await recordConfiguredMediaCost(u.uid, "video_generation", control, result, mediaCost, "video")
          .catch(() => notifyAdminOfIssue(env, "/ai/video", 500, "media_cost_ledger_write_failed"));
        return json(result);
      }
      if (url.pathname.startsWith("/ai/video/") && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await getVideoNative(env,u,decodeURIComponent(url.pathname.slice("/ai/video/".length)))); }
      if (url.pathname === "/social/oauth/callback" && request.method === "GET") { const state=String(url.searchParams.get("state")||""),code=String(url.searchParams.get("code")||""); if(!state||!code)return new Response("OAuth callback parameters required",{status:400}); const result=await socialCallback(env,state,code); return new Response("<!doctype html><html><body><h1>BQ Sqwad AI</h1><p>Account connected successfully. You can return to the app.</p></body></html>",{status:200,headers:{"content-type":"text/html; charset=utf-8","cache-control":"no-store"}}); }
      if (url.pathname === "/organization/social/accounts" && request.method === "GET") { const u=await verifyFirebaseUser(request),orgId=String(url.searchParams.get("organizationId")||u.uid); const org=await firestoreGet("organizationWorkspaces/"+orgId); const member=Array.isArray(org?.members)?org.members.find(x=>String(x.id||"")===u.uid&&x.status!=="inactive"):null;if(!member||!["owner","admin","editor"].includes(String(member.role||"")))throw new Error("organization_social_access_denied");return json({accounts:(await listSocialAccounts(u.uid,orgId)).filter(x=>["youtube","tiktok"].includes(x.platform))}); }
      if (url.pathname === "/organization/social/tiktok/profile" && request.method === "GET") { const u=await verifyFirebaseUser(request),orgId=String(url.searchParams.get("organizationId")||""),accountId=String(url.searchParams.get("accountId")||""); const org=await firestoreGet("organizationWorkspaces/"+orgId);const member=Array.isArray(org?.members)?org.members.find(x=>String(x.id||"")===u.uid&&x.status!=="inactive"):null;if(!member)throw new Error("organization_social_access_denied");return json(await tiktokProfile(env,u.uid,accountId,orgId)); }
      if (url.pathname === "/organization/social/tiktok/videos" && request.method === "GET") { const u=await verifyFirebaseUser(request),orgId=String(url.searchParams.get("organizationId")||""),accountId=String(url.searchParams.get("accountId")||"");const org=await firestoreGet("organizationWorkspaces/"+orgId);const member=Array.isArray(org?.members)?org.members.find(x=>String(x.id||"")===u.uid&&x.status!=="inactive"):null;if(!member)throw new Error("organization_social_access_denied");return json(await tiktokVideos(env,u.uid,accountId,orgId)); }
      if (url.pathname === "/organization/social/publish" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request),orgId=String(b.organizationId||""),accountId=String(b.accountId||"");const org=await firestoreGet("organizationWorkspaces/"+orgId);const member=Array.isArray(org?.members)?org.members.find(x=>String(x.id||"")===u.uid&&x.status!=="inactive"):null;if(!member||!["owner","admin","editor"].includes(String(member.role||"")))throw new Error("organization_social_access_denied");return json(await publishTikTok(env,u.uid,accountId,orgId,b)); }
      if (url.pathname === "/organization/social/oauth/start" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request),platform=String(b.platform||"");if(!["youtube","tiktok"].includes(platform))throw new Error("organization_social_platform_not_supported");const orgId=String(b.organizationId||u.uid),org=await firestoreGet("organizationWorkspaces/"+orgId),member=Array.isArray(org?.members)?org.members.find(x=>String(x.id||"")===u.uid&&x.status!=="inactive"):null;if(!member||!["owner","admin","editor"].includes(String(member.role||"")))throw new Error("organization_social_access_denied");return json(await socialStart(env,u.uid,platform,String(b.redirectUri||"https://bqsqwadai.com/social/oauth/callback"),orgId)); }
      if (url.pathname === "/social/platforms" && request.method === "GET") { return json({platforms:["x","youtube","tiktok"],config:{enabled:true}}); }
      if (url.pathname === "/social/oauth/start" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await socialStart(env,u.uid,String(b.platform||""),String(b.redirectUri||"https://bqsqwadai.com/social/oauth/callback"),String(b.workspaceId||""))); }
      if (url.pathname === "/social/x/action" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await manageXAction(env,u.uid,String(b.accountId||""),b)); }
      if (url.pathname === "/social/accounts" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json({accounts:await listSocialAccounts(u.uid,String(url.searchParams.get("workspaceId")||""))}); }
      if (url.pathname === "/social/tiktok/profile" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await tiktokProfile(env,u.uid,String(url.searchParams.get("accountId")||""),String(url.searchParams.get("workspaceId")||""))); }
      if (url.pathname === "/social/tiktok/videos" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await tiktokVideos(env,u.uid,String(url.searchParams.get("accountId")||""),String(url.searchParams.get("workspaceId")||""))); }
      if (url.pathname === "/social/publish" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await publishTikTok(env,u.uid,String(b.accountId||""),String(b.workspaceId||""),b)); }
      if (url.pathname === "/social/youtube/channel" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await youtubeChannel(env,u.uid,String(url.searchParams.get("accountId")||""))); }
      if (url.pathname === "/organization/production/live/content-policy" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request),sid=String(b.sessionId||""); if(!sid)throw new Error("live_session_required"); const s=await firestoreGet("organizationProductionLiveSessions/"+sid); if(!s||s.uid!==u.uid)throw new Error("live_session_forbidden"); const detected=b.protectedBroadcastDetected===true; let rights=false; const rid=String(b.rightsId||s.rightsId||""); if(rid){const r=await firestoreGet("mediaRights/"+rid);rights=Boolean(r&&r.uid===u.uid&&r.status==="verified"&&(!b.workspaceId||String(r.workspaceId||"")===String(b.workspaceId)));} if(!detected||rights){await firestoreSet("organizationProductionLiveSessions/"+sid,{lastPolicyCheck:{protectedBroadcastDetected:detected,confidence:Math.max(0,Math.min(1,Number(b.confidence||0))),detectionSource:String(b.detectionSource||"runtime_content_analysis").slice(0,80),rightsVerified:rights,action:"allow",checkedAt:new Date()},updatedAt:new Date()},true);return json({allowed:true,stopped:false,rightsVerified:rights});} if(s.platform==="youtube"&&s.accountId&&s.broadcastId)await youtubeTransition(env,u.uid,{accountId:s.accountId,broadcastId:s.broadcastId,status:"complete"}).catch(()=>{}); await firestoreSet("organizationProductionLiveSessions/"+sid,{status:"stopped_policy_violation",stopReason:"protected_broadcast_without_verified_rights",lastPolicyCheck:{protectedBroadcastDetected:true,rightsVerified:false,action:"stop",checkedAt:new Date()},updatedAt:new Date()},true); return json({allowed:false,stopped:true,reason:"protected_broadcast_without_verified_rights"}); }
      if (url.pathname === "/organization/production/youtube/live" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await youtubeCreateLive(env,u.uid,await readBody(request))); }
      if (url.pathname === "/organization/production/youtube/live/transition" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await youtubeTransition(env,u.uid,await readBody(request))); }
      if (url.pathname === "/organization/production/youtube/live/status" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await youtubeStatus(env,u.uid,{accountId:url.searchParams.get("accountId"),broadcastId:url.searchParams.get("broadcastId")})); }
      if (url.pathname === "/organization/production/youtube/live/chat" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await youtubeChat(env,u.uid,{accountId:url.searchParams.get("accountId"),liveChatId:url.searchParams.get("liveChatId")})); }
      if (url.pathname === "/organization/production/youtube/live/chat/reply" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await youtubeReply(env,u.uid,await readBody(request))); }
      if (url.pathname.startsWith("/runtime/") && request.method === "POST") { const u=await verifyFirebaseUser(request),p=url.pathname.split("/"),type=p[2]; return json(await runtimeCreate(u.uid,type,await readBody(request))); }
      if (url.pathname.startsWith("/runtime/") && request.method === "GET") { const u=await verifyFirebaseUser(request),p=url.pathname.split("/"),type=p[2]; return json({items:await runtimeList(u.uid,type)}); }
      if (url.pathname.startsWith("/runtime/") && request.method === "PATCH") { const u=await verifyFirebaseUser(request),p=url.pathname.split("/"),type=p[2],id=decodeURIComponent(p[3]||""); return json(await runtimeUpdate(u.uid,type,id,await readBody(request))); }
      if (url.pathname === "/organization/production" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await getProduction(u.uid)); }
      if (url.pathname === "/organization/production/config" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await updateProductionConfig(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/production/source" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await upsertSource(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/production/source/remove" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await removeSource(u.uid,(await readBody(request)).sourceId)); }
      if (url.pathname === "/organization/production/scene" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await upsertScene(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/production/destination" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await upsertDestination(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/production/state" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await setProductionState(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/production/health" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await updateHealth(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/production/event" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await addProductionEvent(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/production/ai-live" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await getOrganizationAiLiveConfig(u.uid)); }
      if (url.pathname === "/organization/production/ai-live" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await updateOrganizationAiLiveConfig(u.uid,await readBody(request))); }

      if (url.pathname === "/business/plan-builder" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        await requireConsent(u.uid);
        const b = await readBody(request);
        const w = await businessWorkspace(u.uid, { workspaceId: b.workspaceId });
        const brief = String(b.brief || b.message || "").trim().slice(0, 12000);
        if (!brief) throw new Error("business_plan_brief_required");
        return json(await withSharedCreditCharge(u, "business_ai", async control => {
          const z = await runProvider(env, {
            primaryProviderId: control.providerId,
            primaryModelId: control.modelId,
            fallbackProviderIds: control.config.fallbackProviderIds
          }, {
            message: brief,
            system: "You are the BQ Sqwad AI Business Plan Builder. Use only supplied business information. Do not invent market facts, guarantees, licenses, tax or legal status. Return executive summary, objectives, target customers, value proposition, operations, marketing, sales, technology/AI plan, risks, KPIs and 30/60/90-day actions."
          });
          return { ok: true, workspaceId: w.workspaceId, answer: z.text, providerId: z.providerId, modelId: z.modelId };
        }));
      }
      if (url.pathname === "/business/advanced-ai" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        await requireConsent(u.uid);
        const b = await readBody(request);
        return json(await withSharedCreditCharge(u, "business_ai", async () => advancedAiNative(env, u.uid, b)));
      }
      if (url.pathname === "/business/advanced-ai/jobs" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json({jobs:await advancedJobs(u.uid,String(url.searchParams.get("workspaceId")||""))}); }
      if (url.pathname === "/business/integrations" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await integrationConfig(u.uid,String(url.searchParams.get("workspaceId")||""))); }
      if (url.pathname === "/business/integrations" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await configureIntegration(u.uid,await readBody(request))); }
      if (url.pathname === "/business/integrations/supported" && request.method === "GET") { return json({integrations:supportedIntegrations()}); }
      if (url.pathname === "/business/enterprise" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await enterpriseConfig(u.uid,String(url.searchParams.get("workspaceId")||""))); }
      if (url.pathname === "/business/enterprise" && request.method === "PATCH") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await enterpriseUpdate(u.uid,String(b.workspaceId||""),b.config||b)); }
      if (url.pathname === "/business/workspace" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await businessWorkspace(u.uid,{workspaceId:url.searchParams.get("workspaceId")})); }
      if (url.pathname === "/business/workspace" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await businessWorkspace(u.uid,b)); }
      if (url.pathname === "/business/members" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await addBusinessMember(u.uid,await readBody(request))); }
      if (url.pathname === "/business/settings" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await updateBusinessSettings(u.uid,String(b.workspaceId||""),b)); }
      if (url.pathname === "/business/knowledge" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json({ok:true,record:await createBusinessRecord(u.uid,"knowledge",b)}); }
      if (url.pathname === "/business/leads" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json({ok:true,record:await createBusinessRecord(u.uid,"lead",b)}); }
      if (url.pathname === "/business/inbox" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json({ok:true,record:await createBusinessExtended(u.uid,"inbox",b)}); }
      if (url.pathname === "/business/records" && request.method === "GET") { const u=await verifyFirebaseUser(request),type=String(url.searchParams.get("type")||""),w=String(url.searchParams.get("workspaceId")||""); const map={assistant:"businessAssistants",knowledge:"businessKnowledge",contact:"businessContacts",lead:"businessLeads",pipeline:"businessPipelines",appointment:"businessAppointments",activity:"businessCrmActivities"}; if(!map[type]) throw new Error("unsupported_business_record"); await requireBusinessAccess(u.uid,w); return json({items:await firestoreQuery(map[type],{filters:[{field:{fieldPath:"workspaceId"},op:"EQUAL",value:{stringValue:w}}],limit:100})}); }
      if (url.pathname === "/business/records" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json({ok:true,record:await createBusinessRecord(u.uid,String(b.type||""),b)}); }
      if (url.pathname.startsWith("/business/records/") && request.method === "PATCH") { const u=await verifyFirebaseUser(request),parts=url.pathname.split("/"); const type=parts[3],rid=decodeURIComponent(parts[4]||""); return json(await updateBusinessRecord(u.uid,type,rid,await readBody(request))); }
      if (url.pathname.startsWith("/business/records/") && request.method === "DELETE") { const u=await verifyFirebaseUser(request),parts=url.pathname.split("/"); return json(await deleteBusinessRecord(u.uid,parts[3],decodeURIComponent(parts[4]||""))); }
      if (url.pathname === "/business/extended" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await listBusinessExtended(u.uid,String(url.searchParams.get("workspaceId")||""))); }
      if (url.pathname === "/business/extended" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await createBusinessExtended(u.uid,String(b.type||""),b)); }
      if (url.pathname.startsWith("/business/extended/") && request.method === "PATCH") { const u=await verifyFirebaseUser(request),p=url.pathname.split("/"); return json(await updateBusinessExtended(u.uid,p[3],decodeURIComponent(p[4]||""),await readBody(request))); }
      if (url.pathname === "/business/automations/run" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await runBusinessAutomation(u.uid,String(b.automationId||""),b.event||{})); }
      if (url.pathname === "/business/automations/webhook" && request.method === "POST") { const b=await readBody(request); return json(await receiveBusinessAutomationWebhook(String(b.token||""),b.event||{})); }
      if (url.pathname === "/business/assistant" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        await requireConsent(u.uid);
        const b = await readBody(request);
        const w = await businessWorkspace(u.uid, { workspaceId: b.workspaceId });
        const k = await businessKnowledgeForWorkspace(u.uid, w.workspaceId, 50);
        return json(await withSharedCreditCharge(u, "business_ai", async control => {
          const z = await runProvider(env, {
            primaryProviderId: control.providerId,
            primaryModelId: control.modelId,
            fallbackProviderIds: control.config.fallbackProviderIds
          }, {
            message: String(b.message || ""),
            system: "You are the BQ Sqwad AI Business Assistant. Keep business workspace information isolated from personal workspace context. Use only supplied business knowledge and do not invent business facts.\nBusiness knowledge:\n" + k.map(x => x.content || x.text || "").filter(Boolean).join("\n")
          });
          return { answer: z.text || "", providerId: z.providerId || null, modelId: z.modelId || null, workspaceId: w.workspaceId };
        }));
      }

      if (url.pathname === "/admin/route" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const id=String(url.searchParams.get("capability")||"");if(!id)throw new Error("capability_required");return json(await firestoreGet("adminAdvancedOptions/"+(id.startsWith("capability_")?id:"capability_"+id))||{}); }
      if (url.pathname === "/admin/route" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request),id=String(b.id||b.capability||"");if(!id)throw new Error("capability_required");const key=id.startsWith("capability_")?id:"capability_"+id;await firestoreSet("adminAdvancedOptions/"+key,{...b,id:key,updatedBy:u.uid,updatedAt:new Date()},true);return json(await firestoreGet("adminAdvancedOptions/"+key)); }
      if (url.pathname === "/admin/workspace-scope-guards" && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw new Error("Admin access required");
        const stored = await firestoreGet("adminAdvancedOptions/workspace_scope_guards") || {};
        const saved = stored.enabledByWorkspace || stored.config?.enabledByWorkspace || {};
        const names = ["learning", "truthguard", "life_mathis", "companion", "personal_intelligence", "data_analyst", "frontier_lab", "video"];
        return json({ enabledByWorkspace: Object.fromEntries(names.map(name => [name, saved[name] !== false])), updatedAt: stored.updatedAt || null });
      }
      if (url.pathname === "/admin/workspace-scope-guards" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw new Error("Admin access required");
        const body = await readBody(request);
        const incoming = body.enabledByWorkspace;
        if (!incoming || typeof incoming !== "object" || Array.isArray(incoming)) {
          throw Object.assign(new Error("enabledByWorkspace_required"), { httpStatus: 400, safeError: "enabledByWorkspace_required" });
        }
        const names = ["learning", "truthguard", "life_mathis", "companion", "personal_intelligence", "data_analyst", "frontier_lab", "video"];
        const current = await firestoreGet("adminAdvancedOptions/workspace_scope_guards") || {};
        const previous = current.enabledByWorkspace || current.config?.enabledByWorkspace || {};
        const enabledByWorkspace = Object.fromEntries(names.map(name => {
          const value = incoming[name] == null ? previous[name] !== false : incoming[name];
          if (typeof value !== "boolean") throw Object.assign(new Error("invalid_scope_guard_value"), { httpStatus: 400, safeError: "invalid_scope_guard_value" });
          return [name, value];
        }));
        await firestoreSet("adminAdvancedOptions/workspace_scope_guards", { enabledByWorkspace, updatedBy: u.uid, updatedAt: new Date() }, true);
        return json({ ok: true, enabledByWorkspace });
      }
      const livePricingAdminKeys = {
        "/admin/live-voice-pricing": "live_voice_pricing",
        "/admin/live-camera-pricing": "live_camera_pricing",
        "/admin/dictation-pricing": "dictation_pricing"
      };
      const livePricingAdminKey = livePricingAdminKeys[url.pathname];
      if (livePricingAdminKey && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw new Error("Admin access required");
        const stored = await firestoreGet("adminAdvancedOptions/" + livePricingAdminKey) || {};
        const config = normalizeLivePricing(stored.config || stored, {}, livePricingAdminKey);
        return json({ enabled: stored.enabled !== false && config.enabled !== false, killSwitch: stored.killSwitch === true || config.killSwitch === true, config });
      }
      if (livePricingAdminKey && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw new Error("Admin access required");
        const b = await readBody(request);
        const current = await firestoreGet("adminAdvancedOptions/" + livePricingAdminKey) || {};
        const incoming = b.config && typeof b.config === "object" ? b.config : b;
        const config = normalizeLivePricing(incoming, current.config || current, livePricingAdminKey);
        const enabled = b.enabled == null ? config.enabled : b.enabled === true;
        const killSwitch = b.killSwitch == null ? config.killSwitch : b.killSwitch === true;
        await firestoreSet("adminAdvancedOptions/" + livePricingAdminKey, { enabled, killSwitch, config: { ...config, enabled, killSwitch }, updatedBy: u.uid, updatedAt: new Date() }, true);
        return json(await firestoreGet("adminAdvancedOptions/" + livePricingAdminKey));
      }
      if (url.pathname === "/live/pricing" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        enforceRateLimit(user.uid);
        const readPublicPricing = async (key) => {
          const stored = await firestoreGet("adminAdvancedOptions/" + key) || {};
          const conf = normalizeLivePricing(stored.config || stored, {}, key);
          const enabled = stored.enabled !== false && stored.killSwitch !== true && conf.enabled !== false && conf.killSwitch !== true;
          return { enabled, free: enabled && conf.creditsPerMinute === 0 && conf.startFeeCredits === 0, creditsPerMinute: conf.creditsPerMinute, startFeeCredits: conf.startFeeCredits };
        };
        return json({ liveVoice: await readPublicPricing("live_voice_pricing"), liveCamera: await readPublicPricing("live_camera_pricing"), dictation: await readPublicPricing("dictation_pricing") });
      }
      
      if (url.pathname === "/admin/web-search-pricing" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json(await firestoreGet("adminAdvancedOptions/web_search_pricing")||{}); }
      if (url.pathname === "/admin/web-search-pricing" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request);await firestoreSet("adminAdvancedOptions/web_search_pricing",{enabled:b.enabled!==false,config:b.config&&typeof b.config==="object"?b.config:{},updatedBy:u.uid,updatedAt:new Date()},true);return json(await firestoreGet("adminAdvancedOptions/web_search_pricing")); }
      if (url.pathname === "/admin/training-library" && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus:403 });
        const [contributions, consentRows] = await Promise.all([
          firestoreQuery("consented_contributions", { orderBy:{field:"createdAt",direction:"DESCENDING"}, limit:500 }).catch(() => []),
          firestoreQuery("userConsents", {
            filters:[{field:{fieldPath:"trainingConsent"},op:"EQUAL",value:{booleanValue:true}}],
            limit:500
          }).catch(() => [])
        ]);
        const active = contributions.filter(row =>
          row.status !== "withdrawn" && row.contentDeleted !== true &&
          typeof row.prompt === "string" && row.prompt.trim() &&
          typeof row.response === "string" && row.response.trim()
        );
        const items = active.map(row => ({
          id:String(row.id || String(row.__name || "").split("/").pop()).slice(0,120),
          owner:String(row.userId || "").slice(0,8) + "…",
          workspace:String(row.workspace || "main_chat").slice(0,60),
          dataType:String(row.dataType || "chat").slice(0,24),
          status:String(row.status || "pending_review").slice(0,32),
          prompt:String(row.prompt).slice(0,4000),
          response:String(row.response).slice(0,4000),
          createdAt:String(row.createdAt || "")
        })).sort((a,b)=>String(b.createdAt).localeCompare(String(a.createdAt)));
        return json({
          items:items.slice(0,500),
          userCount:consentRows.length,
          itemCount:items.length,
          updatedAt:new Date().toISOString()
        });
      }
      if (url.pathname === "/admin/auth-background-video" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request),enabled=b.enabled===true,urlValue=String(b.url||"").trim();if(enabled&&!/^https:\/\//i.test(urlValue))throw new Error("auth_background_video_url_required");await firestoreSet("publicAppConfig/app",{authBackgroundVideoUrl:enabled?urlValue:null,authBackgroundVideoEnabled:enabled,updatedBy:u.uid,updatedAt:new Date()},true);return json(await firestoreGet("publicAppConfig/app")); }
      if (url.pathname === "/admin/feature-announcement" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw Object.assign(new Error("Admin access required"),{httpStatus:403});const b=await readBody(request),enabled=b.enabled===true,title=String(b.title||"").trim().slice(0,100),body=String(b.body||"").trim().slice(0,500),feature=String(b.feature||"").trim().slice(0,80),id=String(b.id||crypto.randomUUID()).trim().slice(0,120);if(enabled&&(!title||!body))throw new Error("feature_announcement_title_and_body_required");await firestoreSet("publicAppConfig/app",{dailyFeatureAnnouncement:{id,title,body,feature,enabled,updatedAt:new Date(),updatedBy:u.uid},updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,dailyFeatureAnnouncement:{id,title,body,feature,enabled}}); }
      if (url.pathname === "/admin/chat-background" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw Object.assign(new Error("Admin access required"),{httpStatus:403});const b=await readBody(request),enabled=b.enabled===true,urlValue=String(b.url||"").trim();if(enabled&&!/^https:\/\//i.test(urlValue))throw new Error("chat_background_https_url_required");await firestoreSet("publicAppConfig/app",{chatBackgroundUrl:enabled?urlValue:null,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,chatBackgroundUrl:enabled?urlValue:null}); }
      if (url.pathname === "/admin/app-layout" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json(await firestoreGet("publicAppConfig/app")||{}); }
      if (url.pathname === "/admin/app-layout" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request),ids=["CHAT","VIDEO","SEARCH","TRUTH_GUARD","FRONTIER_LAB","CAMERA","VOICE","PERSONAL","LIFE","COMPANION","LEARNING","BUSINESS","ORGANIZATION_STUDIO","DATA_ANALYSIS","ADMIN"],order=[...new Set((Array.isArray(b.workspaceOrder)?b.workspaceOrder:String(b.workspaceOrder||"").split(",")).map(x=>String(x).toUpperCase()).filter(x=>ids.includes(x)))];for(const id of ids)if(!order.includes(id))order.push(id);const enabled=Object.fromEntries(ids.map(id=>[id,b.workspaceEnabled?.[id]!==false]));await firestoreSet("publicAppConfig/app",{workspaceOrder:order,workspaceEnabled:enabled,themeMode:["system","light","dark"].includes(String(b.themeMode||""))?String(b.themeMode):"system",updatedBy:u.uid,updatedAt:new Date()},true);return json(await firestoreGet("publicAppConfig/app")); }
      if (url.pathname === "/admin/staff/roles" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json({roles:{moderator:["admin_console","moderate"],editor:["admin_console","content"],customer_agent:["admin_console","customer_support"],designer:["admin_console","design"],analyst:["admin_console","analytics"],operations:["admin_console","operations"]}}); }
      if (url.pathname === "/admin/staff" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json({staff:await firestoreQuery("adminStaff",{limit:200})}); }
      if (url.pathname === "/admin/staff" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request),uid=String(b.uid||"").trim(),role=String(b.role||"").trim();if(!uid||!role)throw new Error("admin_staff_uid_and_supported_role_required");await firestoreSet("adminStaff/"+uid,{uid,role,permissions:Array.isArray(b.permissions)?b.permissions.map(String).slice(0,40):[],active:b.active!==false,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,uid,role,active:b.active!==false}); }
      if (url.pathname === "/admin/file-box-retention" && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus:403 });
        const config = await firestoreGet("adminAdvancedOptions/fileBoxRetention") || {};
        const rows = await firestoreQuery("fileBox", { limit:500 }).catch(() => []);
        const now = Date.now();
        let activeItems = 0, expiredItems = 0;
        for (const row of rows) for (const item of (Array.isArray(row.items) ? row.items : [])) {
          if (Date.parse(String(item.expiresAt || "")) > now) activeItems++; else expiredItems++;
        }
        return json({ defaultRetentionDays:[7,14,30,90].includes(Number(config.defaultRetentionDays)) ? Number(config.defaultRetentionDays) : 30, scannedAccounts:rows.length, activeItems, expiredItems, updatedAt:config.updatedAt || null });
      }
      if (url.pathname === "/admin/file-box-retention" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus:403 });
        const body = await readBody(request);
        const days = Number(body.defaultRetentionDays);
        if (![7,14,30,90].includes(days)) return json({ error:"invalid_retention_days" }, 400);
        const result = { defaultRetentionDays:days, updatedBy:u.uid, updatedAt:new Date().toISOString() };
        await firestoreSet("adminAdvancedOptions/fileBoxRetention", result, true);
        return json({ ok:true, ...result });
      }
      if (url.pathname === "/admin/config" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const x=await firestoreGet("adminAdvancedOptions/config"); return json(x||{}); }
      if (url.pathname === "/admin/controls" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const x=await firestoreQuery("adminAdvancedOptions",{limit:100}); return json({items:x}); }
      if (url.pathname === "/admin/advanced-options" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json({options:await firestoreQuery("adminAdvancedOptions",{limit:200})}); }
      if (url.pathname === "/admin/ai-billing/config" && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        const stored = await firestoreGet("adminAdvancedOptions/dynamic_billing_config").catch(() => null)
          || await firestoreGet("adminAdvancedOptions/dynamicBillingConfig").catch(() => null) || {};
        const profitMarkup = Math.max(0, Math.min(10, Number(stored.pricingVersion >= 4 ? (stored.profitMarkup ?? 3) : ([1.1, 1.3].includes(Number(stored.profitMarkup)) || !Number.isFinite(Number(stored.profitMarkup)) ? 3 : stored.profitMarkup))));
        const openAiRates = {
          inputPriceUsdPerMillion: Number.isFinite(Number(stored.openAiRates?.inputPriceUsdPerMillion)) ? Number(stored.openAiRates.inputPriceUsdPerMillion) : 0.05,
          outputPriceUsdPerMillion: Number.isFinite(Number(stored.openAiRates?.outputPriceUsdPerMillion)) ? Number(stored.openAiRates.outputPriceUsdPerMillion) : 0.25,
          cachedInputPriceUsdPerMillion: Number.isFinite(Number(stored.openAiRates?.cachedInputPriceUsdPerMillion)) ? Number(stored.openAiRates.cachedInputPriceUsdPerMillion) : 0.005
        };
        const featureRates = {
          audioInputUsdPerToken: Number(stored.featureRates?.audioInputUsdPerToken ?? 0.000032),
          audioOutputUsdPerToken: Number(stored.featureRates?.audioOutputUsdPerToken ?? 0.000064),
          transcriptionUsdPerMinute: Number(stored.featureRates?.transcriptionUsdPerMinute ?? 0.003),
          liveVoiceConnectionUsdPerMinute: Number(stored.featureRates?.liveVoiceConnectionUsdPerMinute ?? 0.05),
          liveCameraConnectionUsdPerMinute: Number(stored.featureRates?.liveCameraConnectionUsdPerMinute ?? 0.05),
          imageOutputUsdPerToken: Number(stored.featureRates?.imageOutputUsdPerToken ?? 0.000015),
          imageInputUsdPerToken: Number(stored.featureRates?.imageInputUsdPerToken ?? 0.000004),
          imageTextInputUsdPerToken: Number(stored.featureRates?.imageTextInputUsdPerToken ?? 0.0000025),
          tavilyCreditsPerSearch: Number(stored.featureRates?.tavilyCreditsPerSearch ?? 1.7),
          deepResearchCredits: Number(stored.featureRates?.deepResearchCredits ?? 5)
        };
        return json({ config: { profitMarkup, creditValueUsd: 0.07999, openAiRates, featureRates, updatedAt: stored.updatedAt || null }, updatedAt: stored.updatedAt || null });
      }
      if (url.pathname === "/admin/ai-billing/config" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        const b = await readBody(request);
        const profitMarkup = Number(b.profitMarkup ?? 3);
        if (!Number.isFinite(profitMarkup) || profitMarkup < 0 || profitMarkup > 10) {
          return json({ error: "invalid_profit_markup", message: "Markup must be between 0% and 1000%." }, 400);
        }
        const current = await firestoreGet("adminAdvancedOptions/dynamic_billing_config").catch(() => null) || {};
        const oldRates = current.openAiRates && typeof current.openAiRates === "object" ? current.openAiRates : {};
        const rawRates = b.openAiRates && typeof b.openAiRates === "object" ? b.openAiRates : {};
        const openAiRates = {
          inputPriceUsdPerMillion: Number(rawRates.inputPriceUsdPerMillion ?? oldRates.inputPriceUsdPerMillion ?? 0.05),
          outputPriceUsdPerMillion: Number(rawRates.outputPriceUsdPerMillion ?? oldRates.outputPriceUsdPerMillion ?? 0.25),
          cachedInputPriceUsdPerMillion: Number(rawRates.cachedInputPriceUsdPerMillion ?? oldRates.cachedInputPriceUsdPerMillion ?? 0.005)
        };
        const oldFeatureRates = current.featureRates && typeof current.featureRates === "object" ? current.featureRates : {};
        const rawFeatureRates = b.featureRates && typeof b.featureRates === "object" ? b.featureRates : {};
        const defaults = {
          audioInputUsdPerToken: 0.000032,
          audioOutputUsdPerToken: 0.000064,
          transcriptionUsdPerMinute: 0.003,
          liveVoiceConnectionUsdPerMinute: 0.05,
          liveCameraConnectionUsdPerMinute: 0.05,
          imageOutputUsdPerToken: 0.000015,
          imageInputUsdPerToken: 0.000004,
          imageTextInputUsdPerToken: 0.0000025,
          tavilyCreditsPerSearch: 1.7,
          deepResearchCredits: 5
        };
        const featureRates = Object.fromEntries(Object.entries(defaults).map(([key, fallback]) => [key, Number(rawFeatureRates[key] ?? oldFeatureRates[key] ?? fallback)]));
        if (Object.values(openAiRates).some(value => !Number.isFinite(value) || value < 0 || value > 10000)) {
          return json({ error: "invalid_openai_rates", message: "OpenAI token rates must be between 0 and 10000 USD per million tokens." }, 400);
        }
        if (Object.values(featureRates).some(value => !Number.isFinite(value) || value < 0 || value > 1000000)) {
          return json({ error: "invalid_feature_rates", message: "Feature rates must be finite, non-negative numbers within supported limits." }, 400);
        }
        const config = { profitMarkup, pricingVersion: 4, creditValueUsd: 0.07999, openAiRates, featureRates, updatedBy: u.uid, updatedAt: new Date().toISOString() };
        await firestoreSet("adminAdvancedOptions/dynamic_billing_config", config, true);
        return json({ ok: true, config: { profitMarkup, creditValueUsd: 0.07999, openAiRates, featureRates, updatedAt: config.updatedAt } });
      }
      if (url.pathname === "/admin/ai-billing/analytics" && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        const limit = Math.min(500, Math.max(1, Number(url.searchParams.get("limit") || 100)));
        return json({ analytics: await getTokenUsageReport({ limit }), lastRefreshedAt: new Date().toISOString() });
      }
      if (url.pathname === "/admin/ai-usage" && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        const limit = Math.min(500, Math.max(1, Number(url.searchParams.get("limit") || 100)));
        return json(await getTokenUsageReport({ limit }));
      }
      if (url.pathname === "/admin/ai-models" && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        await ensureCurrentModelRegistry({ openai: DEFAULT_MODELS.openai, gemini: DEFAULT_MODELS.gemini });
        return json(await listAiModels({ admin: true }));
      }
      if (url.pathname === "/admin/ai-models/status" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        const b = await readBody(request);
        return json(await checkAiModelStatus(env, String(b.id || ""), u.uid));
      }
      if (url.pathname === "/admin/ai-models/validate" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        const b = await readBody(request);
        return json(await validateAiModel(env, b.model && typeof b.model === "object" ? b.model : b));
      }
      if (url.pathname === "/admin/ai-models" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        const b = await readBody(request);
        if (!b.model || typeof b.model !== "object" || Array.isArray(b.model)) return json({ error: "model_object_required" }, 400);
        const validation = await validateAiModel(env, b.model);
        return json(await saveAiModel({ ...b.model, validation }, u.uid));
      }
      if (url.pathname === "/admin/advanced-option" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request),id=String(b.id||"").trim();if(!id)return json({error:"option_id_required"},400);await firestoreSet("adminAdvancedOptions/"+id,{...b,id,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,option:{...b,id}}); }
      if (url.pathname === "/admin/control" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request),id=String(b.controlId||b.id||"").trim();if(!id)return json({error:"control_id_required"},400);await firestoreSet("adminAdvancedOptions/"+id,{...b,id,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,option:{...b,id}}); }
      if (url.pathname === "/admin/plan" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request),id=String(b.id||"").trim();if(!/^[a-z0-9-]{1,80}$/.test(id))return json({error:"invalid_plan_id"},400);const plan={name:String(b.name||"").trim().slice(0,120),active:b.active!==false,amountMinor:Math.max(0,Number(b.amountMinor||0)),currency:"USD",billingPeriod:String(b.billingPeriod||"monthly").slice(0,30),features:Array.isArray(b.features)?b.features.map(String).slice(0,50):[],limits:b.limits&&typeof b.limits==="object"?b.limits:{},updatedBy:u.uid,updatedAt:new Date()};await firestoreSet("plans/"+id,plan,true);if(String(b.module||"").toLowerCase()==="business"||id.startsWith("biz")||id==="businessenterprise")await firestoreSet("businessPlanStatus/"+id,{planId:id,active:plan.active,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,plan:{id,...plan}}); }
      if (url.pathname === "/admin/business/test-session" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json(await getBusinessTestSession(u.uid)); }
      if (url.pathname === "/admin/business/test-session" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request);return json(await setBusinessTestSession(u.uid,String(b.planId||""),b.enabled!==false,Number(b.durationMinutes||120))); }
      if (url.pathname === "/admin/business/test-suite" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json(await runBusinessPlanTestSuite(u.uid,String((await readBody(request)).planId||""))); }
      if (url.pathname === "/admin/business/subscriptions" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json({items:await listAdminBusinessSubscriptions()}); }
      if (url.pathname === "/admin/business/subscription-status" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request);return json(await setAdminBusinessSubscriptionStatus(u.uid,String(b.uid||""),String(b.planId||""),b.active===true,b.confirmDeactivation===true)); }
      if (url.pathname === "/admin/business/plan-status" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request),id=String(b.planId||"");if(!id)return json({error:"business_plan_id_required"},400);if(b.active===false&&b.confirmDeactivation!==true)return json({error:"business_plan_deactivation_confirmation_required"},400);await firestoreSet("businessPlanStatus/"+id,{planId:id,active:b.active!==false,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,planId:id,active:b.active!==false}); }
      if (url.pathname === "/admin/runtime" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const type=String(url.searchParams.get("type")||"jobs");return json({items:await runtimeList(u.uid,type)}); }
      if (url.pathname === "/admin/runtime" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request);return json(await runtimeCreate(u.uid,String(b.type||"jobs"),b)); }
      if (url.pathname.startsWith("/admin/runtime/") && request.method === "PATCH") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const p=url.pathname.split("/");return json(await runtimeUpdate(u.uid,p[3],decodeURIComponent(p[4]||""),await readBody(request))); }
      if (url.pathname === "/admin/social-accounts" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json({accounts:await listSocialAccounts(u.uid)}); }
      if (url.pathname === "/admin/social-oauth/start" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request);return json(await socialStart(env,u.uid,String(b.platform||""),String(b.redirectUri||"https://bqsqwadai.com/social/oauth/callback"),"")); }
      if (url.pathname === "/admin/social-test" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request),p=String(b.platform||"");if(!["x","youtube","tiktok"].includes(p))throw new Error("unsupported_social_platform");const keys={x:["X_CLIENT_ID","X_CLIENT_SECRET"],youtube:["YOUTUBE_CLIENT_ID","YOUTUBE_CLIENT_SECRET"],tiktok:["TIKTOK_CLIENT_KEY","TIKTOK_CLIENT_SECRET"]};return json({ok:Boolean(env[keys[p][0]]&&env[keys[p][1]]),platform:p,clientConfigured:Boolean(env[keys[p][0]]),clientSecretConfigured:Boolean(env[keys[p][1]]),testMode:false}); }
      if (url.pathname === "/admin/social-secret" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json({error:"cloudflare_secret_required",message:"Provider secrets are managed as Cloudflare Worker secrets, not written into Firestore."},409); }
      if (url.pathname === "/admin/security/status" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const policy=await firestoreGet("adminAdvancedOptions/admin_security_policy").catch(()=>null);return json({...await securityStatusNative(),encryptionKeyConfigured:Boolean(env.BQ_SECRET_ENCRYPTION_KEY),unlockRequired:isAdminUnlockRequired(policy)}); }
      if (url.pathname === "/admin/security/session" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw Object.assign(new Error("Admin access required"),{httpStatus:403});return json(await adminSessionStatusNative(u.uid)); }
      if (url.pathname === "/admin/security/password" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json(await configurePasswordNative(env,u.uid,String((await readBody(request)).password||""))); }
      if (url.pathname === "/admin/security/totp/start" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json(await beginTotpNative(env,u.uid)); }
      if (url.pathname === "/admin/security/totp/confirm" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");enforceRateLimit("admin-auth:"+u.uid,8);const b=await readBody(request);return json(await confirmTotpNative(env,u.uid,String(b.code||""))); }
      if (url.pathname === "/admin/security/unlock" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");enforceRateLimit("admin-auth:"+u.uid,8);const b=await readBody(request);return json(await unlockAdminNative(env,u.uid,String(b.password||""),String(b.code||""))); }
      if (url.pathname === "/admin/security/biometric" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json(await biometricStatusNative()); }
       if (url.pathname === "/admin/security/biometric" && request.method === "DELETE") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");await requireAdminSessionNative(u.uid);return json(await removeBiometricNative(u.uid)); }
      if (url.pathname === "/admin/security/biometric/register" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request);return json(await registerBiometricNative(u.uid,String(b.publicKeyBase64||b.publicKey||""))); }
      if (url.pathname === "/admin/security/biometric/challenge" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json(await beginBiometricNative(u.uid)); }
      if (url.pathname === "/admin/security/biometric/verify" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request);return json(await verifyBiometricNative(u.uid,String(b.challengeId||""),String(b.signatureBase64||b.signature||""))); }
      if (url.pathname === "/admin/provider-secret" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json({error:"cloudflare_secret_required",message:"Provider credentials are managed as Cloudflare Worker secrets. Update the Worker secret instead of writing provider credentials to Firestore."},409); }
      if (url.pathname === "/admin/providers" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const rows=await firestoreQuery("adminAdvancedOptions",{limit:200});return json({providers:providerSecretStatus(env),routes:rows.filter(x=>String(x.__name||"").split("/").pop().startsWith("capability_")).map(x=>({capability:String(x.__name||"").split("/").pop().replace(/^capability_/,""),providerId:x.providerId||"openai",modelId:x.modelId||DEFAULT_MODELS.openai,enabled:x.enabled!==false&&x.killSwitch!==true}))}); }
      if (url.pathname === "/admin/social-integrations" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const x=await firestoreGet("socialIntegrations/global");return json({config:x||{enabled:true},platforms:["x","youtube","tiktok"]}); }
      if (url.pathname === "/admin/social-integrations" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request);await firestoreSet("socialIntegrations/global",{enabled:b.enabled!==false,testMode:b.testMode!==false,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true}); }
      if (url.pathname === "/admin/notifications/broadcast" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request),title=String(b.title||"").trim().slice(0,120),body=String(b.body||"").trim().slice(0,1200);if(!title||!body)throw new Error("notification_title_and_body_required");const rows=await firestoreQuery("notificationTokens",{filters:[{field:{fieldPath:"enabled"},op:"EQUAL",value:{booleanValue:true}}],limit:100});const tokens=rows.map(x=>String(x.token||"")).filter(Boolean),project=JSON.parse(env.BQ_FIREBASE_SERVICE_ACCOUNT_JSON).project_id,result=await broadcast(env,project,tokens,title,body,b.data&&typeof b.data==="object"?Object.fromEntries(Object.entries(b.data).slice(0,20).map(([k,v])=>[String(k).slice(0,40),String(v).slice(0,500)])):{}),id=crypto.randomUUID();await firestoreCreate("adminBroadcasts/"+id,{id,title,body,data:b.data||{},...result,createdBy:u.uid,createdAt:new Date()});return json({ok:true,broadcastId:id,...result}); }
      if (url.pathname === "/admin/access" && request.method === "GET") { const u=await verifyFirebaseUser(request); return isSuperAdmin(u,env)?json({allowed:true}):json({allowed:false,error:"Admin access required"},403); }
      if (url.pathname === "/admin/daily-free-credits" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw new Error("Admin access required");
        const config = await firestoreGet("adminAdvancedOptions/daily_free_credits_config");
        return json({ enabled: config == null ? true : config.enabled === true, credits: Math.max(0, Math.min(100000, Number(config?.credits ?? 5))), resetTimeZone: "UTC" });
      }
      if (url.pathname === "/admin/daily-free-credits" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw new Error("Admin access required");
        const body = await readBody(request);
        const credits = Number(body.credits);
        if (!Number.isInteger(credits) || credits < 0 || credits > 100000) {
          return json({ error: "daily_free_credits_must_be_an_integer_between_0_and_100000" }, 400);
        }
        const config = { id: "daily_free_credits_config", enabled: body.enabled === true, credits, updatedBy: user.uid, updatedAt: new Date(), resetTimeZone: "UTC" };
        await firestoreSet("adminAdvancedOptions/daily_free_credits_config", config, true);
        return json({ ok: true, enabled: config.enabled, credits, resetTimeZone: "UTC" });
      }

      if (url.pathname === "/admin/credit-trial" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw new Error("Admin access required");
        const config = await firestoreGet("adminAdvancedOptions/credit_trial_config");
        return json({ enabled: config?.enabled === true, credits: Math.max(0, Number(config?.credits || 5)), days: Math.max(1, Math.min(30, Number(config?.days || 5))) });
      }
      if (url.pathname === "/admin/credit-trial" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw new Error("Admin access required");
        const body = await readBody(request);
        const credits = Number(body.credits);
        const days = Number(body.days);
        if (!Number.isFinite(credits) || credits < 1 || credits > 100000 || !Number.isInteger(days) || days < 1 || days > 30) {
          return json({ error: "trial_credits_must_be_positive_and_days_must_be_1_to_30" }, 400);
        }
        const config = { id: "credit_trial_config", enabled: body.enabled === true, credits, days, updatedBy: user.uid, updatedAt: new Date() };
        await firestoreSet("adminAdvancedOptions/credit_trial_config", config, true);
        return json({ ok: true, enabled: config.enabled, credits, days });
      }

      if (url.pathname === "/admin/test-mode" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isRootSuperAdmin(u,env)) throw Object.assign(new Error("Super-admin access required"), { httpStatus: 403 }); const x=await firestoreGet("adminAdvancedOptions/admin_testing_mode"); return json({enabled:x?.enabled===true,serverControlled:true,scope:"super_admin_only"}); }
      if (url.pathname === "/admin/test-mode" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isRootSuperAdmin(u,env)) throw Object.assign(new Error("Super-admin access required"), { httpStatus: 403 }); const b=await readBody(request); await firestoreSet("adminAdvancedOptions/admin_testing_mode",{id:"admin_testing_mode",enabled:b.enabled===true,version:Number((await firestoreGet("adminAdvancedOptions/admin_testing_mode"))?.version||1)+1,updatedBy:u.uid,updatedAt:new Date()},true); return json({ok:true,enabled:b.enabled===true,scope:"super_admin_only"}); }
      if (url.pathname === "/admin/plan-catalog" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const x=await firestoreGet("planCatalog/config"); const stored=x?.catalog; const hasPlans=Object.values(stored?.sections||{}).some(rows=>Array.isArray(rows)&&rows.length>0); return json({version:Number(x?.version||COMMERCIAL_CATALOG.version||1),catalog:mergeCommercialCatalog(stored),source:hasPlans?"firestore+fallback":"code_fallback"}); }
      if (url.pathname === "/admin/plan-catalog" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const b=await readBody(request),catalog=b.catalog;if(!catalog||typeof catalog!=="object"||Array.isArray(catalog))return json({error:"catalog_object_required"},400);const ids=[],products=[];const validPlayId=value=>typeof value==="string"&&/^[a-z0-9-]+$/.test(value);const walk=v=>{if(!v||typeof v!=="object")return;if(Array.isArray(v)){v.forEach(walk);return}if(typeof v.id==="string"){if(!validPlayId(v.id))throw new Error("invalid_commercial_id");if(ids.includes(v.id))throw new Error("duplicate_commercial_id");ids.push(v.id)}if(typeof v.productId==="string"&&v.productId){if(!validPlayId(v.productId))throw new Error("invalid_product_id");products.push(v.productId)}if(typeof v.basePlanId==="string"&&v.basePlanId&&!validPlayId(v.basePlanId))throw new Error("invalid_base_plan_id");if(typeof v.offerId==="string"&&v.offerId&&!validPlayId(v.offerId))throw new Error("invalid_offer_id");if(Array.isArray(v.basePlans)&&v.basePlans.some(id=>!validPlayId(id)))throw new Error("invalid_base_plan_id");Object.values(v).forEach(walk)};walk(catalog);const old=await firestoreGet("planCatalog/config"),version=Math.max(Number(old?.version||1),Number(b.version||0))+1;await firestoreSet("planCatalog/config",{version,catalog:{...catalog,version},commercialIdCount:ids.length,productIdCount:products.length,updatedBy:u.uid,updatedAt:new Date()},false);await firestoreCreate("adminConfigAudit/"+crypto.randomUUID(),{type:"plan_catalog_update",version,actorUid:u.uid,commercialIdCount:ids.length,productIdCount:products.length,createdAt:new Date()});return json({ok:true,version,catalog}); }
      if (url.pathname === "/admin/plan/status" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const b=await readBody(request),pid=String(b.planId||"");if(!/^[a-z0-9-]+$/.test(pid))return json({error:"invalid_plan_id"},400);const ref="planCatalog/config",x=await firestoreGet(ref),catalog=x?.catalog||{sections:{}},sections=catalog.sections||{};let found=false;for(const arr of Object.values(sections))if(Array.isArray(arr))for(const p of arr)if(String(p?.id||"")===pid){p.active=b.active===true;found=true}if(!found)return json({error:"plan_not_found"},404);const version=Number(x?.version||1)+1;await firestoreSet(ref,{version,catalog:{...catalog,version},updatedBy:u.uid,updatedAt:new Date()},false);return json({ok:true,planId:pid,active:b.active===true,version});}
      if (url.pathname === "/admin/plan/popular" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const b=await readBody(request),pid=String(b.planId||""),x=await firestoreGet("planCatalog/config"),catalog=x?.catalog||{sections:{}},sections=catalog.sections||{};let found=false;for(const arr of Object.values(sections))if(Array.isArray(arr))for(const p of arr)if(p&&typeof p==="object"){if(String(p.id||"")===pid){p.popular=true;found=true}else p.popular=false}if(!found)return json({error:"plan_not_found"},404);const version=Number(x?.version||1)+1;await firestoreSet("planCatalog/config",{version,catalog:{...catalog,version},updatedBy:u.uid,updatedAt:new Date()},false);return json({ok:true,planId:pid,version});}
      if (url.pathname === "/device-command" && request.method === "POST") {
        const u=await verifyFirebaseUser(request); await requireConsent(u.uid); const b=await readBody(request);
        let control=await capability(u.uid,"device_commands");
        if(b.modelRegistryId){const selected=await resolvePublishedModel(b.modelRegistryId,"device-commands");control={...control,providerId:selected.providerId,modelId:selected.modelId,creditCost:selected.creditCost};}
        const r=await chargeCredits(u.uid,control.creditCost||0,"device_commands",u);
        try {
          const z=await runProvider(env,{primaryProviderId:control.providerId||"gemini",primaryModelId:control.modelId||DEFAULT_MODELS.gemini,fallbackProviderIds:[]},{message:String(b.message||"").slice(0,4000),system:'Return ONLY JSON: {"actions":[{"type":"...","value":null}],"message":"short confirmation"}. Allowed actions: torch_on, torch_off, set_brightness, volume_up, volume_down, play_media, pause_media, next_media, previous_media, open_settings, open_wifi_settings, open_bluetooth_settings, open_camera, open_calendar, open_maps. Never invent an action type. The Android app performs the final action and enforces permissions.'});
          let parsed={actions:[],message:"No supported device action."}; try{parsed=JSON.parse(z.text||"")}catch{}
          const allowed=new Set(["torch_on","torch_off","set_brightness","volume_up","volume_down","play_media","pause_media","next_media","previous_media","open_settings","open_wifi_settings","open_bluetooth_settings","open_camera","open_calendar","open_maps"]);
          const actions=Array.isArray(parsed.actions)?parsed.actions.filter(x=>allowed.has(x?.type)).map(x=>({type:x.type,value:x.value??null})): [];
          await commitCredits(u.uid,r.reservationId,z.providerId||"gemini");
          return json({providerId:z.providerId,modelId:z.modelId,message:parsed.message||"",actions,creditsCharged:r.amount});
        }catch(e){await reverseCredits(u.uid,r.reservationId);throw e}
      }

      if (url.pathname === "/companion/catalog" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await companionCatalog(u,env)); }
      if (url.pathname === "/companion/profile" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await companionProfile(u.uid)); }
      if (url.pathname === "/companion/profile" && request.method === "PUT") { const u=await verifyFirebaseUser(request); return json(await saveCompanionProfile(u.uid,await readBody(request))); }
      if (url.pathname === "/companion/history" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await companionHistory(u.uid)); }
      if (url.pathname === "/companion/chat" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await companionChat(env,u,b,chargeCredits,commitCredits,reverseCredits)); }
      if (url.pathname === "/companion/checkins" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await companionCheckins(u.uid)); }
      if (url.pathname === "/companion/checkin" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await companionCheckin(env,u,b,chargeCredits,commitCredits,reverseCredits)); }
      if (url.pathname === "/companion/settings" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await companionSettings(u.uid)); }
      if (url.pathname === "/companion/settings" && request.method === "PUT") { const u=await verifyFirebaseUser(request); return json(await saveCompanionSettings(u.uid,await readBody(request))); }

      if (url.pathname === "/learning/catalog" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await learningCatalog(u,env)); }
      if (url.pathname === "/learning/progress" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await learningProgress(u.uid)); }
      if (url.pathname === "/learning/session" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await learningSession(env,u,b,chargeCredits,commitCredits,reverseCredits)); }

      if (url.pathname === "/truthguard/catalog" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await truthguardCatalog(u,env)); }
      if (url.pathname === "/truthguard/settings" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await truthguardSettings(u.uid)); }
      if (url.pathname === "/truthguard/settings" && request.method === "PUT") { const u=await verifyFirebaseUser(request); return json(await saveTruthguardSettings(u.uid,await readBody(request))); }
      if (url.pathname === "/truthguard/history" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await truthguardHistory(u.uid)); }
      if (url.pathname === "/truthguard/check" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await truthguardCheck(env,u,b,chargeCredits,commitCredits,reverseCredits)); }

      if (url.pathname === "/organization" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await organizationGet(u.uid)); }
      if (url.pathname === "/organization" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await organizationUpdate(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/channels" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await organizationChannel(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/settings" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await organizationSettings(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/knowledge" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await organizationKnowledge(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/members" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await organizationMember(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/member-state" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await organizationMemberState(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/channel-state" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await organizationChannelState(u.uid,await readBody(request))); }
      if (url.pathname === "/organization/assistant" && request.method === "POST") { const u=await verifyFirebaseUser(request); return json(await organizationAssistant(env,u,await readBody(request))); }

      if (url.pathname === "/social/oauth/start" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        await requireConsent(user.uid);
        const body = await readBody(request);
        const platform = String(body.platform || "").trim().toLowerCase();
        const redirectUri = String(body.redirectUri || (new URL(request.url).origin + "/social/oauth/callback")).trim();
        return json(await socialStart(env,user.uid,platform,redirectUri,String(body.workspaceId || "")));
      }

      if (url.pathname === "/social/oauth/callback" && request.method === "GET") {
        const state = String(url.searchParams.get("state") || "");
        const code = String(url.searchParams.get("code") || "");
        if (!state || !code) return json({error:"oauth_callback_parameters_required"},400);
        return json(await socialCallback(env,state,code));
      }

      if (url.pathname === "/social/tiktok/profile" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        await requireConsent(user.uid);
        const accountId = String(url.searchParams.get("accountId") || "").trim();
        const workspaceId = String(url.searchParams.get("workspaceId") || "").trim();
        if (!accountId || !workspaceId) return json({ error: "tiktok_account_required" }, 400);
        return json(await tiktokProfile(env, user.uid, accountId, workspaceId));
      }

      if (url.pathname === "/social/tiktok/videos" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        await requireConsent(user.uid);
        const accountId = String(url.searchParams.get("accountId") || "").trim();
        const workspaceId = String(url.searchParams.get("workspaceId") || "").trim();
        if (!accountId || !workspaceId) return json({ error: "tiktok_account_required" }, 400);
        return json(await tiktokVideos(env, user.uid, accountId, workspaceId));
      }

      if (url.pathname === "/social/publish" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        await requireConsent(user.uid);
        const body = await readBody(request);
        const accountId = String(body.accountId || "").trim();
        const workspaceId = String(body.workspaceId || "").trim();
        const platform = String(body.platform || "").trim().toLowerCase();
        if (platform === "tiktok") {
          if (!accountId || !workspaceId) return json({ error: "tiktok_account_required" }, 400);
          const workspace = await firestoreGet("businessWorkspaces/" + workspaceId);
          if (!workspace || String(workspace.ownerUid || workspace.uid || "") !== user.uid) return json({ error: "business_workspace_not_found" }, 404);
          return json(await publishTikTok(env, user.uid, accountId, workspaceId, body));
        }
      }

      if (url.pathname === "/social/accounts" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        await requireConsent(user.uid);
        const workspaceId = String(url.searchParams.get("workspaceId") || "");
        return json({ accounts: await listSocialAccounts(user.uid, workspaceId) });
      }

      if (url.pathname.startsWith("/social/accounts/") && request.method === "DELETE") {
        const user = await verifyFirebaseUser(request);
        await requireConsent(user.uid);
        const accountId = decodeURIComponent(url.pathname.slice("/social/accounts/".length)).trim();
        if (!accountId) return json({ error: "social_account_required" }, 400);
        const account = await firestoreGet("socialAccounts/" + accountId);
        if (!account || String(account.uid || "") !== user.uid) return json({ error: "social_account_not_found" }, 404);
        await firestoreDelete("socialAccounts/" + accountId);
        return json({ ok: true, disconnected: true, platform: account.platform || null });
      }

      if (url.pathname === "/web/favicon" && request.method === "GET") {
        const domain = String(url.searchParams.get("domain") || "").trim().toLowerCase().replace(/^www\./, "");
        if (!domain || domain.length > 253 || !/^[a-z0-9.-]+$/.test(domain) || domain.includes("..") || domain.startsWith(".") || domain.endsWith(".") || domain === "localhost" || /^\d{1,3}(\.\d{1,3}){3}$/.test(domain)) {
          return new Response(null, { status: 400, headers: { "Cache-Control": "no-store" } });
        }
        const cacheKey = new Request(new URL("/web/favicon?domain=" + encodeURIComponent(domain), url.origin).toString(), { method: "GET" });
        const cache = caches.default;
        const cached = await cache.match(cacheKey);
        if (cached) return cached;
        const upstream = await fetch("https://www.google.com/s2/favicons?domain=" + encodeURIComponent(domain) + "&sz=64", { cf: { cacheTtl: 86400, cacheEverything: true } });
        if (!upstream.ok) return new Response(null, { status: 404, headers: { "Cache-Control": "public, max-age=300" } });
        const contentType = upstream.headers.get("content-type") || "image/png";
        if (!contentType.toLowerCase().startsWith("image/")) return new Response(null, { status: 404, headers: { "Cache-Control": "public, max-age=300" } });
        const response = new Response(await upstream.arrayBuffer(), {
          status: 200,
          headers: { "Content-Type": contentType, "Cache-Control": "public, max-age=86400", "X-Content-Type-Options": "nosniff" }
        });
        await cache.put(cacheKey, response.clone());
        return response;
      }
      if (url.pathname === "/web-search" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        enforceRateLimit(user.uid);
        await requireConsent(user.uid);
        const body = await readBody(request);
        const query = String(body.query || "").trim();
        if (!query) return json({error:"query_required"},400);
        const deepResearch = Boolean(body.deepResearch);
        const capabilityName = deepResearch ? "deep_research" : "web_search";
        const control = await capability(user.uid, capabilityName);
        const charge = Math.max(deepResearch ? Number(control.config?.creditsPerDeepResearch ?? 5) : Number(control.config?.creditsPerSearch ?? 1.7), Number(control.creditCost || 0));
        const reservation = await chargeCredits(user.uid, charge, capabilityName, user);
        try {
          const result = await callTavily(env, query, Number(body.maxResults || control.config?.maxResults || 10), deepResearch);
          let answer = null;
          if (deepResearch && result.sources?.length) {
            const sources = result.sources.map((s,i) => "[" + (i + 1) + "] " + String(s.title || "") + "\nURL: " + String(s.url || "") + "\n" + String(s.snippet || "")).join("\n\n");
            const synthesis = await runProvider(env, {
              primaryProviderId: "openai",
              primaryModelId: DEFAULT_MODELS.openai,
              fallbackProviderIds: ["gemini"],
              fallbackModels: {}
            }, {
              message: "Research question: " + query + "\n\nSearch sources:\n" + sources,
              system: "Synthesize a factual research answer from the supplied search sources. Cite claims using [1], [2], etc. Do not invent facts or citations. Clearly say when the sources do not establish an answer."
            });
            answer = synthesis.text;
          }
          await commitCredits(user.uid, reservation.reservationId, deepResearch ? "tavily+ai" : "tavily");
          return json({...result, answer, creditsCharged:reservation.amount});
        } catch(error) {
          await reverseCredits(user.uid, reservation.reservationId);
          throw error;
        }
      }
      throw Object.assign(new Error("route_not_migrated"), { httpStatus: 501 });
    } catch(error) {
      const message = error?.message || "backend_unavailable";
      const status = error?.httpStatus || (/Authentication required|Invalid or expired authentication token|admin_session_required/.test(message) ? 401 : /required_consent_missing|consent_required|Admin access required/.test(message) ? 403 : /Insufficient credits|insufficient_credits/.test(message) ? 402 : /select_replacement_model_before_/.test(message) ? 409 : /model_|selected_model|provider_model|provider_not_supported_for_feature|default_model_must_be_enabled|default_model_must_be_published/.test(message) ? 400 : /message_required/.test(message) ? 400 : /disabled/.test(message) ? 403 : /rate_limited/.test(message) ? 429 : /idempotency_in_progress/.test(message) ? 409 : 500);
      if (status >= 500) await notifyAdminOfIssue(env, url.pathname, status, message);
      const candidateCode = error?.safeError || (/required_consent_missing|consent_required/.test(message) ? "consent_required" : /Insufficient credits|insufficient_credits/.test(message) ? "insufficient_credits" : /route_not_migrated/.test(message) ? "route_not_migrated" : /Authentication required|Invalid or expired authentication token/.test(message) ? "auth_required" : /Admin access required|admin_session_required/.test(message) ? "admin_access_required" : /rate_limited/.test(message) ? "rate_limited" : status >= 500 ? "service_temporarily_unavailable" : "request_failed");
      // Public responses contain only stable, machine-readable codes. Never send
      // arbitrary exception text, URLs, account details, or provider messages to clients.
      const code = /^[a-z0-9_.-]{1,80}$/i.test(String(candidateCode)) ? String(candidateCode) : (status >= 500 ? "service_temporarily_unavailable" : "request_failed");
      const body = { error: code };
      return new Response(JSON.stringify(body), { status, headers: { ...corsHeaders(request), "Retry-After": String(error?.retryAfter || ""), "content-type":"application/json; charset=utf-8" } });
    }
  }
};