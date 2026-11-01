/**
 * Notify users when the daily free credit is granted.
 * Call after dailyFreeCreditGrant succeeds.
 */
import { broadcast } from "./notifications-native.js";

export async function notifyDailyFreeCredit(env, {
  projectId,
  uid,
  tokens,
  credits,
  dbWrite,
}) {
  const title = "Free credit ready";
  const body = `Your daily free credit (${Number(credits) || 1}) is available in BQ Sqwad AI.`;
  const id = `free_credit_${new Date().toISOString().slice(0, 10)}`;
  if (typeof dbWrite === "function") {
    await dbWrite(`notifications/${uid}/items/${id}`, {
      id,
      type: "free_credit",
      title,
      body,
      read: false,
      createdAt: new Date().toISOString(),
      deepLink: "bqsqwad://credits",
    });
  }
  const list = Array.isArray(tokens) ? tokens.filter(Boolean) : [];
  if (list.length && projectId) {
    await broadcast(env, projectId, list, title, body, {
      type: "free_credit",
      deepLink: "bqsqwad://credits",
    }).catch(() => ({ sent: 0 }));
  }
  return { notified: true, id };
}
