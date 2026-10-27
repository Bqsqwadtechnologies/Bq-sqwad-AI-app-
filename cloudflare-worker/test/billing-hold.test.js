import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { computeHold, computeSettlement, round5 } from "../src/billing-hold.js";

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
});
