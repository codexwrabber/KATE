package com.kate.assistant.features.voice

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class KateSpeechManager(
    private val context: Context,
    private val onResult: (String) -> Unit
) {
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var isListening = false
    private var isModelReady = false

    init {
        Thread {
            try {
                initModel()
            } catch (e: Exception) {
                Log.e("KateSpeech", "Model init failed: ${e.message}")
            }
        }.start()
    }

    private fun initModel() {
        val modelDir = File(context.filesDir, "vosk-model")
        if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
            Log.d("KateSpeech", "Copying model from assets...")
            copyAssetFolder(context.assets, "model", modelDir.absolutePath)
        }
        model = Model(modelDir.absolutePath)
        isModelReady = true
        Log.d("KateSpeech", "✅ VOSK model loaded!")
    }

    private fun copyAssetFolder(
        assetManager: AssetManager,
        assetPath: String,
        destPath: String
    ) {
        val files = assetManager.list(assetPath) ?: return
        File(destPath).mkdirs()
        for (file in files) {
            val srcPath  = "$assetPath/$file"
            val dstFile  = File(destPath, file)
            val subFiles = assetManager.list(srcPath)
            if (subFiles != null && subFiles.isNotEmpty()) {
                copyAssetFolder(assetManager, srcPath, dstFile.absolutePath)
            } else {
                try {
                    assetManager.open(srcPath).use { input ->
                        FileOutputStream(dstFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: IOException) {
                    Log.e("KateSpeech", "Copy failed: $srcPath — ${e.message}")
                }
            }
        }
    }

    fun startListening() {
        if (isListening) return
        if (!isModelReady || model == null) {
            Log.w("KateSpeech", "Model not ready yet — retrying in 1s")
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ startListening() }, 1000)
            return
        }
        try {
            val recognizer = Recognizer(model, 16000.0f)
            speechService  = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    hypothesis ?: return
                    runCatching {
                        val partial = JSONObject(hypothesis).optString("partial")
                        if (partial.isNotBlank())
                            Log.d("KateSpeech", "Partial: $partial")
                    }
                }
                override fun onResult(hypothesis: String?) {
                    hypothesis ?: return
                    runCatching {
                        val text = JSONObject(hypothesis).optString("text")
                        if (text.isNotBlank()) {
                            Log.d("KateSpeech", "✅ Result: $text")
                            onResult(text)
                        }
                    }
                }
                override fun onFinalResult(hypothesis: String?) {
                    hypothesis ?: return
                    runCatching {
                        val text = JSONObject(hypothesis).optString("text")
                        if (text.isNotBlank()) {
                            Log.d("KateSpeech", "Final: $text")
                            onResult(text)
                        }
                    }
                }
                override fun onError(e: Exception?) {
                    Log.e("KateSpeech", "Error: ${e?.message}")
                    isListening = false
                }
                override fun onTimeout() {
                    Log.d("KateSpeech", "Timeout")
                    isListening = false
                }
            })
            isListening = true
            Log.d("KateSpeech", "🎤 VOSK listening started")
        } catch (e: Exception) {
            Log.e("KateSpeech", "Start failed: ${e.message}")
            isListening = false
        }
    }

    fun stopListening() {
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            isListening   = false
            Log.d("KateSpeech", "VOSK stopped")
        } catch (e: Exception) {
            Log.e("KateSpeech", "Stop error: ${e.message}")
        }
    }

    fun isActive() = isListening
}
