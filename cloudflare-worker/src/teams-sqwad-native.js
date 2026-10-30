/**
 * BQ Sqwad AI — Shared Teams (website parity)
 *
 * Data model matches website + docs/APP_CONTRACT_TEAMS.md:
 *   teams/{teamId}, teamUsernames/{username}, teams/{id}/members/{uid},
 *   teams/{id}/projects/{pid}/messages/{mid}, teamCreditPools/{id}
 *
 * Rules:
 * - Member ↔ member messages: no model call, no credits
 * - Ask AI / @ai only: model runs, debits team credit pool only
 * - Personal free credits are never used for team AI
 * - AI prompt includes member roster so it can address people by name
 */

import {
  firestoreGet,
  firestoreSet,
  firestoreCreate,
  firestoreQuery,
  firestoreDelete,
} from "./firebase.js";

const RECOMMENDED_USERNAME = "white";
const RESERVED = new Set(["admin", "api", "www", "support", "help", "null", "undefined", "bq", "bqsqwad"]);

function now() {
  return new Date().toISOString();
}

function normalizeUsername(raw) {
  return String(raw || "")
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9_]/g, "")
    .replace(/^_+|_+$/g, "")
    .slice(0, 32);
}

function str(v, f = "") {
  return v === undefined || v === null ? f : String(v);
}

function num(v, f = 0) {
  return Number.isFinite(Number(v)) ? Number(v) : f;
}

async function getTeam(teamId) {
  const doc = await firestoreGet("teams/" + teamId);
  if (!doc) return null;
  return {
    id: teamId,
    name: str(doc.name),
    username: str(doc.username),
    logoUrl: doc.logoUrl || null,
    visibility: str(doc.visibility, "private") === "public" ? "public" : "private",
    ownerUid: str(doc.ownerUid),
    creditPoolId: str(doc.creditPoolId, teamId),
    memberCount: num(doc.memberCount),
    projectCount: num(doc.projectCount),
    description: str(doc.description),
    createdAt: str(doc.createdAt),
    updatedAt: str(doc.updatedAt),
  };
}

async function getTeamByUsername(username) {
  const handle = normalizeUsername(username);
  if (!handle) return null;
  const map = await firestoreGet("teamUsernames/" + handle);
  if (!map?.teamId) return null;
  return getTeam(str(map.teamId));
}

async function getMember(teamId, uid) {
  const doc = await firestoreGet("teams/" + teamId + "/members/" + uid);
  if (!doc) return null;
  return {
    uid,
    teamId,
    role: str(doc.role, "member"),
    displayName: str(doc.displayName, "Member"),
    photoUrl: doc.photoUrl || null,
    email: doc.email ? str(doc.email).toLowerCase() : null,
    status: str(doc.status, "active"),
    tags: Array.isArray(doc.tags) ? doc.tags.map(String) : [],
    joinedAt: str(doc.joinedAt),
    spendLimitCredits: doc.spendLimitCredits == null ? null : num(doc.spendLimitCredits),
  };
}

async function requireActiveMember(user, teamId, minRole = "viewer") {
  const team = await getTeam(teamId);
  if (!team) {
    const err = new Error("That team does not exist.");
    err.httpStatus = 404;
    throw err;
  }
  const member = await getMember(teamId, user.uid);
  if (!member || member.status !== "active") {
    const err = new Error("You are not an active member of this team.");
    err.httpStatus = 403;
    throw err;
  }
  const rank = { viewer: 1, member: 2, admin: 3, owner: 4 };
  if ((rank[member.role] || 0) < (rank[minRole] || 0)) {
    const err = new Error("Your role in this team does not allow that action.");
    err.httpStatus = 403;
    throw err;
  }
  return { team, member };
}

async function getPoolBalance(creditPoolId) {
  const pool = await firestoreGet("teamCreditPools/" + creditPoolId);
  return num(pool?.balance);
}

