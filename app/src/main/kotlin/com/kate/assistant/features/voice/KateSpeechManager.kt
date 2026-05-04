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
import kotlin.math.sqrt

class KateSpeechManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: ((String) -> Unit)? = null
) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null

    private val isRunning  = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    private val isReady    = AtomicBoolean(false)

    // Energy threshold — ignore low-energy background noise
    private val ENERGY_THRESHOLD = 800.0

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
                onError?.invoke("Model not found"); return
            }

            model = Model(modelDir.absolutePath)

            // Grammar with Nigerian English pronunciation variants
            // [unk] catches everything outside this list
            val grammar = buildGrammar()

            recognizer = try {
                Recognizer(model, 16000.0f, grammar)
            } catch (e: Exception) {
                Log.w("KateSpeech", "Grammar not supported, loading without: ${e.message}")
                Recognizer(model, 16000.0f)
            }

            isReady.set(true)
            Log.d("KateSpeech", "✅ Model loaded")
        } catch (e: Exception) {
            Log.e("KateSpeech", "Model init failed: ${e.message}")
            onError?.invoke("Model failed: ${e.message}")
        }
    }

    private fun buildGrammar(): String {
        // Include Nigerian English alternates and common mishears
        return """[
            "hey kate", "hey cat", "okay kate", "a kate", "kate",
            "open", "launch", "start", "run",
            "close", "exit", "quit",
            "call", "dial", "phone", "ring",
            "text", "message", "sms", "send message to",
            "whatsapp", "instagram", "facebook", "twitter",
            "youtube", "spotify", "chrome", "browser",
            "settings", "camera", "gallery", "calculator",
            "maps", "navigate", "directions",
            "play music", "play songs", "music", "pause", "stop music",
            "search for", "google", "search",
            "flashlight on", "flashlight off", "torch on", "torch off",
            "turn on flashlight", "turn off flashlight",
            "turn on torch", "turn off torch",
            "volume up", "volume down", "increase volume", "decrease volume",
            "mute", "unmute", "silent",
            "do not disturb on", "do not disturb off",
            "silence", "vibrate",
            "remind me", "set reminder", "set alarm",
            "remind me in", "minutes", "hours",
            "open browser", "open chrome",
            "go back", "go home", "home",
            "recent apps", "show recents",
            "show notifications", "open notifications",
            "take screenshot", "screenshot",
            "read screen", "what is on screen",
            "type", "write",
            "hello", "hi kate", "hi", "good morning", "good afternoon",
            "how are you", "what can you do", "help",
            "who are you", "your name",
            "what time", "current time", "time",
            "what date", "today date", "what day", "date",
            "stop listening", "goodbye kate", "bye kate", "bye",
            "[unk]"
        ]"""
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
                    FileOutputStream(dst).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    // ── Calculate RMS energy to filter noise ─────────────────
    private fun calculateEnergy(buffer: ByteArray, read: Int): Double {
        var sum = 0.0
        var i   = 0
        while (i < read - 1) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sum += sample.toDouble() * sample.toDouble()
            i   += 2
        }
        return sqrt(sum / (read / 2))
    }

    fun startListening() {
        if (isRunning.get()) return

        if (!isReady.get()) {
            Log.w("KateSpeech", "Not ready — retrying in 1s")
            Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 1000)
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onError?.invoke("RECORD_AUDIO not granted"); return
        }

        val sampleRate = 16000
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2,
            4096
        )

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onError?.invoke("AudioRecord init failed"); return
        }

        audioRecord = record

        try {
            audioRecord?.startRecording()
            Log.d("KateSpeech", "🎤 Mic started")
        } catch (e: Exception) {
            onError?.invoke("Mic start failed: ${e.message}"); return
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
                        Thread.sleep(100); continue
                    }

                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read <= 0) continue

                    // Skip while Kate is speaking
                    if (isSpeaking.get()) continue

                    // Energy gate — ignore low energy noise
                    val energy = calculateEnergy(buffer, read)
                    if (energy < ENERGY_THRESHOLD) continue

                    val accepted = recognizer?.acceptWaveForm(buffer, read) ?: false

                    if (accepted) {
                        val json = recognizer?.result ?: "{}"
                        val text = JSONObject(json).optString("text", "").trim()

                        // Skip [unk], empty, or very short results
                        if (text.isNotBlank() && text != "[unk]" && text.length > 2) {
                            Log.d("KateSpeech", "✅ Final: $text")
                            handleResult(text)
                        }
                    } else {
                        val json    = recognizer?.partialResult ?: "{}"
                        val partial = JSONObject(json).optString("partial", "").trim()

                        // Wake word on partial for fast response
                        if (partial.isNotBlank() && partial != "[unk]") {
                            Log.d("KateSpeech", "Partial: $partial")
                            if (partial.contains("hey kate") ||
                                partial.contains("hey cat")  ||
                                partial.contains("okay kate")) {
                                Log.d("KateSpeech", "🔔 Wake word partial: $partial")
                                onResult("WAKE")
                            }
                        }
                    }

                    resetWatchdog()

                } catch (e: Exception) {
                    Log.e("KateSpeech", "Loop error: ${e.message}")
                    Thread.sleep(100)
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
                Log.d("KateSpeech", "Wake word in result: $text")
                onResult("WAKE")
            }
            else -> onResult(text)
        }
    }

    // ── Watchdog — restarts if mic dies ──────────────────────
    private fun startWatchdog() {
        watchdogRunnable = Runnable {
            if (isRunning.get()) {
                Log.w("KateSpeech", "Watchdog: restarting mic")
                val wasRunning = isRunning.get()
                stopListening()
                if (wasRunning) {
                    Thread.sleep(500)
                    startListening()
                }
            }
        }
        resetWatchdog()
    }

    private fun resetWatchdog() {
        watchdogRunnable?.let {
            watchdogHandler.removeCallbacks(it)
            watchdogHandler.postDelayed(it, 60_000L) // restart if no audio for 60s
        }
    }

    private fun stopWatchdog() {
        watchdogRunnable?.let { watchdogHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    fun setSpeaking(state: Boolean) {
        isSpeaking.set(state)
        if (!state) resetWatchdog()
        Log.d("KateSpeech", "Speaking: $state")
    }

    fun isListening(): Boolean = isRunning.get()
    fun isSpeaking(): Boolean  = isSpeaking.get()

    fun stopListening() {
        isRunning.set(false)
        stopWatchdog()
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
        isReady.set(false)
    }
}
