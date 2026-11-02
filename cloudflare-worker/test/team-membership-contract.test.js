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

test("team workspace and Teams v2 enforce a 200-member ceiling", () => {
  assert.match(teamWorkspace, /Math\.max\(200/);
  assert.match(teamWorkspace, /team_seat_limit_reached/);
  assert.match(teamsSqwad, /activeSeats >= 200/);
  assert.match(teamsSqwad, /team_seat_limit_reached/);
});

test("Teams v2 my-teams path indexes owners and active members", () => {
  assert.match(teamsSqwad, /teamUserIndexes|teamMemberships|members\//);
  assert.match(teamsSqwad, /role:\s*\"owner\"/);
});

test("Worker presence ping route is mounted on index.js", () => {
  assert.match(indexJs, /\/presence\/ping/);
  assert.match(indexJs, /writePresence/);
});
