// Canonical native catalog metadata. IDs are admin-facing and intentionally contain no underscores.
// Prices/features are sourced from the BQ Sqwad AI master catalog; live Google Play prices remain authoritative at checkout.

export const PLAN_CATALOG_VERSION = 1;

const business = [
  ["bizstarter","Business Starter",29.99,299.99,["AI Chat","File Upload","Knowledge Base","Website Chat Widget","Email Support","Analytics Dashboard","Basic API","Organised Knowledge Vault","FAQ Knowledge Base","AI Business Plan Builder","Resolution Transparency Dashboard","Usage Forecasting","One-Click Multi-Channel Deploy","Custom AI Training"],{assistants:2,teamMembers:5,aiMessagesPerMonth:25000,storage:"20 GB"}],
  ["bizbasic","Business Basic",59.99,599.99,["Everything in Business Starter","Knowledge Vault","FAQ Knowledge Base","AI Business Plan Builder","Email Support"],{assistants:4,teamMembers:10,aiMessagesPerMonth:60000,storage:"50 GB"}],
  ["bizstandard","Business Standard",99.99,999.99,["Everything in Business Basic","Instagram","Telegram","Business Email","CRM","Lead Capture","Appointment Booking","Advanced Analytics","REST API"],{assistants:6,teamMembers:15,aiMessagesPerMonth:100000,storage:"75 GB"}],
  ["bizprofessional","Business Professional",179.99,1799.99,["Everything in Business Standard","Live Chat","WhatsApp Business","YouTube","LinkedIn","Google Business Profile","Discord","SMS","Business Email Integration","CRM Integration","Shared Inbox","Team Roles/Permissions","AI Workflow Automation","Custom Branding","Priority Support","REST API","Webhooks"],{assistants:10,teamMembers:25,aiMessagesPerMonth:150000,storage:"100 GB"}],
  ["bizbusiness","Business",299.99,2999.99,["Everything in Business Professional","Voice AI","OCR Document Reading","PDF Analysis","Image Understanding","Calendar Integration","Google Workspace","Microsoft 365","Slack","Advanced Analytics","Multi-language Support","AI Memory","SSO Login","Audit Logs","99.9% SLA"],{assistants:30,teamMembers:100,aiMessagesPerMonth:750000,storage:"500 GB"}],
  ["bizenterprise","Business Enterprise",599.99,5999.99,["Everything in Business","Omnichannel AI","Call Center AI","Phone AI","Airline Booking","Banking","Insurance","Healthcare","Hotel","ERP","CRM","API Integration","AI Voice Calls","AI Video Calls","Live Translation","TikTok","X","AI Agent Builder","Multi-Department AI","White Label","Dedicated Account Manager","24/7 Premium Support","Priority Infrastructure"],{assistants:"Unlimited (fair use)",teamMembers:"Unlimited (fair use)",aiMessagesPerMonth:"Unlimited (fair use)",storage:"5 TB"}],
  ["businessenterprise","Business Ultimate",null,null,["Unlimited Everything","Custom AI Model Deployment","Private Cloud","On-Premise","Zero Data Retention Option","Dedicated GPU Infrastructure","Enterprise Security","SOC 2 Ready","GDPR Support","ISO 27001 Ready","HIPAA Ready Architecture","Custom Integrations","Salesforce","SAP","Oracle","Microsoft Dynamics","ServiceNow","Airline Reservation Systems","Banking Core Systems Integration","Dedicated AI Engineers","Dedicated Customer Success Manager","99.99% SLA","24/7 Phone Support","Global Deployment","Custom Contract"],{pricing:"Custom / Contact Sales"}]
];

const companion = [
  ["companionfree","Companion Free",0,0,["Caring text conversation","Personality choice","Daily check-in prompts"]],
  ["companionbasic","Companion Basic",4.99,49.99,["Memory you control","Full history","Longer conversations"]],
  ["companionplus","Companion Plus",9.99,99.99,["Voice conversation","Extra voice choices","Avatar"]],
  ["companionpremium","Companion Premium",19.99,199.99,["Real-time voice","Animated avatar","Interrupt/talk-over"]],
  ["companionultimate","Companion Ultimate",34.99,349.99,["Advanced long-term memory","Routines/reminders","Priority response speed"]]
];

