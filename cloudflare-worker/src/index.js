import { configureFirebaseEnv, verifyFirebaseUser, firestoreGet, firestoreSet, firestoreCreate, firestoreCommit, firestoreDelete, firestoreQuery, deleteFirebaseAuthUser } from "./firebase.js";
import { playCatalog, verifyPlayPurchase } from "./google-play.js";
import { COMMERCIAL_CATALOG, canonicalPlayMappings, mergeCommercialCatalog } from "./plan-catalog-native.js";
import { runProvider, runProviderStream, callOpenAI, callTavily, providerSecretStatus, DEFAULT_MODELS } from "./providers.js";
import { getOrganizationCatalog, getOrganizationAccess, setOrganizationPlanStatus, setOrganizationEntitlement, getOrganizationTestSession, setOrganizationTestSession, getAdminOrganizationControl } from "./organization-plans-native.js";
import { listOrganizationScheduledTasks, createOrganizationScheduledTask, updateOrganizationScheduledTask, runOrganizationScheduledTask } from "./scheduler-native.js";
import { getProduction, updateProductionConfig, upsertSource, removeSource, upsertScene, upsertDestination, setProductionState, updateHealth, addProductionEvent, getOrganizationAiLiveConfig, updateOrganizationAiLiveConfig } from "./production-native.js";
import { businessWorkspace, createBusinessRecord, updateBusinessRecord, deleteBusinessRecord, addBusinessMember, requireBusinessAccess, requireBusinessPlan, listBusinessExtended, createBusinessExtended, updateBusinessExtended, businessKnowledgeForWorkspace, runBusinessAutomation, receiveBusinessAutomationWebhook, updateBusinessSettings, getBusinessTestSession, setBusinessTestSession, runBusinessPlanTestSuite, listAdminBusinessSubscriptions, setAdminBusinessSubscriptionStatus } from "./business-native.js";
import { companionCatalog, companionProfile, saveCompanionProfile, companionHistory, companionChat, companionCheckins, companionCheckin, companionSettings, saveCompanionSettings, learningCatalog, learningProgress, learningSession, truthguardCatalog, truthguardSettings, saveTruthguardSettings, truthguardHistory, truthguardCheck, organizationGet, organizationUpdate, organizationSettings, organizationChannel, organizationKnowledge, organizationMember, organizationMemberState, organizationChannelState, organizationAssistant } from "./legacy-native.js";
import { generateImageNative, editImageNative, createVideoNative, getVideoNative, getVideoContentNative } from "./generation-native.js";
import { listAiModels, saveAiModel, resolvePublishedModel, ensureCurrentModelRegistry, validateAiModel } from "./model-registry-native.js";
import { securityStatusNative, configurePasswordNative, beginTotpNative, confirmTotpNative, unlockAdminNative, biometricStatusNative, registerBiometricNative, removeBiometricNative, beginBiometricNative, verifyBiometricNative, adminSessionStatusNative, requireAdminSessionNative } from "./admin-security-native.js";
import { broadcast } from "./notifications-native.js";
import { advancedAiNative, advancedJobs, supportedIntegrations, integrationConfig, configureIntegration, enterpriseConfig, enterpriseUpdate } from "./business-advanced-native.js";
import { passkeyStatus, registrationOptionsNative, verifyRegistrationNative, authenticationOptionsNative, verifyAuthenticationNative, removePasskeyNative } from "./passkeys-native.js";
import { orgWallet, orgTopUps, orgLiveStart, orgChargeAssistant, orgLiveGet, orgLiveHeartbeat, orgLiveStop, orgBillingConfig } from "./organization-billing-native.js";
import { youtubeChannel, youtubeCreateLive, youtubeTransition, youtubeStatus, youtubeChat, youtubeReply } from "./youtube-native.js";
import { runtimeCreate, runtimeList, runtimeUpdate, runtimeAudit } from "./runtime-native.js";
import { createTrainingAssetUploadSession, inspectTrainingAssetObject, deleteTrainingAssetObject, createTrainingAssetDownloadAuthorization } from "./training-assets.js";
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
  // The explicitly configured owner email is the bootstrap administrator.
  // Require a verified Firebase identity for that email; optional custom claims
  // remain supported for delegated administrators.
  return Boolean(owner) && (
    (email === owner && user?.emailVerified === true) ||
    claims.admin === true ||
    claims.superAdmin === true ||
    claims.role === "admin" ||
    claims.role === "super_admin"
  );
}

function diagnosticFeatureForRoute(route) {
  const path = String(route || "/").split("?")[0];
  if (path === "/ai" || path === "/ai/stream") return "chat";
  if (path === "/ai/image") return "image";
  if (path === "/ai/vision") return "vision";
  if (path === "/realtime/session" || path === "/realtime/heartbeat") return "voice_session";
  if (path === "/native/catalog" || path === "/play/catalog" || path === "/plans") return "catalog";
  return "other";
}

