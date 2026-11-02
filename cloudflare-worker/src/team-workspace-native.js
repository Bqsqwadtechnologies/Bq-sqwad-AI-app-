/**
 * Team / workspace membership, invites, and charge-account approval.
 * - Invite by email only if the user already has a Firebase account
 * - Roles: admin | moderator | member
 * - Only admin can invite, remove, request charge-account
 * - Charge-from requires the target member's explicit approval
 * - Notifications: in-app + email outbox
 */

import { firestoreGet, firestoreSet, firestoreCreate, firestoreQuery } from "./firebase.js";

const membershipIndexPath = (ownerUid, memberUid) => "teamMemberships/" + String(memberUid) + "_" + String(ownerUid);

async function writeMembershipIndex(ownerUid, member) {
  const uid = String(member?.uid || "");
  if (!ownerUid || !uid) return;
  await firestoreSet(membershipIndexPath(ownerUid, uid), {
    ownerUid: String(ownerUid), memberUid: uid,
    status: String(member.status || "active"),
    role: String(member.role || "member"),
    updatedAt: new Date().toISOString()
  }, true);
}

async function queryTeamPages(collection, options = {}, maxRows = 1000) {
  const rows = [];
  for (let offset = 0; offset < maxRows; offset += 100) {
    const page = await firestoreQuery(collection, { ...options, limit: 100, offset });
    rows.push(...page);
    if (page.length < 100) break;
  }
  return rows.slice(0, maxRows);
}

async function findMembershipTeams(uid) {
  return queryTeamPages("teamMemberships", {
    filters: [{ field: { fieldPath: "memberUid" }, op: "EQUAL", value: { stringValue: String(uid) } }]
  }, 1000).catch(() => []);
}

async function findUserByEmail(email) {
  const normalized = String(email || "").trim().toLowerCase();
  if (!normalized || !normalized.includes("@")) return null;
  const rows = await firestoreQuery("users", {
    filters: [{ field: { fieldPath: "email" }, op: "EQUAL", value: { stringValue: normalized } }],
    limit: 5
  }).catch(() => []);
  return rows.find(r => String(r.email || "").toLowerCase() === normalized) || null;
}

async function getTeamDoc(ownerUid) {
  return (await firestoreGet("teamWorkspaces/" + ownerUid)) || {
    ownerUid,
    members: {},
    chargeAccountUid: ownerUid,
    chargeApprovedBy: {},
    createdAt: new Date().toISOString()
  };
}

async function notifyUser(uid, title, body, kind = "team") {
  const id = crypto.randomUUID();
  await firestoreCreate("notifications/" + id, {
    id, uid, title, body, kind, read: false, createdAt: new Date().toISOString()
  }).catch(() => {});
  return id;
}

export async function teamMembers(user) {
  const team = await getTeamDoc(user.uid);
  let indexed = await findMembershipTeams(user.uid);
  // Migrate the signed-in user's legacy memberships once, then use the indexed
  // query for subsequent reads instead of scanning the first 50 workspaces.
  if (!indexed.length) {
    const legacy = await queryTeamPages("teamWorkspaces", {}, 500).catch(() => []);
    for (const row of legacy) {
      const member = row.members?.[user.uid];
      if (row.ownerUid === user.uid || member) {
        await writeMembershipIndex(String(row.ownerUid || row.id || ""), {
          uid: user.uid, status: member?.status || "active", role: member?.role || (row.ownerUid === user.uid ? "admin" : "member")
        });
      }
    }
    indexed = await findMembershipTeams(user.uid);
  }
  const ownerIds = indexed
    .filter(row => ["active", "invited"].includes(String(row.status || "active")))
    .map(row => String(row.ownerUid || ""));
  const relevant = await Promise.all(ownerIds.map(ownerUid => firestoreGet("teamWorkspaces/" + ownerUid)));
  const primary = relevant.filter(Boolean).find(row => row.ownerUid === user.uid) || team;
  const memberMap = primary.members || {};
  if (!memberMap[primary.ownerUid || user.uid]) {
    memberMap[primary.ownerUid || user.uid] = {
      uid: primary.ownerUid || user.uid,
      role: "admin",
      status: "active",
      email: user.email || "",
      displayName: user.name || user.email || "Owner"
    };
  }
  const members = Object.values(memberMap).map(m => ({
    uid: m.uid,
    email: m.email || "",
    displayName: m.displayName || m.email || m.uid,
    role: m.role || "member",
    status: m.status || "active",
    canBeCharged: !!(primary.chargeApprovedBy && primary.chargeApprovedBy[m.uid])
  }));
  const myRole = memberMap[user.uid]?.role || (primary.ownerUid === user.uid ? "admin" : "member");
  const pending = await firestoreQuery("teamChargeApprovals", {
    filters: [{ field: { fieldPath: "toUid" }, op: "EQUAL", value: { stringValue: user.uid } }],
    limit: 20
  }).catch(() => []);
  return {
    members,
    chargeAccountUid: primary.chargeAccountUid || primary.ownerUid || user.uid,
    myRole,
    pendingChargeApprovals: pending
      .filter(p => p.status === "pending")
      .map(p => ({
        id: p.id || String(p.__name || "").split("/").pop(),
        fromEmail: p.fromEmail || "",
        toEmail: p.toEmail || "",
        reason: p.reason || "Team usage billing",
        status: p.status
      }))
  };
}

