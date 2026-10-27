/**
 * Training-data pipeline (PREPARE ONLY).
 * Selects consented, text-only conversations; sanitizes PII; stores candidates
 * for admin review. Does NOT connect a fine-tuned model (feature flag off).
 */

import { firestoreGet, firestoreSet, firestoreCreate, firestoreQuery } from "./firebase.js";

const SANITIZER_VERSION = "2026-10-27.1";
const ACTIVE_CONSENT_VERSION = "2026-09";

const PII_PATTERNS = [
  /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi,
  /\b(?:\+?\d{1,3}[-.\s]?)?(?:\(?\d{2,4}\)?[-.\s]?)?\d{3,4}[-.\s]?\d{3,4}\b/g,
  /\b(?:sk|pk|api)[_-][A-Za-z0-9]{16,}\b/gi,
  /\b\d{13,19}\b/g
];

export function sanitizeText(text) {
  let out = String(text || "");
  for (const re of PII_PATTERNS) out = out.replace(re, "[redacted]");
  return out.replace(/\s{2,}/g, " ").trim();
}

async function hashRef(uid) {
  const data = new TextEncoder().encode("bq-train:" + String(uid || ""));
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, "0")).join("").slice(0, 32);
}

export async function buildTrainingCandidates({ limit = 50 } = {}) {
  const cfg = (await firestoreGet("config/agentTraining")) || {};
  if (cfg.enabled !== true) {
    return { ok: true, skipped: true, reason: "feature_flag_off" };
  }
  const consentRows = await firestoreQuery("userConsents", { limit: 200 }).catch(() => []);
  const consentedUids = new Set(
    consentRows
      .filter(r => r.trainingConsent === true && String(r.version || "") === ACTIVE_CONSENT_VERSION)
      .map(r => String(r.uid || String(r.__name || "").split("/").pop() || ""))
      .filter(Boolean)
  );
  let created = 0;
  const chats = await firestoreQuery("chats", { limit: Math.min(200, limit * 4) }).catch(() => []);
  for (const chat of chats) {
    if (created >= limit) break;
    const uid = String(chat.uid || chat.ownerUid || "");
    if (!uid || !consentedUids.has(uid)) continue;
    if (chat.privateChat === true || chat.private === true) continue;
    const messages = Array.isArray(chat.messages) ? chat.messages : [];
    if (messages.some(m => m.imageUrl || m.imageBase64 || (m.attachments && m.attachments.length))) continue;
    const cleaned = messages
      .filter(m => m && m.text && m.text !== "Generating image\u2026")
      .map(m => ({
        role: m.mine || m.role === "user" ? "user" : "assistant",
        content: sanitizeText(m.text)
      }))
      .filter(m => m.content.length > 8);
    if (cleaned.length < 2) continue;
    const id = crypto.randomUUID();
    const userRef = await hashRef(uid);
    await firestoreCreate("agentTrainingCandidates/" + id, {
      id,
      consentVersion: ACTIVE_CONSENT_VERSION,
      sanitizerVersion: SANITIZER_VERSION,
      createdAt: new Date().toISOString(),
      sourceChatHash: await hashRef(String(chat.id || chat.__name || id)),
      userRef,
      language: chat.language || "unknown",
      tokenEstimate: cleaned.reduce((n, m) => n + Math.ceil(m.content.length / 4), 0),
      reviewStatus: "pending",
      messages: [
        { role: "system", content: "You are BQ Sqwad AI, The AI That Grows With You." },
        ...cleaned
      ]
    });
    created++;
  }
  return { ok: true, created, sanitizerVersion: SANITIZER_VERSION, consentVersion: ACTIVE_CONSENT_VERSION };
}

export async function exportApprovedTrainingJsonl({ max = 500 } = {}) {
  const rows = await firestoreQuery("agentTrainingCandidates", { limit: 500 }).catch(() => []);
  const approved = rows.filter(r => r.reviewStatus === "approved").slice(0, max);
  const lines = approved.map(r => JSON.stringify({ messages: r.messages || [] }));
  const manifest = {
    count: approved.length,
    consentVersion: ACTIVE_CONSENT_VERSION,
    sanitizerVersion: SANITIZER_VERSION,
    exportedAt: new Date().toISOString(),
    trainSplit: Math.floor(approved.length * 0.9),
    validationSplit: approved.length - Math.floor(approved.length * 0.9)
  };
  return { jsonl: lines.join("\n"), manifest };
}

export async function listTrainingCandidates({ status = "pending", limit = 50 } = {}) {
  const rows = await firestoreQuery("agentTrainingCandidates", { limit: 200 }).catch(() => []);
  return {
    items: rows
      .filter(r => !status || r.reviewStatus === status)
      .slice(0, limit)
      .map(r => ({
        id: r.id || String(r.__name || "").split("/").pop(),
        reviewStatus: r.reviewStatus,
        consentVersion: r.consentVersion,
        tokenEstimate: r.tokenEstimate,
        createdAt: r.createdAt,
        language: r.language
      }))
  };
}

export async function setTrainingCandidateStatus(id, status, actorUid) {
  if (!["pending", "approved", "rejected"].includes(status)) {
    throw Object.assign(new Error("invalid_status"), { httpStatus: 400 });
  }
  const row = await firestoreGet("agentTrainingCandidates/" + id);
  if (!row) throw Object.assign(new Error("not_found"), { httpStatus: 404 });
  await firestoreSet("agentTrainingCandidates/" + id, {
    ...row,
    reviewStatus: status,
    reviewedBy: actorUid,
    reviewedAt: new Date().toISOString()
  }, true);
  return { ok: true, id, status };
}
