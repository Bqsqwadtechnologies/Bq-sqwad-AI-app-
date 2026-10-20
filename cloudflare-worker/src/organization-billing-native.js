import { firestoreGet, firestoreSet, firestoreCommit } from "./firebase.js";

const DEFAULT = {
  currency: "USD",
  assistantPriceCents: 100,
  liveRateCentsPerMinute: { host_only: 25, moderated_audience: 55, full_audience: 95 },
  lowBalanceWarningCents: 500,
  liveBillingIntervalSeconds: 10,
  liveGraceSeconds: 180
};
const TOP = [
  { productId: "orgdeposit10", amountCents: 1000, amountUsd: 10 },
  { productId: "orgdeposit25", amountCents: 2500, amountUsd: 25 },
  { productId: "orgdeposit50", amountCents: 5000, amountUsd: 50 },
  { productId: "orgdeposit100", amountCents: 10000, amountUsd: 100 },
  { productId: "orgdeposit500", amountCents: 50000, amountUsd: 500 }
];
const clean = (v, n = 240) => String(v ?? "").trim().slice(0, n);
const cents = (v) => Math.max(0, Math.floor(Number(v) || 0));
const stripMetadata = (doc) => {
  if (!doc) return {};
  const { __name, __updateTime, ...data } = doc;
  return data;
};
async function stableId(prefix, value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(value)));
  const hex = Array.from(new Uint8Array(digest), (v) => v.toString(16).padStart(2, "0")).join("");
  return prefix + "_" + hex.slice(0, 40);
}
function updatePrecondition(path, doc) {
  return doc?.__updateTime
    ? { path, updateTime: doc.__updateTime }
    : { path, exists: Boolean(doc) };
}
async function cfg() {
  const x = await firestoreGet("adminAdvancedOptions/organization_billing");
  const d = x?.config || {};
  return {
    currency: "USD",
    assistantPriceCents: cents(d.assistantPriceCents ?? DEFAULT.assistantPriceCents),
    liveRateCentsPerMinute: { ...DEFAULT.liveRateCentsPerMinute, ...(d.liveRateCentsPerMinute || {}) },
    lowBalanceWarningCents: cents(d.lowBalanceWarningCents ?? DEFAULT.lowBalanceWarningCents),
    liveBillingIntervalSeconds: Math.min(60, Math.max(5, Number(d.liveBillingIntervalSeconds ?? DEFAULT.liveBillingIntervalSeconds))),
    liveGraceSeconds: Math.min(300, Math.max(60, Number(d.liveGraceSeconds ?? DEFAULT.liveGraceSeconds)))
  };
}
export async function orgWallet(uid) {
  const x = await firestoreGet("organizationWallets/" + uid);
  const c = await cfg();
  const b = cents(x?.balanceCents);
  return {
    uid, currency: "USD", balanceCents: b, balanceUsd: b / 100,
    lowBalanceWarningCents: c.lowBalanceWarningCents,
    lowBalanceWarningUsd: c.lowBalanceWarningCents / 100,
    assistantPriceCents: c.assistantPriceCents,
    assistantPriceUsd: c.assistantPriceCents / 100,
    liveRateCentsPerMinute: c.liveRateCentsPerMinute,
    liveBillingIntervalSeconds: c.liveBillingIntervalSeconds,
    liveGraceSeconds: c.liveGraceSeconds,
    status: x?.status || "active", updatedAt: x?.updatedAt || null
  };
}
export function orgTopUps() {
  return TOP.map((x) => ({ ...x, currency: "USD", displayAmount: x.amountUsd.toFixed(2) }));
}
export async function orgLiveStart(uid, b) {
  const c = await cfg();
  const w = await orgWallet(uid);
  const sid = clean(b.sessionId, 160);
  if (!sid) throw new Error("organization_live_session_required");
  if (w.status === "blocked" || w.balanceCents <= 0) throw new Error("organization_insufficient_balance");
  const mode = ["host_only", "moderated_audience", "full_audience"].includes(String(b.mode)) ? String(b.mode) : "host_only";
  const old = await firestoreGet("organizationLiveSessions/" + sid);
  if (old && old.uid !== uid) throw new Error("organization_live_session_owned_by_another_account");
  await firestoreSet("organizationLiveSessions/" + sid, {
    uid, sessionId: sid, mode, broadcastId: clean(b.broadcastId, 160) || null,
    accountId: clean(b.accountId, 160) || null, status: "active",
    rateCentsPerMinute: cents(c.liveRateCentsPerMinute[mode]),
    billingIntervalSeconds: c.liveBillingIntervalSeconds,
    billedCents: cents(old?.billedCents), lastHeartbeatAt: new Date(),
    graceUntil: null, lowBalance: w.balanceCents <= c.lowBalanceWarningCents,
    createdAt: old?.createdAt || new Date(), updatedAt: new Date()
  }, true);
  return { sessionId: sid, ...await orgLiveGet(uid, sid) };
}
export async function orgLiveGet(uid, sid) {
  const x = await firestoreGet("organizationLiveSessions/" + sid);
  if (!x || x.uid !== uid) throw new Error("organization_live_session_not_found");
  const w = await orgWallet(uid);
  return {
    sessionId: sid, status: String(x.status || "active"), mode: String(x.mode || "host_only"),
    billedCents: cents(x.billedCents), billedUsd: cents(x.billedCents) / 100,
    balanceCents: w.balanceCents, balanceUsd: w.balanceUsd,
    lowBalance: x.lowBalance === true || w.balanceCents <= w.lowBalanceWarningCents,
    graceUntil: x.graceUntil || null, graceSeconds: w.liveGraceSeconds
  };
}

