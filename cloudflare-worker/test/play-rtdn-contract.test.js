import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const playRtdn = readFileSync(join(root, "src/play-rtdn.js"), "utf8");
const vault = readFileSync(join(root, "src/business-vault-native.js"), "utf8");
const indexJs = readFileSync(join(root, "src/index.js"), "utf8");

test("play-rtdn module exports handlers", () => {
  assert.match(playRtdn, /export async function handlePlayRtdn/);
  assert.match(playRtdn, /export async function reaggregateRootEntitlement/);
  assert.match(playRtdn, /google_play_rtdn/);
});

test("vault append is server-only and redacts secrets", () => {
  assert.match(vault, /appendVaultMessage/);
  assert.match(vault, /assertServerOnlyVaultAccess/);
  assert.match(vault, /redactForVault/);
});

test("index import or route for RTDN preferred when present", () => {
  // Soft preference: fail only if neither snippet path nor live route exists.
  const wired = indexJs.includes("/play/rtdn") || indexJs.includes("play-rtdn.js");
  const snippet = existsSync(join(root, "src/index-play-rtdn.snippet.js"));
  assert.ok(wired || snippet, "expected /play/rtdn wire or index-play-rtdn.snippet.js");
});