/* -------------------------------------------------------------------------- */
/* Create                                                                      */
/* -------------------------------------------------------------------------- */

export async function teamsCreate(user, body) {
  const name = str(body?.name).trim().slice(0, 80);
  if (name.length < 2) {
    const err = new Error("Team name must be at least 2 characters.");
    err.httpStatus = 400;
    throw err;
  }
  const username = normalizeUsername(body?.username);
  if (username.length < 3) {
    const err = new Error("Username must be at least 3 characters.");
    err.httpStatus = 400;
    throw err;
  }
  if (RESERVED.has(username)) {
    const err = new Error("That username is reserved.");
    err.httpStatus = 400;
    throw err;
  }

  const existing = await firestoreGet("teamUsernames/" + username);
  if (existing?.teamId) {
    const err = new Error("That username is already taken.");
    err.httpStatus = 409;
    throw err;
  }

  const teamId = crypto.randomUUID().replace(/-/g, "").slice(0, 20);
  const ts = now();
  const visibility = body?.visibility === "public" ? "public" : "private";

  // Claim username first
  await firestoreSet(
    "teamUsernames/" + username,
    { teamId, claimedAt: ts, claimedBy: user.uid },
    false,
  );

  const teamRecord = {
    name,
    username,
    logoUrl: body?.logoUrl || "",
    visibility,
    ownerUid: user.uid,
    creditPoolId: teamId,
    memberCount: 1,
    projectCount: 1,
    description: str(body?.description).slice(0, 500),
    createdAt: ts,
    updatedAt: ts,
  };
  await firestoreSet("teams/" + teamId, teamRecord, false);

  await firestoreSet(
    "teams/" + teamId + "/members/" + user.uid,
    {
      role: "owner",
      displayName: user.name || user.email || "Owner",
      photoUrl: user.picture || "",
      email: (user.email || "").toLowerCase(),
      status: "active",
      tags: [],
      joinedAt: ts,
      spendLimitCredits: null,
    },
    false,
  );

  await firestoreSet(
    "teamCreditPools/" + teamId,
    {
      teamId,
      balance: 0,
      lifetimeGranted: 0,
      lifetimeSpent: 0,
      updatedAt: ts,
    },
    false,
  );

  const projectId = crypto.randomUUID().replace(/-/g, "").slice(0, 16);
  await firestoreSet(
    "teams/" + teamId + "/projects/" + projectId,
    {
      name: "General",
      description: "Main team chat",
      createdBy: user.uid,
      lastMessageAt: "",
      archived: false,
      createdAt: ts,
    },
    false,
  );

  return { team: { id: teamId, ...teamRecord } };
}

/* -------------------------------------------------------------------------- */
/* Search / list / get                                                         */
/* -------------------------------------------------------------------------- */

export async function teamsSearch(user, body) {
  const q = str(body?.query).trim().toLowerCase();

  if (q) {
    const exact = await getTeamByUsername(q);
    if (exact) {
      return {
        teams: [
          {
            id: exact.id,
            name: exact.name,
            username: exact.username,
            logoUrl: exact.logoUrl,
            visibility: exact.visibility,
            memberCount: exact.memberCount,
            projectCount: exact.projectCount,
            description: exact.description,
          },
        ],
      };
    }
  }

  // Public teams only for list / partial search
  const rows = await firestoreQuery("teams", {
    filters: [
      {
        field: { fieldPath: "visibility" },
        op: "EQUAL",
        value: { stringValue: "public" },
      },
    ],
    limit: 80,
  }).catch(() => []);

  let list = rows.map((r) => ({
    id: str(r.id || String(r.__name || "").split("/").pop()),
    name: str(r.name),
    username: str(r.username),
    logoUrl: r.logoUrl || null,
    visibility: "public",
    memberCount: num(r.memberCount),
    projectCount: num(r.projectCount),
    description: str(r.description),
  }));

  if (q) {
    list = list.filter(
      (t) =>
        t.name.toLowerCase().includes(q) || t.username.toLowerCase().includes(q),
    );
  }

  // Recommend White first
  list.sort((a, b) => {
    if (a.username === RECOMMENDED_USERNAME) return -1;
    if (b.username === RECOMMENDED_USERNAME) return 1;
    return a.name.localeCompare(b.name);
  });

  return { teams: list.slice(0, 40) };
}

