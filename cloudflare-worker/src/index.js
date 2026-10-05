import { configureFirebaseEnv, verifyFirebaseUser, firestoreGet, firestoreSet, firestoreCreate, firestoreCommit, firestoreDelete } from "./firebase.js";
import { runProvider, runProviderStream, callOpenAI, callTavily, providerSecretStatus, DEFAULT_MODELS } from "./providers.js";
import { socialStart, socialCallback, listSocialAccounts, tiktokProfile, tiktokVideos, publishTikTok } from "./social.js";

const UPSTREAM = "https://europe-west1-bqsqwadai.cloudfunctions.net/bqSqwadApi";

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

async function proxyUpstream(request, url) {
  const upstreamUrl = UPSTREAM + url.pathname + url.search;
  const headers = new Headers(request.headers);
  headers.delete("host");
  let upstream;
  try {
    upstream = await fetch(upstreamUrl, {
    method: request.method,
    headers,
    body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
    redirect: "follow"
    });
  } catch (error) {
    return json({ error: "not_migrated", path: url.pathname, detail: error?.message || "upstream_unavailable" }, 501);
  }
  if (upstream.status === 404 || upstream.status === 405) {
    return json({ error: "not_migrated", path: url.pathname }, 501);
  }
  const responseHeaders = new Headers(upstream.headers);
  for (const [key, value] of Object.entries(corsHeaders(request))) responseHeaders.set(key, value);
  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: responseHeaders
  });
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

async function ai(request, user, body) {
  await requireConsent(user.uid);
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
    return json({ text: result.text.trim(), providerId: result.providerId, modelId: result.modelId, creditsCharged: reservation.amount, incidentId: crypto.randomUUID() });
  } catch (error) {
    await reverseCredits(user.uid, reservation.reservationId);
    throw error;
  }
}
async function vision(request, user, body) {
  // AI Live Camera uses the canonical Firebase backend for consent,
  // capability/credit authorization, provider routing, and audit logging.
  // Do not charge or call OpenAI again in the Worker: that would double-charge
  // each sampled frame and bypass the backend source of truth.
  return proxyUpstream(request, new URL(request.url));
}

async function stream(request, user, body) {
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
      // All feature routes not implemented natively in this Worker are intentionally forwarded to the canonical Firebase backend below.\n      // Keep this catch-through behavior so Android always uses this stable API origin while legacy backend handlers remain available.\n      const firebaseBackedPath =
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
      if (firebaseBackedPath && !hasWorkerFirebaseConfig(env)) {
        return await proxyUpstream(request, url);
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
        // Keep Google Play catalog authoritative in the canonical Firebase backend.
        // The Worker remains the stable app-facing origin and preserves query context.
        return await proxyUpstream(request, url);
      }

      if (url.pathname === "/play/verify" && request.method === "POST") {
        // Purchase verification, entitlement grants, Organization wallet deposits,
        // and Play acknowledgement/consumption remain in the canonical backend.
        return await proxyUpstream(request, url);
      }

      if (url.pathname === "/credits" && request.method === "GET") {
        const user = await verifyFirebaseUser(request);
        if (isSuperAdmin(user, env)) return json({ available: 0, reserved: 0, adminUnlimited: true, updatedAt: null, source: "admin_unlimited" });
        // The website's Firebase backend is the canonical credit authority.
        // Both website and Android read the same users/{uid} balance.
        if (!hasWorkerFirebaseConfig(env)) return await proxyUpstream(request, url);
        const profile = await readCanonicalUserBalance(user.uid);
        const balance = canonicalUserBalance(profile);
        if (!balance) return await proxyUpstream(request, url);
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
          const authHeaders = new Headers(request.headers);
          authHeaders.delete("host");
          const gate = await fetch(UPSTREAM + "/business/workspace?workspaceId=" + encodeURIComponent(workspaceId), { method: "GET", headers: authHeaders });
          if (!gate.ok) {
            const detail = await gate.text();
            return new Response(detail, { status: gate.status, headers: { ...corsHeaders(), "content-type": "application/json; charset=utf-8" } });
          }
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
      return await proxyUpstream(request, url);
    } catch(error) {
      const message = error?.message || "backend_unavailable";
      const status = /Authentication required|Invalid or expired authentication token/.test(message) ? 401 : /required_consent_missing/.test(message) ? 403 : /Insufficient credits/.test(message) ? 402 : /message_required/.test(message) ? 400 : /disabled/.test(message) ? 403 : 500;
      return json({error:message},status);
    }
  }
};