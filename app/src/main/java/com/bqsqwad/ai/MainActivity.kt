package com.bqsqwad.ai

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import com.bqsqwad.ai.ui.BQSqwadApp
import com.bqsqwad.ai.ui.theme.BQSqwadTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BQSqwadTheme { BQSqwadApp(Modifier) }
        }
    }
}
