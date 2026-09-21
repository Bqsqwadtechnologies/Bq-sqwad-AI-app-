import test from "node:test";
import assert from "node:assert/strict";
import { PLAN_CATALOG, canonicalPlayProductId } from "../plan-catalog.js";

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