export async function teamsListMine(user) {
  // Lightweight: scan memberships via query on a member index if present;
  // fallback scans recent teams the user owns or is in via known pattern.
  // For scale, website maintains userTeams/{uid}/items/{teamId}.
  const index = await firestoreQuery("userTeams/" + user.uid + "/items", {
    limit: 50,
  }).catch(() => []);

  if (index.length) {
    const teams = [];
    for (const row of index) {
      const teamId = str(row.teamId || row.id);
      const team = await getTeam(teamId);
      if (!team) continue;
      const member = await getMember(teamId, user.uid);
      teams.push({
        id: team.id,
        name: team.name,
        username: team.username,
        logoUrl: team.logoUrl,
        visibility: team.visibility,
        memberCount: team.memberCount,
        projectCount: team.projectCount,
        description: team.description,
        myRole: member?.role || "member",
        myStatus: member?.status || "active",
      });
    }
    return { teams };
  }

  // Fallback: teams owned by user
  const owned = await firestoreQuery("teams", {
    filters: [
      {
        field: { fieldPath: "ownerUid" },
        op: "EQUAL",
        value: { stringValue: user.uid },
      },
    ],
    limit: 30,
  }).catch(() => []);

  const teams = owned.map((r) => ({
    id: str(r.id || String(r.__name || "").split("/").pop()),
    name: str(r.name),
    username: str(r.username),
    logoUrl: r.logoUrl || null,
    visibility: str(r.visibility, "private"),
    memberCount: num(r.memberCount),
    projectCount: num(r.projectCount),
    description: str(r.description),
    myRole: "owner",
    myStatus: "active",
  }));
  return { teams };
}

export async function teamsGet(user, body) {
  let team = null;
  if (body?.teamId) team = await getTeam(str(body.teamId));
  else if (body?.username) team = await getTeamByUsername(str(body.username));
  if (!team) {
    const err = new Error("Team not found.");
    err.httpStatus = 404;
    throw err;
  }
  const member = await getMember(team.id, user.uid);
  const balance = await getPoolBalance(team.creditPoolId);
  return { team, member, balance };
}

/* -------------------------------------------------------------------------- */
/* Join / leave / members                                                      */
/* -------------------------------------------------------------------------- */

export async function teamsJoin(user, body) {
  const teamId = str(body?.teamId);
  const team = await getTeam(teamId);
  if (!team) {
    const err = new Error("That team does not exist.");
    err.httpStatus = 404;
    throw err;
  }

  const existing = await getMember(teamId, user.uid);
  if (existing?.status === "active") {
    return { team, member: existing, pending: false };
  }

  const ts = now();
  const displayName = user.name || user.email || "Member";
  const photoUrl = user.picture || "";
  const email = (user.email || "").toLowerCase();

  if (team.visibility === "public") {
    await firestoreSet(
      "teams/" + teamId + "/members/" + user.uid,
      {
        role: "member",
        displayName,
        photoUrl,
        email,
        status: "active",
        tags: [],
        joinedAt: ts,
        spendLimitCredits: null,
      },
      false,
    );
    await firestoreSet(
      "teams/" + teamId,
      { memberCount: team.memberCount + 1, updatedAt: ts },
      true,
    );
    // Index for list-mine
    await firestoreSet(
      "userTeams/" + user.uid + "/items/" + teamId,
      { teamId, joinedAt: ts },
      false,
    ).catch(() => {});
    const member = await getMember(teamId, user.uid);
    return { team, member, pending: false };
  }

  // Private → pending
  await firestoreSet(
    "teams/" + teamId + "/members/" + user.uid,
    {
      role: "member",
      displayName,
      photoUrl,
      email,
      status: "pending",
      tags: [],
      joinedAt: ts,
      spendLimitCredits: null,
    },
    false,
  );
  const member = await getMember(teamId, user.uid);
  return { team, member, pending: true };
}

