import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

test("secret chats are authenticated, owner-scoped and ciphertext-only", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  const routeStart = source.indexOf('if (url.pathname === "/secret-chats" && request.method === "GET")');
  const routeEnd = source.indexOf('if (url.pathname === "/ai" && request.method === "POST")', routeStart);
  assert.ok(routeStart >= 0 && routeEnd > routeStart, "secret-chat route block exists");
  const routes = source.slice(routeStart, routeEnd);

  assert.match(routes, /const user = await verifyFirebaseUser\(request\)/);
  assert.match(routes, /fieldPath: "uid"[\s\S]*?stringValue: user\.uid/);
  assert.match(routes, /if \(!row \|\| row\.uid !== user\.uid\)/);
  assert.match(routes, /\["messages", "plaintext", "text", "transcript", "title", "parts"\]/);
  assert.match(routes, /readBodyLimited\(request, 2_000_000\)/);
  assert.match(routes, /salt\.length !== 24/);
  assert.match(routes, /iv\.length !== 16/);
  assert.match(routes, /ciphertext\.length > 900000/);
  assert.ok(routes.includes('"secretChats/" + user.uid + "_" + chatId'));
  assert.match(routes, /if \(!existing \|\| existing\.uid !== user\.uid\)/);
  assert.match(routes, /if \(existing && existing\.uid !== user\.uid\)/);
  assert.match(routes, /firestoreCommit\(\[\{[\s\S]*?updateTime: existing\.__updateTime/);
  assert.match(routes, /create: true/);

  const deletionStart = source.indexOf("async function permanentlyDeleteAccount(uid)");
  assert.ok(deletionStart >= 0, "account deletion function exists");
  const deletion = source.slice(deletionStart, deletionStart + 8000);
  assert.match(deletion, /"chats"/);
  assert.match(deletion, /"chatDeletionTombstones"/);
  assert.match(deletion, /"secretChats"/);
  for (const prefix of [
    '"/generated-images/"',
    '"/uploads/"',
    '"/generated-files/"',
    '"avatars/"',
    '"business-training/"'
  ]) assert.ok(deletion.includes(prefix), `missing account deletion storage prefix ${prefix}`);
  assert.ok(deletion.includes("await deleteFirebaseStoragePrefix(prefix)"));
  assert.ok(!deletion.includes("deleteFirebaseStoragePrefix(prefix).catch"));
});
