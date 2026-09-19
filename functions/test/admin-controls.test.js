import test from "node:test";
import assert from "node:assert/strict";
import { ADVANCED_ADMIN_OPTIONS, CORE_CAPABILITY_OPTIONS, ADMIN_CONTROL_SECTIONS } from "../advanced-admin.js";

test("canonical Admin Control Center keeps exactly 40 advanced controls", () => {
  assert.equal(ADVANCED_ADMIN_OPTIONS.length, 40);
  assert.equal(new Set(ADVANCED_ADMIN_OPTIONS.map(([id]) => id)).size, 40);
});

test("core capability controls are distinct and routable", () => {
  assert.equal(CORE_CAPABILITY_OPTIONS.length, 9);
  const ids = CORE_CAPABILITY_OPTIONS.map(([id]) => id);
  assert.equal(new Set(ids).size, ids.length);
  assert.ok(ids.includes("capability_chat"));
  assert.ok(ids.includes("capability_device_commands"));
  assert.ok(ids.includes("capability_web_search"));
});

test("Admin dashboard exposes the complete section model", () => {
  assert.equal(ADMIN_CONTROL_SECTIONS.length, 27);
  assert.ok(ADMIN_CONTROL_SECTIONS.some(([id]) => id === "emergency"));
  assert.ok(ADMIN_CONTROL_SECTIONS.some(([id]) => id === "security"));
  assert.ok(ADMIN_CONTROL_SECTIONS.some(([id]) => id === "payments"));
});
