/**
 * POST /presence/ping body { platform }
 * Wire from index.js: if (path === "/presence/ping" && method === "POST") return handlePresencePing(...)
 */
import { writePresence } from "./presence-usage-native.js";

export async function handlePresencePing({ uid, platform, dbWrite }) {
  if (!uid) return { status: 401, body: { error: "Sign in required." } };
  const result = await writePresence(null, dbWrite, uid, platform || "android");
  return { status: 200, body: { ok: true, ...result } };
}
