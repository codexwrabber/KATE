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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages continuous speech recognition using VOSK (offline fallback).
 *
 * ── CRASH ROOT CAUSE (confirmed via bugreport SIGSEGV traces) ────────────────
 *
 * libvosk.so's Recognizer is NOT thread-safe. Every crash in the bugreport
 * was a SIGSEGV inside Recognizer::Reset() or Recognizer::Result() caused
 * by two threads touching the same recognizer simultaneously:
 *
 *   Thread A (kate-listen): recognizer.acceptWaveForm() or recognizer.result
 *   Thread B (main):        recognizer.reset()  ← called from setSpeaking(false)
 *
 * The fix: recognizerLock (ReentrantLock) wraps EVERY native VOSK call.
 * setSpeaking(false) acquires the lock before calling reset(), so it always
 * waits for any in-flight acceptWaveForm/result call to finish first.
 *
 * ── OTHER DESIGN DECISIONS ───────────────────────────────────────────────────
 *
 * • Echo cooldown: 800ms after Kate stops speaking, results are discarded.
 * • Feed ALL frames to VOSK including silence — VAD needs silence to finalize.
 * • AudioRecord opened unconditionally (Android 14 FGS microphone enforcement).
 * • Dead-mic detection: 60 consecutive bad reads → onError("AUDIO_DEAD").
 * • isSpeaking safety timeout: force-clear after 12s if TTS hangs.
 */
