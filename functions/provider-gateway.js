import { getFirestore } from "firebase-admin/firestore";
import { getProviderSecret } from "./secret-store.js";

const GPT6_TEXT_CAPABILITIES = new Set(["chat", "device_commands", "vision", "truth_guard", "learning", "companion", "business", "business_ai", "organization_studio", "pdf", "life_matters", "frontier_lab", "personal_intelligence", "data_analyst", "deep_research"]);

const DEFAULT_MODELS = {
  openai: "gpt-6-luna",
  gemini: "gemini-3.5-flash"
};

const OPENAI_MODEL_BY_CAPABILITY = {
  chat: "gpt-6-luna",
  vision: "gpt-6-luna",
  image_generation: "gpt-image-2.5-sunburst",
  image_edit: "gpt-image-2.5-sunburst",
  live_camera: "gpt-realtime-2.1-mini",
  live_voice: "gpt-realtime-2.1-mini",
  transcription: "whisper-1",
  text_to_speech: "gpt-4o-mini-tts",
  embeddings: "text-embedding-3-small",
  moderation: "omni-moderation-latest",
  device_commands: "gpt-6-luna",
  video_generation: null
};
function allowedModelForCapability(capability, providerId, configuredModel) {
  if (providerId !== "openai") return configuredModel || DEFAULT_MODELS[providerId] || null;
  return OPENAI_MODEL_BY_CAPABILITY[capability] || DEFAULT_MODELS.openai;
}

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
      primaryProviderId: GPT6_TEXT_CAPABILITIES.has(capability) ? "openai" : (data.providerId || "openai"),
      // Text-generation capabilities use GPT-6 Luna. Modality-specific routes
      // retain their dedicated models, and text routes never silently fall back.
      primaryModelId: allowedModelForCapability(capability, GPT6_TEXT_CAPABILITIES.has(capability) ? "openai" : (data.providerId || "openai"), data.modelId),
      fallbackProviderIds: GPT6_TEXT_CAPABILITIES.has(capability) ? [] : (Array.isArray(config.fallbackProviderIds)
        ? config.fallbackProviderIds
        : ((data.providerId || "openai") === "openai" ? ["gemini"] : [])),
      fallbackModels: config.fallbackModels && typeof config.fallbackModels === "object" ? config.fallbackModels : {},
      enabled: capability !== "video_generation" && data.enabled !== false && data.killSwitch !== true
    };
  }
  const defaults = {
    chat: { primaryProviderId: "openai", primaryModelId: DEFAULT_MODELS.openai, fallbackProviderIds: [], enabled: true },
    device_commands: { primaryProviderId: "openai", primaryModelId: "gpt-6-luna", fallbackProviderIds: [], enabled: true },
    web_search: { primaryProviderId: "tavily", primaryModelId: null, fallbackProviderIds: [], enabled: true },
    video_generation: { primaryProviderId: "openai", primaryModelId: null, fallbackProviderIds: [], enabled: false }
  };
  return defaults[capability] || { primaryProviderId: "openai", primaryModelId: allowedModelForCapability(capability, "openai"), fallbackProviderIds: [], enabled: capability !== "video_generation" };
}

