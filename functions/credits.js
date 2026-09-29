import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";

const ADMIN_EMAIL = "isaacoshiomole0@gmail.com";

async function isSuperAdmin(uid) {
  const user = await getAuth().getUser(uid);
  const claims = user.customClaims || {};
  return user.email === ADMIN_EMAIL && (claims.admin === true || claims.superAdmin === true || claims.role === "admin" || claims.role === "super_admin");
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
  const charge = Math.max(0, Number(amount));
  if (await isSuperAdmin(uid)) return { reservationId: null, amount: 0, adminUnlimited: true };
  if (charge === 0) return { reservationId: null, amount: 0 };

  const db = getFirestore();
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
      fromSubscription,
      fromPermanent,
      status: "reserved",
      createdAt: FieldValue.serverTimestamp()
    });
  });

  return { reservationId: reservationRef.id, amount: charge };
}

export async function commitCredits(uid, reservationId, providerId) {
  if (!reservationId) return;
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

    tx.update(balanceRef, {
      available: totalBalance(balance),
      reserved: Math.max(0, Number(legacy.reserved || 0) - Number(data.amount || 0)),
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.update(reservationRef, {
      status: "committed",
      providerId: providerId || null,
      committedAt: FieldValue.serverTimestamp()
    });
    tx.create(db.collection("creditLedger").doc(), {
      uid,
      capability: data.capability,
      amount: Number(data.amount),
      kind: "usage",
      providerId: providerId || null,
      createdAt: FieldValue.serverTimestamp()
    });
  });
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
}
