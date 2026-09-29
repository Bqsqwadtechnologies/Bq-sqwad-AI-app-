import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";

const DEFAULTS = {
  currency: "USD",
  assistantPriceCents: 100,
  liveRateCentsPerMinute: {
    host_only: 25,
    moderated_audience: 55,
    full_audience: 95
  },
  lowBalanceWarningCents: 500,
  liveBillingIntervalSeconds: 10,
  liveGraceSeconds: 180
};

const TOP_UPS = [
  { productId: "orgdeposit10", amountCents: 1000, amountUsd: 10 },
  { productId: "orgdeposit25", amountCents: 2500, amountUsd: 25 },
  { productId: "orgdeposit50", amountCents: 5000, amountUsd: 50 },
  { productId: "orgdeposit100", amountCents: 10000, amountUsd: 100 },
  { productId: "orgdeposit500", amountCents: 50000, amountUsd: 500 }
];


async function notifyOrganizationBilling(uid, title, body, sessionId = "") {
  try {
    const snap = await getFirestore().collection("notificationTokens").where("uid", "==", uid).where("enabled", "==", true).limit(500).get();
    const tokens = snap.docs.map(d => String(d.data()?.token || "")).filter(Boolean);
    if (!tokens.length) return;
    await getMessaging().sendEachForMulticast({
      tokens,
      notification: { title, body },
      data: { type: "organization_billing", sessionId: String(sessionId || "") }
    });
  } catch (_) {
    // Billing enforcement must never fail because a notification could not be delivered.
  }
}
function clean(value, max = 240) {
  return String(value ?? "").trim().slice(0, max);
}

function walletRef(uid) {
  return getFirestore().collection("organizationWallets").doc(uid);
}

function sessionRef(sessionId) {
  return getFirestore().collection("organizationLiveSessions").doc(sessionId);
}

function ledgerRef() {
  return getFirestore().collection("organizationWalletLedger").doc();
}

async function config() {
  const snap = await getFirestore().collection("adminAdvancedOptions").doc("organization_billing").get();
  const data = snap.exists ? (snap.data()?.config || {}) : {};
  const rates = { ...DEFAULTS.liveRateCentsPerMinute, ...(data.liveRateCentsPerMinute || {}) };
  return {
    currency: "USD",
    assistantPriceCents: Math.max(0, Number(data.assistantPriceCents ?? DEFAULTS.assistantPriceCents)),
    liveRateCentsPerMinute: Object.fromEntries(Object.entries(rates).map(([k, v]) => [k, Math.max(0, Number(v))])),
    lowBalanceWarningCents: Math.max(0, Number(data.lowBalanceWarningCents ?? DEFAULTS.lowBalanceWarningCents)),
    liveBillingIntervalSeconds: Math.min(60, Math.max(5, Number(data.liveBillingIntervalSeconds ?? DEFAULTS.liveBillingIntervalSeconds))),
    liveGraceSeconds: Math.min(300, Math.max(60, Number(data.liveGraceSeconds ?? DEFAULTS.liveGraceSeconds)))
  };
}

export async function getOrganizationWallet(uid) {
  const snap = await walletRef(uid).get();
  const data = snap.exists ? snap.data() : {};
  const balanceCents = Math.max(0, Number(data.balanceCents || 0));
  const cfg = await config();
  return {
    uid,
    currency: cfg.currency,
    balanceCents,
    balanceUsd: balanceCents / 100,
    lowBalanceWarningCents: cfg.lowBalanceWarningCents,
    lowBalanceWarningUsd: cfg.lowBalanceWarningCents / 100,
    assistantPriceCents: cfg.assistantPriceCents,
    assistantPriceUsd: cfg.assistantPriceCents / 100,
    liveRateCentsPerMinute: cfg.liveRateCentsPerMinute,
    liveBillingIntervalSeconds: cfg.liveBillingIntervalSeconds,
    liveGraceSeconds: cfg.liveGraceSeconds,
    status: data.status || "active",
    updatedAt: data.updatedAt || null
  };
}

export function organizationWalletTopUps() {
  return TOP_UPS.map(x => ({ ...x, currency: "USD", displayAmount: x.amountUsd.toFixed(2) }));
}

