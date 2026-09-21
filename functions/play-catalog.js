// Canonical BQ Sqwad AI Google Play/admin catalog.
// IDs intentionally contain no underscore characters. Live Firestore configuration wins when present.
export const CANONICAL_PLAY_CATALOG_VERSION = 2;
export const CANONICAL_PLAY_CATALOG = [
  // Business
  {module:"business",name:"Business Starter",planId:"bizstarter",productId:"bizstarter",billingPeriod:"monthly",priceUsd:29.99,playEligible:true,trialOfferId:"trial-3d",trialDays:3,limits:{assistants:2,teamMembers:5,aiMessagesPerMonth:25000,storageGb:20}},
  {module:"business",name:"Business Starter",planId:"bizstarter",productId:"bizstarter",billingPeriod:"yearly",basePlanId:"yearly",priceUsd:299.99,playEligible:true,trialOfferId:"trial-3d",trialDays:3,limits:{assistants:2,teamMembers:5,aiMessagesPerMonth:25000,storageGb:20}},
  {module:"business",name:"Business Basic",planId:"bizbasic",productId:"bizbasic",billingPeriod:"monthly",priceUsd:59.99,playEligible:true,trialOfferId:"trial-3d",trialDays:3,limits:{assistants:4,teamMembers:10,aiMessagesPerMonth:60000,storageGb:50}},
  {module:"business",name:"Business Basic",planId:"bizbasic",productId:"bizbasic",billingPeriod:"yearly",basePlanId:"yearly",priceUsd:599.99,playEligible:true,trialOfferId:"trial-3d",trialDays:3,limits:{assistants:4,teamMembers:10,aiMessagesPerMonth:60000,storageGb:50}},
  {module:"business",name:"Business Standard",planId:"bizstandard",productId:"bizstandard",billingPeriod:"monthly",priceUsd:99.99,playEligible:true,trialOfferId:"trial-3d",trialDays:3,limits:{assistants:6,teamMembers:15,aiMessagesPerMonth:100000,storageGb:75}},
  {module:"business",name:"Business Standard",planId:"bizstandard",productId:"bizstandard",billingPeriod:"yearly",basePlanId:"yearly",priceUsd:999.99,playEligible:true,trialOfferId:"trial-3d",trialDays:3,limits:{assistants:6,teamMembers:15,aiMessagesPerMonth:100000,storageGb:75}},
  {module:"business",name:"Business Professional",planId:"bizprofessional",productId:"bizprofessional",billingPeriod:"monthly",priceUsd:179.99,playEligible:true,trialOfferId:"trial-3d",trialDays:3,limits:{assistants:10,teamMembers:25,aiMessagesPerMonth:150000,storageGb:100}},
  {module:"business",name:"Business Professional",planId:"bizprofessional",billingPeriod:"yearly",basePlanId:"yearly",priceUsd:1799.99,playEligible:false,contactSales:true,priceLimitBlocked:true,limits:{assistants:10,teamMembers:25,aiMessagesPerMonth:150000,storageGb:100}},
  {module:"business",name:"Business",planId:"bizbusiness",productId:"bizbusiness",billingPeriod:"monthly",priceUsd:299.99,playEligible:true,trialOfferId:"trial-3d",trialDays:3,limits:{assistants:30,teamMembers:100,aiMessagesPerMonth:750000,storageGb:500}},
  {module:"business",name:"Business",planId:"bizbusiness",productId:"bizbusiness",billingPeriod:"yearly",basePlanId:"yearly",priceUsd:2999.99,playEligible:false,contactSales:true,priceLimitBlocked:true,limits:{assistants:30,teamMembers:100,aiMessagesPerMonth:750000,storageGb:500}},
  {module:"business",name:"Business Enterprise",planId:"bizenterprise",productId:"bizenterprise",billingPeriod:"monthly",priceUsd:599.99,playEligible:true,trialOfferId:"trial-3d",trialDays:3,limits:{assistants:"unlimited fair use",teamMembers:"unlimited fair use",aiMessagesPerMonth:"unlimited fair use",storageTb:5}},
  {module:"business",name:"Business Enterprise",planId:"bizenterprise",productId:"bizenterprise",billingPeriod:"yearly",basePlanId:"yearly",priceUsd:5999.99,playEligible:false,contactSales:true,priceLimitBlocked:true,limits:{assistants:"unlimited fair use",teamMembers:"unlimited fair use",aiMessagesPerMonth:"unlimited fair use",storageTb:5}},
  {module:"business",name:"Business Ultimate",planId:"businessenterprise",billingPeriod:"custom",playEligible:false,contactSales:true,priceUsd:null,limits:{everything:"unlimited/custom"}},

  // Companion
  {module:"companion",name:"Companion Free",planId:"companionfree",productId:"companionfree",billingPeriod:"free",priceUsd:0,playEligible:false},
  {module:"companion",name:"Companion Basic",planId:"companionbasic",productId:"companionbasic",billingPeriod:"monthly",priceUsd:4.99,playEligible:true,yearlyPriceUsd:49.99},
  {module:"companion",name:"Companion Plus",planId:"companionplus",productId:"companionplus",billingPeriod:"monthly",priceUsd:9.99,playEligible:true,yearlyPriceUsd:99.99},
  {module:"companion",name:"Companion Premium",planId:"companionpremium",productId:"companionpremium",billingPeriod:"monthly",priceUsd:19.99,playEligible:true,yearlyPriceUsd:199.99},
  {module:"companion",name:"Companion Ultimate",planId:"companionultimate",productId:"companionultimate",billingPeriod:"monthly",priceUsd:34.99,playEligible:true,yearlyPriceUsd:349.99},

  // Learning
  {module:"learning",name:"Explorer",planId:"explorer",billingPeriod:"free",priceUsd:0,playEligible:false,limits:{learners:1,aiLessonsPerDay:3}},
  {module:"learning",name:"Scholar",planId:"learnscholar",productId:"learnscholar",billingPeriod:"monthly",priceUsd:14.99,playEligible:true,termPriceUsd:49.99,yearlyPriceUsd:129.99,limits:{learners:1,liveAiTeacherMinutesPerMonth:120}},
  {module:"learning",name:"Family Academy",planId:"learnfamily",productId:"learnfamily",billingPeriod:"monthly",priceUsd:29.99,playEligible:true,termPriceUsd:99.99,yearlyPriceUsd:259.99,limits:{learners:5,sharedLiveAiTeacherMinutesPerMonth:400}},
  {module:"learning",name:"School & Tutor",planId:"learnschool",productId:"learnschool",billingPeriod:"monthly",priceUsd:179.99,playEligible:true,termPriceUsd:599.99,yearlyPriceUsd:1799.99,yearlyPlayEligible:false,limits:{learners:60}},
  {module:"learning",name:"Learning Capability Plus",planId:"learncapabilityplus",productId:"learncapability",basePlanId:"plus-monthly",billingPeriod:"monthly",priceUsd:3.99,playEligible:true},
  {module:"learning",name:"Learning Capability Premium",planId:"learncapabilitypremium",productId:"learncapability",basePlanId:"premium-monthly",billingPeriod:"monthly",priceUsd:7.99,playEligible:true},
  {module:"learning",name:"Learning Capability Elite",planId:"learncapabilityelite",productId:"learncapability",basePlanId:"elite-monthly",billingPeriod:"monthly",priceUsd:14.99,playEligible:true},

  // TruthGuard
  {module:"truthguard",name:"TruthGuard Free",planId:"truthguardfree",billingPeriod:"free",priceUsd:0,playEligible:false,limits:{checksPerMonth:5,sourcesPerCheck:3}},
  {module:"truthguard",name:"TruthGuard Plus",planId:"tgplus",productId:"tgplus",billingPeriod:"monthly",priceUsd:4.99,playEligible:true,yearlyPriceUsd:49.70,yearlyPriceStatus:"proposal",limits:{checksPerMonth:150}},
  {module:"truthguard",name:"TruthGuard Pro",planId:"tgpro",productId:"tgpro",billingPeriod:"monthly",priceUsd:14.99,playEligible:true,yearlyPriceUsd:149.30,yearlyPriceStatus:"proposal",limits:{checksPerMonth:1000}},
  {module:"truthguard",name:"TruthGuard Team",planId:"tgteam",productId:"tgteam",billingPeriod:"monthly",priceUsd:49.99,playEligible:true,yearlyPriceUsd:497.90,yearlyPriceStatus:"proposal",limits:{checksPerMonth:"fair use unlimited"}},

  // Life Mathis
  {module:"lifemathis",name:"AI Life Mathis Free",planId:"lifemathisfree",billingPeriod:"free",priceUsd:0,playEligible:false,limits:{conversationsPerMonth:10}},
  {module:"lifemathis",name:"Life Mathis Standard",planId:"lmstandard",productId:"lmstandard",billingPeriod:"monthly",priceUsd:19.99,playEligible:true,yearlyPriceUsd:199.10,yearlyPriceStatus:"proposal",limits:{conversationsPerMonth:300}},
  {module:"lifemathis",name:"Life Mathis Ultimate",planId:"lmultimate",productId:"lmultimate",billingPeriod:"monthly",priceUsd:49.99,playEligible:true,yearlyPriceUsd:497.90,yearlyPriceStatus:"proposal",limits:{conversationsPerMonth:"fair use unlimited"}},

  // Credits
  {module:"credits",name:"100 Credits",planId:"cred100",productId:"cred100",billingPeriod:"monthly",priceUsd:11.99,playEligible:true,consumable:false,creditGrant:100},
  {module:"credits",name:"250 Credits",planId:"cred250",productId:"cred250",billingPeriod:"monthly",priceUsd:24.99,playEligible:true,consumable:false,creditGrant:250},
  {module:"credits",name:"500 Credits",planId:"cred500",productId:"cred500",billingPeriod:"monthly",priceUsd:44.99,playEligible:true,consumable:false,creditGrant:500},
  {module:"credits",name:"1000 Credits",planId:"cred1000",productId:"cred1000",billingPeriod:"monthly",priceUsd:79.99,playEligible:true,consumable:false,creditGrant:1000},

  // Business add-ons: subscription products; no trial offers.
  {module:"business-addon",name:"Multimodal Assistant",planId:"bizaoadvmultimodal",productId:"bizaoadvmultimodal",billingPeriod:"monthly",priceUsd:39.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"Unified Cross-Channel Memory",planId:"bizaoadvunifiedmemory",productId:"bizaoadvunifiedmemory",billingPeriod:"monthly",priceUsd:19.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"A/B Testing for AI Responses",planId:"bizaoadvabtesting",productId:"bizaoadvabtesting",billingPeriod:"monthly",priceUsd:24.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"Real-Time Human Handoff",planId:"bizaoadvhumanhandoff",productId:"bizaoadvhumanhandoff",billingPeriod:"monthly",priceUsd:14.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"Proactive Outreach Engine",planId:"bizaoadvproactive",productId:"bizaoadvproactive",billingPeriod:"monthly",priceUsd:29.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"Industry Pre-trained Module",planId:"bizaoadvindustrymodule",productId:"bizaoadvindustrymodule",billingPeriod:"monthly",priceUsd:19.99,playEligible:true,trialOfferId:null,quantityTiers:[{tierId:"qty1",quantity:1,priceUsd:19.99},{tierId:"qty5",quantity:5,priceUsd:99.95},{tierId:"qty10",quantity:10,priceUsd:199.90},{tierId:"qty25",quantity:25,priceUsd:499.75}]},
  {module:"business-addon",name:"Compliance Mode",planId:"bizaoadvcompliancemode",productId:"bizaoadvcompliancemode",billingPeriod:"monthly",priceUsd:34.99,playEligible:true,trialOfferId:null,quantityTiers:[{tierId:"qty1",quantity:1,priceUsd:34.99},{tierId:"qty5",quantity:5,priceUsd:174.95},{tierId:"qty10",quantity:10,priceUsd:349.90},{tierId:"qty25",quantity:25,priceUsd:874.75}]},
  {module:"business-addon",name:"Custom Cloned Brand Voice",planId:"bizaoadvcustomvoice",productId:"bizaoadvcustomvoice",billingPeriod:"monthly",priceUsd:49.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"Explainable AI Audit Trail",planId:"bizaoadvauditexplain",productId:"bizaoadvauditexplain",billingPeriod:"monthly",priceUsd:14.99,playEligible:true,trialOfferId:null},

  {module:"business-addon",name:"+10K AI Messages",planId:"bizaomsgs10k",productId:"bizaomsgs10k",billingPeriod:"monthly",priceUsd:13.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"+50K AI Messages",planId:"bizaomsgs50k",productId:"bizaomsgs50k",billingPeriod:"monthly",priceUsd:54.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"+100K AI Messages",planId:"bizaomsgs100k",productId:"bizaomsgs100k",billingPeriod:"monthly",priceUsd:94.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"Unlimited AI Messages",planId:"bizaomsgsunlimited",productId:"bizaomsgsunlimited",billingPeriod:"monthly",priceUsd:269.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"10 GB Storage",planId:"bizaostorage10gb",productId:"bizaostorage10gb",billingPeriod:"monthly",priceUsd:6.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"100 GB Storage",planId:"bizaostorage100gb",productId:"bizaostorage100gb",billingPeriod:"monthly",priceUsd:19.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"1 TB Storage",planId:"bizaostorage1tb",productId:"bizaostorage1tb",billingPeriod:"monthly",priceUsd:69.99,playEligible:true,trialOfferId:null},
  {module:"business-addon",name:"Extra Team Member",planId:"bizaoteamuser",productId:"bizaoteamuser",billingPeriod:"monthly",priceUsd:9.99,playEligible:true,trialOfferId:null,quantityTiers:[{tierId:"qty1",quantity:1,priceUsd:9.99},{tierId:"qty5",quantity:5,priceUsd:49.95},{tierId:"qty10",quantity:10,priceUsd:99.90},{tierId:"qty25",quantity:25,priceUsd:249.75}]},
  {module:"business-addon",name:"Extra Assistant",planId:"bizaoextraassistant",productId:"bizaoextraassistant",billingPeriod:"monthly",priceUsd:19.99,playEligible:true,trialOfferId:null,quantityTiers:[{tierId:"qty1",quantity:1,priceUsd:19.99},{tierId:"qty5",quantity:5,priceUsd:99.95},{tierId:"qty10",quantity:10,priceUsd:199.90},{tierId:"qty25",quantity:25,priceUsd:499.75}]}
];

