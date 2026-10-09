import test from "node:test";
import assert from "node:assert/strict";
import worker, { isPublicCatalogPlan } from "../src/index.js";

globalThis.fetch = async () => new Response('{"ok":true}', { status: 200 });

test("public catalog exposes shared credit packs and hides feature-specific credit plans", () => {
  for (const id of ["credit100", "credit250", "credit500", "credit1000"]) {
    assert.equal(isPublicCatalogPlan({ id, name: id, module: "credits", active: true, visible: true }), true);
  }
  for (const plan of [
    { id: "ai-live-m-credit", name: "AI Live M Credit", module: "live-camera", active: true, visible: true },
    { id: "live-camera-credits", name: "Live Camera Credits", module: "live-camera", active: true, visible: true },
    { id: "credit-special", name: "Special Credits", module: "credits", active: true, visible: true },
    { id: "ai-live-m", name: "AI Live M", module: "live-camera", active: true, visible: true }
  ]) {
    assert.equal(isPublicCatalogPlan(plan), false, JSON.stringify(plan));
  }
});

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
    ["GET", "/history"], ["GET", "/memory"], ["GET", "/feature-settings"], ["POST", "/feedback"], ["POST", "/notifications/token"], ["GET", "/native/catalog"], ["GET", "/admin/issues"], ["POST", "/issues/report"], ["POST", "/realtime/session"], ["POST", "/realtime/heartbeat"], ["POST", "/business/plan-builder"], ["POST", "/business/advanced-ai"], ["POST", "/business/assistant"], ["GET", "/video/jobs/test/content"]
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

test("native BQ catalog route is registered and requires Firebase auth", async () => {
  const res = await worker.fetch(new Request("https://x.dev/native/catalog"), {});
  assert.equal(res.status, 401);
  const body = await res.json();
  assert.equal(body.error, "Authentication required");
});

test("realtime voice and client issue reporting require Firebase auth", async () => {
  for (const [path, method] of [["/realtime/session", "POST"], ["/realtime/heartbeat", "POST"], ["/issues/report", "POST"], ["/business/plan-builder", "POST"], ["/business/advanced-ai", "POST"], ["/business/assistant", "POST"], ["/video/jobs/test/content", "GET"]]) {
    const res = await worker.fetch(new Request("https://x.dev" + path, {
      method,
      body: "{}"
    }), {});
    assert.equal(res.status, 401, path);
  }
});

test("unmigrated routes return a safe branded service error", async () => {
  const res = await worker.fetch(new Request("https://x.dev/not-yet-migrated"), {});
  assert.equal(res.status, 501);
  const body = await res.json();
  assert.equal(body.error, "service_temporarily_unavailable");
  assert.equal(body.path, undefined);
});

test("CORS preflight returns 204", async () => {
  const res = await worker.fetch(new Request("https://x.dev/ai", { method: "OPTIONS" }), {});
  assert.equal(res.status, 204);
});
