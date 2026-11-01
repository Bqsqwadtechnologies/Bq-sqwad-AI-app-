/** Same catalog as the website. Every tool is optional. KYC is user-connected. */
export const BUSINESS_TOOL_CATALOG = [
  { id: "gmail", name: "Gmail", category: "communication", composioApp: "gmail" },
  { id: "outlook", name: "Outlook", category: "communication", composioApp: "outlook" },
  { id: "slack", name: "Slack", category: "communication", composioApp: "slack" },
  { id: "whatsapp", name: "WhatsApp Business", category: "communication", composioApp: "whatsapp" },
  { id: "telegram", name: "Telegram", category: "communication", composioApp: "telegram" },
  { id: "google_calendar", name: "Google Calendar", category: "productivity", composioApp: "googlecalendar" },
  { id: "notion", name: "Notion", category: "productivity", composioApp: "notion" },
  { id: "linear", name: "Linear", category: "productivity", composioApp: "linear" },
  { id: "hubspot", name: "HubSpot", category: "crm", composioApp: "hubspot" },
  { id: "salesforce", name: "Salesforce", category: "crm", composioApp: "salesforce" },
  { id: "zoho", name: "Zoho CRM", category: "crm", composioApp: "zoho" },
  { id: "stripe", name: "Stripe", category: "payments", composioApp: "stripe" },
  { id: "paystack", name: "Paystack", category: "payments", composioApp: "paystack" },
  { id: "shopify", name: "Shopify", category: "payments", composioApp: "shopify" },
  { id: "google_drive", name: "Google Drive", category: "storage", composioApp: "googledrive" },
  { id: "dropbox", name: "Dropbox", category: "storage", composioApp: "dropbox" },
  { id: "github", name: "GitHub", category: "developer", composioApp: "github" },
  { id: "jira", name: "Jira", category: "developer", composioApp: "jira" },
  { id: "zendesk", name: "Zendesk", category: "support", composioApp: "zendesk" },
  { id: "intercom", name: "Intercom", category: "support", composioApp: "intercom" },
  { id: "freshdesk", name: "Freshdesk", category: "support", composioApp: "freshdesk" },
  { id: "sheets", name: "Google Sheets", category: "data", composioApp: "googlesheets" },
  { id: "airtable", name: "Airtable", category: "data", composioApp: "airtable" },
  { id: "twitter", name: "X", category: "social", composioApp: "twitter" },
  { id: "instagram", name: "Instagram", category: "social", composioApp: "instagram" },
  { id: "kyc_generic", name: "KYC provider", category: "identity", composioApp: "custom" },
  { id: "bvn_lookup", name: "BVN lookup", category: "identity", composioApp: "custom" },
  { id: "nin_lookup", name: "NIN lookup", category: "identity", composioApp: "custom" },
  { id: "custom_api", name: "Your own API", category: "developer", composioApp: "custom" },
];

export function toolsForPlanOrAll(planToolIds) {
  if (!planToolIds || !planToolIds.length) return BUSINESS_TOOL_CATALOG;
  const allow = new Set(planToolIds);
  const filtered = BUSINESS_TOOL_CATALOG.filter((t) => allow.has(t.id));
  return filtered.length ? filtered : BUSINESS_TOOL_CATALOG;
}
