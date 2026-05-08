package com.kate.assistant.features.voice

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

class ModelManager(private val context: Context) {

    private val handler   = Handler(Looper.getMainLooper())
    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs     = context.getSharedPreferences("kate_model", Context.MODE_PRIVATE)

    val smallModelDir   = File(context.filesDir, "vosk-model")
    val upgradeModelDir = File(context.filesDir, "vosk-model-daanzu")
    private val downloadZip = File(context.filesDir, "vosk-daanzu.zip")

    var onUpgradeReady: (() -> Unit)? = null

    companion object {
        private const val TAG        = "ModelManager"
        private const val CHANNEL_ID = "kate_model_download"
        private const val NOTIF_ID   = 42
        private const val MODEL_URL  =
            "https://alphacephei.com/vosk/models/vosk-model-en-us-daanzu-20200905-lgraph.zip"
        private const val PREF_DONE  = "upgrade_done"
    }

    fun bestModelPath(): String =
        if (upgradeReady()) upgradeModelDir.absolutePath
        else smallModelDir.absolutePath

    fun upgradeReady(): Boolean =
        prefs.getBoolean(PREF_DONE, false) &&
        upgradeModelDir.exists() &&
        upgradeModelDir.listFiles()?.isNotEmpty() == true

    fun startUpgradeIfNeeded() {
        if (upgradeReady()) { Log.d(TAG, "Upgrade already present"); return }
        scope.launch { downloadAndExtract() }
    }

    private suspend fun downloadAndExtract() {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            ensureChannel(nm)
            val totalBytes = download(nm)
            if (totalBytes <= 0) { Log.e(TAG, "Download failed"); return }
            notify(nm, "Applying upgrade...", -1)
            extract()
            cleanup(nm)
            prefs.edit().putBoolean(PREF_DONE, true).apply()
            Log.d(TAG, "Upgrade model ready")
            handler.post { onUpgradeReady?.invoke() }
        } catch (e: Exception) {
            Log.e(TAG, "Model upgrade failed: ${e.message}")
            downloadZip.delete()
        }
    }

    private suspend fun download(nm: NotificationManager): Long = withContext(Dispatchers.IO) {
        val resumeFrom = if (downloadZip.exists()) downloadZip.length() else 0L
        var connection: HttpURLConnection? = null
        try {
            connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout    = 30_000
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            connection.connect()
            val code = connection.responseCode
            if (code != 200 && code != 206) return@withContext -1L
            val contentLength = connection.contentLengthLong
            val totalExpected = if (resumeFrom > 0) resumeFrom + contentLength else contentLength
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
                        if (totalExpected > 0 && downloaded - lastNotif > 2 * 1024 * 1024) {
                            val pct = ((downloaded * 100) / totalExpected).toInt()
                            notify(nm, "Downloading model (${downloaded/1024/1024}/${totalExpected/1024/1024} MB)", pct)
                            lastNotif = downloaded
                        }
                    }
                }
            }
            downloaded
        } finally { connection?.disconnect() }
    }

    private fun extract() {
        upgradeModelDir.deleteRecursively()
        upgradeModelDir.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(downloadZip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfter("/")
                if (name.isNotBlank()) {
                    val target = File(upgradeModelDir, name)
                    if (entry.isDirectory) target.mkdirs()
                    else { target.parentFile?.mkdirs(); target.outputStream().use { zis.copyTo(it) } }
                }
                zis.closeEntry(); entry = zis.nextEntry
            }
        }
    }

    private fun cleanup(nm: NotificationManager) { downloadZip.delete(); nm.cancel(NOTIF_ID) }

    private fun ensureChannel(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Kate Model Upgrade", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notify(nm: NotificationManager, text: String, progress: Int) {
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Kate — speech upgrade").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).setSilent(true)
        if (progress in 0..100) b.setProgress(100, progress, false) else b.setProgress(0, 0, true)
        nm.notify(NOTIF_ID, b.build())
    }

    fun cancel() { scope.cancel() }
}
