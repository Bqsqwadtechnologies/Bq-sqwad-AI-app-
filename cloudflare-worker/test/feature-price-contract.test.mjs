import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");

test("fixed-price features fail closed when the direct generation rule is missing", () => {
  assert.match(source, /async function featurePrice\(name, requestedModel = null\)/);
  assert.match(source, /const rule = await activeGenerationRule\(operation, model\)/);
  assert.match(source, /await logMissingPrice\(key \|\| "unknown"\);[\s\S]*?httpStatus: 503[\s\S]*?safeError: "feature_temporarily_unavailable"/);
  assert.doesNotMatch(source, /Math\.max\(1, Number\(value\.creditCost == null/);
});

test("shared generation defaults use baked customer credit prices and preserve excluded prices", () => {
  assert.ok(source.includes("const ADMIN_SEED_DEFAULTS = ["));
  assert.ok(source.includes('"operation": "video.generate"'));
  assert.ok(source.includes('"creditsPerUnit": 87.573'));
  assert.ok(source.includes('"operation": "file.pdf"'));
  assert.ok(source.includes('"creditsPerUnit": 0.3'));
  assert.ok(source.includes('"operation": "web.search"'));
  assert.ok(source.includes('"credits": 25.5'));
  assert.ok(source.includes('"creditsPerUnit": 0.34056155'));
  assert.ok(source.includes('"creditsPerUnit": 0.0204337'));
  assert.ok(source.includes("PRESERVE_EXISTING_DEFAULTS"));
  assert.ok(source.includes("PRICING_DEFAULTS_MARKER"));
  assert.match(source, /await seedAdminPricingDefaults\(\);[\s\S]*?const key = String\(operation/);
});
test("image generation, image edit, and background upload read admin prices rather than route literals", () => {
  assert.match(source, /body\.creditCost = freeImage \? 0 : await featurePrice\("image_generation", control\.modelId\)/);
  assert.match(source, /body\.creditCost = await featurePrice\("image_edit", control\.modelId\)/);
  assert.match(source, /const pdfCreditCost = await featurePrice\("pdf", pdfControl\.modelId\)/);
  assert.match(source, /const backgroundCreditCost = await featurePrice\("chat_background_upload"\)/);
  assert.match(source, /const CAPABILITY_PRICE_OPERATIONS = Object\.freeze/);
  assert.match(source, /image_generation: "image\.generate"/);
  assert.doesNotMatch(source, /configuredMediaProviderCost|canonicalUsageBillingPolicy|markupApplied|config\/usageBilling/);
  assert.doesNotMatch(source, /body\.creditCost = freeImage \? 0 : 2\.5/);
  assert.doesNotMatch(source, /body\.creditCost = 1\.5/);
});

test("a missing capability document reaches the price logger instead of escaping at the first read", () => {
  assert.match(source, /const control = await firestoreGet\("adminAdvancedOptions\/capability_" \+ name\)\.catch\(\(\) => null\)/);
});

test("text capabilities are token-metered rather than charged as fixed feature prices", () => {
  assert.match(source, /const TOKEN_METERED = new Set\(\["chat", "vision", "chat_attachment", "personal_intelligence", "team_chat", "live_camera", "live_voice", "web_search", "deep_research"\]\)/);
  assert.match(source, /creditCost: TOKEN_METERED\.has\(name\) \? 0 : await featurePrice\(name\)/);
  assert.doesNotMatch(source, /Math\.max\(1,\s*Number\(value\.creditCost == null/);
});
