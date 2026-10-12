import test from "node:test";
import assert from "node:assert/strict";
import { assertPublicCapabilityAllowed } from "../src/capability-policy.js";

test("ordinary AI capabilities remain available to public workspaces", () => {
  assert.doesNotThrow(() => assertPublicCapabilityAllowed("chat"));
  assert.doesNotThrow(() => assertPublicCapabilityAllowed("truth_guard"));
  assert.doesNotThrow(() => assertPublicCapabilityAllowed("data_analysis_workspace"));
});

test("administrative and internal capabilities are rejected regardless of case or separators", () => {
  for (const name of [
    "model_routing",
    "MODEL_ROUTING",
    "model-routing",
    "Model Routing",
    "credit_reservation",
    "provider_cost_optimizer",
    "offline_retry_queue",
    "ai_evaluation_laboratory"
  ]) {
    assert.throws(() => assertPublicCapabilityAllowed(name), error => error.httpStatus === 403);
  }
});
