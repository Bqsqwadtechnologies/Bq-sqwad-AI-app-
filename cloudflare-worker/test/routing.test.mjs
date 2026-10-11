import test from "node:test";
import assert from "node:assert/strict";
import worker, { isPublicCatalogPlan } from "../src/index.js";
import { COMMERCIAL_CATALOG, canonicalPlayMappings, mergeCommercialCatalog } from "../src/plan-catalog-native.js";
import { playCatalog } from "../src/google-play.js";

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

test("public catalog keeps active visible workspace, free, and Contact Sales plans", () => {
  for (const plan of [
    { id: "explorer", name: "Explorer", module: "learning", active: true, visible: true, price: { free: 0 } },
    { id: "businessenterprise", name: "Business Ultimate", module: "business", active: true, visible: true, contactSales: true },
    { id: "bizstarter", name: "Business Starter", module: "business", active: true, visible: true },
    { id: "companionpremium", name: "Companion Premium", module: "companion", active: true, visible: true }
  ]) {
    assert.equal(isPublicCatalogPlan(plan), true, JSON.stringify(plan));
  }
  assert.equal(isPublicCatalogPlan({ id: "hidden", name: "Hidden plan", module: "learning", active: true, visible: false }), false);
  assert.equal(isPublicCatalogPlan({ id: "inactive", name: "Inactive plan", module: "learning", active: false, visible: true }), false);
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
    ["GET", "/history"], ["GET", "/memory"], ["GET", "/feature-settings"], ["POST", "/feedback"], ["POST", "/notifications/token"], ["GET", "/native/catalog"], ["GET", "/admin/issues"], ["POST", "/admin/self-test"], ["GET", "/admin/daily-free-credits"], ["POST", "/admin/daily-free-credits"], ["GET", "/admin/security/status"], ["POST", "/admin/security/password"], ["POST", "/admin/security/totp/start"], ["POST", "/admin/security/totp/confirm"], ["POST", "/admin/security/unlock"], ["GET", "/admin/security/biometric"], ["POST", "/admin/security/biometric/register"], ["POST", "/admin/security/biometric/challenge"], ["POST", "/admin/security/biometric/verify"], ["DELETE", "/admin/security/biometric"], ["POST", "/account/schedule-deletion"], ["POST", "/account/cancel-deletion"], ["GET", "/account/deletion-status"], ["DELETE", "/account"], ["POST", "/issues/report"], ["POST", "/realtime/session"], ["POST", "/realtime/heartbeat"], ["POST", "/business/plan-builder"], ["POST", "/business/advanced-ai"], ["POST", "/business/assistant"], ["GET", "/video/jobs/test/content"]
  ]) {
    const res = await worker.fetch(new Request("https://x.dev" + path, {
      method,
      body: method === "POST" ? "{}" : undefined
    }), {});
    const body = await res.text();
    assert.ok(!/is not defined/.test(body), method + " " + path + " -> " + body);
  }
});

test("account deletion cannot be performed immediately", async () => {
  const res = await worker.fetch(new Request("https://x.dev/account", { method: "DELETE" }), {});
  assert.equal(res.status, 405);
});

test("protected AI routes reject missing auth with 401", async () => {
  const res = await worker.fetch(new Request("https://x.dev/ai", {
    method: "POST",
    body: "{}"
  }), {});
  assert.equal(res.status, 401);
});

test("native BQ catalog is browseable without sign-in and falls back to canonical plans", async () => {
  const res = await worker.fetch(new Request("https://x.dev/native/catalog"), {});
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.ok(Array.isArray(body.plans) && body.plans.length > 0);
  assert.ok(body.plans.some(plan => plan.id === "credit100"));
  assert.ok(body.plans.some(plan => plan.id === "bizstarter"));
});

test("realtime voice and client issue reporting require Firebase auth", async () => {
  for (const [path, method] of [["/realtime/session", "POST"], ["/realtime/heartbeat", "POST"], ["/admin/self-test", "POST"], ["/admin/daily-free-credits", "GET"], ["/admin/daily-free-credits", "POST"], ["/issues/report", "POST"], ["/business/plan-builder", "POST"], ["/business/advanced-ai", "POST"], ["/business/assistant", "POST"], ["/video/jobs/test/content", "GET"]]) {
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


test("Worker fallback catalog exposes the canonical BQ Sqwad AI plans and shared credit packs", () => {
  const sections = COMMERCIAL_CATALOG.sections || {};
  const plans = Object.values(sections).flat();
  assert.ok(plans.length > 0, "fallback catalog should not be empty");
  const ids = new Set(plans.map(plan => plan.id));
  for (const id of ["credit100", "credit250", "credit500", "credit1000", "bizstarter", "companionfree", "explorer"]) {
    assert.ok(ids.has(id), "fallback catalog is missing " + id);
  }
  for (const plan of plans) {
    assert.match(String(plan.id || ""), /^[a-z0-9-]+$/);
  }
});

test("Google Play catalog and verification share canonical fallback mappings", async () => {
  const mappings = canonicalPlayMappings();
  assert.ok(mappings.length > 0, "canonical Play mappings should not be empty");
  assert.ok(mappings.some(item => item.planId === "credit100" && item.creditGrant === 100));
  const result = await playCatalog({ get: async () => null });
  assert.ok(result.products.length > 0);
  assert.ok(result.products.some(item => item.planId === "credit100" && item.productId === "credit100"));
  for (const item of result.products) {
    assert.match(item.productId, /^[a-z0-9-]+$/);
  }
});


test("partial Firestore catalog overrides do not remove other canonical plans", () => {
  const merged = mergeCommercialCatalog({
    sections: { companion: [{ id: "companionfree", active: false, name: "Companion Free (paused)" }] }
  });
  const plans = Object.values(merged.sections).flat();
  const byId = new Map(plans.map(plan => [plan.id, plan]));
  assert.ok(byId.has("credit100"));
  assert.ok(byId.has("bizstarter"));
  assert.equal(byId.get("companionfree").active, false);
  assert.equal(byId.get("companionfree").name, "Companion Free (paused)");
});

test("admin plan catalog drives Play mappings for custom eligible plans", async () => {
  const custom = {
    id: "custommonthly",
    name: "Custom Monthly",
    module: "chat",
    productId: "custommonthly",
    productType: "subs",
    basePlans: ["monthly"],
    playEligible: true,
    paymentChannel: "play",
    active: true,
    price: { monthly: 9.99 },
    entitlements: []
  };
  const result = await playCatalog({
    get: async path => path === "planCatalog/config"
      ? { catalog: { sections: { custom: [custom] } }, version: 8 }
      : null
  });
  assert.ok(result.products.some(item =>
    item.planId === "custommonthly" && item.productId === "custommonthly" && item.basePlanId === "monthly"
  ));
});
