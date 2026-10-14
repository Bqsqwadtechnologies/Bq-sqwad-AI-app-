import test from "node:test";
import assert from "node:assert/strict";
import { assertSuperAdminIdentity } from "../src/index.js";

const env = { SUPER_ADMIN_EMAIL: "owner@example.com" };

test("rejects a normal signed-in user with HTTP 403 semantics", () => {
  assert.throws(
    () => assertSuperAdminIdentity({ uid: "user-1", email: "person@example.com", emailVerified: true }, env),
    error => error.httpStatus === 403 && error.safeError === "admin_access_required"
  );
});

test("rejects an unverified owner email", () => {
  assert.throws(
    () => assertSuperAdminIdentity({ uid: "owner-1", email: "owner@example.com", emailVerified: false }, env),
    error => error.httpStatus === 403
  );
});

test("allows the verified configured owner", () => {
  const owner = { uid: "owner-1", email: "owner@example.com", emailVerified: true };
  assert.equal(assertSuperAdminIdentity(owner, env), owner);
});

test("rejects an admin-shaped claim when the owner environment is not configured", () => {
  assert.throws(
    () => assertSuperAdminIdentity({ uid: "delegated-1", email: "staff@example.com", emailVerified: true, customAttributes: { role: "admin" } }, {}),
    error => error.httpStatus === 403
  );
});
