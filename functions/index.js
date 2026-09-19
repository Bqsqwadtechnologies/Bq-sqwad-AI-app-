import { onRequest } from "firebase-functions/v2/https";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { runAi, callTavily } from "./provider-gateway.js";
import { getProviderSecret, setProviderSecret, providerSecretStatus } from "./secret-store.js";
import { paymentPreview, initializePayment, verifyPayment, setAutoRenew, handlePaystackWebhook } from "./payments.js";
import { DEVICE_COMMAND_SYSTEM, parseDeviceActions } from "./device-commands.js";
import { reserveCredits, commitCredits, reverseCredits } from "./credits.js";
import { listAdvancedAdminOptions, updateAdvancedAdminOption } from "./advanced-admin.js";
import crypto from "node:crypto";

initializeApp();

const ADMIN_EMAIL = "isaacoshiomole0@gmail.com";

function send(res, status, value) {
  res.status(status).set("Cache-Control", "no-store").json(value);
}

async function userFromRequest(req) {
  const header = req.headers.authorization || "";
  if (!header.startsWith("Bearer ")) throw new Error("Authentication required");
  return getAuth().verifyIdToken(header.slice(7));
}

async function adminFromRequest(req) {
  const user = await userFromRequest(req);
  if (user.email !== ADMIN_EMAIL) throw new Error("Admin access required");
  return user;
}

