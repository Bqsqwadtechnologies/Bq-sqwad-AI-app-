/**
 * Single entry for index.js — returns a Response if path matched, else null.
 *
 * In cloudflare-worker/src/index.js:
 *   import { handleTeamsSqwadRoutes } from "./teams-routes-handler.js";
 * and near the top of the fetch route chain (after auth helpers are in scope):
 *   const teamsResp = await handleTeamsSqwadRoutes(url, request, env, { verifyFirebaseUser, json });
 *   if (teamsResp) return teamsResp;
 */
import {
  teamsCreate,
  teamsSearch,
  teamsListMine,
  teamsGet,
  teamsJoin,
  teamsLeave,
  teamsListMembers,
  teamsApproveMember,
  teamsListProjects,
  teamsListMessages,
  teamsPostMessage,
  teamsAskAi,
  teamsSeedWhite,
} from "./teams-sqwad-native.js";

export async function handleTeamsSqwadRoutes(url, request, env, deps) {
  const { verifyFirebaseUser, json } = deps;
  const path = url.pathname;
  const method = request.method;

  async function body() {
    return request.json().catch(() => ({}));
  }

  try {
    if (path === "/native/teams/create" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsCreate(u, await body()));
    }
    if (path === "/native/teams/search" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsSearch(u, await body()));
    }
    if (path === "/native/teams/mine" && method === "GET") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsListMine(u));
    }
    if (path === "/native/teams/get" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsGet(u, await body()));
    }
    if (path === "/native/teams/join" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsJoin(u, await body()));
    }
    if (path === "/native/teams/leave" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsLeave(u, await body()));
    }
    if (path === "/native/teams/members" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsListMembers(u, await body()));
    }
    if (path === "/native/teams/approve-member" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsApproveMember(u, await body()));
    }
    if (path === "/native/teams/projects" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsListProjects(u, await body()));
    }
    if (path === "/native/teams/messages" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsListMessages(u, await body()));
    }
    if (path === "/native/teams/post-message" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsPostMessage(u, await body()));
    }
    if (path === "/native/teams/ask-ai" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsAskAi(u, await body(), env));
    }
    if (path === "/native/teams/seed-white" && method === "POST") {
      const u = await verifyFirebaseUser(request);
      return json(await teamsSeedWhite(u));
    }
  } catch (e) {
    const status = e.httpStatus || 500;
    const message = e.message || "Team request failed";
    return json({ error: message }, status);
  }
  return null;
}
