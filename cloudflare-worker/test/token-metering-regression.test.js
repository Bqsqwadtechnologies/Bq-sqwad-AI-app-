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
  const lines = source.split("\n");
  const offenders = lines.filter(line =>
    /chargeCredits\(/.test(line) &&
    /control\.creditCost\s*\|\|\s*0/.test(line) &&
    /capabilityName/.test(line)
  );
  assert.deepEqual(offenders, [], "remove flat capability-cost reservations from chat/vision routes");
});

test("plain chat settles with provider-reported token formula, not a hard-coded one-credit charge", () => {
  const start = source.indexOf("async function ai(request, user, body)");
  const end = source.indexOf("\nasync function stream(", start + 10);
  const ai = source.slice(start, end);
  assert.match(ai, /const calculation = calculateTokenCost\(/);
  assert.match(ai, /settleCreditReservation\(user\.uid, reservation\.reservationId, calculation\.customerCredits/);
  assert.doesNotMatch(ai, /settleCreditReservation\(user\.uid, reservation\.reservationId,\s*1\s*,/);
});
