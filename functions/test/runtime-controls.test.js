import test from "node:test";
import assert from "node:assert/strict";
import { assertControlEnabled, assertPlanAllowed } from "../runtime-controls.js";

test("runtime control rejects disabled and kill-switched capabilities", () => {
  assert.throws(() => assertControlEnabled({ enabled: false }, "x"), /capability_disabled/);
  assert.throws(() => assertControlEnabled({ enabled: true, killSwitch: true }, "x"), /capability_disabled/);
});

test("runtime control accepts enabled rollout", () => {
  assert.deepEqual(assertControlEnabled({ enabled: true, killSwitch: false, rolloutPercent: 100 }, "x").enabled, true);
});

test("plan gates only apply when the admin configured allowed plans", () => {
  assert.doesNotThrow(() => assertPlanAllowed({ allowedPlanIds: [] }, "free"));
  assert.doesNotThrow(() => assertPlanAllowed({ allowedPlanIds: ["pro"] }, "pro"));
  assert.throws(() => assertPlanAllowed({ allowedPlanIds: ["pro"] }, "free"), /plan_not_entitled/);
});
