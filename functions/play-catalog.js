// BQ Sqwad AI Google Play catalog projection.
// The canonical source is functions/plan-catalog.js. Firestore playCatalog/config overrides this projection at runtime.
// Google Play localized pricing is authoritative at checkout.

import { COMMERCIAL_CATALOG, PLAN_CATALOG_VERSION, validateCommercialCatalog } from "./plan-catalog.js";
import { ORGANIZATION_WALLET_TOPUPS } from "./organization-billing.js";

export const CANONICAL_PLAY_CATALOG_VERSION = PLAN_CATALOG_VERSION;

const rows = Object.values(COMMERCIAL_CATALOG.sections).flat();

function entriesFor(item) {
  if (!item.playEligible || !item.productId || item.productType === "free" || item.productType === "custom") return [];
  if (item.priceStatus === "proposal") {
    // Proposal prices are visible to the admin catalog but are never silently published to Play.
    return [];
  }

  const periods = item.billingPeriods || [];
  return periods.flatMap(period => {
    const price = item.price?.[period];
    if (price == null || typeof price !== "number") return [];
    if (Array.isArray(item.playEligiblePeriods) && !item.playEligiblePeriods.includes(period)) return [];
    if (Array.isArray(item.proposalPeriods) && item.proposalPeriods.includes(period)) return [];

    // Base-plan IDs are deliberately stable and human-readable. In particular,
    // every annual offer is the Google Play "yearly" base plan. The BQ Sqwad
    // catalog remains the user-facing catalog; this projection is only the
    // private checkout mapping used to launch and verify Google Play purchases.
    let basePlanId = null;
    if (item.productType === "subs") {
      if (Array.isArray(item.basePlans)) {
        basePlanId = item.basePlans.find(x => String(x).endsWith("-" + period)) || item.basePlans.find(x => String(x).includes(period)) || null;
      }
      if (!basePlanId) basePlanId = period;

      if (!Array.isArray(item.basePlans) || !item.basePlans.includes(basePlanId)) {
        throw new Error("missing_base_plan:" + item.id + ":" + basePlanId);
      }
    }

    const eligibleTrial = item.trialEligible === true && item.offerId === "trial-3d" && item.productType === "subs";
    return [{
      module: item.module,
      name: item.name,
      planId: item.id,
      productId: item.productId,
      basePlanId,
      billingPeriod: period,
      priceUsd: price,
      currency: item.currency || "USD",
      productType: item.productType,
      recurring: item.recurring === true,
      playEligible: true,
      trialOfferId: eligibleTrial ? "trial-3d" : null,
      trialDays: eligibleTrial ? 3 : 0,
      gracePeriodDays: item.gracePeriodDays ?? 7,
      accountHold: item.accountHold || "google-play-automatic",
      resubscribe: item.resubscribe || "allow",
      planChange: item.planChange || "charge-next-billing-date",
      limits: item.limits || {},
      features: item.features || [],
      entitlements: item.entitlements || [],
      quantityTiers: item.quantityTiers || [],
      contactSales: false,
      active: item.active !== false,
      countryAvailability: item.countryAvailability || {mode:"all",countries:[]}
    }];
  });
}

export const CANONICAL_PLAY_CATALOG = [
  ...rows.flatMap(entriesFor),
  ...ORGANIZATION_WALLET_TOPUPS.map(x => ({
    module: "organization-wallet", name: "Organization AI usage deposit", planId: "", productId: x.productId,
    basePlanId: null, billingPeriod: "one_time", priceUsd: x.amountUsd, currency: "USD",
    productType: "inapp", recurring: false, playEligible: true, trialOfferId: null, trialDays: 0,
    gracePeriodDays: 0, accountHold: "none", resubscribe: "allow", planChange: "not_applicable",
    limits: {}, features: ["Non-transferable Organization AI usage balance"], entitlements: [],
    walletTopupCents: x.amountCents, organizationWalletTopup: true, consumable: true
  }))
];

export function canonicalPlayProducts() {
  return CANONICAL_PLAY_CATALOG.map(x => ({
    planId: x.planId,
    product: x.productId,
    productId: x.productId,
    basePlanId: x.basePlanId,
    productType: x.productType === "subs" ? "subs" : "inapp",
    enabled: x.active !== false,
    consumable: false,
    creditGrant: Number((x.entitlements?.find(e => String(e).startsWith("credit:")) || "").split(":")[1] || 0),
    trialOfferId: x.trialOfferId,
    trialDays: x.trialDays,
    billingPeriod: x.billingPeriod,
    priceUsd: x.priceUsd,
    recurring: x.recurring,
    quantityTiers: x.quantityTiers,
    walletTopupCents: x.walletTopupCents || 0,
    organizationWalletTopup: x.organizationWalletTopup === true
  }));
}

export function canonicalAdminCatalog() {
  validateCommercialCatalog();
  const all = rows.map(item => ({
    ...item,
    googlePlayProductId: item.productId,
    basePlanIds: item.basePlans || [],
    offerId: item.offerId,
    localizedPlayPrice: item.localizedPlayPrice || "play-authoritative",
    billingType: item.productType === "subs" ? (item.recurring ? "auto-renewing" : "prepaid") : item.productType,
    recurringVsOneTime: item.recurring ? "recurring" : "one-time",
    consumable: item.productType === "inapp" && item.recurring === false,
    playBillingStatus: item.playEligible ? "eligible" : item.contactSales ? "contact-sales" : "not-for-play",
    tierId: item.tierId,
    tierConfiguration: item.quantityTiers || [],
    includedAddOns: item.includedAddOns || [],
    addOnQuantityRules: item.addOnQuantityRules || null,
    availability: item.countryAvailability || {mode:"all",countries:[]},
    configurationVersion: PLAN_CATALOG_VERSION
  }));

  return {
    version: PLAN_CATALOG_VERSION,
    catalogVersion: PLAN_CATALOG_VERSION,
    currency: COMMERCIAL_CATALOG.currency,
    contactSalesEmail: COMMERCIAL_CATALOG.contactSalesEmail,
    globalRules: COMMERCIAL_CATALOG.globalRules,
    plans: all,
    googlePlayProducts: canonicalPlayProducts(),
    visibility: "admin_only"
  };
}
