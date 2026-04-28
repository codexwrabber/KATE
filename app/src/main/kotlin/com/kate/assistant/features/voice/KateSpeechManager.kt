package com.kate.assistant.features.voice

import android.content.Context
import android.media.*
import android.os.Process
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class KateSpeechManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onProactive: ((String) -> Unit)? = null
) {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null

    private val isRunning = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    private val wakeMode = AtomicBoolean(false)

    init {
        Thread { initModel() }.start()
    }

    // ─────────────────────────────
    // MODEL LOAD
    // ─────────────────────────────
    private fun initModel() {
        try {
            val modelDir = File(context.filesDir, "vosk-model")

            if (!modelDir.exists()) {
                Log.e("KateSpeech", "Model missing")
                return
            }

            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, 16000.0f)

            Log.d("KateSpeech", "Model ready")
        } catch (e: Exception) {
            Log.e("KateSpeech", "Model init failed: ${e.message}")
        }
    }

    // ─────────────────────────────
    // START ALWAYS-ON LISTENER
    // ─────────────────────────────
    fun startListening() {
        if (isRunning.get()) return
        if (recognizer == null) {
            Log.e("KateSpeech", "Recognizer not ready")
            return
        }

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        audioRecord?.startRecording()
        isRunning.set(true)

        thread = Thread {

            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val buffer = ByteArray(bufferSize)

            Log.d("KateSpeech", "🎤 Always listening...")

            while (isRunning.get()) {

                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read <= 0) continue

                // 🔥 CRITICAL: IGNORE WHILE SPEAKING
                if (isSpeaking.get()) continue

                val result = recognizer?.acceptWaveForm(buffer, read) ?: false

                if (result) {

                    val text = JSONObject(recognizer?.result ?: "{}")
                        .optString("text")
                        .lowercase()

                    if (text.isNotBlank()) {
                        handleResult(text)
                    }
                } else {

                    val partial = JSONObject(recognizer?.partialResult ?: "{}")
                        .optString("partial")
                        .lowercase()

                    // optional proactive trigger
                    if (partial.contains("weather") ||
                        partial.contains("remind") ||
                        partial.contains("open")) {

                        onProactive?.invoke("I'm listening...")
                    }
                }
            }
        }

        thread?.start()
    }

    // ─────────────────────────────
    // RESULT HANDLER
    // ─────────────────────────────
    private fun handleResult(text: String) {

        Log.d("KateSpeech", "Heard: $text")

        when {

            text.contains("hey kate") ||
            text.contains("wake") -> {
                wakeMode.set(true)
                onResult("WAKE")
            }

            wakeMode.get() -> {
                wakeMode.set(false)
                onResult(text)
            }

            else -> {
                // ignore background noise until wake
            }
        }
    }

    // ─────────────────────────────
    // TTS CONTROL (IMPORTANT FIX)
    // ─────────────────────────────
    fun setSpeaking(state: Boolean) {
        isSpeaking.set(state)
    }

    // ─────────────────────────────
    // STOP
    // ─────────────────────────────
    fun stopListening() {
        isRunning.set(false)

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}

        audioRecord = null
        thread = null
    }
}
