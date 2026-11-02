import { firestoreGet, firestoreSet, firestoreCommit, firestoreQuery } from "./firebase.js";

const CONFIG = "adminAdvancedOptions/monthlyFeedback";
const clean = (value, max) => String(value ?? "").trim().slice(0, max);
const periodKey = () => new Date().toISOString().slice(0, 7);
const fail = (message, status = 400) => Object.assign(new Error(message), { httpStatus: status });

function requireAdmin(user, env, isSuperAdmin) {
  if (!isSuperAdmin(user, env)) throw fail("admin_access_required", 403);
}

async function verifiedReceiver(uid) {
  const receiver = uid ? await firestoreGet("users/" + uid) : null;
  if (!receiver || receiver.isAdmin !== true) throw fail("feedback_receiver_must_be_admin", 400);
  return receiver;
}

export async function getNativeMonthlyFeedbackState(user) {
  if (!user?.uid || user.isAnonymous === true) return { available: false, submitted: false };
  const config = await firestoreGet(CONFIG) || {};
  const receiverUid = clean(config.receiverUid, 160);
  const receiver = receiverUid ? await firestoreGet("users/" + receiverUid) : null;
  const period = periodKey();
  const existing = await firestoreGet("monthlyFeedback/" + period + "_" + user.uid);
  return {
    available: config.enabled === true && receiver?.isAdmin === true,
    submitted: Boolean(existing),
    period,
    title: clean(config.campaignTitle || "Monthly feedback", 100),
    message: clean(config.campaignMessage || "Tell us how BQ Sqwad AI worked for you this month.", 500)
  };
}

export async function submitNativeMonthlyFeedback(user, body) {
  const rating = Number(body?.rating);
  const category = clean(body?.category, 30);
  const comment = clean(body?.comment, 1200);
  if (!Number.isInteger(rating) || rating < 1 || rating > 5) throw fail("rating_must_be_1_to_5");
  if (!["chat", "teams", "business", "organization", "media", "other"].includes(category)) throw fail("feedback_category_invalid");
  const config = await firestoreGet(CONFIG) || {};
  const receiverUid = clean(config.receiverUid, 160);
  if (config.enabled !== true || !receiverUid) throw fail("monthly_feedback_closed", 403);
  await verifiedReceiver(receiverUid);
  const period = periodKey();
  const id = period + "_" + user.uid;
  const path = "monthlyFeedback/" + id;
  const now = new Date().toISOString();
  let committed = await firestoreGet(path);
  if (!committed) {
    const record = {
      id, period, uid: user.uid, email: clean(user.email, 200), receiverUid,
      rating, category, comment, status: "new", source: "android",
      createdAt: now, updatedAt: now
    };
    try {
      await firestoreCommit([{ path, data: record, create: true }]);
      committed = record;
    } catch (error) {
      committed = await firestoreGet(path);
      if (!committed || committed.uid !== user.uid) throw error;
    }
  }
  if (committed.uid !== user.uid) throw fail("feedback_record_ownership_conflict", 409);
  const assignedUid = clean(committed.receiverUid || receiverUid, 160);
  await verifiedReceiver(assignedUid);
  const notificationId = "monthly_feedback_" + id;
  await firestoreSet("notifications/" + assignedUid + "/items/" + notificationId, {
    id: notificationId,
    type: "monthly_feedback",
    title: "New monthly product feedback",
    body: "A user submitted a " + committed.rating + "/5 rating for " + committed.category + ".",
    read: false,
    createdAt: committed.createdAt || now,
    deepLink: "/admin"
  }, false).catch(async error => {
    // A deterministic notification may already exist after a successful first delivery.
    if (!/ALREADY_EXISTS|already exists|409/i.test(String(error?.message || error))) throw error;
  });
  return { ok: true, period, alreadySubmitted: committed.createdAt !== now };
}

export async function getNativeMonthlyFeedbackAdmin(user, env, isSuperAdmin) {
  requireAdmin(user, env, isSuperAdmin);
  const config = await firestoreGet(CONFIG) || {};
  const submissions = await firestoreQuery("monthlyFeedback", {
    filters: [{ field: { fieldPath: "receiverUid" }, op: "EQUAL", value: { stringValue: user.uid } }],
    limit: 100
  });
  submissions.sort((a, b) => String(b.createdAt || "").localeCompare(String(a.createdAt || "")));
  return {
    enabled: config.enabled === true,
    receiverUid: clean(config.receiverUid, 160),
    campaignTitle: clean(config.campaignTitle || "Monthly feedback", 100),
    campaignMessage: clean(config.campaignMessage || "Tell us how BQ Sqwad AI worked for you this month.", 500),
    submissions: submissions.map(item => ({
      id: clean(item.id, 180), period: clean(item.period, 7), email: clean(item.email, 200),
      rating: Number(item.rating || 0), category: clean(item.category || "other", 30),
      comment: clean(item.comment, 1200), status: clean(item.status || "new", 30),
      createdAt: clean(item.createdAt, 40), source: clean(item.source || "website", 20)
    }))
  };
}

export async function saveNativeMonthlyFeedbackAdmin(user, body, env, isSuperAdmin) {
  requireAdmin(user, env, isSuperAdmin);
  const enabled = body?.enabled === true;
  const receiverUid = clean(body?.receiverUid, 160);
  const campaignTitle = clean(body?.campaignTitle, 100);
  const campaignMessage = clean(body?.campaignMessage, 500);
  if (campaignTitle.length < 2 || campaignMessage.length < 2) throw fail("feedback_campaign_text_required");
  if (enabled && !receiverUid) throw fail("feedback_receiver_required");
  if (receiverUid) await verifiedReceiver(receiverUid);
  await firestoreSet(CONFIG, {
    enabled, receiverUid, campaignTitle, campaignMessage,
    updatedBy: user.uid, updatedAt: new Date().toISOString()
  }, true);
  return { ok: true };
}
