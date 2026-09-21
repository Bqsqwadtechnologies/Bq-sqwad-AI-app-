import { getFirestore } from "firebase-admin/firestore";
import { getProviderSecret } from "./secret-store.js";

const DEFAULT_MODELS = {
  openai: "gpt-5.6-luna",
  gemini: "gemini-3.5-flash"
};

async function jsonFetch(url, options = {}) {
  const response = await fetch(url, options);
  const text = await response.text();
  let body;
  try { body = text ? JSON.parse(text) : {}; } catch { body = { raw: text }; }
  if (!response.ok) throw new Error("Provider HTTP " + response.status + ": " + JSON.stringify(body).slice(0, 1000));
  return body;
}

export async function getRoute(capability) {
  const controlId = "capability_" + capability;
  const doc = await getFirestore().collection("adminAdvancedOptions").doc(controlId).get();
  if (doc.exists) {
    const data = doc.data() || {};
    const config = data.config || {};
    return {
      primaryProviderId: data.providerId || "openai",
      primaryModelId: data.modelId || DEFAULT_MODELS.openai,
      fallbackProviderIds: Array.isArray(config.fallbackProviderIds) ? config.fallbackProviderIds : [],
      fallbackModels: config.fallbackModels && typeof config.fallbackModels === "object" ? config.fallbackModels : {},
      enabled: data.enabled !== false && data.killSwitch !== true
    };
  }
  const defaults = {
    chat: { primaryProviderId: "openai", primaryModelId: DEFAULT_MODELS.openai, fallbackProviderIds: ["gemini"], enabled: true },
    device_commands: { primaryProviderId: "gemini", primaryModelId: DEFAULT_MODELS.gemini, fallbackProviderIds: [], enabled: true },
    web_search: { primaryProviderId: "tavily", primaryModelId: null, fallbackProviderIds: [], enabled: true }
  };
  return defaults[capability] || { primaryProviderId: "openai", primaryModelId: DEFAULT_MODELS.openai, fallbackProviderIds: ["gemini"], enabled: true };
}

export async function callOpenAI({ model, message, system }) {
  const apiKey = await getProviderSecret("openai");
  if (!apiKey) throw new Error("OpenAI provider is not configured");
  const body = { model: model || DEFAULT_MODELS.openai, input: message };
  if (system) body.instructions = system;
  const result = await jsonFetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { Authorization: "Bearer " + apiKey, "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  const text = result.output_text ||
    result.output?.flatMap(x => x.content || []).map(x => x.text || "").join("") || "";
  return { text, providerId: "openai", modelId: body.model, rawUsage: result.usage || {} };
}


export async function callOpenAIVision({ model, message, imageBase64, mimeType = "image/jpeg", system }) {
  const apiKey = await getProviderSecret("openai");
  if (!apiKey) throw new Error("OpenAI provider is not configured");
  if (!imageBase64) throw new Error("Camera frame is required");
  const body = {
    model: model || DEFAULT_MODELS.openai,
    input: [{
      role: "user",
      content: [
        { type: "input_text", text: message || "Describe what is visible in this camera frame." },
        { type: "input_image", image_url: "data:" + mimeType + ";base64," + imageBase64 }
      ]
    }]
  };
  if (system) body.instructions = system;
  const result = await jsonFetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { Authorization: "Bearer " + apiKey, "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  const text = result.output_text ||
    result.output?.flatMap(x => x.content || []).map(x => x.text || "").join("") || "";
  return { text, providerId: "openai", modelId: body.model, rawUsage: result.usage || {} };
}


export async function streamOpenAI({ model, message, system, onEvent }) {
  const apiKey = await getProviderSecret("openai");
  if (!apiKey) throw new Error("OpenAI provider is not configured");
  const body = { model: model || DEFAULT_MODELS.openai, input: message, stream: true };
  if (system) body.instructions = system;
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { Authorization: "Bearer " + apiKey, "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  if (!response.ok || !response.body) {
    const raw = await response.text();
    throw new Error("Provider HTTP " + response.status + ": " + raw.slice(0, 1000));
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const events = buffer.split("\n\n");
      buffer = events.pop() || "";
      for (const event of events) {
        const data = event.split("\n").filter(line => line.startsWith("data:")).map(line => line.slice(5).trimStart()).join("\n");
        if (!data || data === "[DONE]") continue;
        let parsed;
        try { parsed = JSON.parse(data); } catch { continue; }
        if (parsed.type === "response.output_text.delta" && parsed.delta) onEvent({ type: "delta", text: String(parsed.delta) });
        if (parsed.type === "response.completed") onEvent({ type: "completed", response: parsed.response || null });
        if (parsed.type === "error") onEvent({ type: "error", message: parsed.error?.message || "Streaming request failed" });
      }
    }
  } finally {
    reader.releaseLock();
  }
}

export async function callGemini({ model, message, system }) {
  const apiKey = await getProviderSecret("gemini");
  if (!apiKey) throw new Error("Gemini provider is not configured");
  const modelId = model || DEFAULT_MODELS.gemini;
  const request = {
    contents: [{ role: "user", parts: [{ text: message }] }]
  };
  if (system) request.systemInstruction = { parts: [{ text: system }] };
  const result = await jsonFetch(
    "https://generativelanguage.googleapis.com/v1beta/models/" + encodeURIComponent(modelId) + ":generateContent",
    {
      method: "POST",
      headers: { "x-goog-api-key": apiKey, "Content-Type": "application/json" },
      body: JSON.stringify(request)
    }
  );
  const text = result.candidates?.[0]?.content?.parts?.map(p => p.text || "").join("") || "";
  return { text, providerId: "gemini", modelId, rawUsage: result.usageMetadata || {} };
}

export async function callTavily({ query, maxResults = 10, deepResearch = false }) {
  const apiKey = await getProviderSecret("tavily");
  if (!apiKey) throw new Error("Tavily provider is not configured");
  const result = await jsonFetch("https://api.tavily.com/search", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      api_key: apiKey,
      query,
      max_results: Math.min(Math.max(Number(maxResults) || 10, 1), 20),
      search_depth: deepResearch ? "advanced" : "basic",
      include_answer: false,
      include_raw_content: false
    })
  });
  return {
    providerId: "tavily",
    sources: (result.results || []).map(x => ({
      title: x.title,
      url: x.url,
      snippet: x.content || null,
      publishedAt: x.published_date || null
    }))
  };
}

export async function runAi({ capability, message, system }) {
  const route = await getRoute(capability);
  if (route.enabled === false) throw new Error("Capability disabled: " + capability);
  const candidates = [route.primaryProviderId, ...(route.fallbackProviderIds || [])].filter(Boolean);
  const modelFor = providerId => providerId === route.primaryProviderId
    ? route.primaryModelId
    : route.fallbackModels?.[providerId] || DEFAULT_MODELS[providerId] || null;
  let lastError;
  for (const providerId of candidates) {
    try {
      if (providerId === "openai") return await callOpenAI({ model: modelFor(providerId), message, system });
      if (providerId === "gemini") return await callGemini({ model: modelFor(providerId), message, system });
      throw new Error("Unsupported provider: " + providerId);
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError || new Error("No provider route available");
}
