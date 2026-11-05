import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { calculateDirectCreditCharge } from "../dynamic-billing.js";

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

test("Functions fixed charges read the canonical generation credit rules and unknown features fail closed", async () => {
  const source = await readFile(new URL("../dynamic-billing.js", import.meta.url), "utf8");
  assert.match(source, /collection\("generationCostRules"\)/);
  assert.match(source, /where\("operation", "==", operation\)/);
  assert.match(source, /if \(!operation\) throw new Error\("generation_credit_rule_missing"\)/);
  assert.match(source, /token_metering_required/);
  assert.doesNotMatch(source, /calculateDirectCreditCharge\(\{ credits: finalCreditPrice \}\)/);
});
