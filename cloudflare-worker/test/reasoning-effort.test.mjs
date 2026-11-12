import test from "node:test";
import assert from "node:assert/strict";
import { resolveTextReasoningEffort } from "../src/providers.js";

test("greetings use no reasoning tokens on GPT-6 Luna", () => {
  assert.equal(resolveTextReasoningEffort("hello"), "none");
  assert.equal(resolveTextReasoningEffort("How are you?"), "none");
});

test("routine chat uses low reasoning effort", () => {
  assert.equal(resolveTextReasoningEffort("How do I change my profile picture?"), "low");
});

test("complex technical requests retain medium reasoning effort", () => {
  assert.equal(resolveTextReasoningEffort("Debug this Kotlin code and investigate why the app crashes."), "medium");
});

test("non-chat capabilities use low reasoning effort", () => {
  assert.equal(resolveTextReasoningEffort("hello", "image_generation"), "low");
});
