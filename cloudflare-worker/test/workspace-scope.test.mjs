import test from "node:test";
import assert from "node:assert/strict";
import { classifyWorkspaceScope, workspaceScopeSystemPrompt } from "../src/legacy-native.js";

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
