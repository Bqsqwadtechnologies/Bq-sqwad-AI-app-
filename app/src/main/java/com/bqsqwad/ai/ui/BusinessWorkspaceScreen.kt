@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun BusinessWorkspaceScreen() {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Overview") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var workspace by remember { mutableStateOf(JSONObject()) }
    var prompt by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var leadName by remember { mutableStateOf("") }
    var leadContact by remember { mutableStateOf("") }
    var leadNote by remember { mutableStateOf("") }
    var inboxMessage by remember { mutableStateOf("") }
    var inboxContact by remember { mutableStateOf("") }

    fun load() {
        loading = true; error = null
        scope.launch {
            try { workspace = WorkspaceApiClient().businessWorkspace() }
            catch (t: Throwable) { error = t.message ?: "Business workspace could not be loaded." }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }

    val tabs = listOf("Overview", "Assistant", "Knowledge", "Inbox", "Leads", "Automation", "Analytics")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Business AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("A native business workspace. Your business data stays scoped to your authenticated workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0)) {
            tabs.forEachIndexed { i, label ->
                Tab(selected = tab == label, onClick = { tab = label }, text = { Text(label) })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { SurfaceCard("Could not load", it) }

        when (tab) {
            "Overview" -> {
                SurfaceCard("Workspace", workspace.optString("name").ifBlank { "Business workspace" })
                SurfaceCard("Assistant", workspace.optString("assistantName").ifBlank { "Business AI Assistant" })
                SurfaceCard("Data isolation", "Business knowledge, leads, conversations and automation records are stored under the signed-in business workspace and are not included in general model training.")
                Button(onClick = { load() }, enabled = !loading) { Text("Refresh") }
            }
            "Assistant" -> {
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Ask the business assistant") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(enabled = prompt.isNotBlank() && !saving, onClick = {
                    saving = true; answer = ""; error = null
                    scope.launch {
                        try { answer = WorkspaceApiClient().businessAssistant(prompt.trim()).optString("answer") }
                        catch (t: Throwable) { error = t.message ?: "Business assistant request failed." }
                        finally { saving = false }
                    }
                }) { Text(if (saving) "Working…" else "Run assistant") }
                if (answer.isNotBlank()) SurfaceCard("Assistant response", answer)
            }
            "Knowledge" -> {
                SurfaceCard("Knowledge base", "Connect approved business documents, FAQs, policies and product information here. The backend stores records per business workspace.")
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Add a knowledge note") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
                Button(enabled = prompt.isNotBlank() && !saving, onClick = {
                    saving = true
                    scope.launch {
                        try { workspace = WorkspaceApiClient().addBusinessKnowledge(prompt.trim()) }
                        catch (t: Throwable) { error = t.message ?: "Knowledge could not be saved." }
                        finally { saving = false; prompt = "" }
                    }
                }) { Text(if (saving) "Saving…" else "Save knowledge") }
                val knowledge = workspace.optJSONArray("knowledge") 
                if (knowledge != null) itemsFromJson(knowledge).forEach { SurfaceCard("Knowledge", it) }
            }
            "Inbox" -> {
                SurfaceCard("Unified inbox", "Customer messages can be connected here through approved channels. Each conversation is scoped to this business.")
                OutlinedTextField(inboxMessage, { inboxMessage = it }, label = { Text("Add customer message") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(inboxContact, { inboxContact = it }, label = { Text("Customer contact (optional)") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = inboxMessage.isNotBlank() && !saving, onClick = {
                    saving = true; error = null
                    scope.launch {
                        try { workspace = WorkspaceApiClient().addBusinessInboxMessage(inboxMessage.trim(), inboxContact.trim().ifBlank { null }); inboxMessage = ""; inboxContact = "" }
                        catch (t: Throwable) { error = t.message ?: "Inbox message could not be saved." }
                        finally { saving = false }
                    }
                }) { Text(if (saving) "Saving…" else "Add message") }
                val inbox = workspace.optJSONArray("inbox")
                if (inbox != null && inbox.length() > 0) itemsFromJson(inbox).forEach { SurfaceCard("Conversation", it) }
            }
            "Leads" -> {
                SurfaceCard("Leads & CRM", "Track business prospects, status, notes and follow-up activity without mixing them with personal chat history.")
                OutlinedTextField(leadName, { leadName = it }, label = { Text("Lead name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(leadContact, { leadContact = it }, label = { Text("Contact") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(leadNote, { leadNote = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(enabled = leadName.isNotBlank() && !saving, onClick = {
                    saving = true; error = null
                    scope.launch {
                        try { workspace = WorkspaceApiClient().addBusinessLead(leadName.trim(), leadContact.trim(), leadNote.trim()); leadName = ""; leadContact = ""; leadNote = "" }
                        catch (t: Throwable) { error = t.message ?: "Lead could not be saved." }
                        finally { saving = false }
                    }
                }) { Text(if (saving) "Saving…" else "Add lead") }
                val leads = workspace.optJSONArray("leads")
                if (leads != null && leads.length() > 0) itemsFromJson(leads).forEach { SurfaceCard("Lead", it) }
            }
            "Automation" -> {
                SurfaceCard("Automation", "Business automations are represented as server-side records so future actions can be audited and controlled.")
                val automations = workspace.optJSONArray("automations")
                if (automations != null && automations.length() > 0) itemsFromJson(automations).forEach { SurfaceCard("Automation", it) }
            }
            "Analytics" -> {
                SurfaceCard("Business analytics", "Usage and activity metrics are workspace-scoped. The dashboard reports stored activity; it does not invent metrics.")
                val analytics = workspace.optJSONObject("analytics")
                Text("Requests: " + (analytics?.optLong("requests", 0L) ?: 0L))
                Text("Leads: " + (analytics?.optLong("leads", 0L) ?: 0L))
                Text("Knowledge items: " + (analytics?.optLong("knowledge", 0L) ?: 0L))
            }
        }
    }
}

private fun itemsFromJson(array: org.json.JSONArray): List<String> =
    (0 until array.length()).mapNotNull { i ->
        val value = array.opt(i)
        when (value) {
            is JSONObject -> value.toString()
            is String -> value
            else -> value?.toString()
        }
    }
