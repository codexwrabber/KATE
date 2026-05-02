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
import java.io.FileOutputStream
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

    private val isRunning  = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    private val wakeMode   = AtomicBoolean(false)

    init {
        Thread { initModel() }.start()
    }

    // ── Model init — copies from assets if needed ────────────
    private fun initModel() {
        try {
            val modelDir = File(context.filesDir, "vosk-model")

            // Copy from assets if not already copied
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                Log.d("KateSpeech", "Copying model from assets...")
                copyAssets("model", modelDir)
            }

            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                val error = "Model not found at ${modelDir.absolutePath}"
                Log.e("KateSpeech", error)
                onError?.invoke(error)
                return
            }

            model      = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, 16000.0f)
            Log.d("KateSpeech", "✅ Model loaded successfully")

        } catch (e: Exception) {
            val error = "Model init failed: ${e.message}"
            Log.e("KateSpeech", error)
            onError?.invoke(error)
        }
    }

    private fun copyAssets(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val assets = context.assets.list(assetPath) ?: return
        for (asset in assets) {
            val src = "$assetPath/$asset"
            val dst = File(destDir, asset)
            val children = context.assets.list(src)
            if (!children.isNullOrEmpty()) {
                copyAssets(src, dst)
            } else {
                context.assets.open(src).use { input ->
                    FileOutputStream(dst).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        Log.d("KateSpeech", "Copied: $assetPath → ${destDir.absolutePath}")
    }

    // ── Start listening ──────────────────────────────────────
    fun startListening() {
        if (isRunning.get()) {
            Log.d("KateSpeech", "Already listening")
            return
        }

        val permission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            val error = "RECORD_AUDIO not granted"
            Log.e("KateSpeech", error)
            onError?.invoke(error)
            return
        }

        if (recognizer == null) {
            // Model still loading — retry in 1 second
            Log.w("KateSpeech", "Recognizer not ready — retrying in 1s")
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ startListening() }, 1000)
            return
        }

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize <= 0) {
            val error = "Invalid buffer size: $bufferSize"
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
            Log.d("KateSpeech", "🎤 Mic started")
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
                        Log.e("KateSpeech", "Mic stopped — recovering...")
                        Thread.sleep(300)
                        continue
                    }

                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read <= 0) continue

                    // Skip audio while Kate is speaking — prevents self-hearing
                    if (isSpeaking.get()) continue

                    val result = recognizer?.acceptWaveForm(buffer, read) ?: false

                    if (result) {
                        val text = JSONObject(recognizer?.result ?: "{}")
                            .optString("text")
                            .lowercase()
                            .trim()

                        // Only process results with meaningful content
                        if (text.length > 2) {
                            handleResult(text)
                        }
                    } else {
                        // Check partial for wake word
                        val partial = JSONObject(recognizer?.partialResult ?: "{}")
                            .optString("partial")
                            .lowercase()
                            .trim()

                        if (partial.contains("hey kate") ||
                            partial.contains("hey cat") ||  // common VOSK mishear
                            partial.contains("kate")) {
                            Log.d("KateSpeech", "Wake word in partial: $partial")
                            if (!wakeMode.get()) {
                                wakeMode.set(true)
                                onResult("WAKE")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("KateSpeech", "Loop error: ${e.message}")
                    onError?.invoke("Loop error: ${e.message}")
                    Thread.sleep(300)
                }
            }
            Log.d("KateSpeech", "Listening loop ended")
        }

        thread?.start()
    }

    private fun handleResult(text: String) {
        Log.d("KateSpeech", "Heard: $text")

        when {
            // Wake word in final result
            text.contains("hey kate") ||
            text.contains("hey cat") -> {
                wakeMode.set(true)
                onResult("WAKE")
            }
            // Command after wake word
            wakeMode.get() -> {
                wakeMode.set(false)
                onResult(text)
            }
            // Direct command — no wake word required
            else -> {
                if (text.length > 2) onResult(text)
            }
        }
    }

    fun setSpeaking(state: Boolean) {
        isSpeaking.set(state)
        Log.d("KateSpeech", "Speaking: $state")
    }

    fun isListening(): Boolean = isRunning.get()
    fun isSpeaking(): Boolean  = isSpeaking.get()

    fun stopListening() {
        Log.d("KateSpeech", "Stopping...")
        isRunning.set(false)
        wakeMode.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("KateSpeech", "Stop error: ${e.message}")
        }
        audioRecord = null
        try { thread?.join(1000) } catch (e: InterruptedException) { thread?.interrupt() }
        thread = null
        Log.d("KateSpeech", "Stopped")
    }

    fun shutdown() {
        stopListening()
        try { recognizer?.close(); model?.close() } catch (e: Exception) { }
        recognizer = null
        model      = null
    }
}
