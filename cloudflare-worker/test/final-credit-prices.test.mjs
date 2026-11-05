import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const index = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
const tokenBilling = await readFile(new URL("../src/token-billing-native.js", import.meta.url), "utf8");
const registry = await readFile(new URL("../src/model-registry-native.js", import.meta.url), "utf8");
const seedGuard = await readFile(new URL("../src/pricing-seed-native.js", import.meta.url), "utf8");

test("all requested model token rates are present in the shared Firestore seed", () => {
  const expected = [
    "0.000005833333333333333", "0.0000005833333333333333", "0.000029166666666666667",
    "0.000011666666666666668", "0.0000011666666666666668", "0.00007",
    "0.0002916666666666667", "0.00007291666666666667", "0.00046666666666666666",
    "0.00011666666666666667", "0.00175", "0.000035", "0.0000035", "0.00014",
    "0.0005833333333333334", "0.0000175", "0.0011666666666666668",
    "0.00004666666666666667", "0.000004666666666666667",
    "0.00011666666666666667", "0.000011666666666666668", "0.00046666666666666666",
    "0.00014583333333333335", "0.000014583333333333333",
    "0.00007291666666666667", "0.0002916666666666667",
    "0.0007", "0.0000011666666666666668", "0.00001"
  ];
  for (const rate of expected) assert.ok(index.includes(rate), `missing seeded credit rate ${rate}`);
});

test("generation defaults match every fixed price and keep image generation token-metered", () => {
  for (const operation of [
    '"operation": "live.voice"', '"operation": "live.camera"', '"operation": "speech.transcribe"',
    '"operation": "speech.synthesize"', '"operation": "file.pdf"', '"operation": "file.docx"',
    '"operation": "file.xlsx"', '"operation": "file.csv"', '"operation": "file.txt"',
    '"operation": "file.md"', '"operation": "file.html"', '"operation": "file.json"',
    '"operation": "file.code"', '"operation": "file.xml"', '"operation": "file.yaml"',
    '"operation": "web.search"', '"operation": "web.research"', '"operation": "chat.background_upload"',
    '"operation": "team.member_message"', '"operation": "feature.connector_action"',
    '"operation": "resume.generate"', '"operation": "video.generate"', '"operation": "music.generate"',
    '"operation": "music.render"'
  ]) assert.ok(index.includes(operation), `missing operation ${operation}`);
  for (const rate of ['"creditsPerUnit": 0.045','"creditsPerUnit": 0.09','"creditsPerUnit": 0.0204337','"creditsPerUnit": 0.0145833','"creditsPerUnit": 0.3','"creditsPerUnit": 0.45','"credits": 0.15','"creditsPerUnit": 0.2','"credits": 7','"credits": 2','"credits": 0.4','"credits": 0.75','"creditsPerUnit": 87.573','"creditsPerUnit": 1.5']) {
    assert.ok(index.includes(rate), `missing generation credit default ${rate}`);
  }
  assert.match(index, /"operation": "image\.generate",[\s\S]*?"credits": 0,[\s\S]*?"creditsPerUnit": 0,[\s\S]*?"unit": "none"/);
  assert.match(index, /"operation": "image\.edit",[\s\S]*?"credits": 0,[\s\S]*?"creditsPerUnit": 0,[\s\S]*?"unit": "none"/);
});

test("token and generation seed objects contain no legacy USD-per-token fields", () => {
  const tokenSeedStart = index.indexOf("const TOKEN_CREDIT_DEFAULTS = [");
  const tokenSeedEnd = index.indexOf("async function seedTokenCreditDefaultsOnce", tokenSeedStart);
  const generationSeedStart = index.indexOf("const ADMIN_SEED_DEFAULTS = [");
  const generationSeedEnd = index.indexOf("const PRICING_DEFAULTS_MARKER", generationSeedStart);
  assert.ok(tokenSeedStart >= 0 && tokenSeedEnd > tokenSeedStart);
  assert.ok(generationSeedStart >= 0 && generationSeedEnd > generationSeedStart);
  assert.doesNotMatch(index.slice(tokenSeedStart, tokenSeedEnd), /UsdPer(Million|Token)/);
  assert.doesNotMatch(index.slice(generationSeedStart, generationSeedEnd), /UsdPer(Million|Token)/);
  assert.doesNotMatch(tokenBilling, /inputUsdPerMillion:|cachedInputUsdPerMillion:|outputUsdPerMillion:/);
  assert.doesNotMatch(registry, /token_pricing_required_for_active_token_billing/);
});

test("generation and token defaults are guarded by version-five markers", () => {
  assert.match(index, /bq_live_credit_pricing_defaults_v5/);
  assert.match(index, /bq_token_credit_pricing_defaults_v5/);
  assert.match(index, /shouldSeedPricingDefaults\(marker, 5\)/);
  assert.match(seedGuard, /Number\(marker\?\.version\) >= requiredVersion/);
  assert.match(index, /version: 5,[\s\S]*?source: "BQ credit pricing defaults"/);
});
