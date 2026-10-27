// BQ Sqwad AI Worker-side fallback commercial catalog.
// Keep synchronized with the canonical commercial catalog; Firestore admin configuration overrides it.
// Commercial IDs intentionally contain no underscores or spaces.

export const PLAN_CATALOG_VERSION = 4;

const plan = (module, id, name, options = {}) => ({
  module, id, name,
  productId: options.productId ?? id,
  productType: options.productType ?? "subs",
  recurring: options.recurring ?? true,
  billingPeriods: options.billingPeriods ?? ["monthly"],
  basePlans: options.basePlans ?? options.billingPeriods?.map(p => p === "monthly" ? "monthly" : p === "yearly" ? "yearly" : p) ?? ["monthly"],
  // Optional map for Play Console cases where a base-plan ID does not match its actual billing duration.
  basePlanBillingPeriods: options.basePlanBillingPeriods ?? {},
  offerId: options.offerId ?? null,
  trialDays: options.trialDays ?? 0,
  trialEligible: options.trialEligible ?? false,
  price: options.price ?? {},
  // Optional admin-configured Naira prices, keyed by billing period. Never derive these from USD.
  priceNgn: options.priceNgn ?? {},
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
  plan("business","bizstandard","Business Standard",{productId:"bizstandard",billingPeriods:["monthly"],basePlans:["monthly"],offerId:"trial-3d",trialDays:3,trialEligible:true,paymentChannel:"play",playEligible:true,price:{monthly:99.99},limits:{assistants:6,teamMembers:15,aiMessagesPerMonth:100000,storageGb:75},features:["Everything in Business Basic","Instagram","Telegram","Business Email","CRM","Lead Capture","Appointment Booking","Advanced Analytics","REST API"]}),
  plan("business","bizprofessional","Business Professional",{productId:"bizprofessional",billingPeriods:["monthly"],basePlans:["monthly"],offerId:"trial-3d",trialDays:3,trialEligible:true,paymentChannel:"play",popular:true,playEligible:true,playEligiblePeriods:["monthly"],price:{monthly:179.99},limits:{assistants:10,teamMembers:25,aiMessagesPerMonth:150000,storageGb:100},features:["Everything in Business Standard","Live Chat","WhatsApp Business","YouTube","LinkedIn","Google Business Profile","Discord","SMS","Business Email Integration","CRM Integration","Shared Inbox","Team Roles/Permissions","AI Workflow Automation","Custom Branding","Priority Support","REST API","Webhooks"],configurationNotes:["Yearly Business billing is website-managed and may be configured as Contact Sales."]}),
  plan("business","bizbusiness","Business",{productId:"bizbusiness",billingPeriods:["monthly"],basePlans:["monthly"],offerId:"trial-3d",trialDays:3,trialEligible:true,paymentChannel:"play",playEligible:true,playEligiblePeriods:["monthly"],price:{monthly:299.99},limits:{assistants:30,teamMembers:100,aiMessagesPerMonth:750000,storageGb:500},features:["Everything in Business Professional","Voice AI","OCR Document Reading","PDF Analysis","Image Understanding","Calendar Integration","Google Workspace","Microsoft 365","Slack","Advanced Analytics","Multi-language Support","AI Memory","SSO Login","Audit Logs","99.9% SLA"],configurationNotes:["Yearly price is Contact Sales until the higher applicable Play price limit is available."]}),
  plan("business","bizenterprise","Business Enterprise",{productId:"bizenterprise",billingPeriods:["monthly"],basePlans:["monthly"],offerId:"trial-3d",trialDays:3,trialEligible:true,paymentChannel:"play",playEligible:true,playEligiblePeriods:["monthly"],price:{monthly:599.99},limits:{assistants:"unlimited under fair use",teamMembers:"unlimited under fair use",aiMessagesPerMonth:"unlimited under fair use",storageTb:5},features:["Everything in Business","Omnichannel AI","Call Center AI","Phone AI","Airline Booking","Banking","Insurance","Healthcare","Hotel","ERP","CRM","API Integration","AI Voice Calls","AI Video Calls","Live Translation","TikTok","X","AI Agent Builder","Multi-Department AI","White Label","Dedicated Account Manager","24/7 Premium Support","Priority Infrastructure"],configurationNotes:["Yearly price is Contact Sales until the higher applicable Play price limit is available."]}),
  plan("business","businessenterprise","Business Ultimate",{productId:null,productType:"custom",recurring:false,billingPeriods:["custom"],basePlans:[],price:{custom:"CONTACT"},playEligible:false,contactSales:true,limits:{everything:"unlimited"},features:["Unlimited Everything","Custom AI Model Deployment","Private Cloud","On-Premise","Zero Data Retention Option","Dedicated GPU Infrastructure","Enterprise Security","SOC 2 Ready","GDPR Support","ISO 27001 Ready","HIPAA Ready Architecture","Custom Integrations","Salesforce","SAP","Oracle","Microsoft Dynamics","ServiceNow","Airline Reservation Systems","Banking Core Systems Integration","Dedicated AI Engineers","Dedicated Customer Success Manager","99.99% SLA","24/7 Phone Support","Global Deployment","Custom Contract"]})
];

const businessAddOnPlans = [
  plan("business-addons","bizaosupmigration","Business Support Migration",{productId:"bizaosupmigration",productType:"inapp",recurring:false,billingPeriods:["one_time"],basePlans:[],playEligible:true,paymentChannel:"play",features:["One-time business support migration"],configurationNotes:["Existing Google Play one-time product. Google Play supplies the localized checkout price."]}),
  plan("business-addons","bizaosupsetup","Business Support Setup",{productId:"bizaosupsetup",productType:"inapp",recurring:false,billingPeriods:["one_time"],basePlans:[],playEligible:true,paymentChannel:"play",features:["One-time business support setup"],configurationNotes:["Existing Google Play one-time product. Google Play supplies the localized checkout price."]}),
  plan("business-addons","bizaosupdomain","Business Support Domain",{productId:"bizaosupdomain",productType:"inapp",recurring:false,billingPeriods:["one_time"],basePlans:[],playEligible:true,paymentChannel:"play",features:["One-time business support domain"],configurationNotes:["Existing Google Play one-time product. Google Play supplies the localized checkout price."]}),
  plan("business-addons","bizaoaidocs","Business AI Documents",{productId:"bizaoaidocs",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business AI Documents"]}),
  plan("business-addons","bizaoaiworkflow","Business AI Workflow",{productId:"bizaoaiworkflow",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business AI Workflow"]}),
  plan("business-addons","bizaoaiemail","Business AI Email",{productId:"bizaoaiemail",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business AI Email"]}),
  plan("business-addons","bizaoaimarketing","Business AI Marketing",{productId:"bizaoaimarketing",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business AI Marketing"]}),
  plan("business-addons","bizaoaiphone","Business AI Phone",{productId:"bizaoaiphone",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business AI Phone"]}),
  plan("business-addons","bizaoaivoice","Business AI Voice",{productId:"bizaoaivoice",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business AI Voice"]}),
  plan("business-addons","bizaoaibooking","Business AI Booking",{productId:"bizaoaibooking",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business AI Booking"]}),
  plan("business-addons","bizaoextraassistant","Extra Assistant",{productId:"bizaoextraassistant",billingPeriods:["monthly"],basePlans:["1qty"],playEligible:true,paymentChannel:"play",active:false,features:["Extra Assistant"]}),
  plan("business-addons","bizaoadvcompliancemode","Advanced Compliance Mode",{productId:"bizaoadvcompliancemode",billingPeriods:["monthly"],basePlans:["qty-1"],playEligible:true,paymentChannel:"play",features:["Advanced Compliance Mode"]}),
  plan("business-addons","bizaoadvproactive","Advanced Proactive AI",{productId:"bizaoadvproactive",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Advanced Proactive AI"]}),
  plan("business-addons","bizaoadvcustomvoice","Advanced Custom Voice",{productId:"bizaoadvcustomvoice",billingPeriods:["monthly"],basePlans:["bizaoadvcustomvoice"],playEligible:true,paymentChannel:"play",features:["Advanced Custom Voice"]}),
  plan("business-addons","bizaoadvunifiedmemory","Advanced Unified Memory",{productId:"bizaoadvunifiedmemory",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Advanced Unified Memory"]}),
  plan("business-addons","bizaoadvabtesting","Advanced A/B Testing",{productId:"bizaoadvabtesting",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Advanced A/B Testing"]}),
  plan("business-addons","bizaoadvauditexplain","Advanced Audit Explainability",{productId:"bizaoadvauditexplain",billingPeriods:["monthly"],basePlans:["businessaudit"],playEligible:true,paymentChannel:"play",features:["Advanced Audit Explainability"]}),
  plan("business-addons","bizaoadvhumanhandoff","Advanced Human Handoff",{productId:"bizaoadvhumanhandoff",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Advanced Human Handoff"]}),
  plan("business-addons","bizaoadvmultimodal","Advanced Multimodal",{productId:"bizaoadvmultimodal",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Advanced Multimodal"]}),
  plan("business-addons","bizaoadvindustrymodule","Advanced Industry Module",{productId:"bizaoadvindustrymodule",billingPeriods:["qty-1","qty-5","qty-10","qty-25"],basePlans:["qty-1","qty-5","qty-10","qty-25"],playEligible:true,paymentChannel:"play",features:["Advanced Industry Module"]}),
  plan("business-addons","bizaostorage10gb","Business Storage 10 GB",{productId:"bizaostorage10gb",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business Storage 10 GB"]}),
  plan("business-addons","bizaostorage100gb","Business Storage 100 GB",{productId:"bizaostorage100gb",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business Storage 100 GB"]}),
  plan("business-addons","bizaostorage1tb","Business Storage 1 TB",{productId:"bizaostorage1tb",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business Storage 1 TB"]}),
  plan("business-addons","bizaomsgs10k","Business Messages 10K",{productId:"bizaomsgs10k",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business Messages 10K"]}),
  plan("business-addons","bizaomsgs50k","Business Messages 50K",{productId:"bizaomsgs50k",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business Messages 50K"]}),
  plan("business-addons","bizaomsgs100k","Business Messages 100K",{productId:"bizaomsgs100k",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business Messages 100K"]}),
  plan("business-addons","bizaomsgsunlimited","Business Unlimited Messages",{productId:"bizaomsgsunlimited",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business Unlimited Messages"]}),
  plan("business-addons","bizaosupportpriority","Priority Support",{productId:"bizaosupportpriority",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Priority Support"]}),
  plan("business-addons","bizaosupportpremium","Premium Support",{productId:"bizaosupportpremium",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Premium Support"]}),
  plan("business-addons","bizaosupportmanager","Dedicated Support Manager",{productId:"bizaosupportmanager",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Dedicated Support Manager"]}),
  plan("business-addons","bizaosupwhitelabel","Business White Label",{productId:"bizaosupwhitelabel",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Business White Label"]}),
  plan("business-addons","bizaoteamuser","Business Team User",{productId:"bizaoteamuser",billingPeriods:["monthly"],basePlans:["bizaoteamuser"],playEligible:true,paymentChannel:"play",active:false,features:["Business Team User"]}),
  plan("business-addons","bizaointapi","API Integration",{productId:"bizaointapi",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["API Integration"]}),
  plan("business-addons","bizaointcrm","CRM Integration",{productId:"bizaointcrm",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["CRM Integration"]}),
  plan("business-addons","bizaointwhatsapp","WhatsApp Integration",{productId:"bizaointwhatsapp",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["WhatsApp Integration"]}),
  plan("business-addons","bizaointtelegram","Telegram Integration",{productId:"bizaointtelegram",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Telegram Integration"]}),
  plan("business-addons","bizaointmessenger","Messenger Integration",{productId:"bizaointmessenger",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Messenger Integration"]}),
  plan("business-addons","bizaointinstagram","Instagram Integration",{productId:"bizaointinstagram",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Instagram Integration"]}),
  plan("business-addons","bizaointslack","Slack Integration",{productId:"bizaointslack",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Slack Integration"]}),
  plan("business-addons","bizaointdiscord","Discord Integration",{productId:"bizaointdiscord",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Discord Integration"]}),
  plan("business-addons","bizaointzapier","Zapier Integration",{productId:"bizaointzapier",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Zapier Integration"]}),
  plan("business-addons","bizaointshopify","Shopify Integration",{productId:"bizaointshopify",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["Shopify Integration"]}),
  plan("business-addons","bizaointwoocommerce","WooCommerce Integration",{productId:"bizaointwoocommerce",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["WooCommerce Integration"]}),
  plan("business-addons","bizaointwordpress","WordPress Integration",{productId:"bizaointwordpress",billingPeriods:["monthly"],basePlans:["monthly"],playEligible:true,paymentChannel:"play",features:["WordPress Integration"]})
];

const companionPlans = [
  plan("companion","companionfree","Companion Free",{productId:null,productType:"free",recurring:false,billingPeriods:["free"],price:{free:0},playEligible:false,features:["Caring text conversation","Personality choice","Daily check-in prompts"]}),
  plan("companion","companionbasic","Companion Basic",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],basePlanBillingPeriods:{yearly:"monthly"},offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:4.99,yearly:49.99},features:["Memory you control","Full history","Longer conversations"]}),
  plan("companion","companionplus","Companion Plus",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:9.99,yearly:99.99},features:["Voice conversation","Extra voice choices","Avatar"]}),
  plan("companion","companionpremium","Companion Premium",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:19.99,yearly:199.99},features:["Real-time voice","Animated avatar","Interrupt/talk-over","Camera interactions"]}),
  plan("companion","companionultimate","Companion Ultimate",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:34.99,yearly:349.99},features:["Advanced long-term memory","Routines/reminders","Priority response speed","Camera interactions"]})
];

const learningPlans = [
  plan("learning","explorer","Explorer",{productId:null,productType:"free",recurring:false,billingPeriods:["free"],price:{free:0},playEligible:false,limits:{learners:1,aiLessonsPerDay:3},features:["1 learner","3 AI lessons/day","Any curriculum/subject","Practice questions","Instant marking","Progress streaks","Badges","Child-safe mode"]}),
  plan("learning","learnscholar","Scholar",{productId:"learnscholar",productType:"subs",recurring:true,billingPeriods:["monthly","term","yearly"],basePlans:["monthly","term","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:14.99,term:49.99,yearly:129.99},limits:{learners:1,liveAiTeacherMinutesPerMonth:120},features:["1 learner","Unlimited lessons","120 live AI teacher minutes/month","Exam mode","Timed past-paper practice","Homework photo help","Weekly parent report","Downloadable worksheets/revision notes"]}),
  plan("learning","learnfamily","Family Academy",{productId:"learnfamily",productType:"subs",recurring:true,billingPeriods:["monthly","term","yearly"],basePlans:["monthly","term","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:29.99,term:99.99,yearly:259.99},limits:{learners:5,sharedLiveAiTeacherMinutesPerMonth:400},features:["Up to 5 learners","400 shared live AI teacher minutes/month","Parent dashboard","Sibling leaderboards/challenges","Certificates","Priority support"]}),
  plan("learning","learnschool","School & Tutor",{productId:"learnschool",productType:"subs",recurring:true,billingPeriods:["monthly","term"],basePlans:["monthly","term"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:179.99,term:599.99},limits:{learners:60},features:["Up to 60 learners","Learner seats","Teacher dashboards","Classes","Assignments","Bulk onboarding","Curriculum mapping","Coverage reports","Certificates with public verification","Safeguarding log","Data-protection reports","Dedicated onboarding"],configurationNotes:["Yearly price is Contact Sales until the higher applicable Play price limit is available."]})
];

const truthGuardPlans = [
  plan("truthguard","truthguardfree","TruthGuard Free",{productId:null,productType:"free",recurring:false,billingPeriods:["free"],price:{free:0},playEligible:false,limits:{checksPerMonth:5},features:["5 checks/month","Verdict","Confidence","Plain-English explanation","Up to 3 cited sources/check"]}),
  plan("truthguard","tgplus","TruthGuard Plus",{billingPeriods:["monthly"],basePlans:["monthly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:4.99},priceStatus:"approved",proposalPeriods:[],limits:{checksPerMonth:150},features:["150 checks/month","Live web evidence","Source reliability notes","Manipulation/bias signals","History"],configurationNotes:["Google Play Console currently lists only the monthly base plan for tgplus; Google Play supplies localized checkout pricing."]}),
  plan("truthguard","tgpro","TruthGuard Pro",{billingPeriods:["monthly"],basePlans:["monthly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:14.99},priceStatus:"approved",proposalPeriods:[],limits:{checksPerMonth:1000},features:["1,000 checks/month","Deep evidence sweep","More sources","Claim breakdown for long articles/posts","Export-ready summaries","Priority processing"],configurationNotes:["Google Play Console currently lists only the monthly base plan for tgpro; Google Play supplies localized checkout pricing."]}),
  plan("truthguard","tgteam","TruthGuard Team",{billingPeriods:["monthly"],basePlans:["monthly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:49.99},priceStatus:"approved",proposalPeriods:[],limits:{checksPerMonth:"fair-use unlimited"},features:["Fair-use unlimited","Pro features","Shared organization history","Priority support"],configurationNotes:["Google Play Console currently lists only the monthly base plan for tgteam; Google Play supplies localized checkout pricing."]})
];

const frontierPlans = [
  plan("frontier","frontierexplorer","Frontier Explorer",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:false,price:{monthly:14.99,yearly:149.99},features:["Advanced AI experimentation","Structured research","Emerging capability workflows","Prototype workspace","Research history"]}),
  plan("frontier","frontierpro","Frontier Pro",{billingPeriods:["monthly","yearly"],basePlans:["monthly","yearly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:false,price:{monthly:49.99,yearly:499.99},features:["Everything in Frontier Explorer","Deep research","Knowledge graphs","Semantic file search","Project workspaces","AI-generated reports","Advanced model experimentation","Multimodal reasoning"]}),
  plan("frontier","frontierenterprise","Frontier Enterprise",{billingPeriods:["monthly"],basePlans:["monthly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:false,price:{monthly:199.99},features:["Everything in Frontier Pro","AI coding workspace","Evaluation laboratory","Provider/model routing experiments","Priority processing","Advanced project controls","Enterprise research workflows"],configurationNotes:["Starts at $199.99/month; custom enterprise arrangements can be handled separately."]})
];

const lifeMathisPlans = [
  plan("life-mathis","lifemathisfree","AI Life Mathis Free",{productId:null,productType:"free",recurring:false,billingPeriods:["free"],price:{free:0},playEligible:false,limits:{conversationsPerMonth:10},features:["10 conversations/month","Structured guidance","Grounded Life Mathis knowledge"]}),
  plan("life-mathis","lmstandard","Life Mathis Standard",{billingPeriods:["monthly"],basePlans:["monthly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:19.99},priceStatus:"approved",limits:{conversationsPerMonth:300},features:["300 conversations/month","Structured guidance","Deep Life Analysis","Voice notes","Spoken replies","Saved insights","Full history","Multi-perspective answers"],configurationNotes:["Google Play Console mapping currently includes only the monthly base plan."]}),
  plan("life-mathis","lmultimate","Life Mathis Ultimate",{billingPeriods:["monthly"],basePlans:["monthly"],offerId:"trial-3d",trialDays:3,trialEligible:true,playEligible:true,price:{monthly:49.99},priceStatus:"approved",limits:{conversationsPerMonth:"fair-use unlimited"},features:["Fair-use unlimited","Unlimited Deep Life Analysis","Curated wisdom library","Super Wisdom","Life coaching plans","Priority processing/support"],configurationNotes:["Google Play Console mapping currently includes only the monthly base plan."]})
];

const creditPlans = [
  plan("credits","credit100","100 Credits",{productId:"credit100",billingPeriods:["weakly","monthly"],basePlans:["weakly","monthly"],playEligible:true,price:{weakly:8.49,monthly:11.99},productType:"subs",recurring:true,features:["100 credits"],entitlements:["credit:100"],configurationNotes:["Google Play subscription ID is credit100. Base plans are weakly and monthly. The existing monthly price is preserved at $11.99; the weakly price is configured in the fallback catalog; Google Play remains authoritative at checkout."]}),
  plan("credits","credit250","250 Credits",{productId:"credit250",billingPeriods:["weakly","monthly"],basePlans:["weakly","monthly"],playEligible:true,price:{weakly:16.99,monthly:24.99},productType:"subs",recurring:true,features:["250 credits"],entitlements:["credit:250"],configurationNotes:["Google Play subscription ID is credit250. Base plans are weakly and monthly. The existing monthly price is preserved at $24.99; the weakly price is configured in the fallback catalog; Google Play remains authoritative at checkout."]}),
  plan("credits","credit500","500 Credits",{productId:"credit500",billingPeriods:["weakly","monthly","yearly"],basePlans:["weakly","monthly","yearly"],playEligible:true,price:{weakly:29.99,monthly:44.99,yearly:49.99},productType:"subs",recurring:true,features:["500 credits"],entitlements:["credit:500"],configurationNotes:["Google Play subscription ID is credit500. Existing base plans are weakly, monthly, and yearly. Google Play is authoritative for localized checkout prices."]}),
  plan("credits","credit1000","1,000 Credits",{productId:"credit1000",billingPeriods:["weakly","monthly","yearly"],basePlans:["weakly","monthly","yearly"],playEligible:true,price:{weakly:49.99,monthly:79.99,yearly:99.99},productType:"subs",recurring:true,features:["1,000 credits"],entitlements:["credit:1000"],configurationNotes:["Google Play subscription ID is credit1000. Existing base plans are weakly, monthly, and yearly. Google Play is authoritative for localized checkout prices."]}),
  plan("credits","addon-credits-25","25 Credit Offer",{productId:"creditaddon25",productType:"inapp",recurring:false,billingPeriods:["one_time"],basePlans:[],playEligible:true,paymentChannel:"play",price:{one_time:3.5},features:["25 additional credits","One-time purchase","No automatic renewal","Offer"],entitlements:["credit:25"],configurationNotes:["One-time consumable product ID: creditaddon25. No base plan or offer ID. Verified purchases add 25 permanent credits."]}),
  plan("credits","addon-credits-50","50 Credit Offer",{productId:"creditaddon50",productType:"inapp",recurring:false,billingPeriods:["one_time"],basePlans:[],playEligible:true,paymentChannel:"play",price:{one_time:6},features:["50 additional credits","One-time purchase","No automatic renewal","Offer"],entitlements:["credit:50"],configurationNotes:["One-time consumable product ID: creditaddon50. No base plan or offer ID. Verified purchases add 50 permanent credits."]}),
  plan("credits","addon-credits-100","100 Credit Offer",{productId:"creditaddon100",productType:"inapp",recurring:false,billingPeriods:["one_time"],basePlans:[],playEligible:true,paymentChannel:"play",price:{one_time:11},features:["100 additional credits","One-time purchase","No automatic renewal","Offer"],entitlements:["credit:100"],configurationNotes:["One-time consumable product ID: creditaddon100. No base plan or offer ID. Verified purchases add 100 permanent credits."]}),
  plan("credits","addon-credits-200","200 Credit Offer",{productId:"creditaddon200",productType:"inapp",recurring:false,billingPeriods:["one_time"],basePlans:[],playEligible:true,paymentChannel:"play",price:{one_time:20},features:["200 additional credits","One-time purchase","No automatic renewal","Offer"],entitlements:["credit:200"],configurationNotes:["One-time consumable product ID: creditaddon200. No base plan or offer ID. Verified purchases add 200 permanent credits."]}),
  plan("credits","addon-credits-500","500 Credit Offer",{productId:"creditaddon500",productType:"inapp",recurring:false,billingPeriods:["one_time"],basePlans:[],playEligible:true,paymentChannel:"play",price:{one_time:43},features:["500 additional credits","One-time purchase","No automatic renewal","Offer"],entitlements:["credit:500"],configurationNotes:["One-time consumable product ID: creditaddon500. No base plan or offer ID. Verified purchases add 500 permanent credits."]})
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
    businessAddOns: businessAddOnPlans,
    companion: companionPlans,
    learning: learningPlans,
    truthGuard: truthGuardPlans,
    lifeMathis: lifeMathisPlans,
    frontier: frontierPlans,
    credits: creditPlans
  }
};

// Legacy compact shape retained for existing callers/tests; values are derived from the canonical objects above.
export const PLAN_CATALOG = Object.fromEntries(Object.entries(COMMERCIAL_CATALOG.sections).map(([section, rows]) => [
  section,
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
    const pairs = basePlanIds.map((basePlanId, index) => {
      const priceKey = plan.productType === "subs" ? (billingPeriods[index] || basePlanId) : "one_time";
      const actualPeriod = plan.productType === "subs"
        ? (plan.basePlanBillingPeriods?.[String(basePlanId)] || priceKey)
        : "one_time";
      return { basePlanId, billingPeriod: actualPeriod, priceKey };
    }).filter(pair => !eligiblePeriods || eligiblePeriods.has(pair.billingPeriod) || eligiblePeriods.has(String(pair.basePlanId || "")));
    const entitlements = Array.isArray(plan.entitlements) ? plan.entitlements : [];
    const creditGrant = entitlements.reduce((total, value) => {
      const match = String(value).match(/^credit:(\d+)$/);
      return total + (match ? Number(match[1]) : 0);
    }, 0);
    // Google Play supplies localized prices at checkout; fallback USD prices are optional.
    return pairs
      .map(pair => ({
        planId: String(plan.id),
        productId: String(plan.productId),
        basePlanId: pair.basePlanId,
        productType: String(plan.productType),
        enabled: true,
        consumable: plan.productType === "inapp" && creditGrant > 0,
        billingPeriod: pair.billingPeriod,
        priceUsd: plan.price?.[pair.priceKey] == null ? null : Number(plan.price[pair.priceKey]),
        trialOfferId: plan.trialEligible ? (plan.offerId || null) : null,
        trialDays: plan.trialEligible ? Number(plan.trialDays || 0) : 0,
        creditGrant,
        organizationWalletTopup: false
      }));
  });
}
