import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const native = readFileSync(join(root, "src/teams-sqwad-native.js"), "utf8");
const routes = readFileSync(join(root, "src/teams-routes-handler.js"), "utf8");
const android = readFileSync(join(root, "../app/src/main/java/com/bqsqwad/ai/ui/teams/TeamWorkspaceScreen.kt"), "utf8");

test("shared team memory reads require membership and writes require admin", () => {
  assert.match(native, /teamsListMemory\(user, body\)[\s\S]*?requireActiveMember\(user, teamId, "viewer"\)/);
  assert.match(native, /teamsAddMemory\(user, body\)[\s\S]*?requireActiveMember\(user, teamId, "admin"\)/);
  assert.match(native, /teamsUpdateMemory\(user, body\)[\s\S]*?requireActiveMember\(user, teamId, "admin"\)/);
  assert.match(native, /teamsDeleteMemory\(user, body\)[\s\S]*?requireActiveMember\(user, teamId, "admin"\)/);
});

test("shared team memory routes are mounted and Android calls the same Worker API", () => {
  for (const path of [
    "/native/teams/memory",
    "/native/teams/memory/add",
    "/native/teams/memory/update",
    "/native/teams/memory/delete",
  ]) {
    assert.ok(routes.includes(path), "missing Worker route: " + path);
    assert.ok(android.includes(path), "Android does not call Worker route: " + path);
  }
});
