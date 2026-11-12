import test from "node:test";
import assert from "node:assert/strict";
import {
  WELCOME_CREDIT_AMOUNT,
  WELCOME_CREDIT_POLICY_START,
  firebaseCreatedAtMillis,
  isEligibleForWelcomeCredits
} from "../src/welcome-credit.js";

test("new-account welcome grant is fixed at two credits", () => {
  assert.equal(WELCOME_CREDIT_AMOUNT, 2);
});

test("Firebase numeric and ISO creation timestamps are parsed", () => {
  const start = Date.parse(WELCOME_CREDIT_POLICY_START);
  assert.equal(firebaseCreatedAtMillis(String(start)), start);
  assert.equal(firebaseCreatedAtMillis(new Date(start).toISOString()), start);
  assert.equal(firebaseCreatedAtMillis("not-a-date"), 0);
});

test("only accounts created on or after the policy start are eligible", () => {
  const start = Date.parse(WELCOME_CREDIT_POLICY_START);
  assert.equal(isEligibleForWelcomeCredits(String(start - 1)), false);
  assert.equal(isEligibleForWelcomeCredits(String(start)), true);
  assert.equal(isEligibleForWelcomeCredits(String(start + 60_000)), true);
});
