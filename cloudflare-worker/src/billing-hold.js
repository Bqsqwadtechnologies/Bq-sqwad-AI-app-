/**
 * Pure hold/settle helpers for measured-token billing.
 * A small balance must never block a cheap reply.
 * A reply that was already delivered must never be rejected for overage.
 */
const EPS = 1e-9;

export function round5(n) {
  return Math.round((Number(n) || 0) * 1e5) / 1e5;
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
