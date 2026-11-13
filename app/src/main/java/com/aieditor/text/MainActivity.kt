package com.aieditor.text

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.aieditor.text.ui.AppRoot
import com.aieditor.text.ui.AppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent
        val shared = if (incoming?.action == Intent.ACTION_SEND && incoming.type == "text/plain") {
            incoming.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(sharedText = shared)
                }
            }
        }
    }
}