const learning = [
  ["explorer","Explorer",0,null,["1 learner","3 AI lessons/day","Any curriculum/subject","Practice questions","Instant marking","Progress streaks","Badges","Child-safe mode"]],
  ["learnscholar","Scholar",14.99,129.99,["1 learner","Unlimited lessons","120 live AI teacher minutes/month","Exam mode","Timed past-paper practice","Homework photo help","Weekly parent report","Downloadable worksheets/revision notes"]],
  ["learnfamily","Family Academy",29.99,259.99,["Up to 5 learners","400 shared live AI teacher minutes/month","Parent dashboard","Sibling leaderboards/challenges","Certificates","Priority support"]],
  ["learnschool","School & Tutor",179.99,1799.99,["Up to 60 learners","Learner seats","Teacher dashboards","Classes","Assignments","Bulk onboarding","Curriculum mapping","Coverage reports","Certificates with public verification","Safeguarding log","Data-protection reports","Dedicated onboarding"]]
];

const truthGuard = [
  ["truthguardfree","TruthGuard Free",0,null,["5 checks/month","Verdict","Confidence","Plain-English explanation","Up to 3 cited sources/check"]],
  ["tgplus","TruthGuard Plus",4.99,49.70,["150 checks/month","Live web evidence","Source reliability notes","Manipulation/bias signals","History"]],
  ["tgpro","TruthGuard Pro",14.99,149.30,["1,000 checks/month","Deep evidence sweep","More sources","Claim breakdown for long articles/posts","Export-ready summaries","Priority processing"]],
  ["tgteam","TruthGuard Team",49.99,497.90,["Fair-use unlimited","Pro features","Shared org history","Priority support"]]
];

const lifeMathis = [
  ["lifemathisfree","AI Life Mathis Free",0,null,["10 conversations/month","Structured guidance","Grounded Life Mathis knowledge"]],
  ["lmstandard","Life Mathis Standard",19.99,199.10,["300 conversations/month","Structured guidance","Deep Life Analysis","Voice notes","Spoken replies","Saved insights","Full history","Multi-perspective answers"]],
  ["lmultimate","Life Mathis Ultimate",49.99,497.90,["Fair-use unlimited","Unlimited Deep Life Analysis","Curated wisdom library","Super Wisdom","Life coaching plans","Priority processing/support"]]
];

const learningCapability = [
  ["learncapabilityplus","Learning Capability Plus",3.99,34.99,["Premium voice tutor","Advanced AI models"]],
  ["learncapabilitypremium","Learning Capability Premium",7.99,69.99,["Voice tutor","Advanced AI models","AI Live Camera tutor","Real-time demonstrations"]],
  ["learncapabilityelite","Learning Capability Elite",14.99,129.99,["Voice tutor","Advanced AI models","AI Live Camera","Real-time demonstrations","Deep research","AI Studio"]]
];

const credits = [
  ["cred100","100 Credits",11.99,null,["100 credits","Monthly"]],
  ["cred250","250 Credits",24.99,null,["250 credits","Monthly"]],
  ["cred500","500 Credits",44.99,null,["500 credits","Monthly"]],
  ["cred1000","1,000 Credits",79.99,null,["1,000 credits","Monthly"]]
];

