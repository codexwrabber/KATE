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

    // ✅ FIXED: Only one initModel function remains
    private fun initModel() {
        try {
            val modelDir = File(context.filesDir, "vosk-model")
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                Log.d("KateSpeech", "Copying model from assets...")
                copyAssets("model", modelDir)
            }
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                onError?.invoke("Model not found"); return
            }

            model = Model(modelDir.absolutePath)

            val grammar = """
                ["hey kate", "okay kate",
                 "open", "launch", "close",
                 "call", "dial", "text", "send message to",
                 "play music", "music", "youtube", "spotify",
                 "search for", "google", "navigate to",
                 "torch on", "torch off", "flashlight on", "flashlight off",
                 "turn on torch", "turn off torch",
                 "volume up", "volume down", "mute", "unmute",
                 "do not disturb on", "do not disturb off", "silence",
                 "go back", "go home", "recent apps",
                 "show notifications", "take screenshot",
                 "read screen", "type", "write",
                 "what time", "what date", "today's date",
                 "hello", "hi kate", "how are you",
                 "what can you do", "help",
                 "open browser", "open chrome", "open whatsapp",
                 "open instagram", "open facebook", "open settings",
                 "stop listening", "goodbye kate", "bye kate",
                 "remind me", "set reminder", "set alarm",
                 "weather", "news",
                 "[unk]"]
            """.trimIndent()

            recognizer = Recognizer(model, 16000.0f, grammar)
            isReady.set(true)
            Log.d("KateSpeech", "✅ VOSK model + grammar loaded")

        } catch (e: Exception) {
            Log.w("KateSpeech", "Grammar failed, loading without: ${e.message}")
            try {
                recognizer = Recognizer(model, 16000.0f)
                isReady.set(true)
                Log.d("KateSpeech", "✅ VOSK model loaded (no grammar)")
            } catch (e2: Exception) {
                onError?.invoke("Model init failed: ${e2.message}")
            }
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
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4
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

            while (isRunning.get()) {
                try {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read <= 0 || isSpeaking.get()) continue

                    val accepted = recognizer?.acceptWaveForm(buffer, read) ?: false

                    if (accepted) {
                        val text = JSONObject(recognizer?.result ?: "{}")
                            .optString("text", "")
                            .lowercase().trim()

                        if (text.length > 2) handleResult(text)
                    } else {
                        val partial = JSONObject(recognizer?.partialResult ?: "{}")
                            .optString("partial", "")
                            .lowercase().trim()

                        if ((partial.contains("hey kate") ||
                             partial.contains("hey cat") ||
                             partial.contains("okay kate")) && !wakeMode.get()) {
                            wakeMode.set(true)
                            onResult("WAKE")
                        }
                    }

                    resetWatchdog()

                } catch (e: Exception) {
                    Thread.sleep(200)
                }
            }
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

    private fun startWatchdog() {
        watchdogRunnable = Runnable {
            if (isRunning.get()) restartListening()
        }
        resetWatchdog()
    }

    private fun resetWatchdog() {
        watchdogRunnable?.let {
            watchdogHandler.removeCallbacks(it)
            watchdogHandler.postDelayed(it, 30000)
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
        if (!state) resetWatchdog()
    }

    fun isListening(): Boolean = isRunning.get()
    fun isSpeaking(): Boolean  = isSpeaking.get()

    fun stopListening() {
        isRunning.set(false)
        wakeMode.set(false)
        stopWatchdog()
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { thread?.join(1000) } catch (_: InterruptedException) { thread?.interrupt() }
        thread = null
    }

    fun shutdown() {
        stopListening()
        try { recognizer?.close(); model?.close() } catch (_: Exception) {}
        recognizer = null
        model = null
        isReady.set(false)
    }
}
