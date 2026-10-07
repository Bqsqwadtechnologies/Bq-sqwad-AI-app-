import { configureFirebaseEnv, verifyFirebaseUser, firestoreGet, firestoreSet, firestoreCreate, firestoreCommit, firestoreDelete, firestoreQuery } from "./firebase.js";
import { playCatalog, verifyPlayPurchase } from "./google-play.js";
import { runProvider, runProviderStream, callOpenAI, callTavily, providerSecretStatus, DEFAULT_MODELS } from "./providers.js";
import { getOrganizationCatalog, getOrganizationAccess, setOrganizationPlanStatus, setOrganizationEntitlement, getOrganizationTestSession, setOrganizationTestSession, getAdminOrganizationControl } from "./organization-plans-native.js";
import { listOrganizationScheduledTasks, createOrganizationScheduledTask, updateOrganizationScheduledTask, runOrganizationScheduledTask } from "./scheduler-native.js";
import { getProduction, updateProductionConfig, upsertSource, removeSource, upsertScene, upsertDestination, setProductionState, updateHealth, addProductionEvent, getOrganizationAiLiveConfig, updateOrganizationAiLiveConfig } from "./production-native.js";
import { businessWorkspace, createBusinessRecord, updateBusinessRecord, deleteBusinessRecord, addBusinessMember, requireBusinessAccess, requireBusinessPlan, listBusinessExtended, createBusinessExtended, updateBusinessExtended, businessKnowledgeForWorkspace, runBusinessAutomation, receiveBusinessAutomationWebhook, updateBusinessSettings, getBusinessTestSession, setBusinessTestSession, runBusinessPlanTestSuite, listAdminBusinessSubscriptions, setAdminBusinessSubscriptionStatus } from "./business-native.js";
import { companionCatalog, companionProfile, saveCompanionProfile, companionHistory, companionChat, companionCheckins, companionCheckin, companionSettings, saveCompanionSettings, learningCatalog, learningProgress, learningSession, truthguardCatalog, truthguardSettings, saveTruthguardSettings, truthguardHistory, truthguardCheck, organizationGet, organizationUpdate, organizationSettings, organizationChannel, organizationKnowledge, organizationMember, organizationMemberState, organizationChannelState, organizationAssistant } from "./legacy-native.js";
import { generateImageNative, createVideoNative, getVideoNative, getVideoContentNative } from "./generation-native.js";
import { youtubeChannel, youtubeCreateLive, youtubeTransition, youtubeStatus, youtubeChat, youtubeReply } from "./youtube-native.js";
import { runtimeCreate, runtimeList, runtimeUpdate, runtimeAudit } from "./runtime-native.js";
import { socialStart, socialCallback, listSocialAccounts, tiktokProfile, tiktokVideos, publishTikTok } from "./social.js";

const rateBuckets = new Map();

async function requestKeyHash(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(value || "")));
  return Array.from(new Uint8Array(digest)).map(x => x.toString(16).padStart(2, "0")).join("");
}

function enforceRateLimit(uid) {
  const limit = Math.max(1, Number(globalThis.__bqEnv?.RATE_LIMIT_PER_MINUTE || 60));
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
  return Boolean(owner) && email === owner && (
    claims.admin === true ||
    claims.superAdmin === true ||
    claims.role === "admin" ||
    claims.role === "super_admin"
  );
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
    creditCost: Number(value.creditCost || 0),
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
  const permanent = Math.max(0, Number(profile.credits || 0));
  const subscription = subscriptionLive ? Math.max(0, Number(profile.subCredits || 0)) : 0;
  return {
    permanent,
    subscription,
    total: permanent + subscription,
    subscriptionExpiresAt: subscriptionLive ? expiresAt : null,
    plan: String(profile.subscriptionPlan || "free")
  };
}

async function readCanonicalUserBalance(uid) {
  return firestoreGet("users/" + uid);
}

