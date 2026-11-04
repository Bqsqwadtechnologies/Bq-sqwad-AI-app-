import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

test("Team AI uses the canonical provider usage and token pricing APIs", async () => {
  const source = await readFile(new URL("../src/teams-sqwad-native.js", import.meta.url), "utf8");
  assert.match(source, /primaryProviderId: "openai"[\s\S]*?primaryModelId: "gpt-6-luna"/);
  assert.match(source, /normalizeProviderUsage\(result\.providerId \|\| "openai", result\.usage \|\| \{\}\)/);
  assert.match(source, /resolveTokenPricing\([\s\S]*?"chat"/);
  assert.match(source, /calculateTokenCost\(\{ usage, pricing \}\)/);
  assert.match(source, /actualModel !== "gpt-6-luna"/);
  assert.match(source, /memberMessagePriceCredits: memberMessagePrice/);
  assert.equal((source.match(/await debitTeamPoolForMember\(team, member, user\.uid, creditsCharged\)/g) || []).length, 1);
  assert.doesNotMatch(source, /resolveTokenPricing\(env,/);
});
