// BQ Sqwad AI Worker-side fallback commercial catalog.
// Keep synchronized with the canonical commercial catalog; Firestore admin configuration overrides it.
// Commercial IDs intentionally contain no underscores or spaces.

export const PLAN_CATALOG_VERSION = 3;

const plan = (module, id, name, options = {}) => ({
  module, id, name,
  productId: options.productId ?? id,
  productType: options.productType ?? "subs",
  recurring: options.recurring ?? true,
  billingPeriods: options.billingPeriods ?? ["monthly"],
  basePlans: options.basePlans ?? options.billingPeriods?.map(p => p === "monthly" ? "monthly" : p === "yearly" ? "yearly" : p) ?? ["monthly"],
  offerId: options.offerId ?? null,
  trialDays: options.trialDays ?? 0,
  trialEligible: options.trialEligible ?? false,
  price: options.price ?? {},
  playEligiblePeriods: options.playEligiblePeriods ?? null,
  proposalPeriods: options.proposalPeriods ?? [],
  priceStatus: options.priceStatus ?? "approved",
  playEligible: options.playEligible ?? false,
  paymentChannel: options.paymentChannel ?? (options.playEligible ? "play" : (options.contactSales ? "contact_sales" : "website")),
  popular: options.popular ?? false,
  contactSales: options.contactSales ?? false,
  active: options.active ?? true,
  limits: options.limits ?? {},
  features: options.features ?? [],
  entitlements: options.entitlements ?? [],
  countryAvailability: options.countryAvailability ?? { mode: "all", countries: [] },
  gracePeriodDays: options.gracePeriodDays ?? 7,
  accountHold: options.accountHold ?? "google-play-automatic",
  resubscribe: options.resubscribe ?? "allow",
  planChange: options.planChange ?? "charge-next-billing-date",
  localizedPlayPrice: options.localizedPlayPrice ?? "play-authoritative",
  currency: options.currency ?? "USD",
  tierId: options.tierId ?? null,
  quantityTiers: options.quantityTiers ?? [],
  addOnQuantityRules: options.addOnQuantityRules ?? null,
  includedAddOns: options.includedAddOns ?? [],
  configurationNotes: options.configurationNotes ?? []
});

