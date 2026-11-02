import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const indexJs = readFileSync(join(root, "src/index.js"), "utf8");
const androidSettings = readFileSync(join(root, "../app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt"), "utf8");
const androidApi = readFileSync(join(root, "../app/src/main/java/com/bqsqwad/ai/core/WorkspaceApiClient.kt"), "utf8");

test("My Tasks reads only the authenticated user's assigned tasks", () => {
  assert.match(indexJs, /url\.pathname === "\/tasks\/my" && request\.method === "GET"/);
  assert.match(indexJs, /fieldPath: "assignees"/);
  assert.match(indexJs, /op: "ARRAY_CONTAINS"/);
  assert.match(indexJs, /row\.assignees\.includes\(user\.uid\)/);
});

test("My Tasks status updates validate task ownership and lifecycle state", () => {
  assert.match(indexJs, /url\.pathname === "\/tasks\/my\/status"/);
  assert.match(indexJs, /task\.assignees\.includes\(user\.uid\)/);
  assert.match(indexJs, /\["open","in_progress","blocked","done"\]\.includes\(status\)/);
});

test("notification preferences are account-scoped and enforced for admin broadcasts", () => {
  assert.match(indexJs, /url\.pathname === "\/notifications\/preferences"/);
  assert.match(indexJs, /notificationPreferences\/" \+ user\.uid/);
  assert.match(indexJs, /preferences\.get\(String\(row\.uid \|\| ""\)\)\?\.pushEnabled !== false/);
});

test("notification category mutes are shared between Worker and Android settings", () => {
  assert.match(indexJs, /Array\.isArray\(body\.mutedKinds\)/);
  assert.match(indexJs, /allowedKinds\.has\(kind\)/);
  assert.match(indexJs, /mutedKinds,/);
  assert.match(androidApi, /put\("mutedKinds", JSONArray\(mutedKinds\)\)/);
  assert.match(androidSettings, /updateNotificationPreferences\(\s*notifications, notifications, mutedNotificationKinds\.toList\(\)/);
  assert.match(androidSettings, /"team_message" to "Team messages"/);
});
