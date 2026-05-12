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

/**
 * Manages continuous offline speech recognition using VOSK.
 *
 * ── WHY VOSK WAS DEAF ────────────────────────────────────────────────────────
 *
 * The previous implementation had a noise-floor gate before acceptWaveForm():
 *
 *     if (rms(buf, read) < NOISE_FLOOR) continue   // ← SILENT FRAMES SKIPPED
 *     recognizer?.acceptWaveForm(buf, read)
 *
 * VOSK's internal VAD (voice activity detection) works by analysing the
 * energy contrast between speech frames and the surrounding silence.
 * It detects end-of-utterance when it sees silence *after* speech.
 * By skipping silent frames, we starved VOSK of the silence it needs to
 * finalise a result. acceptWaveForm() never returned true, so onResult()
 * was never called. Kate heard nothing.
 *
 * THE FIX: Feed EVERY frame — including silence — to VOSK, unconditionally.
 * Gate the RESULT only, never the INPUT.
 *
 * ── OTHER DESIGN DECISIONS ───────────────────────────────────────────────────
 *
 * • Echo cooldown: 800ms after Kate finishes speaking, VOSK results are
 *   discarded so room reverb doesn't trigger phantom commands.
 *
 * • isSpeaking gate: while Kate is speaking we still feed audio to VOSK
 *   (so VAD context is maintained) but we discard all results.
 *
 * • Dead-mic detection: 60 consecutive bad reads → onError("AUDIO_DEAD").
 *
 * • AudioRecord state polling: every ~5s we check recordingState directly.
 *   OEM ROMs (MIUI, ColorOS, OxygenOS) can silently pause background
 *   AudioRecord sessions after a few minutes. Polling catches this before
 *   the 60-read failStreak would.
 *
 * • isSpeaking safety timeout: if TTS engine hangs without firing onDone,
 *   isSpeaking would stay true forever silencing the mic. After 12s we
 *   force-clear it.
 *
 * • Double-open guard: startListening() returns immediately if audioRecord
 *   is still allocated from a previous session that hasn't wound down yet.
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

    private val handler      = Handler(Looper.getMainLooper())
    private val isRunning    = AtomicBoolean(false)
    private val isSpeaking   = AtomicBoolean(false)
    private val isModelReady = AtomicBoolean(false)

    // ── Online audio tap ───────────────────────────────────────
    // When online, KateServices sets this to forward raw PCM to Deepgram.
    // Called on the listen thread — must be fast (no blocking, no UI work).
    // Set to null when offline so there's zero overhead.
    @Volatile var onAudioFrame: ((ByteArray, Int) -> Unit)? = null

    // Timestamp when isSpeaking was last set true.
    // The listen loop checks this to detect a permanently-stuck mute state.
    private val speakingSetAt = AtomicLong(0L)
    private val MAX_SPEAKING_MS = 12_000L   // longer than any realistic utterance

    // Discard VOSK results for this window after Kate stops speaking.
    private val ECHO_COOLDOWN_MS = 800L
    @Volatile private var ignoredUntil = 0L

    // Number of listen-loop cycles between AudioRecord health checks.
    // At ~100ms per read cycle, 50 cycles ≈ 5 seconds between checks.
    private val STATE_CHECK_INTERVAL = 50

    init {
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            initModel()
        }.also { it.name = "vosk-init"; it.start() }
    }

    // ── Model init ─────────────────────────────────────────────

    private fun initModel() {
        try {
            val dir = File(modelPath)
            if (isValidModelDir(dir)) {
                model = Model(dir.absolutePath)
                Log.d(TAG, "✅ VOSK loaded from modelPath: $modelPath")
            } else {
                // Try the copy we made on a previous launch first.
                val cached = File(context.filesDir, "vosk-model")
                if (isValidModelDir(cached)) {
                    Log.d(TAG, "✅ VOSK loaded from cache: ${cached.absolutePath}")
                    model = Model(cached.absolutePath)
                } else {
                    // First run — copy from assets. This can take 10-30s for a 50MB model.
                    Log.d(TAG, "Copying model from assets — this may take a moment...")
                    copyAssets("model", cached)
                    if (!isValidModelDir(cached)) {
                        Log.e(TAG, "Model copy failed or assets/model/ has no real model (only README?)")
                        onError?.invoke("VOSK_MODEL_MISSING")
                        return
                    }
                    model = Model(cached.absolutePath)
                    Log.d(TAG, "✅ VOSK loaded after copy from assets")
                }
            }
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            isModelReady.set(true)
            Log.d(TAG, "Recognizer ready at ${SAMPLE_RATE}Hz")
        } catch (e: Exception) {
            Log.e(TAG, "VOSK init failed: ${e.message}", e)
            onError?.invoke("VOSK_INIT_FAILED:${e.message}")
        }
    }

    /**
     * A real VOSK model directory contains am/ and conf/ subdirectories.
     * assets/model/ in the repo only has README.MD — this guard prevents
     * VOSK from trying (and crashing) on an empty directory.
     */
    private fun isValidModelDir(dir: File): Boolean =
        dir.exists() &&
        File(dir, "am").isDirectory &&
        File(dir, "conf").isDirectory

    // ── Start / Stop ───────────────────────────────────────────

    fun startListening() {
        // Guard: already running.
        if (isRunning.get()) return

        // Guard: previous AudioRecord hasn't released yet.
        // Without this, a watchdog or AUDIO_DEAD restart can open a second
        // AudioRecord on top of a still-closing one, causing init failure.
        if (audioRecord != null) {
            Log.w(TAG, "startListening(): audioRecord still exists — skipping")
            return
        }

        // ── CRITICAL: Do NOT gate on isModelReady here. ───────────────────
        //
        // Android 14 (targetSdk 34) enforces that a foreground service declared
        // with foregroundServiceType="microphone" MUST open an AudioRecord session
        // within a short window after startForeground() is called. If VOSK model
        // loading is still in progress (or fails entirely), and we return early here,
        // the OS throws ForegroundServiceDidNotStartInTimeException and kills the
        // process — which was the "app closes 7 seconds after greeting" crash.
        //
        // Fix: open AudioRecord unconditionally. In the listen loop we only call
        // recognizer.acceptWaveForm() once isModelReady is true. Frames captured
        // before VOSK is ready are silently discarded — the mic stays open and
        // the OS is satisfied.

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission denied")
            onError?.invoke("NO_MIC_PERMISSION")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBuf")
            onError?.invoke("AUDIO_DEAD"); return
        }

        // Use 4× minBuf for the internal ring buffer — reduces the chance of
        // overrun on slow devices — but read in minBuf-sized chunks so each
        // VOSK call processes ~100ms of audio, which is the sweet spot for
        // the small model's frame rate.
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise (state=${ar.state})")
            ar.release()
            onError?.invoke("AUDIO_DEAD"); return
        }

        try {
            ar.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording() threw: ${e.message}")
            ar.release()
            onError?.invoke("AUDIO_DEAD"); return
        }

        audioRecord = ar
        isRunning.set(true)
        Log.d(TAG, "🎤 AudioRecord started — minBuf=$minBuf bytes")

        listenThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf        = ByteArray(minBuf)
            var failStreak = 0
            var cycleCount = 0

            while (isRunning.get()) {
                try {
                    // ── Periodic health checks ─────────────────────────────
                    if (++cycleCount % STATE_CHECK_INTERVAL == 0) {

                        // 1. AudioRecord state check — catches OEM ROM mic revocation.
                        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            Log.e(TAG, "AudioRecord state changed to ${ar.recordingState} — restarting")
                            isRunning.set(false)
                            handler.post { onError?.invoke("AUDIO_DEAD") }
                            break
                        }

                        // 2. isSpeaking timeout — if TTS engine never fired onDone,
                        //    isSpeaking stays true and all results are silently dropped.
                        //    Force-clear after MAX_SPEAKING_MS to recover the mic.
                        val setAt = speakingSetAt.get()
                        if (isSpeaking.get() && setAt > 0L &&
                            System.currentTimeMillis() - setAt > MAX_SPEAKING_MS) {
                            Log.w(TAG, "isSpeaking stuck for ${MAX_SPEAKING_MS}ms — force-clearing")
                            isSpeaking.set(false)
                            speakingSetAt.set(0L)
                            ignoredUntil = System.currentTimeMillis() + ECHO_COOLDOWN_MS
                            try { recognizer?.reset() } catch (_: Exception) {}
                        }
                    }

                    // ── Read audio ────────────────────────────────────────
                    val read = ar.read(buf, 0, buf.size)

                    if (read <= 0) {
                        if (++failStreak > 60) {
                            Log.e(TAG, "60 consecutive bad reads — AUDIO_DEAD")
                            isRunning.set(false)
                            handler.post { onError?.invoke("AUDIO_DEAD") }
                            break
                        }
                        Thread.sleep(10)
                        continue
                    }
                    failStreak = 0

                    // ── Forward audio to online STT (Deepgram) ────────────
                    // This tap fires regardless of isSpeaking/cooldown state —
                    // Deepgram handles VAD server-side. The gate at the result
                    // layer (below) is where we suppress echo, not here.
                    onAudioFrame?.invoke(buf, read)

                    // ── Feed ALL audio to VOSK — including silence ────────
                    //
                    // DO NOT gate here with an rms/energy check.
                    //
                    // VOSK's VAD detects end-of-utterance by observing the energy
                    // drop from speech back to silence. If we skip silent frames,
                    // VOSK never sees that drop, acceptWaveForm() never returns true,
                    // and no final result is ever produced.
                    //
                    // Always feed every frame. Gate only on the RESULT below.
                    // If the model isn't ready yet, discard the frame silently —
                    // the AudioRecord session stays open (satisfying Android 14 FGS).
                    val isFinal = if (!isModelReady.get()) {
                        false  // model still loading — keep AudioRecord open, drop frame
                    } else {
                        try {
                            recognizer?.acceptWaveForm(buf, read) ?: false
                        } catch (e: Exception) {
                            Log.e(TAG, "acceptWaveForm: ${e.message}")
                            false
                        }
                    }

                    // ── Gate results (not input) ───────────────────────────
                    val now = System.currentTimeMillis()
                    val inCooldown = now < ignoredUntil

                    if (isFinal) {
                        val json = try { recognizer?.result ?: "{}" } catch (_: Exception) { "{}" }
                        val text = JSONObject(json).optString("text", "").trim()

                        Log.v(TAG, "VOSK final raw: \"$text\"")

                        if (!inCooldown && !isSpeaking.get() &&
                            text.isNotBlank() && text != "[unk]" && text.length > 1) {
                            Log.d(TAG, "✅ Result: \"$text\"")
                            handler.post { onResult(text) }
                        }

                    } else {
                        // Partials: used only for early wake-word detection.
                        // Checking partials gives ~300ms lower latency for "hey kate"
                        // without waiting for the full utterance to complete.
                        if (!inCooldown && !isSpeaking.get()) {
                            val json = try { recognizer?.partialResult ?: "{}" } catch (_: Exception) { "{}" }
                            val partial = JSONObject(json).optString("partial", "").trim()

                            if (partial.isNotBlank() && partial != "[unk]" && isWakeWord(partial)) {
                                Log.d(TAG, "🔔 Wake word in partial: \"$partial\"")
                                handler.post { onResult("WAKE") }
                                try { recognizer?.reset() } catch (_: Exception) {}
                            }
                        }
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "Listen loop exception: ${e.message}")
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

    fun setSpeaking(speaking: Boolean) {
        isSpeaking.set(speaking)
        if (speaking) {
            speakingSetAt.compareAndSet(0L, System.currentTimeMillis())
            Log.d(TAG, "Mic gated — TTS active")
        } else {
            speakingSetAt.set(0L)
            ignoredUntil = System.currentTimeMillis() + ECHO_COOLDOWN_MS
            try { recognizer?.reset() } catch (_: Exception) {}
            Log.d(TAG, "Mic open — echo cooldown ${ECHO_COOLDOWN_MS}ms")
        }
    }

    fun isListening(): Boolean = isRunning.get()
    fun isSpeaking(): Boolean  = isSpeaking.get()

    fun stopListening() {
        isRunning.set(false)
        listenThread?.interrupt()
        listenThread = null
        try { audioRecord?.stop() }    catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.d(TAG, "Listening stopped")
    }

    fun shutdown() {
        stopListening()
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() }      catch (_: Exception) {}
        recognizer   = null
        model        = null
        isModelReady.set(false)
        Log.d(TAG, "Shutdown complete")
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun copyAssets(src: String, dst: File) {
        dst.mkdirs()
        val entries = try { context.assets.list(src) } catch (_: Exception) { null } ?: return
        for (entry in entries) {
            val srcPath = "$src/$entry"
            val dstFile = File(dst, entry)
            val children = try { context.assets.list(srcPath) } catch (_: Exception) { null }
            if (!children.isNullOrEmpty()) {
                copyAssets(srcPath, dstFile)
            } else {
                try {
                    context.assets.open(srcPath).use { input ->
                        FileOutputStream(dstFile).use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy asset $srcPath: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG         = "KateSpeech"
        private const val SAMPLE_RATE = 16000
    }
}