export const bqSqwadApi = onRequest(
  { region: "europe-west1", timeoutSeconds: 120, cors: true },
  async (req, res) => {
    try {
      const path = req.path.replace(/^\//, "");
      let body = req.body || {};
      if (typeof body === "string") {
        try { body = JSON.parse(body); } catch { body = {}; }
      }

      if (req.method === "GET" && path === "health") {
        return send(res, 200, { ok: true, service: "bq-sqwad-ai", time: new Date().toISOString() });
      }

      if (req.method === "POST" && path === "paystack/webhook") {
        const raw = Buffer.isBuffer(req.rawBody) ? req.rawBody : Buffer.from(JSON.stringify(body));
        const result = await handlePaystackWebhook(raw, req.headers["x-paystack-signature"]);
        return send(res, 200, result);
      }

      const user = await userFromRequest(req);

      if (req.method === "POST" && path === "ai") {
        const capability = String(body.capability || "chat");
        const result = await runAi({
          capability,
          message: String(body.message || ""),
          system: body.system ? String(body.system) : undefined
        });
        return send(res, 200, { ...result, incidentId: crypto.randomUUID() });
      }

      if (req.method === "POST" && path === "device-command") {
        const result = await runAi({
          capability: "device_commands",
          message: String(body.message || ""),
          system: DEVICE_COMMAND_SYSTEM
        });
        let parsed = { actions: [], message: "No supported device action." };
        try { parsed = JSON.parse(result.text); } catch {}
        return send(res, 200, {
          providerId: result.providerId,
          modelId: result.modelId,
          message: parsed.message || "",
          actions: parseDeviceActions(result.text)
        });
      }

      if (req.method === "POST" && path === "web-search") {
        const query = String(body.query || "").trim();
        if (!query) return send(res, 400, { error: "query_required" });
        const db = getFirestore();
        const pricingDoc = await db.collection("webSearchPricing").doc("default").get();
        const pricing = pricingDoc.exists ? pricingDoc.data() : { creditsPerSearch: 1, creditsPerDeepResearch: 5 };
        const charge = body.deepResearch ? Number(pricing.creditsPerDeepResearch) : Number(pricing.creditsPerSearch);
        const capability = body.deepResearch ? "deep_research" : "web_search";
        const reservation = await reserveCredits(user.uid, charge, capability);
        let result;
        try {
          result = await callTavily({ query, maxResults: body.maxResults, deepResearch: Boolean(body.deepResearch) });
          await commitCredits(user.uid, reservation.reservationId, "tavily");
        } catch (error) {
          await reverseCredits(user.uid, reservation.reservationId);
          throw error;
        }
        return send(res, 200, { ...result, creditsCharged: reservation.amount });
      }

      if (req.method === "POST" && path === "payments/preview") {
        return send(res, 200, await paymentPreview({ uid: user.uid, planId: String(body.planId || "") }));
      }

      if (req.method === "POST" && path === "payments/initialize") {
        return send(res, 200, await initializePayment({
          uid: user.uid,
          email: user.email,
          planId: String(body.planId || ""),
          callbackUrl: body.callbackUrl ? String(body.callbackUrl) : undefined
        }));
      }

      if (req.method === "POST" && path === "payments/verify") {
        return send(res, 200, await verifyPayment({ uid: user.uid, reference: String(body.reference || "") }));
      }

      if (req.method === "POST" && path === "payments/auto-renew") {
        return send(res, 200, await setAutoRenew({ uid: user.uid, enabled: Boolean(body.enabled) }));
      }

      if (req.method === "GET" && path === "admin/controls") {
        await adminFromRequest(req);
        const db = getFirestore();
        const snapshot = await db.collection("adminControls").get();
        const saved = new Map(snapshot.docs.map(d => [d.id, d.data()]));
        const ids = [
          "provider_cost_optimizer","provider_health_monitor","model_version_management",
          "evaluation_lab","prompt_config_versioning","capability_credit_pricing",
          "search_domain_controls","device_approval_policies","ai_activity_timeline",
          "action_preview","human_approval","undoable_ai_actions","agent_sandbox",
          "plugin_marketplace","background_jobs","scheduled_tasks","monitoring_agents",
          "usage_forecasting","automatic_quality_fallback","emergency_kill_switches",
          "incident_replay","security_risk_monitoring","config_snapshots_rollback",
          "regional_pricing_promos","subscription_lifecycle","business_ai_employees",
          "agent_to_agent_workflows","cross_device_sync","secure_sensitive_vault",
          "resumable_uploads","multilingual_ui_voice","accessibility_controls",
          "passkeys_sessions_devices","data_export_deletion","provider_routing",
          "model_routing","provider_secret_management","web_search_pricing",
          "feature_rollouts","admin_testing_mode"
        ];
        return send(res, 200, {
          controls: ids.map(id => ({
            id,
            enabled: saved.get(id)?.enabled !== false,
            rolloutPercent: Math.max(0, Math.min(100, Number(saved.get(id)?.rolloutPercent ?? 100))),
            killSwitch: saved.get(id)?.killSwitch === true,
            config: saved.get(id)?.config || {}
          }))
        });
      }

      if (req.method === "POST" && path === "admin/control") {
        const admin = await adminFromRequest(req);
        const id = String(body.controlId || "").trim();
        const allowed = new Set([
          "provider_cost_optimizer","provider_health_monitor","model_version_management",
          "evaluation_lab","prompt_config_versioning","capability_credit_pricing",
          "search_domain_controls","device_approval_policies","ai_activity_timeline",
          "action_preview","human_approval","undoable_ai_actions","agent_sandbox",
          "plugin_marketplace","background_jobs","scheduled_tasks","monitoring_agents",
          "usage_forecasting","automatic_quality_fallback","emergency_kill_switches",
          "incident_replay","security_risk_monitoring","config_snapshots_rollback",
          "regional_pricing_promos","subscription_lifecycle","business_ai_employees",
          "agent_to_agent_workflows","cross_device_sync","secure_sensitive_vault",
          "resumable_uploads","multilingual_ui_voice","accessibility_controls",
          "passkeys_sessions_devices","data_export_deletion","provider_routing",
          "model_routing","provider_secret_management","web_search_pricing",
          "feature_rollouts","admin_testing_mode"
        ]);
        if (!allowed.has(id)) return send(res, 400, { error: "unsupported_admin_control" });
        const enabled = body.enabled !== false;
        const rolloutPercent = Math.max(0, Math.min(100, Number(body.rolloutPercent ?? 100)));
        const killSwitch = Boolean(body.killSwitch);
        await getFirestore().collection("adminControls").doc(id).set({
          enabled, rolloutPercent, killSwitch, config: body.config || {},
          updatedAt: FieldValue.serverTimestamp(), updatedBy: admin.uid
        }, { merge: true });
        return send(res, 200, { ok: true, controlId: id, enabled, rolloutPercent, killSwitch });
      }

      if (req.method === "GET" && path === "admin/providers") {
        await adminFromRequest(req);
        const db = getFirestore();
        const routes = await db.collection("adminProviderRoutes").get();
        const pricing = await db.collection("webSearchPricing").doc("default").get();
        const providers = await Promise.all(
          ["gemini", "openai", "tavily", "paystack", "paystack_public"].map(providerSecretStatus)
        );
        return send(res, 200, {
          providers,
          routes: routes.docs.map(d => ({ capability: d.id, ...d.data() })),
          webSearchPricing: pricing.exists ? pricing.data() : { creditsPerSearch: 1, creditsPerDeepResearch: 5 }
        });
      }

      if (req.method === "POST" && path === "admin/provider-secret") {
        const admin = await adminFromRequest(req);
        const providerId = String(body.providerId || "");
        const allowed = new Set(["gemini", "openai", "tavily", "paystack", "paystack_public"]);
        if (!allowed.has(providerId)) return send(res, 400, { error: "unsupported_provider" });
        await setProviderSecret(providerId, String(body.secretValue || ""), admin.uid);
        return send(res, 200, { ok: true, providerId });
      }

      if (req.method === "POST" && path === "admin/route") {
        await adminFromRequest(req);
        const capability = String(body.capabilityId || "");
        if (!capability) return send(res, 400, { error: "capability_required" });
        const route = {
          primaryProviderId: String(body.primaryProviderId || ""),
          primaryModelId: body.primaryModelId ? String(body.primaryModelId) : null,
          fallbackProviderIds: Array.isArray(body.fallbackProviderIds) ? body.fallbackProviderIds.map(String) : [],
          enabled: body.enabled !== false,
          creditCostId: body.creditCostId ? String(body.creditCostId) : null,
          updatedAt: FieldValue.serverTimestamp()
        };
        await getFirestore().collection("adminProviderRoutes").doc(capability).set(route, { merge: true });
        return send(res, 200, { ok: true, capabilityId: capability });
      }

      if (req.method === "GET" && path === "admin/advanced-options") {
        await adminFromRequest(req);
        return send(res, 200, { options: await listAdvancedAdminOptions() });
      }

      if (req.method === "POST" && path === "admin/advanced-option") {
        const admin = await adminFromRequest(req);
        return send(res, 200, {
          ok: true,
          option: await updateAdvancedAdminOption(body, admin.uid)
        });
      }

      if (req.method === "POST" && path === "admin/web-search-pricing") {
        await adminFromRequest(req);
        const pricing = {
          pricingId: "default",
          enabled: body.enabled !== false,
          creditsPerSearch: Math.max(0, Number(body.creditsPerSearch ?? 1)),
          creditsPerDeepResearch: Math.max(0, Number(body.creditsPerDeepResearch ?? 5)),
          creditsPerResultPage: Math.max(0, Number(body.creditsPerResultPage ?? 0)),
          maxResults: Math.min(20, Math.max(1, Number(body.maxResults ?? 10))),
          dailyUserLimit: body.dailyUserLimit == null ? null : Math.max(0, Number(body.dailyUserLimit)),
          planOverrides: body.planOverrides || {},
          updatedAt: FieldValue.serverTimestamp()
        };
        await getFirestore().collection("webSearchPricing").doc("default").set(pricing, { merge: true });
        return send(res, 200, { ok: true });
      }

      return send(res, 404, { error: "not_found" });
    } catch (error) {
      console.error("BQ API error", error instanceof Error ? error.message : "request_failed");
      return send(res, 400, { error: error instanceof Error ? error.message : "request_failed" });
    }
  }
);
