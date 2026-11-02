import test from "node:test";
import assert from "node:assert/strict";
import {
  hasAdultTrainingConsent,
  isTrainingChatEligible,
  sanitizeText,
} from "../src/training-pipeline-native.js";

test("training requires explicit consent and the server-owned adult gate", () => {
  assert.equal(hasAdultTrainingConsent({
    trainingConsent: true,
    trainingAgeGatePassed: true,
    trainingAdultGatePassed: true,
  }), true);
  assert.equal(hasAdultTrainingConsent({
    trainingConsent: true,
    trainingAgeGatePassed: true,
    trainingAdultGatePassed: false,
  }), false);
  assert.equal(hasAdultTrainingConsent({
    trainingConsent: true,
    trainingAgeGatePassed: true,
  }), false);
});

test("training excludes secret, private, minor and non-personal workspaces", () => {
  assert.equal(isTrainingChatEligible({ workspace: "main_chat" }), true);
  for (const chat of [
    { private: true }, { privateChat: true }, { secretChat: true },
    { isSecret: true }, { encrypted: true }, { isMinor: true },
    { workspace: "business_ai" }, { workspace: "organization" },
    { workspace: "team_chat" }, { workspace: "secret" },
  ]) assert.equal(isTrainingChatEligible(chat), false);
});

test("training sanitizer redacts direct identifiers and credentials", () => {
  const cleaned = sanitizeText("Email me at isaac@example.com, BVN: 12345678901, token sk-abcdefghijklmnopqrstuvwxyz");
  assert.equal(cleaned.includes("isaac@example.com"), false);
  assert.equal(cleaned.includes("12345678901"), false);
  assert.equal(cleaned.includes("sk-abcdefghijklmnopqrstuvwxyz"), false);
});
