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
    private var listenThread: Thread? = null

    private val handler      = Handler(Looper.getMainLooper())
    private val isRunning    = AtomicBoolean(false)
    private val isSpeaking   = AtomicBoolean(false)
    private val isModelReady = AtomicBoolean(false)

    // Energy gate — tune this if too sensitive or not sensitive enough
    // Higher = ignores more noise. Lower = picks up quieter speech.
    private val ENERGY_THRESHOLD = 500.0

    init {
        // Load model on dedicated background thread
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            initModel()
        }.also { it.name = "vosk-init"; it.start() }
    }

    private fun initModel() {
        try {
            val modelDir = File(context.filesDir, "vosk-model")
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                Log.d(TAG, "Copying model from assets...")
                copyAssets("model", modelDir)
                Log.d(TAG, "Model copied ✅")
            }
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                onError?.invoke("VOSK model missing from assets folder")
                return
            }
            model      = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            isModelReady.set(true)
            Log.d(TAG, "✅ VOSK model loaded — ready to listen")
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

    // ── RMS energy — reject silence/noise ────────────────────
    private fun energy(buf: ByteArray, len: Int): Double {
        var sum = 0.0; var i = 0
        while (i < len - 1) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            sum  += s * s.toDouble(); i += 2
        }
        return sqrt(sum / (len / 2))
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
            onError?.invoke("RECORD_AUDIO not granted")
            return
        }

        val minBuf  = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { onError?.invoke("Bad buffer size"); return }
        val bufSize = minBuf * 2

        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); onError?.invoke("AudioRecord failed"); return
        }

        try { ar.startRecording() } catch (e: Exception) {
            ar.release(); onError?.invoke("Mic failed: ${e.message}"); return
        }

        audioRecord = ar
        isRunning.set(true)

        listenThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            // Use minBuf chunk — smaller = lower latency per VOSK docs
            val buf = ByteArray(minBuf)
            Log.d(TAG, "🎤 Listen loop running")

            while (isRunning.get()) {
                try {
                    if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Thread.sleep(50); continue
                    }
                    val read = ar.read(buf, 0, buf.size)
                    if (read <= 0 || isSpeaking.get()) continue

                    // Energy gate — skip noise
                    if (energy(buf, read) < ENERGY_THRESHOLD) continue

                    val final = recognizer?.acceptWaveForm(buf, read) ?: false
                    if (final) {
                        val text = JSONObject(recognizer?.result ?: "{}")
                            .optString("text", "").trim().lowercase()
                        if (text.length > 2 && text != "[unk]") {
                            Log.d(TAG, "✅ $text")
                            onResult(text)
                        }
                    } else {
                        val partial = JSONObject(recognizer?.partialResult ?: "{}")
                            .optString("partial", "").trim().lowercase()
                        if (partial.isNotBlank() && partial != "[unk]" && isWakeWord(partial)) {
                            Log.d(TAG, "🔔 Wake partial: $partial")
                            onResult("WAKE")
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "Loop: ${e.message}")
                    Thread.sleep(50)
                }
            }
            Log.d(TAG, "Loop ended")
        }.also { it.name = "kate-listen"; it.start() }
    }

    private fun isWakeWord(t: String) =
        t.contains("hey kate") || t.contains("hey cat") ||
        t.contains("okay kate") || t.contains("hi kate")

    fun setSpeaking(state: Boolean) {
        isSpeaking.set(state)
        // Reset recognizer buffer when Kate stops speaking
        // Prevents Kate hearing her own TTS as commands
        if (!state) recognizer?.reset()
        Log.d(TAG, "Speaking: $state")
    }

    fun isListening(): Boolean = isRunning.get()
    fun isSpeaking(): Boolean  = isSpeaking.get()

    fun stopListening() {
        isRunning.set(false)
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        listenThread?.interrupt(); listenThread = null
        Log.d(TAG, "Stopped")
    }

    fun shutdown() {
        stopListening()
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() }      catch (_: Exception) {}
        recognizer = null; model = null
        isModelReady.set(false)
    }

    companion object {
        private const val TAG         = "KateSpeech"
        private const val SAMPLE_RATE = 16000
    }
}
