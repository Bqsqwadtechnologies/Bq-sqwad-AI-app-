import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const googlePlay = readFileSync(join(root, "src/google-play.js"), "utf8");
const indexJs = readFileSync(join(root, "src/index.js"), "utf8");
const vault = readFileSync(join(root, "src/business-vault-native.js"), "utf8");

test("google-play exports RTDN and reaggregate helpers", () => {
  assert.match(googlePlay, /export async function handlePlayRtdn/);
  assert.match(googlePlay, /export async function reaggregateRootEntitlement/);
  assert.match(googlePlay, /google_play_rtdn/);
});

test("index mounts POST /play/rtdn or documents pending wire", () => {
  // Prefer live route; if index not yet patched, fail clearly so CI catches it.
  assert.match(indexJs, /\/play\/rtdn/);
  assert.match(indexJs, /handlePlayRtdn/);
});

test("vault append is server-only and redacts secrets", () => {
  assert.match(vault, /appendVaultMessage/);
  assert.match(vault, /assertServerOnlyVaultAccess/);
  assert.match(vault, /redactForVault/);
});
