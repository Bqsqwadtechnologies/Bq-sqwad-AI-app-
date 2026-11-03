import test from "node:test";
import assert from "node:assert/strict";
import { calculateDirectCreditCharge, priceOperation } from "../dynamic-billing.js";

test("configured feature credits are final prices without markup or provider USD", () => {
  const quote = calculateDirectCreditCharge({ credits: 2.75 });
  assert.equal(quote.credits, 2.75);
  assert.equal(quote.reservedCredits, 2.75);
  assert.equal(quote.providerCostUsd, 0);
  assert.equal(quote.sellingPriceUsd, 0);
  assert.equal(quote.profitMarkup, 0);
  assert.equal(quote.feeRateApplied, 0);
  assert.equal(quote.estimateSource, "direct_credit_price");
});

test("zero is a valid direct feature price", () => {
  assert.equal(calculateDirectCreditCharge({ credits: 0 }).credits, 0);
});

test("negative or non-finite direct prices are rejected", () => {
  assert.throws(() => calculateDirectCreditCharge({ credits: -1 }), /direct_credit_price_invalid/);
  assert.throws(() => calculateDirectCreditCharge({ credits: Number.NaN }), /direct_credit_price_invalid/);
});

test("legacy capability estimates are used as final credits without a multiplier", async () => {
  const quote = await priceOperation("business_plan_builder", 4.5);
  assert.equal(quote.credits, 4.5);
  assert.equal(quote.reservedCredits, 4.5);
  assert.equal(quote.profitMarkup, 0);
});
