@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bqsqwad.ai.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.domain.Workspace

private data class LegalSection(val title: String, val detail: String, val url: String)

private const val PRIVACY = "https://bqsqwadai.com/privacy-policy"
private const val TERMS = "https://bqsqwadai.com/terms"

private fun textFragment(base: String, heading: String): String =
    base + "#:~:text=" + Uri.encode(heading)

@Composable
fun LegalCenterScreen(initialTopic: Workspace? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val privacy = listOf(
        LegalSection("What information is collected", "Account details, conversations, uploaded content, memory and feature-specific data are explained here.", textFragment(PRIVACY, "Information we collect")),
        LegalSection("How information is used", "See the purposes for account operation, AI processing, personalisation, security and service improvement.", textFragment(PRIVACY, "How we use your information")),
        LegalSection("Microphone, camera and device permissions", "See what happens when you start voice, camera or supported device features.", textFragment(PRIVACY, "Microphone, camera and device permissions")),
        LegalSection("Firebase and cloud infrastructure", "See how authentication and cloud services are used.", textFragment(PRIVACY, "Firebase and cloud infrastructure")),
        LegalSection("AI service providers", "See how relevant prompts and context may be processed by configured AI providers.", textFragment(PRIVACY, "AI service providers")),
        LegalSection("Data retention", "See retention periods, deletion handling and backup rotation.", textFragment(PRIVACY, "Data retention")),
        LegalSection("Your rights and choices", "See access, correction, deletion, export, objection and consent choices.", textFragment(PRIVACY, "Your rights and choices")),
        LegalSection("Security", "See the security measures used to protect information.", textFragment(PRIVACY, "Security")),
        LegalSection("International data transfers", "See how cross-border processing is addressed.", textFragment(PRIVACY, "International data transfers")),
        LegalSection("Account deletion", "Delete your account in the app or use the public deletion instructions in the Privacy Policy.", textFragment(PRIVACY, "Your rights and choices")),
        LegalSection("Contact and privacy requests", "Use the official privacy contact information for questions or data requests.", textFragment(PRIVACY, "Contact us"))
    )
    val terms = listOf(
        LegalSection("Using the service", "See the rules governing lawful and respectful use.", textFragment(TERMS, "Using the service")),
        LegalSection("Your account", "See account security and responsibility requirements.", textFragment(TERMS, "Your account")),
        LegalSection("AI output", "See limitations and responsibilities when relying on AI-generated responses.", textFragment(TERMS, "AI output")),
        LegalSection("Subscriptions", "See the current subscription and cancellation terms.", textFragment(TERMS, "Subscriptions")),
        LegalSection("Changes", "See how material changes to the terms are communicated.", textFragment(TERMS, "Changes"))
    )

    fun open(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Legal Center") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Your privacy controls", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Each BQ Sqwad AI feature has its own privacy topic. Feature privacy actions open this section directly, while public policy links use browser heading navigation when supported.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            initialTopic?.let { topic ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${topic.title} privacy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(featurePrivacyDetail(topic), color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Public policy section: ${featurePrivacyHeading(topic)}", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item { Text("Feature privacy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(Workspace.values()) { topic ->
                LegalSectionCard(
                    LegalSection(
                        "${topic.title} privacy",
                        featurePrivacyDetail(topic),
                        textFragment(PRIVACY, featurePrivacyHeading(topic))
                    ),
                    ::open
                )
            }
            item { Text("General Privacy Policy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(privacy) { section ->
                LegalSectionCard(section, ::open)
            }
            item { Text("Terms & Conditions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(terms) { section ->
                LegalSectionCard(section, ::open)
            }
            item {
                Button(onClick = { open(PRIVACY) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Text("Open full Privacy Policy", modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                Button(onClick = { open(TERMS) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Text("Open full Terms & Conditions", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

private fun featurePrivacyHeading(w: Workspace): String = when (w) {
    Workspace.CHAT -> "Main Chat"
    Workspace.SEARCH -> "Web Search"
    Workspace.CAMERA -> "AI Live Camera"
    Workspace.VOICE -> "Live Voice AI"
    Workspace.PERSONAL -> "Personal Intelligence"
    Workspace.LIFE -> "Life Matters"
    Workspace.COMPANION -> "AI Companion"
    Workspace.LEARNING -> "Learning Studio"
    Workspace.BUSINESS -> "Business AI"
    Workspace.ORGANIZATION_STUDIO -> "Organization Studio AI"
    Workspace.ADMIN -> "Admin Control Center"
}

private fun featurePrivacyDetail(w: Workspace): String = when (w) {
    Workspace.CHAT -> "Chat messages, attachments, response feedback and saved history are processed to provide the chat service."
    Workspace.SEARCH -> "Search queries and returned source information are processed to provide web-search results; search-provider credentials remain server-side."
    Workspace.CAMERA -> "Camera and screen frames are processed only while the user starts the relevant capture feature; Android permission indicators and foreground-service controls apply."
    Workspace.VOICE -> "Live voice audio is processed while a voice session is active. Microphone permission is required and the live session can be stopped by the user."
    Workspace.PERSONAL -> "Personal workspace requests and optional memory are account-scoped. Memory controls determine whether saved personal context is used."
    Workspace.LIFE -> "Life Matters requests are processed to provide planning and practical assistance, with account-scoped feature data where applicable."
    Workspace.COMPANION -> "Companion conversations and optional memory/check-in data are feature-scoped and controlled through the account."
    Workspace.LEARNING -> "Learning requests and learning-related feature data are processed to provide tutoring, lessons and revision assistance."
    Workspace.BUSINESS -> "Business workspace data is scoped to authorized business workspaces. Connected providers receive only the data required for an enabled integration."
    Workspace.ORGANIZATION_STUDIO -> "Organization Studio data is processed for the organization's configured tools and workflows, subject to workspace access controls."
    Workspace.ADMIN -> "Administrative configuration is restricted by server-side authorization; provider secrets and security material are kept server-side."
}

@Composable
private fun LegalSectionCard(section: LegalSection, onOpen: (String) -> Unit) {
    Card(
        onClick = { onOpen(section.url) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(section.title, fontWeight = FontWeight.SemiBold)
            Text(section.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("Open exact section", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