/**
 * Each heartbeat uses the session's last persisted heartbeat as its idempotency
 * key. Wallet, session and ledger changes commit together with update-time
 * preconditions, preventing concurrent/retried heartbeats from double-charging.
 */
export async function orgLiveHeartbeat(uid, sid) {
  const sessionPath = "organizationLiveSessions/" + sid;
  const x = await firestoreGet(sessionPath);
  if (!x || x.uid !== uid) throw new Error("organization_live_session_not_found");
  if (["stopped", "blocked"].includes(String(x.status))) {
    return { ...await orgLiveGet(uid, sid), action: "stop", chargedCents: 0 };
  }

  const c = await cfg();
  const walletPath = "organizationWallets/" + uid;
  const walletDoc = await firestoreGet(walletPath);
  const balance = cents(walletDoc?.balanceCents);
  const interval = Math.max(5, Number(x.billingIntervalSeconds || c.liveBillingIntervalSeconds));
  const rate = cents(x.rateCentsPerMinute || c.liveRateCentsPerMinute[x.mode]);
  const charge = Math.max(0, Math.ceil(rate * interval / 60));
  const heartbeatKey = String(x.lastHeartbeatAt || x.createdAt || "initial");
  const ledgerPath = await stableId("org_live_heartbeat", uid + ":" + sid + ":" + heartbeatKey);
  const previous = await firestoreGet("organizationWalletLedger/" + ledgerPath);
  if (previous && previous.uid === uid && previous.sessionId === sid) {
    return {
      ...await orgLiveGet(uid, sid),
      action: previous.finalPartialInterval ? "grace" : "continue",
      chargedCents: cents(-Number(previous.amountCents || 0)),
      warning: previous.finalPartialInterval === true || cents(previous.balanceAfterCents) <= c.lowBalanceWarningCents,
      duplicate: true
    };
  }

  if (charge === 0) {
    await firestoreCommit([{
      ...updatePrecondition(sessionPath, x),
      data: { ...stripMetadata(x), lastHeartbeatAt: new Date(), updatedAt: new Date() }
    }]);
    return { ...await orgLiveGet(uid, sid), action: "continue", chargedCents: 0, warning: balance <= c.lowBalanceWarningCents };
  }

  if (balance >= charge) {
    const after = balance - charge;
    const now = new Date();
    const ledger = {
      uid, type: "usage", capability: "organization_live_stream", amountCents: -charge,
      balanceBeforeCents: balance, balanceAfterCents: after, currency: "USD",
      sessionId: sid, mode: x.mode, createdAt: now, idempotencyKey: heartbeatKey
    };
    try {
      await firestoreCommit([
        { ...updatePrecondition(walletPath, walletDoc), data: { ...stripMetadata(walletDoc), uid, balanceCents: after, currency: "USD", status: "active", updatedAt: now } },
        { ...updatePrecondition(sessionPath, x), data: { ...stripMetadata(x), billedCents: cents(x.billedCents) + charge, lowBalance: after <= c.lowBalanceWarningCents, lastHeartbeatAt: now, updatedAt: now } },
        { path: "organizationWalletLedger/" + ledgerPath, data: ledger, create: true }
      ]);
    } catch (error) {
      const replay = await firestoreGet("organizationWalletLedger/" + ledgerPath).catch(() => null);
      if (replay?.uid === uid && replay?.sessionId === sid) {
        return { ...await orgLiveGet(uid, sid), action: "continue", chargedCents: cents(-Number(replay.amountCents || 0)), warning: cents(replay.balanceAfterCents) <= c.lowBalanceWarningCents, duplicate: true };
      }
      throw error;
    }
    return { ...await orgLiveGet(uid, sid), action: "continue", chargedCents: charge, warning: after <= c.lowBalanceWarningCents };
  }

  if (balance > 0) {
    const now = new Date();
    const grace = new Date(Date.now() + c.liveGraceSeconds * 1000).toISOString();
    const ledger = {
      uid, type: "usage", capability: "organization_live_stream", amountCents: -balance,
      balanceBeforeCents: balance, balanceAfterCents: 0, currency: "USD",
      sessionId: sid, mode: x.mode, finalPartialInterval: true, createdAt: now,
      idempotencyKey: heartbeatKey
    };
    try {
      await firestoreCommit([
        { ...updatePrecondition(walletPath, walletDoc), data: { ...stripMetadata(walletDoc), uid, balanceCents: 0, currency: "USD", status: "active", updatedAt: now } },
        { ...updatePrecondition(sessionPath, x), data: { ...stripMetadata(x), billedCents: cents(x.billedCents) + balance, lowBalance: true, graceUntil: grace, lastHeartbeatAt: now, updatedAt: now } },
        { path: "organizationWalletLedger/" + ledgerPath, data: ledger, create: true }
      ]);
    } catch (error) {
      const replay = await firestoreGet("organizationWalletLedger/" + ledgerPath).catch(() => null);
      if (replay?.uid === uid && replay?.sessionId === sid) {
        return { ...await orgLiveGet(uid, sid), action: "grace", chargedCents: cents(-Number(replay.amountCents || 0)), warning: true, duplicate: true };
      }
      throw error;
    }
    return { ...await orgLiveGet(uid, sid), action: "grace", chargedCents: balance, warning: true };
  }

  const now = new Date();
  const patches = [
    { ...updatePrecondition(sessionPath, x), data: { ...stripMetadata(x), status: "blocked", lowBalance: true, updatedAt: now } }
  ];
  if (walletDoc) {
    patches.push({ ...updatePrecondition(walletPath, walletDoc), data: { ...stripMetadata(walletDoc), status: "blocked", updatedAt: now } });
  }
  await firestoreCommit(patches);
  return { ...await orgLiveGet(uid, sid), action: "stop", chargedCents: 0, warning: true };
}
export async function orgLiveStop(uid, sid, reason = "operator_stop") {
  const path = "organizationLiveSessions/" + sid;
  const x = await firestoreGet(path);
  if (!x || x.uid !== uid) throw new Error("organization_live_session_not_found");
  await firestoreCommit([{
    ...updatePrecondition(path, x),
    data: { ...stripMetadata(x), status: "stopped", stopReason: clean(reason, 200), stoppedAt: new Date(), updatedAt: new Date() }
  }]);
  return orgLiveGet(uid, sid);
}
export async function orgBillingConfig(adminUid, b) {
  const c = await cfg();
  const next = {
    assistantPriceCents: b.assistantPriceCents == null ? c.assistantPriceCents : cents(b.assistantPriceCents),
    liveRateCentsPerMinute: { ...c.liveRateCentsPerMinute, ...(b.liveRateCentsPerMinute || {}) },
    lowBalanceWarningCents: b.lowBalanceWarningCents == null ? c.lowBalanceWarningCents : cents(b.lowBalanceWarningCents),
    liveBillingIntervalSeconds: b.liveBillingIntervalSeconds == null ? c.liveBillingIntervalSeconds : Math.min(60, Math.max(5, Math.floor(Number(b.liveBillingIntervalSeconds)))),
    liveGraceSeconds: b.liveGraceSeconds == null ? c.liveGraceSeconds : Math.min(300, Math.max(60, Math.floor(Number(b.liveGraceSeconds))))
  };
  await firestoreSet("adminAdvancedOptions/organization_billing", { enabled: b.enabled !== false, config: next, updatedBy: adminUid, updatedAt: new Date() }, true);
  return next;
}

