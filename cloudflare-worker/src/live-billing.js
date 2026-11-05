export function generationCreditsForSeconds(rule, seconds) {
  const duration = Math.max(0, Number(seconds) || 0);
  if (rule?.unit === "second") return Number(rule.creditsPerUnit || 0) * duration;
  if (rule?.unit === "minute") return Number(rule.creditsPerUnit || 0) * duration / 60;
  if (Number(rule?.creditsPerUnit || 0) === 0) return 0;
  throw Object.assign(new Error("generation_price_unit_invalid"), { httpStatus: 409, safeError: "provider_unavailable" });
}

export function calculateLiveHeartbeatCharge(liveRule, transcriptionRule, seconds, smart = false) {
  const liveCredits = generationCreditsForSeconds(liveRule, seconds);
  const transcriptionCredits = smart
    ? generationCreditsForSeconds(transcriptionRule, seconds)
    : 0;
  return liveCredits + transcriptionCredits;
}
