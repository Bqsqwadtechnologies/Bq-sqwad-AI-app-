import test from "node:test";
import assert from "node:assert/strict";
import { calculateTokenCost, allocateReservationRefund } from "../src/token-billing-native.js";

test("token billing prices uncached input, cached input, and output separately", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1000, cachedInputTokens: 400, outputTokens: 500 },
    pricing: {
      inputPriceUsdPerMillion: 1,
      cachedInputPriceUsdPerMillion: 0.25,
      outputPriceUsdPerMillion: 2,
      profitMarkupPercent: 100,
    },
    creditReferenceValueUsd: 0.1,
    multiplier: 2,
  });
  assert.equal(result.inputCostUsd, 0.0006);
  assert.equal(result.cachedInputCostUsd, 0.0001);
  assert.equal(result.outputCostUsd, 0.001);
  assert.ok(Math.abs(result.providerCostUsd - 0.0017) < 1e-12);
  assert.ok(Math.abs(result.customerCredits - 0.034) < 1e-12);
});

test("token billing rejects missing or zero provider rates", () => {
  assert.throws(
    () => calculateTokenCost({
      usage: { inputTokens: 10, outputTokens: 10 },
      pricing: { inputPriceUsdPerMillion: 0, outputPriceUsdPerMillion: 0 },
      creditReferenceValueUsd: 0.1,
      multiplier: 2,
    }),
    /token_pricing_not_configured/,
  );
});

test("reservation refunds restore only the original reserved buckets", () => {
  assert.deepEqual(
    allocateReservationRefund({
      fromPermanent: 3,
      fromSubscription: 2,
      fromTrial: 1,
      fromDaily: 4,
    }, 6),
    { refundPermanent: 3, refundSubscription: 2, refundTrial: 1, refundDaily: 0 },
  );
});

test("reservation refund rejects an amount larger than the original reservation", () => {
  assert.throws(
    () => allocateReservationRefund({ fromPermanent: 1, fromSubscription: 2 }, 4),
    /credit_reservation_refund_mismatch/,
  );
});
