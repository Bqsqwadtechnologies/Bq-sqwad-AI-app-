import test from "node:test";
import assert from "node:assert/strict";
import { callTavily, providerSecretStatus } from "../src/providers.js";

test("Tavily search authenticates with the configured Worker secret using Bearer auth", async () => {
  const originalFetch = globalThis.fetch;
  let request;
  globalThis.fetch = async (url, init) => {
    request = { url, init, body: JSON.parse(init.body) };
    return new Response(JSON.stringify({
      results: [{
        title: "Example result",
        url: "https://example.com/article",
        content: "A short result snippet.",
        published_date: "2026-10-12"
      }]
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  };

  try {
    const result = await callTavily(
      { TAVILY_API_KEY: "tvly-test-secret" },
      "test query",
      5,
      true
    );

    assert.equal(request.url, "https://api.tavily.com/search");
    assert.equal(request.init.method, "POST");
    assert.equal(request.init.headers.Authorization, "Bearer tvly-test-secret");
    assert.equal(request.init.headers["Content-Type"], "application/json");
    assert.equal("api_key" in request.body, false);
    assert.equal(request.body.query, "test query");
    assert.equal(request.body.max_results, 5);
    assert.equal(request.body.search_depth, "advanced");
    assert.equal(result.providerId, "tavily");
    assert.equal(result.sources.length, 1);
    assert.equal(result.sources[0].url, "https://example.com/article");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Tavily search fails closed when the Worker secret is missing", async () => {
  await assert.rejects(
    () => callTavily({}, "test query"),
    /web_search_not_configured/
  );
});

test("Worker secret status reports Tavily presence without exposing its value", () => {
  const status = providerSecretStatus({ TAVILY_API_KEY: "tvly-test-secret" });
  assert.equal(status.TAVILY_API_KEY, true);
  assert.equal(Object.values(status).includes("tvly-test-secret"), false);
});
