import test from "node:test";
import assert from "node:assert/strict";
import { currentDailyFreeCredits, shouldRestoreDailyFreeCredits } from "../src/credit-cycle.js";

test("legacy daily-free fields are never spendable for free or paid accounts", () => {
  const now = new Date("2026-01-01T23:00:00.000Z");
  const profiles = [
    { dailyFreeCredits: 1, dailyFreeCreditsDate: "2026-01-02", subscriptionPlan: "free" },
    { dailyFreeCredits: 50, dailyFreeCreditsDate: "2026-01-02", hasEverPurchasedCredits: true, subscriptionPlan: "premium" }
  ];
  for (const profile of profiles) assert.equal(currentDailyFreeCredits(profile, now), 0);
});

test("failed legacy reservations never restore a retired daily bucket", () => {
  const profile = { dailyFreeCredits: 5, dailyFreeCreditsDate: "2026-01-02", dailyFreeCreditsTimeZone: "Africa/Lagos" };
  const reservation = { dailyFreeCreditsDate: "2026-01-02", dailyFreeCreditsTimeZone: "Africa/Lagos" };
  assert.equal(shouldRestoreDailyFreeCredits(profile, reservation, new Date("2026-01-02T12:00:00.000Z")), false);
});
