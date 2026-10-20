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


test("canonical website pricing JSON is unwrapped before billing reads it", async () => {
  const docs = {
    "config/usageBilling": { markupPercent: 85, feePercent: 15, usdPerCredit: 0.07999 },
    "config/providerConnections": { liveVoice: { usdPerMinute: 0.05 } },
    "config/pricing": { json: JSON.stringify({ generation: { image: 12 }, features: [{ id: "web-search", credits: 1.7 }] }) },
    "config/aiModels": { models: [{ id: "gpt-test", inputUsdPerToken: 0.000001 }] }
  };
  const { loadBillingConfig } = await import("../src/billing-core.js");
  const config = await loadBillingConfig(async (path) => docs[path], 1234);
  assert.equal(config.loadedAt, 1234);
  assert.equal(config.usageBilling.markupPercent, 85);
  assert.equal(config.pricing.generation.image, 12);
  assert.equal(config.pricing.features[0].credits, 1.7);
  assert.equal(config.providerConnections.liveVoice.usdPerMinute, 0.05);
  assert.equal(config.aiModels.models[0].id, "gpt-test");
  assert.deepEqual(config.warnings, []);
});

test("malformed canonical pricing JSON is ignored with an admin-visible warning", async () => {
  const docs = {
    "config/usageBilling": { markupPercent: 600, feePercent: 0, usdPerCredit: 0.1199 },
    "config/providerConnections": {},
    "config/pricing": { json: "{broken" },
    "config/aiModels": {}
  };
  const { loadBillingConfig } = await import("../src/billing-core.js");
  const config = await loadBillingConfig(async (path) => docs[path]);
  assert.deepEqual(config.pricing, {});
  assert.ok(config.warnings.some((warning) => warning.includes("invalid JSON config")));
});
