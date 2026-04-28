package com.kate.assistant.services

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
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

            when (text) {

                // 🔥 WAKE WORD EVENT
                "WAKE_WORD" -> {
                    Log.d("Kate", "Wake word detected")

                    tts.speak("Yes?") {
                        // switch back to idle listening
                        speechManager.activateListening()
                    }
                }

                // 🔥 NORMAL COMMAND FLOW
                else -> {
                    Log.d("Kate", "Command: $text")

                    scope.launch(Dispatchers.Default) {
                        handleVoiceCommand(text)
                    }
                }
            }
        }

        // 🔥 START ALWAYS-ON LISTENER
        speechManager.startListening()

        // 🔥 BOOT SPEECH
        tts.speak("Kate is online")
    }

    // ─────────────────────────────
    // COMMAND HANDLER
    // ─────────────────────────────
    private suspend fun handleVoiceCommand(text: String) {

        val lower = text.lowercase().trim()

        when {

            lower.contains("hello") -> {
                speak("Hello, how can I help?")
            }

            lower.contains("time") -> {
                val time = java.text.SimpleDateFormat(
                    "h:mm a",
                    java.util.Locale.getDefault()
                ).format(java.util.Date())

                speak("It is $time")
            }

            lower.contains("stop") -> {
                speak("Goodbye")
                speechManager.stopListening()
            }

            else -> {
                speak("You said $text")
            }
        }
    }

    // ─────────────────────────────
    // SAFE SPEAK WRAPPER
    // ─────────────────────────────
    private fun speak(text: String) {
        tts.speak(text)
    }

    // ─────────────────────────────
    // FOREGROUND SERVICE
    // ─────────────────────────────
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
            .setContentText("Listening for wake word...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        speechManager.stopListening()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
