package com.kate.assistant.features.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class KateTts(private val context: Context) {

    private var engine: TextToSpeech? = null
    private var ready  = false
    private val queue  = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())

    init { initEngine() }

    private fun initEngine() {
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureVoice()
                ready = true
                Log.d(TAG, "✅ TTS ready")
                // Drain any queued speech
                handler.post {
                    while (queue.isNotEmpty()) doSpeak(queue.removeFirst())
                }
            } else {
                Log.e(TAG, "TTS failed: $status — retrying in 2s")
                handler.postDelayed({ initEngine() }, 2000)
            }
        }
    }

    fun speak(text: String) {
        if (!ready) { queue.addLast(text); return }
        doSpeak(text)
    }

    private fun doSpeak(text: String) {
        engine?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    private fun configureVoice() {
        engine?.let { e ->
            val result = e.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                e.setLanguage(Locale.getDefault())
            }
            // Best offline voice — prefer female, lowest latency
            e.voices
                ?.filter { v ->
                    !v.isNetworkConnectionRequired &&
                    v.locale.language == "en" &&
                    !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }
                ?.minByOrNull { v ->
                    v.latency + (if (v.name.contains("female", true)) 0 else 500)
                }
                ?.let { e.voice = it; Log.d(TAG, "Voice: ${it.name}") }

            e.setPitch(0.95f)
            e.setSpeechRate(0.9f)
        }
    }

    fun shutdown() {
        engine?.stop(); engine?.shutdown()
        engine = null; ready = false; queue.clear()
    }

    companion object { private const val TAG = "KateTts" }
}
