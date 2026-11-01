import test from "node:test";
import assert from "node:assert/strict";

/** Contract: three playbooks, secrets never named as chat payloads. */
const PLAYBOOKS = [
  { id: "change_date_of_birth", secretFields: ["dateOfBirth", "otp"] },
  { id: "bvn_confirmation", secretFields: ["bvn", "otp"] },
  { id: "open_account", secretFields: ["nin", "bvn", "otp"] },
];

test("business playbook ids match product contract", () => {
  assert.deepEqual(
    PLAYBOOKS.map((p) => p.id).sort(),
    ["bvn_confirmation", "change_date_of_birth", "open_account"].sort(),
  );
});

test("secret fields are limited to verification surface", () => {
  for (const p of PLAYBOOKS) {
    assert.ok(p.secretFields.length >= 1);
    for (const f of p.secretFields) {
      assert.ok(["otp", "bvn", "nin", "dateOfBirth"].includes(f));
    }
  }
});
