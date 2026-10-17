import test from "node:test";
import assert from "node:assert/strict";
import { calculateDynamicCharge } from "../dynamic-billing.js";

const base = {
  enabled: true,
  version: 7,
  profitMarkup: 0.8,
  feeRate: 0.15,
  applyFeeToUsage: false,
  creditValueUsd: 0.07999,
  reservationSafetyMargin: 0.2,
  minimumCredits: 0.01,
  maximumCredits: 100000,
  capabilityRates: {}
};

test("dynamic billing applies the configured 80 percent markup and converts to credits", () => {
  const quote = calculateDynamicCharge({ providerCostUsd: 1, config: base, estimated: true });
  assert.equal(quote.sellingPriceUsd, 1.8);
  assert.equal(quote.feeRateApplied, 0);
  assert.equal(quote.pricingVersion, 7);
  assert.equal(quote.credits, 22.51);
  assert.equal(quote.estimated, true);
});

test("configured fee allowance is applied only when enabled", () => {
  const quote = calculateDynamicCharge({
    providerCostUsd: 1,
    config: { ...base, applyFeeToUsage: true },
    estimated: true
  });
  assert.equal(quote.feeRateApplied, 0.15);
  assert.equal(quote.sellingPriceUsd, 1.8 / 0.85);
});

test("invalid fee rates are rejected", () => {
  assert.throws(
    () => calculateDynamicCharge({ providerCostUsd: 1, config: { ...base, feeRate: 1 }, estimated: true }),
    /billing_fee_rate_out_of_range/
  );
});

test("negative provider costs are rejected", () => {
  assert.throws(
    () => calculateDynamicCharge({ providerCostUsd: -1, config: base, estimated: true }),
    /billing_provider_cost_invalid/
  );
});

test("credit value must be positive", () => {
  assert.throws(
    () => calculateDynamicCharge({ providerCostUsd: 1, config: { ...base, creditValueUsd: 0 }, estimated: true }),
    /billing_credit_value_invalid/
  );
});
