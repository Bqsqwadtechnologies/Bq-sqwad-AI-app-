import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const index = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
const generationDefaults = index.slice(index.indexOf("const ADMIN_SEED_DEFAULTS"), index.indexOf("const PRICING_DEFAULTS_MARKER"));
const auxiliaryDefaults = index.slice(index.indexOf("const TOKEN_CREDIT_DEFAULTS ="), index.indexOf("async function seedTokenCreditDefaultsOnce"));

test("all model token defaults match the final BQ credit table", () => {
  for (const value of [
    "0.000005833333333333333", "0.0000005833333333333333", "0.000029166666666666667",
    "0.000011666666666666668", "0.0000011666666666666668", "0.00007",
    "0.0002916666666666667", "0.00007291666666666667", "0.00046666666666666666",
    "0.00011666666666666667", "0.00175",
  ]) assert.ok(auxiliaryDefaults.includes(value), `missing main/image rate ${value}`);
  for (const value of [
    "0.000035", "0.0000035", "0.00014", "0.0005833333333333334", "0.0000175",
    "0.0011666666666666668", "0.00004666666666666667", "0.000004666666666666667",
    "0.00011666666666666667", "0.000011666666666666668", "0.00046666666666666666",
    "0.00014583333333333335", "0.000014583333333333333", "0.00007291666666666667",
    "0.0002916666666666667", "0.0007", "0.0000011666666666666668", "0.00001",
  ]) assert.ok(auxiliaryDefaults.includes(value), `missing auxiliary rate ${value}`);
});

test("all fixed and per-second launch prices are present in generation seed data", () => {
  for (const [operation, price] of [
    ["live.voice", "0.045"], ["live.camera", "0.09"], ["speech.transcribe", "0.0204337"],
    ["speech.synthesize", "0.0145833"], ["file.pdf", "0.3"], ["file.docx", "0.3"],
    ["file.xlsx", "0.45"], ["file.csv", "0.15"], ["file.txt", "0.15"], ["file.md", "0.15"],
    ["file.html", "0.15"], ["file.json", "0.15"], ["file.code", "0.15"], ["file.xml", "0.15"],
    ["file.yaml", "0.15"], ["web.search", "0.2"], ["web.research", "7"],
    ["chat.background_upload", "2"], ["team.member_message", "0"], ["feature.connector_action", "0.4"],
    ["resume.generate", "0.75"], ["video.generate", "87.573"], ["music.generate", "1.5"], ["music.render", "0.3"],
  ]) {
    assert.ok(generationDefaults.includes(`"operation": "${operation}"`), `missing operation ${operation}`);
    assert.ok(generationDefaults.includes(price), `missing price ${price} for ${operation}`);
  }
});

test("seed defaults contain no stored cost-rate fields and the marker is version five", () => {
  assert.doesNotMatch(auxiliaryDefaults, /UsdPer(Million|Token)/);
  assert.match(index, /TOKEN_CREDIT_DEFAULTS_MARKER = "adminAdvancedOptions\/bq_token_credit_pricing_defaults_v5"/);
  assert.match(index, /seedTokenCreditDefaultsOnce\(\)/);
  assert.match(index, /PRICING_DEFAULTS_MARKER = "adminAdvancedOptions\/bq_live_credit_pricing_defaults_v5"/);
  assert.match(index, /marker\?\.applied === true && Number\(marker\.version\) >= 5/);
  assert.match(index, /seededDefaultVersion: 5/);
  assert.match(index, /version: 5,[\s\S]*?source: "BQ credit pricing defaults"/);
});

test("image generation and image editing remain measured-token rules with zero fixed price", () => {
  assert.match(generationDefaults, /"operation": "image\.generate",[\s\S]*?"credits": 0,[\s\S]*?"creditsPerUnit": 0,[\s\S]*?"unit": "none"/);
  assert.match(generationDefaults, /"operation": "image\.edit",[\s\S]*?"credits": 0,[\s\S]*?"creditsPerUnit": 0,[\s\S]*?"unit": "none"/);
  assert.match(index, /PRESERVE_EXISTING_DEFAULTS = new Set\(\["image\.generate"/);
});

test("Smart heartbeat calculation adds transcription at the same elapsed duration", () => {
  assert.match(index, /calculateLiveHeartbeatCharge\(liveRule, smartTranscribeRule, elapsedSeconds, heartbeatBody\.smart === true\)/);
});
