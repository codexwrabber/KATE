package com.kate.assistant.features.voice

class WakeWordDetector {

    private val wakeWords = listOf(
        "hey kate",
        "hi kate",
        "okay kate",
        "kate"
    )

    fun isWakeWord(text: String): Boolean {
        val lower = text.lowercase().trim()
        return wakeWords.any { lower.contains(it) }
    }
}
