package com.kate.assistant.features.voice

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.*

class KateTts(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    private var onDoneCallback: (() -> Unit)? = null
    private var isSpeaking = false

    private val pendingQueue = mutableListOf<String>()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureVoice()
                ready = true
                setupListener()

                Log.d("KateTTS", "TTS ready")

                pendingQueue.forEach { speakInternal(it) }
                pendingQueue.clear()
            }
        }
    }

    // 🔥 MAIN SPEAK FUNCTION (WITH CALLBACK)
    fun speak(text: String, onDone: (() -> Unit)? = null) {

        if (!ready) {
            pendingQueue.add(text)
            return
        }

        onDoneCallback = onDone
        speakInternal(text)
    }

    private fun speakInternal(text: String) {

        isSpeaking = true

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }

        Log.d("KateTTS", "Speaking: $text")

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "KATE_${System.currentTimeMillis()}"
        )
    }

    // 🔥 REAL SPEECH END DETECTION
    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false

                Log.d("KateTTS", "Speech finished")

                onDoneCallback?.invoke()
                onDoneCallback = null
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
                onDoneCallback = null
            }
        })
    }

    fun isSpeaking(): Boolean = isSpeaking

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
