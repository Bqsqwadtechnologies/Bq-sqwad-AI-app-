/** Prompt J Part 2 — presence pings and sharded usageDaily counters (no message content). */

export async function writePresence(env, dbWrite, uid, platform = "android") {
  const lastSeen = new Date().toISOString();
  await dbWrite(`presence/${uid}`, {
    platform: String(platform || "android"),
    lastSeen,
    signedIn: true,
    updatedAt: lastSeen,
  });
  return { lastSeen };
}

export function isActivePresence(lastSeenIso, now = Date.now()) {
  const t = Date.parse(String(lastSeenIso || ""));
  if (!Number.isFinite(t)) return false;
  return now - t <= 90_000;
}

export function dayKey(d = new Date()) {
  return d.toISOString().slice(0, 10).replace(/-/g, "");
}

/**
 * Increment feature counters in usageDaily/{yyyymmdd}/shards/{n}.
 * features: chat|vision|image|file|web_search|live_voice|live_camera|transcription|teams|connectors|contact_sales
 */
export async function recordUsageEvent(env, dbWrite, dbGet, {
  feature,
  uid,
  inputTokens = 0,
  cachedTokens = 0,
  outputTokens = 0,
  modelId = "",
  shardCount = 16,
}) {
  const day = dayKey();
  const shard = Math.abs(hash(uid || "anon")) % Math.max(1, shardCount);
  const path = `usageDaily/${day}/shards/${shard}`;
  const prev = (await dbGet(path).catch(() => null)) || {};
  const features = { ...(prev.features || {}) };
  const f = features[feature] || { count: 0, inputTokens: 0, cachedTokens: 0, outputTokens: 0, uids: {} };
  f.count += 1;
  f.inputTokens += Number(inputTokens) || 0;
  f.cachedTokens += Number(cachedTokens) || 0;
  f.outputTokens += Number(outputTokens) || 0;
  if (uid) f.uids[uid] = true;
  features[feature] = f;
  const models = { ...(prev.models || {}) };
  if (modelId) {
    const m = models[modelId] || { inputTokens: 0, outputTokens: 0, count: 0 };
    m.count += 1;
    m.inputTokens += Number(inputTokens) || 0;
    m.outputTokens += Number(outputTokens) || 0;
    models[modelId] = m;
  }
  await dbWrite(path, { features, models, updatedAt: new Date().toISOString() });
}

function hash(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return h;
}
