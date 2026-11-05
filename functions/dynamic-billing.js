import { getFirestore, FieldValue } from "firebase-admin/firestore";


function finite(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

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
    creditValueUsd: 0,
    estimated: true,
    pricingVersion: 1,
    estimateSource: "direct_credit_price"
  };
}

export const TOKEN_METERED_CAPABILITIES = new Set([
  "chat", "text-chat", "vision", "chat_attachment", "personal_intelligence",
  "team_chat", "image_generation", "image_edit"
]);

export function calculateTokenCreditsFromRule(rule, usage = {}) {
  const input = Math.max(0, Number(usage.inputTokens ?? usage.input_tokens ?? usage.prompt_tokens ?? 0));
  const output = Math.max(0, Number(usage.outputTokens ?? usage.output_tokens ?? usage.completion_tokens ?? 0));
  const inDetails = usage.input_token_details || usage.input_tokens_details || usage.prompt_tokens_details || {};
  const outDetails = usage.output_token_details || usage.output_tokens_details || usage.completion_tokens_details || {};
  const audioIn = Math.max(0, Number(usage.inputAudioTokens ?? usage.input_audio_tokens ?? inDetails.audio_tokens ?? 0));
  const cachedAudioIn = Math.max(0, Number(usage.cachedInputAudioTokens ?? inDetails.cached_audio_tokens ?? 0));
  const imageIn = Math.max(0, Number(usage.inputImageTokens ?? usage.input_image_tokens ?? inDetails.image_tokens ?? 0));
  const cachedImageIn = Math.max(0, Number(usage.cachedInputImageTokens ?? inDetails.cached_image_tokens ?? 0));
  const audioOut = Math.max(0, Number(usage.outputAudioTokens ?? usage.output_audio_tokens ?? outDetails.audio_tokens ?? 0));
  const imageOut = Math.max(0, Number(usage.outputImageTokens ?? usage.output_image_tokens ?? outDetails.image_tokens ?? 0));
  const cachedText = Math.max(0, Number(usage.cachedInputTokens ?? usage.cached_input_tokens ?? inDetails.cached_tokens ?? 0));
  if (audioIn + imageIn > input || audioOut + imageOut > output || cachedAudioIn > audioIn || cachedImageIn > imageIn) throw new Error("token_usage_modality_invalid");
  const textIn = input - audioIn - imageIn;
  const textOut = output - audioOut - imageOut;
  const cachedTextTokens = Math.min(textIn, cachedText);
  const rates = [
    [textIn - cachedTextTokens, rule.inputCreditsPerToken],
    [cachedTextTokens, rule.cachedInputCreditsPerToken],
    [textOut, rule.outputCreditsPerToken],
    [audioIn - cachedAudioIn, rule.inputAudioCreditsPerToken],
    [cachedAudioIn, rule.cachedInputAudioCreditsPerToken],
    [audioOut, rule.outputAudioCreditsPerToken],
    [imageIn - cachedImageIn, rule.inputImageCreditsPerToken],
    [cachedImageIn, rule.cachedInputImageCreditsPerToken],
    [imageOut, rule.outputImageCreditsPerToken]
  ];
  for (const [count, rate] of rates) if (count > 0 && (!Number.isFinite(Number(rate)) || Number(rate) < 0)) throw new Error("token_pricing_not_configured");
  const credits = rates.reduce((sum, [count, rate]) => sum + Number(count) * Number(rate || 0), 0);
  return Math.round((credits + Number.EPSILON) * 1_000_000_000_000_000) / 1_000_000_000_000_000;
}

