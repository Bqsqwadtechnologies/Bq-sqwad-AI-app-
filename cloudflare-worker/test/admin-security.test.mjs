import test from "node:test";
import assert from "node:assert/strict";
import { isAdminSessionActive } from "../src/admin-security-native.js";

test("admin step-up sessions are active only before their expiry", () => {
  const now = Date.parse("2026-10-10T12:00:00.000Z");
  assert.equal(
    isAdminSessionActive({ expiresAt: new Date(now + 30 * 60 * 1000).toISOString() }, now),
    true
  );
  assert.equal(
    isAdminSessionActive({ expiresAt: new Date(now).toISOString() }, now),
    false
  );
  assert.equal(
    isAdminSessionActive({ expiresAt: new Date(now - 1).toISOString() }, now),
    false
  );
  assert.equal(isAdminSessionActive({ expiresAt: "not-a-date" }, now), false);
  assert.equal(isAdminSessionActive(null, now), false);
});
