import test from "node:test";
import assert from "node:assert/strict";
import { PLAN_CATALOG, COMMERCIAL_CATALOG, canonicalPlayProductId, validateCommercialCatalog } from "../plan-catalog.js";

test("canonical plan/product IDs contain no underscores or spaces", () => {
  for (const [group, rows] of Object.entries(PLAN_CATALOG)) {
    if (!Array.isArray(rows)) continue;
    for (const row of rows) {
      if (!Array.isArray(row)) continue;
      const id = String(row[0] || "");
      assert.match(id, /^[a-z0-9-]+$/, group + " contains invalid ID: " + id);
      assert.equal(canonicalPlayProductId(id), id);
    }
  }
});

test("trial is only the canonical 3-day offer and add-ons have no trial", () => {
  assert.equal(PLAN_CATALOG.trialOfferId, "trial-3d");
  assert.equal(PLAN_CATALOG.addOnTrial, false);
});

test("business price-limit blocked annual plans remain Contact Sales", () => {
  assert.equal(PLAN_CATALOG.notes.yearlyBusinessProfessional.includes("Contact Sales"), true);
  assert.equal(PLAN_CATALOG.notes.yearlyBusiness.includes("Contact Sales"), true);
  assert.equal(PLAN_CATALOG.notes.yearlyBusinessEnterprise.includes("Contact Sales"), true);
});


test("complete canonical catalog validates and has no underscore commercial IDs", () => {
  const result = validateCommercialCatalog(COMMERCIAL_CATALOG);
  assert.equal(result.ok, true);
  assert.ok(result.planCount > 0);
  assert.ok(result.productCount > 0);
  for (const rows of Object.values(COMMERCIAL_CATALOG.sections)) {
    for (const item of rows) {
      assert.match(item.id, /^[a-z0-9-]+$/);
      assert.equal(item.id.includes("_"), false);
      if (item.productId) assert.equal(String(item.productId).includes("_"), false);
      for (const tier of item.quantityTiers || []) assert.match(String(tier.tierId), /^[a-z0-9-]+$/);
    }
  }
});

test("one-time services are not recurring and add-ons have no trial", () => {
  const setup = COMMERCIAL_CATALOG.sections.businessAddOns.find(x => x.id === "bizaosupsetup");
  assert.equal(setup.productType, "inapp");
  assert.equal(setup.recurring, false);
  const addon = COMMERCIAL_CATALOG.sections.businessAddOns.find(x => x.id === "bizaoadvmultimodal");
  assert.equal(addon.trialEligible, false);
  assert.equal(addon.offerId, null);
});
