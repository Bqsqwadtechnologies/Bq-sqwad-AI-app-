import test from "node:test";
import assert from "node:assert/strict";
import { callOpenAI, callGemini } from "../src/providers.js";

test("OpenAI vision request includes each supplied image", async () => {
  let payload;
  globalThis.fetch = async (_url, init) => {
    payload = JSON.parse(init.body);
    return new Response(JSON.stringify({ output_text: "Two images received." }), { status: 200 });
  };

  const response = await callOpenAI(
    { OPENAI_API_KEY: "test-key" },
    {
      model: "test-model",
      message: "Compare these pictures.",
      images: [
        { mimeType: "image/jpeg", imageBase64: "aGVsbG8=" },
        { mimeType: "image/png", imageBase64: "d29ybGQ=" }
      ]
    }
  );

  assert.equal(response.status, 200);
  const imageParts = payload.input[0].content.filter(part => part.type === "input_image");
  assert.equal(imageParts.length, 2);
  assert.match(imageParts[0].image_url, /^data:image\/jpeg;base64,/);
  assert.match(imageParts[1].image_url, /^data:image\/png;base64,/);
});

test("Gemini vision request includes each supplied image as inline data", async () => {
  let payload;
  globalThis.fetch = async (_url, init) => {
    payload = JSON.parse(init.body);
    return new Response(JSON.stringify({
      candidates: [{ content: { parts: [{ text: "Both images received." }] } }]
    }), { status: 200 });
  };

  const result = await callGemini(
    { GEMINI_API_KEY: "test-key" },
    {
      model: "test-model",
      message: "Compare these pictures.",
      images: [
        { mimeType: "image/jpeg", imageBase64: "aGVsbG8=" },
        { mimeType: "image/webp", imageBase64: "d29ybGQ=" }
      ]
    }
  );

  assert.equal(result.text, "Both images received.");
  const parts = payload.contents[0].parts;
  assert.equal(parts.filter(part => part.inlineData).length, 2);
  assert.equal(parts[1].inlineData.mimeType, "image/jpeg");
  assert.equal(parts[2].inlineData.mimeType, "image/webp");
});
