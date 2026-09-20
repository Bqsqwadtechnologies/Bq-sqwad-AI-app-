import { onRequest } from "firebase-functions/v2/https";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { runAi, callTavily } from "./provider-gateway.js";
import { getProviderSecret, setProviderSecret, providerSecretStatus } from "./secret-store.js";
import { paymentPreview, initializePayment, verifyPayment, setAutoRenew, handlePaystackWebhook } from "./payments.js";
import { DEVICE_COMMAND_SYSTEM, parseDeviceActions } from "./device-commands.js";
import { reserveCredits, commitCredits, reverseCredits } from "./credits.js";
import { authorizeCapability } from "./capability-runtime.js";
import { writeActivity, createManagedRecord, updateManagedRecord, COLLECTIONS } from "./runtime-controls.js";
import { listAdvancedAdminOptions, updateAdvancedAdminOption, getAdminControlConfig } from "./advanced-admin.js";
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
      // All capability endpoints below require a verified Firebase ID token.
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
        const capability = String(body.capability || "chat").trim();
        if (!/^[a-z0-9_-]{1,64}$/.test(capability)) return send(res, 400, { error: "invalid_capability" });
        const gate = await authorizeCapability(user.uid, "capability_" + capability);
        let reservation = null;
        try {
          reservation = await reserveCredits(user.uid, gate.creditCost, capability);
          const result = await runAi({
            capability,
            message: String(body.message || ""),
            system: body.system ? String(body.system) : undefined
          });
          await commitCredits(user.uid, reservation?.reservationId, result.providerId);
          await writeActivity({ uid: user.uid, capability, action: "ai_request", status: "success", metadata: { provider: result.providerId || "", model: result.modelId || "" } });
          return send(res, 200, { ...result, creditsCharged: reservation?.amount || 0, incidentId: crypto.randomUUID() });
        } catch (error) {
          await reverseCredits(user.uid, reservation?.reservationId);
          await writeActivity({ uid: user.uid, capability, action: "ai_request", status: "error", metadata: { error: error?.message || "request_failed" } });
          throw error;
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "device-command") {
        const gate = await authorizeCapability(user.uid, "capability_device_commands");
        let reservation = null;
        try {
          reservation = await reserveCredits(user.uid, gate.creditCost, "device_commands");
          const result = await runAi({
            capability: "device_commands",
            message: String(body.message || ""),
            system: DEVICE_COMMAND_SYSTEM
          });
          await commitCredits(user.uid, reservation?.reservationId, result.providerId);
          let parsed = { actions: [], message: "No supported device action." };
          try { parsed = JSON.parse(result.text); } catch {}
          return send(res, 200, {
            providerId: result.providerId,
            modelId: result.modelId,
            message: parsed.message || "",
            actions: parseDeviceActions(result.text),
            creditsCharged: reservation?.amount || 0
          });
        } catch (error) {
          await reverseCredits(user.uid, reservation?.reservationId);
          throw error;
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "web-search") {
        const query = String(body.query || "").trim();
        if (!query) return send(res, 400, { error: "query_required" });
        const db = getFirestore();
        const pricingDoc = await db.collection("adminAdvancedOptions").doc("web_search_pricing").get();
        const pricing = pricingDoc.exists
          ? (pricingDoc.data()?.config || {})
          : { creditsPerSearch: 1, creditsPerDeepResearch: 5, maxResults: 10, dailyUserLimit: null };
        if (pricingDoc.exists && pricingDoc.data()?.enabled === false) return send(res, 403, { error: "web_search_disabled" });
        const charge = body.deepResearch ? Number(pricing.creditsPerDeepResearch ?? 5) : Number(pricing.creditsPerSearch ?? 1);
        const capability = body.deepResearch ? "deep_research" : "web_search";
        const gate = await authorizeCapability(user.uid, "capability_" + capability);
        const effectiveCharge = Math.max(charge, gate.creditCost);
        const reservation = await reserveCredits(user.uid, effectiveCharge, capability);
        let result;
        try {
          result = await callTavily({
            query,
            maxResults: Math.min(Number(body.maxResults || pricing.maxResults || 10), Number(pricing.maxResults || 10)),
            deepResearch: Boolean(body.deepResearch)
          });
          await commitCredits(user.uid, reservation.reservationId, "tavily");
          return send(res, 200, { ...result, creditsCharged: reservation.amount });
        } catch (error) {
          await reverseCredits(user.uid, reservation.reservationId);
          throw error;
        } finally {
          await gate.release();
        }
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

      if (req.method === "POST" && path.startsWith("runtime/")) {
        const key = path.split("/")[1];
        if (!["jobs","workflows","agents","evaluations","plugins"].includes(key)) return send(res, 404, { error: "unsupported_runtime" });
        const record = await createManagedRecord(key === "jobs" ? "jobs" : key, user.uid, body);
        return send(res, 200, { ok: true, record });
      }

      if (req.method === "PATCH" && path.startsWith("runtime/")) {
        const parts = path.split("/");
        const key = parts[1];
        const id = parts[2];
        if (!["jobs","workflows","agents","evaluations","plugins"].includes(key) || !id) return send(res, 400, { error: "invalid_runtime_record" });
        const record = await updateManagedRecord(key, user.uid, id, body);
        return send(res, 200, { ok: true, record });
      }

      // Canonical Admin Control Center: adminAdvancedOptions is the single source of truth.
      // The legacy adminControls store and endpoints are intentionally removed.
      if (req.method === "GET" && path === "admin/config") {
        await adminFromRequest(req);
        return send(res, 200, await getAdminControlConfig());
      }

      if (req.method === "GET" && path === "admin/controls") {
        await adminFromRequest(req);
        return send(res, 200, await getAdminControlConfig());
      }

      if (req.method === "POST" && path === "admin/control") {
        const admin = await adminFromRequest(req);
        return send(res, 200, {
          ok: true,
          option: await updateAdvancedAdminOption({
            ...body,
            id: body.controlId || body.id
          }, admin.uid)
        });
      }

      if (req.method === "GET" && path === "admin/providers") {
        await adminFromRequest(req);
        const db = getFirestore();
        const config = await getAdminControlConfig();
        const providers = await Promise.all(
          ["gemini", "openai", "tavily", "paystack", "paystack_public"].map(providerSecretStatus)
        );
        return send(res, 200, {
          providers,
          routes: config.options
            .filter(x => String(x.id).startsWith("capability_"))
            .map(x => ({
              capability: String(x.id).replace(/^capability_/, ""),
              primaryProviderId: x.providerId,
              primaryModelId: x.modelId,
              fallbackProviderIds: x.config?.fallbackProviderIds || [],
              enabled: x.enabled && !x.killSwitch
            })),
          webSearchPricing: config.options.find(x => x.id === "web_search_pricing")?.config || {}
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
        const optionId = "capability_" + capability;
        const admin = await adminFromRequest(req);
        return send(res, 200, {
          ok: true,
          option: await updateAdvancedAdminOption({
            id: optionId,
            providerId: route.primaryProviderId,
            modelId: route.primaryModelId,
            enabled: route.enabled,
            config: { fallbackProviderIds: route.fallbackProviderIds }
          }, admin.uid)
        });
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
          enabled: body.enabled !== false,
          creditsPerSearch: Math.max(0, Number(body.creditsPerSearch ?? 1)),
          creditsPerDeepResearch: Math.max(0, Number(body.creditsPerDeepResearch ?? 5)),
          creditsPerResultPage: Math.max(0, Number(body.creditsPerResultPage ?? 0)),
          maxResults: Math.min(20, Math.max(1, Number(body.maxResults ?? 10))),
          dailyUserLimit: body.dailyUserLimit == null ? null : Math.max(0, Number(body.dailyUserLimit)),
          planOverrides: body.planOverrides || {}
        };
        const admin = await adminFromRequest(req);
        const current = await getFirestore().collection("adminAdvancedOptions").doc("web_search_pricing").get();
        return send(res, 200, {
          ok: true,
          option: await updateAdvancedAdminOption({
            id: "web_search_pricing",
            enabled: pricing.enabled,
            config: pricing
          }, admin.uid)
        });
      }

      return send(res, 404, { error: "not_found" });
    } catch (error) {
      console.error("BQ API error", error instanceof Error ? error.message : "request_failed");
      return send(res, 400, { error: error instanceof Error ? error.message : "request_failed" });
    }
  }
);
