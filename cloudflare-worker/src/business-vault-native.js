/**
 * Prompt K — Conversation vault (server-only). Clients cannot read vault paths.
 * Stores redacted NDJSON pages; never stores OTP/BVN/NIN/PIN content.
 */

const SECRET_PATTERN = /\b(otp|bvn|nin|pin|password|card\s*number)\b/i;

export function redactForVault(text) {
  let t = String(text || "");
  t = t.replace(/\b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}\b/g, "[card]");
  t = t.replace(/\b\d{11}\b/g, "[id]");
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
export async function appendVaultMessage({
  dbSet,
  dbGet,
  businessId,
  agentId,
  threadId,
  role,
  text,
  caller = "worker",
}) {
  assertServerOnlyVaultAccess(caller);
  const redacted = redactForVault(text);
  const indexPath = `vaultIndex/${businessId}/${agentId}/${threadId}`;
  const index = (await dbGet(indexPath)) || { page: 1, prevHash: "", messageCount: 0 };
  const page = Number(index.page || 1);
  const payload = JSON.stringify({
    ts: new Date().toISOString(),
    role: String(role || "assistant"),
    text: redacted,
  });
  const hash = await appendHashChain(index.prevHash, payload);
  const path = vaultPagePath(businessId, agentId, threadId, page);
  const existing = (await dbGet(path)) || { lines: [] };
  const lines = Array.isArray(existing.lines) ? existing.lines.slice() : [];
  lines.push({ payload, hash });
  await dbSet(path, { lines, updatedAt: new Date().toISOString() }, true);
  const messageCount = Number(index.messageCount || 0) + 1;
  const nextPage = lines.length >= 200 ? page + 1 : page;
  await dbSet(indexPath, {
    page: nextPage,
    prevHash: hash,
    messageCount,
    updatedAt: new Date().toISOString(),
  }, true);
  return { path, hash, messageCount, page: nextPage };
}
