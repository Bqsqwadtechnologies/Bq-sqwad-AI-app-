import test from "node:test";
import assert from "node:assert/strict";
import { computeFixedCredits, round7 } from "../src/billing-core.js";

test("fixed customer prices are rounded to seven decimal places", () => {
  assert.equal(computeFixedCredits(1.7), 1.7);
  assert.equal(computeFixedCredits(0.12567891), 0.1256789);
  assert.equal(computeFixedCredits(0), 0);
});
test("invalid fixed prices fail to zero rather than creating a charge", () => {
  assert.equal(computeFixedCredits(-1), 0);
  assert.equal(computeFixedCredits(Number.NaN), 0);
  assert.equal(computeFixedCredits(Number.POSITIVE_INFINITY), 0);
});
test("round7 preserves exact zero and seven-decimal fractional values", () => {
  assert.equal(round7(0), 0);
  assert.equal(round7(0.0000001), 0.0000001);
});