const businessPlans = [
  plan("business","bizstarter","Business Starter",{productId:"bizstarter",billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,paymentChannel:"play",playEligible:true,price:{monthly:29.99,yearly:299.99},limits:{assistants:2,teamMembers:5,aiMessagesPerMonth:25000,storageGb:20},features:["AI Chat","File Upload","Knowledge Base","Website Chat Widget","Email Support","Analytics Dashboard","Basic API","Organised Knowledge Vault","FAQ Knowledge Base","AI Business Plan Builder","Resolution Transparency Dashboard","Usage Forecasting","One-Click Multi-Channel Deploy","Custom AI Training"]}),
  plan("business","bizbasic","Business Basic",{productId:"bizbasic",billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,paymentChannel:"play",playEligible:true,price:{monthly:59.99,yearly:599.99},limits:{assistants:4,teamMembers:10,aiMessagesPerMonth:60000,storageGb:50},features:["Everything in Business Starter","Knowledge Vault","FAQ Knowledge Base","AI Business Plan Builder","Email Support"]}),
  plan("business","bizstandard","Business Standard",{productId:"bizstandard",billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,paymentChannel:"play",playEligible:true,price:{monthly:99.99,yearly:999.99},limits:{assistants:6,teamMembers:15,aiMessagesPerMonth:100000,storageGb:75},features:["Everything in Business Basic","Instagram","Telegram","Business Email","CRM","Lead Capture","Appointment Booking","Advanced Analytics","REST API"]}),
  plan("business","bizprofessional","Business Professional",{productId:"bizprofessional",billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,paymentChannel:"website",popular:true,playEligible:false,playEligiblePeriods:["monthly"],price:{monthly:179.99,yearly:1799.99},limits:{assistants:10,teamMembers:25,aiMessagesPerMonth:150000,storageGb:100},features:["Everything in Business Standard","Live Chat","WhatsApp Business","YouTube","LinkedIn","Google Business Profile","Discord","SMS","Business Email Integration","CRM Integration","Shared Inbox","Team Roles/Permissions","AI Workflow Automation","Custom Branding","Priority Support","REST API","Webhooks"],configurationNotes:["Yearly Business billing is website-managed and may be configured as Contact Sales."]}),
  plan("business","bizbusiness","Business",{productId:"bizbusiness",billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,paymentChannel:"play",playEligible:true,playEligiblePeriods:["monthly"],price:{monthly:299.99,yearly:2999.99},limits:{assistants:30,teamMembers:100,aiMessagesPerMonth:750000,storageGb:500},features:["Everything in Business Professional","Voice AI","OCR Document Reading","PDF Analysis","Image Understanding","Calendar Integration","Google Workspace","Microsoft 365","Slack","Advanced Analytics","Multi-language Support","AI Memory","SSO Login","Audit Logs","99.9% SLA"],configurationNotes:["Yearly price is Contact Sales until the higher applicable Play price limit is available."]}),
  plan("business","bizenterprise","Business Enterprise",{productId:"bizenterprise",billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,paymentChannel:"play",playEligible:true,playEligiblePeriods:["monthly"],price:{monthly:599.99,yearly:5999.99},limits:{assistants:"unlimited under fair use",teamMembers:"unlimited under fair use",aiMessagesPerMonth:"unlimited under fair use",storageTb:5},features:["Everything in Business","Omnichannel AI","Call Center AI","Phone AI","Airline Booking","Banking","Insurance","Healthcare","Hotel","ERP","CRM","API Integration","AI Voice Calls","AI Video Calls","Live Translation","TikTok","X","AI Agent Builder","Multi-Department AI","White Label","Dedicated Account Manager","24/7 Premium Support","Priority Infrastructure"],configurationNotes:["Yearly price is Contact Sales until the higher applicable Play price limit is available."]}),
  plan("business","businessenterprise","Business Ultimate",{productId:null,productType:"custom",recurring:false,billingPeriods:["custom"],basePlans:[],price:{custom:"CONTACT"},playEligible:false,contactSales:true,limits:{everything:"unlimited"},features:["Unlimited Everything","Custom AI Model Deployment","Private Cloud","On-Premise","Zero Data Retention Option","Dedicated GPU Infrastructure","Enterprise Security","SOC 2 Ready","GDPR Support","ISO 27001 Ready","HIPAA Ready Architecture","Custom Integrations","Salesforce","SAP","Oracle","Microsoft Dynamics","ServiceNow","Airline Reservation Systems","Banking Core Systems Integration","Dedicated AI Engineers","Dedicated Customer Success Manager","99.99% SLA","24/7 Phone Support","Global Deployment","Custom Contract"]})
];

const companionPlans = [
  plan("companion","companionfree","Companion Free",{productId:null,productType:"free",recurring:false,billingPeriods:["free"],price:{free:0},playEligible:false,features:["Caring text conversation","Personality choice","Daily check-in prompts"]}),
  plan("companion","companionbasic","Companion Basic",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:4.99,yearly:49.99},features:["Memory you control","Full history","Longer conversations"]}),
  plan("companion","companionplus","Companion Plus",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:9.99,yearly:99.99},features:["Voice conversation","Extra voice choices","Avatar"]}),
  plan("companion","companionpremium","Companion Premium",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:19.99,yearly:199.99},features:["Real-time voice","Animated avatar","Interrupt/talk-over","Camera interactions"]}),
  plan("companion","companionultimate","Companion Ultimate",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:34.99,yearly:349.99},features:["Advanced long-term memory","Routines/reminders","Priority response speed","Camera interactions"]})
];

