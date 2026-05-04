package com.kate.assistant.features.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

class KateTts(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureVoice()
                ready = true
                Log.d("KateTts", "✅ TTS ready")
                // Speak any queued messages
                handler.post {
                    queue.forEach { speak(it) }
                    queue.clear()
                }
            } else {
                Log.e("KateTts", "TTS init failed: $status")
                // Retry after 2 seconds
                handler.postDelayed({ initTts() }, 2000)
            }
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        if (!ready) {
            Log.w("KateTts", "TTS not ready — queuing: $text")
            queue.add(text)
            return
        }
        try {
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(text, mode, null, text.hashCode().toString())
            Log.d("KateTts", "Speaking: $text")
        } catch (e: Exception) {
            Log.e("KateTts", "Speak failed: ${e.message}")
        }
    }

    private fun configureVoice() {
        tts?.let { engine ->
            // Set language
            val result = engine.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("KateTts", "US English not supported — using default")
                engine.setLanguage(Locale.getDefault())
            }

            // Find best offline female voice
            val bestVoice = engine.voices
                ?.filter { v ->
                    v.locale.language == "en" &&
                    !v.isNetworkConnectionRequired &&
                    !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }
                ?.minByOrNull { v ->
                    // Prefer female, lower latency
                    var score = v.latency
                    if (!v.name.contains("female", true)) score += 1000
                    score
                }

            bestVoice?.let {
                engine.voice = it
                Log.d("KateTts", "Voice: ${it.name}")
            }

            engine.setPitch(0.95f)
            engine.setSpeechRate(0.92f)
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("KateTts", "Shutdown error: ${e.message}")
        }
        tts   = null
        ready = false
        queue.clear()
    }
}
