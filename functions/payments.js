import crypto from "node:crypto";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getProviderSecret } from "./secret-store.js";

async function paystack(path, options = {}) {
  const secret = await getProviderSecret("paystack");
  if (!secret) throw new Error("Paystack is not configured");
  const response = await fetch("https://api.paystack.co" + path, {
    ...options,
    headers: {
      Authorization: "Bearer " + secret,
      "Content-Type": "application/json",
      ...(options.headers || {})
    }
  });
  const body = await response.json();
  if (!response.ok || body.status !== true) throw new Error(body.message || "Paystack request failed");
  return body;
}

export async function paymentPreview({ uid, planId }) {
  const plan = await getFirestore().collection("plans").doc(planId).get();
  if (!plan.exists || plan.data()?.active === false) throw new Error("Plan unavailable");
  const p = plan.data();
  return {
    planId,
    name: p.name,
    amountMinor: Number(p.amountMinor),
    currency: p.currency || "NGN",
    billingPeriod: p.billingPeriod || "monthly",
    features: p.features || [],
    limits: p.limits || {},
    accountId: uid
  };
}

export async function initializePayment({ uid, email, planId, callbackUrl }) {
  const preview = await paymentPreview({ uid, planId });
  if (!email) throw new Error("Authenticated account email required");
  const reference = "bq_" + uid.slice(0, 12) + "_" + crypto.randomUUID().replaceAll("-", "");
  const db = getFirestore();
  await db.collection("paymentTransactions").doc(reference).set({
    uid, planId, amountMinor: preview.amountMinor, currency: preview.currency,
    status: "initialized", createdAt: FieldValue.serverTimestamp()
  });

  const body = await paystack("/transaction/initialize", {
    method: "POST",
    body: JSON.stringify({
      email,
      amount: String(preview.amountMinor),
      currency: preview.currency,
      reference,
      callback_url: callbackUrl,
      metadata: JSON.stringify({ uid, planId, reference })
    })
  });

  await db.collection("paymentTransactions").doc(reference).update({
    authorizationUrl: body.data.authorization_url,
    accessCode: body.data.access_code,
    initializedAt: FieldValue.serverTimestamp()
  });

  return { reference, authorizationUrl: body.data.authorization_url, accessCode: body.data.access_code, preview };
}

export async function verifyPayment({ uid, reference }) {
  const db = getFirestore();
  const txRef = db.collection("paymentTransactions").doc(reference);
  const tx = await txRef.get();
  if (!tx.exists || tx.data()?.uid !== uid) throw new Error("Payment reference not found");
  const transaction = await paystack("/transaction/verify/" + encodeURIComponent(reference));
  const data = transaction.data;
  const expectedAmount = Number(tx.data().amountMinor);
  const verifiedAmount = Number(data.amount);

  if (data.status !== "success" || verifiedAmount !== expectedAmount || data.currency !== tx.data().currency) {
    await txRef.update({ status: data.status || "failed", verifiedAt: FieldValue.serverTimestamp() });
    return { verified: false, status: data.status || "failed" };
  }

  if (tx.data().fulfilled === true) return { verified: true, status: "success", planId: tx.data().planId, alreadyFulfilled: true };

  await db.collection("entitlements").doc(uid).set({
    planId: tx.data().planId,
    active: true,
    status: "active",
    autoRenew: false,
    startsAt: FieldValue.serverTimestamp(),
    sourceReference: reference,
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });

  await txRef.update({
    status: "success", fulfilled: true, verifiedAt: FieldValue.serverTimestamp(),
    paystackTransactionId: String(data.id)
  });

  return { verified: true, status: "success", planId: tx.data().planId };
}

export async function setAutoRenew({ uid, enabled }) {
  await getFirestore().collection("entitlements").doc(uid).set({
    autoRenew: Boolean(enabled),
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });
  return { autoRenew: Boolean(enabled) };
}

export async function handlePaystackWebhook(rawBody, signature) {
  const secret = await getProviderSecret("paystack");
  if (!secret) throw new Error("Paystack is not configured");
  const expected = crypto.createHmac("sha512", secret).update(rawBody).digest("hex");
  if (!crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(String(signature || "")))) {
    throw new Error("Invalid Paystack webhook signature");
  }
  const event = JSON.parse(rawBody.toString("utf8"));
  if (event.event === "charge.success" && event.data?.reference) {
    const ref = event.data.reference;
    const db = getFirestore();
    const tx = await db.collection("paymentTransactions").doc(ref).get();
    if (tx.exists && tx.data()?.fulfilled !== true && event.data.status === "success") {
      const amountOk = Number(event.data.amount) === Number(tx.data().amountMinor);
      const currencyOk = event.data.currency === tx.data().currency;
      if (amountOk && currencyOk) {
        await db.collection("entitlements").doc(tx.data().uid).set({
          planId: tx.data().planId, active: true, status: "active",
          autoRenew: false, sourceReference: ref, updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        await db.collection("paymentTransactions").doc(ref).update({
          status: "success", fulfilled: true, webhookFulfilled: true, webhookAt: FieldValue.serverTimestamp()
        });
      }
    }
  }
  return { received: true };
}
