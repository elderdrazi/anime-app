package com.kumo.anime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kumo.anime.ui.KumoRoot
import com.kumo.anime.ui.theme.KumoTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** The latest intent that still needs handling (deep link or notification tap). */
    private val pending = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pending.value = intent
        setContent {
            KumoTheme {
                KumoRoot(pending = pending, onHandled = { pending.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pending.value = intent
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_INBOX = "inbox"
    }
}
