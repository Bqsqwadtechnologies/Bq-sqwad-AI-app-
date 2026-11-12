export const DEFAULT_FREE_CREDIT_TIME_ZONE = "Africa/Lagos";

/** Daily free credits are retired; legacy Firestore fields are never spendable. */
export function currentDailyFreeCredits(_profile, _now = new Date(), _fallbackTimeZone = DEFAULT_FREE_CREDIT_TIME_ZONE) {
  return 0;
}

/** Legacy reservations must never restore a retired daily-free bucket. */
export function shouldRestoreDailyFreeCredits(_profile, _reservation, _now = new Date(), _fallbackTimeZone = DEFAULT_FREE_CREDIT_TIME_ZONE) {
  return false;
}
