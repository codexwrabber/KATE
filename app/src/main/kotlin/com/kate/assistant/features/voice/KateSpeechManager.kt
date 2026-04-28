package com.kate.assistant.features.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class KateSpeechManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onProactive: (String) -> Unit
) {

    // ─────────────────────────────
    // ENGINE CORE
    // ─────────────────────────────
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null

    private val running = AtomicBoolean(false)

    // ─────────────────────────────
    // STATE MACHINE
    // ─────────────────────────────
    enum class Mode {
        IDLE,
        LISTENING,
        SPEAKING
    }

    @Volatile
    private var mode = Mode.IDLE

    // ─────────────────────────────
    // MEMORY SYSTEM (PROACTIVE CORE)
    // ─────────────────────────────
    private var lastCommand: String? = null
    private var lastIntent: String? = null

    private val commandHistory = mutableListOf<String>()

    // ─────────────────────────────
    // WAKE WORDS
    // ─────────────────────────────
    private val wakeWords = listOf(
        "hey kate",
        "kate",
        "ok kate",
        "hi kate"
    )

    init {
        Thread { initModel() }.start()
    }

    // ─────────────────────────────
    // LOAD MODEL
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
            Log.e("KateSpeech", "Init error: ${e.message}")
        }
    }

    // ─────────────────────────────
    // START LISTENING LOOP
    // ─────────────────────────────
    fun startListening() {
        if (running.get()) return
        if (recognizer == null) return

        val sampleRate = 16000

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        audioRecord?.startRecording()
        running.set(true)

        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val buffer = ByteArray(bufferSize)

            Log.d("KateSpeech", "Proactive mode active")

            while (running.get()) {

                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read <= 0) continue

                val final = recognizer?.acceptWaveForm(buffer, read) ?: false

                if (final) {
                    val text = JSONObject(recognizer?.result ?: "{}")
                        .optString("text")
                        .trim()

                    if (text.isNotBlank()) handleSpeech(text)
                }
            }
        }

        thread?.start()
    }

    // ─────────────────────────────
    // BRAIN CORE
    // ─────────────────────────────
    private fun handleSpeech(text: String) {

        val input = text.lowercase().trim()

        Log.d("KateSpeech", "Heard: $input | mode=$mode")

        when (mode) {

            Mode.IDLE -> {
                if (isWakeWord(input)) {
                    mode = Mode.LISTENING
                    onResult("WAKE")
                }
            }

            Mode.LISTENING -> {

                val resolved = resolveContext(input)

                lastCommand = resolved
                commandHistory.add(resolved)

                detectIntent(resolved)

                onResult(resolved)

                // 🔥 PROACTIVE ENGINE TRIGGER
                runProactiveEngine()
            }

            Mode.SPEAKING -> {}
        }
    }

    // ─────────────────────────────
    // CONTEXT ENGINE
    // ─────────────────────────────
    private fun resolveContext(text: String): String {
        return when {
            text == "that" -> lastCommand ?: text
            text.contains("again") -> lastCommand ?: text
            text.contains("same") -> lastCommand ?: text
            else -> text
        }
    }

    // ─────────────────────────────
    // INTENT DETECTION
    // ─────────────────────────────
    private fun detectIntent(text: String) {

        lastIntent = when {
            text.contains("open") -> "OPEN_APP"
            text.contains("play") -> "MEDIA"
            text.contains("call") -> "CALL"
            text.contains("message") -> "MESSAGE"
            else -> "GENERAL"
        }
    }

    // ─────────────────────────────
    // PROACTIVE ENGINE (🔥 MAIN UPGRADE)
    // ─────────────────────────────
    private fun runProactiveEngine() {

        if (commandHistory.size < 2) return

        val last = commandHistory.last()
        val previous = commandHistory.dropLast(1).last()

        var suggestion: String? = null

        when {

            // Pattern: user repeats same action
            last == previous -> {
                suggestion = "You just did this again. Want me to automate it next time?"
            }

            // Pattern: app usage loop
            last.contains("open") && previous.contains("open") -> {
                suggestion = "You often open apps back to back. Want quick shortcuts?"
            }

            // Pattern: media after launch
            last.contains("open") && previous.contains("play") -> {
                suggestion = "You usually play media after opening apps. Want auto-play mode?"
            }

            // Pattern: frequent repetition
            commandHistory.takeLast(5).count { it == last } >= 3 -> {
                suggestion = "You repeat this often. I can turn it into a shortcut."
            }
        }

        if (suggestion != null) {
            Log.d("KateSpeech", "Proactive: $suggestion")
            onProactive(suggestion)
        }
    }

    // ─────────────────────────────
    // WAKE WORD CHECK
    // ─────────────────────────────
    private fun isWakeWord(text: String): Boolean {
        return wakeWords.any { text.startsWith(it) }
    }

    // ─────────────────────────────
    // EXTERNAL CONTROL
    // ─────────────────────────────
    fun setSpeaking(active: Boolean) {
        mode = if (active) Mode.SPEAKING else Mode.IDLE
    }

    fun activateListening() {
        mode = Mode.IDLE
    }

    fun resetMemory() {
        lastCommand = null
        lastIntent = null
        commandHistory.clear()
    }

    fun stopListening() {
        running.set(false)

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}

        audioRecord = null
        thread = null
    }
}
