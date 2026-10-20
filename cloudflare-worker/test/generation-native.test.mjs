import test from "node:test";
import assert from "node:assert/strict";
import { generateImageNative, editImageNative, createVideoNative, validateGeneratedImageBase64 } from "../src/generation-native.js";

test("image generation rejects an empty prompt before reserving credits", async () => {
  let charged = false;
  await assert.rejects(
    generateImageNative(
      {},
      { uid: "user-test" },
      { prompt: "   " },
      async () => { charged = true; throw new Error("unexpected_charge"); },
      async () => {},
      async () => {}
    ),
    /image_prompt_required/
  );
  assert.equal(charged, false);
});

test("image editing rejects unsupported MIME types before reserving credits", async () => {
  let charged = false;
  await assert.rejects(
    editImageNative(
      {},
      { uid: "user-test" },
      { prompt: "Edit this picture", imageBase64: "aGVsbG8=", mimeType: "image/gif" },
      async () => { charged = true; throw new Error("unexpected_charge"); },
      async () => {},
      async () => {}
    ),
    error => error.httpStatus === 415 && /image_edit_type_unsupported/.test(error.message)
  );
  assert.equal(charged, false);
});

test("image editing rejects an empty image before reserving credits", async () => {
  let charged = false;
  await assert.rejects(
    editImageNative(
      {},
      { uid: "user-test" },
      { prompt: "Edit this picture", imageBase64: "  ", mimeType: "image/png" },
      async () => { charged = true; throw new Error("unexpected_charge"); },
      async () => {},
      async () => {}
    ),
    /image_edit_input_required/
  );
  assert.equal(charged, false);
});

test("generated image payload validation accepts PNG and rejects malformed bytes", () => {
  const validPngHeader = "iVBORw0KGgo=";
  assert.equal(validateGeneratedImageBase64(validPngHeader), true);
  assert.throws(() => validateGeneratedImageBase64("aGVsbG8="), /image_generation_invalid_image_data/);
  assert.throws(() => validateGeneratedImageBase64("%%%"), /image_generation_invalid_image_data/);
});


test("image generation ignores client-supplied credit prices and delegates pricing to the Worker", async () => {
  const originalFetch = globalThis.fetch;
  let chargeArgs = null;
  let reversed = false;
  globalThis.fetch = async () => new Response(JSON.stringify({ error: { message: "test-stop-before-provider" } }), { status: 400 });
  try {
    await assert.rejects(generateImageNative(
      { OPENAI_API_KEY: "test" }, { uid: "user-test" },
      { prompt: "A blue bird", creditCost: 0.00001 },
      async (...args) => { chargeArgs = args; return { reservationId: "test-reservation", amount: 3 }; },
      async () => {}, async () => { reversed = true; }
    ), /OpenAI HTTP 400/);
    assert.equal(chargeArgs[1], 0);
    assert.equal(chargeArgs[2], "image_generation");
    assert.equal(reversed, true);
  } finally { globalThis.fetch = originalFetch; }
});

test("image editing ignores client-supplied credit prices and delegates pricing to the Worker", async () => {
  const originalFetch = globalThis.fetch;
  let chargeArgs = null;
  let reversed = false;
  globalThis.fetch = async () => new Response(JSON.stringify({ error: { message: "test-stop-before-provider" } }), { status: 400 });
  try {
    await assert.rejects(editImageNative(
      { OPENAI_API_KEY: "test" }, { uid: "user-test" },
      { prompt: "Change the sky", imageBase64: "aGVsbG8=", mimeType: "image/png", creditCost: 999999 },
      async (...args) => { chargeArgs = args; return { reservationId: "test-reservation", amount: args[1] }; },
      async () => {}, async () => { reversed = true; }, 2.75
    ), /OpenAI HTTP 400/);
    assert.equal(chargeArgs[1], 2.75);
    assert.equal(chargeArgs[2], "image_generation");
    assert.equal(reversed, true);
  } finally { globalThis.fetch = originalFetch; }
});

test("video generation ignores client-supplied credit prices and delegates pricing to the Worker", async () => {
  const originalFetch = globalThis.fetch;
  let chargeArgs = null;
  let reversed = false;
  globalThis.fetch = async () => new Response(JSON.stringify({ error: { message: "test-stop-before-provider" } }), { status: 400 });
  try {
    await assert.rejects(createVideoNative(
      { OPENAI_API_KEY: "test" }, { uid: "user-test" },
      { prompt: "A blue bird flying", creditCost: 0 },
      async (...args) => { chargeArgs = args; return { reservationId: "test-reservation", amount: 3 }; },
      async () => {}, async () => { reversed = true; }
    ), /OpenAI HTTP 400/);
    assert.equal(chargeArgs[1], 0);
    assert.equal(chargeArgs[2], "video_generation");
    assert.equal(reversed, true);
  } finally { globalThis.fetch = originalFetch; }
});


test("generation uses the explicit server-resolved price rather than request body creditCost", async () => {
  const originalFetch = globalThis.fetch;
  let chargedAmount = null;
  globalThis.fetch = async () => new Response(JSON.stringify({ error: { message: "test-stop-before-provider" } }), { status: 400 });
  try {
    await assert.rejects(generateImageNative(
      { OPENAI_API_KEY: "test" }, { uid: "user-test" },
      { prompt: "A blue bird", creditCost: 999999 },
      async (_uid, amount) => { chargedAmount = amount; return { reservationId: "test-reservation", amount }; },
      async () => {}, async () => {}, 2.75
    ), /OpenAI HTTP 400/);
    assert.equal(chargedAmount, 2.75);
  } finally { globalThis.fetch = originalFetch; }
});
