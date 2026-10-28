import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");

test("fixed-price features fail closed when the admin price is missing", () => {
  assert.match(source, /async function featurePrice\(name\)/);
  assert.match(source, /if \(doc\?\.creditCost != null && Number\.isFinite\(value\) && value >= 0\) return value/);
  assert.match(source, /await logMissingPrice\(name\);[\s\S]*?httpStatus: 503[\s\S]*?safeError: "feature_temporarily_unavailable"/);
  assert.doesNotMatch(source, /Math\.max\(1, Number\(value\.creditCost == null/);
});

test("media price seeds are admin documents and preserve later administrator edits", () => {
  assert.match(source, /const ADMIN_SEED_DEFAULTS = \{ image_generation: 1\.5, image_edit: 1\.5, chat_background_upload: 2 \}/);
  assert.match(source, /if \(!\(await firestoreGet\(path\)\.catch\(\(\) => null\)\)\) \{[\s\S]*?firestoreSet\(path, \{ name, creditCost: price, seeded: true/);
  assert.match(source, /await seedAdminPricingDefaults\(\)/);
});

test("image generation, image edit, and background upload read admin prices rather than route literals", () => {
  assert.match(source, /body\.creditCost = freeImage \? 0 : await featurePrice\("image_generation"\)/);
  assert.match(source, /body\.creditCost = await featurePrice\("image_edit"\)/);
  assert.match(source, /const backgroundCreditCost = await featurePrice\("chat_background_upload"\)/);
  assert.doesNotMatch(source, /body\.creditCost = freeImage \? 0 : 2\.5/);
  assert.doesNotMatch(source, /body\.creditCost = 1\.5/);
});

test("a missing capability document reaches the price logger instead of escaping at the first read", () => {
  assert.match(source, /const control = await firestoreGet\("adminAdvancedOptions\/capability_" \+ name\)\.catch\(\(\) => null\)/);
});

test("text capabilities are token-metered rather than charged as fixed feature prices", () => {
  assert.match(source, /const TOKEN_METERED = new Set\(\["chat", "vision", "chat_attachment", "personal_intelligence", "team_chat", "live_camera", "live_voice"\]\)/);
  assert.match(source, /creditCost: TOKEN_METERED\.has\(name\) \? 0 : await featurePrice\(name\)/);
  assert.doesNotMatch(source, /Math\.max\(1,\s*Number\(value\.creditCost == null/);
});
