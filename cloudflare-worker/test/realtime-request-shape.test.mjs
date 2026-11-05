import test from "node:test";
import assert from "node:assert/strict";
import { readFile, access } from "node:fs/promises";

test("realtime client-secret request uses the GA audio.output.voice field", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.match(source, /session:\s*\{[\s\S]*?audio:\s*\{[\s\S]*?output:\s*\{[\s\S]*?\bvoice\b/);
  assert.doesNotMatch(source, /session:\s*\{\s*type:\s*"realtime"\s*,\s*model\s*,\s*voice:/);
  assert.match(source, /const DEFAULT_REALTIME_MODEL = "gpt-realtime-2\.1-mini"/);
});


test("Android admin realtime pricing screen is removed because admin lives on the website", async () => {
  await assert.rejects(
    access(new URL("../../app/src/main/java/com/bqsqwad/ai/ui/AdminLivePricingScreen.kt", import.meta.url)),
    { code: "ENOENT" },
  );
});

test("realtime public errors use safe codes rather than provider diagnostics", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  for (const safeCode of [
    "provider_key_missing",
    "provider_billing",
    "realtime_request_rejected",
    "realtime_model_rejected",
    "realtime_session_failed"
  ]) assert.ok(source.includes(safeCode), `missing safe public error code: ${safeCode}`);
  assert.match(source, /const body = \{ error: code \};/);
  assert.doesNotMatch(source, /const body = \{[^}]*providerStatus/);
});

test("live voice and camera prices are returned from the shared per-second credit rules", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.match(source, /url\.pathname === "\/pricing" \|\| url\.pathname === "\/live\/pricing"/);
  assert.match(source, /creditsPerSecond: rule\.unit === "second" \? Number\(rule\.creditsPerUnit \|\| 0\) : 0/);
  assert.match(source, /minimumBalanceToStart: creditsPerMinute/);
  assert.ok(source.includes('"creditsPerUnit": 0.045'));
  assert.ok(source.includes('"creditsPerUnit": 0.09'));
  assert.doesNotMatch(source, /0\.34056155|0\.72977475/);
});



test("Android live price labels read only the shared public pricing endpoint", async () => {
  const client = await readFile(new URL("../../app/src/main/java/com/bqsqwad/ai/core/WorkspaceApiClient.kt", import.meta.url), "utf8");
  const app = await readFile(new URL("../../app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt", import.meta.url), "utf8");
  assert.match(client, /livePricing\(\): JSONObject = request\("\/pricing", "GET"\)/);
  assert.match(app, /WorkspaceApiClient\(\)\.livePricing\(\)/);
  assert.match(app, /liveCameraPriceLabel = if \(cameraRate\.isFinite\(\)\)/);
  assert.match(app, /liveVoicePriceLabel = if \(voiceRate\.isFinite\(\)\)/);
});

test("Settings shows the signed-in account credit balance from the authenticated credits endpoint", async () => {
  const app = await readFile(new URL("../../app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt", import.meta.url), "utf8");
  const api = await readFile(new URL("../../app/src/main/java/com/bqsqwad/ai/core/WorkspaceApiClient.kt", import.meta.url), "utf8");
  const card = await readFile(new URL("../../app/src/main/java/com/bqsqwad/ai/ui/chat/WorkspaceTeamScreen.kt", import.meta.url), "utf8");
  assert.match(api, /suspend fun credits\(\):JSONObject = request\("\/credits","GET"\)/);
  assert.match(app, /creditBalanceLabel = if \(creditBalanceLoaded\) creditBalanceLabel else "Loading balance…"/);
  assert.match(app, /SettingsCreditsCard\([\s\S]*?balanceLabel = creditBalanceLabel/);
  assert.match(app, /isAdminUnlimited = creditBalanceLabel == "Credits: Unlimited"/);
  assert.match(app, /if \(settingsCategory == "All settings" \|\| settingsCategory == "Account"\)[\s\S]*?SettingsCreditsCard/);
  assert.match(card, /Text\("Credit balance"/);
});

test("opt-in training library is admin-only and checks explicit consent before returning examples", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.match(source, /url\.pathname === \"\/admin\/training-library\" && request\.method === \"GET\"/);
  assert.match(source, /if \(!isSuperAdmin\(u, env\)\) throw new Error\(\"Admin access required\"\)/);
  assert.match(source, /fieldPath:\s*\"trainingConsent\"[\s\S]*?booleanValue:\s*true/);
  assert.match(source, /firestoreQuery\("consented_contributions"/);
});

test("heartbeat has no undefined trackedCost reference", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.doesNotMatch(source, /trackedCost/);
});

test("normal realtime sessions explicitly disable transcription", async () => {
  const source = await readFile(new URL("../../app/src/main/java/com/bqsqwad/ai/core/RealtimeVoiceSession.kt", import.meta.url), "utf8");
  assert.match(source, /put\("transcription", if \(silentTextMode\)[\s\S]*?else JSONObject\.NULL\)/);
});

test("realtime session clamps stale Firestore model IDs to the supported model", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.match(source, /const ALLOWED_REALTIME_MODELS = new Set\(\["gpt-realtime-2\.1-mini"\]\)/);
  assert.match(source, /if \(!ALLOWED_REALTIME_MODELS\.has\(model\)\)[\s\S]*?model = DEFAULT_REALTIME_MODEL/);
  assert.match(source, /model = await pickAccessibleModel\(env, model, \/\^gpt-realtime\/\)/);
});
