const DEFAULT_MODELS = {
  openai: "gpt-5.6-luna",
  gemini: "gemini-2.5-flash"
};

function secret(env, name) {
  const value = env?.[name];
  if (!value) throw new Error(name + "_not_configured");
  return String(value);
}

export function providerSecretStatus(env) {
  const names = [
    "BQ_FIREBASE_SERVICE_ACCOUNT_JSON",
    "FIREBASE_WEB_API_KEY",
    "OPENAI_API_KEY",
    "GEMINI_API_KEY",
    "TAVILY_API_KEY",
    "YOUTUBE_CLIENT_SECRET",
    "YOUTUBE_API_KEY",
    "X_CLIENT_SECRET",
    "X_API_KEY",
    "X_API_SECRET",
    "META_APP_SECRET",
    "BQ_SOCIAL_ENCRYPTION_KEY",
    "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"
  ];
  return Object.fromEntries(names.map(name => [name, Boolean(env?.[name])]));
}

async function jsonFetch(url, options = {}) {
  const response = await fetch(url, options);
  const raw = await response.text();
  let body = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { raw }; }
  if (!response.ok) throw new Error("provider_http_" + response.status + ":" + String(body.error?.message || body.message || body.raw || "").slice(0, 700));
  return body;
}

export async function callOpenAI(env, { model, message, system, imageBase64, mimeType = "image/jpeg", stream = false }) {
  const input = imageBase64
    ? [{ role: "user", content: [
        { type: "input_text", text: String(message || "Describe what is visible in this image.") },
        { type: "input_image", image_url: "data:" + mimeType + ";base64," + imageBase64 }
      ]}]
    : String(message || "");
  const body = { model: model || DEFAULT_MODELS.openai, input, stream };
  if (system) body.instructions = system;
  return fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { Authorization: "Bearer " + secret(env, "OPENAI_API_KEY"), "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
}

export async function callGemini(env, { model, message, system }) {
  const modelId = model || DEFAULT_MODELS.gemini;
  const request = { contents: [{ role: "user", parts: [{ text: String(message || "") }] }] };
  if (system) request.systemInstruction = { parts: [{ text: system }] };
  const result = await jsonFetch(
    "https://generativelanguage.googleapis.com/v1beta/models/" + encodeURIComponent(modelId) + ":generateContent",
    {
      method: "POST",
      headers: { "x-goog-api-key": secret(env, "GEMINI_API_KEY"), "Content-Type": "application/json" },
      body: JSON.stringify(request)
    }
  );
  return {
    text: result.candidates?.[0]?.content?.parts?.map(p => p.text || "").join("") || "",
    providerId: "gemini",
    modelId
  };
}

export async function callTavily(env, query, maxResults = 10, deepResearch = false) {
  const result = await jsonFetch("https://api.tavily.com/search", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      api_key: secret(env, "TAVILY_API_KEY"),
      query: String(query || ""),
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

export async function runProvider(env, route, input) {
  const providers = [route.primaryProviderId, ...(route.fallbackProviderIds || [])].filter(Boolean);
  let lastError;
  for (const providerId of providers) {
    try {
      if (providerId === "openai") {
        const response = await callOpenAI(env, { ...input, model: route.primaryProviderId === providerId ? route.primaryModelId : (route.fallbackModels?.[providerId] || DEFAULT_MODELS.openai) });
        if (!response.ok) {
          const raw = await response.text();
          throw new Error("provider_http_" + response.status + ":" + raw.slice(0, 700));
        }
        const body = await response.json();
        return { text: body.output_text || body.output?.flatMap(x => x.content || []).map(x => x.text || "").join("") || "", providerId: "openai", modelId: input.model || route.primaryModelId || DEFAULT_MODELS.openai };
      }
      if (providerId === "gemini") {
        return await callGemini(env, { ...input, model: route.primaryProviderId === providerId ? route.primaryModelId : (route.fallbackModels?.[providerId] || DEFAULT_MODELS.gemini) });
      }
      throw new Error("unsupported_provider:" + providerId);
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError || new Error("no_provider_available");
}

export { DEFAULT_MODELS };
