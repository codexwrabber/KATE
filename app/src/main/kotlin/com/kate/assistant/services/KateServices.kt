package com.kate.assistant.services

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kate.assistant.bridge.*
import com.kate.assistant.features.voice.KateSpeechManager
import com.kate.assistant.features.voice.KateTts
import kotlinx.coroutines.*

class KateService : Service() {

    private lateinit var speechManager: KateSpeechManager
    private lateinit var tts: KateTts

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val CHANNEL_ID = "kate_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceSafe()

        tts = KateTts(this)

        speechManager = KateSpeechManager(this) { text ->
            Log.d("Kate", "Heard: $text")

            // 🔴 STOP listening immediately
            speechManager.stopListening()

            // 🔴 Move processing off main thread
            scope.launch(Dispatchers.Default) {
                handleVoiceCommand(text)
            }
        }

        // 🔴 Start flow safely
        scope.launch {
            speakAndResume("Kate is ready.")
        }
    }

    // ✅ SAFE SPEAK + RESUME
    private suspend fun speakAndResume(text: String) {
        withContext(Dispatchers.Main) {
            tts.speak(text)
        }

        delay(1500) // allow speech to finish (simple safe approach)

        speechManager.startListening()
    }

    // ✅ BACKGROUND COMMAND HANDLER
    private suspend fun handleVoiceCommand(text: String) {
        val lower = text.lowercase().trim()

        when {
            lower.contains("hello") -> {
                speakAndResume("Hello, how can I help?")
            }

            lower.contains("time") -> {
                val time = java.text.SimpleDateFormat(
                    "h:mm a",
                    java.util.Locale.getDefault()
                ).format(java.util.Date())

                speakAndResume("It is $time")
            }

            lower.contains("stop") -> {
                withContext(Dispatchers.Main) {
                    tts.speak("Goodbye")
                }
                speechManager.stopListening()
            }

            else -> {
                speakAndResume("You said $text")
            }
        }
    }

    override fun onDestroy() {
        speechManager.stopListening()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ✅ FOREGROUND SERVICE
    private fun startForegroundServiceSafe() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kate Assistant",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kate is running")
            .setContentText("Listening...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1, notification)
    }
}