export async function teamsLeave(user, body) {
  const teamId = str(body?.teamId);
  const { team, member } = await requireActiveMember(user, teamId, "member");
  if (member.role === "owner") {
    const err = new Error("Owner cannot leave. Transfer ownership first.");
    err.httpStatus = 400;
    throw err;
  }
  await firestoreDelete("teams/" + teamId + "/members/" + user.uid).catch(() => {});
  await firestoreSet(
    "teams/" + teamId,
    { memberCount: Math.max(0, team.memberCount - 1), updatedAt: now() },
    true,
  );
  await firestoreDelete("userTeams/" + user.uid + "/items/" + teamId).catch(() => {});
  return { ok: true };
}

export async function teamsListMembers(user, body) {
  const teamId = str(body?.teamId);
  await requireActiveMember(user, teamId, "viewer");
  const rows = await firestoreQuery("teams/" + teamId + "/members", {
    limit: 200,
  }).catch(() => []);
  const members = rows
    .map((r) => ({
      uid: str(r.id || String(r.__name || "").split("/").pop()),
      teamId,
      role: str(r.role, "member"),
      displayName: str(r.displayName, "Member"),
      photoUrl: r.photoUrl || null,
      email: r.email ? str(r.email).toLowerCase() : null,
      status: str(r.status, "active"),
      tags: Array.isArray(r.tags) ? r.tags.map(String) : [],
      joinedAt: str(r.joinedAt),
    }))
    .filter((m) => m.status === "active" || m.status === "pending")
    .sort((a, b) => a.displayName.localeCompare(b.displayName));
  return { members };
}

export async function teamsApproveMember(user, body) {
  const teamId = str(body?.teamId);
  const targetUid = str(body?.uid);
  await requireActiveMember(user, teamId, "admin");
  const target = await getMember(teamId, targetUid);
  if (!target || target.status !== "pending") {
    const err = new Error("No pending join request for that member.");
    err.httpStatus = 404;
    throw err;
  }
  const team = await getTeam(teamId);
  const ts = now();
  await firestoreSet(
    "teams/" + teamId + "/members/" + targetUid,
    { status: "active", joinedAt: ts },
    true,
  );
  await firestoreSet(
    "teams/" + teamId,
    { memberCount: (team?.memberCount || 0) + 1, updatedAt: ts },
    true,
  );
  await firestoreSet(
    "userTeams/" + targetUid + "/items/" + teamId,
    { teamId, joinedAt: ts },
    false,
  ).catch(() => {});
  return { ok: true };
}

/* -------------------------------------------------------------------------- */
/* Projects + messages                                                         */
/* -------------------------------------------------------------------------- */

export async function teamsListProjects(user, body) {
  const teamId = str(body?.teamId);
  await requireActiveMember(user, teamId, "viewer");
  const rows = await firestoreQuery("teams/" + teamId + "/projects", {
    limit: 50,
  }).catch(() => []);
  const projects = rows
    .map((r) => ({
      id: str(r.id || String(r.__name || "").split("/").pop()),
      teamId,
      name: str(r.name),
      description: str(r.description),
      createdBy: str(r.createdBy),
      lastMessageAt: r.lastMessageAt || null,
      archived: !!r.archived,
      createdAt: str(r.createdAt),
    }))
    .filter((p) => !p.archived)
    .sort((a, b) =>
      (b.lastMessageAt || b.createdAt).localeCompare(a.lastMessageAt || a.createdAt),
    );
  return { projects };
}

