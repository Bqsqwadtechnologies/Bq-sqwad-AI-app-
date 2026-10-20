import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { computeFixedCredits, computeMinuteCredits, computeTokenCredits } from "../src/billing-core.js";

const vectors = JSON.parse(await readFile(new URL("./billing-test-vectors.json", import.meta.url), "utf8"));
const policy = { markupPercent: vectors.defaults.markupPercent, feePercent: vectors.defaults.feePercent, usdPerCredit: vectors.defaults.usdPerCredit };
const rates = { inputUsdPerToken: vectors.defaults.openAiInputUsdPerToken, cachedInputUsdPerToken: vectors.defaults.openAiCachedInputUsdPerToken, outputUsdPerToken: vectors.defaults.openAiOutputUsdPerToken };

test("shared billing vectors", () => {
  for (const v of vectors.cases) {
    let actual;
    if (v.kind === "tokens") actual = computeTokenCredits({ uncachedInputTokens: v.uncachedInputTokens, cachedInputTokens: v.cachedInputTokens, outputTokens: v.outputTokens }, policy, rates).credits;
    else if (v.kind === "minute") actual = computeMinuteCredits({ elapsedSeconds: 60 }, policy, v.providerCostUsd).credits;
    else actual = computeFixedCredits(v.configuredCredits);
    assert.equal(actual, v.expectedCredits, v.id);
  }
});

test("fixed prices are final, fractional and zero are valid", () => {
  assert.equal(computeFixedCredits(1.7), 1.7);
  assert.equal(computeFixedCredits(0.12567), 0.12567);
  assert.equal(computeFixedCredits(0), 0);
});

test("minute billing prorates seconds and zero override means free", () => {
  assert.equal(computeMinuteCredits({ elapsedSeconds: 30 }, policy, 0.05).credits, 1.45955);
  assert.equal(computeMinuteCredits({ elapsedSeconds: 60 }, policy, 0.05, 0).credits, 0);
});
