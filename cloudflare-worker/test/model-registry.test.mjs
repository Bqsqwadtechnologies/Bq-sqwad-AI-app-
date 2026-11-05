import test from "node:test";
import assert from "node:assert/strict";
import { validateAiModel } from "../src/model-registry-native.js";

test("OpenAI model validation verifies the provider model ID with the server-side key", async () => {
  let calledUrl = "";
  let calledHeaders = {};
  globalThis.fetch = async (url, init) => {
    calledUrl = String(url);
    calledHeaders = init.headers;
    return new Response(JSON.stringify({ id: "gpt-6-luna", owned_by: "openai" }), { status: 200 });
  };
  const result = await validateAiModel(
    { OPENAI_API_KEY: "server-test-key" },
    { providerId: "openai", modelId: "gpt-6-luna", feature: "text-chat" }
  );
  assert.equal(result.ok, true);
  assert.equal(result.modelId, "gpt-6-luna");
  assert.match(calledUrl, /\/v1\/models\/gpt-6-luna$/);
  assert.equal(calledHeaders.Authorization, "Bearer server-test-key");
});

test("Gemini model validation verifies a registered model without placing its key in the URL", async () => {
  let calledUrl = "";
  let calledHeaders = {};
  globalThis.fetch = async (url, init) => {
    calledUrl = String(url);
    calledHeaders = init.headers;
    return new Response(JSON.stringify({ name: "models/gemini-test-model", displayName: "Gemini Test Model" }), { status: 200 });
  };
  const result = await validateAiModel(
    { GEMINI_API_KEY: "gemini-server-test-key" },
    { providerId: "gemini", modelId: "gemini-test-model", feature: "device-commands" }
  );
  assert.equal(result.ok, true);
  assert.equal(result.providerModelName, "Gemini Test Model");
  assert.match(calledUrl, /\/v1beta\/models\/gemini-test-model$/);
  assert.equal(calledHeaders["x-goog-api-key"], "gemini-server-test-key");
  assert.equal(new URL(calledUrl).search, "");
});

test("model validation rejects a provider incompatible with the feature before calling a provider", async () => {
  let called = false;
  globalThis.fetch = async () => {
    called = true;
    return new Response("{}", { status: 200 });
  };
  await assert.rejects(
    validateAiModel(
      { GEMINI_API_KEY: "test-key" },
      { providerId: "gemini", modelId: "gemini-test-model", feature: "image-generation" }
    ),
    /provider_not_supported_for_feature/
  );
  assert.equal(called, false);
});

test("model validation blocks OpenAI models outside the feature allowlist before provider access", async () => {
  let called = false;
  globalThis.fetch = async () => {
    called = true;
    return new Response("{}", { status: 200 });
  };
  await assert.rejects(
    validateAiModel(
      { OPENAI_API_KEY: "test-key" },
      { providerId: "openai", modelId: "gpt-6-luna", feature: "image-generation" }
    ),
    /openai_model_not_allowed_by_project_policy/
  );
  assert.equal(called, false);
});

test("model validation rejects unknown provider model IDs", async () => {
  let called = false;
  globalThis.fetch = async () => {
    called = true;
    return new Response(JSON.stringify({ error: { message: "The model does not exist or you do not have access to it." } }), { status: 404 });
  };
  await assert.rejects(
    validateAiModel(
      { OPENAI_API_KEY: "test-key" },
      { providerId: "openai", modelId: "gpt-missing-model", feature: "text-chat" }
    ),
    /openai_model_not_allowed_by_project_policy/
  );
  assert.equal(called, false);
});

test("provider model validation returns a bounded service error when the provider request times out", async () => {
  globalThis.fetch = async () => {
    const error = new Error("request timed out");
    error.name = "TimeoutError";
    throw error;
  };
  await assert.rejects(
    validateAiModel(
      { OPENAI_API_KEY: "server-test-key" },
      { providerId: "openai", modelId: "gpt-6-luna", feature: "text-chat" }
    ),
    error => error.httpStatus === 503 && /provider_model_validation_timeout/.test(error.message)
  );
});

test("legacy realtime model ID is rejected for both live features", async () => {
  for (const feature of ["live-voice", "live-camera"]) {
    await assert.rejects(
      validateAiModel(
        { OPENAI_API_KEY: "test-key" },
        { providerId: "openai", modelId: "gpt-realtime-2.1", feature }
      ),
      /openai_model_not_allowed_by_project_policy/
    );
  }
});


test("active token models do not require stored cost-rate fields", async () => {
  const { readFile } = await import("node:fs/promises");
  const source = await readFile(new URL("../src/model-registry-native.js", import.meta.url), "utf8");
  assert.doesNotMatch(source, /token_pricing_required_for_active_token_billing/);
});


test("model registry stores model identity only and writes replacement documents", async () => {
  const { readFile } = await import("node:fs/promises");
  const source = await readFile(new URL("../src/model-registry-native.js", import.meta.url), "utf8");
  for (const field of [
    "inputPriceUsdPerMillion", "outputPriceUsdPerMillion", "cachedInputPriceUsdPerMillion",
    "creditReferenceValueUsd", "customerMultiplier", "estimatedFeePercent",
    "providerCostUnit", "providerCostTrackingEnabled", "priceUsd"
  ]) assert.doesNotMatch(source, new RegExp(field));
  assert.match(source, /firestoreSet\("aiModelRegistry\/" \+ model\.id, model, false\)/);
  assert.match(source, /firestoreSet\("aiModelRegistry\/" \+ id, model, false\)/);
});
