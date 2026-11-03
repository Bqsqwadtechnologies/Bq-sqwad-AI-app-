import test from "node:test";
import assert from "node:assert/strict";
import { allocateReservationRefund, calculateTokenCost } from "../src/token-billing-native.js";

const CREDIT_VALUE_USD = 11.99 / 100;

test("refund restores a partial reservation to the original balance buckets", () => {
  const result = allocateReservationRefund({
    fromPermanent: 4,
    fromSubscription: 3,
    fromTrial: 2,
    fromDaily: 1
  }, 5.5);

  assert.deepEqual(result, {
    refundPermanent: 4,
    refundSubscription: 1.5,
    refundTrial: 0,
    refundDaily: 0
  });
  assert.equal(Object.values(result).reduce((sum, value) => sum + value, 0), 5.5);
});

test("refund can restore all original buckets without exceeding reservation allocations", () => {
  const reservation = {
    fromPermanent: 0.25,
    fromSubscription: 0.5,
    fromTrial: 0.75,
    fromDaily: 1
  };
  const result = allocateReservationRefund(reservation, 2.5);
  assert.deepEqual(result, {
    refundPermanent: 0.25,
    refundSubscription: 0.5,
    refundTrial: 0.75,
    refundDaily: 1
  });
});

test("refund allocation fails closed when the requested refund exceeds its source buckets", () => {
  assert.throws(
    () => allocateReservationRefund({ fromPermanent: 1, fromSubscription: 1 }, 2.01),
    error => error?.message === "credit_reservation_refund_mismatch"
  );
  assert.throws(
    () => allocateReservationRefund({ fromPermanent: Number.NaN }, 0.5),
    error => error?.message === "credit_reservation_refund_mismatch"
  );
});

test("token charges use final direct per-token credit prices", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1_000_000, outputTokens: 1_000_000, cachedInputTokens: 0 },
    pricing: { sharedCreditMetering: {
      creditsPerInputToken: 0.000001, creditsPerCachedInputToken: 0.0000001, creditsPerOutputToken: 0.000002,
      minimumCredits: 0, precision: 7, conversionRate: 1,
    }, profitMarkupPercent: 600 },
    multiplier: 8.4,
  });
  assert.equal(result.customerCredits, 3);
  assert.equal(result.customerMultiplier, 1);
  assert.equal(result.estimatedFeePercent, 0);
});
