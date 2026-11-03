/**
 * Prompt K — Conversation vault (server-only). Clients cannot read vault paths.
 * Stores redacted NDJSON pages; never stores OTP/BVN/NIN/PIN content.
 */

const SECRET_PATTERN = /\b(otp|bvn|nin|pin|password|card\s*number)\b/i;

export function redactForVault(text) {
  let t = String(text || "");
  t = t.replace(/\b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}\b/g, "[card]");
  t = t.replace(/\b\d{11}\b/g, "[id]");
  t = t.replace(/\b(otp|bvn|nin|pin)\b\s*(?:is|:|=)?\s*[A-Za-z0-9-]{3,24}/gi, "$1: [redacted]");
  if (SECRET_PATTERN.test(t)) {
    t = t.replace(/\b\d{3,8}\b/g, "[redacted]");
  }
  return t;
}

export function vaultPagePath(businessId, agentId, threadId, page, now = new Date()) {
  const yyyy = now.getUTCFullYear();
  const mm = String(now.getUTCMonth() + 1).padStart(2, "0");
  return `vault/${businessId}/${agentId}/${yyyy}/${mm}/${threadId}/page-${String(page).padStart(4, "0")}.ndjson.gz`;
}

export function assertServerOnlyVaultAccess(caller) {
  if (caller !== "worker" && caller !== "website_admin") {
    const err = new Error("vault_client_forbidden");
    err.status = 403;
    throw err;
  }
}

export function isPlanActiveForVault(expiresAt, now = Date.now()) {
  if (!expiresAt) return false;
  const t = Date.parse(String(expiresAt));
  return Number.isFinite(t) && t > now;
}

/** Ingress gate: no AI reply when plan expired. */
export function businessIngressAllowed(entitlement, now = Date.now()) {
  if (!entitlement) return { allowed: false, reason: "no_entitlement" };
  const state = String(entitlement.state || entitlement.status || "");
  if (state !== "active" && state !== "trialing" && state !== "grace") {
    return { allowed: false, reason: "inactive" };
  }
  if (entitlement.expiresAt && Date.parse(entitlement.expiresAt) <= now) {
    return { allowed: false, reason: "expired" };
  }
  return { allowed: true };
}

export function storageMeterView(storage, planActive) {
  const quota = Number(storage?.planQuotaBytes || 0) + Number(storage?.addOnBytes || 0);
  const used = Number(storage?.usedBytes || 0);
  const pct = quota > 0 ? Math.min(100, (used / quota) * 100) : 0;
  const threshold = Number(storage?.addonOfferThresholdPct || 90);
  const showAddon = planActive && pct >= threshold;
  return {
    usedBytes: used,
    totalBytes: quota,
    percent: pct,
    showAddonOffer: showAddon,
    showRenewalOnly: !planActive,
  };
}

export async function appendHashChain(prevHash, payload) {
  const data = new TextEncoder().encode(String(prevHash || "") + "|" + payload);
  const dig = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(dig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Server-only vault append: redacts secrets, extends hash chain, records index. */
async function vaultAesKey(secret) {
  const material = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(String(secret || "")),
  );
  return crypto.subtle.importKey("raw", material, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

/** Server-only encrypted vault append. Only ciphertext is stored with metadata. */
export async function appendVaultMessage({
  dbSet,
  dbGet,
  encryptionKey,
  businessId,
  agentId,
  threadId,
  role,
  text,
  caller = "worker",
}) {
  assertServerOnlyVaultAccess(caller);
  if (!String(encryptionKey || "").trim()) {
    throw Object.assign(new Error("business_vault_encryption_not_configured"), {
      status: 503,
      safeError: "business_vault_not_configured",
    });
  }
  const redacted = redactForVault(text);
  const indexPath = "vaultIndex/" + businessId + "/" + agentId + "/" + threadId;
  const index = (await dbGet(indexPath)) || { page: 1, pageBytes: 0, prevHash: "", messageCount: 0 };
  let page = Number(index.page || 1);
  let pageBytes = Number(index.pageBytes || 0);
  const payload = JSON.stringify({
    ts: new Date().toISOString(),
    role: String(role || "assistant"),
    text: redacted,
  });
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await vaultAesKey(encryptionKey);
  const encrypted = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    new TextEncoder().encode(payload),
  );
  const ciphertext = btoa(String.fromCharCode(...new Uint8Array(encrypted)));
  if (pageBytes + ciphertext.length > 700_000) {
    page += 1;
    pageBytes = 0;
  }
  const hash = await appendHashChain(index.prevHash, ciphertext);
  const path = vaultPagePath(businessId, agentId, threadId, page);
  const pagePaths = Array.isArray(index.pagePaths) ? index.pagePaths.slice() : [];
  if (!pagePaths.includes(path)) pagePaths.push(path);
  const existing = (await dbGet(path)) || { records: [] };
  const records = Array.isArray(existing.records) ? existing.records.slice() : [];
  records.push({
    iv: btoa(String.fromCharCode(...iv)),
    ciphertext,
    hash,
  });
  await dbSet(path, { records, updatedAt: new Date().toISOString() }, true);
  const messageCount = Number(index.messageCount || 0) + 1;
  pageBytes += ciphertext.length;
  await dbSet(indexPath, {
    page,
    pageBytes,
    prevHash: hash,
    messageCount,
    pagePaths,
    updatedAt: new Date().toISOString(),
  }, true);
  return { path, hash, messageCount, page, pagePaths };
}


/** Super-admin export helper. Decrypts only server-side vault records for download. */
export async function exportBusinessVaultJsonl({ dbGet, dbQuery, encryptionKey, max = 500 }) {
  if (!String(encryptionKey || "").trim()) {
    throw Object.assign(new Error("business_vault_encryption_not_configured"), { status: 503 });
  }
  const key = await vaultAesKey(encryptionKey);
  const threads = await dbQuery("businessVaultThreadIndex", { limit: Math.min(500, Math.max(1, max)) }).catch(() => []);
  const lines = [];
  for (const thread of threads) {
    const businessId = String(thread.businessId || "");
    const agentId = String(thread.agentId || "");
    const threadId = String(thread.threadId || "");
    if (!businessId || !agentId || !threadId) continue;
    const indexPath = "vaultIndex/" + businessId + "/" + agentId + "/" + threadId;
    const index = await dbGet(indexPath);
    if (!index) continue;
    const paths = Array.isArray(index.pagePaths) && index.pagePaths.length
      ? index.pagePaths
      : [vaultPagePath(businessId, agentId, threadId, Number(index.page || 1))];
    for (const path of paths) {
      const page = await dbGet(String(path));
      for (const record of Array.isArray(page?.records) ? page.records : []) {
        try {
          const iv = Uint8Array.from(atob(String(record.iv || "")), c => c.charCodeAt(0));
          const ciphertext = Uint8Array.from(atob(String(record.ciphertext || "")), c => c.charCodeAt(0));
          const clear = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, ciphertext);
          const message = JSON.parse(new TextDecoder().decode(clear));
          lines.push(JSON.stringify({
            workspaceId: businessId,
            assistantId: agentId,
            conversationId: threadId,
            role: String(message.role || "assistant"),
            content: redactForVault(message.text || ""),
            createdAt: message.ts || null
          }));
        } catch {
          // Skip a corrupt/unreadable record; never fail an entire export on one row.
        }
      }
    }
  }
  return {
    jsonl: lines.join("\\n"),
    manifest: { count: lines.length, threadCount: threads.length, truncated: threads.length >= max, exportedAt: new Date().toISOString(), encryptedAtRest: true, piiRedacted: true }
  };
}
