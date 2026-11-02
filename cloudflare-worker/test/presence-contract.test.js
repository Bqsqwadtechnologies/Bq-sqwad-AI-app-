import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const presenceUsage = readFileSync(join(root, "src/presence-usage-native.js"), "utf8");
const presenceRoute = readFileSync(join(root, "src/presence-route.js"), "utf8");
const indexJs = readFileSync(join(root, "src/index.js"), "utf8");

test("presence helper writes presence/{uid} shape", () => {
  assert.match(presenceUsage, /writePresence/);
  assert.match(presenceUsage, /platform/);
  assert.match(presenceUsage, /lastSeen/);
});

test("presence route rejects unsigned callers", () => {
  assert.match(presenceRoute, /Sign in required/);
  assert.match(presenceRoute, /handlePresencePing/);
});

test("index routes POST /presence/ping", () => {
  assert.match(indexJs, /pathname === \"\/presence\/ping\"/);
  assert.match(indexJs, /request\.method === \"POST\"/);
});
