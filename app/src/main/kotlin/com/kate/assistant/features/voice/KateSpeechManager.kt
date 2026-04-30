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
    private val onError: ((String) -> Unit)? = null
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
                val error = "Model not found at ${modelDir.absolutePath}"
                Log.e("KateSpeech", error)
                onError?.invoke(error)
                return
            }

            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, 16000.0f)

            Log.d("KateSpeech", "Model loaded")
        } catch (e: Exception) {
            val error = "Model init failed: ${e.message}"
            Log.e("KateSpeech", error)
            onError?.invoke(error)
        }
    }

    // ─────────────────────────────
    // START LISTENING
    // ─────────────────────────────
    fun startListening() {
        if (isRunning.get()) {
            Log.d("KateSpeech", "Already listening")
            return
        }

        val permission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        )

        if (permission != PackageManager.PERMISSION_GRANTED) {
            val error = "RECORD_AUDIO not granted"
            Log.e("KateSpeech", error)
            onError?.invoke(error)
            return
        }

        if (recognizer == null) {
            val error = "Recognizer not ready"
            Log.e("KateSpeech", error)
            onError?.invoke(error)
            return
        }

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            val error = "Invalid buffer size"
            Log.e("KateSpeech", error)
            onError?.invoke(error)
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            val error = "AudioRecord init failed"
            Log.e("KateSpeech", error)
            onError?.invoke(error)
            return
        }

        audioRecord = record

        try {
            audioRecord?.startRecording()
            Log.d("KateSpeech", "Mic started")
        } catch (e: Exception) {
            val error = "Mic start failed: ${e.message}"
            Log.e("KateSpeech", error)
            onError?.invoke(error)
            return
        }

        isRunning.set(true)

        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ByteArray(bufferSize)
            Log.d("KateSpeech", "Listening loop started")

            while (isRunning.get()) {
                try {
                    if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Log.e("KateSpeech", "Mic stopped - recovering...")
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
                            .trim()

                        if (text.isNotBlank()) {
                            handleResult(text)
                        }
                    } else {
                        val partial = JSONObject(recognizer?.partialResult ?: "{}")
                            .optString("partial")
                            .lowercase()
                            .trim()

                        if (partial.contains("hey kate")) {
                            Log.d("KateSpeech", "Wake word detected: $partial")
                            onResult("WAKE")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("KateSpeech", "Loop crash: ${e.message}")
                    onError?.invoke("Loop crash: ${e.message}")
                    recoverMic()
                }
            }
        }

        thread?.start()
    }

    private fun recoverMic() {
        stopListening()
        Thread.sleep(500)
        startListening()
    }

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
            else -> {
                if (text.isNotBlank() && text.length > 3) {
                    onResult(text)
                }
            }
        }
    }

    fun setSpeaking(state: Boolean) {
        isSpeaking.set(state)
        Log.d("KateSpeech", "Speaking mode: $state")
    }

    fun isListening(): Boolean = isRunning.get()
    
    fun isSpeaking(): Boolean = isSpeaking.get()

    fun stopListening() {
        Log.d("KateSpeech", "Stopping listening...")
        isRunning.set(false)
        wakeMode.set(false)

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("KateSpeech", "Error stopping audio: ${e.message}")
        }

        audioRecord = null
        
        try {
            thread?.join(1000)
        } catch (e: InterruptedException) {
            thread?.interrupt()
        }
        
        thread = null
        Log.d("KateSpeech", "Listening stopped")
    }

    fun shutdown() {
        stopListening()
        try {
            recognizer?.close()
            model?.close()
        } catch (e: Exception) {
            Log.e("KateSpeech", "Error closing: ${e.message}")
        }
        recognizer = null
        model = null
    }
}
