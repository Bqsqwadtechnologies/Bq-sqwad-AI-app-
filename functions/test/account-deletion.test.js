import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

test("account deletion covers ordinary chats, secret chats, tombstones and private user files", async () => {
  const source = await readFile(new URL("../index.js", import.meta.url), "utf8");
  const start = source.indexOf("async function deleteUserAccountData(uid)");
  const end = source.indexOf("async function userFromRequest(req)", start);
  assert.ok(start >= 0 && end > start, "account deletion implementation exists");
  const cleanup = source.slice(start, end);
  for (const collection of ["users", "chats", "chatHistory", "secretChats", "chatDeletionTombstones"]) {
    assert.ok(cleanup.includes(JSON.stringify(collection)), "missing account cleanup for " + collection);
  }
  for (const prefix of [
    '"users/" + uid + "/uploads/"',
    '"users/" + uid + "/generated-files/"',
    '"avatars/" + uid + "/"'
  ]) {
    assert.ok(cleanup.includes(prefix), "missing private storage cleanup prefix " + prefix);
  }
  assert.match(cleanup, /await bucket\.deleteFiles\(\{ prefix \}\)/);
});
