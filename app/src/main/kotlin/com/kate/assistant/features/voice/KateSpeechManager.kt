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

/**
 * Manages continuous offline speech recognition using VOSK.
 *
 * Key design decisions:
 *
 * 1. NO hard energy gate — the old 500 RMS gate cut the first ~200ms of every
 *    utterance (attack phase), causing the "delay" bug. Removed completely.
 *
 * 2. Soft noise floor at 150 RMS — filters pure silence / fan hum only.
 *    Real speech onset easily clears 150. Prevents VOSK from accumulating
 *    noise context → random word hallucinations.
 *
 * 3. Dynamic grammar — restricts VOSK vocabulary to Kate's command space.
 *    Background speech or noise that doesn't match any command → "[unk]" → discarded.
 *    This alone cuts false-positive commands dramatically.
 *
 * 4. Model path injection — ModelManager passes in the best available model
 *    (small fallback or upgraded daanzu model) without touching this class.
 *
 * 5. Echo cooldown — 800ms window after TTS finishes ignores VOSK results
 *    so room reverb can't trigger random commands.
 *
 * 6. AudioRecord dead-detection — after 60 consecutive bad reads, fires
 *    onError("AUDIO_DEAD") so KateServices can restart the mic.
 */
class KateSpeechManager(
    private val context:  Context,
    private val modelPath: String,
    private val onResult: (String) -> Unit,
    private val onError:  ((String) -> Unit)? = null
) {
    private var model:      Model?      = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listenThread: Thread?   = null

    private val handler      = Handler(Looper.getMainLooper())
    private val isRunning    = AtomicBoolean(false)
    private val isSpeaking   = AtomicBoolean(false)
    private val isModelReady = AtomicBoolean(false)

    // Dynamic grammar — JSON array string of phrases Kate understands.
    // Set via updateGrammar() before or after startListening().
    @Volatile private var grammar: String? = null

    // Noise floor: filters silence/hum but never cuts real speech onset
    private val NOISE_FLOOR = 150.0

    // Echo cooldown: ignore VOSK results for 800ms after Kate stops speaking
    private val ECHO_COOLDOWN_MS = 800L
    @Volatile private var ignoredUntil = 0L

    init {
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            initModel()
        }.also { it.name = "vosk-init"; it.start() }
    }

    // ── Model initialisation ───────────────────────────────────

    private fun initModel() {
        try {
            val dir = File(modelPath)
            if (!dir.exists() || dir.listFiles().isNullOrEmpty()) {
                // Model not present at this path — try copying from assets as fallback
                val assetFallback = File(context.filesDir, "vosk-model")
                if (!assetFallback.exists() || assetFallback.listFiles().isNullOrEmpty()) {
                    Log.d(TAG, "Copying small model from assets...")
                    copyAssets("model", assetFallback)
                }
                if (assetFallback.exists() && assetFallback.listFiles()?.isNotEmpty() == true) {
                    model = Model(assetFallback.absolutePath)
                } else {
                    onError?.invoke("VOSK model missing — place model files in assets/model/")
                    return
                }
            } else {
                model = Model(dir.absolutePath)
            }

            rebuildRecognizer()
            isModelReady.set(true)
            Log.d(TAG, "✅ VOSK ready — model: $modelPath")
        } catch (e: Exception) {
            Log.e(TAG, "Model init failed: ${e.message}")
            onError?.invoke("Model failed: ${e.message}")
        }
    }

    /** Recreates the recognizer with the current grammar (or no grammar if null). */
    private fun rebuildRecognizer() {
        try {
            recognizer?.close()
            val gram = grammar
            recognizer = if (!gram.isNullOrBlank()) {
                Log.d(TAG, "Using grammar recognizer (${gram.length} chars)")
                Recognizer(model, SAMPLE_RATE.toFloat(), gram)
            } else {
                Recognizer(model, SAMPLE_RATE.toFloat())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recognizer build failed: ${e.message}")
        }
    }

    // ── Grammar management ─────────────────────────────────────

    /**
     * Update VOSK's vocabulary. Call this from KateServices whenever
     * the installed app list or contacts change.
     *
     * VOSK will only try to match these phrases. Anything else → "[unk]".
     * This massively cuts false positives from background noise.
     */
    fun updateGrammar(phrases: List<String>) {
        if (phrases.isEmpty()) return
        // Build JSON array — always include [unk] so unrecognised input doesn't vanish
        val all = phrases.toMutableList()
        if (!all.contains("[unk]")) all.add("[unk]")

        // Sanitise: VOSK grammar phrases must be lowercase
        val sanitised = all.map { it.lowercase().trim() }.distinct()
        grammar = org.json.JSONArray(sanitised).toString()

        Log.d(TAG, "Grammar updated — ${sanitised.size} phrases")

        // Rebuild recognizer on the listen thread if already running,
        // or it will be picked up automatically when startListening() is next called
        if (isModelReady.get()) {
            handler.post {
                val wasRunning = isRunning.get()
                if (wasRunning) {
                    // Briefly stop reading, rebuild, resume — seamless
                    synchronized(this) { rebuildRecognizer() }
                } else {
                    rebuildRecognizer()
                }
            }
        }
    }

    /** Reinitialise with a new model path (called by ModelManager when upgrade lands). */
    fun switchModel(newModelPath: String) {
        Log.d(TAG, "Switching to upgraded model: $newModelPath")
        isModelReady.set(false)
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val newModel = Model(newModelPath)
                val old = model
                model = newModel
                rebuildRecognizer()
                old?.close()
                isModelReady.set(true)
                Log.d(TAG, "✅ Model switched to daanzu")
            } catch (e: Exception) {
                Log.e(TAG, "Model switch failed: ${e.message}")
                isModelReady.set(true)  // keep running on old model
            }
        }.also { it.name = "vosk-switch"; it.start() }
    }

    // ── Listening ──────────────────────────────────────────────

    fun startListening() {
        if (isRunning.get()) return
        if (!isModelReady.get()) {
            Log.w(TAG, "Model loading — retrying in 500ms")
            handler.postDelayed({ startListening() }, 500)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onError?.invoke("RECORD_AUDIO permission not granted"); return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { onError?.invoke("Bad AudioRecord buffer size"); return }

        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); onError?.invoke("AudioRecord init failed"); return
        }
        try { ar.startRecording() } catch (e: Exception) {
            ar.release(); onError?.invoke("Mic open failed: ${e.message}"); return
        }

        audioRecord = ar
        isRunning.set(true)

        listenThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf        = ByteArray(minBuf)
            var failStreak = 0
            Log.d(TAG, "🎤 Listening... (model: $modelPath)")

            while (isRunning.get()) {
                try {
                    if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Thread.sleep(20); continue
                    }

                    val read = ar.read(buf, 0, buf.size)

                    // ── Dead mic detection ─────────────────────
                    if (read <= 0) {
                        if (++failStreak > 60) {
                            Log.e(TAG, "AudioRecord dead — triggering restart")
                            isRunning.set(false)
                            handler.post { onError?.invoke("AUDIO_DEAD") }
                            break
                        }
                        Thread.sleep(10); continue
                    }
                    failStreak = 0

                    // Pause VOSK while Kate is speaking
                    if (isSpeaking.get()) continue

                    // ── Soft noise floor ───────────────────────
                    if (rms(buf, read) < NOISE_FLOOR) continue

                    val isFinal = recognizer?.acceptWaveForm(buf, read) ?: false

                    if (isFinal) {
                        val text = JSONObject(recognizer?.result ?: "{}")
                            .optString("text", "").trim()

                        if (System.currentTimeMillis() < ignoredUntil) continue
                        if (text == "[unk]" || text.length <= 2 || text.isBlank()) continue

                        Log.d(TAG, "✅ Final: \"$text\"")
                        handler.post { onResult(text) }

                    } else {
                        val partial = JSONObject(recognizer?.partialResult ?: "{}")
                            .optString("partial", "").trim()

                        if (System.currentTimeMillis() < ignoredUntil) continue
                        if (partial.isBlank() || partial == "[unk]") continue

                        // Early wake word detection from partials (~300ms faster response)
                        if (isWakeWord(partial)) {
                            Log.d(TAG, "🔔 Wake partial: \"$partial\"")
                            handler.post { onResult("WAKE") }
                            recognizer?.reset()
                        }
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "Listen loop: ${e.message}")
                    Thread.sleep(30)
                }
            }
            Log.d(TAG, "Listen loop ended")
        }.also { it.name = "kate-listen"; it.start() }
    }

    private fun isWakeWord(t: String): Boolean {
        val l = t.lowercase()
        return l.contains("hey kate") || l.contains("hi kate") ||
               l.contains("okay kate") || l.contains("ok kate") ||
               l.contains("hey cat") || l.startsWith("kate ")
    }

    fun setSpeaking(state: Boolean) {
        isSpeaking.set(state)
        if (!state) {
            ignoredUntil = System.currentTimeMillis() + ECHO_COOLDOWN_MS
            recognizer?.reset()
            Log.d(TAG, "Mic resumed — echo cooldown ${ECHO_COOLDOWN_MS}ms")
        } else {
            Log.d(TAG, "Mic paused — Kate speaking")
        }
    }

    fun isListening(): Boolean = isRunning.get()
    fun isSpeaking(): Boolean  = isSpeaking.get()

    fun stopListening() {
        isRunning.set(false)
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        listenThread?.interrupt()
        listenThread = null
    }

    fun shutdown() {
        stopListening()
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() }      catch (_: Exception) {}
        recognizer = null
        model      = null
        isModelReady.set(false)
    }

    // ── Utilities ──────────────────────────────────────────────

    private fun rms(buf: ByteArray, len: Int): Double {
        var sum = 0.0; var i = 0
        while (i < len - 1) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            sum += s * s.toDouble(); i += 2
        }
        return kotlin.math.sqrt(sum / (len / 2))
    }

    private fun copyAssets(src: String, dst: File) {
        dst.mkdirs()
        val items = context.assets.list(src) ?: return
        for (item in items) {
            val srcPath = "$src/$item"
            val dstFile = File(dst, item)
            if (!context.assets.list(srcPath).isNullOrEmpty()) {
                copyAssets(srcPath, dstFile)
            } else {
                context.assets.open(srcPath).use { i ->
                    FileOutputStream(dstFile).use { o -> i.copyTo(o) }
                }
            }
        }
    }

    companion object {
        private const val TAG         = "KateSpeech"
        private const val SAMPLE_RATE = 16000
    }
}
