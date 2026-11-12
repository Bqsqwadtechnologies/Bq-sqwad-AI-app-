export const WELCOME_CREDIT_AMOUNT = 2;
export const WELCOME_CREDIT_POLICY_START = "2026-11-12T00:00:00.000Z";

export function firebaseCreatedAtMillis(value) {
  const numeric = Number(value);
  if (Number.isFinite(numeric) && numeric > 0) return numeric;
  const parsed = Date.parse(String(value || ""));
  return Number.isFinite(parsed) ? parsed : 0;
}

export function isEligibleForWelcomeCredits(createdAt) {
  const createdAtMs = firebaseCreatedAtMillis(createdAt);
  return createdAtMs >= Date.parse(WELCOME_CREDIT_POLICY_START);
}
