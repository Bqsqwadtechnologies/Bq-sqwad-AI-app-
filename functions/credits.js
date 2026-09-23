import { getFirestore, FieldValue } from "firebase-admin/firestore";

export async function reserveCredits(uid, amount, capability) {
  const charge = Math.max(0, Number(amount));
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
