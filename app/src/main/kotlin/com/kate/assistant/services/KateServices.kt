package com.kate.assistant.features.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Handler
import android.os.Looper
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
    private var watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null

    private val isRunning  = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    private val wakeMode   = AtomicBoolean(false)
    private val isReady    = AtomicBoolean(false)

    init {
        Thread { initModel() }.start()
    }

    private fun initModel() {
        try {
            val modelDir = File(context.filesDir, "vosk-model")
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                Log.d("KateSpeech", "Copying model from assets...")
                copyAssets("model", modelDir)
            }
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                onError?.invoke("Model not found")
                return
            }
            model      = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, 16000.0f)
            isReady.set(true)
            Log.d("KateSpeech", "✅ Model loaded")
        } catch (e: Exception) {
            Log.e("KateSpeech", "Model init failed: ${e.message}")
            onError?.invoke("Model init failed: ${e.message}")
        }
    }

    private fun copyAssets(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val assets = context.assets.list(assetPath) ?: return
        for (asset in assets) {
            val src      = "$assetPath/$asset"
            val dst      = File(destDir, asset)
            val children = context.assets.list(src)
            if (!children.isNullOrEmpty()) {
                copyAssets(src, dst)
            } else {
                context.assets.open(src).use { input ->
                    FileOutputStream(dst).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    fun startListening() {
        if (isRunning.get()) return

        if (!isReady.get()) {
            Log.w("KateSpeech", "Model not ready — retrying in 1s")
            Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 1000)
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onError?.invoke("RECORD_AUDIO not granted")
            return
        }

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).takeIf { it > 0 } ?: run {
            onError?.invoke("Invalid buffer size")
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // better for speech
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4  // larger buffer = more stable
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onError?.invoke("AudioRecord init failed")
            return
        }

        audioRecord = record
        try {
            audioRecord?.startRecording()
            Log.d("KateSpeech", "🎤 Mic started")
        } catch (e: Exception) {
            onError?.invoke("Mic start failed: ${e.message}")
            return
        }

        isRunning.set(true)
        startWatchdog()

        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ByteArray(bufferSize)
            Log.d("KateSpeech", "Loop started")

            while (isRunning.get()) {
                try {
                    if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Thread.sleep(200)
                        continue
                    }

                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read <= 0) continue
                    if (isSpeaking.get()) continue

                    // Feed to VOSK
                    val accepted = recognizer?.acceptWaveForm(buffer, read) ?: false

                    if (accepted) {
                        val json = recognizer?.result ?: "{}"
                        val text = JSONObject(json)
                            .optString("text", "")
                            .lowercase().trim()
                        if (text.length > 2) {
                            Log.d("KateSpeech", "✅ Final: $text")
                            handleResult(text)
                        }
                    } else {
                        val json    = recognizer?.partialResult ?: "{}"
                        val partial = JSONObject(json)
                            .optString("partial", "")
                            .lowercase().trim()
                        if (partial.isNotBlank()) {
                            Log.d("KateSpeech", "Partial: $partial")
                        }
                        // Wake word on partial for fast response
                        if ((partial.contains("hey kate") ||
                             partial.contains("hey cat") ||
                             partial.contains("okay kate")) && !wakeMode.get()) {
                            Log.d("KateSpeech", "🔔 Wake word: $partial")
                            wakeMode.set(true)
                            onResult("WAKE")
                        }
                    }

                    // Reset watchdog — loop is alive
                    resetWatchdog()

                } catch (e: Exception) {
                    Log.e("KateSpeech", "Loop error: ${e.message}")
                    Thread.sleep(200)
                }
            }
            Log.d("KateSpeech", "Loop ended")
        }
        thread?.start()
    }

    private fun handleResult(text: String) {
        when {
            text.contains("hey kate") ||
            text.contains("hey cat")  ||
            text.contains("okay kate") -> {
                wakeMode.set(true)
                onResult("WAKE")
            }
            wakeMode.get() -> {
                wakeMode.set(false)
                onResult(text)
            }
            else -> onResult(text)
        }
    }

    // ── Watchdog — restarts mic if it goes silent ────────────
    private fun startWatchdog() {
        watchdogRunnable = Runnable {
            if (isRunning.get()) {
                Log.w("KateSpeech", "Watchdog triggered — restarting mic")
                restartListening()
            }
        }
        resetWatchdog()
    }

    private fun resetWatchdog() {
        watchdogRunnable?.let {
            watchdogHandler.removeCallbacks(it)
            watchdogHandler.postDelayed(it, 30000) // restart if silent for 30s
        }
    }

    private fun stopWatchdog() {
        watchdogRunnable?.let { watchdogHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    fun restartListening() {
        stopListening()
        Thread.sleep(300)
        startListening()
    }

    fun setSpeaking(state: Boolean) {
        isSpeaking.set(state)
        if (!state) resetWatchdog() // reset watchdog after speaking
        Log.d("KateSpeech", "Speaking: $state")
    }

    fun isListening(): Boolean = isRunning.get()
    fun isSpeaking(): Boolean  = isSpeaking.get()

    fun stopListening() {
        isRunning.set(false)
        wakeMode.set(false)
        stopWatchdog()
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) { }
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
        isReady.set(false)
    }
}
