const DEFAULT_REALTIME_MODEL = "gpt-realtime-2.1-mini";
// RESTORE_IN_PROGRESS — full file is in local artifacts/index.js.restore
// Temporary minimal bootstrap so Worker is not empty.
import { handleTeamsSqwadRoutes } from "./teams-routes-handler.js";
import { configureFirebaseEnv, verifyFirebaseUser } from "./firebase.js";

function corsHeaders(request) {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
  };
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders(), "content-type": "application/json; charset=utf-8" },
  });
}

export default {
  async fetch(request, env) {
    try {
      configureFirebaseEnv(env);
    } catch (_) {}
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders(request) });
    }
    const url = new URL(request.url);
    // Shared Teams (website parity)
    try {
      const teamsResp = await handleTeamsSqwadRoutes(url, request, env, { verifyFirebaseUser, json });
      if (teamsResp) return teamsResp;
    } catch (e) {
      return json({ error: e.message || "team error" }, e.httpStatus || 500);
    }
    // Temporary notice while full index is restored
    if (url.pathname === "/" || url.pathname === "/health") {
      return json({ ok: true, notice: "partial worker bootstrap — full routes restore required" });
    }
    return json({ error: "Route not available in bootstrap; full index restore required", path: url.pathname }, 503);
  },
};