export async function priceTokenUsage({ providerId = "openai", modelId, usage, feature, customerClass = "all" }) {
  if (!modelId || !usage || typeof usage !== "object") throw new Error("token_usage_missing");
  const hasUsage = ["inputTokens", "input_tokens", "prompt_tokens", "outputTokens", "output_tokens", "completion_tokens"]
    .some(key => Object.prototype.hasOwnProperty.call(usage, key));
  if (!hasUsage) throw new Error("token_usage_missing");
  const provider = String(providerId || "").toLowerCase();
  const snapshot = await getFirestore().collection("tokenPricingRules").where("model", "==", String(modelId)).where("status", "==", "active").limit(100).get();
  const rows = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
  const rule = rows.filter(row =>
    String(row.provider || "").toLowerCase() === provider &&
    ["all", customerClass].includes(String(row.customerClass || "all").toLowerCase()) &&
    (!Array.isArray(row.features) || row.features.length === 0 || row.features.includes(String(feature || "")))
  ).sort((a, b) => Number(b.version || 0) - Number(a.version || 0))[0];
  if (!rule) throw new Error("token_pricing_not_configured");
  return { credits: calculateTokenCreditsFromRule(rule, usage), providerCostUsd: 0, pricingVersion: Number(rule.version || 1), pricingSnapshot: { mode: "tokenPricingRules", ruleId: rule.id, model: rule.model, version: Number(rule.version || 1) } };
}

const CAPABILITY_TO_GENERATION_OPERATION = Object.freeze({
  video_generation: "video.generate", video: "video.generate",
  chat_background_upload: "chat.background_upload", team_member_message: "team.member_message",
  connector_action: "feature.connector_action", resume: "resume.generate",
  web_search: "web.search", deep_research: "web.research",
  music: "music.generate", music_render: "music.render",
  pdf: "file.pdf", file_pdf: "file.pdf", file_docx: "file.docx", file_xlsx: "file.xlsx",
  file_csv: "file.csv", file_txt: "file.txt", file_md: "file.md", file_html: "file.html",
  file_json: "file.json", file_code: "file.code", file_xml: "file.xml", file_yaml: "file.yaml"
});

export async function priceOperation(capability, units = 0) {
  const key = String(capability || "").trim().toLowerCase();
  if (TOKEN_METERED_CAPABILITIES.has(key)) {
    return { ...calculateDirectCreditCharge({ credits: 0 }), capability: key, providerId: null, modelId: null, pricingSnapshot: { mode: "tokenPricingRules" }, estimateSource: "token_metered_usage" };
  }
  if (["live_voice", "live_camera", "dictation", "text_to_speech"].includes(key)) {
    throw new Error("duration_metering_required");
  }
  const operation = CAPABILITY_TO_GENERATION_OPERATION[key] || (key.includes(".") ? key : "");
  if (!operation) throw new Error("generation_credit_rule_missing");
  const requestedUnits = Number(units);
  if (!Number.isFinite(requestedUnits) || requestedUnits < 0) throw new Error("generation_usage_units_invalid");
  const snapshot = await getFirestore().collection("generationCostRules")
    .where("operation", "==", operation).where("status", "==", "active").limit(100).get();
  const rows = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
  const rule = rows.filter(row => !row.model || row.model === "*")
    .sort((a, b) => Number(b.version || 0) - Number(a.version || 0))[0];
  if (!rule) throw new Error("generation_credit_rule_missing");
  const unit = String(rule.unit || "none");
  let variableUnits = requestedUnits;
  if (unit === "none") variableUnits = 0;
  else if (unit === "minute") variableUnits = requestedUnits / 60;
  else if (unit !== "second" && unit !== "page" && unit !== "file" && unit !== "resume") {
    throw new Error("generation_credit_rule_unit_invalid");
  }
  const credits = Math.max(0, Number(rule.credits || 0) + Math.max(0, Number(rule.creditsPerUnit || 0)) * variableUnits);
  return {
    ...calculateDirectCreditCharge({ credits }),
    capability: key,
    providerId: null,
    modelId: null,
    rateSnapshot: null,
    pricingSnapshot: { mode: "generationCostRules", operation, ruleId: rule.id, version: Number(rule.version || 1), unit }
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
        creditValueUsd: Number(data.creditValueUsd || 0),
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
    const creditValue = Math.max(0, finite(x.creditValueUsd, 0));
    const exempt = x.exemptType === "admin_exempt";
    const subscription = x.exemptType === "subscription_included" || x.exemptType === "subscription_or_free";
    const revenue = exempt || subscription ? 0 : Math.max(0, finite(x.sellingPriceUsd, 0));
    const fee = 0;
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