class KateSpeechManager(
    private val context:   Context,
    private val modelPath: String,
    private val onResult:  (String) -> Unit,
    private val onError:   ((String) -> Unit)? = null
) {
    private var model:        Model?       = null
    private var recognizer:   Recognizer?  = null
    private var audioRecord:  AudioRecord? = null
    private var listenThread: Thread?      = null

    private val handler       = Handler(Looper.getMainLooper())
    private val isRunning     = AtomicBoolean(false)
    private val isSpeaking    = AtomicBoolean(false)
    private val isModelReady  = AtomicBoolean(false)

    // ── THE KEY FIX ────────────────────────────────────────────────────────────
    // All native VOSK calls (acceptWaveForm, result, partialResult, reset, close)
    // must be serialized through this lock. libvosk.so is not thread-safe.
    // The listen loop holds it while calling VOSK. setSpeaking acquires it before
    // calling reset(). This eliminates the SIGSEGV race confirmed in bugreport.
    private val recognizerLock = ReentrantLock()

    // ── Online audio tap ───────────────────────────────────────────────────────
    @Volatile var onAudioFrame: ((ByteArray, Int) -> Unit)? = null

    private val speakingSetAt   = AtomicLong(0L)
    private val MAX_SPEAKING_MS = 12_000L
    private val ECHO_COOLDOWN_MS = 800L
    @Volatile private var ignoredUntil = 0L
    private val STATE_CHECK_INTERVAL = 50

    init {
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            initModel()
        }.also { it.name = "vosk-init"; it.start() }
    }

    private fun initModel() {
        try {
            val dir = File(modelPath)
            if (isValidModelDir(dir)) {
                model = Model(dir.absolutePath)
                Log.d(TAG, "VOSK loaded from modelPath")
            } else {
                val cached = File(context.filesDir, "vosk-model")
                if (isValidModelDir(cached)) {
                    model = Model(cached.absolutePath)
                    Log.d(TAG, "VOSK loaded from cache")
                } else {
                    Log.d(TAG, "Copying VOSK model from assets...")
                    copyAssets("model", cached)
                    if (!isValidModelDir(cached)) {
                        Log.e(TAG, "No valid VOSK model in assets")
                        onError?.invoke("VOSK_MODEL_MISSING")
                        return
                    }
                    model = Model(cached.absolutePath)
                    Log.d(TAG, "VOSK loaded after asset copy")
                }
            }
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            isModelReady.set(true)
            Log.d(TAG, "Recognizer ready")
        } catch (e: Exception) {
            Log.e(TAG, "VOSK init failed: ${e.message}", e)
            onError?.invoke("VOSK_INIT_FAILED:${e.message}")
        }
    }

    private fun isValidModelDir(dir: File): Boolean =
        dir.exists() && File(dir, "am").isDirectory && File(dir, "conf").isDirectory

    fun startListening() {
        if (isRunning.get()) return
        if (audioRecord != null) { Log.w(TAG, "audioRecord still exists — skip"); return }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onError?.invoke("NO_MIC_PERMISSION"); return
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { onError?.invoke("AUDIO_DEAD"); return }

        val bufSize = minBuf * 4
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize)

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); onError?.invoke("AUDIO_DEAD"); return
        }

        ar.startRecording()
        audioRecord = ar
        isRunning.set(true)

        listenThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf   = ByteArray(bufSize / 2)
            var failStreak = 0
            var loopCount  = 0

            while (isRunning.get()) {
                try {
                    loopCount++

                    if (loopCount % STATE_CHECK_INTERVAL == 0) {
                        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            isRunning.set(false)
                            handler.post { onError?.invoke("AUDIO_DEAD") }
                            break
                        }
                        val setAt = speakingSetAt.get()
                        if (isSpeaking.get() && setAt > 0L &&
                            System.currentTimeMillis() - setAt > MAX_SPEAKING_MS) {
                            isSpeaking.set(false); speakingSetAt.set(0L)
                            ignoredUntil = System.currentTimeMillis() + ECHO_COOLDOWN_MS
                            recognizerLock.withLock {
                                try { recognizer?.reset() } catch (_: Exception) {}
                            }
                        }
                    }

                    val read = ar.read(buf, 0, buf.size)
                    if (read <= 0) {
                        if (++failStreak > 60) {
                            isRunning.set(false)
                            handler.post { onError?.invoke("AUDIO_DEAD") }
                            break
                        }
                        Thread.sleep(10); continue
                    }
                    failStreak = 0

                    // Forward to Deepgram (not during Kate's own speech)
                    if (!isSpeaking.get()) onAudioFrame?.invoke(buf, read)

                    // Discard frames until model is ready — keeps AudioRecord open
                    if (!isModelReady.get()) continue

                    // ── LOCK: all VOSK native calls serialized here ────────
                    // This is the fix. setSpeaking(false) also locks before
                    // reset(), so they can never race.
                    val isFinal: Boolean
                    val finalJson: String
                    val partialJson: String

                    recognizerLock.withLock {
                        isFinal = try {
                            recognizer?.acceptWaveForm(buf, read) ?: false
                        } catch (e: Exception) { Log.e(TAG, "acceptWaveForm: ${e.message}"); false }

                        finalJson = if (isFinal) {
                            try { recognizer?.result ?: "{}" } catch (_: Exception) { "{}" }
                        } else "{}"

                        partialJson = if (!isFinal) {
                            try { recognizer?.partialResult ?: "{}" } catch (_: Exception) { "{}" }
                        } else "{}"
                    }
                    // ── UNLOCK ─────────────────────────────────────────────

                    val now        = System.currentTimeMillis()
                    val inCooldown = now < ignoredUntil

                    if (isFinal) {
                        val text = JSONObject(finalJson).optString("text", "").trim()
                        if (!inCooldown && !isSpeaking.get() &&
                            text.isNotBlank() && text != "[unk]" && text.length > 1) {
                            Log.d(TAG, "Result: \"$text\"")
                            handler.post { onResult(text) }
                        }
                    } else {
                        if (!inCooldown && !isSpeaking.get()) {
                            val partial = JSONObject(partialJson).optString("partial", "").trim()
                            if (partial.isNotBlank() && partial != "[unk]" && isWakeWord(partial)) {
                                Log.d(TAG, "Wake: \"$partial\"")
                                handler.post { onResult("WAKE") }
                                recognizerLock.withLock {
                                    try { recognizer?.reset() } catch (_: Exception) {}
                                }
                            }
                        }
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "Listen loop: ${e.message}")
                    Thread.sleep(30)
                }
            }
            Log.d(TAG, "Listen loop exited")
        }.also { it.name = "kate-listen"; it.start() }
    }

    private fun isWakeWord(text: String): Boolean {
        val l = text.lowercase()
        return l.contains("hey kate") || l.contains("hi kate") ||
               l.contains("okay kate") || l.contains("ok kate") ||
               l.contains("hey cat") || l.startsWith("kate ")
    }

    // setSpeaking acquires recognizerLock before reset() — the other half of the fix.
    fun setSpeaking(speaking: Boolean) {
        isSpeaking.set(speaking)
        if (speaking) {
            speakingSetAt.compareAndSet(0L, System.currentTimeMillis())
            Log.d(TAG, "Mic gated — TTS active")
        } else {
            speakingSetAt.set(0L)
            ignoredUntil = System.currentTimeMillis() + ECHO_COOLDOWN_MS
            recognizerLock.withLock {
                try { recognizer?.reset() } catch (_: Exception) {}
            }
            Log.d(TAG, "Mic open — echo cooldown ${ECHO_COOLDOWN_MS}ms")
        }
    }

    fun isListening(): Boolean = isRunning.get()
    fun isSpeaking(): Boolean  = isSpeaking.get()

    fun stopListening() {
        isRunning.set(false)
        listenThread?.interrupt()
        listenThread = null
        try { audioRecord?.stop()    } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    fun shutdown() {
        stopListening()
        recognizerLock.withLock {
            try { recognizer?.close() } catch (_: Exception) {}
            try { model?.close()      } catch (_: Exception) {}
            recognizer = null
            model      = null
        }
        isModelReady.set(false)
        Log.d(TAG, "Shutdown complete")
    }

    private fun copyAssets(src: String, dst: File) {
        dst.mkdirs()
        val entries = try { context.assets.list(src) } catch (_: Exception) { null } ?: return
        for (entry in entries) {
            val srcPath  = "$src/$entry"
            val dstFile  = File(dst, entry)
            val children = try { context.assets.list(srcPath) } catch (_: Exception) { null }
            if (!children.isNullOrEmpty()) {
                copyAssets(srcPath, dstFile)
            } else {
                try {
                    context.assets.open(srcPath).use { i ->
                        FileOutputStream(dstFile).use { o -> i.copyTo(o) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Asset copy $srcPath: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG         = "KateSpeech"
        private const val SAMPLE_RATE = 16000
    }
}
