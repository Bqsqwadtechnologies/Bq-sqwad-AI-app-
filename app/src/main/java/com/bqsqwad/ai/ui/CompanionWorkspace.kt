package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.ChatApiClient
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch

private data class CompanionMessage(val fromUser:Boolean,val text:String)

@Composable
fun CompanionWorkspace(){
    val scope=rememberCoroutineScope()
    var personality by remember { mutableStateOf("Caring") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var memoryEnabled by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    val messages=remember { mutableStateListOf<CompanionMessage>() }

    LaunchedEffect(Unit){
        runCatching{WorkspaceApiClient().memory()}.onSuccess{memoryEnabled=it.optBoolean("enabled",true)}
    }

    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("AI Companion",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("A configurable companion for caring conversation, personality and user-controlled memory.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            AssistChip(onClick={personality="Caring"},label={Text("Caring")})
            AssistChip(onClick={personality="Encouraging"},label={Text("Encouraging")})
            AssistChip(onClick={personality="Calm"},label={Text("Calm")})
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Column(Modifier.weight(1f)){Text("Memory",fontWeight=FontWeight.SemiBold);Text(if(memoryEnabled)"Enabled — you control it" else "Disabled",style=MaterialTheme.typography.bodySmall)}
            Switch(checked=memoryEnabled,onCheckedChange={enabled->
                memoryEnabled=enabled
                scope.launch{runCatching{WorkspaceApiClient().setMemoryEnabled(enabled)}}
            })
        }
        notice?.let{Text(it,color=MaterialTheme.colorScheme.primary)}
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(messages){m->Surface(color=if(m.fromUser)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth()){Text(m.text,Modifier.padding(12.dp))}}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(input,{input=it},Modifier.weight(1f),placeholder={Text("Talk to your companion…")},maxLines=4)
            Button(enabled=!busy&&input.isNotBlank(),onClick={
                val text=input.trim(); input=""; messages.add(CompanionMessage(true,text)); busy=true; notice=null
                scope.launch{
                    runCatching{ChatApiClient().sendMessage("Personality: $personality. Respond as a supportive AI companion. User: $text","companion")}
                        .onSuccess{messages.add(CompanionMessage(false,it.text))}
                        .onFailure{notice=it.message?:"Companion response failed."}
                    busy=false
                }
            }){Text(if(busy)"…" else "Send")}
        }
        OutlinedButton(enabled=!busy,onClick={
            val prompt="Give me a brief, caring daily check-in prompt for today."
            busy=true
            scope.launch{runCatching{ChatApiClient().sendMessage("Personality: $personality. $prompt","companion")}.onSuccess{messages.add(CompanionMessage(false,it.text))}.onFailure{notice=it.message?:"Check-in failed."};busy=false}
        },modifier=Modifier.fillMaxWidth()){Text("Daily check-in")}
        DeviceIntelligencePanel("AI Companion")
    }
}
