import test from "node:test";
import assert from "node:assert/strict";
import { processPlayRtdn, verifyPlayPurchase } from "../google-play.js";

test("google-play module parses and exports verification handlers", () => {
  assert.equal(typeof processPlayRtdn, "function");
  assert.equal(typeof verifyPlayPurchase, "function");
});
