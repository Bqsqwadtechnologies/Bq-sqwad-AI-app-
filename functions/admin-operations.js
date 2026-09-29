import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { getAuth } from "firebase-admin/auth";

export const ADMIN_STAFF_ROLES = {
  moderator: ["admin_console", "moderate"],
  editor: ["admin_console", "content"],
  customer_agent: ["admin_console", "customer_support"],
  designer: ["admin_console", "design"],
  analyst: ["admin_console", "analytics"],
  operations: ["admin_console", "operations"],
};

function clean(value, max = 500) {
  return String(value ?? "").trim().slice(0, max);
}

export async function listAdminStaff() {
  const snap = await getFirestore().collection("adminStaff").orderBy("createdAt", "desc").limit(200).get();
  return snap.docs.map(d => ({ id: d.id, ...d.data() }));
}

export async function setAdminStaff(adminUid, input = {}) {
  const uid = clean(input.uid, 160);
  const role = clean(input.role, 40).toLowerCase();
  if (!uid || !ADMIN_STAFF_ROLES[role]) throw new Error("admin_staff_uid_and_supported_role_required");
  const target = await getAuth().getUser(uid);
  const permissions = Array.isArray(input.permissions)
    ? input.permissions.map(x => clean(x, 60)).filter(Boolean).slice(0, 40)
    : ADMIN_STAFF_ROLES[role];
  const ref = getFirestore().collection("adminStaff").doc(uid);
  await ref.set({
    uid,
    email: target.email || null,
    displayName: target.displayName || null,
    role,
    permissions,
    active: input.active !== false,
    updatedBy: adminUid,
    updatedAt: new Date(),
    createdAt: (await ref.get()).exists ? undefined : new Date()
  }, { merge: true });

  const claims = target.customClaims || {};
  await getAuth().setCustomUserClaims(uid, {
    ...claims,
    adminStaff: input.active !== false,
    adminStaffRole: input.active !== false ? role : null
  });
  return { uid, email: target.email || null, role, permissions, active: input.active !== false };
}

export async function removeAdminStaff(adminUid, uid) {
  const cleanUid = clean(uid, 160);
  if (!cleanUid) throw new Error("admin_staff_uid_required");
  await getFirestore().collection("adminStaff").doc(cleanUid).set({
    active: false,
    updatedBy: adminUid,
    updatedAt: new Date()
  }, { merge: true });
  const target = await getAuth().getUser(cleanUid);
  const claims = { ...(target.customClaims || {}) };
  delete claims.adminStaff;
  delete claims.adminStaffRole;
  await getAuth().setCustomUserClaims(cleanUid, claims);
  return { uid: cleanUid, active: false };
}

export async function broadcastAdminNotification(adminUid, input = {}) {
  const title = clean(input.title, 120);
  const body = clean(input.body, 1200);
  if (!title || !body) throw new Error("notification_title_and_body_required");
  const data = {};
  if (input.data && typeof input.data === "object") {
    for (const [key, value] of Object.entries(input.data).slice(0, 20)) {
      data[clean(key, 40)] = clean(value, 500);
    }
  }
  const snap = await getFirestore().collection("notificationTokens").where("enabled", "==", true).limit(10000).get();
  const tokens = snap.docs.map(d => String(d.data()?.token || "")).filter(Boolean);
  let sent = 0, failed = 0;
  for (let i = 0; i < tokens.length; i += 500) {
    const batch = tokens.slice(i, i + 500);
    const result = await getMessaging().sendEachForMulticast({
      tokens: batch,
      notification: { title, body },
      data: { ...data, source: "admin_broadcast" }
    });
    sent += result.successCount;
    failed += result.failureCount;
  }
  const ref = getFirestore().collection("adminBroadcasts").doc();
  await ref.set({
    id: ref.id,
    title,
    body,
    data,
    sent,
    failed,
    tokenCount: tokens.length,
    createdBy: adminUid,
    createdAt: new Date()
  });
  return { ok: true, broadcastId: ref.id, sent, failed, tokenCount: tokens.length };
}
