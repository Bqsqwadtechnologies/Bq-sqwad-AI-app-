import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";

const ADMIN_EMAIL = "isaacoshiomole0@gmail.com";

async function isSuperAdmin(uid) {
  const user = await getAuth().getUser(uid);
  const claims = user.customClaims || {};
  return user.email === ADMIN_EMAIL && (claims.admin === true || claims.superAdmin === true || claims.role === "admin" || claims.role === "super_admin");
}

export async function reserveCredits(uid, amount, capability) {
  const charge = Math.max(0, Number(amount));
  if (await isSuperAdmin(uid)) return { reservationId: null, amount: 0, adminUnlimited: true };
  if (charge === 0) return { reservationId: null, amount: 0 };
  const db = getFirestore();
  const balanceRef = db.collection("creditBalances").doc(uid);
  const reservationRef = db.collection("creditReservations").doc();
  await db.runTransaction(async tx => {
    const snap = await tx.get(balanceRef);
    const data = snap.exists ? snap.data() : { available: 0, reserved: 0 };
    const available = Number(data.available || 0);
    if (available < charge) throw new Error("Insufficient credits");
    tx.update(balanceRef, {
      available: available - charge,
      reserved: Number(data.reserved || 0) + charge,
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.set(reservationRef, {
      uid, capability, amount: charge, status: "reserved",
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
    const balanceRef = db.collection("creditBalances").doc(uid);
    const balance = await tx.get(balanceRef);
    const current = balance.exists ? balance.data() : { reserved: 0 };
    tx.update(balanceRef, {
      reserved: Math.max(0, Number(current.reserved || 0) - Number(data.amount)),
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.update(reservationRef, {
      status: "committed", providerId: providerId || null,
      committedAt: FieldValue.serverTimestamp()
    });
    tx.create(db.collection("creditLedger").doc(), {
      uid, capability: data.capability, amount: Number(data.amount),
      kind: "usage", providerId: providerId || null,
      createdAt: FieldValue.serverTimestamp()
    });
  });
}

export async function grantCredits(adminUid, targetUid, amount, reason = "admin_grant") {
  if (!(await isSuperAdmin(adminUid))) throw new Error("Admin access required");
  const charge = Number(amount);
  if (!Number.isFinite(charge) || charge <= 0) throw new Error("credit_amount_must_be_positive");
  const db = getFirestore();
  const balanceRef = db.collection("creditBalances").doc(targetUid);
  await db.runTransaction(async tx => {
    const snap = await tx.get(balanceRef);
    const current = snap.exists ? (snap.data() || {}) : {};
    tx.set(balanceRef, { uid: targetUid, available: Number(current.available || 0) + charge, reserved: Number(current.reserved || 0), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    tx.create(db.collection("creditLedger").doc(), { uid: targetUid, capability: "admin_grant", amount: charge, kind: "admin_grant", reason: String(reason || "admin_grant").slice(0, 200), actorUid: adminUid, createdAt: FieldValue.serverTimestamp() });
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
    const balanceRef = db.collection("creditBalances").doc(uid);
    const balance = await tx.get(balanceRef);
    const current = balance.exists ? balance.data() : { available: 0, reserved: 0 };
    tx.update(balanceRef, {
      available: Number(current.available || 0) + Number(data.amount),
      reserved: Math.max(0, Number(current.reserved || 0) - Number(data.amount)),
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.update(reservationRef, {
      status: "reversed", reversedAt: FieldValue.serverTimestamp()
    });
  });
}
