/**
 * Worker public models catalog (Prompt G).
 * GET /models/catalog — same public projection as website /api/public/models.
 */
import { SEED_MODELS, toPublicProjection } from "./ai-model-catalog.ts";
import { firestoreGet, firestoreQuery, firestoreSet } from "./firebase.js";
import { currentDailyFreeCredits, DEFAULT_FREE_CREDIT_TIME_ZONE } from "./credit-cycle.js";

async function listCatalogEntries() {
  let rows = [];
  try {
    rows = await firestoreQuery("aiModelCatalog", { limit: 100 });
  } catch {
    rows = [];
  }
  if (!rows.length) {
    for (const seed of SEED_MODELS) {
      try {
        await firestoreSet("aiModelCatalog/" + seed.modelKey, seed, true);
      } catch {}
    }
    return SEED_MODELS.slice();
  }
  return rows.map((row) => ({
    modelKey: String(row.modelKey || row.id || ""),
    providerModelId: String(row.providerModelId || ""),
    provider: String(row.provider || "openai"),
    displayName: String(row.displayName || ""),
    icon: String(row.icon || "star-spark"),
    description: String(row.description || ""),
    kind: row.kind || "chat",
    status: row.status === "active" ? "active" : "inactive",
    sortOrder: Number(row.sortOrder) || 99,
    allowFreeCredit: row.allowFreeCredit === true,
    requiresPurchasedCredits: row.requiresPurchasedCredits === true,
    userSelectable: row.userSelectable !== false,
    fallbackFor: Array.isArray(row.fallbackFor) ? row.fallbackFor : [],
    plans: Array.isArray(row.plans) ? row.plans : [],
    tags: Array.isArray(row.tags) ? row.tags : [],
    pricing: row.pricing || {
      inputCreditsPerToken: "0",
      cachedInputCreditsPerToken: "0",
      outputCreditsPerToken: "0",
    },
    version: Number(row.version) || 1,
    updatedAt: String(row.updatedAt || new Date().toISOString()),
  })).sort((a, b) => a.sortOrder - b.sortOrder);
}

export async function resolveModelCatalogEntry(modelKey) {
  const key = String(modelKey || "").trim().toLowerCase();
  if (!key) return null;
  const entries = await listCatalogEntries();
  return entries.find((entry) => String(entry.modelKey).toLowerCase() === key) || null;
}

export async function handleModelsCatalog(request, env, uid) {
  let balance = 0;
  let hasPurchasedCredits = false;
  if (uid) {
    try {
      const user = await firestoreGet("users/" + uid);
      if (user) {
        const permanent = Math.max(0, Number(user.credits ?? user.balance ?? 0) || 0);
        const expiry = user.subExpiresAt ? Date.parse(String(user.subExpiresAt)) : NaN;
        const subscription = Number.isFinite(expiry) && expiry > Date.now()
          ? Math.max(0, Number(user.subCredits ?? 0) || 0)
          : 0;
        const timeZone = String(user.dailyFreeCreditsTimeZone || env?.FREE_CREDIT_TIME_ZONE || DEFAULT_FREE_CREDIT_TIME_ZONE);
        const daily = Math.max(0, Number(currentDailyFreeCredits(user, new Date(), timeZone)) || 0);
        balance = permanent + subscription + daily;
        // Daily free allowance is spendable only by BQ-7-Star, never a
        // qualification for BQ-Codron. Paid subscription credits count.
        hasPurchasedCredits = permanent + subscription > 0;
      }
    } catch {}
  }
  const entries = await listCatalogEntries();
  const models = toPublicProjection(entries, { balance, hasPurchasedCredits });
  const defaultModelKey =
    models.find((m) => m.modelKey === "bq-7-star" && !m.locked)?.modelKey ||
    models.find((m) => !m.locked)?.modelKey ||
    "bq-7-star";
  return new Response(JSON.stringify({ models, defaultModelKey }), {
    headers: {
      "content-type": "application/json",
      "cache-control": "private, max-age=30",
    },
  });
}
