import test from "node:test";
import assert from "node:assert/strict";
import { validateAiModel } from "../src/model-registry-native.js";

test("OpenAI model validation verifies the provider model ID with the server-side key", async () => {
  let calledUrl = "";
  let calledHeaders = {};
  globalThis.fetch = async (url, init) => {
    calledUrl = String(url);
    calledHeaders = init.headers;
    return new Response(JSON.stringify({ id: "gpt-test-model", owned_by: "openai" }), { status: 200 });
  };
  const result = await validateAiModel(
    { OPENAI_API_KEY: "server-test-key" },
    { providerId: "openai", modelId: "gpt-test-model", feature: "text-chat" }
  );
  assert.equal(result.ok, true);
  assert.equal(result.modelId, "gpt-test-model");
  assert.match(calledUrl, /\/v1\/models\/gpt-test-model$/);
  assert.equal(calledHeaders.Authorization, "Bearer server-test-key");
});

test("Gemini model validation verifies a registered Gemini model", async () => {
  let calledUrl = "";
  globalThis.fetch = async url => {
    calledUrl = String(url);
    return new Response(JSON.stringify({ name: "models/gemini-test-model", displayName: "Gemini Test Model" }), { status: 200 });
  };
  const result = await validateAiModel(
    { GEMINI_API_KEY: "gemini-server-test-key" },
    { providerId: "gemini", modelId: "gemini-test-model", feature: "device-commands" }
  );
  assert.equal(result.ok, true);
  assert.equal(result.providerModelName, "Gemini Test Model");
  assert.match(calledUrl, /\/v1beta\/models\/gemini-test-model\?key=gemini-server-test-key$/);
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

test("model validation rejects model IDs that do not match a feature's API family", async () => {
  let called = false;
  globalThis.fetch = async () => {
    called = true;
    return new Response("{}", { status: 200 });
  };
  await assert.rejects(
    validateAiModel(
      { OPENAI_API_KEY: "test-key" },
      { providerId: "openai", modelId: "gpt-test-model", feature: "image-generation" }
    ),
    /model_id_does_not_match_feature/
  );
  assert.equal(called, false);
});

test("model validation rejects unknown provider model IDs", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    error: { message: "The model does not exist or you do not have access to it." }
  }), { status: 404 });
  await assert.rejects(
    validateAiModel(
      { OPENAI_API_KEY: "test-key" },
      { providerId: "openai", modelId: "gpt-missing-model", feature: "text-chat" }
    ),
    /model does not exist/
  );
});