function safeDiagnosticCode(errorCode) {
  const value = String(errorCode || "").toLowerCase();
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
  const features = ["chat", "image", "vision", "voice_session", "catalog"];
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
      createdAt: x.createdAt || null
    }))];
  }));
  return Object.fromEntries(records);
}

async function adminReadinessSelfTest(env) {
  const check = async (feature, capabilityName, providerNames) => {
    try {
      const config = await firestoreGet("adminAdvancedOptions/capability_" + capabilityName);
      const disabled = config?.enabled === false || config?.killSwitch === true;
      const provider = String(config?.providerId || "openai").toLowerCase();
      const secretReady = providerNames.some(name => Boolean(env?.[name]));
      const model = String(config?.modelId || "").slice(0, 80);
      return {
        feature,
        status: disabled ? "disabled" : (secretReady ? "ready" : "missing_provider_key"),
        capabilityEnabled: !disabled,
        providerConfigured: secretReady,
        provider: /^[a-z0-9_-]{1,40}$/.test(provider) ? provider : "configured_provider",
        model: /^[a-zA-Z0-9._:-]{1,80}$/.test(model) ? model : ""
      };
    } catch {
      return { feature, status: "check_failed", capabilityEnabled: false, providerConfigured: false };
    }
  };
  const [chat, image, vision, voice, catalog] = await Promise.all([
    check("chat", "chat", ["OPENAI_API_KEY", "GEMINI_API_KEY"]),
    check("image", "image_generation", ["OPENAI_API_KEY"]),
    check("vision", "live_camera", ["OPENAI_API_KEY", "GEMINI_API_KEY"]),
    (async () => {
      try {
        const config = await firestoreGet("adminAdvancedOptions/live_voice_pricing");
        const enabled = config?.enabled !== false && config?.killSwitch !== true;
        const providerConfigured = Boolean(env?.OPENAI_API_KEY);
        return { feature: "voice_session", status: !enabled ? "disabled" : (providerConfigured ? "ready" : "missing_provider_key"), capabilityEnabled: enabled, providerConfigured };
      } catch {
        return { feature: "voice_session", status: env?.OPENAI_API_KEY ? "config_check_failed" : "missing_provider_key", capabilityEnabled: false, providerConfigured: Boolean(env?.OPENAI_API_KEY) };
      }
    })(),
    (async () => {
      try {
        const data = await nativeCatalog();
        const planCount = Array.isArray(data.plans) ? data.plans.length : 0;
        const productCount = Array.isArray(data.products) ? data.products.length : 0;
        const degraded = data.catalogStatus?.degraded === true;
        return { feature: "catalog", status: degraded ? "degraded" : ((planCount + productCount) > 0 ? "ready" : "empty"), planCount, productCount, warnings: Array.isArray(data.catalogStatus?.warnings) ? data.catalogStatus.warnings.slice(0, 10) : [] };
      } catch {
        return { feature: "catalog", status: "check_failed", planCount: 0, productCount: 0, warnings: ["catalog_check_failed"] };
      }
    })()
  ]);
  return { checkedAt: new Date().toISOString(), mode: "readiness_only_no_paid_ai_calls", checks: [chat, image, vision, voice, catalog] };
}

