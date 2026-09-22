import { onRequest } from "firebase-functions/v2/https";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
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
import { COMMERCIAL_CATALOG, PLAN_CATALOG, PLAN_CATALOG_VERSION } from "./plan-catalog.js";
import { canonicalAdminCatalog, canonicalPlayProducts } from "./play-catalog.js";
import { getUserMemory, rememberUser, setMemoryEnabled, clearUserMemory, memoryContext } from "./memory.js";
import { getOrganization, updateOrganization, updateOrganizationSettings, addOrganizationChannel, setOrganizationChannel, addOrganizationKnowledge, upsertOrganizationMember, setOrganizationMemberStatus } from "./organization.js";
import { getProduction, updateProductionConfig, upsertSource, removeSource, upsertScene, upsertDestination, setProductionState, updateHealth, addProductionEvent } from "./organization-production.js";
import { onMessagePublished } from "firebase-functions/v2/pubsub";

initializeApp();

const ADMIN_EMAIL = "isaacoshiomole0@gmail.com";

async function assertSuperAdmin(user) {
  const token = await getAuth().getUser(user.uid);
  const claims = token.customClaims || {};
  if (user.email !== ADMIN_EMAIL || (claims.admin !== true && claims.superAdmin !== true && claims.role !== "admin" && claims.role !== "super_admin")) {
    throw new Error("Admin access required");
  }
  return user;
}

function send(res, status, value) {
  res.status(status).set("Cache-Control", "no-store").json(value);
}

async function userFromRequest(req) {
  const header = req.headers.authorization || "";
  if (!header.startsWith("Bearer ")) throw new Error("Authentication required");
  return getAuth().verifyIdToken(header.slice(7), true);
}

async function requireRequiredConsent(uid) {
  const snap = await getFirestore().collection("userConsents").doc(uid).get();
  if (!snap.exists || snap.data()?.requiredAccepted !== true) {
    throw new Error("required_consent_missing");
  }
}

