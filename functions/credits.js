import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { priceOperation, recordBillingTransaction, updateBillingTransaction, getDynamicBillingConfig, calculateDynamicCharge, providerCostFromUsage } from "./dynamic-billing.js";

const ADMIN_EMAIL = String(process.env.SUPER_ADMIN_EMAIL || "isaacoshiomole0@gmail.com").trim().toLowerCase();

async function isSuperAdmin(uid) {
  const user = await getAuth().getUser(uid);
  const claims = user.customClaims || {};
  return String(user.email || "").trim().toLowerCase() === ADMIN_EMAIL && (claims.admin === true || claims.superAdmin === true || claims.role === "admin" || claims.role === "super_admin");
}

function liveSubscriptionCredits(profile) {
  const expiresAt = profile?.subExpiresAt ? Date.parse(String(profile.subExpiresAt)) : NaN;
  return Number.isFinite(expiresAt) && expiresAt > Date.now()
    ? Math.max(0, Number(profile?.subCredits || 0))
    : 0;
}

function canonicalBalance(profile) {
  return {
    permanent: Math.max(0, Number(profile?.credits || 0)),
    subscription: liveSubscriptionCredits(profile)
  };
}

function totalBalance(balance) {
  return Math.max(0, Number(balance.permanent || 0)) + Math.max(0, Number(balance.subscription || 0));
}

/**
 * Firebase users/{uid} is the shared credit authority for the app and the
 * externally maintained website. creditBalances/{uid} remains a compatibility
 * mirror for older backend consumers.
 */