async function notifyAdminOfIssue(env, route, status, errorCode) {
  try {
    const issueId = crypto.randomUUID();
    await firestoreCreate("adminIssues/" + issueId, {
      id: issueId,
      route: String(route || "/").slice(0, 160),
      status: Number(status || 500),
      code: String(errorCode || "backend_unavailable").replace(/https?:\/\/\S+/gi, "[redacted-url]").slice(0, 160),
      state: "open",
      createdAt: new Date()
    });
    const feature = diagnosticFeatureForRoute(route);
    if (["chat", "image", "vision", "voice_session", "catalog"].includes(feature)) {
      await firestoreCreate("adminDiagnostics/" + issueId, {
        id: issueId,
        feature,
        route: String(route || "/").split("?")[0].slice(0, 120),
        status: Number(status || 500),
        code: safeDiagnosticCode(errorCode),
        createdAt: new Date()
      });
    }
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
async function capability(uid, name) {
  const control = await firestoreGet("adminAdvancedOptions/capability_" + name);
  if (control?.enabled === false || control?.killSwitch === true) throw new Error("capability_disabled:capability_" + name);
  const defaults = {
    chat: { providerId: "openai", modelId: DEFAULT_MODELS.openai, fallbackProviderIds: ["gemini"] },
    image_generation: { providerId: "openai", modelId: "gpt-image-2", fallbackProviderIds: [] },
    live_camera: { providerId: "openai", modelId: "gpt-realtime-2.1", fallbackProviderIds: [] },
    live_voice: { providerId: "openai", modelId: "gpt-realtime-2.1", fallbackProviderIds: [] },
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
    creditCost: Math.max(1, Number(value.creditCost == null ? (name === "deep_research" ? 3 : (name === "web_search" ? 2 : 1)) : value.creditCost)),
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
  return {
    permanent,
    subscription,
    trial,
    total: permanent + subscription + trial,
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

async function readCanonicalUserBalance(uid) {
  return firestoreGet("users/" + uid);
}

async function chargeCredits(uid, amount, capabilityName, adminUser = null) {
  let charge = Math.max(0, Number(amount || 0));
  if (isSuperAdmin(adminUser, globalThis.__bqEnv)) return { reservationId: null, amount: 0, adminUnlimited: true };
  await applyConfiguredCreditTrial(uid);
  if (!charge) {
    const configured = await firestoreGet("adminAdvancedOptions/capability_" + String(capabilityName || ""));
    charge = Math.max(0, Number(configured?.creditCost || 0));
  }
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
    const fromTrial = Math.min(balance.trial, charge);
    const afterTrial = charge - fromTrial;
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

async function ai(request, user, body) {
  enforceRateLimit(user.uid);
  await requireConsent(user.uid);
  const idem = await loadIdempotentResponse(request, user, "/ai");
  if (idem?.replay) return idem.replay;
  if (idem?.fresh) await firestoreCreate("idempotency/" + idem.hash, { uid:user.uid, status:"pending", createdAt:new Date(), expiresAt:new Date(Date.now()+600000) });
  const capabilityName = String(body.capability || "chat").trim();
  const userMessage = String(body.message || "").trim();
  if (!userMessage) throw new Error("message_required");
  if (!/^[a-z0-9_-]{1,64}$/.test(capabilityName)) throw new Error("invalid_capability");
  let control = await capability(user.uid, capabilityName);
  if (body.modelRegistryId && capabilityName === "chat") {
    const selected = await resolvePublishedModel(body.modelRegistryId, "text-chat");
    control = { ...control, providerId: selected.providerId, modelId: selected.modelId, creditCost: selected.creditCost };
  }
  const reservation = await chargeCredits(user.uid, control.creditCost || 0, capabilityName, user);
  try {
    const memory = await memoryContext(user.uid);
    const system = [BQ_APP_KNOWLEDGE, body.system ? String(body.system) : "", memory ? "Relevant user memory (use only when helpful and do not invent details):\n" + memory : ""].filter(Boolean).join("\n");
    const result = await runProvider(globalThis.__bqEnv, {
      primaryProviderId: control.providerId,
      primaryModelId: control.modelId,
      fallbackProviderIds: control.config?.fallbackProviderIds || [],
      fallbackModels: control.config?.fallbackModels || {}
    }, { message: userMessage, system });
    if (!result.text?.trim()) throw new Error("The AI service returned an empty response.");
    await commitCredits(user.uid, reservation.reservationId, result.providerId);
    const responseBody = { text: result.text.trim(), providerId: result.providerId, modelId: result.modelId, creditsCharged: reservation.amount, incidentId: crypto.randomUUID() };
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
  if (rawImages.length > 4 || rawFiles.length > 4 || rawImages.length + rawFiles.length < 1) {
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
    const primaryModelId = primaryProviderId === control.providerId
      ? (control.modelId || (primaryProviderId === "gemini" ? DEFAULT_MODELS.gemini : DEFAULT_MODELS.openai))
      : DEFAULT_MODELS.openai;
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
  let control = await capability(user.uid, capabilityName);
  if (body.modelRegistryId && capabilityName === "chat") {
    const selected = await resolvePublishedModel(body.modelRegistryId, "text-chat");
    control = { ...control, providerId: selected.providerId, modelId: selected.modelId, creditCost: selected.creditCost };
  }
  const reservation = await chargeCredits(user.uid, control.creditCost || 0, capabilityName, user);
  try {
    const memory = await memoryContext(user.uid);
    const system = [BQ_APP_KNOWLEDGE, body.system ? String(body.system) : "", memory ? "Relevant user memory (use only when helpful and do not invent details):\n" + memory : ""].filter(Boolean).join("\n");
    const provider = await runProviderStream(globalThis.__bqEnv, {
      primaryProviderId: control.providerId,
      primaryModelId: control.modelId,
      fallbackProviderIds: control.config?.fallbackProviderIds || [],
      fallbackModels: control.config?.fallbackModels || {}
    }, { message: userMessage, system });
    const encoder = new TextEncoder();
    const output = new ReadableStream({
      async start(controller) {
        const emit = (value) => controller.enqueue(encoder.encode("data: " + JSON.stringify(value) + "\n\n"));
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
                  emit({ type: "completed", providerId: provider.providerId, modelId: provider.modelId, creditsCharged: reservation.amount });
                } else if (parsed.type === "error") {
                  throw new Error(parsed.error?.message || "Streaming failed");
                }
              }
            }
            if (!completed) throw new Error("stream_incomplete");
          } else {
            emit({ type: "delta", text: provider.text });
            emit({ type: "completed", providerId: provider.providerId, modelId: provider.modelId, creditsCharged: reservation.amount });
          }
          await commitCredits(user.uid, reservation.reservationId, provider.providerId);
          controller.enqueue(encoder.encode("data: [DONE]\n\n"));
          controller.close();
        } catch (error) {
          await reverseCredits(user.uid, reservation.reservationId);
          await notifyAdminOfIssue(globalThis.__bqEnv, "/ai/stream", 500, error?.message || "stream_failed");
          emit({ type: "error", message: "service_temporarily_unavailable" });
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
  // Delete Auth first: if the service account lacks firebaseauth.users.delete,
  // the scheduled job fails before any user data is removed. Retries are safe.
  await deleteFirebaseAuthUser(uid);
  const cols = [
    "chatHistory", "businessTrainingAssets", "aiFeedback", "contactSalesRequests",
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
        "POST /admin/security/password",
        "POST /admin/security/totp/start",
        "POST /admin/security/totp/confirm",
        "POST /admin/security/unlock",
        "GET /admin/security/biometric",
        "POST /admin/security/biometric/challenge",
        "POST /admin/security/biometric/verify"
      ]);
      if (url.pathname.startsWith("/admin/") && !adminBootstrapRoutes.has(request.method + " " + url.pathname)) {
        const adminIdentity = await verifyFirebaseUser(request);
        if (!isSuperAdmin(adminIdentity, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        await requireAdminSessionNative(adminIdentity.uid);
      }
      const protectedPaths = new Set(["/ai", "/ai/stream", "/ai/vision", "/web-search", "/credits", "/entitlements", "/entitlement"]);
      if (protectedPaths.has(url.pathname) && !request.headers.get("Authorization")) {
        return json({ error: "auth_required" }, 401);
      }

      if (url.pathname === "/ai" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        return await ai(request, user, await readBody(request));
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
          plan: balance.plan,
          subscriptionExpiresAt: balance.subscriptionExpiresAt,
          trialExpiresAt: balance.trialExpiresAt,
          updatedAt: profile.updatedAt || null,
          source: "firebase_users"
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
        const messages = rawMessages.slice(-100).map(item => ({
          id: String(item?.id || crypto.randomUUID()).slice(0, 120),
          mine: item?.mine === true,
          text: String(item?.text || "").slice(0, 20000),
          ...(item?.prompt ? { prompt: String(item.prompt).slice(0, 20000) } : {}),
          ...(Array.isArray(item?.attachments) ? {
            attachments: item.attachments.slice(0, 8).map(value => String(value || "").slice(0, 300)).filter(Boolean)
          } : {}),
          ...(typeof item?.imageUrl === "string" && /^https:\/\//i.test(item.imageUrl)
            ? { imageUrl: item.imageUrl.slice(0, 2000) }
            : {})
        })).filter(item => item.text.length > 0);
        if (!messages.length) return json({ error: "history_messages_required" }, 400);
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
        const allowed = new Set(["web_search","memory","notifications","history","auto_run","streaming","voice_responses","save_files","personalization","background_jobs"]);
        const incoming = body.settings && typeof body.settings === "object" ? body.settings : {};
        const cleaned = {};
        for (const key of allowed) if (typeof incoming[key] === "boolean") cleaned[key] = incoming[key];
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
        await firestoreCreate("aiFeedback/" + crypto.randomUUID(), {
          uid:user.uid, messageId:body.messageId == null ? null : String(body.messageId).slice(0,120),
          category:String(body.category || "general").slice(0,40),
          sentiment:body.sentiment == null ? null : String(body.sentiment).slice(0,20),
          reason:body.reason == null ? null : String(body.reason).slice(0,80),
          comment:body.comment == null ? null : String(body.comment).slice(0,1000),
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
        await notifyAdminOfIssue(env, requestedRoute, 500, String(body.code || "client_runtime_issue").replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 80) || "client_runtime_issue");
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

      if (url.pathname === "/admin/diagnostics" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        return json({ features: await adminDiagnosticSnapshot(), checkedAt: new Date().toISOString() });
      }

      if (url.pathname === "/admin/self-test" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        if (!isSuperAdmin(user, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        return json(await adminReadinessSelfTest(env));
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
        const trainingConsent = body.trainingConsent === true;
        const policyVersion = String(body.policyVersion || "2026-09").slice(0,40);
        await firestoreSet("userConsents/" + user.uid, {
          uid:user.uid, requiredAccepted:true, policyVersion,
          requiredConsentAt:new Date(), trainingConsent,
          trainingConsentAt:new Date(), updatedAt:new Date()
        }, true);
        return json({ok:true,policyVersion,trainingConsent});
      }

      if (url.pathname === "/consent/training" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        const body = await readBody(request);
        const trainingConsent = body.trainingConsent === true;
        await firestoreSet("userConsents/" + user.uid, {uid:user.uid,trainingConsent,trainingConsentAt:new Date(),updatedAt:new Date()}, true);
        return json({ok:true,trainingConsent});
      }

      // Native legacy phase routes: Companion, Learning, TruthGuard, Organization.
      if (url.pathname === "/companion/memory" && request.method === "GET") { const u=await verifyFirebaseUser(request); const x=await firestoreGet("userMemory/"+u.uid); return json({enabled:x?.enabled!==false,items:Array.isArray(x?.items)?x.items.slice(-80):[]}); }
      if (url.pathname === "/companion/memory" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); if(b.enabled!=null){await firestoreSet("userMemory/"+u.uid,{uid:u.uid,enabled:b.enabled===true,updatedAt:new Date()},true);return json({enabled:b.enabled===true});} const textValue=String(b.text||"").trim().slice(0,4000);if(!textValue)throw new Error("memory_text_required");const x=await firestoreGet("userMemory/"+u.uid),items=Array.isArray(x?.items)?x.items:[],item={id:crypto.randomUUID(),text:textValue,source:String(b.source||"companion").slice(0,40),createdAt:new Date().toISOString()};items.push(item);await firestoreSet("userMemory/"+u.uid,{uid:u.uid,enabled:x?.enabled!==false,items:items.slice(-200),updatedAt:new Date()},true);return json(item); }
       if (url.pathname === "/realtime/heartbeat" && request.method === "POST") {
         const user = await verifyFirebaseUser(request);
         await requireConsent(user.uid);
         const heartbeatBody = await readBody(request);
         const heartbeatMode = String(heartbeatBody.mode || "voice").toLowerCase();
         const config = await firestoreGet("adminAdvancedOptions/live_voice_pricing");
         const pricing = config?.config || {};
         if (heartbeatMode === "voice" && config?.enabled === false) throw Object.assign(new Error("live_voice_disabled"), { httpStatus: 403 });
         const capabilityName = heartbeatMode === "camera" || heartbeatMode === "screen" ? "live_camera" : "live_voice";
         let control = await capability(user.uid, capabilityName);
         if (heartbeatBody.modelRegistryId) {
           const expectedFeature = heartbeatMode === "camera" || heartbeatMode === "screen" ? "live-camera" : "live-voice";
           const selected = await resolvePublishedModel(heartbeatBody.modelRegistryId, expectedFeature);
           control = { ...control, providerId: selected.providerId, modelId: selected.modelId, creditsPerMinute: selected.creditsPerMinute };
         }
         const profile = await readCanonicalUserBalance(user.uid);
         const balance = canonicalUserBalance(profile);
         if (!isSuperAdmin(user, env) && (!balance || balance.total <= 0)) throw Object.assign(new Error("Insufficient credits"), { httpStatus: 402 });
         const rate = Math.max(0, Number(control.creditsPerMinute == null ? (pricing.creditsPerMinute || 0) : control.creditsPerMinute));
         const charge = await chargeCredits(user.uid, rate, "live_session_minute", user);
         if (charge.reservationId) await commitCredits(user.uid, charge.reservationId, "openai_realtime_minute");
         const updatedProfile = await readCanonicalUserBalance(user.uid);
         const updatedBalance = canonicalUserBalance(updatedProfile);
         return json({
           ok: true,
           available: isSuperAdmin(user, env) ? null : Number(updatedBalance?.total || 0),
           charged: Number(charge.amount || 0),
           billingIntervalSeconds: 60
         });
       }

      if (url.pathname === "/realtime/session" && request.method === "POST") { const u=await verifyFirebaseUser(request); await requireConsent(u.uid); const b=await readBody(request),organization=b.organization===true,mode=String(b.mode||"voice").toLowerCase(),capabilityName=organization?"organization_studio":(mode==="camera"||mode==="screen"?"live_camera":"live_voice"); let control=await capability(u.uid,capabilityName); if(!organization&&b.modelRegistryId){const expectedFeature=(mode==="camera"||mode==="screen")?"live-camera":"live-voice";const selected=await resolvePublishedModel(b.modelRegistryId,expectedFeature);control={...control,providerId:selected.providerId,modelId:selected.modelId,creditCost:selected.creditCost,creditsPerMinute:selected.creditsPerMinute};} const reservation=organization?null:await chargeCredits(u.uid,control.creditCost||0,capabilityName,u); try { const cfg=await firestoreGet("adminAdvancedOptions/live_voice_pricing"),conf=cfg?.config||{},model=String(b.modelRegistryId?control.modelId:((mode==="camera"||mode==="screen")?control.modelId:(cfg?.modelId||control.modelId))||"gpt-realtime-2.1"),voice0=String(b.voice||conf.defaultVoice||"marin").toLowerCase(),voice=["alloy","ash","ballad","coral","echo","sage","shimmer","verse","marin","cedar"].includes(voice0)?voice0:String(conf.defaultVoice||"marin"),key=String(env.OPENAI_API_KEY||"");if(mode==="voice"&&cfg?.enabled===false)throw new Error("live_voice_disabled");if(!key)throw new Error("openai_provider_not_configured");const h=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(u.uid));let safety="";for(const v of new Uint8Array(h))safety+=v.toString(16).padStart(2,"0");const rr=await fetch("https://api.openai.com/v1/realtime/client_secrets",{method:"POST",headers:{"Authorization":"Bearer "+key,"Content-Type":"application/json","OpenAI-Safety-Identifier":safety},body:JSON.stringify({session:{type:"realtime",model,voice,output_modalities:["audio"],audio:{input:{format:{type:"audio/pcm",rate:24000},turn_detection:{type:"server_vad",interrupt_response:true,create_response:true}},output:{format:{type:"audio/pcm",rate:24000}}}}})}),raw=await rr.text();let out={};try{out=JSON.parse(raw)}catch{}if(!rr.ok)throw new Error(out?.error?.message||"realtime_session_failed");const secret=out?.value||out?.client_secret?.value;if(!secret)throw new Error("realtime_client_secret_missing");if(organization){const w=await orgWallet(u.uid);if(w.balanceCents<=0)throw new Error("organization_insufficient_balance");await orgChargeAssistant(u.uid,"realtime-"+Date.now()); } else await commitCredits(u.uid,reservation.reservationId,"openai");return json({clientSecret:String(secret),model,voice,pricing:{creditsPerMinute:control.creditsPerMinute==null?(conf.creditsPerMinute==null?null:Number(conf.creditsPerMinute)):Number(control.creditsPerMinute),minimumMinutes:conf.minimumMinutes==null?null:Number(conf.minimumMinutes),maxSessionMinutes:conf.maxSessionMinutes==null?null:Number(conf.maxSessionMinutes)}}); }catch(e){await reverseCredits(u.uid,reservation?.reservationId);throw e} }
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
      if (url.pathname === "/public/app-config" && request.method === "GET") { const x=await firestoreGet("publicAppConfig/app"),d=x||{}; const ids=["CHAT","VIDEO","SEARCH","TRUTH_GUARD","FRONTIER_LAB","CAMERA","VOICE","PERSONAL","LIFE","COMPANION","LEARNING","BUSINESS","ORGANIZATION_STUDIO","DATA_ANALYSIS","ADMIN"]; return json({authBackgroundVideoUrl:d.authBackgroundVideoUrl||null,authBackgroundVideoEnabled:d.authBackgroundVideoEnabled===true,workspaceOrder:Array.isArray(d.workspaceOrder)?d.workspaceOrder.filter(x=>ids.includes(String(x))):ids,workspaceEnabled:d.workspaceEnabled&&typeof d.workspaceEnabled==="object"?d.workspaceEnabled:Object.fromEntries(ids.map(x=>[x,true])),themeMode:["system","light","dark"].includes(String(d.themeMode||""))?String(d.themeMode):"system"}); }
      if (url.pathname === "/plans" && request.method === "GET") { return json({plans:await firestoreQuery("plans",{filters:[{field:{fieldPath:"active"},op:"EQUAL",value:{booleanValue:true}}],limit:100})}); }
      if (url.pathname === "/credits" && request.method === "GET") { const u=await verifyFirebaseUser(request),p=await firestoreGet("users/"+u.uid),l=await firestoreGet("creditBalances/"+u.uid),exp=p?.subExpiresAt?Date.parse(String(p.subExpiresAt)):NaN,sub=Number.isFinite(exp)&&exp>Date.now()?Math.max(0,Number(p?.subCredits||0)):0,perm=Math.max(0,Number(p?.credits||0));return json({available:perm+sub,permanent:perm,subscription:sub,reserved:Math.max(0,Number(l?.reserved||0)),updatedAt:p?.updatedAt||l?.updatedAt||null,source:"firebase_users"}); }
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
      if (url.pathname === "/consent/training" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); await firestoreSet("userConsents/"+u.uid,{uid:u.uid,trainingConsent:b.trainingConsent===true,trainingConsentAt:new Date(),updatedAt:new Date()},true); return json({ok:true,trainingConsent:b.trainingConsent===true}); }
      if (url.pathname === "/notifications/token" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request),token=String(b.token||"").trim(); if(!token||token.length>4096)return json({error:"notification_token_required"},400); const hash=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(token)); let h=""; for(const v of new Uint8Array(hash))h+=v.toString(16).padStart(2,"0"); await firestoreSet("notificationTokens/"+u.uid+"_"+h,{uid:u.uid,token,tokenHash:h,platform:String(b.platform||"android").slice(0,40),enabled:true,updatedAt:new Date()},true); return json({ok:true,registered:true}); }
      if (url.pathname === "/notifications/token" && request.method === "DELETE") { const u=await verifyFirebaseUser(request),b=await readBody(request),token=String(b.token||"").trim(); if(!token)return json({error:"notification_token_required"},400); const hash=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(token)); let h=""; for(const v of new Uint8Array(hash))h+=v.toString(16).padStart(2,"0"); await firestoreDelete("notificationTokens/"+u.uid+"_"+h); return json({ok:true,removed:true}); }
      if (url.pathname === "/video/generate" && request.method === "POST") { const u=await verifyFirebaseUser(request); await requireConsent(u.uid); let control=await capability(u.uid,"video_generation"); const b=await readBody(request); if(b.modelRegistryId){const selected=await resolvePublishedModel(b.modelRegistryId,"video-generation");control={...control,providerId:selected.providerId,modelId:selected.modelId,creditCost:selected.creditCost};} b.creditCost=control.creditCost;b.model=control.modelId;return json(await createVideoNative(env,u,b,(uid,cost,name,user)=>chargeCredits(uid,cost,name,user),(uid,id,p)=>commitCredits(uid,id,p),(uid,id)=>reverseCredits(uid,id))); }
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
          control = { ...control, providerId: selected.providerId, modelId: selected.modelId, creditCost: selected.creditCost };
        }
        // Model and credit cost are resolved from trusted server-side configuration.
        body.model = control.modelId || "gpt-image-2";
        body.creditCost = control.creditCost;
        return json(await generateImageNative(
          env, u, body,
          (uid, cost, name, user) => chargeCredits(uid, cost, name, user),
          (uid, id, provider) => commitCredits(uid, id, provider),
          (uid, id) => reverseCredits(uid, id)
        ));
      }
      if (url.pathname === "/ai/image/edit" && request.method === "POST") {
        const u = await verifyFirebaseUser(request);
        await requireConsent(u.uid);
        let control = await capability(u.uid, "image_generation");
        const body = await readBody(request);
        if (body.modelRegistryId) {
          const selected = await resolvePublishedModel(body.modelRegistryId, "image-generation");
          control = { ...control, providerId: selected.providerId, modelId: selected.modelId, creditCost: selected.creditCost };
        }
        // Model and credit cost are resolved from trusted server-side configuration.
        body.model = control.modelId || "gpt-image-2";
        body.creditCost = control.creditCost;
        return json(await editImageNative(
          env, u, body,
          (uid, cost, name, user) => chargeCredits(uid, cost, name, user),
          (uid, id, provider) => commitCredits(uid, id, provider),
          (uid, id) => reverseCredits(uid, id)
        ));
      }
      if (url.pathname === "/ai/video" && request.method === "POST") { const u=await verifyFirebaseUser(request); await requireConsent(u.uid); let control=await capability(u.uid,"video_generation"); const b=await readBody(request); if(b.modelRegistryId){const selected=await resolvePublishedModel(b.modelRegistryId,"video-generation");control={...control,providerId:selected.providerId,modelId:selected.modelId,creditCost:selected.creditCost};} b.creditCost=control.creditCost; b.model=control.modelId; return json(await createVideoNative(env,u,b,(uid,cost,name,user)=>chargeCredits(uid,cost,name,user),(uid,id,p)=>commitCredits(uid,id,p),(uid,id)=>reverseCredits(uid,id))); }
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
      if (url.pathname === "/admin/live-voice-pricing" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json(await firestoreGet("adminAdvancedOptions/live_voice_pricing")||{}); }
      if (url.pathname === "/admin/live-voice-pricing" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request);await firestoreSet("adminAdvancedOptions/live_voice_pricing",{enabled:b.enabled!==false,config:b.config&&typeof b.config==="object"?b.config:{},updatedBy:u.uid,updatedAt:new Date()},true);return json(await firestoreGet("adminAdvancedOptions/live_voice_pricing")); }
      if (url.pathname === "/admin/web-search-pricing" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json(await firestoreGet("adminAdvancedOptions/web_search_pricing")||{}); }
      if (url.pathname === "/admin/web-search-pricing" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request);await firestoreSet("adminAdvancedOptions/web_search_pricing",{enabled:b.enabled!==false,config:b.config&&typeof b.config==="object"?b.config:{},updatedBy:u.uid,updatedAt:new Date()},true);return json(await firestoreGet("adminAdvancedOptions/web_search_pricing")); }
      if (url.pathname === "/admin/auth-background-video" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request),enabled=b.enabled===true,urlValue=String(b.url||"").trim();if(enabled&&!/^https:\/\//i.test(urlValue))throw new Error("auth_background_video_url_required");await firestoreSet("publicAppConfig/app",{authBackgroundVideoUrl:enabled?urlValue:null,authBackgroundVideoEnabled:enabled,updatedBy:u.uid,updatedAt:new Date()},true);return json(await firestoreGet("publicAppConfig/app")); }
      if (url.pathname === "/admin/app-layout" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json(await firestoreGet("publicAppConfig/app")||{}); }
      if (url.pathname === "/admin/app-layout" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request),ids=["CHAT","VIDEO","SEARCH","TRUTH_GUARD","FRONTIER_LAB","CAMERA","VOICE","PERSONAL","LIFE","COMPANION","LEARNING","BUSINESS","ORGANIZATION_STUDIO","DATA_ANALYSIS","ADMIN"],order=[...new Set((Array.isArray(b.workspaceOrder)?b.workspaceOrder:String(b.workspaceOrder||"").split(",")).map(x=>String(x).toUpperCase()).filter(x=>ids.includes(x)))];for(const id of ids)if(!order.includes(id))order.push(id);const enabled=Object.fromEntries(ids.map(id=>[id,b.workspaceEnabled?.[id]!==false]));await firestoreSet("publicAppConfig/app",{workspaceOrder:order,workspaceEnabled:enabled,themeMode:["system","light","dark"].includes(String(b.themeMode||""))?String(b.themeMode):"system",updatedBy:u.uid,updatedAt:new Date()},true);return json(await firestoreGet("publicAppConfig/app")); }
      if (url.pathname === "/admin/staff/roles" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json({roles:{moderator:["admin_console","moderate"],editor:["admin_console","content"],customer_agent:["admin_console","customer_support"],designer:["admin_console","design"],analyst:["admin_console","analytics"],operations:["admin_console","operations"]}}); }
      if (url.pathname === "/admin/staff" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json({staff:await firestoreQuery("adminStaff",{limit:200})}); }
      if (url.pathname === "/admin/staff" && request.method === "POST") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");const b=await readBody(request),uid=String(b.uid||"").trim(),role=String(b.role||"").trim();if(!uid||!role)throw new Error("admin_staff_uid_and_supported_role_required");await firestoreSet("adminStaff/"+uid,{uid,role,permissions:Array.isArray(b.permissions)?b.permissions.map(String).slice(0,40):[],active:b.active!==false,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,uid,role,active:b.active!==false}); }
      if (url.pathname === "/admin/config" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const x=await firestoreGet("adminAdvancedOptions/config"); return json(x||{}); }
      if (url.pathname === "/admin/controls" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const x=await firestoreQuery("adminAdvancedOptions",{limit:100}); return json({items:x}); }
      if (url.pathname === "/admin/advanced-options" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json({options:await firestoreQuery("adminAdvancedOptions",{limit:200})}); }
      if (url.pathname === "/admin/ai-models" && request.method === "GET") {
        const u = await verifyFirebaseUser(request);
        if (!isSuperAdmin(u, env)) throw Object.assign(new Error("Admin access required"), { httpStatus: 403 });
        await ensureCurrentModelRegistry({ openai: DEFAULT_MODELS.openai, gemini: DEFAULT_MODELS.gemini });
        return json(await listAiModels({ admin: true }));
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
      if (url.pathname === "/admin/security/status" && request.method === "GET") { const u=await verifyFirebaseUser(request);if(!isSuperAdmin(u,env))throw new Error("Admin access required");return json({...await securityStatusNative(),encryptionKeyConfigured:Boolean(env.BQ_SECRET_ENCRYPTION_KEY)}); }
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

      if (url.pathname === "/admin/test-mode" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const x=await firestoreGet("adminAdvancedOptions/admin_testing_mode"); return json({enabled:x?.enabled===true,serverControlled:true}); }
      if (url.pathname === "/admin/test-mode" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const b=await readBody(request); await firestoreSet("adminAdvancedOptions/admin_testing_mode",{id:"admin_testing_mode",enabled:b.enabled===true,version:Number((await firestoreGet("adminAdvancedOptions/admin_testing_mode"))?.version||1)+1,updatedBy:u.uid,updatedAt:new Date()},true); return json({ok:true,enabled:b.enabled===true}); }
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
        const charge = Math.max(deepResearch ? Number(control.config?.creditsPerDeepResearch ?? 5) : Number(control.config?.creditsPerSearch ?? 1), Number(control.creditCost || 0));
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
      const status = error?.httpStatus || (/Authentication required|Invalid or expired authentication token|admin_session_required/.test(message) ? 401 : /required_consent_missing|Admin access required/.test(message) ? 403 : /Insufficient credits/.test(message) ? 402 : /select_replacement_model_before_/.test(message) ? 409 : /model_|selected_model|provider_model|provider_not_supported_for_feature|default_model_must_be_enabled|default_model_must_be_published/.test(message) ? 400 : /message_required/.test(message) ? 400 : /disabled/.test(message) ? 403 : /rate_limited/.test(message) ? 429 : /idempotency_in_progress/.test(message) ? 409 : 500);
      if (status >= 500) await notifyAdminOfIssue(env, url.pathname, status, message);
      const safeError = status >= 500 ? "service_temporarily_unavailable" : message;
      return new Response(JSON.stringify({ error: safeError }), { status, headers: { ...corsHeaders(request), "Retry-After": String(error?.retryAfter || ""), "content-type":"application/json; charset=utf-8" } });
    }
  }
};