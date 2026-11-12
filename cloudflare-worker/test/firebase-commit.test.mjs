import test from "node:test";
import assert from "node:assert/strict";
import { buildFirestoreCommitBody } from "../src/firebase.js";

const documentName = (path) => `projects/example/databases/(default)/documents/${path}`;

test("partial account updates carry a field mask and cannot erase omitted fields", () => {
  const body = buildFirestoreCommitBody([
    {
      path: "users/user-a",
      data: { credits: 0.97, dailyFreeCredits: 0.42, updatedAt: new Date("2026-11-06T03:00:00Z") },
      updateTime: "2026-11-06T02:59:00Z",
    },
  ], documentName);

  assert.deepEqual(body.writes[0].updateMask, {
    fieldPaths: ["credits", "dailyFreeCredits", "updatedAt"],
  });
  assert.equal("dailyFreeCreditsDate" in body.writes[0].update.fields, false);
  assert.equal(body.writes[0].updateMask.fieldPaths.includes("dailyFreeCreditsDate"), false);
  assert.deepEqual(body.writes[0].currentDocument, { updateTime: "2026-11-06T02:59:00Z" });
});

test("each update gets its own mask and exclusive creation preconditions survive", () => {
  const body = buildFirestoreCommitBody([
    { path: "users/user-a", data: { credits: 4 } },
    { path: "creditReservations/reservation-a", data: { uid: "user-a", status: "reserved" }, exists: false },
  ], documentName);

  assert.deepEqual(body.writes[0].updateMask, { fieldPaths: ["credits"] });
  assert.deepEqual(body.writes[1].updateMask, { fieldPaths: ["uid", "status"] });
  assert.equal(body.writes[1].update.fields.uid.stringValue, "user-a");
  assert.deepEqual(body.writes[1].currentDocument, { exists: false });
});

test("an explicit create becomes a masked update with an exists:false precondition", () => {
  const body = buildFirestoreCommitBody([
    { path: "creditTransactions/transaction-a", data: { uid: "user-a" }, create: true },
  ], documentName);

  assert.equal(body.writes[0].create, undefined);
  assert.equal(body.writes[0].update.fields.uid.stringValue, "user-a");
  assert.deepEqual(body.writes[0].updateMask, { fieldPaths: ["uid"] });
  assert.deepEqual(body.writes[0].currentDocument, { exists: false });
});

test("a new rate-limit window uses the valid Firestore REST creation contract", () => {
  const body = buildFirestoreCommitBody([
    {
      path: "rateLimits/worker_hash",
      data: { subjectHash: "hash", count: 1, startedAt: 1 },
      create: true,
    },
  ], documentName);

  assert.equal("create" in body.writes[0], false);
  assert.equal(body.writes[0].update.fields.count.integerValue, "1");
  assert.deepEqual(body.writes[0].currentDocument, { exists: false });
});

test("commit preserves reservation status updates without unmasking unrelated fields", () => {
  const body = buildFirestoreCommitBody([
    {
      path: "creditReservations/reservation-a",
      data: { uid: "user-a", status: "committed", providerId: "openai" },
      updateTime: "2026-11-06T03:01:00Z",
    },
  ], documentName);

  assert.deepEqual(body.writes[0].updateMask, {
    fieldPaths: ["uid", "status", "providerId"],
  });
  assert.equal(body.writes[0].update.fields.status.stringValue, "committed");
});
