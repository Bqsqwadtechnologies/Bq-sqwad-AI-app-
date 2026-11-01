import test from "node:test";
import assert from "node:assert/strict";
import { BUSINESS_TOOL_CATALOG, toolsForPlanOrAll } from "../src/business-tool-catalog.js";

test("catalog is broad and KYC is present but not required", () => {
  assert.ok(BUSINESS_TOOL_CATALOG.length >= 25);
  const ids = BUSINESS_TOOL_CATALOG.map((t) => t.id);
  assert.ok(ids.includes("gmail"));
  assert.ok(ids.includes("kyc_generic"));
  assert.ok(ids.includes("custom_api"));
});

test("empty plan still offers the full catalog", () => {
  assert.equal(toolsForPlanOrAll(null).length, BUSINESS_TOOL_CATALOG.length);
  assert.equal(toolsForPlanOrAll(["slack"]).length, 1);
});
