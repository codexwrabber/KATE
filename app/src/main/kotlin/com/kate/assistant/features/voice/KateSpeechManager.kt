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
    private var listenThread: Thread? = null

    private val handler      = Handler(Looper.getMainLooper())
    private val isRunning    = AtomicBoolean(false)
    private val isSpeaking   = AtomicBoolean(false)
    private val isModelReady = AtomicBoolean(false)

    // After Kate stops speaking, ignore VOSK results for this many ms.
    // TTS echo + room reverb lingers in the mic buffer even after onSpeechIdle.
    // Without this, VOSK processes the tail of Kate's own voice as a command.
    private val ECHO_COOLDOWN_MS = 800L
    @Volatile private var ignoredUntil = 0L

    // ── Soft noise floor gate ─────────────────────────────────
    // NOT the old hard gate (500 RMS) that cut speech onset and caused delay.
    // This low floor (150 RMS) only blocks pure silence and fan/room hum.
    // Real speech onset easily clears 150. Without it, VOSK accumulates
    // garbage context from constant background noise → random hallucinations.
    private val NOISE_FLOOR = 150.0

    init {
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            initModel()
        }.also { it.name = "vosk-init"; it.start() }
    }

    private fun initModel() {
        try {
            val modelDir = File(context.filesDir, "vosk-model")
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                Log.d(TAG, "Copying VOSK model from assets...")
                copyAssets("model", modelDir)
                Log.d(TAG, "Model copied ✅")
            }
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                onError?.invoke("VOSK model missing from assets")
                return
            }
            model      = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            isModelReady.set(true)
            Log.d(TAG, "✅ VOSK ready")
        } catch (e: Exception) {
            Log.e(TAG, "Model init failed: ${e.message}")
            onError?.invoke("Model failed: ${e.message}")
        }
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

    private fun rms(buf: ByteArray, len: Int): Double {
        var sum = 0.0; var i = 0
        while (i < len - 1) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            sum += s * s.toDouble(); i += 2
        }
        return kotlin.math.sqrt(sum / (len / 2))
    }

    fun startListening() {
        if (isRunning.get()) return
        if (!isModelReady.get()) {
            Log.w(TAG, "Model loading — retrying in 500ms")
            handler.postDelayed({ startListening() }, 500)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onError?.invoke("RECORD_AUDIO permission not granted")
            return
        }

        // minBuf sized buffer — smaller chunks = lower VOSK latency
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { onError?.invoke("Bad AudioRecord buffer size"); return }

        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4   // Internal ring buffer: 4x for stability, read chunk stays small
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); onError?.invoke("AudioRecord failed to init"); return
        }

        try { ar.startRecording() } catch (e: Exception) {
            ar.release(); onError?.invoke("Mic open failed: ${e.message}"); return
        }

        audioRecord = ar
        isRunning.set(true)

        listenThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            // Read in minBuf chunks — optimal for VOSK low-latency operation
            val buf = ByteArray(minBuf)
            Log.d(TAG, "🎤 Listening...")

            // Counts consecutive read failures — if AudioRecord dies silently,
            // this triggers a restart instead of looping forever as a zombie.
            var failStreak = 0

            while (isRunning.get()) {
                try {
                    if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Thread.sleep(20); continue
                    }

                    val read = ar.read(buf, 0, buf.size)

                    if (read <= 0) {
                        failStreak++
                        // 60 failures × 10ms = 600ms of dead audio → trigger restart
                        if (failStreak > 60) {
                            Log.e(TAG, "AudioRecord dead (failStreak=$failStreak) — signalling restart")
                            isRunning.set(false)
                            handler.post { onError?.invoke("AUDIO_DEAD") }
                            break
                        }
                        Thread.sleep(10); continue
                    }
                    failStreak = 0  // reset on any successful read

                    // Pause input while Kate is speaking
                    if (isSpeaking.get()) continue

                    // Soft noise floor — skip frames that are pure silence/hum
                    // (RMS < 150). Speech onset clears this easily.
                    if (rms(buf, read) < NOISE_FLOOR) continue

                    val isFinal = recognizer?.acceptWaveForm(buf, read) ?: false

                    if (isFinal) {
                        val text = JSONObject(recognizer?.result ?: "{}")
                            .optString("text", "").trim()
                        // Suppress results during echo cooldown window
                        if (System.currentTimeMillis() < ignoredUntil) continue
                        if (text.length > 2 && text != "[unk]" && text.isNotBlank()) {
                            Log.d(TAG, "✅ Final: $text")
                            handler.post { onResult(text) }
                        }
                    } else {
                        val partial = JSONObject(recognizer?.partialResult ?: "{}")
                            .optString("partial", "").trim()
                        // Also suppress wake word detection during cooldown
                        if (System.currentTimeMillis() < ignoredUntil) continue
                        if (partial.isNotBlank() && isWakeWord(partial)) {
                            Log.d(TAG, "🔔 Wake partial: $partial")
                            handler.post { onResult("WAKE") }
                            recognizer?.reset()
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "Listen loop error: ${e.message}")
                    Thread.sleep(30)
                }
            }
            Log.d(TAG, "Listen loop ended")
        }.also { it.name = "kate-listen"; it.start() }
    }

    private fun isWakeWord(t: String): Boolean {
        val lower = t.lowercase()
        return lower.contains("hey kate") ||
               lower.contains("hi kate") ||
               lower.contains("okay kate") ||
               lower.contains("ok kate") ||
               lower.contains("hey cat") ||
               lower.startsWith("kate ")
    }

    fun setSpeaking(state: Boolean) {
        isSpeaking.set(state)
        if (!state) {
            // Set the cooldown window — ignore all VOSK results for 800ms
            // to let room echo die completely before we process speech again
            ignoredUntil = System.currentTimeMillis() + ECHO_COOLDOWN_MS
            recognizer?.reset()
            Log.d(TAG, "Mic resumed — echo cooldown active for ${ECHO_COOLDOWN_MS}ms")
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
        Log.d(TAG, "Listening stopped")
    }

    fun shutdown() {
        stopListening()
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() }      catch (_: Exception) {}
        recognizer = null
        model = null
        isModelReady.set(false)
    }

    companion object {
        private const val TAG         = "KateSpeech"
        private const val SAMPLE_RATE = 16000
    }
}
