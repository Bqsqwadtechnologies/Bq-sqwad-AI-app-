import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { classifyWorkspaceScope, workspaceScopeSystemPrompt } from "../src/legacy-native.js";

test("TruthGuard accepts explicit checks and clear claim statements but not general questions", () => {
  assert.deepEqual(classifyWorkspaceScope("truthguard", "Check whether this claim is true"), { inScope: true });
  assert.deepEqual(classifyWorkspaceScope("truthguard", "The Earth orbits the Sun"), { inScope: true });
  assert.equal(classifyWorkspaceScope("truthguard", "What is photosynthesis?").inScope, false);
});

test("Learning accepts teaching requests and redirects unrelated requests to Main Chat", () => {
  assert.deepEqual(classifyWorkspaceScope("learning", "Teach me how photosynthesis works"), { inScope: true });
  for (const prompt of ["write me a love poem", "tell me the news", "build me an app", "tell me a joke"]) {
    const result = classifyWorkspaceScope("learning", prompt);
    assert.equal(result.inScope, false, prompt);
    assert.equal(result.redirect.workspace, "CHAT", prompt);
    assert.match(result.redirect.message, /Main Chat/, prompt);
  }
});

test("dedicated workspaces redirect unrelated prompts before any charge call is needed", () => {
  for (const [workspace, prompt] of [
    ["truthguard", "Write me a funny poem"],
    ["life_mathis", "Generate a picture of a car"],
    ["life_matters", "Tell me the latest news"],
    ["companion", "Build an Android app"],
    ["personal_intelligence", "Tell me the latest news"],
    ["data_analyst", "Write me a love poem"],
    ["frontier_lab", "What is the weather today?"],
    ["video", "Explain algebra to me"]
  ]) {
    const result = classifyWorkspaceScope(workspace, prompt);
    assert.equal(result.inScope, false, workspace);
    assert.ok(result.redirect?.workspace, workspace);
  }
});


test("dedicated workspace system prompts keep the model inside its job", () => {
  for (const workspace of ["learning", "truthguard", "life_mathis", "life_matters", "companion", "personal_intelligence", "data_analyst", "frontier_lab", "video_generation"]) {
    const prompt = workspaceScopeSystemPrompt(workspace);
    assert.match(prompt, /STRICT WORKSPACE BOUNDARY/);
    assert.match(prompt, /Do not act as Main Chat/);
  }
  assert.equal(workspaceScopeSystemPrompt("chat"), "");
});


test("out-of-scope redirects are checked before direct workspace credit charging", async () => {
  const source = await readFile(new URL("../src/legacy-native.js", import.meta.url), "utf8");
  for (const [name, guard, charge] of [
    ["learningSession", 'workspaceScopeRedirect("learning",goal)', 'charge(user.uid'],
    ["truthguardCheck", 'workspaceScopeRedirect("truthguard",claim)', 'charge(user.uid'],
    ["companionChat", 'workspaceScopeRedirect("companion", m)', 'charge(user.uid'],
    ["companionCheckin", 'workspaceScopeRedirect("companion", p)', 'charge(user.uid']
  ]) {
    const start = source.indexOf("export async function " + name);
    assert.notEqual(start, -1, name + " handler must exist");
    const next = source.indexOf("\nexport async function ", start + 1);
    const body = source.slice(start, next < 0 ? source.length : next);
    const guardIndex = body.indexOf(guard);
    const chargeIndex = body.indexOf(charge);
    assert.ok(guardIndex >= 0 && chargeIndex > guardIndex, name + " must guard before charging");
  }
  const indexSource = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  const aiStart = indexSource.indexOf("async function ai(");
  const aiGuard = indexSource.indexOf("workspaceScopeRedirect(capabilityName, userMessage)", aiStart);
  const aiCharge = indexSource.indexOf("chargeCredits(user.uid", aiStart);
  assert.ok(aiGuard >= 0 && aiCharge > aiGuard, "generic AI route must guard before charging");
  const videoStart = indexSource.indexOf('url.pathname === "/video/generate"');
  const videoGuard = indexSource.indexOf('workspaceScopeRedirect("video",videoPrompt)', videoStart);
  const videoCreate = indexSource.indexOf("createVideoNative(", videoStart);
  assert.ok(videoGuard >= 0 && videoCreate > videoGuard, "video route must guard before generation/charging");
});
