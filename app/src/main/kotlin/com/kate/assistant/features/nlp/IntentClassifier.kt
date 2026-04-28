package com.kate.assistant.features.nlp

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class IntentClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val labels = mapOf(
        0 to "COMMUNICATION",
        1 to "MEDIA_CONTROL",
        2 to "OPEN_APP",
        3 to "REMINDER",
        4 to "SYSTEM_CONTROL",
        5 to "UNKNOWN"
    )

    init {
        try {
            val model = loadModelFromAssets("model_intent.tflite")
            interpreter = Interpreter(model)
            Log.d("IntentClassifier", "✅ TFLite model loaded")
        } catch (e: Exception) {
            Log.e("IntentClassifier", "Failed to load model: ${e.message}")
        }
    }

    fun classify(text: String): String {
        val interp = interpreter ?: return "UNKNOWN"
        return try {
            // Simple bag-of-words vectorization — 100 features
            val input  = Array(1) { vectorize(text) }
            val output = Array(1) { FloatArray(labels.size) }
            interp.run(input, output)
            val maxIdx = output[0].indices.maxByOrNull { output[0][it] } ?: 5
            val confidence = output[0][maxIdx]
            Log.d("IntentClassifier", "Intent: ${labels[maxIdx]} confidence: $confidence")
            if (confidence > 0.6f) labels[maxIdx] ?: "UNKNOWN"
            else "UNKNOWN"
        } catch (e: Exception) {
            Log.e("IntentClassifier", "Classify error: ${e.message}")
            "UNKNOWN"
        }
    }

    private fun vectorize(text: String): FloatArray {
        val vector = FloatArray(100)
        val words  = text.lowercase().split(" ")
        words.forEachIndexed { i, word ->
            if (i < 100) vector[i] = word.hashCode().toFloat() % 1.0f
        }
        return vector
    }

    private fun loadModelFromAssets(filename: String): MappedByteBuffer {
        val fd     = context.assets.openFd(filename)
        val stream = FileInputStream(fd.fileDescriptor)
        return stream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
