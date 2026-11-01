package com.bqsqwad.ai.core

/** Mirrors the website catalog. Every tool is optional. The business connects what they want. */
data class BusinessTool(
    val id: String,
    val name: String,
    val category: String,
    val summary: String
)

object BusinessToolCatalog {
    val all: List<BusinessTool> = listOf(
        BusinessTool("gmail", "Gmail", "Communication", "Read and send customer email."),
        BusinessTool("outlook", "Outlook", "Communication", "Microsoft mail and calendar."),
        BusinessTool("slack", "Slack", "Communication", "Team and customer channels."),
        BusinessTool("whatsapp", "WhatsApp Business", "Communication", "Message customers on WhatsApp."),
        BusinessTool("google_calendar", "Google Calendar", "Productivity", "Book and update appointments."),
        BusinessTool("notion", "Notion", "Productivity", "Notes, wikis, and tickets."),
        BusinessTool("hubspot", "HubSpot", "CRM", "Contacts, deals, and tickets."),
        BusinessTool("salesforce", "Salesforce", "CRM", "CRM records and cases."),
        BusinessTool("stripe", "Stripe", "Payments", "Payments, invoices, refunds."),
        BusinessTool("paystack", "Paystack", "Payments", "Local payments and customers."),
        BusinessTool("shopify", "Shopify", "Payments", "Orders and store customers."),
        BusinessTool("google_drive", "Google Drive", "Files", "Files the business already stores."),
        BusinessTool("github", "GitHub", "Developer", "Repos, issues, and pull requests."),
        BusinessTool("zendesk", "Zendesk", "Support", "Support tickets."),
        BusinessTool("sheets", "Google Sheets", "Data", "Look up and update rows."),
        BusinessTool("kyc_generic", "KYC provider", "Identity (optional)", "Connect your own identity provider. Secrets stay off chat."),
        BusinessTool("bvn_lookup", "BVN lookup", "Identity (optional)", "You connect this only if you want it."),
        BusinessTool("nin_lookup", "NIN lookup", "Identity (optional)", "You connect this only if you want it."),
        BusinessTool("custom_api", "Your own API", "Developer", "Any HTTPS API you already run.")
    )

    fun forPlan(ids: List<String>?): List<BusinessTool> {
        if (ids.isNullOrEmpty()) return all
        val filtered = all.filter { ids.contains(it.id) }
        return filtered.ifEmpty { all }
    }
}
