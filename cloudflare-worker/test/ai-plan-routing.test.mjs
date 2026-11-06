import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const read = (path) => readFile(new URL(path, import.meta.url), "utf8");

test("TruthGuard Free has its own visible plan card and a five-check saved history list", async () => {
  const ui = await read("../../app/src/main/java/com/bqsqwad/ai/ui/TruthGuardWorkspace.kt");
  const worker = await read("../src/index.js");
  const legacy = await read("../src/legacy-native.js");
  assert.match(ui, /TruthGuardPlanStrip\(plans, activePlanId, isAdmin, onOpenPlans\)/);
  assert.match(ui, /activePlanId == "truthguardfree"/);
  assert.match(worker, /const historyLimit=catalog\.adminTestMode\?100:\(String\(catalog\.activePlanId\|\|"\)\.toLowerCase\(\)==="truthguardfree"\?5:100\)/);
  assert.match(legacy, /truthguardHistory\(uid,limit=100\)/);
  assert.match(legacy, /a\.slice\(0,safeLimit\)/);
});

test("TruthGuard Deep Evidence Sweep is Pro or Team only and uses Tavily plus GPT-6 Luna", async () => {
  const legacy = await read("../src/legacy-native.js");
  const ui = await read("../../app/src/main/java/com/bqsqwad/ai/ui/TruthGuardWorkspace.kt");
  assert.match(legacy, /deep=Boolean\(b\.deepResearch&&\(g\.adminTestMode\|\|\["tgpro","tgteam"\]\.includes\(plan\)\)\)/);
  assert.match(legacy, /callTavily\(env,claim,sourceLimit,deep\)/);
  assert.match(legacy, /primaryModelId:"gpt-6-luna",fallbackProviderIds:\[\]/);
  assert.match(ui, /enabled = proOrHigher/);
  assert.match(ui, /Deep Evidence Sweep requires TruthGuard Pro or Team/);
});

test("Device Intelligence defaults to GPT-6 Luna in both capability and published model registry", async () => {
  const worker = await read("../src/index.js");
  const registry = await read("../src/model-registry-native.js");
  assert.match(worker, /device_commands: \{ providerId: "openai", modelId: "gpt-6-luna"/);
  assert.match(worker, /primaryProviderId:control\.providerId\|\|"openai",primaryModelId:control\.modelId\|\|"gpt-6-luna"/);
  assert.match(worker, /else control=\{\.\.\.control,providerId:"openai",modelId:"gpt-6-luna"\}/);
  assert.match(registry, /feature: "device-commands", capability: "device_commands", providerId: "openai", modelId: "gpt-6-luna"/);
  assert.match(registry, /if \(definition\.feature === "device-commands"\) \{\s*providerId = "openai";\s*modelId = "gpt-6-luna";/);
});

test("Dictation waits for the user's explicit Transcribe tap instead of auto-charging", async () => {
  const app = await read("../../app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt");
  const worker = await read("../src/index.js");
  assert.doesNotMatch(app, /LaunchedEffect\(dictationReadyAudio\)[\s\S]{0,180}?transcribeDictation\(\)/);
  assert.match(app, /Button\(onClick = \{ transcribeDictation\(\) \}/);
  assert.match(worker, /activeGenerationRule\("speech\.transcribe"\)/);
  assert.match(worker, /chargeCredits\(user\.uid, chargeAmount, "dictation", user\)/);
});

test("Live voice lets Realtime server VAD create replies; transcription stays Smart-mode only", async () => {
  const android = await read("../../app/src/main/java/com/bqsqwad/ai/core/RealtimeVoiceSession.kt");
  const worker = await read("../src/index.js");
  assert.match(android, /put\("create_response", true\)/);
  assert.match(worker, /create_response: true/);
  assert.match(android, /if \(silentTextMode\) \{[\s\S]*?put\("transcription", JSONObject\(\)\.put\("model", "gpt-4o-mini-transcribe"\)\)/);
  assert.match(worker, /heartbeatBody\.smart === true\s*\? await activeGenerationRule\("speech\.transcribe"\)/);
});