export const CANONICAL_ADDON_IDS = [
  ["bizaointwhatsapp","WhatsApp Integration",19.99],["bizaointmessenger","Messenger Integration",13.99],
  ["bizaointinstagram","Instagram Integration",13.99],["bizaointtelegram","Telegram Integration",6.99],
  ["bizaointslack","Slack Integration",13.99],["bizaointdiscord","Discord Integration",13.99],
  ["bizaointshopify","Shopify Integration",26.99],["bizaointwoocommerce","WooCommerce Integration",19.99],
  ["bizaointwordpress","WordPress Integration",13.99],["bizaointzapier","Zapier Integration",26.99],
  ["bizaointcrm","CRM Integration",34.99],["bizaointapi","API Integration",34.99],
  ["bizaoaivoice","AI Voice",34.99],["bizaoaiphone","AI Phone",69.99],["bizaoaibooking","AI Booking",19.99],
  ["bizaoaisales","AI Sales",39.99],["bizaoaimarketing","AI Marketing",34.99],["bizaoaiemail","AI Email",19.99],
  ["bizaoaiworkflow","AI Workflow",54.99],["bizaoaidocs","AI Docs",39.99],
  ["bizaosupportpriority","Priority Support",34.99],["bizaosupportpremium","Premium Support",69.99],
  ["bizaosupportmanager","Dedicated Manager",139.99],["bizaosupwhitelabel","White Label",199],
  ["bizaosupsetup","AI Setup",349],["bizaosupmigration","Migration",199],["bizaosupdomain","Custom Domain",69],
  ["bizaosupcustommodel","Custom AI Model","CONTACT"]
].map(([id,name,price])=>({module:"business-addon",name,planId:id,productId:id,billingPeriod:price==="CONTACT"?"custom":"monthly",priceUsd:price==="CONTACT"?null:Number(price),playEligible:price!=="CONTACT",contactSales:price==="CONTACT",trialOfferId:null,oneTime:["bizaosupsetup","bizaosupmigration","bizaosupdomain"].includes(id)}));

export function canonicalPlayProducts() {
  return [...CANONICAL_PLAY_CATALOG, ...CANONICAL_ADDON_IDS]
    .filter(x => x.playEligible && x.productId)
    .map(x => ({
      planId:x.planId, product:x.productId, productId:x.productId,
      basePlanId:x.basePlanId || (x.billingPeriod === "yearly" ? "yearly" : "monthly"),
      productType:x.oneTime ? "inapp" : "subs",
      enabled:true, consumable:false, creditGrant:Number(x.creditGrant || 0),
      trialOfferId:x.trialOfferId || null
    }));
}

export function canonicalAdminCatalog() {
  return {
    version: CANONICAL_PLAY_CATALOG_VERSION,
    ids: [...CANONICAL_PLAY_CATALOG, ...CANONICAL_ADDON_IDS],
    trial: {offerId:"trial-3d",days:3,appliesTo:"eligible auto-renewing base plans only"},
    accountHold: {gracePeriodDays:7,automatic:true},
    quantityTiers: {tierIds:["qty1","qty5","qty10","qty25"],overLargestTier:"contact_sales"},
    visibility:"admin_only"
  };
}
