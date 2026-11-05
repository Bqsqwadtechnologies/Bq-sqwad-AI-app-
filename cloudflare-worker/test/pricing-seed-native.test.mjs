import test from "node:test";
import assert from "node:assert/strict";
import { shouldSeedPricingDefaults } from "../src/pricing-seed-native.js";

test("version-five seed runs once and never overwrites a later admin edit", () => {
  let marker = null;
  let storedPrice = 0;
  const seed = () => {
    if (!shouldSeedPricingDefaults(marker, 5)) return;
    storedPrice = 0.045;
    marker = { applied: true, version: 5 };
  };
  seed();
  assert.equal(storedPrice, 0.045);
  storedPrice = 0.061; // an administrator changes the saved price
  seed();
  assert.equal(storedPrice, 0.061);
});

test("an old marker version is eligible for the one-time version-five migration", () => {
  assert.equal(shouldSeedPricingDefaults({ applied: true, version: 3 }, 5), true);
  assert.equal(shouldSeedPricingDefaults({ applied: true, version: 5 }, 5), false);
});
