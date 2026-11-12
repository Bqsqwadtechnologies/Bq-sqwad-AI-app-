import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

test("Worker named SSE events use real line breaks", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  const expected = 'return "event: " + event + "\\ndata: " + JSON.stringify(data) + "\\n\\n";';
  const broken = 'return "event: " + event + "\\\\ndata: "';
  assert.ok(source.includes(expected), "SSE formatter must use newline escapes between event fields");
  assert.ok(!source.includes(broken), "SSE formatter must not emit literal backslash-n sequences");
});
