/**
 * Pure hold/settle helpers for measured-token billing.
 * A small balance must never block a cheap reply.
 * A reply that was already delivered must never be rejected for overage.
 */
const EPS = 1e-9;

export function round5(n) {
  return Math.round((Number(n) || 0) * 1e5) / 1e5;
}

/**
 * Deterministic mixed-balance drain order:
 * - BQ-7-Star: daily free -> paid subscription -> permanent purchased credits.
 * - BQ-Codron (purchasedOnly): paid subscription -> permanent purchased credits.
 * The daily allowance and retired trial bucket are never used for BQ-Codron.
 */
export function splitCreditBuckets({ daily, subscription, permanent, amount, purchasedOnly = false }) {
  const free = purchasedOnly ? 0 : Math.max(0, Number(daily) || 0);
  const sub = Math.max(0, Number(subscription) || 0);
  const paid = Math.max(0, Number(permanent) || 0);
  const requested = Math.max(0, Number(amount) || 0);
  const spendable = free + sub + paid;
  if (spendable + EPS < requested) {
    return { ok: false, spendable, fromDaily: 0, fromSubscription: 0, fromPermanent: 0 };
  }
  const fromDaily = Math.min(free, requested);
  const afterDaily = requested - fromDaily;
  const fromSubscription = Math.min(sub, afterDaily);
  const fromPermanent = Math.max(0, afterDaily - fromSubscription);
  return { ok: true, spendable, fromDaily, fromSubscription, fromPermanent };
}

/** Hold at most what the balance can cover. */
export function computeHold({ balanceTotal, estimate }) {
  const spendable = Math.max(0, Number(balanceTotal) || 0);
  if (spendable <= EPS) return { ok: false, hold: 0 };
  return { ok: true, hold: round5(Math.min(spendable, Math.max(0, Number(estimate) || 0))) };
}

/** Settle to measured usage. Never reject a reply that was already delivered. */
export function computeSettlement({ held, actual, balanceAfterHold }) {
  const want = Math.max(0, round5(actual));
  const canTake = Math.max(0, held + Math.max(0, balanceAfterHold));
  const charge = Math.min(want, canTake);
  return {
    charge: round5(charge),
    refund: round5(Math.max(0, held - charge)),
    extraTaken: round5(Math.max(0, charge - held)),
    shortfall: round5(Math.max(0, want - charge))
  };
}
