import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { computeHold, computeSettlement, round5, splitCreditBuckets } from "../src/billing-hold.js";

describe("billing-hold", () => {
  it("blocks when balance is empty", () => {
    const h = computeHold({ balanceTotal: 0, estimate: 0.9 });
    assert.equal(h.ok, false);
    assert.equal(h.hold, 0);
  });

  it("holds only what the balance can cover", () => {
    const h = computeHold({ balanceTotal: 0.04, estimate: 0.9 });
    assert.equal(h.ok, true);
    assert.equal(h.hold, 0.04);
  });

  it("holds the full estimate when balance is sufficient", () => {
    const h = computeHold({ balanceTotal: 2, estimate: 0.9 });
    assert.equal(h.ok, true);
    assert.equal(h.hold, 0.9);
  });

  it("settles under hold with refund", () => {
    const s = computeSettlement({ held: 0.9, actual: 0.012, balanceAfterHold: 0 });
    assert.equal(s.charge, 0.012);
    assert.equal(s.refund, 0.888);
    assert.equal(s.extraTaken, 0);
    assert.equal(s.shortfall, 0);
  });

  it("takes extra from remaining balance and records shortfall", () => {
    const s = computeSettlement({ held: 0.04, actual: 0.1, balanceAfterHold: 0.03 });
    assert.equal(s.charge, 0.07);
    assert.equal(s.refund, 0);
    assert.equal(s.extraTaken, 0.03);
    assert.equal(s.shortfall, 0.03);
  });
  it("drains BQ-7-Star from daily, then subscription, then permanent credits", () => {
    const split = splitCreditBuckets({
      daily: 0.2,
      subscription: 0.3,
      permanent: 0.5,
      amount: 0.4,
    });
    assert.deepEqual(split, {
      ok: true,
      spendable: 1,
      fromDaily: 0.2,
      fromSubscription: 0.2,
      fromPermanent: 0,
    });
  });

  it("drains BQ-Codron from purchased buckets and leaves daily free credit untouched", () => {
    const split = splitCreditBuckets({
      daily: 1,
      subscription: 0.2,
      permanent: 0.5,
      amount: 0.4,
      purchasedOnly: true,
    });
    assert.deepEqual(split, {
      ok: true,
      spendable: 0.7,
      fromDaily: 0,
      fromSubscription: 0.2,
      fromPermanent: 0.2,
    });
  });

  it("rejects a BQ-Codron reservation that only the daily allowance could cover", () => {
    const split = splitCreditBuckets({
      daily: 1,
      subscription: 0,
      permanent: 0,
      amount: 0.1,
      purchasedOnly: true,
    });
    assert.equal(split.ok, false);
    assert.equal(split.spendable, 0);
  });

});