const learningPlans = [
  plan("learning","explorer","Explorer",{productId:null,productType:"free",recurring:false,billingPeriods:["free"],price:{free:0},playEligible:false,limits:{learners:1,aiLessonsPerDay:3},features:["1 learner","3 AI lessons/day","Any curriculum/subject","Practice questions","Instant marking","Progress streaks","Badges","Child-safe mode"]}),
  plan("learning","learnscholar","Scholar",{productId:"learnscholar",productType:"subs",recurring:true,billingPeriods:["monthly","term","yearly"],basePlans:["monthly","term","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:14.99,term:49.99,yearly:129.99},limits:{learners:1,liveAiTeacherMinutesPerMonth:120},features:["1 learner","Unlimited lessons","120 live AI teacher minutes/month","Exam mode","Timed past-paper practice","Homework photo help","Weekly parent report","Downloadable worksheets/revision notes"]}),
  plan("learning","learnfamily","Family Academy",{productId:"learnfamily",productType:"subs",recurring:true,billingPeriods:["monthly","term","yearly"],basePlans:["monthly","term","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:29.99,term:99.99,yearly:259.99},limits:{learners:5,sharedLiveAiTeacherMinutesPerMonth:400},features:["Up to 5 learners","400 shared live AI teacher minutes/month","Parent dashboard","Sibling leaderboards/challenges","Certificates","Priority support"]}),
  plan("learning","learnschool","School & Tutor",{productId:"learnschool",productType:"subs",recurring:true,billingPeriods:["monthly","term","yearly"],basePlans:["monthly","term","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:179.99,term:599.99,yearly:1799.99},limits:{learners:60},features:["Up to 60 learners","Learner seats","Teacher dashboards","Classes","Assignments","Bulk onboarding","Curriculum mapping","Coverage reports","Certificates with public verification","Safeguarding log","Data-protection reports","Dedicated onboarding"],configurationNotes:["Yearly price is Contact Sales until the higher applicable Play price limit is available."]})
];

const capabilityTiers = [
  plan("learning-capability","learncapabilityplus","Learning Capability Plus",{productId:"learncapability",billingPeriods:["monthly","term","yearly"],basePlans:["plus-monthly","plus-term","plus-yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,tierId:"plus",price:{monthly:3.99,term:12.99,yearly:34.99},features:["Premium voice tutor","Advanced AI models"]}),
  plan("learning-capability","learncapabilitypremium","Learning Capability Premium",{productId:"learncapability",billingPeriods:["monthly","term","yearly"],basePlans:["premium-monthly","premium-term","premium-yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,tierId:"premium",price:{monthly:7.99,term:24.99,yearly:69.99},features:["Voice tutor","Advanced AI models","AI Live Camera tutor","Real-time demonstrations"]}),
  plan("learning-capability","learncapabilityelite","Learning Capability Elite",{productId:"learncapability",billingPeriods:["monthly","term","yearly"],basePlans:["elite-monthly","elite-term","elite-yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,tierId:"elite",price:{monthly:14.99,term:44.99,yearly:129.99},features:["Voice tutor","Advanced AI models","AI Live Camera","Real-time demonstrations","Deep research","AI Studio"]})
];

const truthGuardPlans = [
  plan("truthguard","truthguardfree","TruthGuard Free",{productId:null,productType:"free",recurring:false,billingPeriods:["free"],price:{free:0},playEligible:false,limits:{checksPerMonth:5},features:["5 checks/month","Verdict","Confidence","Plain-English explanation","Up to 3 cited sources/check"]}),
  plan("truthguard","tgplus","TruthGuard Plus",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:4.99,yearly:49.70},priceStatus:"approved",proposalPeriods:[],limits:{checksPerMonth:150},features:["150 checks/month","Live web evidence","Source reliability notes","Manipulation/bias signals","History"],configurationNotes:["Yearly price is approved at $49.70 USD\/year (monthly price × 12 × 0.83)."]}),
  plan("truthguard","tgpro","TruthGuard Pro",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:14.99,yearly:149.30},priceStatus:"approved",proposalPeriods:[],limits:{checksPerMonth:1000},features:["1,000 checks/month","Deep evidence sweep","More sources","Claim breakdown for long articles/posts","Export-ready summaries","Priority processing"],configurationNotes:["Yearly price is approved at $149.30 USD/year (monthly price × 12 × 0.83)."]}),
  plan("truthguard","tgteam","TruthGuard Team",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:49.99,yearly:497.90},priceStatus:"approved",proposalPeriods:[],limits:{checksPerMonth:"fair-use unlimited"},features:["Fair-use unlimited","Pro features","Shared organization history","Priority support"],configurationNotes:["Yearly price is approved at $497.90 USD\/year (monthly price × 12 × 0.83)."]})
];

const frontierPlans = [
  plan("frontier","frontierexplorer","Frontier Explorer",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:14.99,yearly:149.99},features:["Advanced AI experimentation","Structured research","Emerging capability workflows","Prototype workspace","Research history"]}),
  plan("frontier","frontierpro","Frontier Pro",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:49.99,yearly:499.99},features:["Everything in Frontier Explorer","Deep research","Knowledge graphs","Semantic file search","Project workspaces","AI-generated reports","Advanced model experimentation","Multimodal reasoning"]}),
  plan("frontier","frontierenterprise","Frontier Enterprise",{billingPeriods:["monthly"],basePlans:["monthly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:199.99},features:["Everything in Frontier Pro","AI coding workspace","Evaluation laboratory","Provider/model routing experiments","Priority processing","Advanced project controls","Enterprise research workflows"],configurationNotes:["Starts at $199.99/month; custom enterprise arrangements can be handled separately."]})
];

const lifeMathisPlans = [
  plan("life-mathis","lifemathisfree","AI Life Mathis Free",{productId:null,productType:"free",recurring:false,billingPeriods:["free"],price:{free:0},playEligible:false,limits:{conversationsPerMonth:10},features:["10 conversations/month","Structured guidance","Grounded Life Mathis knowledge"]}),
  plan("life-mathis","lmstandard","Life Mathis Standard",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:19.99,yearly:199.10},priceStatus:"approved",limits:{conversationsPerMonth:300},features:["300 conversations/month","Structured guidance","Deep Life Analysis","Voice notes","Spoken replies","Saved insights","Full history","Multi-perspective answers"],configurationNotes:["Monthly base plan ID: monthly. Yearly base plan ID: yearly."]}),
  plan("life-mathis","lmultimate","Life Mathis Ultimate",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:49.99,yearly:497.90},priceStatus:"approved",limits:{conversationsPerMonth:"fair-use unlimited"},features:["Fair-use unlimited","Unlimited Deep Life Analysis","Curated wisdom library","Super Wisdom","Life coaching plans","Priority processing/support"],configurationNotes:["Monthly base plan ID: monthly. Yearly base plan ID: yearly."]})
];

const creditPlans = [
  plan("credits","credit100","100 Credits",{productId:"credit100",billingPeriods:["weekly","monthly"],basePlans:["weekly","monthly"],playEligible:true,price:{monthly:11.99},productType:"subs",recurring:true,features:["100 credits"],entitlements:["credit:100"],configurationNotes:["Google Play subscription ID is credit100. Base plans are weekly and monthly. The existing monthly price is preserved at $11.99; weekly prices must be configured before those base plans are published."]}),
  plan("credits","credit250","250 Credits",{productId:"credit250",billingPeriods:["weekly","monthly"],basePlans:["weekly","monthly"],playEligible:true,price:{monthly:24.99},productType:"subs",recurring:true,features:["250 credits"],entitlements:["credit:250"],configurationNotes:["Google Play subscription ID is credit250. Base plans are weekly and monthly. The existing monthly price is preserved at $24.99; weekly prices must be configured before those base plans are published."]}),
  plan("credits","credit500","500 Credits",{productId:"credit500",billingPeriods:["weekly","monthly"],basePlans:["weekly","monthly"],playEligible:true,price:{monthly:44.99},productType:"subs",recurring:true,features:["500 credits"],entitlements:["credit:500"],configurationNotes:["Google Play subscription ID is credit500. Base plans are weekly and monthly. The existing monthly price is preserved at $44.99; weekly prices must be configured before those base plans are published."]}),
  plan("credits","credit1000","1,000 Credits",{productId:"credit1000",billingPeriods:["weekly","monthly"],basePlans:["weekly","monthly"],playEligible:true,price:{monthly:79.99},productType:"subs",recurring:true,features:["1,000 credits"],entitlements:["credit:1000"],configurationNotes:["Google Play subscription ID is credit1000. Base plans are weekly and monthly. The existing monthly price is preserved at $79.99; weekly prices must be configured before those base plans are published."]})
];

const tierProducts = [
  {id:"bizaoadvindustrymodule",name:"Industry Pre-trained Module",price:19.99,unit:"module/month",quantityTiers:[["qty1",1,19.99],["qty5",5,99.95],["qty10",10,199.90],["qty25",25,499.75]]},
  {id:"bizaoadvcompliancemode",name:"Compliance Mode",price:34.99,unit:"mode/month",quantityTiers:[["qty1",1,34.99],["qty5",5,174.95],["qty10",10,349.90],["qty25",25,874.75]]},
  {id:"bizaoteamuser",name:"Extra Team Member",price:9.99,unit:"user/month",quantityTiers:[["qty1",1,9.99],["qty5",5,49.95],["qty10",10,99.90],["qty25",25,249.75]]},
  {id:"bizaoextraassistant",name:"Extra Assistant",price:19.99,unit:"assistant/month",quantityTiers:[["qty1",1,19.99],["qty5",5,99.95],["qty10",10,199.90],["qty25",25,499.75]]}
];

const simpleAddOns = [
  ["bizaoadvmultimodal","Multimodal Assistant",39.99,"monthly",["Text","Voice","Vision","Shared memory"]],
  ["bizaoadvunifiedmemory","Unified Cross-Channel Memory",19.99,"monthly",[]],
  ["bizaoadvabtesting","A/B Testing for AI Responses",24.99,"monthly",[]],
  ["bizaoadvhumanhandoff","Real-Time Human Handoff",14.99,"monthly",[]],
  ["bizaoadvproactive","Proactive Outreach Engine",29.99,"monthly",[]],
  ["bizaoadvcustomvoice","Custom Cloned Brand Voice",49.99,"monthly",[]],
  ["bizaoadvauditexplain","Explainable AI Audit Trail",14.99,"monthly",[]],
  ["bizaomsgs10k","+10,000 AI messages",13.99,"monthly",[]],
  ["bizaomsgs50k","+50,000 AI messages",54.99,"monthly",[]],
  ["bizaomsgs100k","+100,000 AI messages",94.99,"monthly",[]],
  ["bizaomsgsunlimited","Unlimited AI messages / fair use",269.99,"monthly",[]],
  ["bizaostorage10gb","+10 GB",6.99,"monthly",[]],
  ["bizaostorage100gb","+100 GB",19.99,"monthly",[]],
  ["bizaostorage1tb","+1 TB",69.99,"monthly",[]],
  ["bizaointwhatsapp","WhatsApp Integration",19.99,"monthly",[]],
  ["bizaointmessenger","Messenger Integration",13.99,"monthly",[]],
  ["bizaointinstagram","Instagram Integration",13.99,"monthly",[]],
  ["bizaointtelegram","Telegram Integration",6.99,"monthly",[]],
  ["bizaointslack","Slack Integration",13.99,"monthly",[]],
  ["bizaointdiscord","Discord Integration",13.99,"monthly",[]],
  ["bizaointshopify","Shopify Integration",26.99,"monthly",[]],
  ["bizaointwoocommerce","WooCommerce Integration",19.99,"monthly",[]],
  ["bizaointwordpress","WordPress Integration",13.99,"monthly",[]],
  ["bizaointzapier","Zapier Integration",26.99,"monthly",[]],
  ["bizaointcrm","CRM Integration",34.99,"monthly",[]],
  ["bizaointapi","API Integration",34.99,"monthly",[]],
  ["bizaoaivoice","AI Voice",34.99,"monthly",[]],
  ["bizaoaiphone","AI Phone",69.99,"monthly",[]],
  ["bizaoaibooking","AI Booking",19.99,"monthly",[]],
  ["bizaoaisales","AI Sales",39.99,"monthly",[]],
  ["bizaoaimarketing","AI Marketing",34.99,"monthly",[]],
  ["bizaoaiemail","AI Email",19.99,"monthly",[]],
  ["bizaoaiworkflow","AI Workflow",54.99,"monthly",[]],
  ["bizaoaidocs","AI Documents",39.99,"monthly",[]],
  ["bizaosupportpriority","Priority Support",34.99,"monthly",[]],
  ["bizaosupportpremium","Premium Support",69.99,"monthly",[]],
  ["bizaosupportmanager","Dedicated Manager",139.99,"monthly",[]],
  ["bizaosupsetup","AI Setup",349,"one-time",[]],
  ["bizaosupmigration","Migration",199,"one-time",[]],
  ["bizaosupwhitelabel","White Label",199,"monthly",[]],
  ["bizaosupdomain","Custom Domain",69,"one-time",[]],
  ["bizaosupcustommodel","Custom AI Model","CONTACT","custom",[]]
];

const examAddOns = [
  ["learnexamwaecneco","WAEC/NECO",6.99,19.99],
  ["learnexamgcse","GCSE",6.99,19.99],
  ["learnexamsatact","SAT/ACT",7.99,22.99],
  ["learnexamigcsalevel","IGCSE/A Level",7.99,22.99]
];

const tieredAddOns = tierProducts.map(x => ({
  module:"business-addon", id:x.id, name:x.name, productId:x.id, productType:"subs", recurring:true,
  billingPeriods:["monthly"], basePlans:["monthly"], offerId:null, trialDays:0, trialEligible:false,
  price:{monthly:x.price}, priceStatus:"approved", playEligible:false, contactSales:false, active:true,
  limits:{}, features:[], entitlements:[], countryAvailability:{mode:"all",countries:[]},
  gracePeriodDays:7, accountHold:"google-play-automatic", resubscribe:"allow", planChange:"charge-next-billing-date",
  localizedPlayPrice:"play-authoritative",currency:"USD",tierId:null,
  quantityTiers:x.quantityTiers.map(([tierId,quantity,price])=>({tierId,quantity,price,billingPeriod:"monthly",unitType:x.unit,maximumQuantity:quantity,available:true,active:true,basePlanId:"monthly",includedEntitlement:x.id+":"+quantity})),
  addOnQuantityRules:{min:1,max:25,allowedTierIds:["qty1","qty5","qty10","qty25"]},includedAddOns:[],
  configurationNotes:["Add-on; no 3-day trial.","Quantity and entitlement must be validated server-side."]
}));

const addOns = [
  ...tieredAddOns,
  ...simpleAddOns.map(([id,name,price,billingPeriod,features]) => ({
    module:"business-addon",id,name,productId:id,
    productType:billingPeriod === "one-time" ? "inapp" : price === "CONTACT" ? "custom" : "subs",
    recurring:billingPeriod === "monthly",billingPeriods:[billingPeriod],
    basePlans:billingPeriod === "monthly" ? ["monthly"] : [],
    offerId:null,trialDays:0,trialEligible:false,
    price:{[billingPeriod]:price},priceStatus:"approved",playEligible:false,
    contactSales:price === "CONTACT",active:true,limits:{},features,entitlements:[],
    countryAvailability:{mode:"all",countries:[]},gracePeriodDays:7,accountHold:"google-play-automatic",
    resubscribe:"allow",planChange:"charge-next-billing-date",localizedPlayPrice:"play-authoritative",currency:"USD",
    tierId:null,quantityTiers:[],addOnQuantityRules:null,includedAddOns:[],
    configurationNotes:[billingPeriod === "one-time" ? "One-time service; do not model as recurring." : "Add-on; no 3-day trial."]
  })),
  ...examAddOns.map(([id,name,monthly,term]) => ({
    module:"learning-exam-addon",id,name,productId:id,productType:"subs",recurring:true,
    billingPeriods:["monthly","term"],basePlans:["monthly","term"],offerId:null,trialDays:0,trialEligible:false,
    price:{monthly,term},priceStatus:"approved",playEligible:false,contactSales:false,active:true,
    limits:{},features:[],entitlements:[],countryAvailability:{mode:"all",countries:[]},gracePeriodDays:7,
    accountHold:"google-play-automatic",resubscribe:"allow",planChange:"charge-next-billing-date",
    localizedPlayPrice:"play-authoritative",currency:"USD",tierId:null,quantityTiers:[],addOnQuantityRules:null,includedAddOns:[],
    configurationNotes:["No 3-day trial on exam add-ons."]
  }))
];

export const COMMERCIAL_CATALOG = {
  version: PLAN_CATALOG_VERSION,
  currency: "USD",
  contactSalesEmail: "hello@bqsqwadai.com",
  globalRules: {
    trial: {offerId:"trial-3d",durationDays:3,appliesTo:"eligible auto-renewing subscription base plans only"},
    addOnTrial: false,
    gracePeriodDays: 7,
    accountHold: "google-play-automatic",
    resubscribe: "allow",
    planChange: "charge-next-billing-date",
    localizedPlayPrice: "Google Play authoritative at checkout",
    countryAvailability: {mode:"all",countries:[]},
    internalIdsHiddenFromUsers: true,
    noUnderscoresInCommercialIds: true,
    secretHandling: "server-only"
  },
  sections: {
    business: businessPlans,
    businessAddOns: addOns.filter(x=>x.module === "business-addon"),
    companion: companionPlans,
    learning: learningPlans,
    learningCapability: capabilityTiers,
    learningExamAddOns: addOns.filter(x=>x.module === "learning-exam-addon"),
    truthGuard: truthGuardPlans,
    lifeMathis: lifeMathisPlans,
    frontier: frontierPlans,
    credits: creditPlans
  }
};

// Legacy compact shape retained for existing callers/tests; values are derived from the canonical objects above.
export const PLAN_CATALOG = Object.fromEntries(Object.entries(COMMERCIAL_CATALOG.sections).map(([section, rows]) => [
  section === "businessAddOns" ? "addOns" : section,
  rows.map(p => [p.id, p.name, p.price?.monthly ?? p.price?.free ?? null, p.price?.yearly ?? null, p.features || [], p.limits || {}])
]));
PLAN_CATALOG.trialOfferId = "trial-3d";
PLAN_CATALOG.addOnTrial = false;
PLAN_CATALOG.basePlanIds = ["monthly","yearly"];
PLAN_CATALOG.contactSalesEmail = COMMERCIAL_CATALOG.contactSalesEmail;
PLAN_CATALOG.notes = {
  yearlyBusinessProfessional:"Business Professional yearly is Contact Sales until the higher applicable Play price limit is available.",
  yearlyBusiness:"Business yearly is Contact Sales until the higher applicable Play price limit is available.",
  yearlyBusinessEnterprise:"Business Enterprise yearly is Contact Sales until the higher applicable Play price limit is available."
};

export function canonicalPlayProductId(id) {
  return String(id || "").trim().replaceAll("_", "");
}

export function validateCommercialCatalog(catalog = COMMERCIAL_CATALOG) {
  const ids = [];
  const products = [];
  for (const rows of Object.values(catalog.sections || {})) {
    for (const item of rows || []) {
      const id = String(item.id || "");
      if (!id || !/^[a-z0-9-]+$/.test(id)) throw new Error("invalid_commercial_id:" + id);
      if (ids.includes(id)) throw new Error("duplicate_commercial_id:" + id);
      ids.push(id);
      if (item.productId) {
        if (!/^[a-z0-9-]+$/.test(String(item.productId))) throw new Error("invalid_product_id:" + item.productId);
        if (String(item.productId).includes("_")) throw new Error("underscore_product_id:" + item.productId);
        products.push(String(item.productId));
      }
      if (item.productType === "subs" && item.playEligible && (item.billingPeriods || []).includes("yearly")) {
        if (!(item.basePlans || []).some(base => base === "yearly" || String(base).endsWith("-yearly"))) {
          throw new Error("yearly_base_plan_required:" + id);
        }
      }
      for (const tier of item.quantityTiers || []) {
        if (!/^[a-z0-9-]+$/.test(String(tier.tierId || ""))) throw new Error("invalid_tier_id:" + tier.tierId);
      }
    }
  }
  return {ok:true,version:catalog.version,planCount:ids.length,productCount:products.length};
}

validateCommercialCatalog();


/**
 * Merge saved admin overrides over the complete canonical catalog. A plan is
 * hidden by setting active:false or visible:false; simply having a partial
 * Firestore document must not accidentally remove every other catalog plan.
 */
export function mergeCommercialCatalog(overrideCatalog = null) {
  const override = overrideCatalog && typeof overrideCatalog === "object" && !Array.isArray(overrideCatalog)
    ? overrideCatalog
    : {};
  const overrideSections = override.sections && typeof override.sections === "object"
    ? override.sections
    : {};
  const overrideById = new Map();
  for (const rows of Object.values(overrideSections)) {
    if (!Array.isArray(rows)) continue;
    for (const item of rows) {
      if (item && typeof item === "object" && item.id) {
        overrideById.set(String(item.id), item);
      }
    }
  }
  const baseIds = new Set(Object.values(COMMERCIAL_CATALOG.sections || {}).flat().map(item => String(item.id)));
  const sections = {};
  for (const [section, rows] of Object.entries(COMMERCIAL_CATALOG.sections || {})) {
    sections[section] = rows.map(item => ({
      ...item,
      ...(overrideById.get(String(item.id)) || {})
    }));
  }
  for (const [section, rows] of Object.entries(overrideSections)) {
    if (!Array.isArray(rows)) continue;
    const custom = rows.filter(item => item && typeof item === "object" && item.id && !baseIds.has(String(item.id)));
    if (custom.length) sections[section] = [...(sections[section] || []), ...custom];
  }
  return {
    ...COMMERCIAL_CATALOG,
    ...override,
    version: Number(override.version || COMMERCIAL_CATALOG.version),
    sections
  };
}

/**
 * Canonical Google Play mappings used only when an admin has not supplied a
 * mapping for the same plan/base-plan pair. Product IDs and base-plan IDs are
 * copied from the canonical plan catalog; no IDs are synthesized.
 */
export function canonicalPlayMappings(catalog = COMMERCIAL_CATALOG) {
  const resolvedCatalog = catalog === COMMERCIAL_CATALOG ? COMMERCIAL_CATALOG : mergeCommercialCatalog(catalog);
  const rows = Object.values(resolvedCatalog?.sections || {}).flat();
  return rows.filter(plan =>
    plan && typeof plan === "object" &&
    plan.active !== false &&
    (plan.paymentChannel === "play" || (!plan.paymentChannel && plan.playEligible === true)) &&
    plan.playEligible === true &&
    plan.productId &&
    (plan.productType === "subs" || plan.productType === "inapp")
  ).flatMap(plan => {
    const basePlanIds = plan.productType === "subs"
      ? (Array.isArray(plan.basePlans) && plan.basePlans.length ? plan.basePlans.map(String).filter(Boolean) : (Array.isArray(plan.billingPeriods) ? plan.billingPeriods.map(String).filter(Boolean) : []))
      : [null];
    const billingPeriods = Array.isArray(plan.billingPeriods) ? plan.billingPeriods.map(String) : [];
    const eligiblePeriods = Array.isArray(plan.playEligiblePeriods) && plan.playEligiblePeriods.length
      ? new Set(plan.playEligiblePeriods.map(String))
      : null;
    const pairs = basePlanIds.map((basePlanId, index) => ({
      basePlanId,
      billingPeriod: plan.productType === "subs" ? (billingPeriods[index] || basePlanId) : "one_time"
    })).filter(pair => !eligiblePeriods || eligiblePeriods.has(pair.billingPeriod) || eligiblePeriods.has(String(pair.basePlanId || "")));
    const entitlements = Array.isArray(plan.entitlements) ? plan.entitlements : [];
    const creditGrant = entitlements.reduce((total, value) => {
      const match = String(value).match(/^credit:(\\d+)$/);
      return total + (match ? Number(match[1]) : 0);
    }, 0);
    return pairs
      .filter(pair => plan.productType === "inapp" || (
        plan.price?.[pair.billingPeriod] != null &&
        Number.isFinite(Number(plan.price[pair.billingPeriod]))
      ))
      .map(pair => ({
        planId: String(plan.id),
        productId: String(plan.productId),
        basePlanId: pair.basePlanId,
        productType: String(plan.productType),
        enabled: true,
        consumable: plan.productType === "inapp" && creditGrant > 0,
        billingPeriod: pair.billingPeriod,
        priceUsd: plan.price?.[pair.billingPeriod] == null ? null : Number(plan.price[pair.billingPeriod]),
        trialOfferId: plan.trialEligible ? (plan.offerId || null) : null,
        trialDays: plan.trialEligible ? Number(plan.trialDays || 0) : 0,
        creditGrant,
        organizationWalletTopup: false
      }));
  });
}
