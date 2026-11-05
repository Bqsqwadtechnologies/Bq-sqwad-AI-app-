import test from "node:test";
import assert from "node:assert/strict";
import { recoverStreamFailure } from "../src/stream-delivery.js";

test("streamed answer followed by unavailable usage is completed and refunded", async () => {
  let reverseCalls = 0;
  const emitted = await recoverStreamFailure({
    deliveredChars: "Hello there.".length,
    providerId: "openai",
    modelId: "gpt-6-luna",
    reverseCredits: async () => { reverseCalls += 1; },
    notifyAdmin: async () => {}
  });
  assert.equal(emitted.type, "completed");
  assert.equal(emitted.creditsCharged, 0);
  assert.equal(emitted.settlement, "failed_after_delivery");
  assert.equal(reverseCalls, 1);
});

test("provider failure before any text remains an error", async () => {
  let reverseCalls = 0;
  const emitted = await recoverStreamFailure({
    deliveredChars: 0,
    reverseCredits: async () => { reverseCalls += 1; },
    notifyAdmin: async () => {},
    safeError: "service_temporarily_unavailable"
  });
  assert.equal(emitted.type, "error");
  assert.equal(emitted.message, "service_temporarily_unavailable");
  assert.equal(reverseCalls, 1);
});