async function chargeCredits(uid, amount, capabilityName, adminUser = null) {
  const charge = Math.max(0, Number(amount || 0));
  if (!charge) return { reservationId: null, amount: 0 };
  if (isSuperAdmin(adminUser, globalThis.__bqEnv)) return { reservationId: null, amount: 0, adminUnlimited: true };

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
    const fromSubscription = Math.min(balance.subscription, charge);
    const fromPermanent = charge - fromSubscription;
    try {
      await firestoreCommit([
        {
          path: "users/" + uid,
          data: {
            credits: balance.permanent - fromPermanent,
            subCredits: Math.max(0, balance.subscription - fromSubscription),
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

async function nativeCatalog() {
  const [plans, planCatalog, playCatalogConfig, features, bindings, addOns, businessPricing] = await Promise.all([
    firestoreQuery("plans", { filters: [{ field: { fieldPath: "active" }, op: "EQUAL", value: { booleanValue: true } }], limit: 100 }),
    firestoreGet("planCatalog/config"),
    firestoreGet("playCatalog/config"),
    firestoreQuery("featureRegistry", { limit: 100 }),
    firestoreQuery("planFeatureBindings", { limit: 100 }),
    firestoreQuery("addOns", { filters: [{ field: { fieldPath: "enabled" }, op: "EQUAL", value: { booleanValue: true } }], limit: 100 }),
    firestoreGet("config/businessPricing")
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
  const configuredSections = planCatalog?.catalog?.sections;
  const configuredPlans = configuredSections
    ? Object.values(configuredSections).flat().map(item => safePlan(item.id, item))
    : [];
  const firestorePlans = plans.map(d => safePlan(String(d.__name || "").split("/").pop(), d));
  const basePlans = configuredPlans.length ? configuredPlans : firestorePlans;
  const configuredProducts = Array.isArray(playCatalogConfig?.products) ? playCatalogConfig.products : [];
  const products = configuredProducts.map(p => ({
    planId: String(p.planId || ""),
    productId: String(p.productId || ""),
    basePlanId: p.basePlanId ? String(p.basePlanId) : null,
    productType: String(p.productType || ""),
    enabled: p.enabled === true,
    consumable: p.consumable === true,
    billingPeriod: p.billingPeriod ? String(p.billingPeriod) : null,
    priceUsd: p.priceUsd == null ? null : Number(p.priceUsd)
  })).filter(p => p.productId && /^[a-z0-9-]+$/.test(p.productId) && (p.productType === "subs" || p.productType === "inapp"));
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
    version: Math.max(1, Number(planCatalog?.version || playCatalogConfig?.version || 1)),
    plans: basePlans.filter(x => x.visible && x.active),
    products,
    publicContactSales: [],
    features: featureRows,
    bindings: bindingRows,
    addOns: addOnRows,
    businessPricingConfigured: businessPricing !== null,
    businessPricing: businessPricing || null
  };
}

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
  const control = await capability(user.uid, capabilityName);
  const reservation = await chargeCredits(user.uid, control.creditCost || 0, capabilityName, user);
  try {
    const memory = await memoryContext(user.uid);
    const system = [body.system ? String(body.system) : "", memory ? "Relevant user memory (use only when helpful and do not invent details):\n" + memory : ""].filter(Boolean).join("\n");
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
  const message = String(body.message || "Describe what is visible in this camera frame.").slice(0, 4000);
  const imageBase64 = String(body.imageBase64 || "");
  if (!imageBase64) throw new Error("image_required");
  if (imageBase64.length > 8000000) throw new Error("image_too_large");
  const control = await capability(user.uid, "live_camera");
  const reservation = await chargeCredits(user.uid, control.creditCost || 0, "live_camera", user);
  try {
    const response = await callOpenAI(globalThis.__bqEnv, {
      model: control.modelId || DEFAULT_MODELS.openai,
      message,
      imageBase64,
      mimeType: String(body.mimeType || "image/jpeg").slice(0, 80)
    });
    if (!response.ok) throw new Error("provider_http_" + response.status + ":" + (await response.text()).slice(0, 700));
    const result = await response.json();
    const text = outputText(result).trim();
    if (!text) throw new Error("The AI service returned an empty response.");
    await commitCredits(user.uid, reservation.reservationId, "openai");
    return json({ text, providerId: "openai", modelId: control.modelId || DEFAULT_MODELS.openai, creditsCharged: reservation.amount });
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
  const control = await capability(user.uid, capabilityName);
  const reservation = await chargeCredits(user.uid, control.creditCost || 0, capabilityName, user);
  try {
    const memory = await memoryContext(user.uid);
    const system = [body.system ? String(body.system) : "", memory ? "Relevant user memory (use only when helpful and do not invent details):\n" + memory : ""].filter(Boolean).join("\n");
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
          emit({ type: "error", message: error.message || "stream_failed" });
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


export default {
  async fetch(request, env) {
    configureFirebaseEnv(env);
    globalThis.__bqEnv = env;
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders(request) });
    if (url.pathname === "/" || url.pathname === "/health") return json({service:"BQ Sqwad AI", status:"ok", backend:"cloudflare-worker"});
    try {
      // All feature routes not implemented natively in this Worker are forwarded to the canonical Firebase backend below.
      // Keep this catch-through behavior so Android always uses this stable API origin while legacy backend handlers remain available.
      const firebaseBackedPath =
        url.pathname === "/ai" ||
        url.pathname === "/ai/stream" ||
        url.pathname === "/ai/vision" ||
        url.pathname === "/web-search" ||
        url.pathname === "/consent" ||
        url.pathname === "/consent/training" ||
        url.pathname === "/play/catalog" ||
        url.pathname === "/play/verify" ||
        url.pathname === "/social/oauth/start" ||
        url.pathname === "/social/oauth/callback" ||
        url.pathname === "/social/accounts" ||
        url.pathname.startsWith("/social/accounts/") ||
        url.pathname === "/native/catalog" ||
        url.pathname === "/entitlements" ||
        url.pathname === "/entitlement" ||
        url.pathname === "/admin/access" ||
        url.pathname === "/admin/test-mode";
      if (firebaseBackedPath && !request.headers.get("Authorization") && ["/ai", "/ai/stream", "/ai/vision", "/web-search", "/credits", "/entitlements", "/entitlement"].includes(url.pathname)) {
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
          plan: balance.plan,
          subscriptionExpiresAt: balance.subscriptionExpiresAt,
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
          ...(item?.prompt ? { prompt: String(item.prompt).slice(0, 20000) } : {})
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
      if (url.pathname === "/video/generate" && request.method === "POST") { const u=await verifyFirebaseUser(request); await requireConsent(u.uid); const control=await capability(u.uid,"video_generation"); const b=await readBody(request); b.creditCost=control.creditCost;b.model=b.model||control.modelId;return json(await createVideoNative(env,u,b,(uid,cost,name,user)=>chargeCredits(uid,cost,name,user),(uid,id,p)=>commitCredits(uid,id,p),(uid,id)=>reverseCredits(uid,id))); }
      if (url.pathname.startsWith("/video/jobs/") && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await getVideoNative(env,u,decodeURIComponent(url.pathname.slice("/video/jobs/".length)))); }
      if (url.pathname.startsWith("/video/jobs/") && url.pathname.endsWith("/content") && request.method === "GET") { const u=await verifyFirebaseUser(request); return await getVideoContentNative(env,u,decodeURIComponent(url.pathname.slice("/video/jobs/".length,-8))); }
      if (url.pathname === "/ai/image" && request.method === "POST") { const u=await verifyFirebaseUser(request); await requireConsent(u.uid); const control=await capability(u.uid,"image_generation"); return json(await generateImageNative(env,u,await readBody(request),(uid,cost,name,user)=>chargeCredits(uid,cost,name,user),(uid,id,p)=>commitCredits(uid,id,p),(uid,id)=>reverseCredits(uid,id))); }
      if (url.pathname === "/ai/video" && request.method === "POST") { const u=await verifyFirebaseUser(request); await requireConsent(u.uid); const control=await capability(u.uid,"video_generation"); const b=await readBody(request); b.creditCost=control.creditCost; b.model=b.model||control.modelId; return json(await createVideoNative(env,u,b,(uid,cost,name,user)=>chargeCredits(uid,cost,name,user),(uid,id,p)=>commitCredits(uid,id,p),(uid,id)=>reverseCredits(uid,id))); }
      if (url.pathname.startsWith("/ai/video/") && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await getVideoNative(env,u,decodeURIComponent(url.pathname.slice("/ai/video/".length)))); }
      if (url.pathname === "/social/oauth/callback" && request.method === "GET") { const state=String(url.searchParams.get("state")||""),code=String(url.searchParams.get("code")||""); if(!state||!code)return new Response("OAuth callback parameters required",{status:400}); const result=await socialCallback(env,state,code); return new Response("<!doctype html><html><body><h1>BQ Sqwad AI</h1><p>Account connected successfully. You can return to the app.</p></body></html>",{status:200,headers:{"content-type":"text/html; charset=utf-8","cache-control":"no-store"}}); }
      if (url.pathname === "/social/platforms" && request.method === "GET") { return json({platforms:["x","youtube","tiktok"],config:{enabled:true}}); }
      if (url.pathname === "/social/oauth/start" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await socialStart(env,u.uid,String(b.platform||""),String(b.redirectUri||"https://bqsqwadai.com/social/oauth/callback"),String(b.workspaceId||""))); }
      if (url.pathname === "/social/accounts" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json({accounts:await listSocialAccounts(u.uid,String(url.searchParams.get("workspaceId")||""))}); }
      if (url.pathname === "/social/tiktok/profile" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await tiktokProfile(env,u.uid,String(url.searchParams.get("accountId")||""),String(url.searchParams.get("workspaceId")||""))); }
      if (url.pathname === "/social/tiktok/videos" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await tiktokVideos(env,u.uid,String(url.searchParams.get("accountId")||""),String(url.searchParams.get("workspaceId")||""))); }
      if (url.pathname === "/social/publish" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request); return json(await publishTikTok(env,u.uid,String(b.accountId||""),String(b.workspaceId||""),b)); }
      if (url.pathname === "/social/youtube/channel" && request.method === "GET") { const u=await verifyFirebaseUser(request); return json(await youtubeChannel(env,u.uid,String(url.searchParams.get("accountId")||""))); }
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
      if (url.pathname === "/business/assistant" && request.method === "POST") { const u=await verifyFirebaseUser(request),b=await readBody(request),w=await businessWorkspace(u.uid,{workspaceId:b.workspaceId}),k=await businessKnowledgeForWorkspace(u.uid,w.workspaceId,50); const z=await runProvider(env,{primaryProviderId:"openai",primaryModelId:DEFAULT_MODELS.openai,fallbackProviderIds:["gemini"]},{message:String(b.message||""),system:"You are the BQ Sqwad AI Business Assistant. Keep business workspace information isolated from personal workspace context. Use only supplied business knowledge and do not invent business facts.\nBusiness knowledge:\n"+k.map(x=>x.content||x.text||"").filter(Boolean).join("\n")}); return json({answer:z.text||"",providerId:z.providerId||null,modelId:z.modelId||null}); }

      if (url.pathname === "/admin/config" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const x=await firestoreGet("adminAdvancedOptions/config"); return json(x||{}); }
      if (url.pathname === "/admin/controls" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const x=await firestoreQuery("adminAdvancedOptions",{limit:100}); return json({items:x}); }
      if (url.pathname === "/admin/advanced-options" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json({options:await firestoreQuery("adminAdvancedOptions",{limit:200})}); }
      if (url.pathname === "/admin/advanced-option" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request),id=String(b.id||"").trim();if(!id)return json({error:"option_id_required"},400);await firestoreSet("adminAdvancedOptions/"+id,{...b,id,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,option:{...b,id}}); }
      if (url.pathname === "/admin/control" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request),id=String(b.controlId||b.id||"").trim();if(!id)return json({error:"control_id_required"},400);await firestoreSet("adminAdvancedOptions/"+id,{...b,id,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,option:{...b,id}}); }
      if (url.pathname === "/admin/plan" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request),id=String(b.id||"").trim();if(!/^[a-z0-9-]{1,80}$/.test(id))return json({error:"invalid_plan_id"},400);const plan={name:String(b.name||"").trim().slice(0,120),active:b.active!==false,amountMinor:Math.max(0,Number(b.amountMinor||0)),currency:"USD",billingPeriod:String(b.billingPeriod||"monthly").slice(0,30),features:Array.isArray(b.features)?b.features.map(String).slice(0,50):[],limits:b.limits&&typeof b.limits==="object"?b.limits:{},updatedBy:u.uid,updatedAt:new Date()};await firestoreSet("plans/"+id,plan,true);if(String(b.module||"").toLowerCase()==="business"||id.startsWith("biz")||id==="businessenterprise")await firestoreSet("businessPlanStatus/"+id,{planId:id,active:plan.active,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,plan:{id,...plan}}); }
      if (url.pathname === "/admin/business/test-session" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json(await getBusinessTestSession(u.uid)); }
      if (url.pathname === "/admin/business/test-session" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request);return json(await setBusinessTestSession(u.uid,String(b.planId||""),b.enabled!==false,Number(b.durationMinutes||120))); }
      if (url.pathname === "/admin/business/test-suite" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json(await runBusinessPlanTestSuite(u.uid,String((await readBody(request)).planId||""))); }
      if (url.pathname === "/admin/business/subscriptions" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); return json({items:await listAdminBusinessSubscriptions()}); }
      if (url.pathname === "/admin/business/subscription-status" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request);return json(await setAdminBusinessSubscriptionStatus(u.uid,String(b.uid||""),String(b.planId||""),b.active===true,b.confirmDeactivation===true)); }
      if (url.pathname === "/admin/business/plan-status" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env))throw new Error("Admin access required"); const b=await readBody(request),id=String(b.planId||"");if(!id)return json({error:"business_plan_id_required"},400);if(b.active===false&&b.confirmDeactivation!==true)return json({error:"business_plan_deactivation_confirmation_required"},400);await firestoreSet("businessPlanStatus/"+id,{planId:id,active:b.active!==false,updatedBy:u.uid,updatedAt:new Date()},true);return json({ok:true,planId:id,active:b.active!==false}); }
      if (url.pathname === "/admin/access" && request.method === "GET") { const u=await verifyFirebaseUser(request); return isSuperAdmin(u,env)?json({allowed:true}):json({allowed:false,error:"Admin access required"},403); }
      if (url.pathname === "/admin/test-mode" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const x=await firestoreGet("adminAdvancedOptions/admin_testing_mode"); return json({enabled:x?.enabled===true,serverControlled:true}); }
      if (url.pathname === "/admin/test-mode" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const b=await readBody(request); await firestoreSet("adminAdvancedOptions/admin_testing_mode",{id:"admin_testing_mode",enabled:b.enabled===true,version:Number((await firestoreGet("adminAdvancedOptions/admin_testing_mode"))?.version||1)+1,updatedBy:u.uid,updatedAt:new Date()},true); return json({ok:true,enabled:b.enabled===true}); }
      if (url.pathname === "/admin/plan-catalog" && request.method === "GET") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const x=await firestoreGet("planCatalog/config"); return json({version:Number(x?.version||1),catalog:x?.catalog||{sections:{}},source:x?"firestore":"empty"}); }
      if (url.pathname === "/admin/plan-catalog" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const b=await readBody(request),catalog=b.catalog;if(!catalog||typeof catalog!=="object"||Array.isArray(catalog))return json({error:"catalog_object_required"},400);const raw=JSON.stringify(catalog);if(raw.includes("_"))return json({error:"commercial_ids_must_not_contain_underscores"},400);const ids=[],products=[];const walk=v=>{if(!v||typeof v!=="object")return;if(Array.isArray(v)){v.forEach(walk);return}if(typeof v.id==="string"){if(!/^[a-z0-9-]+$/.test(v.id))throw new Error("invalid_commercial_id");if(ids.includes(v.id))throw new Error("duplicate_commercial_id");ids.push(v.id)}if(typeof v.productId==="string"&&v.productId){if(!/^[a-z0-9-]+$/.test(v.productId))throw new Error("invalid_product_id");products.push(v.productId)}Object.values(v).forEach(walk)};walk(catalog);const old=await firestoreGet("planCatalog/config"),version=Math.max(Number(old?.version||1),Number(b.version||0))+1;await firestoreSet("planCatalog/config",{version,catalog:{...catalog,version},commercialIdCount:ids.length,productIdCount:products.length,updatedBy:u.uid,updatedAt:new Date()},false);await firestoreCreate("adminConfigAudit/"+crypto.randomUUID(),{type:"plan_catalog_update",version,actorUid:u.uid,commercialIdCount:ids.length,productIdCount:products.length,createdAt:new Date()});return json({ok:true,version,catalog}); }
      if (url.pathname === "/admin/plan/status" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const b=await readBody(request),pid=String(b.planId||"");if(!/^[a-z0-9-]+$/.test(pid))return json({error:"invalid_plan_id"},400);const ref="planCatalog/config",x=await firestoreGet(ref),catalog=x?.catalog||{sections:{}},sections=catalog.sections||{};let found=false;for(const arr of Object.values(sections))if(Array.isArray(arr))for(const p of arr)if(String(p?.id||"")===pid){p.active=b.active===true;found=true}if(!found)return json({error:"plan_not_found"},404);const version=Number(x?.version||1)+1;await firestoreSet(ref,{version,catalog:{...catalog,version},updatedBy:u.uid,updatedAt:new Date()},false);return json({ok:true,planId:pid,active:b.active===true,version});}
      if (url.pathname === "/admin/plan/popular" && request.method === "POST") { const u=await verifyFirebaseUser(request); if(!isSuperAdmin(u,env)) throw new Error("Admin access required"); const b=await readBody(request),pid=String(b.planId||""),x=await firestoreGet("planCatalog/config"),catalog=x?.catalog||{sections:{}},sections=catalog.sections||{};let found=false;for(const arr of Object.values(sections))if(Array.isArray(arr))for(const p of arr)if(p&&typeof p==="object"){if(String(p.id||"")===pid){p.popular=true;found=true}else p.popular=false}if(!found)return json({error:"plan_not_found"},404);const version=Number(x?.version||1)+1;await firestoreSet("planCatalog/config",{version,catalog:{...catalog,version},updatedBy:u.uid,updatedAt:new Date()},false);return json({ok:true,planId:pid,version});}
      if (url.pathname === "/device-command" && request.method === "POST") {
        const u=await verifyFirebaseUser(request); await requireConsent(u.uid); const b=await readBody(request);
        const control=await capability(u.uid,"device_commands");
        const r=await chargeCredits(u.uid,control.creditCost||0,"device_commands",u);
        try {
          const z=await runProvider(env,{primaryProviderId:"gemini",primaryModelId:control.modelId||DEFAULT_MODELS.gemini,fallbackProviderIds:[]},{message:String(b.message||"").slice(0,4000),system:'Return ONLY JSON: {"actions":[{"type":"...","value":null}],"message":"short confirmation"}. Allowed actions: torch_on, torch_off, set_brightness, volume_up, volume_down, play_media, pause_media, next_media, previous_media, open_settings, open_wifi_settings, open_bluetooth_settings, open_camera, open_calendar, open_maps. Never invent an action type. The Android app performs the final action and enforces permissions.'});
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
      return json({ error: "route_not_migrated", path: url.pathname }, 501);
    } catch(error) {
      const message = error?.message || "backend_unavailable";
      const status = /Authentication required|Invalid or expired authentication token/.test(message) ? 401 : /required_consent_missing/.test(message) ? 403 : /Insufficient credits/.test(message) ? 402 : /message_required/.test(message) ? 400 : /disabled/.test(message) ? 403 : /rate_limited/.test(message) ? 429 : /idempotency_in_progress/.test(message) ? 409 : 500;
      return new Response(JSON.stringify({error:message}), { status, headers: { ...corsHeaders(), "Retry-After": String(error?.retryAfter || "") , "content-type":"application/json; charset=utf-8" } });
    }
  }
};