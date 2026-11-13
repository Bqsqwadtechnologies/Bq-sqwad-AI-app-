import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");

test("Main Chat discovers owned connector sessions without keyword gating", () => {
  assert.match(source, /async function runConnectorAwareOpenAI\([\s\S]*?conversationUsedConnectors = false[,)]/);
  assert.match(source, /firestoreQuery\("composioSessions",\s*\{[\s\S]*?fieldPath: "uid"[\s\S]*?user\.uid/);
  assert.doesNotMatch(source, /const connectorIntent = isConnectorRelatedPrompt/);
  assert.match(source, /runConnectorAwareOpenAI\(globalThis\.__bqEnv, user, control, userMessage, system, true/);
  assert.match(source, /CONNECTED SERVICES FOR THIS ACCOUNT:[\s\S]*?do not tell the user it is disconnected/);
});

test("connector calls reserve and settle the configured per-call feature price", () => {
  assert.match(source, /activeGenerationRule\("feature\.connector_action"\)/);
  assert.match(source, /chargeCredits\(uid, Number\(pricing\.credits\), "feature\\.connector_action", user, \{ allowFractional: true \}\)/);
  assert.match(source, /commitCredits\(uid, reservation\.reservationId, "composio"\)/);
  assert.match(source, /reverseCredits\(uid, reservation\.reservationId\)/);
  assert.match(source, /execution\.httpStatus === 402/);
});

test("read-only connector write attempts return an actionable permission error", () => {
  assert.match(source, /error: mutation\.test\(cleanSlug\) \? "connector_write_requires_full_access" : "connector_tool_not_allowed"/);
  assert.match(source, /toolkit: cleanToolkit,[\s\S]*?accessMode/);
});
