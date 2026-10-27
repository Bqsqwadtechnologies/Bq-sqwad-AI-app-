import test from "node:test";
import assert from "node:assert/strict";
import { COMMERCIAL_CATALOG, canonicalPlayMappings, validateCommercialCatalog } from "../src/plan-catalog-native.js";

// These IDs are externally configured in Play Console and must never be renamed
// as part of a pricing/catalog refactor.
test("Google Play credit subscription IDs and amounts remain immutable", () => {
  const rows = COMMERCIAL_CATALOG.sections.credits.filter(x => x.productType === "subs");
  assert.deepEqual(rows.map(x => [x.id, x.productId, x.entitlements]), [
    ["credit100", "credit100", ["credit:100"]],
    ["credit250", "credit250", ["credit:250"]],
    ["credit500", "credit500", ["credit:500"]],
    ["credit1000", "credit1000", ["credit:1000"]],
  ]);
  assert.deepEqual(rows.map(x => x.price.monthly), [11.99, 24.99, 44.99, 79.99]);
  assert.deepEqual(rows.map(x => x.basePlans), [
    ["weakly", "monthly"],
    ["weakly", "monthly"],
    ["weakly", "monthly", "yearly"],
    ["weakly", "monthly", "yearly"],
  ]);
});

test("canonical Play mappings match existing credit plans and one-time offers", () => {
  const mappings = canonicalPlayMappings();
  const creditRows = mappings.filter(x => ["credit100", "credit250", "credit500", "credit1000"].includes(x.productId));
  assert.deepEqual(
    creditRows.map(x => `${x.productId}:${x.basePlanId}`).sort(),
    [
      "credit100:monthly", "credit100:weakly",
      "credit250:monthly", "credit250:weakly",
      "credit500:monthly", "credit500:weakly", "credit500:yearly",
      "credit1000:monthly", "credit1000:weakly", "credit1000:yearly",
    ].sort()
  );

  const creditOffers = mappings.filter(x => x.productId.startsWith("creditaddon"));
  assert.deepEqual(
    creditOffers.map(x => [x.productId, x.creditGrant, x.productType, x.basePlanId]).sort((a, b) => a[0].localeCompare(b[0])),
    [
      ["creditaddon25", 25, "inapp", null],
      ["creditaddon50", 50, "inapp", null],
      ["creditaddon100", 100, "inapp", null],
      ["creditaddon200", 200, "inapp", null],
      ["creditaddon500", 500, "inapp", null],
    ].sort((a, b) => a[0].localeCompare(b[0]))
  );

  const businessOneTime = mappings.filter(x => ["bizaosupmigration", "bizaosupsetup", "bizaosupdomain"].includes(x.productId));
  assert.equal(businessOneTime.length, 3);
  assert.ok(businessOneTime.every(x => x.productType === "inapp" && x.basePlanId === null));

  const companionBasicYearlyId = mappings.find(x => x.productId === "companionbasic" && x.basePlanId === "yearly");
  assert.equal(companionBasicYearlyId.billingPeriod, "monthly");

  assert.ok(!mappings.some(x => ["frontierexplorer", "frontierpro", "frontierenterprise"].includes(x.productId)));
  assert.ok(!mappings.some(x => ["bizaoextraassistant", "bizaoteamuser"].includes(x.productId)));

  assert.equal(validateCommercialCatalog(COMMERCIAL_CATALOG).ok, true);
});