export async function orgChargeAssistant(uid, reference = "", capability = "organization_assistant") {
  const ref = clean(reference, 180) || crypto.randomUUID();
  const walletPath = "organizationWallets/" + uid;
  const ledgerId = await stableId("org_assistant_charge", uid + ":" + ref);
  const ledgerPath = "organizationWalletLedger/" + ledgerId;
  const prior = await firestoreGet(ledgerPath);
  if (prior) {
    if (prior.uid !== uid || prior.providerReference !== ref || prior.type !== "usage") throw new Error("organization_charge_reference_conflict");
    return { chargedCents: cents(-Number(prior.amountCents || 0)), balanceCents: cents(prior.balanceAfterCents), reference: ref, capability: String(prior.capability || capability), duplicate: true };
  }

  const c = await cfg();
  const price = c.assistantPriceCents;
  for (let attempt = 0; attempt < 3; attempt++) {
    const wallet = await firestoreGet(walletPath);
    const balance = cents(wallet?.balanceCents);
    if (wallet?.status === "blocked" || balance < price) throw new Error("organization_insufficient_balance");
    const after = balance - price;
    const now = new Date();
    try {
      await firestoreCommit([
        { ...updatePrecondition(walletPath, wallet), data: { ...stripMetadata(wallet), uid, balanceCents: after, currency: "USD", status: "active", updatedAt: now } },
        { path: ledgerPath, data: {
          uid, type: "usage", capability: String(capability || "organization_assistant"),
          amountCents: -price, balanceBeforeCents: balance, balanceAfterCents: after,
          currency: "USD", providerReference: ref, createdAt: now
        }, create: true }
      ]);
      return { chargedCents: price, balanceCents: after, reference: ref, capability: String(capability || "organization_assistant") };
    } catch (error) {
      const replay = await firestoreGet(ledgerPath).catch(() => null);
      if (replay?.uid === uid && replay?.providerReference === ref && replay?.type === "usage") {
        return { chargedCents: cents(-Number(replay.amountCents || 0)), balanceCents: cents(replay.balanceAfterCents), reference: ref, capability: String(replay.capability || capability), duplicate: true };
      }
      if (attempt === 2) throw error;
    }
  }
  throw new Error("organization_charge_failed");
}

