package com.kate.assistant.features.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class KateSpeechManager(
    private val context: Context,
    private val onResult: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    fun startListening() {
        if (isListening) return
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    isListening = false
                    val text = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    Log.d("KateSpeech", "Heard: $text")
                    onResult(text)
                }
                override fun onError(error: Int) {
                    isListening = false
                    Log.e("KateSpeech", "Error: $error")
                }
                override fun onReadyForSpeech(params: Bundle?)  { isListening = true }
                override fun onBeginningOfSpeech()              {}
                override fun onRmsChanged(rmsdB: Float)         {}
                override fun onBufferReceived(buffer: ByteArray?){}
                override fun onEndOfSpeech()                    {}
                override fun onPartialResults(partial: Bundle?) {}
                override fun onEvent(type: Int, params: Bundle?){}
            })
            recognizer?.startListening(buildIntent())
            Log.d("KateSpeech", "Listening started")
        } catch (e: Exception) {
            Log.e("KateSpeech", "Failed to start: ${e.message}")
            isListening = false
        }
    }

    fun stopListening() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
            isListening = false
        } catch (e: Exception) {
            Log.e("KateSpeech", "Failed to stop: ${e.message}")
        }
    }

    fun isActive() = isListening
}
