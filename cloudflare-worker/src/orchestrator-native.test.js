import test from "node:test";
import assert from "node:assert/strict";
import { estimateCostUsd, shouldFallback, settlementLedgerEntry } from "./orchestrator-native.js";

test("Gemini fallback is eligible for provider throttling and server errors", () => {
  assert.equal(shouldFallback(new Error("provider_http_429: rate limited")), true);
  assert.equal(shouldFallback(new Error("provider_http_503: unavailable")), true);
});

test("Gemini fallback is not used for authentication or validation errors", () => {
  assert.equal(shouldFallback(new Error("provider_http_401: unauthorized")), false);
  assert.equal(shouldFallback(new Error("invalid_request: validation failed")), false);
});

test("fallback cost guard understands canonical USD-per-token pricing", () => {
  const cost = estimateCostUsd({
    inputPriceUsdPerToken: 0.000001,
    outputPriceUsdPerToken: 0.000002
  }, 1000, 500);
  assert.equal(cost, 0.002);
});

test("settlement ledger records the provider that actually answered", () => {
  const entry = settlementLedgerEntry({
    providerId: "gemini",
    modelId: "gemini-2.5-flash",
    route: "fallback",
    inputTokens: 100,
    outputTokens: 25,
    credits: 0.01
  });
  assert.equal(entry.provider, "gemini");
  assert.equal(entry.route, "fallback");
  assert.equal(entry.credits, 0.01);
});
