package com.kate.assistant.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.kate.assistant.bridge.KateEvent
import com.kate.assistant.bridge.KateEventBus
import com.kate.assistant.core.permissions.PermissionManager
import com.kate.assistant.services.KateService
import com.kate.assistant.ui.screens.HomeScreen
import com.kate.assistant.ui.theme.KateTheme

class MainActivity : ComponentActivity() {

    // Speech launcher — fires system speech dialog
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull()
            if (!text.isNullOrBlank()) {
                Log.d("Kate", "Heard: $text")
                KateEventBus.emitSpeech(text)
            }
        }
        // Re-launch after 1 second — continuous listening
        window.decorView.postDelayed({ startListening() }, 1000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PermissionManager.requestAll(this)
        PermissionManager.requestExactAlarm(this)

        val serviceIntent = Intent(this, KateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent { KateTheme { HomeScreen() } }

        // Start listening after 3 seconds
        window.decorView.postDelayed({ startListening() }, 3000)
    }

    private fun startListening() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Kate...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("Kate", "Speech launch error: ${e.message}")
        }
    }
}
