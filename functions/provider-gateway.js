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
  const doc = await getFirestore().collection("adminProviderRoutes").doc(capability).get();
  if (doc.exists) return doc.data();
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
  let lastError;
  for (const providerId of candidates) {
    try {
      if (providerId === "openai") return await callOpenAI({ model: route.primaryModelId, message, system });
      if (providerId === "gemini") return await callGemini({ model: route.primaryModelId, message, system });
      throw new Error("Unsupported provider: " + providerId);
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError || new Error("No provider route available");
}