export async function orgRefundAssistant(uid, charge, reference = "") {
  const ref = clean(reference || charge?.reference, 180);
  const amount = cents(charge?.chargedCents);
  if (!amount) return orgWallet(uid);
  if (!ref) throw new Error("organization_refund_reference_required");
  const chargeId = await stableId("org_assistant_charge", uid + ":" + ref);
  const refundId = await stableId("org_assistant_refund", uid + ":" + ref);
  const chargeRecord = await firestoreGet("organizationWalletLedger/" + chargeId);
  if (!chargeRecord || chargeRecord.uid !== uid || chargeRecord.type !== "usage" || chargeRecord.providerReference !== ref) {
    throw new Error("organization_refund_charge_not_found");
  }
  const refundPath = "organizationWalletLedger/" + refundId;
  const prior = await firestoreGet(refundPath);
  if (prior) {
    if (prior.uid !== uid || prior.providerReference !== ref || prior.type !== "refund") throw new Error("organization_refund_reference_conflict");
    return { ...(await orgWallet(uid)), refunded: true, duplicate: true };
  }

  const walletPath = "organizationWallets/" + uid;
  for (let attempt = 0; attempt < 3; attempt++) {
    const wallet = await firestoreGet(walletPath);
    const balance = cents(wallet?.balanceCents);
    const after = balance + amount;
    const now = new Date();
    try {
      await firestoreCommit([
        { ...updatePrecondition(walletPath, wallet), data: { ...stripMetadata(wallet), uid, balanceCents: after, currency: "USD", status: "active", updatedAt: now } },
        { path: refundPath, data: {
          uid, type: "refund", capability: String(chargeRecord.capability || charge?.capability || "organization_assistant"),
          amountCents: amount, balanceBeforeCents: balance, balanceAfterCents: after,
          currency: "USD", providerReference: ref, createdAt: now
        }, create: true }
      ]);
      return { balanceCents: after, currency: "USD", refunded: true };
    } catch (error) {
      const replay = await firestoreGet(refundPath).catch(() => null);
      if (replay?.uid === uid && replay?.providerReference === ref && replay?.type === "refund") {
        return { ...(await orgWallet(uid)), refunded: true, duplicate: true };
      }
      if (attempt === 2) throw error;
    }
  }
  throw new Error("organization_refund_failed");
}
