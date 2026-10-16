import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

test("realtime client-secret request uses the GA audio.output.voice field", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.match(source, /audio:\s*\{[\s\S]*?output:\s*\{[\s\S]*?voice:/);
  assert.doesNotMatch(source, /session:\s*\{\s*type:\s*"realtime"\s*,\s*model\s*,\s*voice:/);
  assert.match(source, /const DEFAULT_REALTIME_MODEL = "gpt-realtime"/);
  assert.doesNotMatch(source, /gpt-realtime-2\.1/);
});

test("live voice and camera defaults are free unless admin explicitly configures a positive price", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.match(source, /name === "live_voice" \|\| name === "live_camera"/);
  assert.match(source, /Number\(value\.creditCost == null \? 0 : value\.creditCost\)/);
});
