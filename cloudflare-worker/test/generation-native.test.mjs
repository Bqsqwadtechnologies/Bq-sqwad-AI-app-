import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { configureFirebaseEnv, uploadSupabaseStorageObject } from "../src/firebase.js";
import { generateImageNative, editImageNative, validateGeneratedImageBase64 } from "../src/generation-native.js";

const testPrivateKey = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey.export({ type: "pkcs8", format: "pem" });
configureFirebaseEnv({
  BQ_FIREBASE_SERVICE_ACCOUNT_JSON: JSON.stringify({ project_id: "bq-sqwad-test", client_email: "worker-tests@bq-sqwad-test.iam.gserviceaccount.com", private_key: testPrivateKey }),
  SUPABASE_URL: "https://storage-test.supabase.co",
  SUPABASE_SERVICE_ROLE_KEY: "test-service-role-key"
});
function installImageTestFetch(providerFetch) {
  const original = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    if (url.startsWith("https://oauth2.googleapis.com/token")) return new Response(JSON.stringify({ access_token: "test-firebase-token", expires_in: 3600 }), { status: 200 });
    if (url.startsWith("https://firestore.googleapis.com/v1/projects/bq-sqwad-test/")) return new Response(JSON.stringify({ name: "projects/bq-sqwad-test/databases/(default)/documents/users/user-test", fields: { hasEverPurchasedCredits: { booleanValue: true }, subscriptionPlan: { stringValue: "free" } } }), { status: 200 });
    return providerFetch(input, init);
  };
  return () => { globalThis.fetch = original; };
}


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


test("image generation ignores client-supplied prices and uses only the tiny preflight reservation before token settlement", async () => {
  let chargeArgs = null;
  let reversed = false;
  const restoreFetch = installImageTestFetch(async () => new Response(JSON.stringify({ error: { message: "test-stop-before-provider" } }), { status: 400 }));
  try {
    await assert.rejects(generateImageNative(
      { OPENAI_API_KEY: "test" }, { uid: "user-test" },
      { prompt: "A blue bird", creditCost: 0.00001 },
      async (...args) => { chargeArgs = args; return { reservationId: "test-reservation", amount: 3 }; },
      async () => {}, async () => { reversed = true; }
    ), /OpenAI HTTP 400/);
    assert.equal(chargeArgs[1], 0.00001);
    assert.equal(chargeArgs[2], "image_generation");
    assert.equal(reversed, true);
  } finally { restoreFetch(); }
});

test("image generation uses the website-selected allowed model, size, and quality", async () => {
  let providerPayload = null;
  let reversed = false;
  const restoreFetch = installImageTestFetch(async (_url, init) => {
    providerPayload = JSON.parse(init.body);
    return new Response(JSON.stringify({ error: { message: "test-stop-before-provider" } }), { status: 400 });
  });
  try {
    await assert.rejects(generateImageNative(
      { OPENAI_API_KEY: "test" }, { uid: "user-test" },
      { prompt: "A blue bird", model: "gpt-image-2.5-sunburst", size: "1024x1536", quality: "high" },
      async () => ({ reservationId: "test-reservation", amount: 3 }),
      async () => {}, async () => { reversed = true; }
    ), /OpenAI HTTP 400/);
    assert.deepEqual(providerPayload, {
      model: "gpt-image-2.5-sunburst", prompt: "A blue bird", size: "1024x1536", quality: "high"
    });
    assert.equal(reversed, true);
  } finally { restoreFetch(); }
});

test("image editing ignores client-supplied credit prices and delegates pricing to the Worker", async () => {
  let chargeArgs = null;
  let reversed = false;
  const restoreFetch = installImageTestFetch(async () => new Response(JSON.stringify({ error: { message: "test-stop-before-provider" } }), { status: 400 }));
  try {
    await assert.rejects(editImageNative(
      { OPENAI_API_KEY: "test" }, { uid: "user-test" },
      { prompt: "Change the sky", imageBase64: "aGVsbG8=", mimeType: "image/png", creditCost: 999999 },
      async (...args) => { chargeArgs = args; return { reservationId: "test-reservation", amount: args[1] }; },
      async () => {}, async () => { reversed = true; }, 2.75
    ), /OpenAI HTTP 400/);
    assert.equal(chargeArgs[1], 2.75);
    assert.equal(chargeArgs[2], "image_edit");
    assert.equal(reversed, true);
  } finally { restoreFetch(); }
});

test("generation uses the explicit server-resolved price rather than request body creditCost", async () => {
  let chargedAmount = null;
  const restoreFetch = installImageTestFetch(async () => new Response(JSON.stringify({ error: { message: "test-stop-before-provider" } }), { status: 400 }));
  try {
    await assert.rejects(generateImageNative(
      { OPENAI_API_KEY: "test" }, { uid: "user-test" },
      { prompt: "A blue bird", creditCost: 999999 },
      async (_uid, amount) => { chargedAmount = amount; return { reservationId: "test-reservation", amount }; },
      async () => {}, async () => {}, 2.75
    ), /OpenAI HTTP 400/);
    assert.equal(chargedAmount, 2.75);
  } finally { restoreFetch(); }
});

test("generated media uploads to private photo bucket and returns a short-lived signed URL", async () => {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    calls.push({ url, init });
    if (url.includes("/storage/v1/object/sign/photo/")) {
      return new Response(JSON.stringify({ signedURL: "/storage/v1/object/sign/photo/users/user-test/images/gen-1/generated.png?token=short-lived" }), { status: 200 });
    }
    if (url.includes("/storage/v1/object/photo/")) return new Response(JSON.stringify({ Key: "stored" }), { status: 200 });
    throw new Error("unexpected_storage_request");
  };
  try {
    const result = await uploadSupabaseStorageObject(
      "photo",
      "users/user-test/images/gen-1/generated.png",
      new Uint8Array([137, 80, 78, 71]),
      "image/png"
    );
    assert.equal(result.bucket, "photo");
    assert.equal(result.path, "users/user-test/images/gen-1/generated.png");
    assert.equal(result.expiresIn, 3600);
    assert.match(result.signedUrl, /^https:\/\/storage-test\.supabase\.co\//);
    assert.equal(calls.length, 2);
    assert.equal(calls[0].init.method, "POST");
    assert.equal(new Headers(calls[0].init.headers).get("x-upsert"), "false");
  } finally {
    globalThis.fetch = original;
  }
});

test("private media upload rejects unscoped paths and unapproved buckets", async () => {
  await assert.rejects(
    uploadSupabaseStorageObject("generations", "users/user-test/images/gen-1/a.png", new Uint8Array([1]), "image/png"),
    /private_media_invalid_input/
  );
  await assert.rejects(
    uploadSupabaseStorageObject("photo", "other-user/user-test/images/gen-1/a.png", new Uint8Array([1]), "image/png"),
    /private_media_invalid_input/
  );
});
