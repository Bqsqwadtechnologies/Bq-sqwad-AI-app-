import test from "node:test";
import assert from "node:assert/strict";
import { generateImageNative, editImageNative } from "../src/generation-native.js";

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