export async function callOpenAI({ model, message, system }) {
  const apiKey = await getProviderSecret("openai");
  if (!apiKey) throw new Error("OpenAI provider is not configured");
  const body = { model: DEFAULT_MODELS.openai, input: message };
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
    model: DEFAULT_MODELS.openai,
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
  const body = { model: DEFAULT_MODELS.openai, input: message, stream: true };
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
      // OpenAI SSE can use either LF or CRLF separators. Normalize both so
      // response.output_text.delta events are never left buffered indefinitely.
      const events = buffer.split(/\r?\n\r?\n/);
      buffer = events.pop() || "";
      for (const event of events) {
        const data = event.split(/\r?\n/).filter(line => line.startsWith("data:")).map(line => line.slice(5).trimStart()).join("\n");
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


export async function streamAi({ capability, message, system, onEvent }) {
  const route = await getRoute(capability);
  if (route.enabled === false) throw new Error("Capability disabled: " + capability);
  const candidates = [route.primaryProviderId, ...(route.fallbackProviderIds || [])].filter(Boolean);
  const modelFor = providerId => providerId === route.primaryProviderId
    ? route.primaryModelId
    : route.fallbackModels?.[providerId] || DEFAULT_MODELS[providerId] || null;
  let lastError;
  for (const providerId of candidates) {
    try {
      if (providerId === "openai") {
        await streamOpenAI({
          model: modelFor(providerId),
          message,
          system,
          onEvent
        });
        return { providerId: "openai", modelId: modelFor(providerId) };
      }
      if (providerId === "gemini") {
        const result = await callGemini({ model: modelFor(providerId), message, system });
        if (!result.text?.trim()) throw new Error("The AI service returned an empty response.");
        onEvent({ type: "delta", text: result.text });
        onEvent({ type: "completed", response: null });
        return { providerId: "gemini", modelId: result.modelId };
      }
      throw new Error("Unsupported provider: " + providerId);
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError || new Error("No provider route available");
}

export async function callOpenAIDeviceCommand({ model, message, system }) {
  const apiKey = await getProviderSecret("openai");
  if (!apiKey) throw new Error("OpenAI provider is not configured");
  const modelId = "gpt-6-luna";
  const body = {
    model: modelId,
    input: [{ role: "user", content: [{ type: "input_text", text: String(message || "") }] }],
    tools: [{
      type: "function",
      name: "device_command",
      description: "Return safe Android actions requested by the user. The app validates and performs each action.",
      parameters: {
        type: "object",
        properties: {
          actions: {
            type: "array",
            items: {
              type: "object",
              properties: {
                type: { type: "string" },
                value: { type: ["string", "number", "null"] }
              },
              required: ["type", "value"],
              additionalProperties: false
            }
          },
          message: { type: "string" }
        },
        required: ["actions", "message"],
        additionalProperties: false
      },
      strict: true
    }],
    tool_choice: { type: "function", name: "device_command" }
  };
  if (system) body.instructions = String(system);
  const result = await jsonFetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { Authorization: "Bearer " + apiKey, "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  const call = (result.output || []).find(item => item?.type === "function_call" && item?.name === "device_command");
  let args = {};
  try { args = JSON.parse(String(call?.arguments || "{}")); } catch {}
  return {
    text: JSON.stringify({ actions: Array.isArray(args.actions) ? args.actions : [], message: String(args.message || "") }),
    providerId: "openai",
    modelId,
    rawUsage: result.usage || {}
  };
}

export async function callGeminiDeviceCommand({ model, message, system }) {
  const apiKey = await getProviderSecret("gemini");
  if (!apiKey) throw new Error("Gemini provider is not configured");
  const modelId = model || DEFAULT_MODELS.gemini;
  const request = {
    contents: [{ role: "user", parts: [{ text: message }] }],
    tools: [{
      functionDeclarations: [{
        name: "device_command",
        description: "Execute one or more safe, user-requested Android device actions.",
        parameters: {
          type: "object",
          properties: {
            actions: {
              type: "array",
              items: {
                type: "OBJECT",
                properties: {
                  type: { type: "string" },
                  value: { type: "STRING" }
                },
                required: ["type"]
              }
            },
            message: { type: "STRING" }
          },
          required: ["actions", "message"]
        }
      }]
    }],
    toolConfig: { functionCallingConfig: { mode: "ANY", allowedFunctionNames: ["device_command"] } }
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
  const parts = result.candidates?.[0]?.content?.parts || [];
  const call = parts.find(p => p.functionCall)?.functionCall;
  const args = call?.args || {};
  return {
    text: JSON.stringify({ actions: Array.isArray(args.actions) ? args.actions : [], message: String(args.message || "") }),
    providerId: "gemini",
    modelId,
    rawUsage: result.usageMetadata || {}
  };
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
