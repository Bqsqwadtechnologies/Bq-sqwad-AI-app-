package com.bqsqwad.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import com.bqsqwad.ai.ui.BQSqwadApp
import com.bqsqwad.ai.ui.theme.BQSqwadTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeFirebase()
        setContent {
            BQSqwadTheme { BQSqwadApp(Modifier) }
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
