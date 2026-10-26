import test from "node:test";
import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

const uiRoot = new URL("../../app/src/main/java/com/bqsqwad/ai/ui/", import.meta.url);
const forbidden = /\b(?:Google Play|Firebase|Play Console)\b/i;
const failures = [];

async function walk(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      await walk(full);
      continue;
    }
    if (!entry.name.endsWith(".kt") || entry.name.startsWith("Admin") || entry.name === "LegalCenterScreen.kt") continue;
    let source = await readFile(full, "utf8");
    // Only inspect Kotlin string literals, not imports, identifiers, or comments.
    source = source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^[ \t]*\/\/.*$/gm, "");
    const strings = source.match(/"(?:\\.|[^"\\])*"/g) || [];
    for (const literal of strings) {
      if (forbidden.test(literal)) failures.push({ file: path.relative(new URL("../../app/src/main/java/com/bqsqwad/ai/ui/", import.meta.url).pathname, full), literal });
    }
  }
}

test("customer-facing UI string literals do not expose infrastructure provider names", async () => {
  await walk(uiRoot.pathname);
  assert.deepEqual(failures, [], JSON.stringify(failures, null, 2));
});
