package com.kate.assistant.features.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

    fun startListening() {
        // Always run on main thread
        mainHandler.post {
            if (isListening) {
                Log.d("KateSpeech", "Already listening — skipping")
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e("KateSpeech", "Speech recognition NOT available!")
                return@post
            }

            try {
                Log.d("KateSpeech", "Creating recognizer on main thread...")
                recognizer?.destroy()
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)

                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        Log.d("KateSpeech", "✅ Ready — speak now!")
                    }
                    override fun onBeginningOfSpeech() {
                        Log.d("KateSpeech", "🎤 Speech detected!")
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        Log.d("KateSpeech", "🔇 End of speech")
                        isListening = false
                    }
                    override fun onError(error: Int) {
                        isListening = false
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO              -> "Audio error"
                            SpeechRecognizer.ERROR_CLIENT             -> "Client error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "No permission!"
                            SpeechRecognizer.ERROR_NETWORK            -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT    -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH           -> "No match found"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY    -> "Recognizer busy"
                            SpeechRecognizer.ERROR_SERVER             -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT     -> "Speech timeout"
                            else                                      -> "Unknown error $error"
                        }
                        Log.e("KateSpeech", "❌ Error: $msg (code $error)")
                    }
                    override fun onResults(results: Bundle) {
                        isListening = false
                        val matches = results
                            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        Log.d("KateSpeech", "✅ Results: $matches")
                        val text = matches?.firstOrNull() ?: return
                        onResult(text)
                    }
                    override fun onPartialResults(partial: Bundle?) {
                        val text = partial
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        Log.d("KateSpeech", "Partial: $text")
                    }
                    override fun onEvent(type: Int, params: Bundle?) {
                        Log.d("KateSpeech", "Event: $type")
                    }
                })

                Log.d("KateSpeech", "Starting listening...")
                recognizer?.startListening(buildIntent())

            } catch (e: Exception) {
                Log.e("KateSpeech", "Exception: ${e.message}")
                isListening = false
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                recognizer?.stopListening()
                recognizer?.destroy()
                recognizer  = null
                isListening = false
                Log.d("KateSpeech", "Stopped listening")
            } catch (e: Exception) {
                Log.e("KateSpeech", "Stop error: ${e.message}")
            }
        }
    }

    fun isActive() = isListening