export async function teamsListMessages(user, body) {
  const teamId = str(body?.teamId);
  const projectId = str(body?.projectId);
  await requireActiveMember(user, teamId, "viewer");
  const limit = Math.min(num(body?.limit, 30), 100);
  const rows = await firestoreQuery(
    "teams/" + teamId + "/projects/" + projectId + "/messages",
    { limit: limit * 2 },
  ).catch(() => []);
  const messages = rows
    .map((r) => ({
      id: str(r.id || String(r.__name || "").split("/").pop()),
      teamId,
      projectId,
      authorUid: str(r.authorUid),
      authorName: str(r.authorName),
      text: str(r.text),
      attachments: Array.isArray(r.attachments) ? r.attachments : [],
      createdAt: str(r.createdAt),
      tokenUsage: r.tokenUsage || undefined,
      credits: r.credits !== undefined ? num(r.credits) : undefined,
    }))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .slice(0, limit)
    .reverse();
  return { messages };
}

/** Member message only — no AI, no credits. */
export async function teamsPostMessage(user, body) {
  const teamId = str(body?.teamId);
  const projectId = str(body?.projectId);
  const text = str(body?.text).slice(0, 20000);
  if (!text.trim()) {
    const err = new Error("Message cannot be empty.");
    err.httpStatus = 400;
    throw err;
  }
  const { member } = await requireActiveMember(user, teamId, "member");
  const id = crypto.randomUUID().replace(/-/g, "").slice(0, 16);
  const ts = now();
  const record = {
    authorUid: user.uid,
    authorName: member.displayName,
    text,
    attachments: Array.isArray(body?.attachments) ? body.attachments : [],
    createdAt: ts,
  };
  await firestoreSet(
    "teams/" + teamId + "/projects/" + projectId + "/messages/" + id,
    record,
    false,
  );
  await firestoreSet(
    "teams/" + teamId + "/projects/" + projectId,
    { lastMessageAt: ts },
    true,
  );
  return {
    kind: "member",
    message: { id, teamId, projectId, ...record },
  };
}

/**
 * Explicit AI turn. Only call when user presses Ask AI or message invokes AI.
 * Debits team credit pool only. Returns no-credit message when pool is empty.
 */
