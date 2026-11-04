import test from "node:test";
import assert from "node:assert/strict";
import { calculateTokenCost, allocateReservationRefund, normalizeProviderUsage, pricingFeatureAliases } from "../src/token-billing-native.js";

test("token billing charges direct input, cached and output credit rates", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1000, cachedInputTokens: 400, outputTokens: 500 },
    pricing: {
      inputPriceUsdPerMillion: 1, cachedInputPriceUsdPerMillion: 0.25, outputPriceUsdPerMillion: 2,
      sharedCreditMetering: {
        creditsPerInputToken: 0.0001, creditsPerCachedInputToken: 0.000025, creditsPerOutputToken: 0.0002,
        minimumCredits: 0, precision: 7, conversionRate: 1,
      },
    },
  });
  assert.equal(result.inputCostUsd, 0.0006);
  assert.equal(result.cachedInputCostUsd, 0.0001);
  assert.equal(result.outputCostUsd, 0.001);
  assert.ok(Math.abs(result.providerCostUsd - 0.0017) < 1e-12);
  assert.equal(result.customerCredits, 0.17);
  assert.equal(result.customerMultiplier, 1);
  assert.equal(result.estimatedFeePercent, 0);
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

test("website credit prices do not require USD telemetry rates to calculate customer credits", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1000, cachedInputTokens: 400, outputTokens: 500 },
    pricing: {
      sharedCreditMetering: {
        creditsPerInputToken: 0.00003,
        creditsPerOutputToken: 0.00024,
        creditsPerCachedInputToken: 0.000003,
        minimumCredits: 0,
        precision: 7,
        conversionRate: 1,
      },
      sharedModelMultiplier: 1,
    },
    creditReferenceValueUsd: 0.1199,
  });
  assert.equal(result.customerCredits, 0.1392);
  assert.equal(result.providerCostUsd, 0);
});

test("native Main Chat uses the model-specific direct token rates without a multiplier", () => {
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
  assert.equal(result.customerCredits, 0.15);
});

test("shared website meter never applies a minimum charge", () => {
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
  assert.equal(tiny.customerCredits, 0);
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

test("token billing rejects missing rates but permits explicitly configured zero rates", () => {
  assert.throws(
    () => calculateTokenCost({
      usage: { inputTokens: 10, outputTokens: 10 },
      pricing: {},
      creditReferenceValueUsd: 0.1,
      multiplier: 2,
    }),
    /token_pricing_not_configured/,
  );
  const free = calculateTokenCost({
    usage: { inputTokens: 10, outputTokens: 10 },
    pricing: { sharedCreditMetering: {
      creditsPerInputToken: 0, creditsPerCachedInputToken: 0, creditsPerOutputToken: 0,
      minimumCredits: 0, precision: 7, conversionRate: 1,
    } },
  });
  assert.equal(free.providerCostUsd, 0);
  assert.equal(free.customerCredits, 0);
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


test("team AI resolves Main Chat token rules without using a separate team price", () => {
  const aliases = pricingFeatureAliases("team_chat");
  assert.equal(aliases.has("team_chat"), true);
  assert.equal(aliases.has("chat"), true);
  assert.equal(aliases.has("text-chat"), true);
  assert.equal(pricingFeatureAliases("chat").has("team_chat"), false);
});

test("team chat and deep research are eligible for shared model token metering", async () => {
  const { readFile } = await import("node:fs/promises");
  const source = await readFile(new URL("../src/token-billing-native.js", import.meta.url), "utf8");
  assert.match(source, /"team_chat"[^\]]*"deep_research"/s);
});

test("token pricing selects free and paid rules independently and prefers the exact class", async () => {
  const { matchesCustomerClass } = await import("../src/token-billing-native.js");
  assert.equal(matchesCustomerClass({ customerClass: "free" }, "free"), true);
  assert.equal(matchesCustomerClass({ customerClass: "free" }, "paid"), false);
  assert.equal(matchesCustomerClass({ customerClass: "paid" }, "paid"), true);
  assert.equal(matchesCustomerClass({ customerClass: "all" }, "free"), true);
  assert.equal(matchesCustomerClass({}, "paid"), true);
});

test("provider-reported audio and image tokens use their own Admin Live Credit Pricing rates", () => {
  const result = calculateTokenCost({
    usage: {
      inputTokens: 1000,
      cachedInputTokens: 200,
      outputTokens: 500,
      inputImageTokens: 300,
      outputAudioTokens: 100
    },
    pricing: {
      sharedCreditMetering: {
        creditsPerInputToken: 0.0001,
        creditsPerCachedInputToken: 0.00002,
        creditsPerOutputToken: 0.0002,
        creditsPerInputImageToken: 0.0003,
        creditsPerOutputAudioToken: 0.0004,
        minimumCredits: 0,
        precision: 7,
        conversionRate: 1
      }
    }
  });
  // Text: (500 uncached × .0001) + (200 cached × .00002) + (400 output × .0002)
  // Modalities: (300 image × .0003) + (100 audio × .0004) = .13
  assert.equal(result.customerCredits, 0.264);
  assert.equal(result.inputImageTokens, 300);
  assert.equal(result.outputAudioTokens, 100);
});

test("OpenAI usage normalization extracts cached, audio and image token details", () => {
  const usage = normalizeProviderUsage("openai", {
    input_tokens: 1200,
    output_tokens: 500,
    input_token_details: { cached_tokens: 100, audio_tokens: 300, image_tokens: 200 },
    output_token_details: { audio_tokens: 150, image_tokens: 50, reasoning_tokens: 40 }
  });
  assert.equal(usage.cachedInputTokens, 100);
  assert.equal(usage.inputAudioTokens, 300);
  assert.equal(usage.inputImageTokens, 200);
  assert.equal(usage.outputAudioTokens, 150);
  assert.equal(usage.outputImageTokens, 50);
  assert.equal(usage.reasoningTokens, 40);
});

test("Gemini usage normalization extracts modality token counts", () => {
  const usage = normalizeProviderUsage("gemini", {
    promptTokenCount: 1000,
    candidatesTokenCount: 200,
    promptTokensDetails: [
      { modality: "TEXT", tokenCount: 500 },
      { modality: "IMAGE", tokenCount: 300 },
      { modality: "AUDIO", tokenCount: 200 }
    ],
    candidatesTokensDetails: [
      { modality: "TEXT", tokenCount: 150 },
      { modality: "AUDIO", tokenCount: 50 }
    ]
  });
  assert.equal(usage.inputImageTokens, 300);
  assert.equal(usage.inputAudioTokens, 200);
  assert.equal(usage.outputAudioTokens, 50);
});
