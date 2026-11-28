import test from "node:test";
import assert from "node:assert/strict";
import { retainFileBoxItems } from "../src/file-box-retention.js";

test("retainFileBoxItems keeps the 8 newest binary files and 8 newest text entries", () => {
  const binaryItems = Array.from({ length: 15 }, (_, i) => ({
    id: `b-${i}`,
    objectPath: `users/u/images/${i}.png`,
    createdAt: new Date(Date.UTC(2026, 0, i + 1)).toISOString()
  }));
  const textItems = Array.from({ length: 12 }, (_, i) => ({
    id: `t-${i}`,
    content: `text ${i}`,
    createdAt: new Date(Date.UTC(2026, 1, i + 1)).toISOString()
  }));

  const retained = retainFileBoxItems([...binaryItems, ...textItems]);
  const retainedBinary = retained.filter(item => item.objectPath);
  const retainedText = retained.filter(item => !item.objectPath);

  assert.equal(retainedBinary.length, 8);
  assert.equal(retainedText.length, 8);
  // Oldest binary (b-0 through b-6) should be evicted; newest (b-7 to b-14) kept
  assert.equal(retainedBinary.some(i => i.id === "b-0"), false);
  assert.equal(retainedBinary.some(i => i.id === "b-14"), true);
});

test("retainFileBoxItems handles empty or invalid lists safely", () => {
  assert.deepEqual(retainFileBoxItems([]), []);
  assert.deepEqual(retainFileBoxItems(null), []);
});
