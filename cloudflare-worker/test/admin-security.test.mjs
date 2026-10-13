import test from "node:test";
import assert from "node:assert/strict";
import { isAdminSessionActive } from "../src/admin-security-native.js";

test("unlock-shaped admin step-up record passes and expires after 30 minutes", () => {
  const now = Date.parse("2026-10-10T12:00:00.000Z");
  const record = { uid: "admin-test", verifiedAt: new Date(now), expiresAt: new Date(now + 30 * 60 * 1000) };
  assert.equal(isAdminSessionActive(record, now), true);
  assert.equal(isAdminSessionActive(record, now + 30 * 60 * 1000), false);
  assert.equal(isAdminSessionActive({ ...record, expiresAt: new Date(now - 1) }, now), false);
});

test("admin expiry parser accepts ISO, Date and Firestore Timestamp round trips", () => {
  const now = Date.parse("2026-10-10T12:00:00.000Z");
  assert.equal(isAdminSessionActive({ expiresAt: new Date(now + 1000).toISOString() }, now), true);
  assert.equal(isAdminSessionActive({ expiresAt: new Date(now + 1000) }, now), true);
  assert.equal(isAdminSessionActive({ expiresAt: { seconds: Math.floor((now + 1000) / 1000), nanoseconds: 0 } }, now), true);
  assert.equal(isAdminSessionActive({ expiresAt: { _seconds: Math.floor((now + 1000) / 1000), _nanoseconds: 0 } }, now), true);
  assert.equal(isAdminSessionActive({ expiresAt: { timestampValue: new Date(now + 1000).toISOString() } }, now), true);
  assert.equal(isAdminSessionActive({ expiresAt: "not-a-date" }, now), false);
  assert.equal(isAdminSessionActive(null, now), false);
});
