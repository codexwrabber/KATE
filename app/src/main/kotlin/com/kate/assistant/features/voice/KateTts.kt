package com.kate.assistant.features.voice

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.*

class KateTts(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    // 🔴 Queue speech until ready
    private val pendingQueue = mutableListOf<String>()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureVoice()
                ready = true
                Log.d("KateTTS", "✅ TTS initialized")

                // 🔥 Speak any queued text
                pendingQueue.forEach { speakInternal(it) }
                pendingQueue.clear()
            } else {
                Log.e("KateTTS", "❌ TTS init failed")
            }
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        if (!ready) {
            Log.w("KateTTS", "TTS not ready, queueing: $text")
            pendingQueue.add(text)
            return
        }
        speakInternal(text, flush)
    }

    private fun speakInternal(text: String, flush: Boolean = true) {
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }

        Log.d("KateTTS", "🗣️ Speaking: $text")

        tts?.speak(text, mode, params, text.hashCode().toString())
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun configureVoice() {
        tts?.let { engine ->
            val voice: Voice? = engine.voices
                ?.firstOrNull {
                    it.locale.language == Locale.ENGLISH.language &&
                    !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
                }

            voice?.let { engine.voice = it }

            engine.setPitch(0.95f)
            engine.setSpeechRate(0.97f)
        }
    }
}
