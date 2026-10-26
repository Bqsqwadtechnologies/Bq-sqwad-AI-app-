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

test("native Main Chat follows website per-token metering instead of stale USD conversion", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1000, cachedInputTokens: 400, outputTokens: 500 },
    pricing: {
      inputPriceUsdPerMillion: 1,
      cachedInputPriceUsdPerMillion: 0.25,
      outputPriceUsdPerMillion: 2,
      sharedCreditMetering: {
        creditsPerInputToken: 0.00003,
        creditsPerOutputToken: 0.00024,
        creditsPerCachedInputToken: 0.000003,
        minimumCredits: 0.002,
        fallbackCredits: 0.05,
        precision: 4,
        conversionRate: 1,
      },
      sharedModelMultiplier: 1,
    },
    creditReferenceValueUsd: 0.1,
    multiplier: 2,
  });
  // (600 uncached × .00003) + (400 cached × .000003) + (500 output × .00024)
  // = 0.1392 credits. Provider USD rates must not override the shared website rates.
  assert.equal(result.customerCredits, 0.1392);
});

test("native Main Chat applies configured model weight to per-token credits", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1000, outputTokens: 500 },
    pricing: {
      inputPriceUsdPerMillion: 1,
      outputPriceUsdPerMillion: 2,
      sharedCreditMetering: {
        creditsPerInputToken: 0.00003,
        creditsPerOutputToken: 0.00024,
        creditsPerCachedInputToken: 0.000003,
        minimumCredits: 0.002,
        fallbackCredits: 0.05,
        precision: 4,
        conversionRate: 1,
      },
      sharedModelMultiplier: 3,
    },
    creditReferenceValueUsd: 0.1,
    multiplier: 2,
  });
  assert.equal(result.customerCredits, 0.45);
});

test("shared website meter applies minimum only to nonzero usage and never overrides token rates", () => {
  const pricing = {
    inputPriceUsdPerMillion: 1,
    outputPriceUsdPerMillion: 2,
    cachedInputPriceUsdPerMillion: 0.25,
    sharedCreditMetering: {
      creditsPerInputToken: 0.00003,
      creditsPerCachedInputToken: 0.000003,
      creditsPerOutputToken: 0.00024,
      minimumCredits: 0.002,
      precision: 4,
      conversionRate: 1,
    },
    sharedModelMultiplier: 1,
  };
  const empty = calculateTokenCost({
    usage: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0 },
    pricing,
    creditReferenceValueUsd: 0.1199,
    multiplier: 7,
  });
  assert.equal(empty.customerCredits, 0);

  const tiny = calculateTokenCost({
    usage: { inputTokens: 1, cachedInputTokens: 0, outputTokens: 0 },
    pricing,
    creditReferenceValueUsd: 0.1199,
    multiplier: 7,
  });
  assert.equal(tiny.customerCredits, 0.002);
});

test("shared website meter rejects invalid admin metering instead of charging unpredictably", () => {
  assert.throws(
    () => calculateTokenCost({
      usage: { inputTokens: 100, cachedInputTokens: 0, outputTokens: 100 },
      pricing: {
        inputPriceUsdPerMillion: 1,
        outputPriceUsdPerMillion: 2,
        sharedCreditMetering: {
          creditsPerInputToken: -1,
          creditsPerCachedInputToken: 0,
          creditsPerOutputToken: 0.00024,
          precision: 4,
          conversionRate: 1,
        },
      },
      creditReferenceValueUsd: 0.1199,
      multiplier: 7,
    }),
    /shared_token_metering_invalid/,
  );
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
