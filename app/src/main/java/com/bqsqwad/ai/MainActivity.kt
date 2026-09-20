package com.bqsqwad.ai

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import com.bqsqwad.ai.core.WorkspaceApiClient
import com.bqsqwad.ai.ui.BQSqwadApp
import com.bqsqwad.ai.ui.theme.BQSqwadTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeFirebase()
        setContent {
            BQSqwadTheme { BQSqwadApp(Modifier) }
        }
        handlePaymentCallback(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePaymentCallback(intent)
    }

    private fun handlePaymentCallback(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "bqsqwad" || uri.host != "paystack") return
        val reference = uri.getQueryParameter("reference") ?: uri.getQueryParameter("trxref") ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { WorkspaceApiClient().paymentVerify(reference) }
                .onSuccess { result ->
                    runOnUiThread { Toast.makeText(this@MainActivity, if (result.optBoolean("verified")) "Payment verified" else "Payment could not be verified", Toast.LENGTH_LONG).show() }
                }
                .onFailure { error ->
                    runOnUiThread { Toast.makeText(this@MainActivity, error.message ?: "Payment verification failed", Toast.LENGTH_LONG).show() }
                }
        }
    }

    private fun initializeFirebase() {
        if (FirebaseApp.getApps(this).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setApiKey("AIzaSyBcbFDXG6IsKatj8DjpCOQjJJ4DHv536eo")
            .setApplicationId("1:848359744340:android:11a35aa871b41ba8839ec1")
            .setProjectId("bqsqwadai")
            .setStorageBucket("bqsqwadai.firebasestorage.app")
            .build()
        FirebaseApp.initializeApp(this, options)
    }
}
