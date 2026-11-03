import { getFirestore, FieldValue } from "firebase-admin/firestore";

const DIRECT_CREDIT_REFERENCE_USD = 0.07999;

export function calculateDirectCreditCharge({ credits }) {
  const amount = Number(credits);
  if (!Number.isFinite(amount) || amount < 0) throw new Error("direct_credit_price_invalid");
  return {
    credits: amount,
    reservedCredits: amount,
    providerCostUsd: 0,
    sellingPriceUsd: 0,
    profitMarkup: 0,
    feeRateApplied: 0,
    creditValueUsd: DIRECT_CREDIT_REFERENCE_USD,
    estimated: true,
    pricingVersion: 1,
    estimateSource: "direct_credit_price"
  };
}

export async function priceOperation(capability, finalCreditPrice = 0) {
  return {
    ...calculateDirectCreditCharge({ credits: finalCreditPrice }),
    capability: String(capability || "unknown").trim().toLowerCase().slice(0, 80),
    providerId: null,
    modelId: null,
    rateSnapshot: null,
    pricingSnapshot: { mode: "direct_credit_price" }
  };
}

export async function recordBillingTransaction(id, data) {
  try {
    const ref = getFirestore().collection("aiBillingTransactions").doc(String(id));
    await getFirestore().runTransaction(async tx => {
      const existing = await tx.get(ref);
      if (existing.exists) return;
      tx.create(ref, {
        transactionId: String(id),
        uid: String(data.uid || ""),
        capability: String(data.capability || "unknown"),
        providerId: data.providerId || null,
        modelId: data.modelId || null,
        providerCostUsd: Number(data.providerCostUsd || 0),
        rateSnapshot: data.rateSnapshot && typeof data.rateSnapshot === "object" ? data.rateSnapshot : null,
        pricingSnapshot: data.pricingSnapshot && typeof data.pricingSnapshot === "object" ? data.pricingSnapshot : null,
        costType: data.costType || "estimated",
        sellingPriceUsd: Number(data.sellingPriceUsd || 0),
        profitMarkup: Number(data.profitMarkup || 0),
        feeRateApplied: Number(data.feeRateApplied || 0),
        creditValueUsd: Number(data.creditValueUsd || DIRECT_CREDIT_REFERENCE_USD),
        creditsReserved: Number(data.creditsReserved || 0),
        creditsCharged: Number(data.creditsCharged || 0),
        creditsRefunded: Number(data.creditsRefunded || 0),
        pricingVersion: Number(data.pricingVersion || 1),
        exemptType: data.exemptType || "credit_billed",
        status: data.status || "reserved",
        estimateSource: data.estimateSource || "estimated",
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp()
      });
    });
  } catch (error) {
    // Usage accounting must not turn a successful AI operation into a failed
    // response. Log only a stable error code; never log request data or secrets.
    console.error("ai_billing_analytics_write_failed", error?.code || "unknown");
  }
}

