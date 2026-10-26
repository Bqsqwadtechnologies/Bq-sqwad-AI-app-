import test from "node:test";
import assert from "node:assert/strict";
import { COMMERCIAL_CATALOG, canonicalPlayMappings, validateCommercialCatalog } from "../src/plan-catalog-native.js";

// These IDs are externally configured in Play Console and must never be renamed
// as part of a pricing/catalog refactor.
test("Google Play credit pack IDs and amounts remain immutable", () => {
  const rows = COMMERCIAL_CATALOG.sections.credits;
  assert.deepEqual(rows.map(x => [x.id, x.productId, x.entitlements]), [
    ["credit100", "credit100", ["credit:100"]],
    ["credit250", "credit250", ["credit:250"]],
    ["credit500", "credit500", ["credit:500"]],
    ["credit1000", "credit1000", ["credit:1000"]],
  ]);
  assert.deepEqual(rows.map(x => x.price.monthly), [11.99, 24.99, 44.99, 79.99]);
});

test("canonical Play mappings preserve the existing credit product/base-plan identifiers", () => {
  const mappings = canonicalPlayMappings();
  const creditRows = mappings.filter(x => ["credit100", "credit250", "credit500", "credit1000"].includes(x.productId));
  assert.deepEqual(creditRows.map(x => [x.productId, x.basePlanId]).sort(), [
    ["credit100", "monthly"],
    ["credit1000", "monthly"],
    ["credit250", "monthly"],
    ["credit500", "monthly"],
  ]);
  assert.deepEqual(COMMERCIAL_CATALOG.sections.credits.map(x => [x.productId, x.basePlans]), [
    ["credit100", ["weekly", "monthly"]],
    ["credit250", ["weekly", "monthly"]],
    ["credit500", ["weekly", "monthly"]],
    ["credit1000", ["weekly", "monthly"]],
  ]);
  assert.equal(validateCommercialCatalog(COMMERCIAL_CATALOG).ok, true);
});
