package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class BusinessPlanCard(
    val id: String,
    val title: String,
    val subtitle: String,
    val bullets: List<String>
)

val DEFAULT_BUSINESS_PLANS = listOf(
    BusinessPlanCard(
        id = "business",
        title = "Business",
        subtitle = "AI customer-care agents that verify customers securely and work with your own systems.",
        bullets = listOf(
            "Hosted chat + embeddable widget",
            "Secure verification handoff (no secrets in chat)",
            "Integrations with your back-office APIs",
            "Playbooks for common support flows"
        )
    ),
    BusinessPlanCard(
        id = "enterprise",
        title = "Enterprise",
        subtitle = "Unlimited agents, advanced approvals, audit trail and dedicated support.",
        bullets = listOf(
            "Everything in Business",
            "Two-person approval for high-risk actions",
            "Immutable audit trail",
            "Custom limits and Contact Sales support"
        )
    ),
    BusinessPlanCard(
        id = "contact_sales",
        title = "Contact Sales",
        subtitle = "Custom deployment, integrations and pricing, set up by our team.",
        bullets = listOf(
            "Tailored integrations",
            "Custom compliance requirements",
            "Dedicated onboarding"
        )
    )
)

/** Clean Business / Enterprise plan cards for the app plans screen (Prompt I). */
@Composable
fun BusinessPlanCards(
    plans: List<BusinessPlanCard> = DEFAULT_BUSINESS_PLANS,
    activePlanIds: Set<String> = emptySet(),
    onSelect: (BusinessPlanCard) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        plans.forEach { plan ->
            val active = plan.id in activePlanIds
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (active)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(plan.title, style = MaterialTheme.typography.titleLarge)
                    Text(plan.subtitle, style = MaterialTheme.typography.bodyMedium)
                    plan.bullets.forEach { b ->
                        Text("• $b", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onSelect(plan) }) {
                        Text(if (active) "Included in your plan" else if (plan.id == "contact_sales") "Contact sales" else "View plan")
                    }
                }
            }
        }
    }
}
