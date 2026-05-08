package com.kate.assistant.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages VOSK model selection and background upgrade.
 *
 * Strategy:
 *  - On first run: use the small bundled model (already in filesDir/vosk-model/)
 *  - Immediately start downloading daanzu-lgraph (129MB) in the background
 *  - Once downloaded and extracted: switch to it on next KateSpeechManager init
 *  - Download is resumable — stores partial file and checks Content-Length
 *
 * Model quality:
 *  Small model  (vosk-model-small-en-us-0.15) : WER ~11.5%  — fast, already present
 *  Daanzu-lgraph (daanzu-20200905-lgraph)     : WER ~8.2%   — wideband dictation model
 *                                                             designed for mic input
 */
class ModelManager(private val context: Context) {

    private val handler   = Handler(Looper.getMainLooper())
    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs     = context.getSharedPreferences("kate_model", Context.MODE_PRIVATE)

    // Paths
    val smallModelDir   = File(context.filesDir, "vosk-model")
    val upgradeModelDir = File(context.filesDir, "vosk-model-daanzu")
    private val downloadZip = File(context.filesDir, "vosk-daanzu.zip")

    // Callback fires on main thread when upgrade model is ready
    var onUpgradeReady: (() -> Unit)? = null

    companion object {
        private const val TAG         = "ModelManager"
        private const val CHANNEL_ID  = "kate_model_download"
        private const val NOTIF_ID    = 42
        private const val MODEL_URL   =
            "https://alphacephei.com/vosk/models/vosk-model-en-us-daanzu-20200905-lgraph.zip"
        private const val PREF_DONE   = "upgrade_done"
    }

    /** Returns the best available model path right now (may be the small one initially). */
    fun bestModelPath(): String =
        if (upgradeReady()) upgradeModelDir.absolutePath
        else smallModelDir.absolutePath

    fun upgradeReady(): Boolean =
        prefs.getBoolean(PREF_DONE, false) &&
        upgradeModelDir.exists() &&
        upgradeModelDir.listFiles()?.isNotEmpty() == true

    /**
     * Start upgrade download if not already done.
     * Safe to call every launch — skips immediately if already done.
     */
    fun startUpgradeIfNeeded() {
        if (upgradeReady()) {
            Log.d(TAG, "Upgrade model already present — nothing to do")
            return
        }
        scope.launch { downloadAndExtract() }
    }

    // ── Download ───────────────────────────────────────────────

    private suspend fun downloadAndExtract() {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            ensureChannel(nm)

            Log.d(TAG, "Starting model download from $MODEL_URL")
            val totalBytes = download(nm)
            if (totalBytes <= 0) {
                Log.e(TAG, "Download failed or empty"); return
            }

            notify(nm, "Applying upgrade...", -1)
            extract()
            cleanup(nm)

            prefs.edit().putBoolean(PREF_DONE, true).apply()
            Log.d(TAG, "✅ Upgrade model ready at ${upgradeModelDir.absolutePath}")
            handler.post { onUpgradeReady?.invoke() }

        } catch (e: Exception) {
            Log.e(TAG, "Model upgrade failed: ${e.message}")
            downloadZip.delete()  // clean partial file so next launch retries
        }
    }

    /** Downloads with resume support. Returns total bytes downloaded, or -1 on failure. */
    private suspend fun download(nm: NotificationManager): Long = withContext(Dispatchers.IO) {
        val resumeFrom = if (downloadZip.exists()) downloadZip.length() else 0L
        var connection: HttpURLConnection? = null
        try {
            connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout    = 30_000
            if (resumeFrom > 0) {
                connection.setRequestProperty("Range", "bytes=$resumeFrom-")
                Log.d(TAG, "Resuming from $resumeFrom bytes")
            }
            connection.connect()

            val code = connection.responseCode
            if (code != 200 && code != 206) {
                Log.e(TAG, "HTTP $code"); return@withContext -1L
            }

            val contentLength = connection.contentLengthLong
            val totalExpected = if (resumeFrom > 0) resumeFrom + contentLength else contentLength
            Log.d(TAG, "Total size: $totalExpected bytes")

            val out = FileOutputStream(downloadZip, resumeFrom > 0)
            val buf = ByteArray(64 * 1024)
            var downloaded = resumeFrom
            var lastNotif  = 0L

            connection.inputStream.use { ins ->
                out.use { os ->
                    var read: Int
                    while (ins.read(buf).also { read = it } != -1) {
                        os.write(buf, 0, read)
                        downloaded += read

                        // Update notification at most every 2MB to avoid spam
                        if (totalExpected > 0 && downloaded - lastNotif > 2 * 1024 * 1024) {
                            val pct = ((downloaded * 100) / totalExpected).toInt()
                            val mb  = downloaded / (1024 * 1024)
                            val total = totalExpected / (1024 * 1024)
                            notify(nm, "Downloading enhanced model ($mb/$total MB)", pct)
                            lastNotif = downloaded
                        }
                    }
                }
            }
            downloaded
        } finally {
            connection?.disconnect()
        }
    }

    /** Extracts the downloaded zip to upgradeModelDir. */
    private fun extract() {
        upgradeModelDir.deleteRecursively()
        upgradeModelDir.mkdirs()

        ZipInputStream(BufferedInputStream(FileInputStream(downloadZip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Strip the top-level directory from zip entries
                val name = entry.name.substringAfter("/")
                if (name.isNotBlank()) {
                    val target = File(upgradeModelDir, name)
                    if (entry.isDirectory) target.mkdirs()
                    else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Log.d(TAG, "Extracted to ${upgradeModelDir.absolutePath}")
    }

    private fun cleanup(nm: NotificationManager) {
        downloadZip.delete()
        nm.cancel(NOTIF_ID)
    }

    // ── Notifications ──────────────────────────────────────────

    private fun ensureChannel(nm: NotificationManager) {
        val ch = NotificationChannel(
            CHANNEL_ID, "Kate Model Upgrade", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun notify(nm: NotificationManager, text: String, progress: Int) {
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Kate — speech upgrade")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
        if (progress in 0..100) b.setProgress(100, progress, false)
        else b.setProgress(0, 0, true)
        nm.notify(NOTIF_ID, b.build())
    }

    fun cancel() { scope.cancel() }
}
