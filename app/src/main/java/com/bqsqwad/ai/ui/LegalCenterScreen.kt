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

private data class LegalSection(val title: String, val detail: String, val url: String)

private const val PRIVACY = "https://bqsqwadai.com/privacy-policy"
private const val TERMS = "https://bqsqwadai.com/terms"

private fun textFragment(base: String, heading: String): String =
    base + "#:~:text=" + Uri.encode(heading)

@Composable
fun LegalCenterScreen(onBack: () -> Unit) {
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
                    "Tap a topic to open the corresponding section of the current public policy. The links use browser text-fragment navigation so the selected topic opens at its matching heading when supported by the browser.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            item { Text("Privacy Policy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
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
