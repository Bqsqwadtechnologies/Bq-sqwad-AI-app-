import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, webcrypto } from "node:crypto";
import { verifyPlayPurchase } from "../src/google-play.js";

if (!globalThis.crypto) globalThis.crypto = webcrypto;

test("Google Play subscription renewals grant credits once per verified billing cycle", async () => {
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const privateKeyPem = privateKey.export({ type: "pkcs8", format: "pem" }).toString();
  const env = {
    GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: JSON.stringify({
      client_email: "play-test@example.invalid",
      private_key: privateKeyPem
    })
  };
  let orderId = "order-cycle-1";
  let expiryTime = "2030-01-01T00:00:00Z";
  const originalFetch = globalThis.fetch;
  let failNextReceiptCommit = true;
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url === "https://oauth2.googleapis.com/token") {
      return new Response(JSON.stringify({ access_token: "test-access-token", expires_in: 3600 }), { status: 200 });
    }
    if (url.includes("/purchases/subscriptionsv2/tokens/")) {
      return new Response(JSON.stringify({
        subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
        latestOrderId: orderId,
        startTime: "2029-01-01T00:00:00Z",
        lineItems: [{
          latestSuccessfulOrderId: orderId,
          expiryTime,
          offerDetails: { basePlanId: "monthly", offerId: "trial-3d" },
          autoRenewingPlan: { autoRenewEnabled: true }
        }]
      }), { status: 200 });
    }
    if (url.includes(":acknowledge")) return new Response("{}", { status: 200 });
    throw new Error("Unexpected Google Play request: " + url);
  };

  const documents = new Map([
    ["users/test-user", {
      uid: "test-user", email: "test@example.invalid", credits: 0,
      subCredits: 0, subExpiresAt: null
    }]
  ]);
  const firestore = {
    async get(path) {
      const value = documents.get(path);
      return value == null ? null : structuredClone(value);
    },
    async commit(writes) {
      // Simulate a transient receipt-write failure after the grant transaction.
      if (failNextReceiptCommit && writes.some(write => write.path.startsWith("billingReceipts/"))) {
        failNextReceiptCommit = false;
        throw new Error("Simulated receipt persistence outage");
      }
      // Match Firestore's atomic create preconditions so duplicate event creation
      // cannot silently grant the same billing cycle twice.
      for (const write of writes) {
        if (write.create && documents.has(write.path)) throw new Error("Document already exists: " + write.path);
      }
      for (const write of writes) documents.set(write.path, structuredClone(write.data));
      return { ok: true };
    }
  };

  try {
    const input = { uid: "test-user", productId: "credit100", purchaseToken: "subscription-token" };
    await assert.rejects(verifyPlayPurchase(env, firestore, input), /Simulated receipt persistence outage/);
    assert.equal((await firestore.get("users/test-user")).subCredits, 100);

    // A retry must finish receipt/acknowledgement side effects without granting
    // the same cycle again after the credit transaction already committed.
    const recovered = await verifyPlayPurchase(env, firestore, input);
    assert.equal(recovered.ok, true);
    assert.equal(recovered.alreadyProcessed, true);
    assert.equal((await firestore.get("users/test-user")).subCredits, 100);
    assert.equal([...documents.keys()].filter(key => key.startsWith("creditLedger/")).length, 1);

    const repeat = await verifyPlayPurchase(env, firestore, input);
    assert.equal(repeat.ok, true);
    assert.equal(repeat.alreadyProcessed, true);
    assert.equal((await firestore.get("users/test-user")).subCredits, 100);

    // Google Play reuses the subscription token, but a renewal has a new verified
    // successful order ID and expiry. It must grant the next cycle exactly once.
    orderId = "order-cycle-2";
    expiryTime = "2030-02-01T00:00:00Z";
    const renewal = await verifyPlayPurchase(env, firestore, input);
    assert.equal(renewal.ok, true);
    assert.equal(renewal.alreadyProcessed, false);
    assert.equal((await firestore.get("users/test-user")).subCredits, 200);

    const repeatedRenewal = await verifyPlayPurchase(env, firestore, input);
    assert.equal(repeatedRenewal.ok, true);
    assert.equal(repeatedRenewal.alreadyProcessed, true);
    assert.equal((await firestore.get("users/test-user")).subCredits, 200);
    assert.equal([...documents.keys()].filter(key => key.startsWith("creditLedger/")).length, 2);
    assert.equal([...documents.keys()].filter(key => key.startsWith("playPurchaseEvents/")).length, 2);
    assert.equal([...documents.keys()].filter(key => key.startsWith("billingReceipts/")).length, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
