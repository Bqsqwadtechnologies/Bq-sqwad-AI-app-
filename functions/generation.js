import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { getMessaging } from "firebase-admin/messaging";
import { getProviderSecret } from "./secret-store.js";
import { getRoute } from "./provider-gateway.js";


async function notifyGenerationReady(uid, title, body, generationId) {
  try {
    const snap = await getFirestore().collection("notificationTokens").where("uid", "==", uid).where("enabled", "==", true).limit(100).get();
    const tokens = snap.docs.map(d => String(d.data()?.token || "")).filter(Boolean);
    if (!tokens.length) return;
    for (let i = 0; i < tokens.length; i += 500) {
      await getMessaging().sendEachForMulticast({
        tokens: tokens.slice(i, i + 500),
        notification: { title, body },
        data: { type: "generation_ready", generationId: String(generationId || "") }
      });
    }
  } catch (error) {
    console.error("generation_notification_failed");
  }
}

async function openAiJson(path, options = {}) {
  const apiKey = await getProviderSecret("openai");
  if (!apiKey) throw new Error("OpenAI provider is not configured");
  const response = await fetch("https://api.openai.com/v1" + path, {
    ...options,
    headers: {
      Authorization: "Bearer " + apiKey,
      ...(options.headers || {})
    }
  });
  const raw = await response.text();
  let body;
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { raw }; }
  if (!response.ok) throw new Error("OpenAI HTTP " + response.status + ": " + JSON.stringify(body).slice(0, 1200));
  return body;
}

async function signedStorageUrl(buffer, contentType, storagePath, expiresMs = 7 * 24 * 60 * 60 * 1000) {
  const bucket = getStorage().bucket();
  const file = bucket.file(storagePath);
  await file.save(buffer, { resumable: false, metadata: { contentType, cacheControl: "private,max-age=3600" } });
  const [url] = await file.getSignedUrl({ action: "read", expires: Date.now() + expiresMs });
  return url;
}

export async function generateImage(uid, prompt, options = {}) {
  const route = await getRoute("image_generation");
  const model = "gpt-image-2.5-sunburst";
  const body = {
    model,
    prompt: String(prompt || "").trim().slice(0, 12000),
    size: String(options.size || "1024x1024"),
    quality: String(options.quality || "auto")
  };
  if (!body.prompt) throw new Error("image_prompt_required");
  const result = await openAiJson("/images/generations", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  const encoded = result.data?.[0]?.b64_json;
  if (!encoded) throw new Error("image_generation_returned_no_image");
  const buffer = Buffer.from(encoded, "base64");
  const id = cryptoRandomId();
  const url = await signedStorageUrl(buffer, "image/png", "generated/" + uid + "/images/" + id + ".png");
  await notifyGenerationReady(uid, "Your image is ready", "BQ Sqwad AI finished generating your image.", id);
  return { providerId: "openai", modelId: model, imageUrl: url, generationId: id, rawUsage: result.usage || null };
}
