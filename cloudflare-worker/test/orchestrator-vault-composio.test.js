import test from "node:test";
import assert from "node:assert/strict";
import { shouldFallback, estimateCostUsd } from "../src/orchestrator-native.js";
import { businessIngressAllowed, storageMeterView, redactForVault } from "../src/business-vault-native.js";
import { toolsForPlan } from "../src/business-tool-catalog.js";
import { isActivePresence } from "../src/presence-usage-native.js";

test("fallback on 429 not on auth", () => {
  assert.equal(shouldFallback(new Error("provider_http_429:rate")), true);
  assert.equal(shouldFallback(new Error("unauthorized")), false);
});

test("cost estimate positive", () => {
  const c = estimateCostUsd({ inputPer1kUsd: 0.01, outputPer1kUsd: 0.03 }, 1000, 500);
  assert.ok(c > 0);
});

test("plan expiry blocks ingress", () => {
  const past = { state: "active", expiresAt: new Date(Date.now() - 1000).toISOString() };
  assert.equal(businessIngressAllowed(past).allowed, false);
  const future = { state: "active", expiresAt: new Date(Date.now() + 86400000).toISOString() };
  assert.equal(businessIngressAllowed(future).allowed, true);
});

test("addon offer only when active and high usage", () => {
  const m = storageMeterView({ planQuotaBytes: 100, usedBytes: 95, addOnBytes: 0 }, true);
  assert.equal(m.showAddonOffer, true);
  const expired = storageMeterView({ planQuotaBytes: 100, usedBytes: 95, addOnBytes: 0 }, false);
  assert.equal(expired.showRenewalOnly, true);
  assert.equal(expired.showAddonOffer, false);
});

test("vault redacts card-like numbers", () => {
  const r = redactForVault("card 4111111111111111 ok");
  assert.match(r, /\[card\]/);
});

test("plan tools are catalog-driven not forced KYC", () => {
  const tools = toolsForPlan({ tools: [{ id: "gmail", name: "Gmail" }, { id: "slack", name: "Slack" }] });
  assert.equal(tools.length, 2);
  assert.ok(!tools.some((t) => /bvn|nin/i.test(t.id)));
});

test("presence active within 90s", () => {
  assert.equal(isActivePresence(new Date().toISOString()), true);
  assert.equal(isActivePresence(new Date(Date.now() - 120000).toISOString()), false);
});
