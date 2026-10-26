import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");

test("chat and attachment capability defaults are token-metered without a one-credit floor", () => {
  assert.match(source, /const TOKEN_METERED = new Set\(\["chat", "vision", "chat_attachment", "live_camera", "live_voice"\]\)/);
  assert.match(source, /creditCost: TOKEN_METERED\.has\(name\)\s*\? 0/);
  assert.match(source, /if \(!charge && !options\.allowFractional && !TOKEN_METERED\.has\(String\(capabilityName \|\| ""\)\)\)/);
});

test("vision reserves an estimate, settles reported token usage, and reverses failed requests", () => {
  const start = source.indexOf("async function vision(request, user, body) {");
  const end = source.indexOf("\nasync function ", start + 10);
  assert.ok(start >= 0 && end > start, "vision function must exist");
  const vision = source.slice(start, end);
  assert.match(vision, /estimateTokenReservation\(/);
  assert.match(vision, /normalizeProviderUsage\(result\.providerId, result\.usage \|\| \{\}\)/);
  assert.match(vision, /calculateTokenCost\(/);
  assert.match(vision, /settleCreditReservation\(/);
  assert.match(vision, /await reverseCredits\(user\.uid, reservation\.reservationId\)/);
  assert.doesNotMatch(vision, /chargeCredits\(user\.uid, control\.creditCost \|\| 0/);
});

test("metered chat cannot use a flat creditCost reservation argument", () => {
  for (const [startMarker, endMarker] of [
    ["async function ai(request, user, body)", "\nasync function stream("],
    ["async function stream(request, user, body)", "\nasync function vision("],
    ["async function vision(request, user, body)", "\nasync function "],
  ]) {
    const start = source.indexOf(startMarker);
    const end = source.indexOf(endMarker, start + startMarker.length);
    assert.ok(start >= 0 && end > start, "metered handler boundaries must exist");
    const handler = source.slice(start, end);
    assert.doesNotMatch(handler, /chargeCredits\(user\.uid,\s*control\.creditCost\s*\|\|\s*0/);
  }
});

test("plain chat settles with provider-reported token formula, not a hard-coded one-credit charge", () => {
  const start = source.indexOf("async function ai(request, user, body)");
  const end = source.indexOf("\nasync function stream(", start + 10);
  const ai = source.slice(start, end);
  assert.match(ai, /const calculation = calculateTokenCost\(/);
  assert.match(ai, /settleCreditReservation\(user\.uid, reservation\.reservationId, calculation\.customerCredits/);
  assert.doesNotMatch(ai, /settleCreditReservation\(user\.uid, reservation\.reservationId,\s*1\s*,/);
});

test("fixed-price shared-charge helper rejects token-metered capabilities", () => {
  const start = source.indexOf("async function withSharedCreditCharge(");
  const end = source.indexOf("\nexport function isPublicCatalogPlan", start);
  assert.ok(start >= 0 && end > start);
  const helper = source.slice(start, end);
  assert.match(helper, /TOKEN_METERED\.has\(String\(capabilityName \|\| ""\)\)/);
  assert.match(helper, /token_metering_route_required/);
});

test("search fees require the explicit admin setting and cannot inherit the chat capability price", () => {
  const start = source.indexOf('const configuredSearchCharge = deepResearch ?');
  const end = source.indexOf('\n      }', start);
  assert.ok(start >= 0 && end > start);
  const block = source.slice(start, end);
  assert.match(block, /control\.config\?\.creditsPerSearch/);
  assert.match(block, /search_pricing_not_configured/);
  assert.doesNotMatch(block, /Math\.max\([^\n]*control\.creditCost/);
});