export async function teamsAskAi(user, body, env) {
  const teamId = str(body?.teamId);
  const projectId = str(body?.projectId);
  const text = str(body?.text).slice(0, 20000);
  if (!text.trim()) {
    const err = new Error("Message cannot be empty.");
    err.httpStatus = 400;
    throw err;
  }

  const { team, member } = await requireActiveMember(user, teamId, "member");
  const balance = await getPoolBalance(team.creditPoolId);

  // Always store the user message first
  const userMsgId = crypto.randomUUID().replace(/-/g, "").slice(0, 16);
  const ts = now();
  await firestoreSet(
    "teams/" + teamId + "/projects/" + projectId + "/messages/" + userMsgId,
    {
      authorUid: user.uid,
      authorName: member.displayName,
      text,
      attachments: [],
      createdAt: ts,
    },
    false,
  );

  if (balance <= 0) {
    const msg =
      "This team has no credits. Ask an admin to add credits, or continue in your personal chat.";
    const aiId = crypto.randomUUID().replace(/-/g, "").slice(0, 16);
    await firestoreSet(
      "teams/" + teamId + "/projects/" + projectId + "/messages/" + aiId,
      {
        authorUid: "ai",
        authorName: "BQ Sqwad AI",
        text: msg,
        attachments: [],
        createdAt: now(),
        credits: 0,
      },
      false,
    );
    return {
      kind: "ai",
      creditsCharged: 0,
      message: msg,
      noCredits: true,
    };
  }

  // Build roster + recent history for the model
  const membersRes = await teamsListMembers(user, { teamId });
  const roster = (membersRes.members || [])
    .filter((m) => m.status === "active")
    .map((m) => m.displayName + " (" + m.role + ")")
    .join(", ");

  const recentRes = await teamsListMessages(user, {
    teamId,
    projectId,
    limit: 20,
  });
  const history = (recentRes.messages || [])
    .map((m) => m.authorName + ": " + String(m.text).slice(0, 600))
    .join("\n");

  const system = [
    'You are BQ Sqwad AI in the team workspace "' + team.name + '".',
    "Active members: " + (roster || "none listed") + ".",
    "Address people by name when relevant. Help the group plan and produce work.",
    "Only reply when a member has asked you.",
    "Current speaker: " + member.displayName + ".",
  ].join("\n");

  // Call provider via existing runProvider when available
  let replyText = "";
  let inputTokens = 0;
  let outputTokens = 0;
  try {
    const { runProvider } = await import("./providers.js");
    const result = await runProvider(env, {
      feature: "team_chat",
      messages: [
        { role: "system", content: system },
        {
          role: "user",
          content:
            "Recent team chat:\n" +
            history +
            "\n\n" +
            member.displayName +
            " asks the AI:\n" +
            text,
        },
      ],
    });
    replyText = str(result?.text || result?.content || "");
    inputTokens = num(result?.usage?.inputTokens || result?.usage?.prompt_tokens);
    outputTokens = num(result?.usage?.outputTokens || result?.usage?.completion_tokens);
  } catch (e) {
    replyText =
      "I could not complete that request right now. Please try again in a moment.";
  }

  // Settle credits from team pool using shared token pricing when available
  let creditsCharged = 0;
  try {
    const { calculateTokenCost, resolveTokenPricing } = await import(
      "./token-billing-native.js"
    );
    const pricing = await resolveTokenPricing(env, {
      feature: "team_chat",
      model: "gpt-6-luna",
    });
    if (pricing && (inputTokens > 0 || outputTokens > 0)) {
      const cost = calculateTokenCost({
        inputTokens,
        outputTokens,
        cachedInputTokens: 0,
        rates: pricing,
      });
      creditsCharged = num(cost?.credits);
    }
  } catch {
    creditsCharged = 0;
  }

  if (creditsCharged > 0) {
    const pool = await firestoreGet("teamCreditPools/" + team.creditPoolId);
    const current = num(pool?.balance);
    const next = Math.max(0, current - creditsCharged);
    await firestoreSet(
      "teamCreditPools/" + team.creditPoolId,
      {
        ...(pool || {}),
        balance: next,
        lifetimeSpent: num(pool?.lifetimeSpent) + creditsCharged,
        updatedAt: now(),
      },
      true,
    );
  }

  const aiId = crypto.randomUUID().replace(/-/g, "").slice(0, 16);
  await firestoreSet(
    "teams/" + teamId + "/projects/" + projectId + "/messages/" + aiId,
    {
      authorUid: "ai",
      authorName: "BQ Sqwad AI",
      text: replyText,
      attachments: [],
      createdAt: now(),
      tokenUsage: { inputTokens, cachedInputTokens: 0, outputTokens },
      credits: creditsCharged,
    },
    false,
  );
  await firestoreSet(
    "teams/" + teamId + "/projects/" + projectId,
    { lastMessageAt: now() },
    true,
  );

  return {
    kind: "ai",
    creditsCharged,
    message: replyText,
    noCredits: false,
  };
}

export async function teamsSeedWhite(user) {
  // Admin-only helper: create the recommended public White team if missing
  if (!user.isAdmin && !(user.email || "").toLowerCase().includes("admin")) {
    // Soft check — website uses ADMIN_EMAILS; Worker may set isAdmin on verify
    const err = new Error("Admin only.");
    err.httpStatus = 403;
    throw err;
  }
  const existing = await getTeamByUsername(RECOMMENDED_USERNAME);
  if (existing) return { team: existing, created: false };
  return teamsCreate(user, {
    name: "White",
    username: RECOMMENDED_USERNAME,
    visibility: "public",
    description: "The default community team. Plan and build together with AI.",
  }).then((r) => ({ ...r, created: true }));
}
