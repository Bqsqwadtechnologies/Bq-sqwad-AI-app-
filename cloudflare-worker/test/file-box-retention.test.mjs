import test from "node:test";
import assert from "node:assert/strict";
import { retainFileBoxItems } from "../src/file-box-retention.js";

test("retainFileBoxItems keeps the eight newest items across binary and text entries", () => {
  const binaryItems = Array.from({ length: 9 }, (_, i) => ({
    id: "b-" + i,
    objectPath: "users/u/images/" + i + ".png",
    createdAt: new Date(Date.UTC(2026, 0, i + 1)).toISOString()
  }));
  const textItems = Array.from({ length: 9 }, (_, i) => ({
    id: "t-" + i,
    content: "text " + i,
    createdAt: new Date(Date.UTC(2026, 1, i + 1)).toISOString()
  }));

  const retained = retainFileBoxItems([...binaryItems, ...textItems]);
  assert.equal(retained.length, 8);
  assert.deepEqual(retained.map(item => item.id), ["t-1", "t-2", "t-3", "t-4", "t-5", "t-6", "t-7", "t-8"]);
  assert.equal(retained.some(item => item.id === "b-8"), false);
});

test("retainFileBoxItems handles empty, invalid and malformed timestamps safely", () => {
  assert.deepEqual(retainFileBoxItems([]), []);
  assert.deepEqual(retainFileBoxItems(null), []);
  const rows = [{ id: "invalid", createdAt: "not-a-date" }, { id: "valid", createdAt: "2026-01-01T00:00:00.000Z" }];
  assert.deepEqual(retainFileBoxItems(rows).map(item => item.id), ["invalid", "valid"]);
});