const addOns = [
  ["bizaoadvmultimodal","Multimodal Assistant",39.99,"monthly"],
  ["bizaoadvunifiedmemory","Unified Cross-Channel Memory",19.99,"monthly"],
  ["bizaoadvabtesting","A/B Testing for AI Responses",24.99,"monthly"],
  ["bizaoadvhumanhandoff","Real-Time Human Handoff",14.99,"monthly"],
  ["bizaoadvproactive","Proactive Outreach Engine",29.99,"monthly"],
  ["bizaoadvindustrymodule","Industry Pre-trained Module",19.99,"per-module/month"],
  ["bizaoadvcompliancemode","Compliance Mode",34.99,"per-mode/month"],
  ["bizaoadvcustomvoice","Custom Cloned Brand Voice",49.99,"monthly"],
  ["bizaoadvauditexplain","Explainable AI Audit Trail",14.99,"monthly"],
  ["bizaomsgs10k","+10K AI Messages",13.99,"monthly"],
  ["bizaomsgs50k","+50K AI Messages",54.99,"monthly"],
  ["bizaomsgs100k","+100K AI Messages",94.99,"monthly"],
  ["bizaomsgsunlimited","Unlimited AI Messages",269.99,"monthly"],
  ["bizaostorage10gb","+10 GB Storage",6.99,"monthly"],
  ["bizaostorage100gb","+100 GB Storage",19.99,"monthly"],
  ["bizaostorage1tb","+1 TB Storage",69.99,"monthly"],
  ["bizaoteamuser","Extra Team Member",9.99,"per-user/month"],
  ["bizaoextraassistant","Extra Assistant",19.99,"per-assistant/month"],
  ["bizaointwhatsapp","WhatsApp Integration",19.99,"monthly"],
  ["bizaointmessenger","Messenger Integration",13.99,"monthly"],
  ["bizaointinstagram","Instagram Integration",13.99,"monthly"],
  ["bizaointtelegram","Telegram Integration",6.99,"monthly"],
  ["bizaointslack","Slack Integration",13.99,"monthly"],
  ["bizaointdiscord","Discord Integration",13.99,"monthly"],
  ["bizaointshopify","Shopify Integration",26.99,"monthly"],
  ["bizaointwoocommerce","WooCommerce Integration",19.99,"monthly"],
  ["bizaointwordpress","WordPress Integration",13.99,"monthly"],
  ["bizaointzapier","Zapier Integration",26.99,"monthly"],
  ["bizaointcrm","CRM Integration",34.99,"monthly"],
  ["bizaointapi","API Integration",34.99,"monthly"],
  ["bizaoaivoice","AI Voice",34.99,"monthly"],
  ["bizaoaiphone","AI Phone",69.99,"monthly"],
  ["bizaoaibooking","AI Booking",19.99,"monthly"],
  ["bizaoaisales","AI Sales",39.99,"monthly"],
  ["bizaoaimarketing","AI Marketing",34.99,"monthly"],
  ["bizaoaiemail","AI Email",19.99,"monthly"],
  ["bizaoaiworkflow","AI Workflow",54.99,"monthly"],
  ["bizaoaidocs","AI Documents",39.99,"monthly"],
  ["bizaosupportpriority","Priority Support",34.99,"monthly"],
  ["bizaosupportpremium","Premium Support",69.99,"monthly"],
  ["bizaosupportmanager","Dedicated Manager",139.99,"monthly"],
  ["bizaosupsetup","AI Setup",349,"one-time"],
  ["bizaosupmigration","Migration",199,"one-time"],
  ["bizaosupwhitelabel","White Label",199,"monthly"],
  ["bizaosupdomain","Custom Domain",69,"one-time"],
  ["bizaosupcustommodel","Custom Model","CONTACT","custom"]
];

const examAddOns = [
  ["learnexamwaecneco","WAEC/NECO",6.99,19.99],
  ["learnexamgcse","GCSE",6.99,19.99],
  ["learnexamsatact","SAT/ACT",7.99,22.99],
  ["learnexamigcsalevel","IGCSE/A Level",7.99,22.99]
];

export const PLAN_CATALOG = {
  business,
  companion,
  learning,
  learningCapability,
  truthGuard,
  lifeMathis,
  credits,
  addOns,
  examAddOns,
  commonBusinessFreeCallouts:["Resolution Transparency Dashboard","Usage Forecasting and Bill Prediction","One-Click Multi-Channel Deploy"],
  businessBasePlans:["monthly","yearly"],
  trialOfferId:"trial-3d",
  addOnTrial:false,
  contactSalesEmail:"hello@bqsqwadai.com",
  notes:{
    yearlyBusinessProfessional:"Contact Sales until the higher Google Play price limit is available.",
    yearlyBusiness:"Contact Sales until the higher Google Play price limit is available.",
    yearlyBusinessEnterprise:"Contact Sales until the higher Google Play price limit is available.",
    businessUltimate:"Contact Sales / custom pricing.",
    playPricesAreLocalized:true
  }
};

export function canonicalPlayProductId(id) {
  return String(id || "").replace(/_/g, "");
}
