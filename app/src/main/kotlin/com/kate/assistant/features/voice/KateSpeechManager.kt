package com.kate.assistant.features.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
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
    // INIT MODEL
    // ─────────────────────────────
    private fun initModel() {
        try {
            val modelDir = File(context.filesDir, "vosk-model")

            if (!modelDir.exists()) {
                Log.e("KateSpeech", "❌ Model not found")
                return
            }

            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, 16000.0f)

            Log.d("KateSpeech", "✅ Model loaded")
        } catch (e: Exception) {
            Log.e("KateSpeech", "❌ Model init failed: ${e.message}")
        }
    }

    // ─────────────────────────────
    // START LISTENING (AUTO RECOVERY)
    // ─────────────────────────────
    fun startListening() {

        if (isRunning.get()) return

        // 🔒 Permission check
        val permission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        )

        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.e("KateSpeech", "❌ RECORD_AUDIO not granted")
            return
        }

        if (recognizer == null) {
            Log.e("KateSpeech", "❌ Recognizer not ready")
            return
        }

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("KateSpeech", "❌ AudioRecord init failed")
            return
        }

        audioRecord = record

        try {
            audioRecord?.startRecording()
            Log.d("KateSpeech", "🎤 Mic started")
        } catch (e: Exception) {
            Log.e("KateSpeech", "❌ Mic start failed: ${e.message}")
            return
        }

        isRunning.set(true)

        thread = Thread {

            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val buffer = ByteArray(bufferSize)

            Log.d("KateSpeech", "🚀 Listening loop started")

            while (isRunning.get()) {

                try {

                    if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Log.e("KateSpeech", "⚠️ Mic stopped — recovering...")
                        recoverMic()
                        continue
                    }

                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read <= 0) continue

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

                        if (partial.contains("hey kate")) {
                            onResult("WAKE")
                        }
                    }

                } catch (e: Exception) {
                    Log.e("KateSpeech", "🔥 Loop crash: ${e.message}")
                    recoverMic()
                }
            }
        }

        thread?.start()
    }

    // ─────────────────────────────
    // AUTO RECOVERY
    // ─────────────────────────────
    private fun recoverMic() {
        stopListening()
        Thread.sleep(500)
        startListening()
    }

    // ─────────────────────────────
    // RESULT HANDLER
    // ─────────────────────────────
    private fun handleResult(text: String) {

        Log.d("KateSpeech", "Heard: $text")

        when {
            text.contains("hey kate") -> {
                wakeMode.set(true)
                onResult("WAKE")
            }

            wakeMode.get() -> {
                wakeMode.set(false)
                onResult(text)
            }
        }
    }

    // ─────────────────────────────
    // SPEAKING CONTROL
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
