import test from "node:test";
import assert from "node:assert/strict";
import { calculateLiveHeartbeatCharge } from "../src/live-billing.js";

test("normal voice heartbeat charges only the live voice rate", () => {
  assert.equal(calculateLiveHeartbeatCharge(
    { unit: "second", creditsPerUnit: 0.045 },
    { unit: "second", creditsPerUnit: 0.0204337 },
    30,
    false,
  ), 1.35);
});

test("Smart voice heartbeat adds transcription for the same elapsed seconds", () => {
  const charged = calculateLiveHeartbeatCharge(
    { unit: "second", creditsPerUnit: 0.045 },
    { unit: "second", creditsPerUnit: 0.0204337 },
    30,
    true,
  );
  assert.ok(Math.abs(charged - 1.963011) < 1e-12);
});

test("Smart camera heartbeat adds transcription to the camera rate", () => {
  const charged = calculateLiveHeartbeatCharge(
    { unit: "second", creditsPerUnit: 0.09 },
    { unit: "second", creditsPerUnit: 0.0204337 },
    30,
    true,
  );
  assert.ok(Math.abs(charged - 3.313011) < 1e-12);
});
