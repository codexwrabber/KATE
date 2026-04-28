package com.kate.assistant.features.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.json.JSONObject
import java.io.File

class KateSpeechManager(
    private val context: Context,
    private val onResult: (String) -> Unit
) {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var thread: Thread? = null

    init {
        Thread {
            initModel()
        }.start()
    }

    private fun initModel() {
        val modelDir = File(context.filesDir, "vosk-model")

        if (!modelDir.exists()) {
            Log.e("KateSpeech", "❌ Model not found in filesDir")
            return
        }

        model = Model(modelDir.absolutePath)
        recognizer = Recognizer(model, 16000.0f)

        Log.d("KateSpeech", "✅ Model loaded")
    }

    fun startListening() {
        if (isListening) return
        if (recognizer == null) {
            Log.e("KateSpeech", "Recognizer not ready")
            return
        }

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()
        isListening = true

        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val buffer = ByteArray(bufferSize)

            Log.d("KateSpeech", "🎤 Listening started")

            while (isListening) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (read > 0) {
                    val result = recognizer?.acceptWaveForm(buffer, read) ?: false

                    if (result) {
                        val text = JSONObject(recognizer?.result ?: "{}")
                            .optString("text")

                        if (text.isNotBlank()) {
                            Log.d("KateSpeech", "FINAL: $text")
                            onResult(text)
                        }
                    } else {
                        val partial = JSONObject(recognizer?.partialResult ?: "{}")
                            .optString("partial")

                        if (partial.isNotBlank()) {
                            Log.d("KateSpeech", "Partial: $partial")
                        }
                    }
                }
            }
        }

        thread?.start()
    }

    fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
