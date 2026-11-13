import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const worker = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
const firebase = await readFile(new URL("../src/firebase.js", import.meta.url), "utf8");
const android = await readFile(new URL("../../app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt", import.meta.url), "utf8");
const workspaceApi = await readFile(new URL("../../app/src/main/java/com/bqsqwad/ai/core/WorkspaceApiClient.kt", import.meta.url), "utf8");

test("admin bypass and credits snapshot share the Admin Plan Test Mode decision", () => {
  const bypassStart = worker.indexOf("async function adminBypassEnabled(");
  const bypassEnd = worker.indexOf("\nexport function assertSuperAdminIdentity", bypassStart);
  assert.ok(bypassStart >= 0 && bypassEnd > bypassStart);
  const bypass = worker.slice(bypassStart, bypassEnd);
  assert.match(bypass, /firestoreGet\("adminPlanTest\/" \+ user\.uid\)/);
  assert.match(bypass, /planTest\?\.enabled !== true/);

  const creditsStart = worker.indexOf('if (url.pathname === "/credits" && request.method === "GET")');
  const creditsEnd = worker.indexOf('\n      // Android reads these entitlement routes', creditsStart);
  assert.ok(creditsStart >= 0 && creditsEnd > creditsStart);
  const creditsRoute = worker.slice(creditsStart, creditsEnd);
  assert.match(creditsRoute, /if \(await adminBypassEnabled\(user, env\)\)/);
  assert.match(creditsRoute, /adminUnlimited: false/);
  assert.doesNotMatch(creditsRoute, /if \(isSuperAdmin\(user, env\)\)/);
});

test("admin-gifted permanent credits are part of the canonical spendable balance", () => {
  assert.match(worker, /const permanent = Math\.max\(0, Number\(profile\.credits \|\| 0\)\)/);
  assert.match(worker, /const nextPermanent = Math\.round\(\(oldBalance\.permanent \+ amount\) \* 1e7\) \/ 1e7/);
  assert.match(worker, /credits: nextPermanent/);
  assert.match(worker, /type: "admin_grant"/);
  assert.match(worker, /const spendableBalance = options\.purchasedOnly/);
  assert.match(worker, /permanent: balance\.permanent/);
});

test("Android applies the authoritative stream balance immediately without showing a receipt", () => {
  assert.match(android, /fun applyStreamBalance\(balanceAfter: Double\)/);
  assert.match(android, /reply\.balanceAfter\?\.let \{ applyStreamBalance\(it\) \}/);
  assert.match(android, /reply\.billingStatus != "admin_exempt"/);
  assert.doesNotMatch(android, /Credits charged:|Balance after reply:/i);
});

test("Android credits reads explicitly bypass HTTP caches", () => {
  assert.match(workspaceApi, /path == "\/credits" && method == "GET"/);
  assert.match(workspaceApi, /Cache-Control", "no-cache, no-store, max-age=0"/);
  assert.match(workspaceApi, /Pragma", "no-cache"/);
});

test("Firestore commit updates are field-masked and create-only records cannot overwrite existing documents", () => {
  assert.match(firebase, /updateMask: \{ fieldPaths: Object\.keys\(fields\) \}/);
  assert.match(firebase, /currentDocument: \{ exists: false \}/);
});