export async function creditOrganizationWallet(uid, amountCents, metadata = {}) {
  const amount = Math.floor(Number(amountCents));
  if (!Number.isFinite(amount) || amount <= 0) throw new Error("organization_wallet_amount_invalid");
  const db = getFirestore();
  const wallet = walletRef(uid);
  const ledger = ledgerRef();

  await db.runTransaction(async tx => {
    const snap = await tx.get(wallet);
    const current = snap.exists ? snap.data() || {} : {};
    const before = Math.max(0, Number(current.balanceCents || 0));
    const after = before + amount;
    tx.set(wallet, {
      uid,
      balanceCents: after,
      currency: "USD",
      status: "active",
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
    tx.create(ledger, {
      uid,
      type: "deposit",
      amountCents: amount,
      balanceBeforeCents: before,
      balanceAfterCents: after,
      currency: "USD",
      productId: clean(metadata.productId, 120) || null,
      provider: clean(metadata.provider, 60) || "google_play",
      providerReference: clean(metadata.providerReference, 240) || null,
      note: clean(metadata.note, 500) || "Organization usage balance deposit",
      createdAt: FieldValue.serverTimestamp()
    });
  });
  return getOrganizationWallet(uid);
}

export async function creditOrganizationWalletOnce(uid, amountCents, metadata = {}) {
  const amount = Math.floor(Number(amountCents));
  const providerReference = clean(metadata.providerReference, 240);
  if (!Number.isFinite(amount) || amount <= 0) throw new Error("organization_wallet_amount_invalid");
  if (!providerReference) throw new Error("organization_wallet_provider_reference_required");
  const db = getFirestore();
  const wallet = walletRef(uid);
  const existingSnap = await db.collection("organizationWalletLedger")
    .where("uid", "==", uid)
    .where("providerReference", "==", providerReference)
    .where("type", "==", "deposit")
    .limit(1).get();
  if (!existingSnap.empty) return getOrganizationWallet(uid);
  const ledger = ledgerRef();
  await db.runTransaction(async tx => {
    const duplicate = await tx.get(db.collection("organizationWalletLedger").where("uid", "==", uid).where("providerReference", "==", providerReference).where("type", "==", "deposit").limit(1));
    if (!duplicate.empty) return;
    const snap = await tx.get(wallet);
    const current = snap.exists ? snap.data() || {} : {};
    const before = Math.max(0, Number(current.balanceCents || 0));
    const after = before + amount;
    tx.set(wallet, {
      uid, balanceCents: after, currency: "USD", status: "active",
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
    tx.create(ledger, {
      uid, type: "deposit", amountCents: amount,
      balanceBeforeCents: before, balanceAfterCents: after, currency: "USD",
      productId: clean(metadata.productId, 120) || null,
      provider: clean(metadata.provider, 60) || "google_play",
      providerReference,
      note: clean(metadata.note, 500) || "Organization usage balance deposit",
      createdAt: FieldValue.serverTimestamp()
    });
  });
  return getOrganizationWallet(uid);
}

export async function chargeOrganizationWallet(uid, amountCents, capability, metadata = {}) {
  const amount = Math.max(0, Math.ceil(Number(amountCents)));
  if (!Number.isFinite(amount)) throw new Error("organization_wallet_charge_invalid");
  if (amount === 0) return { chargedCents: 0, balanceCents: (await getOrganizationWallet(uid)).balanceCents, warning: false };
  const db = getFirestore();
  const wallet = walletRef(uid);
  const ledger = ledgerRef();

  const result = await db.runTransaction(async tx => {
    const snap = await tx.get(wallet);
    const current = snap.exists ? snap.data() || {} : {};
    if (current.status === "blocked") throw new Error("organization_billing_blocked");
    const before = Math.max(0, Number(current.balanceCents || 0));
    if (before < amount) throw new Error("organization_insufficient_balance");
    const after = before - amount;
    tx.set(wallet, { uid, balanceCents: after, currency: "USD", updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    tx.create(ledger, {
      uid,
      type: "usage",
      capability: clean(capability, 100),
      amountCents: -amount,
      balanceBeforeCents: before,
      balanceAfterCents: after,
      currency: "USD",
      sessionId: clean(metadata.sessionId, 160) || null,
      providerReference: clean(metadata.providerReference, 240) || null,
      metadata: metadata && typeof metadata === "object" ? metadata : {},
      createdAt: FieldValue.serverTimestamp()
    });
    return { before, after };
  });
  const cfg = await config();
  return {
    chargedCents: amount,
    balanceCents: result.after,
    warning: result.after <= cfg.lowBalanceWarningCents,
    lowBalance: result.after <= cfg.lowBalanceWarningCents
  };
}

export async function startOrganizationLiveBilling(uid, input = {}) {
  const cfg = await config();
  const balance = await getOrganizationWallet(uid);
  if (balance.status === "blocked") throw new Error("organization_billing_blocked");
  if (balance.balanceCents <= 0) throw new Error("organization_insufficient_balance");
  const mode = ["host_only", "moderated_audience", "full_audience"].includes(String(input.mode)) ? String(input.mode) : "host_only";
  const sessionId = clean(input.sessionId, 160);
  if (!sessionId) throw new Error("organization_live_session_required");
  const ref = sessionRef(sessionId);
  const existing = await ref.get();
  if (existing.exists && existing.data()?.uid !== uid) throw new Error("organization_live_session_owned_by_another_account");
  await ref.set({
    uid,
    sessionId,
    mode,
    broadcastId: clean(input.broadcastId, 160) || null,
    accountId: clean(input.accountId, 160) || null,
    status: "active",
    rateCentsPerMinute: cfg.liveRateCentsPerMinute[mode] ?? 0,
    billingIntervalSeconds: cfg.liveBillingIntervalSeconds,
    billedCents: existing.exists ? Number(existing.data()?.billedCents || 0) : 0,
    lastHeartbeatAt: FieldValue.serverTimestamp(),
    graceUntil: null,
    lowBalance: balance.balanceCents <= cfg.lowBalanceWarningCents,
    createdAt: existing.exists ? (existing.data()?.createdAt || FieldValue.serverTimestamp()) : FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });
  return { sessionId, ...await getOrganizationLiveBilling(uid, sessionId) };
}

export async function getOrganizationLiveBilling(uid, sessionId) {
  const snap = await sessionRef(sessionId).get();
  if (!snap.exists || snap.data()?.uid !== uid) throw new Error("organization_live_session_not_found");
  const data = snap.data() || {};
  const wallet = await getOrganizationWallet(uid);
  return {
    sessionId,
    status: String(data.status || "active"),
    mode: String(data.mode || "host_only"),
    billedCents: Math.max(0, Number(data.billedCents || 0)),
    billedUsd: Math.max(0, Number(data.billedCents || 0)) / 100,
    balanceCents: wallet.balanceCents,
    balanceUsd: wallet.balanceUsd,
    lowBalance: data.lowBalance === true || wallet.balanceCents <= wallet.lowBalanceWarningCents,
    graceUntil: data.graceUntil || null,
    graceSeconds: wallet.liveGraceSeconds
  };
}

export async function heartbeatOrganizationLiveBilling(uid, sessionId) {
  const ref = sessionRef(sessionId);
  const db = getFirestore();
  const cfg = await config();
  const result = await db.runTransaction(async tx => {
    const sessionSnap = await tx.get(ref);
    const walletSnap = await tx.get(walletRef(uid));
    if (!sessionSnap.exists || sessionSnap.data()?.uid !== uid) throw new Error("organization_live_session_not_found");
    const session = sessionSnap.data() || {};
    const wallet = walletSnap.exists ? walletSnap.data() || {} : {};
    if (["stopped", "blocked"].includes(String(session.status))) {
      return { action: "stop", chargedCents: 0, balanceCents: Math.max(0, Number(wallet.balanceCents || 0)), graceUntil: session.graceUntil || null, status: String(session.status) };
    }
    const balanceCents = Math.max(0, Number(wallet.balanceCents || 0));
    const rate = Math.max(0, Number(session.rateCentsPerMinute ?? cfg.liveRateCentsPerMinute[session.mode] ?? 0));
    const interval = Math.max(5, Number(session.billingIntervalSeconds || cfg.liveBillingIntervalSeconds));
    const charge = Math.max(0, Math.ceil(rate * interval / 60));
    const graceUntilMs = session.graceUntil ? Date.parse(String(session.graceUntil)) : NaN;

    if (balanceCents === 0 && Number.isFinite(graceUntilMs) && graceUntilMs > Date.now()) {
      return { action: "grace", chargedCents: 0, balanceCents: 0, graceUntil: session.graceUntil, status: "active", lowBalance: true };
    }

    if (charge === 0) {
      tx.set(ref, { lastHeartbeatAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      return { action: "continue", chargedCents: 0, balanceCents, graceUntil: null, status: "active", lowBalance: balanceCents <= cfg.lowBalanceWarningCents };
    }

    if (balanceCents >= charge) {
      const after = balanceCents - charge;
      tx.set(walletRef(uid), { uid, balanceCents: after, currency: "USD", status: "active", updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      tx.create(ledgerRef(), { uid, type: "usage", capability: "organization_live_stream", amountCents: -charge, balanceBeforeCents: balanceCents, balanceAfterCents: after, currency: "USD", sessionId, mode: session.mode, createdAt: FieldValue.serverTimestamp() });
      tx.set(ref, { billedCents: Number(session.billedCents || 0) + charge, lowBalance: after <= cfg.lowBalanceWarningCents, graceUntil: null, lastHeartbeatAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      return { action: "continue", chargedCents: charge, balanceCents: after, graceUntil: null, status: "active", lowBalance: after <= cfg.lowBalanceWarningCents, warning: !session.lowBalance && after <= cfg.lowBalanceWarningCents };
    }

    if (balanceCents > 0) {
      const graceUntil = new Date(Date.now() + cfg.liveGraceSeconds * 1000).toISOString();
      tx.set(walletRef(uid), { uid, balanceCents: 0, currency: "USD", status: "active", updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      tx.create(ledgerRef(), { uid, type: "usage", capability: "organization_live_stream", amountCents: -balanceCents, balanceBeforeCents: balanceCents, balanceAfterCents: 0, currency: "USD", sessionId, mode: session.mode, finalPartialInterval: true, createdAt: FieldValue.serverTimestamp() });
      tx.set(ref, { billedCents: Number(session.billedCents || 0) + balanceCents, lowBalance: true, graceUntil, lastHeartbeatAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      return { action: "grace", chargedCents: balanceCents, balanceCents: 0, graceUntil, status: "active", lowBalance: true, warning: true, message: "Organization usage balance is empty. Add funds within the grace period or the live stream will be stopped." };
    }

    tx.set(ref, { status: "blocked", lowBalance: true, lastHeartbeatAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    tx.set(walletRef(uid), { status: "blocked", updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    return { action: "stop", chargedCents: 0, balanceCents: 0, graceUntil: null, status: "blocked", lowBalance: true, warning: true, message: "Organization usage balance is empty and the grace period expired. Live streaming has been stopped and billing access is blocked until funds are added." };
  });

  if (result.warning === true) {
    const title = result.action === "stop" ? "Organization live stream stopped" : "Organization usage balance is low";
    const body = result.action === "stop"
      ? "The Organization usage balance is empty and the grace period expired. Add funds to restore billing access."
      : result.action === "grace"
        ? "Your Organization usage balance is empty. Add funds before the grace period expires to keep the live stream running."
        : "Your Organization usage balance is getting low. Add funds before live usage reaches zero.";
    await notifyOrganizationBilling(uid, title, body, sessionId);
  }
  return {
    ...(await getOrganizationLiveBilling(uid, sessionId)),
    ...result
  };
}
export async function stopOrganizationLiveBilling(uid, sessionId, reason = "operator_stop") {
  const ref = sessionRef(sessionId);
  const snap = await ref.get();
  if (!snap.exists || snap.data()?.uid !== uid) throw new Error("organization_live_session_not_found");
  await ref.set({ status: "stopped", stopReason: clean(reason, 200), stoppedAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return getOrganizationLiveBilling(uid, sessionId);
}

export async function organizationBillingAdminConfig(adminUid, patch = {}) {
  const db = getFirestore();
  const user = await (await import("firebase-admin/auth")).getAuth().getUser(adminUid);
  const claims = user.customClaims || {};
  const allowed = user.email === "isaacoshiomole0@gmail.com" && (claims.admin === true || claims.superAdmin === true || claims.role === "admin" || claims.role === "super_admin");
  if (!allowed) throw new Error("Admin access required");
  const current = await config();
  const next = {
    assistantPriceCents: patch.assistantPriceCents == null ? current.assistantPriceCents : Math.max(0, Math.floor(Number(patch.assistantPriceCents))),
    liveRateCentsPerMinute: {
      ...current.liveRateCentsPerMinute,
      ...(patch.liveRateCentsPerMinute || {})
    },
    lowBalanceWarningCents: patch.lowBalanceWarningCents == null ? current.lowBalanceWarningCents : Math.max(0, Math.floor(Number(patch.lowBalanceWarningCents))),
    liveBillingIntervalSeconds: patch.liveBillingIntervalSeconds == null ? current.liveBillingIntervalSeconds : Math.min(60, Math.max(5, Math.floor(Number(patch.liveBillingIntervalSeconds)))),
    liveGraceSeconds: patch.liveGraceSeconds == null ? current.liveGraceSeconds : Math.min(300, Math.max(60, Math.floor(Number(patch.liveGraceSeconds))))
  };
  await db.collection("adminAdvancedOptions").doc("organization_billing").set({
    enabled: patch.enabled !== false,
    config: next,
    updatedAt: FieldValue.serverTimestamp(),
    updatedBy: adminUid
  }, { merge: true });
  return next;
}

export const ORGANIZATION_WALLET_TOPUPS = TOP_UPS;
