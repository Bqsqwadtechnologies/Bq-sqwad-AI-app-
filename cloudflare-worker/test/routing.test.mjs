import test from "node:test";
import assert from "node:assert/strict";
import worker, { isPublicCatalogPlan, isRootSuperAdmin, normalizeLivePricing, liveStartChargePolicy, settleLiveStartReservation } from "../src/index.js";
import { COMMERCIAL_CATALOG, canonicalPlayMappings, mergeCommercialCatalog } from "../src/plan-catalog-native.js";
import { playCatalog } from "../src/google-play.js";

globalThis.fetch = async () => new Response('{"ok":true}', { status: 200 });

test("admin test-mode bypass is restricted to verified root super-admins", () => {
  const env = { SUPER_ADMIN_EMAIL: "owner@example.com" };
  assert.equal(isRootSuperAdmin({ uid: "owner", email: "owner@example.com", emailVerified: true }, env), true);
  assert.equal(isRootSuperAdmin({ uid: "owner", email: "owner@example.com", emailVerified: false }, env), false);
  assert.equal(isRootSuperAdmin({ uid: "delegated", email: "admin@example.com", emailVerified: true, customAttributes: { admin: true, role: "admin" } }, env), false);
  assert.equal(isRootSuperAdmin({ uid: "root", email: "admin@example.com", customAttributes: { role: "super_admin" } }, env), true);
});

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
    ["GET", "/credits"], ["GET", "/entitlement"], ["GET", "/entitlements"],
    ["POST", "/web-search"],
    ["GET", "/history"], ["GET", "/memory"], ["GET", "/feature-settings"], ["POST", "/feedback"], ["POST", "/notifications/token"], ["GET", "/native/catalog"], ["GET", "/admin/issues"], ["POST", "/admin/self-test"], ["GET", "/admin/test-mode"], ["POST", "/admin/test-mode"], ["GET", "/admin/credit-trial"], ["POST", "/admin/credit-trial"], ["GET", "/admin/daily-free-credits"], ["POST", "/admin/daily-free-credits"], ["GET", "/admin/security/status"], ["POST", "/admin/security/password"], ["POST", "/admin/security/totp/start"], ["POST", "/admin/security/totp/confirm"], ["POST", "/admin/security/unlock"], ["GET", "/admin/security/biometric"], ["POST", "/admin/security/biometric/register"], ["POST", "/admin/security/biometric/challenge"], ["POST", "/admin/security/biometric/verify"], ["DELETE", "/admin/security/biometric"], ["POST", "/account/schedule-deletion"], ["POST", "/account/cancel-deletion"], ["GET", "/account/deletion-status"], ["DELETE", "/account"], ["POST", "/issues/report"], ["POST", "/realtime/session"], ["POST", "/realtime/heartbeat"], ["POST", "/business/plan-builder"], ["POST", "/business/advanced-ai"], ["POST", "/business/assistant"], ["GET", "/video/jobs/test/content"]
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

test("protected AI and entitlement routes reject missing auth with 401", async () => {
  for (const [path, method, body] of [
    ["/ai", "POST", "{}"],
    ["/entitlement", "GET", undefined],
    ["/entitlements", "GET", undefined]
  ]) {
    const res = await worker.fetch(new Request("https://x.dev" + path, { method, body }), {});
    assert.equal(res.status, 401, path);
  }
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
  for (const [path, method] of [["/realtime/session", "POST"], ["/realtime/heartbeat", "POST"], ["/admin/self-test", "POST"], ["/admin/test-mode", "GET"], ["/admin/test-mode", "POST"], ["/admin/credit-trial", "GET"], ["/admin/credit-trial", "POST"], ["/admin/daily-free-credits", "GET"], ["/admin/daily-free-credits", "POST"], ["/issues/report", "POST"], ["/business/plan-builder", "POST"], ["/business/advanced-ai", "POST"], ["/business/assistant", "POST"], ["/video/jobs/test/content", "GET"]]) {
    const res = await worker.fetch(new Request("https://x.dev" + path, {
      method,
      body: method === "POST" ? "{}" : undefined
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


test("live pricing defaults to free and validates paid rates and minimum balance", () => {
  const free = normalizeLivePricing({}, {}, "live_voice_pricing");
  assert.equal(free.creditsPerMinute, 0);
  assert.equal(free.startFeeCredits, 0);
  assert.equal(free.minimumBalanceToStart, 0);
  const paid = normalizeLivePricing({ creditsPerMinute: 1.5, startFeeCredits: 2, minimumBalanceToStart: 4 }, {}, "live_voice_pricing");
  assert.equal(paid.creditsPerMinute, 1.5);
  assert.equal(paid.startFeeCredits, 2);
  assert.equal(paid.minimumBalanceToStart, 4);
  assert.throws(() => normalizeLivePricing({ creditsPerMinute: -1 }, {}, "live_voice_pricing"), /invalid_live_pricing_creditsPerMinute/);
});

test("live start policy permits free sessions, checks paid minimums, and exempts super-admin", () => {
  assert.deepEqual(liveStartChargePolicy({ creditsPerMinute: 0, startFeeCredits: 0, minimumBalanceToStart: 0 }, 0, false), {
    allowed: true, free: true, requiredBalance: 0, startFeeCredits: 0
  });
  assert.equal(liveStartChargePolicy({ creditsPerMinute: 1, startFeeCredits: 0, minimumBalanceToStart: 3 }, 2, false).allowed, false);
  assert.deepEqual(liveStartChargePolicy({ creditsPerMinute: 1, startFeeCredits: 0, minimumBalanceToStart: 3 }, 3, false), {
    allowed: true, free: false, requiredBalance: 3, startFeeCredits: 0
  });
  assert.equal(liveStartChargePolicy({ creditsPerMinute: 5, startFeeCredits: 2, minimumBalanceToStart: 10 }, 0, true).allowed, true);
  assert.equal(liveStartChargePolicy({ creditsPerMinute: 5, startFeeCredits: 2, minimumBalanceToStart: 10 }, 0, true).startFeeCredits, 0);
});


test("live session commits the start-fee reservation only after session creation", async () => {
  const events = [];
  const result = await settleLiveStartReservation(
    "user-1",
    { reservationId: "reservation-1" },
    async (uid, id, provider) => { events.push(["commit", uid, id, provider]); },
    async (uid, id) => { events.push(["reverse", uid, id]); },
    async () => { events.push(["create"]); return "session-secret"; }
  );
  assert.equal(result, "session-secret");
  assert.deepEqual(events, [
    ["create"],
    ["commit", "user-1", "reservation-1", "openai"]
  ]);
});

test("live session reverses a reserved start fee when provider/session creation fails", async () => {
  const events = [];
  await assert.rejects(
    settleLiveStartReservation(
      "user-2",
      { reservationId: "reservation-2" },
      async (_uid, id) => { events.push(["commit", id]); },
      async (uid, id) => { events.push(["reverse", uid, id]); },
      async () => { events.push(["create"]); throw new Error("provider failed"); }
    ),
    /provider failed/
  );
  assert.deepEqual(events, [["create"], ["reverse", "user-2", "reservation-2"]]);
});

test("free live sessions create without commit or reverse calls", async () => {
  const events = [];
  const result = await settleLiveStartReservation(
    "user-free",
    null,
    async () => { events.push("commit"); },
    async () => { events.push("reverse"); },
    async () => { events.push("create"); return "free-session"; }
  );
  assert.equal(result, "free-session");
  assert.deepEqual(events, ["create"]);
});