export async function teamInvite(user, body) {
  const email = String(body?.email || "").trim().toLowerCase();
  const role = ["moderator", "member"].includes(String(body?.role || "").toLowerCase())
    ? String(body.role).toLowerCase() : "member";
  const team = await getTeamDoc(user.uid);
  const myRole = team.members?.[user.uid]?.role || (team.ownerUid === user.uid ? "admin" : "member");
  if (myRole !== "admin") throw Object.assign(new Error("Only admins can invite members"), { httpStatus: 403 });
  const target = await findUserByEmail(email);
  if (!target) throw Object.assign(new Error("No BQ Sqwad AI account exists for that email. They must sign up first."), { httpStatus: 404 });
  const targetUid = String(target.uid || String(target.__name || "").split("/").pop() || "");
  if (!targetUid) throw Object.assign(new Error("Account not found"), { httpStatus: 404 });
  if (targetUid === user.uid) throw Object.assign(new Error("You are already on this team"), { httpStatus: 400 });
  team.members = team.members || {};
  team.members[targetUid] = {
    uid: targetUid, email,
    displayName: target.displayName || target.name || email,
    role, status: "invited",
    invitedAt: new Date().toISOString(), invitedBy: user.uid
  };
  team.updatedAt = new Date().toISOString();
  await firestoreSet("teamWorkspaces/" + user.uid, { ...team, ownerUid: user.uid }, true);
  await writeMembershipIndex(user.uid, team.members[targetUid]);
  await notifyUser(targetUid, "Team invite",
    (user.email || "A BQ Sqwad AI user") + " invited you to their workspace as " + role + ". Open Settings → Team.", "team_invite");
  await firestoreCreate("emailOutbox/" + crypto.randomUUID(), {
    to: email, template: "team_invite",
    subject: "You were invited to a BQ Sqwad AI workspace",
    body: "You have been invited as " + role + ". Open the BQ Sqwad AI app to accept.",
    createdAt: new Date().toISOString(), status: "pending"
  }).catch(() => {});
  return { ok: true, uid: targetUid, email, role, status: "invited" };
}

export async function teamRemove(user, body) {
  const targetUid = String(body?.uid || "").trim();
  const team = await getTeamDoc(user.uid);
  const myRole = team.members?.[user.uid]?.role || (team.ownerUid === user.uid ? "admin" : "member");
  if (myRole !== "admin") throw Object.assign(new Error("Only admins can remove members"), { httpStatus: 403 });
  if (targetUid === user.uid || targetUid === team.ownerUid) {
    throw Object.assign(new Error("Cannot remove the workspace owner"), { httpStatus: 400 });
  }
  if (team.members) delete team.members[targetUid];
  await firestoreSet(membershipIndexPath(user.uid, targetUid), {
    ownerUid: user.uid, memberUid: targetUid, status: "removed", updatedAt: new Date().toISOString()
  }, true);
  if (team.chargeAccountUid === targetUid) team.chargeAccountUid = user.uid;
  if (team.chargeApprovedBy) delete team.chargeApprovedBy[targetUid];
  team.updatedAt = new Date().toISOString();
  await firestoreSet("teamWorkspaces/" + user.uid, team, true);
  await notifyUser(targetUid, "Removed from team", "You were removed from a BQ Sqwad AI workspace.", "team");
  return { ok: true };
}

