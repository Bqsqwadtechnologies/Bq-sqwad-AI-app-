import test from "node:test";
import assert from "node:assert/strict";
import { dailyCreditDateKey, currentDailyFreeCredits, shouldRestoreDailyFreeCredits, dailyFreeCreditGrant } from "../src/credit-cycle.js";

test("Africa/Lagos day rolls over at local midnight", () => {
  assert.equal(dailyCreditDateKey(new Date("2026-01-01T22:59:59.000Z"), "Africa/Lagos"), "2026-01-01");
  assert.equal(dailyCreditDateKey(new Date("2026-01-01T23:00:00.000Z"), "Africa/Lagos"), "2026-01-02");
});

test("daily grant replaces prior-day unused credits instead of stacking", () => {
  const old = { dailyFreeCredits: 4, dailyFreeCreditsDate: "2026-01-01", dailyFreeCreditsTimeZone: "Africa/Lagos" };
  const now = new Date("2026-01-01T23:00:00.000Z");
  assert.equal(currentDailyFreeCredits(old, now), 0);
  const grant = dailyFreeCreditGrant(5, now, "Africa/Lagos");
  assert.equal(grant.dailyFreeCredits, 5);
  assert.equal(grant.dailyFreeCreditsDate, "2026-01-02");
});

test("configured renewal time is applied in the configured time zone", () => {
  // 06:30 UTC is 07:30 in Lagos; an 08:00 local renewal has not happened yet.
  assert.equal(dailyCreditDateKey(new Date("2026-01-02T06:30:00.000Z"), "Africa/Lagos", "08:00"), "2026-01-01");
  assert.equal(dailyCreditDateKey(new Date("2026-01-02T07:00:00.000Z"), "Africa/Lagos", "08:00"), "2026-01-02");
});

test("a failed reservation cannot restore a prior-day daily bucket", () => {
  const profile = { dailyFreeCredits: 5, dailyFreeCreditsDate: "2026-01-02", dailyFreeCreditsTimeZone: "Africa/Lagos" };
  const reservation = { dailyFreeCreditsDate: "2026-01-01", dailyFreeCreditsTimeZone: "Africa/Lagos" };
  assert.equal(shouldRestoreDailyFreeCredits(profile, reservation, new Date("2026-01-01T23:00:00.000Z")), false);
});
