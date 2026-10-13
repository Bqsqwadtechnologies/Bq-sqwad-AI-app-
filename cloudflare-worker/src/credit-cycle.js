export const DEFAULT_FREE_CREDIT_TIME_ZONE = "Africa/Lagos";

export function dailyCreditDateKey(date = new Date(), timeZone = DEFAULT_FREE_CREDIT_TIME_ZONE, renewalTime = "00:00") {
  const instant = date instanceof Date ? date : new Date(date);
  const format = (zone) => {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", hourCycle: "h23"
    }).formatToParts(instant);
    const part = (type) => parts.find((item) => item.type === type)?.value || "";
    let year = Number(part("year")), month = Number(part("month")), day = Number(part("day"));
    const localMinutes = Number(part("hour")) * 60 + Number(part("minute"));
    const match = /^(\\d{1,2}):(\\d{2})$/.exec(String(renewalTime || "00:00"));
    const renewalMinutes = match ? Math.min(1439, Number(match[1]) * 60 + Number(match[2])) : 0;
    if (localMinutes < renewalMinutes) {
      const previous = new Date(Date.UTC(year, month - 1, day) - 86400000);
      year = previous.getUTCFullYear();
      month = previous.getUTCMonth() + 1;
      day = previous.getUTCDate();
    }
    return String(year).padStart(4, "0") + "-" + String(month).padStart(2, "0") + "-" + String(day).padStart(2, "0");
  };
  try { return format(String(timeZone || DEFAULT_FREE_CREDIT_TIME_ZONE)); }
  catch { return format(DEFAULT_FREE_CREDIT_TIME_ZONE); }
}

export function currentDailyFreeCredits(profile, now = new Date(), fallbackTimeZone = DEFAULT_FREE_CREDIT_TIME_ZONE) {
  if (!profile) return 0;
  const zone = String(profile.dailyFreeCreditsTimeZone || fallbackTimeZone || DEFAULT_FREE_CREDIT_TIME_ZONE);
  return String(profile.dailyFreeCreditsDate || "") === dailyCreditDateKey(now, zone, profile.dailyFreeCreditsRenewalTime || "00:00") ? Math.max(0, Number(profile.dailyFreeCredits || 0)) : 0;
}

export function shouldRestoreDailyFreeCredits(profile, reservation, now = new Date(), fallbackTimeZone = DEFAULT_FREE_CREDIT_TIME_ZONE) {
  if (!profile || !reservation) return false;
  const zone = String(profile.dailyFreeCreditsTimeZone || reservation.dailyFreeCreditsTimeZone || fallbackTimeZone || DEFAULT_FREE_CREDIT_TIME_ZONE);
  const renewalTime = String(profile.dailyFreeCreditsRenewalTime || reservation.dailyFreeCreditsRenewalTime || "00:00");
  const today = dailyCreditDateKey(now, zone, renewalTime);
  return String(profile.dailyFreeCreditsDate || "") === today && String(reservation.dailyFreeCreditsDate || "") === today;
}

export function dailyFreeCreditGrant(credits, now = new Date(), timeZone = DEFAULT_FREE_CREDIT_TIME_ZONE, renewalTime = "00:00") {
  return {
    dailyFreeCredits: Math.max(0, Number(credits || 0)),
    dailyFreeCreditsDate: dailyCreditDateKey(now, timeZone, renewalTime),
    dailyFreeCreditsTimeZone: String(timeZone || DEFAULT_FREE_CREDIT_TIME_ZONE),
    dailyFreeCreditsRenewalTime: String(renewalTime || "00:00"),
    dailyFreeCreditsGrantedAt: now
  };
}
