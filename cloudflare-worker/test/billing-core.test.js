import test from "node:test";
import assert from "node:assert/strict";
import { computeFixedCredits, round5 } from "../src/billing-core.js";

test("fixed customer prices are rounded to five decimal places", () => {
  assert.equal(computeFixedCredits(1.7), 1.7);
  assert.equal(computeFixedCredits(0.125678), 0.12568);
  assert.equal(computeFixedCredits(0), 0);
});
test("invalid fixed prices fail to zero rather than creating a charge", () => {
  assert.equal(computeFixedCredits(-1), 0);
  assert.equal(computeFixedCredits(Number.NaN), 0);
  assert.equal(computeFixedCredits(Number.POSITIVE_INFINITY), 0);
});
test("round5 preserves exact zero and small fractional values", () => {
  assert.equal(round5(0), 0);
  assert.equal(round5(0.00001), 0.00001);
});
