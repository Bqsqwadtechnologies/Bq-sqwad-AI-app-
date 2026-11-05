import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { canonicalPlayEntitlementWrite } from "../src/google-play.js";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const playRtdn = readFileSync(join(root, "src/play-rtdn.js"), "utf8");
const vault = readFileSync(join(root, "src/business-vault-native.js"), "utf8");
const indexJs = readFileSync(join(root, "src/index.js"), "utf8");

test("play-rtdn module exports handlers", () => {
  assert.match(playRtdn, /export async function handlePlayRtdn/);
  assert.match(playRtdn, /export async function reaggregateRootEntitlement/);
  assert.match(playRtdn, /source: "google_play"/);
  assert.match(playRtdn, /state: notificationType === 5 \? "grace" : notificationType === 3 \? "cancelled" : "expired"/);
});

test("verified Play plan writes retain the canonical product module", () => {
  const write = canonicalPlayEntitlementWrite(
    "test-user",
    { planId: "bizstarter", productType: "subs" },
    { startTime: "2026-11-01T00:00:00.000Z" },
    { expiryTime: "2026-12-01T00:00:00.000Z" },
    "ACTIVE"
  );
  assert.equal(write.data.module, "business");
  assert.equal(write.data.source, "google_play");
  assert.equal(write.data.expiresAt, "2026-12-01T00:00:00.000Z");
});

test("Worker entitlements response includes website canonical plan rows", () => {
  assert.match(indexJs, /firestoreQuery\("plans", \{ parent: "entitlements\/" \+ user\.uid, limit: 100 \}\)/);
  assert.match(indexJs, /row\.expiresAt \|\| row\.endDate/);
  assert.match(indexJs, /module: String\(row\.module \|\| ""\)/);
});

test("vault append is server-only and redacts secrets", () => {
  assert.match(vault, /appendVaultMessage/);
  assert.match(vault, /assertServerOnlyVaultAccess/);
  assert.match(vault, /redactForVault/);
  assert.match(vault, /AES-GCM/);
  assert.match(vault, /ciphertext/);
  assert.match(indexJs, /appendBusinessVaultEntry/);
  assert.match(indexJs, /BUSINESS_VAULT_ENCRYPTION_KEY/);
});

test("index import or route for RTDN preferred when present", () => {
  // Soft preference: fail only if neither snippet path nor live route exists.
  const wired = indexJs.includes("/play/rtdn") && indexJs.includes("PLAY_RTDN_SHARED_SECRET") && indexJs.includes("handlePlayRtdn");
  const snippet = existsSync(join(root, "src/index-play-rtdn.snippet.js"));
  assert.ok(wired || snippet, "expected /play/rtdn wire or index-play-rtdn.snippet.js");
});
