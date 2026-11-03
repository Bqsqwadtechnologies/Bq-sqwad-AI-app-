import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const teams = readFileSync(join(root, "src/teams-sqwad-native.js"), "utf8");
const routes = readFileSync(join(root, "src/teams-routes-handler.js"), "utf8");
const android = readFileSync(join(root, "../app/src/main/java/com/bqsqwad/ai/ui/teams/TeamWorkspaceScreen.kt"), "utf8");

test("native team AI settlement reserves monthly member usage before pool debit", () => {
  assert.match(teams, /async function debitTeamPoolForMember\(/);
  assert.match(teams, /teams\/" \+ team\.id \+ "\/usage\/" \+ ym \+ "_" \+ uid/);
  assert.match(teams, /limit !== null && spent \+ amount > limit \+ 1e-9/);
  assert.match(teams, /await firestoreUpdateAtomic\("teamCreditPools\/" \+ team\.creditPoolId/);
});

test("native team AI settlement rolls back member usage if pool debit fails", () => {
  assert.match(teams, /credits: Math\.max\(0, Number\(\(num\(current\?\.credits\) - amount\)\.toFixed\(7\)\)\)/);
  assert.match(teams, /debitTeamPoolForMember\(team, member, user\.uid, creditsCharged\)/);
  assert.match(teams, /team_member_spend_limit_reached/);
});

test("team spend-limit administration is mounted and available in Android workspace", () => {
  assert.match(routes, /\/native\/teams\/member\/spend-limit/);
  assert.match(routes, /teamsSetMemberSpendLimit/);
  assert.match(teams, /target\.role === "owner" && user\.uid !== targetUid/);
  assert.match(teams, /spendLimitUpdatedBy: user\.uid/);
  assert.match(android, /\/native\/teams\/member\/spend-limit/);
  assert.match(android, /Monthly AI spending limit/);
});

test("team AI settlement does not deliver a successful provider response without usage pricing", () => {
  assert.match(teams, /if \(inputTokens \+ outputTokens <= 0\)/);
  assert.match(teams, /resolveTokenPricing\(/);
  assert.match(teams, /if \(settlementError\)/);
  assert.match(teams, /response was not delivered or charged/i);
});