export async function updateBillingTransaction(id, patch = {}) {
  if (!id) return;
  try {
    const ref = getFirestore().collection("aiBillingTransactions").doc(String(id));
    await ref.set({ ...patch, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  } catch (error) {
    console.error("ai_billing_settlement_write_failed", error?.code || "unknown");
  }
}

function asDate(value) {
  if (value?.toDate) return value.toDate();
  if (value instanceof Date) return value;
  const d = new Date(value || 0);
  return Number.isNaN(d.getTime()) ? new Date(0) : d;
}

export async function getBillingAnalytics(limit = 500) {
  const db = getFirestore();
  const bounded = Math.max(1, Math.min(1000, Number(limit) || 500));
  const snap = await db.collection("aiBillingTransactions").orderBy("createdAt", "desc").limit(bounded).get();
  const now = new Date();
  const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1);
  const totals = () => ({ providerCostUsd: 0, estimatedProviderCostUsd: 0, usageCalculatedCostUsd: 0, customerProviderCostUsd: 0, revenueUsd: 0, estimatedProfitUsd: 0, creditsCharged: 0, creditsRefunded: 0, requests: 0, succeeded: 0, failed: 0, adminCostUsd: 0, subscriptionCostUsd: 0, outstandingReservations: 0, unsettled: 0, costOverruns: 0, costOverrunCredits: 0 });
  const today = totals(), month = totals(), all = totals();
  const byProvider = {}, byCapability = {}, transactions = [];
  for (const doc of snap.docs) {
    const x = doc.data() || {};
    const cost = Math.max(0, finite(x.providerCostUsd, 0));
    const charged = Math.max(0, finite(x.creditsCharged, 0));
    const refunded = Math.max(0, finite(x.creditsRefunded, 0));
    const creditValue = Math.max(0, finite(x.creditValueUsd, DIRECT_CREDIT_REFERENCE_USD));
    const exempt = x.exemptType === "admin_exempt";
    const subscription = x.exemptType === "subscription_included" || x.exemptType === "subscription_or_free";
    const revenue = exempt || subscription ? 0 : Math.max(0, finite(x.sellingPriceUsd, charged * creditValue));
    const fee = Math.max(0, finite(x.feeRateApplied, 0)) * revenue;
    const customerCost = exempt || subscription ? 0 : cost;
    const profit = revenue - customerCost - fee;
    const created = asDate(x.createdAt);
    const status = String(x.status || "reserved");
    const add = target => {
      target.providerCostUsd += cost;
      if (x.costType === "calculated_from_actual_usage") target.usageCalculatedCostUsd += cost;
      else target.estimatedProviderCostUsd += cost;
      target.customerProviderCostUsd += customerCost;
      target.revenueUsd += revenue;
      target.estimatedProfitUsd += profit;
      target.creditsCharged += charged;
      target.creditsRefunded += refunded;
      target.requests += 1;
      if (status === "committed") target.succeeded += 1;
      if (status === "failed" || status === "reversed") target.failed += 1;
      if (Number(x.costOverrunCredits || 0) > 0) { target.costOverruns += 1; target.costOverrunCredits += Number(x.costOverrunCredits || 0); }
      if (exempt) target.adminCostUsd += cost;
      if (subscription) target.subscriptionCostUsd += cost;
      if (status === "reserved") target.outstandingReservations += 1;
      if (status !== "committed" && status !== "failed" && status !== "reversed") target.unsettled += 1;
    };
    add(all);
    if (created >= dayStart) add(today);
    if (created >= monthStart) add(month);
    const provider = String(x.providerId || "unknown");
    const capability = String(x.capability || "unknown");
    for (const [map, key] of [[byProvider, provider], [byCapability, capability]]) {
      const row = map[key] || (map[key] = { requests: 0, providerCostUsd: 0, revenueUsd: 0, estimatedProfitUsd: 0 });
      row.requests += 1; row.providerCostUsd += cost; row.revenueUsd += revenue; row.estimatedProfitUsd += profit;
    }
    transactions.push({
      transactionId: doc.id, uid: x.uid || "", capability, providerId: x.providerId || null,
      modelId: x.modelId || null, providerCostUsd: cost, costType: x.costType || "estimated",
      sellingPriceUsd: revenue, creditsReserved: finite(x.creditsReserved, 0),
      creditsCharged: charged, creditsRefunded: refunded, status, exemptType: x.exemptType || "credit_billed",
      pricingVersion: finite(x.pricingVersion, 1), usage: x.usage || null,
      costOverrun: Boolean(x.costOverrun), costOverrunCredits: finite(x.costOverrunCredits, 0),
      createdAt: created.toISOString()
    });
  }
  const round = obj => Object.fromEntries(Object.entries(obj).map(([k,v]) => [k, typeof v === "number" ? Math.round(v * 10000) / 10000 : v]));
  return {
    generatedAt: new Date().toISOString(), source: "latest_transactions_bounded",
    transactionCount: transactions.length, limit: bounded,
    today: round(today), month: round(month), all: round(all),
    byProvider: Object.entries(byProvider).map(([providerId, value]) => ({ providerId, ...round(value) })).sort((a,b)=>b.providerCostUsd-a.providerCostUsd),
    byCapability: Object.entries(byCapability).map(([capability, value]) => ({ capability, ...round(value) })).sort((a,b)=>b.providerCostUsd-a.providerCostUsd),
    transactions
  };
}
