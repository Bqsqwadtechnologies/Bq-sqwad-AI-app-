import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

test("realtime client-secret request uses the GA audio.output.voice field", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.match(source, /session:\s*\{[\s\S]*?audio:\s*\{[\s\S]*?output:\s*\{[\s\S]*?\bvoice\b/);
  assert.doesNotMatch(source, /session:\s*\{\s*type:\s*"realtime"\s*,\s*model\s*,\s*voice:/);
  assert.match(source, /const DEFAULT_REALTIME_MODEL = "gpt-realtime"/);
});


test("admin realtime model selector includes documented current realtime models", async () => {
  const source = await readFile(new URL("../../app/src/main/java/com/bqsqwad/ai/ui/AdminLivePricingScreen.kt", import.meta.url), "utf8");
  assert.match(source, /gpt-realtime-2\\.1/);
  assert.match(source, /gpt-realtime/);
});

test("realtime public errors use safe codes rather than provider diagnostics", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  for (const safeCode of [
    "provider_key_missing",
    "provider_billing",
    "realtime_request_rejected",
    "realtime_model_rejected",
    "realtime_session_failed"
  ]) assert.ok(source.includes(safeCode), `missing safe public error code: ${safeCode}`);
  assert.match(source, /const body = \{ error: code \};/);
  assert.doesNotMatch(source, /const body = \{[^}]*providerStatus/);
});

test("live voice and camera defaults are free unless admin explicitly configures a positive price", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.match(source, /name === "live_voice" \|\| name === "live_camera"/);
  assert.match(source, /Number\(value\.creditCost == null \? 0 : value\.creditCost\)/);
});
