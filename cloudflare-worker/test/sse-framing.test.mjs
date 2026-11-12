import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

test("Worker named SSE events use real line breaks", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.ok(
    source.includes(String.raw\`return "event: " + event + "\ndata: " + JSON.stringify(data) + "\n\n";\`),
    "SSE event formatter must separate event/data fields and events with actual newline escapes"
  );
  assert.ok(
    !source.includes(String.raw\`return "event: " + event + "\\ndata: "\`),
    "SSE event formatter must not emit literal backslash-n sequences"
  );
});
