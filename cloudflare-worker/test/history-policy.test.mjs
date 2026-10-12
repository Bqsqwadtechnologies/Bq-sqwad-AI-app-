import test from "node:test";
import assert from "node:assert/strict";
import { normalizeHistoryMessages } from "../src/history-policy.js";

test("history validation preserves message text without truncation", () => {
  const text = "A".repeat(19000);
  const messages = normalizeHistoryMessages([{ id: "m1", mine: true, text }]);
  assert.equal(messages[0].text, text);
});

test("history validation rejects more than 100 messages", () => {
  const messages = Array.from({ length: 101 }, (_, i) => ({ id: String(i), mine: true, text: "message" }));
  assert.throws(() => normalizeHistoryMessages(messages), error => error.httpStatus === 413 && error.message === "history_message_limit_exceeded");
});

test("history validation rejects oversized individual messages and payloads", () => {
  assert.throws(() => normalizeHistoryMessages([{ text: "x".repeat(20001) }]), /history_message_too_large/);
  const messages = Array.from({ length: 100 }, (_, i) => ({ id: String(i), text: "x".repeat(8000) }));
  assert.throws(() => normalizeHistoryMessages(messages), error => error.httpStatus === 413 && error.message === "history_payload_too_large");
});