export async function teamRequestChargeAccount(user, body) {
  const targetUid = String(body?.uid || "").trim();
  const team = await getTeamDoc(user.uid);
  const myRole = team.members?.[user.uid]?.role || (team.ownerUid === user.uid ? "admin" : "member");
  if (myRole !== "admin") throw Object.assign(new Error("Only admins can set the charge account"), { httpStatus: 403 });
  const member = team.members?.[targetUid];
  if (!member || member.status !== "active") {
    throw Object.assign(new Error("Member must be active before their credits can be used"), { httpStatus: 400 });
  }
  if (team.chargeApprovedBy?.[targetUid]) {
    team.chargeAccountUid = targetUid;
    team.updatedAt = new Date().toISOString();
    await firestoreSet("teamWorkspaces/" + user.uid, team, true);
    return { ok: true, chargeAccountUid: targetUid, alreadyApproved: true };
  }
  const id = crypto.randomUUID();
  await firestoreCreate("teamChargeApprovals/" + id, {
    id, teamOwnerUid: user.uid, fromUid: user.uid, fromEmail: user.email || "",
    toUid: targetUid, toEmail: member.email || "", reason: "Team usage billing",
    status: "pending", createdAt: new Date().toISOString()
  });
  await notifyUser(targetUid, "Credit charge approval",
    (user.email || "Team admin") + " wants to use your credits for team usage. Approve in Settings → Team.", "team_charge");
  await firestoreCreate("emailOutbox/" + crypto.randomUUID(), {
    to: member.email, template: "team_charge_approval",
    subject: "Approve team credit usage on BQ Sqwad AI",
    body: "A workspace admin requested to charge team usage to your balance. Open the app to approve or decline.",
    createdAt: new Date().toISOString(), status: "pending"
  }).catch(() => {});
  return { ok: true, approvalId: id, pending: true };
}

export async function teamApproveCharge(user, body) {
  const id = String(body?.id || "").trim();
  const approve = body?.approve === true;
  const row = await firestoreGet("teamChargeApprovals/" + id);
  if (!row || row.toUid !== user.uid) throw Object.assign(new Error("Approval not found"), { httpStatus: 404 });
  if (row.status !== "pending") throw Object.assign(new Error("Already resolved"), { httpStatus: 409 });
  await firestoreSet("teamChargeApprovals/" + id, {
    ...row, status: approve ? "approved" : "declined", resolvedAt: new Date().toISOString()
  }, true);
  if (approve) {
    const team = await getTeamDoc(row.teamOwnerUid);
    team.chargeApprovedBy = { ...(team.chargeApprovedBy || {}), [user.uid]: true };
    team.chargeAccountUid = user.uid;
    team.updatedAt = new Date().toISOString();
    await firestoreSet("teamWorkspaces/" + row.teamOwnerUid, team, true);
    await notifyUser(row.teamOwnerUid, "Charge account approved",
      (user.email || "Member") + " approved using their credits for the team.", "team_charge");
  }
  return { ok: true, approved: approve };
}

export async function teamAcceptInvite(user, body) {
  const ownerUid = String(body?.ownerUid || "").trim();
  const team = await getTeamDoc(ownerUid);
  const member = team.members?.[user.uid];
  if (!member || member.status !== "invited") {
    throw Object.assign(new Error("No pending invite"), { httpStatus: 404 });
  }
  team.members[user.uid] = { ...member, status: "active", acceptedAt: new Date().toISOString() };
  team.updatedAt = new Date().toISOString();
  await firestoreSet("teamWorkspaces/" + ownerUid, team, true);
  await writeMembershipIndex(ownerUid, team.members[user.uid]);
  await notifyUser(ownerUid, "Invite accepted", (user.email || "A user") + " joined your workspace.", "team");
  return { ok: true };
}
