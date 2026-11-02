import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const read = (path) => readFileSync(join(root, path), "utf8");

test("Business Inbox paging is authenticated, bounded and workspace-scoped", () => {
  const worker = read("src/business-native.js");
  const router = read("src/index.js");
  const client = read("../app/src/main/java/com/bqsqwad/ai/core/WorkspaceApiClient.kt");
  const screen = read("../app/src/main/java/com/bqsqwad/ai/ui/BusinessWorkspaceScreen.kt");

  assert.match(worker, /inboxLimit=Math\.min\(100,Math\.max\(1,Math\.floor\(Number\(options\.inboxLimit\)\|\|30\)\)\)/);
  assert.match(worker, /inboxOffset=Math\.max\(0,Math\.min\(100000,Math\.floor\(Number\(options\.inboxOffset\)\|\|0\)\)\)/);
  assert.match(worker, /out\.inboxPage=\{offset:inboxOffset,limit:inboxLimit,hasMore:out\.inbox\.length===inboxLimit\}/);
  assert.match(router, /verifyFirebaseUser\(request\).*listBusinessExtended\(u\.uid,String\(url\.searchParams\.get\("workspaceId"\)\|\|""\),\{inboxOffset:url\.searchParams\.get\("inboxOffset"\),inboxLimit:url\.searchParams\.get\("inboxLimit"\)\}\)/);
  assert.match(client, /suspend fun businessExtended\(workspaceId:String\?=null, inboxOffset:Int=0, inboxLimit:Int=30\)/);
  assert.match(screen, /Load more inbox messages/);
  assert.match(screen, /inboxOffset = currentItems\.length\(\)/);
});