async function adminFromRequest(req) {
  const user = await userFromRequest(req);
      // All capability endpoints below require a verified Firebase ID token.
  return assertSuperAdmin(user);
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
      if (path !== "consent" && path !== "consent/training") {
        await requireRequiredConsent(user.uid);
      }

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

      if (req.method === "GET" && path === "history") {
        const limit = Math.min(100, Math.max(1, Number(req.query.limit || 50)));
        const snap = await getFirestore().collection("chatHistory")
          .where("uid", "==", user.uid)
          .limit(100)
          .get();
        const docs = snap.docs.sort((a, b) => {
          const am = a.data()?.updatedAt?.toMillis?.() || 0;
          const bm = b.data()?.updatedAt?.toMillis?.() || 0;
          return bm - am;
        }).slice(0, limit);
        return send(res, 200, {
          items: docs.map(doc => {
            const data = doc.data() || {};
            return {
              id: doc.id,
              title: String(data.title || "New chat"),
              updatedAt: data.updatedAt?.toDate?.()?.toISOString?.() || "",
              messageCount: Array.isArray(data.messages) ? data.messages.length : 0
            };
          })
        });
      }

      if (req.method === "GET" && path.startsWith("history/")) {
        const id = path.slice("history/".length).trim();
        if (!id || id.includes("/")) return send(res, 400, { error: "invalid_history_id" });
        const doc = await getFirestore().collection("chatHistory").doc(id).get();
        if (!doc.exists || doc.data()?.uid !== user.uid) return send(res, 404, { error: "history_not_found" });
        const data = doc.data() || {};
        return send(res, 200, { id, title: String(data.title || "New chat"), messages: Array.isArray(data.messages) ? data.messages : [] });
      }

      if (req.method === "POST" && path === "history") {
        const requestedId = String(body.id || "").trim();
        const ref = requestedId
          ? getFirestore().collection("chatHistory").doc(requestedId)
          : getFirestore().collection("chatHistory").doc();
        const existing = requestedId ? await ref.get() : null;
        if (existing?.exists && existing.data()?.uid !== user.uid) return send(res, 403, { error: "history_forbidden" });
        const rawMessages = Array.isArray(body.messages) ? body.messages : [];
        const messages = rawMessages.slice(-100).map(item => ({
          id: String(item?.id || crypto.randomUUID()).slice(0, 120),
          mine: item?.mine === true,
          text: String(item?.text || "").slice(0, 20000),
          ...(item?.prompt ? { prompt: String(item.prompt).slice(0, 20000) } : {})
        })).filter(item => item.text.length > 0);
        if (!messages.length) return send(res, 400, { error: "history_messages_required" });
        const title = String(body.title || messages.find(item => item.mine)?.text || "New chat").trim().slice(0, 180);
        await ref.set({
          uid: user.uid,
          title,
          messages,
          createdAt: existing?.exists ? (existing.data()?.createdAt || FieldValue.serverTimestamp()) : FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        return send(res, 200, { ok: true, id: ref.id, title, messageCount: messages.length });
      }

      if (req.method === "DELETE" && path.startsWith("history/")) {
        const id = path.slice("history/".length).trim();
        if (!id || id.includes("/")) return send(res, 400, { error: "invalid_history_id" });
        const ref = getFirestore().collection("chatHistory").doc(id);
        const doc = await ref.get();
        if (!doc.exists || doc.data()?.uid !== user.uid) return send(res, 404, { error: "history_not_found" });
        await ref.delete();
        return send(res, 200, { ok: true });
      }

      if (req.method === "GET" && path === "memory") {
        return send(res, 200, await getUserMemory(user.uid));
      }

      if (req.method === "POST" && path === "memory") {
        const item = await rememberUser(user.uid, body.text, body.source);
        return send(res, 200, { ok: true, item });
      }

      if (req.method === "POST" && path === "memory/settings") {
        return send(res, 200, await setMemoryEnabled(user.uid, body.enabled === true));
      }

      if (req.method === "DELETE" && path === "memory") {
        return send(res, 200, await clearUserMemory(user.uid));
      }

      if (req.method === "GET" && path === "organization") {
        return send(res, 200, await getOrganization(user.uid));
      }

      if (req.method === "POST" && path === "organization") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try {
          const result = await updateOrganization(user.uid, body);
          gate.commit();
          return send(res, 200, result);
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "organization/channels") {
        const name = String(body.name || "").trim();
        if (!name) return send(res, 400, { error: "channel_name_required" });
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try {
          const result = await addOrganizationChannel(user.uid, name, body.type);
          gate.commit();
          return send(res, 200, result);
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "organization/settings") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try {
          const result = await updateOrganizationSettings(user.uid, body);
          gate.commit();
          return send(res, 200, result);
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "organization/knowledge") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try {
          const result = await addOrganizationKnowledge(user.uid, body.text, body.source);
          gate.commit();
          return send(res, 200, result);
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "organization/members") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try {
          const result = await upsertOrganizationMember(user.uid, body.memberId, body.role);
          gate.commit();
          return send(res, 200, result);
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "organization/member-state") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try {
          const result = await setOrganizationMemberStatus(user.uid, body.memberId, body.status);
          gate.commit();
          return send(res, 200, result);
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "organization/channel-state") {
        const channelId = String(body.channelId || "").trim();
        if (!channelId) return send(res, 400, { error: "channel_id_required" });
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try {
          const result = await setOrganizationChannel(user.uid, channelId, body.enabled === true);
          gate.commit();
          return send(res, 200, result);
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "organization/assistant") {
        const message = String(body.message || "").trim().slice(0, 8000);
        if (!message) return send(res, 400, { error: "organization_message_required" });
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        let reservation = null;
        try {
          const org = await getOrganization(user.uid);
          const settings = org.settings || {};
          if (settings.assistantEnabled === false) return send(res, 403, { error: "organization_ai_disabled" });
          const knowledge = settings.sharedKnowledgeEnabled === false ? "" : (Array.isArray(org.knowledge)
            ? org.knowledge.slice(-50).map(item => typeof item === "string" ? item : item?.text).filter(Boolean).join("\n")
            : "");
          const sharedMemory = settings.sharedMemoryEnabled === true ? await memoryContext(user.uid) : "";
          reservation = await reserveCredits(user.uid, gate.creditCost, "organization_assistant");
          const system = [
            "You are the BQ Sqwad AI Organization Studio assistant.",
            "Keep organization data isolated from unrelated personal workspace data.",
            "Use the organization profile and organization knowledge below for organization-specific claims.",
            "Do not invent organization facts. If the required information is missing, say so.",
            "Organization: " + String(org.name || "My Organization"),
            "Description: " + String(org.description || ""),
            "Organization knowledge:",
            knowledge || "(No organization knowledge has been added.)",
            sharedMemory ? "Approved shared user memory:\n" + sharedMemory : ""
          ].filter(Boolean).join("\n");
          const result = await runAi({ capability: "chat", message, system });
          await commitCredits(user.uid, reservation?.reservationId, result.providerId);
          gate.commit();
          await writeActivity({ uid: user.uid, capability: "organization_studio", action: "organization_assistant", status: "success", metadata: { provider: result.providerId || "", model: result.modelId || "" } });
          return send(res, 200, { answer: result.text, providerId: result.providerId || null, creditsCharged: reservation?.amount || 0 });
        } catch (error) {
          await reverseCredits(user.uid, reservation?.reservationId);
          await writeActivity({ uid: user.uid, capability: "organization_studio", action: "organization_assistant", status: "error", metadata: { error: error?.message || "organization_request_failed" } });
          throw error;
        } finally {
          await gate.release();
        }
      }

      if (req.method === "GET" && path === "organization/production") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try { const result = await getProduction(user.uid); gate.commit(); return send(res, 200, result); }
        finally { await gate.release(); }
      }

      if (req.method === "POST" && path === "organization/production/config") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try { const result = await updateProductionConfig(user.uid, body); gate.commit(); return send(res, 200, result); }
        finally { await gate.release(); }
      }

      if (req.method === "POST" && path === "organization/production/source") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try { const result = await upsertSource(user.uid, body); gate.commit(); return send(res, 200, result); }
        finally { await gate.release(); }
      }

      if (req.method === "POST" && path === "organization/production/source/remove") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try { const result = await removeSource(user.uid, body.sourceId); gate.commit(); return send(res, 200, result); }
        finally { await gate.release(); }
      }

      if (req.method === "POST" && path === "organization/production/scene") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try { const result = await upsertScene(user.uid, body); gate.commit(); return send(res, 200, result); }
        finally { await gate.release(); }
      }

      if (req.method === "POST" && path === "organization/production/destination") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try { const result = await upsertDestination(user.uid, body); gate.commit(); return send(res, 200, result); }
        finally { await gate.release(); }
      }

      if (req.method === "POST" && path === "organization/production/state") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try { const result = await setProductionState(user.uid, body); gate.commit(); return send(res, 200, result); }
        finally { await gate.release(); }
      }

      if (req.method === "POST" && path === "organization/production/health") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try { const result = await updateHealth(user.uid, body); gate.commit(); return send(res, 200, result); }
        finally { await gate.release(); }
      }

      if (req.method === "POST" && path === "organization/production/event") {
        const gate = await authorizeCapability(user.uid, "capability_organization_studio");
        try { const result = await addProductionEvent(user.uid, body); gate.commit(); return send(res, 200, result); }
        finally { await gate.release(); }
      }

      if (req.method === "GET" && path === "business/workspace") {
        const db = getFirestore();
        const ref = db.collection("businessWorkspaces").doc(user.uid);
        const snap = await ref.get();
        if (!snap.exists) {
          const initial = {
            uid: user.uid,
            name: "Business Workspace",
            assistantName: "Business AI Assistant",
            knowledge: [],
            inbox: [],
            leads: [],
            automations: [],
            analytics: { requests: 0, leads: 0, knowledge: 0 },
            createdAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp()
          };
          await ref.set(initial);
          return send(res, 200, { ...initial, createdAt: null, updatedAt: null });
        }
        return send(res, 200, { exists: true, ...snap.data() });
      }

      if (req.method === "POST" && path === "business/training-assets/upload-url") {
        const type = String(body.type || "").trim().toLowerCase();
        const allowedTypes = new Set(["document", "photo", "voice", "video", "step_by_step", "appearance_hair"]);
        if (!allowedTypes.has(type)) return send(res, 400, { error: "unsupported_training_asset_type" });
        const title = String(body.title || "").trim().slice(0, 180);
        const purpose = String(body.purpose || "").trim().slice(0, 1000);
        const fileName = String(body.fileName || "training-asset").trim().slice(0, 160);
        const contentType = String(body.contentType || "application/octet-stream").trim().slice(0, 120);
        const size = Math.max(0, Number(body.size || 0));
        if (!title || !purpose || !fileName || !size) return send(res, 400, { error: "training_asset_metadata_required" });
        if (size > 500 * 1024 * 1024) return send(res, 413, { error: "training_asset_too_large" });
        const assetId = crypto.randomUUID();
        const safeName = fileName.replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 120);
        const objectPath = "business-training/" + user.uid + "/" + assetId + "-" + safeName;
        const bucket = getStorage().bucket();
        const file = bucket.file(objectPath);
        const [uploadUrl] = await file.getSignedUrl({
          version: "v4",
          action: "write",
          expires: Date.now() + 15 * 60 * 1000,
          contentType
        });
        await getFirestore().collection("businessTrainingAssets").doc(assetId).set({
          id: assetId,
          uid: user.uid,
          type,
          title,
          purpose,
          fileName: safeName,
          contentType,
          size,
          objectPath,
          status: "upload_pending",
          createdAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp()
        });
        return send(res, 200, { ok: true, assetId, uploadUrl, objectPath, expiresInSeconds: 900 });
      }

      if (req.method === "POST" && path === "business/training-assets/finalize") {
        const assetId = String(body.assetId || "").trim();
        if (!assetId) return send(res, 400, { error: "training_asset_id_required" });
        const ref = getFirestore().collection("businessTrainingAssets").doc(assetId);
        const snap = await ref.get();
        if (!snap.exists || snap.data()?.uid !== user.uid) return send(res, 404, { error: "training_asset_not_found" });
        const data = snap.data() || {};
        const file = getStorage().bucket().file(String(data.objectPath || ""));
        const [exists] = await file.exists();
        if (!exists) return send(res, 409, { error: "training_asset_upload_missing" });
        const [metadata] = await file.getMetadata();
        const actualSize = Number(metadata.size || 0);
        const expectedSize = Number(data.size || 0);
        if (!actualSize || (expectedSize && actualSize !== expectedSize)) return send(res, 409, { error: "training_asset_size_mismatch" });
        await ref.set({
          status: "uploaded",
          uploadedAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        const workspaceRef = getFirestore().collection("businessWorkspaces").doc(user.uid);
        const workspaceSnap = await workspaceRef.get();
        const workspace = workspaceSnap.exists ? workspaceSnap.data() || {} : {};
        const assets = Array.isArray(workspace.trainingAssets) ? workspace.trainingAssets : [];
        const entry = {
          id: assetId,
          type: String(data.type || ""),
          title: String(data.title || ""),
          purpose: String(data.purpose || ""),
          contentType: String(data.contentType || ""),
          size: actualSize,
          objectPath: String(data.objectPath || ""),
          status: "uploaded",
          createdAt: new Date().toISOString()
        };
        const without = assets.filter(item => String(item?.id || "") !== assetId);
        without.push(entry);
        await workspaceRef.set({ uid: user.uid, trainingAssets: without.slice(-500), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
        return send(res, 200, { ok: true, asset: entry });
      }

      if (req.method === "POST" && path === "business/training-assets") {
        const type = String(body.type || "").trim().toLowerCase();
        const allowedTypes = new Set(["text", "document", "photo", "voice", "video", "step_by_step", "appearance_hair"]);
        if (!allowedTypes.has(type)) return send(res, 400, { error: "unsupported_training_asset_type" });
        const purpose = String(body.purpose || "").trim().slice(0, 1000);
        const title = String(body.title || "").trim().slice(0, 180);
        if (!title || !purpose) return send(res, 400, { error: "training_asset_title_and_purpose_required" });
        const db = getFirestore();
        const ref = db.collection("businessWorkspaces").doc(user.uid);
        const snap = await ref.get();
        const current = snap.exists ? snap.data() : {};
        const trainingAssets = Array.isArray(current.trainingAssets) ? current.trainingAssets : [];
        trainingAssets.push({
          id: crypto.randomUUID(),
          type,
          title,
          purpose,
          status: "metadata_saved",
          createdAt: new Date().toISOString()
        });
        await ref.set({ uid: user.uid, trainingAssets: trainingAssets.slice(-500), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
        return send(res, 200, { ok: true, trainingAssets });
      }

      if (req.method === "POST" && path === "business/knowledge") {
        const text = String(body.text || "").trim().slice(0, 12000);
        if (!text) return send(res, 400, { error: "knowledge_text_required" });
        const db = getFirestore();
        const ref = db.collection("businessWorkspaces").doc(user.uid);
        const snap = await ref.get();
        const current = snap.exists ? snap.data() : {};
        const knowledge = Array.isArray(current.knowledge) ? current.knowledge : [];
        const item = { id: crypto.randomUUID(), text, createdAt: new Date().toISOString() };
        knowledge.push(item);
        await ref.set({
          uid: user.uid,
          name: current.name || "Business Workspace",
          assistantName: current.assistantName || "Business AI Assistant",
          knowledge: knowledge.slice(-200),
          inbox: Array.isArray(current.inbox) ? current.inbox : [],
          leads: Array.isArray(current.leads) ? current.leads : [],
          automations: Array.isArray(current.automations) ? current.automations : [],
          analytics: { ...(current.analytics || {}), knowledge: knowledge.length },
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        const updated = await ref.get();
        return send(res, 200, { exists: true, ...updated.data() });
      }

      if (req.method === "POST" && path === "business/leads") {
        const name = String(body.name || "").trim().slice(0, 160);
        const contact = String(body.contact || "").trim().slice(0, 320);
        const note = String(body.note || "").trim().slice(0, 2000);
        if (!name) return send(res, 400, { error: "lead_name_required" });
        const db = getFirestore();
        const ref = db.collection("businessWorkspaces").doc(user.uid);
        const snap = await ref.get();
        const current = snap.exists ? snap.data() : {};
        const leads = Array.isArray(current.leads) ? current.leads : [];
        leads.push({ id: crypto.randomUUID(), name, contact, note, status: "new", createdAt: new Date().toISOString() });
        await ref.set({
          uid: user.uid,
          leads: leads.slice(-500),
          analytics: { ...(current.analytics || {}), leads: leads.length },
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        const updated = await ref.get();
        return send(res, 200, { exists: true, ...updated.data() });
      }

      if (req.method === "POST" && path === "business/inbox") {
        const message = String(body.message || "").trim().slice(0, 4000);
        const contact = String(body.contact || "").trim().slice(0, 320);
        if (!message) return send(res, 400, { error: "inbox_message_required" });
        const db = getFirestore();
        const ref = db.collection("businessWorkspaces").doc(user.uid);
        const snap = await ref.get();
        const current = snap.exists ? snap.data() : {};
        const inbox = Array.isArray(current.inbox) ? current.inbox : [];
        inbox.push({ id: crypto.randomUUID(), message, contact, status: "open", createdAt: new Date().toISOString() });
        await ref.set({
          uid: user.uid,
          inbox: inbox.slice(-500),
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        const updated = await ref.get();
        return send(res, 200, { exists: true, ...updated.data() });
      }

      if (req.method === "POST" && path === "business/assistant") {
        const message = String(body.message || "").trim().slice(0, 8000);
        if (!message) return send(res, 400, { error: "business_message_required" });
        const db = getFirestore();
        const ref = db.collection("businessWorkspaces").doc(user.uid);
        const snap = await ref.get();
        const data = snap.exists ? snap.data() : {};
        const knowledge = Array.isArray(data.knowledge) ? data.knowledge : [];
        const knowledgeText = knowledge.slice(-30).map(item => typeof item === "string" ? item : item?.text).filter(Boolean).join("\n");
        const gate = await authorizeCapability(user.uid, "capability_chat");
        let reservation = null;
        try {
          reservation = await reserveCredits(user.uid, gate.creditCost, "chat");
          const system = [
            "You are the BQ Sqwad AI Business Assistant.",
            "Keep business workspace information isolated from personal workspace context.",
            "Use only the business knowledge supplied below when making business-specific claims; if it is absent, say that the workspace does not contain that information.",
            "Business knowledge:",
            knowledgeText || "(No business knowledge has been added yet.)"
          ].join("\n");
          const result = await runAi({ capability: "chat", message, system });
          await commitCredits(user.uid, reservation?.reservationId, result.providerId);
          gate.commit();
          await ref.set({
            uid: user.uid,
            analytics: { ...(data.analytics || {}), requests: Number(data.analytics?.requests || 0) + 1 },
            updatedAt: FieldValue.serverTimestamp()
          }, { merge: true });
          await writeActivity({ uid: user.uid, capability: "chat", action: "business_assistant", status: "success", metadata: { provider: result.providerId || "", model: result.modelId || "" } });
          return send(res, 200, { answer: result.text, providerId: result.providerId || null, creditsCharged: reservation?.amount || 0 });
        } catch (error) {
          await reverseCredits(user.uid, reservation?.reservationId);
          await writeActivity({ uid: user.uid, capability: "chat", action: "business_assistant", status: "error", metadata: { error: error?.message || "business_request_failed" } });
          throw error;
        } finally {
          await gate.release();
        }
      }

      if (req.method === "POST" && path === "ai") {
        const capability = String(body.capability || "chat").trim();
        if (!/^[a-z0-9_-]{1,64}$/.test(capability)) return send(res, 400, { error: "invalid_capability" });
        const gate = await authorizeCapability(user.uid, "capability_" + capability);
        let reservation = null;
        try {
          reservation = await reserveCredits(user.uid, gate.creditCost, capability);
          const remembered = await memoryContext(user.uid);
          const suppliedSystem = body.system ? String(body.system) : "";
          const system = remembered
            ? [suppliedSystem, "Relevant user memory (use only when helpful and do not invent details):", remembered].filter(Boolean).join("\n")
            : suppliedSystem || undefined;
          const result = await runAi({
            capability,
            message: String(body.message || ""),
            system
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
            system: (await memoryContext(user.uid))
              ? [(body.system ? String(body.system) : ""), "Relevant user memory (use only when helpful and do not invent details):", await memoryContext(user.uid)].filter(Boolean).join("\n")
              : (body.system ? String(body.system) : undefined),
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
          res.write("data: [DONE]

");
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
      if (req.method === "GET" && path === "admin/play-catalog") {
        await adminFromRequest(req);
        return send(res, 200, canonicalAdminCatalog());
      }

      if (req.method === "POST" && path === "contact-sales") {
        const email = String(body.email || user.email || "").trim().slice(0, 320);
        const companyName = String(body.companyName || "").trim().slice(0, 180);
        const companyEmail = String(body.companyEmail || "").trim().slice(0, 320);
        let planId = String(body.planId || "").trim().slice(0, 100);
        const planName = String(body.planName || "").trim().slice(0, 180);
        const billingPeriod = String(body.billingPeriod || "").trim().slice(0, 40);
        const message = String(body.message || "").trim().slice(0, 6000);
        if (!planId && planName) {
          const match = Object.values(COMMERCIAL_CATALOG.sections).flat().find(item => String(item.name || "") === planName);
          if (match) planId = String(match.id);
        }
        const features = Array.isArray(body.features) ? body.features.map(x => String(x).slice(0, 180)).slice(0, 100) : [];
        if (!email || !planId || !message) return send(res, 400, { error: "email_plan_and_message_required" });
        const ref = await getFirestore().collection("contactSalesRequests").add({
          uid: user.uid, email, companyName, companyEmail, planId, planName, billingPeriod, features, message,
          status: "new", createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp()
        });
        return send(res, 200, { ok: true, requestId: ref.id, status: "new" });
      }

      if (req.method === "GET" && path === "contact-sales/status") {
        const snap = await getFirestore().collection("contactSalesRequests").where("uid", "==", user.uid).orderBy("updatedAt", "desc").limit(10).get();
        return send(res, 200, { requests: snap.docs.map(d => ({ id: d.id, ...d.data() })) });
      }

      if (req.method === "GET" && path === "admin/contact-sales") {
        await adminFromRequest(req);
        const snap = await getFirestore().collection("contactSalesRequests").orderBy("createdAt", "desc").limit(100).get();
        return send(res, 200, { requests: snap.docs.map(d => ({ id: d.id, ...d.data() })) });
      }

      if (req.method === "POST" && path === "admin/contact-sales/status") {
        await adminFromRequest(req);
        const requestId = String(body.requestId || "").trim();
        const status = String(body.status || "").trim().toLowerCase();
        if (!requestId || !["seen","contacted","replied","closed"].includes(status)) return send(res, 400, { error: "invalid_request_status" });
        await getFirestore().collection("contactSalesRequests").doc(requestId).set({
          status, lastAdminAction: status, lastAdminActionAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        return send(res, 200, { ok: true, requestId, status });
      }

      if (req.method === "GET" && path === "admin/plan-catalog") {
        await adminFromRequest(req);
        const snap = await getFirestore().collection("planCatalog").doc("config").get();
        if (snap.exists) {
          const data = snap.data() || {};
          return send(res, 200, {
            version: Number(data.version || PLAN_CATALOG_VERSION),
            catalog: data.catalog || canonicalAdminCatalog(),
            source: "firestore",
            updatedAt: data.updatedAt || null
          });
        }
        return send(res, 200, { version: PLAN_CATALOG_VERSION, catalog: canonicalAdminCatalog(), source: "code-fallback" });
      }

      if (req.method === "POST" && path === "admin/plan-catalog") {
        const admin = await adminFromRequest(req);
        const incoming = body.catalog;
        if (!incoming || typeof incoming !== "object" || Array.isArray(incoming)) {
          return send(res, 400, { error: "catalog_object_required" });
        }
        const raw = JSON.stringify(incoming);
        if (raw.includes("_")) return send(res, 400, { error: "commercial_ids_must_not_contain_underscores" });

        const ids = [];
        const products = [];
        const walk = value => {
          if (!value || typeof value !== "object") return;
          if (Array.isArray(value)) return value.forEach(walk);
          if (typeof value.id === "string") {
            if (!/^[a-z0-9-]+$/.test(value.id)) throw new Error("invalid_commercial_id");
            if (ids.includes(value.id)) throw new Error("duplicate_commercial_id");
            ids.push(value.id);
          }
          if (typeof value.productId === "string" && value.productId) {
            if (!/^[a-z0-9-]+$/.test(value.productId)) throw new Error("invalid_product_id");
            products.push(value.productId);
          }
          if (Array.isArray(value.quantityTiers)) {
            for (const tier of value.quantityTiers) {
              if (!tier || !/^[a-z0-9-]+$/.test(String(tier.tierId || ""))) throw new Error("invalid_tier_id");
            }
          }
          Object.values(value).forEach(walk);
        };
        try { walk(incoming); } catch (e) { return send(res, 400, { error: e.message || "invalid_catalog" }); }

        const existing = await getFirestore().collection("planCatalog").doc("config").get();
        const version = Math.max(
          PLAN_CATALOG_VERSION,
          Number(existing.data()?.version || PLAN_CATALOG_VERSION),
          Number(body.version || 0)
        ) + (existing.exists ? 1 : 0);

        const payload = {
          version,
          catalog: { ...incoming, version },
          commercialIdCount: ids.length,
          productIdCount: products.length,
          updatedBy: admin.uid,
          updatedAt: FieldValue.serverTimestamp()
        };
        await getFirestore().collection("planCatalog").doc("config").set(payload, { merge: false });
        await getFirestore().collection("adminConfigAudit").add({
          type: "plan_catalog_update",
          version,
          actorUid: admin.uid,
          previousVersion: Number(existing.data()?.version || PLAN_CATALOG_VERSION),
          commercialIdCount: ids.length,
          productIdCount: products.length,
          createdAt: FieldValue.serverTimestamp()
        });
        return send(res, 200, { ok: true, version, catalog: incoming });
      }

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
        if (!id || !/^[a-z0-9-]{1,80}$/.test(id)) return send(res, 400, { error: "invalid_plan_id" });
        const plan = {
          name: String(body.name || "").trim().slice(0, 120),
          active: body.active !== false,
          amountMinor: Math.max(0, Number(body.amountMinor || 0)),
          currency: "USD",
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

      if (req.method === "GET" && path === "credits") {
        const snap = await getFirestore().collection("creditBalances").doc(user.uid).get();
        const data = snap.exists ? snap.data() : {};
        return send(res, 200, {
          available: Number(data.available || 0),
          reserved: Number(data.reserved || 0),
          updatedAt: data.updatedAt || null
        });
      }

      if (req.method === "GET" && path === "usage") {
        const limit = Math.min(100, Math.max(1, Number(req.query?.limit || 50)));
        const snap = await getFirestore().collection("creditLedger")
          .where("uid", "==", user.uid)
          .limit(100)
          .get();
        const items = snap.docs.map(d => ({ id: d.id, ...d.data() }));
        items.sort((a, b) => (b.createdAt?.toMillis?.() || 0) - (a.createdAt?.toMillis?.() || 0));
        return send(res, 200, { items: items.slice(0, limit) });
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
        const configuredProducts = playSnap.exists && Array.isArray(playSnap.data()?.products) ? playSnap.data().products : canonicalPlayProducts();
        const products = configuredProducts.map(p => ({
          planId: String(p.planId || ""),
          productId: String(p.productId || ""),
          basePlanId: p.basePlanId ? String(p.basePlanId) : null,
          productType: String(p.productType || ""),
          enabled: p.enabled === true,
          consumable: p.consumable === true
        })).filter(p => p.productId && !p.productId.includes("_") && /^[a-z0-9-]+$/.test(p.productId) && (p.productType === "subs" || p.productType === "inapp"));
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
        const publicContactSales = Object.values(COMMERCIAL_CATALOG.sections).flat().flatMap(item => {
          const periods = item.billingPeriods || [];
          const blockedPeriods = Array.isArray(item.playEligiblePeriods)
            ? periods.filter(period => !item.playEligiblePeriods.includes(period))
            : [];
          const rows = item.contactSales
            ? [{name:item.name,billingPeriod:"custom",price:"Contact Sales",features:item.features || [],limits:item.limits || {}}]
            : blockedPeriods.map(period => ({name:item.name,billingPeriod:period,price:"Contact Sales",features:item.features || [],limits:item.limits || {}}));
          return rows.map(row => ({...row,module:item.module,contactSales:true}));
        });
        return send(res, 200, {
          version: Math.max(1, Number(business?.version || playSnap.data()?.version || PLAN_CATALOG_VERSION)),
          plans: plansSnap.docs.map(d => safePlan(d.id, d.data())).filter(x => x.visible),
          products,
          publicContactSales,
          features: features.filter(x => x.visible),
          bindings,
          addOns,
          businessPricingConfigured: business !== null,
          businessPricing: business || null
        });
      }

      if (req.method === "GET" && path === "play/catalog") {
        const snap = await getFirestore().collection("playCatalog").doc("config").get();
        const sourceProducts = snap.exists && Array.isArray(snap.data()?.products) ? snap.data().products : canonicalPlayProducts();
        const products = sourceProducts.map(p => ({
          planId: String(p.planId || ""),
          productId: String(p.productId || ""),
          basePlanId: p.basePlanId ? String(p.basePlanId) : null,
          offerId: p.offerId ? String(p.offerId) : null,
          productType: String(p.productType || ""),
          enabled: p.enabled === true,
          consumable: p.consumable === true,
          trialOfferId: p.trialOfferId ? String(p.trialOfferId) : null,
          trialDays: Number(p.trialDays || 0),
          billingPeriod: String(p.billingPeriod || ""),
          priceUsd: p.priceUsd == null ? null : Number(p.priceUsd)
        })).filter(p => p.productId && !p.productId.includes("_") && /^[a-z0-9-]+$/.test(p.productId) && (p.productType === "subs" || p.productType === "inapp"));
        return send(res, 200, {
          version: snap.exists ? Number(snap.data()?.version || PLAN_CATALOG_VERSION) : PLAN_CATALOG_VERSION,
          products
        });
      }

      if (req.method === "POST" && path === "play/verify") {
        const productId = String(body.productId || "").trim();
        const purchaseToken = String(body.purchaseToken || "").trim();
        const result = await verifyPlayPurchase({
          uid: user.uid,
          productId,
          purchaseToken,
          obfuscatedAccountId: body.obfuscatedAccountId ? String(body.obfuscatedAccountId) : "",
          requestedBasePlanId: body.basePlanId ? String(body.basePlanId) : "",
          requestedOfferId: body.offerId ? String(body.offerId) : ""
        });
        return send(res, 200, result);
      }

      if (req.method === "GET" && path === "plans") {
        const snap = await getFirestore().collection("plans").where("active", "==", true).get();
        return send(res, 200, { plans: snap.docs.map(d => ({ id: d.id, ...d.data() })) });
      }

      if (req.method === "GET" && path === "admin/config") {
        await adminFromRequest(req);
        return send(res, 200, await getAdminControlConfig());
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