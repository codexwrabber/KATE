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
        // INIT SPEECH ENGINE (NO DELAY START)
        // ─────────────────────────────
        speechManager = KateSpeechManager(
            context = this,
            onResult = { text -> handleSpeech(text) },
            onProactive = { suggestion -> handleProactive(suggestion) }
        )

        // 🔥 CRITICAL FIX: START MIC FIRST
        speechManager.startListening()

        // 🔥 THEN SPEAK (NON-BLOCKING)
        Handler(Looper.getMainLooper()).postDelayed({
            speechManager.setSpeaking(true)

            tts.speak("Kate is online")

            Handler(Looper.getMainLooper()).postDelayed({
                speechManager.setSpeaking(false)
            }, 1200)

        }, 600)
    }

    // ─────────────────────────────
    // SPEECH RESULT HANDLER
    // ─────────────────────────────
    private fun handleSpeech(text: String) {

        when (text) {

            "WAKE" -> {
                Log.d("Kate", "Wake detected")

                speechManager.setSpeaking(true)
                tts.speak("Yes?")

                Handler(Looper.getMainLooper()).postDelayed({
                    speechManager.setSpeaking(false)
                }, 800)
            }

            else -> {
                scope.launch {
                    handleVoiceCommand(text)
                }
            }
        }
    }

    // ─────────────────────────────
    // PROACTIVE RESPONSE
    // ─────────────────────────────
    private fun handleProactive(text: String) {

        Log.d("Kate", "Proactive: $text")

        speechManager.setSpeaking(true)
        tts.speak(text)

        Handler(Looper.getMainLooper()).postDelayed({
            speechManager.setSpeaking(false)
        }, 1200)
    }

    // ─────────────────────────────
    // COMMAND LOGIC
    // ─────────────────────────────
    private suspend fun handleVoiceCommand(text: String) {

        val lower = text.lowercase().trim()

        when {

            lower.contains("hello") -> speak("Hello, how can I help?")

            lower.contains("time") -> {
                val time = java.text.SimpleDateFormat(
                    "h:mm a",
                    java.util.Locale.getDefault()
                ).format(java.util.Date())

                speak("It is $time")
            }

            lower.contains("stop") -> {
                speak("Stopping")
                speechManager.stopListening()
            }

            else -> speak("You said $text")
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
            .setContentText("Always listening...")
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
