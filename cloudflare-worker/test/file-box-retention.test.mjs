import test from "node:test";
import assert from "node:assert/strict";
import { retainFileBoxItems } from "../src/file-box-retention.js";

test("retains newest 200 binary uploads and 20 inline text items", () => {
  const binary = Array.from({ length: 205 }, (_, i) => ({
    id: "b" + i, objectPath: "file-box/u/b" + i, createdAt: new Date(i * 1000).toISOString(),
  }));
  const text = Array.from({ length: 25 }, (_, i) => ({
    id: "t" + i, content: "text", createdAt: new Date((300 + i) * 1000).toISOString(),
  }));
  const retained = retainFileBoxItems([...binary, ...text]);
  assert.equal(retained.filter((item) => item.objectPath).length, 200);
  assert.equal(retained.filter((item) => !item.objectPath).length, 20);
  assert.equal(retained.length, 220);
});

test("orders retained items chronologically", () => {
  const retained = retainFileBoxItems([
    { id: "late", objectPath: "late", createdAt: "2026-02-01T00:00:00.000Z" },
    { id: "early", objectPath: "early", createdAt: "2026-01-01T00:00:00.000Z" },
  ]);
  assert.deepEqual(retained.map((item) => item.id), ["early", "late"]);
});

test("empty and missing lists are safe", () => {
  assert.deepEqual(retainFileBoxItems([]), []);
  assert.deepEqual(retainFileBoxItems(undefined), []);
});
