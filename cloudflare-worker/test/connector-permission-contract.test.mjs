import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");

test("connector follow-up turns retain connector intent when the client marks prior use", () => {
  assert.match(source, /async function runConnectorAwareOpenAI\([\s\S]*?conversationUsedConnectors = false\)/);
  assert.match(source, /const connectorIntent = isConnectorRelatedPrompt\(message\) \|\| conversationUsedConnectors === true/);
  assert.match(source, /body\?\.conversationUsedConnectors === true/);
});

test("read-only connector write attempts return an actionable permission error", () => {
  assert.match(source, /error: mutation\.test\(cleanSlug\) \? "connector_write_requires_full_access" : "connector_tool_not_allowed"/);
  assert.match(source, /toolkit: cleanToolkit,[\s\S]*?accessMode/);
});
