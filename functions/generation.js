import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { getMessaging } from "firebase-admin/messaging";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
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
    console.error("generation_notification_failed", { uid, generationId, message: error?.message || "notification_failed" });
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
  const model = route.primaryModelId || "gpt-image-2";
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
  return { providerId: "openai", modelId: model, imageUrl: url, generationId: id };
}

export async function createVideo(uid, prompt, options = {}) {
  const route = await getRoute("video_generation");
  const model = route.primaryModelId || "sora-2";
  const form = new FormData();
  form.append("model", model);
  form.append("prompt", String(prompt || "").trim().slice(0, 12000));
  if (options.seconds) form.append("seconds", String(options.seconds));
  if (options.size) form.append("size", String(options.size));
  if (!String(prompt || "").trim()) throw new Error("video_prompt_required");
  const result = await openAiJson("/videos", { method: "POST", body: form });
  const ref = getFirestore().collection("videoGenerationJobs").doc();
  await ref.set({
    uid,
    jobId: ref.id,
    provider: "openai",
    providerVideoId: String(result.id),
    modelId: String(result.model || model),
    status: String(result.status || "queued"),
    progress: Number(result.progress || 0),
    prompt: String(prompt).slice(0, 12000),
    seconds: String(result.seconds || options.seconds || ""),
    size: String(result.size || options.size || ""),
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp()
  });
  return { jobId: ref.id, providerVideoId: result.id, status: result.status, progress: Number(result.progress || 0), modelId: result.model || model };
}

export async function getVideoJob(uid, jobId) {
  const ref = getFirestore().collection("videoGenerationJobs").doc(String(jobId));
  const snap = await ref.get();
  if (!snap.exists || snap.data()?.uid !== uid) throw new Error("video_job_not_found");
  const data = snap.data() || {};
  const result = await openAiJson("/videos/" + encodeURIComponent(String(data.providerVideoId)), { method: "GET" });
  let patch = {
    status: String(result.status || data.status || "queued"),
    progress: Number(result.progress || 0),
    updatedAt: FieldValue.serverTimestamp()
  };
  if (result.error?.message) patch.error = String(result.error.message).slice(0, 2000);
  if (result.status === "completed" && !data.contentUrl) {
    const apiKey = await getProviderSecret("openai");
    const response = await fetch("https://api.openai.com/v1/videos/" + encodeURIComponent(String(data.providerVideoId)) + "/content", {
      headers: { Authorization: "Bearer " + apiKey }
    });
    if (!response.ok) throw new Error("video_content_download_failed");
    const buffer = Buffer.from(await response.arrayBuffer());
    patch.contentUrl = await signedStorageUrl(buffer, "video/mp4", "generated/" + uid + "/videos/" + jobId + ".mp4", 24 * 60 * 60 * 1000);
  }
  if (result.status === "completed" && !data.notificationSentAt) {
    patch.notificationSentAt = FieldValue.serverTimestamp();
    await notifyGenerationReady(uid, "Your video is ready", "BQ Sqwad AI finished generating your video.", jobId);
  }
  await ref.set(patch, { merge: true });
  return { jobId, ...data, ...patch };
}

function cryptoRandomId() {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}
