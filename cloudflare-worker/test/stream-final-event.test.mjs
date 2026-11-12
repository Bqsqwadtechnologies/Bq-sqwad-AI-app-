import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

test("Android chat Worker flushes the final provider SSE event", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.ok(source.includes("buffer += decoder.decode();"));
  assert.ok(source.includes("if (buffer.trim()) processProviderEvent(buffer);"));
  assert.ok(source.includes('if (!completed) throw new Error("stream_incomplete");'));
});
