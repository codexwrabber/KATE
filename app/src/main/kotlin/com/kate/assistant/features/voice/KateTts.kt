package com.kate.assistant.features.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class KateTts(private val context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false

    // Queued text before engine is ready
    private val queue = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())

    // Tracks how many utterances are still pending/playing.
    // Only when this hits 0 do we signal the mic to reopen.
    private val pendingCount = AtomicInteger(0)

    // Called the moment the FIRST utterance in a batch starts (mic must close)
    var onSpeechActive: (() -> Unit)? = null

    // Called only when ALL queued utterances are DONE (mic can reopen)
    var onSpeechIdle: (() -> Unit)? = null

    init { initEngine() }

    private fun initEngine() {
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureVoice()
                attachProgressListener()
                ready = true
                Log.d(TAG, "✅ TTS ready")
                handler.post {
                    while (queue.isNotEmpty()) doSpeak(queue.removeFirst())
                }
            } else {
                Log.e(TAG, "TTS init failed ($status) — retry in 2s")
                handler.postDelayed({ initEngine() }, 2000)
            }
        }
    }

    private fun attachProgressListener() {
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String) {
                // Signal mic-close on first utterance of any batch
                handler.post { onSpeechActive?.invoke() }
            }

            override fun onDone(utteranceId: String) {
                val remaining = pendingCount.decrementAndGet()
                Log.d(TAG, "TTS done — $remaining in queue")
                if (remaining <= 0) {
                    pendingCount.set(0)
                    // 300ms buffer so room echo dies before mic opens
                    handler.postDelayed({ onSpeechIdle?.invoke() }, 300)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                Log.e(TAG, "TTS error — $utteranceId")
                val remaining = pendingCount.decrementAndGet()
                if (remaining <= 0) {
                    pendingCount.set(0)
                    handler.postDelayed({ onSpeechIdle?.invoke() }, 300)
                }
            }

            override fun onError(utteranceId: String, errorCode: Int) = onError(utteranceId)
        })
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        // Increment BEFORE queuing so count is never 0 mid-batch
        pendingCount.incrementAndGet()
        if (!ready) { queue.addLast(text); return }
        doSpeak(text)
    }

    private fun doSpeak(text: String) {
        val uid = "kate_${System.nanoTime()}"
        engine?.speak(text, TextToSpeech.QUEUE_ADD, null, uid)
    }

    private fun configureVoice() {
        engine?.let { e ->
            val langResult = e.setLanguage(Locale.US)
            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                e.setLanguage(Locale.getDefault())
            }

            // Priority: Google offline voice → any offline English female → any offline English
            val best = e.voices
                ?.filter { v ->
                    !v.isNetworkConnectionRequired &&
                    v.locale.language == "en" &&
                    !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }
                ?.minByOrNull { v ->
                    val googleBonus = if (v.name.contains("google", true)) -2000 else 0
                    val femaleBonus = if (v.name.contains("female", true) ||
                                         v.name.contains("en-us-x-iob", true) ||
                                         v.name.contains("en-us-x-tpf", true)) -1000 else 0
                    v.latency + googleBonus + femaleBonus
                }

            best?.let {
                e.voice = it
                Log.d(TAG, "Voice: ${it.name}  latency=${it.latency}")
            }

            e.setPitch(0.97f)
            e.setSpeechRate(0.92f)
        }
    }

    fun isSpeaking(): Boolean = pendingCount.get() > 0

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        queue.clear()
        pendingCount.set(0)
    }

    companion object { private const val TAG = "KateTts" }
}
