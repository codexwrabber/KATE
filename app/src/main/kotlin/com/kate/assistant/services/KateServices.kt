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

        // ─────────────────────────────
        // SPEECH ENGINE INIT (FIXED)
        // ─────────────────────────────
        speechManager = KateSpeechManager(
            context = this,

            // NORMAL SPEECH
            onResult = { text ->

                when (text) {

                    "WAKE" -> {
                        Log.d("Kate", "Wake word detected")

                        speechManager.setSpeaking(true)

                        tts.speak("Yes?")

                        // resume listening safely
                        Handler(Looper.getMainLooper()).postDelayed({
                            speechManager.setSpeaking(false)
                            speechManager.activateListening()
                        }, 900)
                    }

                    else -> {
                        Log.d("Kate", "Command: $text")

                        scope.launch(Dispatchers.Default) {
                            handleVoiceCommand(text)
                        }
                    }
                }
            },

            // ─────────────────────────────
            // PROACTIVE ENGINE (NEW FIX)
            // ─────────────────────────────
            onProactive = { suggestion ->
                Log.d("Kate", "Proactive: $suggestion")

                speechManager.setSpeaking(true)
                tts.speak(suggestion)

                Handler(Looper.getMainLooper()).postDelayed({
                    speechManager.setSpeaking(false)
                }, 1200)
            }
        )

        // START ENGINE
        speechManager.startListening()

        speechManager.setSpeaking(true)
        tts.speak("Kate is online")

        Handler(Looper.getMainLooper()).postDelayed({
            speechManager.setSpeaking(false)
        }, 1200)
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
        speechManager.setSpeaking(true)

        tts.speak(text)

        Handler(Looper.getMainLooper()).postDelayed({
            speechManager.setSpeaking(false)
        }, 1000)
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
