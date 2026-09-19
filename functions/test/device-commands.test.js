import test from "node:test";
import assert from "node:assert/strict";
import { parseDeviceActions } from "../device-commands.js";

test("device parser allowlists actions", () => {
  const actions = parseDeviceActions(JSON.stringify({
    actions: [
      { type: "torch_on" },
      { type: "set_brightness", value: 0.7 },
      { type: "run_shell_command" }
    ]
  }));
  assert.deepEqual(actions, [
    { type: "torch_on", value: null },
    { type: "set_brightness", value: 0.7 }
  ]);
});

test("invalid device JSON is safe", () => {
  assert.deepEqual(parseDeviceActions("not-json"), []);
});
