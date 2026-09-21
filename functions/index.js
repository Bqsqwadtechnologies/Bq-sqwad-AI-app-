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
import { verifyPlayPurchase, processPlayRtdn } from "./google-play.js";
import { onMessagePublished } from "firebase-functions/v2/pubsub";

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

      if (req.method === "POST" && path === "consent") {
        const requiredAccepted = body.requiredAccepted === true;
        if (!requiredAccepted) return send(res, 400, { error: "required_consent_missing" });
        const policyVersion = String(body.policyVersion || "2026-01").slice(0, 40);
        const trainingConsent = body.trainingConsent === true;
        await getFirestore().collection("userConsents").doc(user.uid).set({
          uid: user.uid,
          requiredAccepted: true,
          policyVersion,
          requiredConsentAt: FieldValue.serverTimestamp(),
          trainingConsent,
          trainingConsentAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        return send(res, 200, { ok: true, policyVersion, trainingConsent });
      }

      if (req.method === "GET" && path === "consent") {
        const snap = await getFirestore().collection("userConsents").doc(user.uid).get();
        return send(res, 200, {
          requiredAccepted: snap.exists && snap.data()?.requiredAccepted === true,
          policyVersion: snap.exists ? String(snap.data()?.policyVersion || "") : "",
          trainingConsent: snap.exists && snap.data()?.trainingConsent === true
        });
      }

      if (req.method === "POST" && path === "consent/training") {
        const trainingConsent = body.trainingConsent === true;
        await getFirestore().collection("userConsents").doc(user.uid).set({
          uid: user.uid,
          trainingConsent,
          trainingConsentAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        return send(res, 200, { ok: true, trainingConsent });
      }

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
          gate.commit();
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

      if (req.method === "POST" && path === "ai/stream") {
        const capability = String(body.capability || "chat").trim();
        if (!/^[a-z0-9_-]{1,64}$/.test(capability)) return send(res, 400, { error: "invalid_capability" });
        const gate = await authorizeCapability(user.uid, "capability_" + capability);
        let reservation = null;
        try {
          reservation = await reserveCredits(user.uid, gate.creditCost, capability);
          const route = await import("./provider-gateway.js");
          const configured = await route.getRoute(capability);
          if (configured.primaryProviderId !== "openai") throw new Error("Streaming requires the configured OpenAI route.");
          res.status(200);
          res.set("Cache-Control", "no-cache");
          res.set("Connection", "keep-alive");
          res.set("Content-Type", "text/event-stream");
          res.flushHeaders?.();
          let completed = false;
          await route.streamOpenAI({
            model: configured.primaryModelId,
            message: String(body.message || ""),
            system: body.system ? String(body.system) : undefined,
            onEvent: event => {
              if (event.type === "delta") {
                res.write("data: " + JSON.stringify({ type: "delta", text: event.text }) + "\n\n");
              } else if (event.type === "completed") {
                completed = true;
                res.write("data: " + JSON.stringify({ type: "completed" }) + "\n\n");
              } else if (event.type === "error") {
                res.write("data: " + JSON.stringify({ type: "error", message: event.message }) + "\n\n");
              }
            }
          });
          if (!completed) throw new Error("The AI stream ended before completion.");
          await commitCredits(user.uid, reservation?.reservationId, "openai");
          gate.commit();
          await writeActivity({ uid: user.uid, capability, action: "ai_stream", status: "success", metadata: { provider: "openai", model: configured.primaryModelId || "" } });
          res.write("data: [DONE]\n\n");
          return res.end();
        } catch (error) {
          await reverseCredits(user.uid, reservation?.reservationId);
          await writeActivity({ uid: user.uid, capability, action: "ai_stream", status: "error", metadata: { error: error?.message || "stream_failed" } });
          if (!res.headersSent) return send(res, 400, { error: error instanceof Error ? error.message : "stream_failed" });
          res.write("data: " + JSON.stringify({ type: "error", message: error instanceof Error ? error.message : "stream_failed" }) + "\n\n");
          return res.end();
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "ai/vision") {
        const message = String(body.message || "Describe what is visible in this camera frame.").slice(0, 4000);
        const imageBase64 = String(body.imageBase64 || "");
        if (!imageBase64) return send(res, 400, { error: "image_required" });
        if (imageBase64.length > 8_000_000) return send(res, 413, { error: "image_too_large" });
        const gate = await authorizeCapability(user.uid, "capability_chat");
        let reservation = null;
        try {
          reservation = await reserveCredits(user.uid, gate.creditCost, "chat");
          const result = await (async () => {
            const route = await import("./provider-gateway.js");
            const configured = await route.getRoute("chat");
            if (configured.primaryProviderId !== "openai") {
              throw new Error("Live camera vision requires the configured OpenAI route.");
            }
            return route.callOpenAIVision({
              model: configured.primaryModelId,
              message,
              imageBase64,
              mimeType: String(body.mimeType || "image/jpeg").slice(0, 80)
            });
          })();
          await commitCredits(user.uid, reservation?.reservationId, result.providerId);
          gate.commit();
          await writeActivity({ uid: user.uid, capability: "chat", action: "live_camera_vision", status: "success", metadata: { provider: result.providerId || "", model: result.modelId || "" } });
          return send(res, 200, { ...result, creditsCharged: reservation?.amount || 0 });
        } catch (error) {
          await reverseCredits(user.uid, reservation?.reservationId);
          await writeActivity({ uid: user.uid, capability: "chat", action: "live_camera_vision", status: "error", metadata: { error: error?.message || "vision_failed" } });
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
          gate.commit();
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
          gate.commit();
          return send(res, 200, { ...result, creditsCharged: reservation.amount });
        } catch (error) {
          await reverseCredits(user.uid, reservation.reservationId);
          throw error;
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "feedback") {
        const category = String(body.category || "general").trim().slice(0, 40);
        const sentiment = body.sentiment == null ? null : String(body.sentiment).trim().slice(0, 20);
        const reason = body.reason == null ? null : String(body.reason).trim().slice(0, 80);
        const comment = body.comment == null ? null : String(body.comment).trim().slice(0, 1000);
        const messageId = body.messageId == null ? null : String(body.messageId).trim().slice(0, 120);
        const messageHash = crypto.createHash("sha256").update(String(body.message || "")).digest("hex");
        await getFirestore().collection("aiFeedback").add({
          uid: user.uid,
          messageId,
          category,
          sentiment,
          reason,
          comment,
          messageHash,
          createdAt: FieldValue.serverTimestamp()
        });
        return send(res, 200, { ok: true });
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

      if (req.method === "POST" && path === "admin/plan") {
        const admin = await adminFromRequest(req);
        const id = String(body.id || "").trim();
        if (!id || !/^[a-zA-Z0-9_-]{1,80}$/.test(id)) return send(res, 400, { error: "invalid_plan_id" });
        const plan = {
          name: String(body.name || "").trim().slice(0, 120),
          active: body.active !== false,
          amountMinor: Math.max(0, Number(body.amountMinor || 0)),
          currency: String(body.currency || "NGN").toUpperCase().slice(0, 3),
          billingPeriod: String(body.billingPeriod || "monthly").slice(0, 30),
          features: Array.isArray(body.features) ? body.features.map(String).slice(0, 50) : [],
          limits: body.limits && typeof body.limits === "object" ? body.limits : {},
          updatedAt: FieldValue.serverTimestamp(),
          updatedBy: admin.uid
        };
        await getFirestore().collection("plans").doc(id).set(plan, { merge: true });
        return send(res, 200, { ok: true, plan: { id, ...plan } });
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

      if (req.method === "GET" && path === "entitlement") {
        const snap = await getFirestore().collection("entitlements").doc(user.uid).get();
        return send(res, 200, snap.exists ? { exists: true, ...snap.data() } : { exists: false });
      }

      if (req.method === "GET" && path === "native/catalog") {
        const db = getFirestore();
        const [plansSnap, playSnap, featureSnap, bindingSnap, addOnSnap, businessSnap] = await Promise.all([
          db.collection("plans").where("active", "==", true).get(),
          db.collection("playCatalog").doc("config").get(),
          db.collection("featureRegistry").get(),
          db.collection("planFeatureBindings").get(),
          db.collection("addOns").where("enabled", "==", true).get(),
          db.collection("config").doc("businessPricing").get()
        ]);
        const safePlan = (id, data = {}) => ({
          id,
          name: String(data.name || ""),
          description: String(data.description || ""),
          module: String(data.module || data.product || ""),
          features: Array.isArray(data.features) ? data.features.map(String).slice(0, 100) : [],
          limits: data.limits && typeof data.limits === "object" ? data.limits : {},
          badge: data.badge ? String(data.badge) : null,
          highlighted: data.highlighted === true,
          sortOrder: Number(data.sortOrder || 0),
          visible: data.visible !== false,
          version: Number(data.version || 1),
          effectiveDate: data.effectiveDate || null
        });
        const products = playSnap.exists && Array.isArray(playSnap.data()?.products) ? playSnap.data().products.map(p => ({
          planId: String(p.planId || ""),
          productId: String(p.productId || ""),
          basePlanId: p.basePlanId ? String(p.basePlanId) : null,
          productType: String(p.productType || ""),
          enabled: p.enabled === true,
          consumable: p.consumable === true
        })).filter(p => p.productId && (p.productType === "subs" || p.productType === "inapp")) : [];
        const features = featureSnap.docs.map(d => {
          const x = d.data();
          return { id: d.id, name: String(x.name || x.title || d.id), description: String(x.description || ""), group: String(x.group || x.category || ""), product: String(x.product || ""), defaultState: String(x.defaultState || "unavailable"), visible: x.visible !== false, comingSoon: x.comingSoon === true };
        });
        const bindings = bindingSnap.docs.map(d => {
          const x = d.data();
          return { id: d.id, planId: String(x.planId || ""), featureId: String(x.featureId || ""), state: String(x.state || "unavailable"), quota: x.quota ?? null, creditCost: x.creditCost ?? null, note: x.note ? String(x.note) : null, version: Number(x.version || 1) };
        });
        const addOns = addOnSnap.docs.map(d => {
          const x = d.data();
          return { id: d.id, name: String(x.name || d.id), description: String(x.description || ""), type: String(x.type || ""), enabled: x.enabled !== false, eligiblePlanIds: Array.isArray(x.eligiblePlanIds) ? x.eligiblePlanIds.map(String) : [], quantityTiers: Array.isArray(x.quantityTiers) ? x.quantityTiers : [] };
        });
        const business = businessSnap.exists ? businessSnap.data() : null;
        return send(res, 200, { version: Math.max(1, Number(business?.version || playSnap.data()?.version || 1)), plans: plansSnap.docs.map(d => safePlan(d.id, d.data())).filter(x => x.visible), products, features: features.filter(x => x.visible), bindings, addOns, businessPricingConfigured: business !== null });
      }

      if (req.method === "GET" && path === "play/catalog") {
        const snap = await getFirestore().collection("playCatalog").doc("config").get();
        const products = snap.exists && Array.isArray(snap.data()?.products) ? snap.data().products : [];
        return send(res, 200, {
          version: snap.exists ? Number(snap.data()?.version || 1) : 1,
          products: products.map(p => ({
            planId: String(p.planId || ""),
            productId: String(p.productId || ""),
            basePlanId: p.basePlanId ? String(p.basePlanId) : null,
            productType: String(p.productType || ""),
            enabled: p.enabled === true,
            consumable: p.consumable === true
          })).filter(p => p.productId && (p.productType === "subs" || p.productType === "inapp"))
        });
      }

      if (req.method === "POST" && path === "play/verify") {
        const productId = String(body.productId || "").trim();
        const purchaseToken = String(body.purchaseToken || "").trim();
        const result = await verifyPlayPurchase({ uid: user.uid, productId, purchaseToken, obfuscatedAccountId: body.obfuscatedAccountId ? String(body.obfuscatedAccountId) : "" });
        return send(res, 200, result);
      }

      if (req.method === "GET" && path === "plans") {
        const snap = await getFirestore().collection("plans").where("active", "==", true).get();
        return send(res, 200, { plans: snap.docs.map(d => ({ id: d.id, ...d.data() })) });
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


export const googlePlayRtdn = onMessagePublished({ topic: "google-play-rtdn", region: "europe-west1" }, async (event) => {
  try { return await processPlayRtdn(event); }
  catch (error) { console.error("Google Play RTDN error", error instanceof Error ? error.message : "notification_failed"); throw error; }
});
