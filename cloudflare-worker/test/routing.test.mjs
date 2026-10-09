import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

globalThis.fetch = async () => new Response('{"ok":true}', { status: 200 });

test("/health is public and ok", async () => {
  const res = await worker.fetch(new Request("https://x.dev/health"), {});
  assert.equal(res.status, 200);
  assert.equal((await res.json()).status, "ok");
});

test("routing never returns ReferenceError for feature paths", async () => {
  for (const [method, path] of [
    ["POST", "/ai"],
    ["POST", "/ai/stream"],
    ["GET", "/credits"],
    ["POST", "/web-search"],
    ["GET", "/history"], ["GET", "/memory"], ["GET", "/feature-settings"], ["POST", "/feedback"], ["POST", "/notifications/token"], ["GET", "/native/catalog"]
  ]) {
    const res = await worker.fetch(new Request("https://x.dev" + path, {
      method,
      body: method === "POST" ? "{}" : undefined
    }), {});
    const body = await res.text();
    assert.ok(!/is not defined/.test(body), method + " " + path + " -> " + body);
  }
});

test("protected AI routes reject missing auth with 401", async () => {
  const res = await worker.fetch(new Request("https://x.dev/ai", {
    method: "POST",
    body: "{}"
  }), {});
  assert.equal(res.status, 401);
});

test("CORS preflight returns 204", async () => {
  const res = await worker.fetch(new Request("https://x.dev/ai", { method: "OPTIONS" }), {});
  assert.equal(res.status, 204);
});
