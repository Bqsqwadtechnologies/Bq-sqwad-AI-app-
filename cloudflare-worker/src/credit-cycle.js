export const DEFAULT_FREE_CREDIT_TIME_ZONE = "Africa/Lagos";

export function dailyCreditDateKey(date = new Date(), timeZone = DEFAULT_FREE_CREDIT_TIME_ZONE) {
  const instant = date instanceof Date ? date : new Date(date);
  const format = (zone) => {
    const parts = new Intl.DateTimeFormat("en-US", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(instant);
    const part = (type) => parts.find((item) => item.type === type)?.value || "";
    return part("year") + "-" + part("month") + "-" + part("day");
  };
  try { return format(String(timeZone || DEFAULT_FREE_CREDIT_TIME_ZONE)); }
  catch { return format(DEFAULT_FREE_CREDIT_TIME_ZONE); }
}

export function currentDailyFreeCredits(profile, now = new Date(), fallbackTimeZone = DEFAULT_FREE_CREDIT_TIME_ZONE) {
  if (!profile) return 0;
  const zone = String(profile.dailyFreeCreditsTimeZone || fallbackTimeZone || DEFAULT_FREE_CREDIT_TIME_ZONE);
  return String(profile.dailyFreeCreditsDate || "") === dailyCreditDateKey(now, zone) ? Math.max(0, Number(profile.dailyFreeCredits || 0)) : 0;
}

export function shouldRestoreDailyFreeCredits(profile, reservation, now = new Date(), fallbackTimeZone = DEFAULT_FREE_CREDIT_TIME_ZONE) {
  if (!profile || !reservation) return false;
  const zone = String(profile.dailyFreeCreditsTimeZone || reservation.dailyFreeCreditsTimeZone || fallbackTimeZone || DEFAULT_FREE_CREDIT_TIME_ZONE);
  const today = dailyCreditDateKey(now, zone);
  return String(profile.dailyFreeCreditsDate || "") === today && String(reservation.dailyFreeCreditsDate || "") === today;
}

export function dailyFreeCreditGrant(credits, now = new Date(), timeZone = DEFAULT_FREE_CREDIT_TIME_ZONE, renewalTime = "00:00") {
  return {
    dailyFreeCredits: Math.max(0, Number(credits || 0)),
    dailyFreeCreditsDate: dailyCreditDateKey(now, timeZone),
    dailyFreeCreditsTimeZone: String(timeZone || DEFAULT_FREE_CREDIT_TIME_ZONE),
    dailyFreeCreditsRenewalTime: String(renewalTime || "00:00"),
    dailyFreeCreditsGrantedAt: now
  };
}
