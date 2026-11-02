import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const teamWorkspace = readFileSync(join(root, "src/team-workspace-native.js"), "utf8");
const teamsSqwad = readFileSync(join(root, "src/teams-sqwad-native.js"), "utf8");
const indexJs = readFileSync(join(root, "src/index.js"), "utf8");

test("team workspace membership uses teamMemberships index path", () => {
  assert.match(teamWorkspace, /teamMemberships\//);
  assert.match(teamWorkspace, /memberUid/);
  assert.match(teamWorkspace, /writeMembershipIndex/);
  assert.match(teamWorkspace, /queryTeamPages/);
});

test("team workspace pages membership reads instead of a single 50-row scan", () => {
  assert.doesNotMatch(teamWorkspace, /limit:\s*50/);
  assert.match(teamWorkspace, /limit:\s*100/);
  assert.match(teamWorkspace, /offset/);
});

test("teams have no hard-coded member ceiling and new teams start with their owner", () => {
  assert.doesNotMatch(teamWorkspace, /team_seat_limit_reached|activeSeats\s*>=\s*\d+/);
  assert.doesNotMatch(teamsSqwad, /team_seat_limit_reached|activeSeats\s*>=\s*\d+/);
  assert.match(teamsSqwad, /memberCount:\s*1/);
  assert.match(teamsSqwad, /role:\s*"owner"/);
  assert.match(teamsSqwad, /userTeams\/.*items/);
});

test("member-to-member team messages use the sender's personal credit reservation", () => {
  assert.match(teamsSqwad, /billing\.reserve\(user, price\)/);
  assert.match(teamsSqwad, /billing\.commit\(user\.uid, reservation\.reservationId\)/);
  assert.match(indexJs, /team_member_message/);
  assert.match(indexJs, /reverse: async \(uid, reservationId\) => reverseCredits/);
});

test("Teams v2 my-teams path indexes owners and active members", () => {
  assert.match(teamsSqwad, /teamUserIndexes|teamMemberships|members\//);
  assert.match(teamsSqwad, /role:\s*\"owner\"/);
});

test("Worker presence ping route is mounted on index.js", () => {
  assert.match(indexJs, /\/presence\/ping/);
  assert.match(indexJs, /writePresence/);
});


test("public join, approval and leave use compare-and-set membership transitions", () => {
  assert.match(teamsSqwad, /async function firestoreUpdateAtomic\(/);
  assert.match(teamsSqwad, /updateTime:\s*current\.__updateTime/);
  assert.match(teamsSqwad, /const activated = await firestoreUpdateAtomic/);
  assert.match(teamsSqwad, /const deactivated = await firestoreUpdateAtomic/);
  assert.match(teamsSqwad, /status:\s*"left"/);
  assert.match(teamsSqwad, /memberCount:\s*num\(current\.memberCount\)\s*\+\s*1/);
  assert.match(teamsSqwad, /Math\.max\(0,\s*num\(current\.memberCount\)\s*-\s*1\)/);
});
