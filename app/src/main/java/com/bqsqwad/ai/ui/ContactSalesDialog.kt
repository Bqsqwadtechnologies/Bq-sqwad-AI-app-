package com.bqsqwad.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bqsqwad.ai.core.WorkspaceApiClient
import kotlinx.coroutines.launch

@Composable
fun ContactSalesDialog(
    planId:String,
    planName:String,
    features:List<String>,
    onDismiss:()->Unit,
    onSubmitted:(String)->Unit
) {
    val scope=rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var companyName by remember { mutableStateOf("") }
    var companyEmail by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("I would like more information about $planName.") }
    var selected by remember { mutableStateOf(features.toSet()) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest={if(!sending)onDismiss()},
        title={Text("Contact Sales")},
        text={
            LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp), modifier=Modifier.fillMaxWidth()){
                item{Text("Choose the features you want us to discuss.",fontWeight=FontWeight.SemiBold)}
                items(features.size){i->
                    val feature=features[i]
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween){
                        Text(feature,Modifier.weight(1f))
                        Checkbox(checked=selected.contains(feature),onCheckedChange={checked->selected=if(checked)selected+feature else selected-feature})
                    }
                }
                item{OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text("Your email")},singleLine=true)}
                item{OutlinedTextField(companyName,{companyName=it},Modifier.fillMaxWidth(),label={Text("Company name")},singleLine=true)}
                item{OutlinedTextField(companyEmail,{companyEmail=it},Modifier.fillMaxWidth(),label={Text("Company email")},singleLine=true)}
                item{OutlinedTextField(message,{message=it},Modifier.fillMaxWidth(),label={Text("Message / requirements")},minLines=3,maxLines=6)}
                error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
            }
        },
        confirmButton={
            Button(enabled=!sending && email.trim().contains("@") && message.trim().isNotEmpty(),onClick={
                sending=true; error=null
                scope.launch {
                    runCatching{WorkspaceApiClient().submitContactSales(email.trim(),companyName.trim(),companyEmail.trim(),planId,planName,selected.toList(),message.trim())}
                        .onSuccess{onSubmitted("Request sent. Our admin team can review it and contact you at $email.");onDismiss()}
                        .onFailure{error=it.message ?: "Could not send your request."}
                    sending=false
                }
            }){Text(if(sending)"Sending…" else "Send request")}
        },
        dismissButton={TextButton(enabled=!sending,onClick=onDismiss){Text("Cancel")}}
    )
}
