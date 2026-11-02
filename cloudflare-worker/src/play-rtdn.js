/** Play RTDN + root entitlement aggregation (server-only). */
import { verifyPlayPurchase } from "./google-play.js";

export async function reaggregateRootEntitlement(firestore, uid) {
  const plans = await firestore.query("entitlements/" + uid + "/plans", { limit: 50 }).catch(() => []);
  const rank = { active: 3, trialing: 3, grace: 2, expired: 0, cancelled: 0, canceled: 0, revoked: 0 };
  let best = null;
  for (const plan of plans || []) {
    const state = String(plan.state || plan.status || "").toLowerCase();
    const score = rank[state] ?? 0;
    if (!best || score > (rank[String(best.state || best.status || "").toLowerCase()] ?? 0)) best = plan;
  }
  if (!best || (rank[String(best.state || best.status || "").toLowerCase()] ?? 0) < 2) {
    await firestore.set("entitlements/" + uid, {
      uid,
      active: false,
      source: "google_play_aggregate",
      updatedAt: new Date().toISOString()
    }, true);
    return { active: false };
  }
  const data = {
    uid,
    active: true,
    planId: String(best.planId || best.id || ""),
    module: String(best.module || ""),
    state: String(best.state || best.status || "active"),
    expiresAt: best.expiresAt || best.endDate || null,
    source: "google_play_aggregate",
    includedFeatures: Array.isArray(best.includedFeatures) ? best.includedFeatures : [],
    updatedAt: new Date().toISOString()
  };
  await firestore.set("entitlements/" + uid, data, true);
  return data;
}

export async function handlePlayRtdn(env, firestore, body = {}) {
  let notification = body;
  if (body.message && body.message.data) {
    try {
      const decoded = JSON.parse(atob(String(body.message.data).replace(/-/g, "+").replace(/_/g, "/")));
      notification = decoded;
    } catch {
      return { ok: false, error: "invalid_pubsub_payload" };
    }
  }
  const sub = notification.subscriptionNotification || notification.oneTimeProductNotification || null;
  if (!sub) {
    if (notification.testNotification) return { ok: true, test: true };
    return { ok: false, error: "unsupported_notification" };
  }
  const purchaseToken = String(sub.purchaseToken || "");
  const notificationType = Number(sub.notificationType || 0);
  const revokeTypes = new Set([3, 5, 10, 12, 13]);
  const purchases = await firestore.query("playPurchases", {
    filters: [{ field: { fieldPath: "purchaseToken" }, op: "EQUAL", value: { stringValue: purchaseToken } }],
    limit: 5
  }).catch(() => []);
  const purchase = purchases[0];
  if (!purchase) return { ok: true, matched: false, notificationType };
  const uid = String(purchase.uid || "");
  const planId = String(purchase.planId || purchase.productId || "");
  if (!uid || !planId) return { ok: true, matched: false, notificationType };

  if (revokeTypes.has(notificationType)) {
    await firestore.set("entitlements/" + uid + "/plans/" + planId, {
      planId,
      state: notificationType === 5 ? "grace" : notificationType === 3 ? "cancelled" : "expired",
      active: false,
      source: "google_play",
      rtdnType: notificationType,
      updatedAt: new Date().toISOString()
    }, true);
    await reaggregateRootEntitlement(firestore, uid);
    return { ok: true, matched: true, action: "revoke_or_hold", notificationType, uid, planId };
  }

  try {
    if (purchaseToken && planId) {
      await verifyPlayPurchase(env, firestore, {
        uid,
        productId: String(purchase.productId || planId),
        purchaseToken,
        obfuscatedAccountId: String(purchase.obfuscatedAccountId || ""),
        requestedBasePlanId: String(purchase.basePlanId || ""),
        requestedOfferId: String(purchase.offerId || "")
      });
    }
  } catch (err) {
    await reaggregateRootEntitlement(firestore, uid);
    return { ok: true, matched: true, action: "refresh_failed", error: String(err?.message || err), notificationType };
  }
  await reaggregateRootEntitlement(firestore, uid);
  return { ok: true, matched: true, action: "refresh", notificationType, uid, planId };
}