export async function reserveCredits(uid, amount, capability) {
  const legacyEstimate = Math.max(0, Number(amount) || 0);
  let pricing;
  try {
    pricing = await priceOperation(capability, legacyEstimate);
  } catch (error) {
    // Keep requests billable if the optional dynamic configuration is temporarily
    // unavailable: use the server-provided capability estimate plus the 80% markup.
    const fallbackCharge = Math.ceil(legacyEstimate * 1.8 * 100) / 100;
    pricing = {
      capability: String(capability || "unknown"),
      providerCostUsd: legacyEstimate * 0.07999,
      sellingPriceUsd: fallbackCharge * 0.07999,
      credits: fallbackCharge,
      reservedCredits: fallbackCharge,
      profitMarkup: 0.8,
      feeRateApplied: 0,
      creditValueUsd: 0.07999,
      estimated: true,
      estimateSource: "safe_fallback_legacy_estimate",
      pricingVersion: 1
    };
  }
  const adminExempt = await isSuperAdmin(uid);
  const db = getFirestore();
  if (adminExempt) {
    const adminTransactionId = "admin_" + db.collection("aiBillingTransactions").doc().id;
    await recordBillingTransaction(adminTransactionId, {
      uid, capability, providerCostUsd: pricing.providerCostUsd, costType: "estimated",
      sellingPriceUsd: 0, profitMarkup: pricing.profitMarkup, feeRateApplied: pricing.feeRateApplied,
      creditValueUsd: pricing.creditValueUsd, creditsReserved: 0, creditsCharged: 0,
      pricingVersion: pricing.pricingVersion, exemptType: "admin_exempt",
      status: "reserved", estimateSource: pricing.estimateSource
    });
    return { reservationId: adminTransactionId, amount: 0, adminUnlimited: true, billingEstimate: pricing };
  }
  const finalCharge = Math.max(0, Number(pricing.credits || 0));
  const charge = Math.max(finalCharge, Number(pricing.reservedCredits || finalCharge));
  if (finalCharge === 0) return { reservationId: null, amount: 0, billingEstimate: pricing };
  const userRef = db.collection("users").doc(uid);
  const balanceRef = db.collection("creditBalances").doc(uid);
  const reservationRef = db.collection("creditReservations").doc();

  await db.runTransaction(async tx => {
    const userSnap = await tx.get(userRef);
    if (!userSnap.exists) throw new Error("Account not found.");
    const profile = userSnap.data() || {};
    if (profile.suspended === true) throw new Error("Your account is suspended.");

    const balance = canonicalBalance(profile);
    const total = totalBalance(balance);
    if (total < charge) throw new Error("Insufficient credits");

    // Subscription credits are consumed before permanent credits.
    const fromSubscription = Math.min(balance.subscription, charge);
    const fromPermanent = charge - fromSubscription;
    const nextPermanent = balance.permanent - fromPermanent;
    const nextSubscription = balance.subscription - fromSubscription;
    const nextTotal = nextPermanent + nextSubscription;

    const legacySnap = await tx.get(balanceRef);
    const legacy = legacySnap.exists ? legacySnap.data() || {} : {};

    tx.update(userRef, {
      credits: nextPermanent,
      subCredits: nextSubscription,
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.set(balanceRef, {
      uid,
      available: nextTotal,
      reserved: Number(legacy.reserved || 0) + charge,
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
    tx.set(reservationRef, {
      uid,
      capability,
      amount: charge,
      finalCharge,
      fromSubscription,
      fromPermanent,
      status: "reserved",
      providerId: pricing.providerId || null,
      modelId: pricing.modelId || null,
      providerCostUsd: pricing.providerCostUsd,
      rateSnapshot: pricing.rateSnapshot || null,
      pricingSnapshot: pricing.pricingSnapshot || null,
      sellingPriceUsd: pricing.sellingPriceUsd,
      profitMarkup: pricing.profitMarkup,
      feeRateApplied: pricing.feeRateApplied,
      creditValueUsd: pricing.creditValueUsd,
      pricingVersion: pricing.pricingVersion,
      estimateSource: pricing.estimateSource,
      createdAt: FieldValue.serverTimestamp()
    });
  });

  await recordBillingTransaction(reservationRef.id, {
    uid, capability, providerId: pricing.providerId || null, modelId: pricing.modelId || null,
    providerCostUsd: pricing.providerCostUsd, rateSnapshot: pricing.rateSnapshot || null,
    pricingSnapshot: pricing.pricingSnapshot || null, costType: "estimated",
    sellingPriceUsd: pricing.sellingPriceUsd, profitMarkup: pricing.profitMarkup,
    feeRateApplied: pricing.feeRateApplied, creditValueUsd: pricing.creditValueUsd,
    creditsReserved: charge, creditsCharged: 0, pricingVersion: pricing.pricingVersion,
    exemptType: "credit_billed", status: "reserved", estimateSource: pricing.estimateSource
  });
  return {
    reservationId: reservationRef.id, amount: finalCharge, reservedAmount: charge, billingEstimate: pricing
  };
}

export async function commitCredits(uid, reservationId, providerId, metadata = {}) {
  if (!reservationId) return;
  if (String(reservationId).startsWith("admin_")) {
    await updateBillingTransaction(reservationId, {
      providerId: providerId || null, modelId: metadata.modelId || null, usage: metadata.usage || null,
      status: "committed", creditsCharged: 0,
      sellingPriceUsd: 0, exemptType: "admin_exempt", costType: "estimated",
      committedAt: FieldValue.serverTimestamp()
    });
    return;
  }
  const db = getFirestore();
  const reservationRef = db.collection("creditReservations").doc(reservationId);

  await db.runTransaction(async tx => {
    const snap = await tx.get(reservationRef);
    if (!snap.exists || snap.data().uid !== uid || snap.data().status !== "reserved") return;

    const data = snap.data();
    const userRef = db.collection("users").doc(uid);
    const balanceRef = db.collection("creditBalances").doc(uid);
    const userSnap = await tx.get(userRef);
    const balanceSnap = await tx.get(balanceRef);
    if (!userSnap.exists) throw new Error("Account not found.");

    const profile = userSnap.data() || {};
    const balance = canonicalBalance(profile);
    const legacy = balanceSnap.exists ? balanceSnap.data() || {} : {};
    const reservedAmount = Math.max(0, Number(data.amount || 0));
    const finalCharge = Math.min(reservedAmount, Math.max(0, Number(data.finalCharge ?? reservedAmount)));
    const unused = Math.max(0, reservedAmount - finalCharge);
    // Refund unused safety-margin credits to the same buckets originally debited.
    const refundPermanent = Math.min(unused, Math.max(0, Number(data.fromPermanent || 0)));
    const refundSubscription = Math.max(0, unused - refundPermanent);
    const nextPermanent = balance.permanent + refundPermanent;
    const nextSubscription = balance.subscription + refundSubscription;
    const nextBalance = { permanent: nextPermanent, subscription: nextSubscription };

    tx.update(userRef, {
      credits: nextPermanent,
      subCredits: nextSubscription,
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.update(balanceRef, {
      available: totalBalance(nextBalance),
      reserved: Math.max(0, Number(legacy.reserved || 0) - reservedAmount),
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.update(reservationRef, {
      status: "committed",
      finalCharge,
      providerId: providerId || null,
      committedAt: FieldValue.serverTimestamp()
    });
    tx.create(db.collection("creditLedger").doc(), {
      uid,
      capability: data.capability,
      amount: finalCharge,
      kind: "usage",
      providerId: providerId || null,
      createdAt: FieldValue.serverTimestamp()
    });
  });
  const committed = await reservationRef.get();
  if (committed.exists) {
    const data = committed.data() || {};
    if (data.status === "committed") {
      await updateBillingTransaction(reservationId, {
        providerId: providerId || null,
        modelId: metadata.modelId || data.modelId || null,
        usage: metadata.usage && typeof metadata.usage === "object" ? metadata.usage : null,
        status: "committed",
        creditsCharged: Number(data.finalCharge ?? data.amount ?? 0),
        creditsReserved: Number(data.amount || 0),
        creditsRefunded: Math.max(0, Number(data.amount || 0) - Number(data.finalCharge ?? data.amount ?? 0)),
        committedAt: FieldValue.serverTimestamp()
      });
    }
  }
}

export async function grantCredits(adminUid, targetUid, amount, reason = "admin_grant") {
  if (!(await isSuperAdmin(adminUid))) throw new Error("Admin access required");
  const charge = Number(amount);
  if (!Number.isFinite(charge) || charge <= 0) throw new Error("credit_amount_must_be_positive");

  const db = getFirestore();
  const userRef = db.collection("users").doc(targetUid);
  const balanceRef = db.collection("creditBalances").doc(targetUid);

  await db.runTransaction(async tx => {
    const userSnap = await tx.get(userRef);
    if (!userSnap.exists) throw new Error("Account not found.");
    const profile = userSnap.data() || {};
    const balance = canonicalBalance(profile);
    const legacySnap = await tx.get(balanceRef);
    const legacy = legacySnap.exists ? legacySnap.data() || {} : {};
    const nextPermanent = balance.permanent + charge;

    tx.update(userRef, {
      credits: nextPermanent,
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.set(balanceRef, {
      uid: targetUid,
      available: nextPermanent + balance.subscription,
      reserved: Number(legacy.reserved || 0),
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
    tx.create(db.collection("creditLedger").doc(), {
      uid: targetUid,
      capability: "admin_grant",
      amount: charge,
      kind: "admin_grant",
      reason: String(reason || "admin_grant").slice(0, 200),
      actorUid: adminUid,
      createdAt: FieldValue.serverTimestamp()
    });
  });

  return { ok: true, uid: targetUid, amount: charge };
}

export async function reverseCredits(uid, reservationId) {
  if (!reservationId) return;
  if (String(reservationId).startsWith("admin_")) {
    await updateBillingTransaction(reservationId, {
      status: "failed", creditsCharged: 0, creditsRefunded: 0,
      errorClass: "provider_or_operation_failed", updatedAt: FieldValue.serverTimestamp()
    });
    return;
  }
  const db = getFirestore();
  const reservationRef = db.collection("creditReservations").doc(reservationId);

  await db.runTransaction(async tx => {
    const snap = await tx.get(reservationRef);
    if (!snap.exists || snap.data().uid !== uid || snap.data().status !== "reserved") return;

    const data = snap.data();
    const userRef = db.collection("users").doc(uid);
    const balanceRef = db.collection("creditBalances").doc(uid);
    const [userSnap, balanceSnap] = await Promise.all([tx.get(userRef), tx.get(balanceRef)]);
    if (!userSnap.exists) throw new Error("Account not found.");

    const profile = userSnap.data() || {};
    const balance = canonicalBalance(profile);
    const legacy = balanceSnap.exists ? balanceSnap.data() || {} : {};

    const restoredPermanent = balance.permanent + Number(data.fromPermanent || 0);
    const restoredSubscription = balance.subscription + Number(data.fromSubscription || 0);

    tx.update(userRef, {
      credits: restoredPermanent,
      subCredits: restoredSubscription,
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.set(balanceRef, {
      uid,
      available: restoredPermanent + restoredSubscription,
      reserved: Math.max(0, Number(legacy.reserved || 0) - Number(data.amount || 0)),
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
    tx.update(reservationRef, {
      status: "reversed",
      reversedAt: FieldValue.serverTimestamp()
    });
  });
  const reversed = await reservationRef.get();
  if (reversed.exists && reversed.data()?.status === "reversed") {
    await updateBillingTransaction(reservationId, {
      status: "reversed", creditsCharged: 0,
      creditsRefunded: Number(reversed.data()?.amount || 0),
      reversedAt: FieldValue.serverTimestamp()
    });
  }
}
