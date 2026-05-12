package com.kate.assistant.bridge

import android.content.Context

class KateBridge(private val context: Context) {

    init {
        nativeInit()
    }

    // Called from C bridge via JNI when any typed event fires (INTENT, HABIT_UPDATE, etc.)
    @Suppress("unused")
    fun onNativeEvent(type: String, payload: String) {
        when (type) {
            "WAKE_WORD"    -> KateEventBus.emit(KateEvent.WakeWordDetected)
            "INTENT"       -> {
                val parts   = payload.split("|")
                val intent  = parts.getOrNull(0)
                    ?.let { runCatching { IntentType.valueOf(it) }.getOrNull() }
                    ?: IntentType.UNKNOWN
                val entity  = parts.getOrNull(1) ?: ""
                val emotion = parts.getOrNull(2)
                    ?.let { runCatching { EmotionType.valueOf(it) }.getOrNull() }
                    ?: EmotionType.NEUTRAL
                KateEventBus.emit(KateEvent.IntentEvent(intent, entity, emotion))
            }
            "HABIT_UPDATE" -> {
                val parts  = payload.split("|")
                val intent = parts.getOrNull(0) ?: return
                val entity = parts.getOrNull(1) ?: return
                KateEventBus.emit(KateEvent.HabitUpdate(intent, entity))
            }
            "SUGGESTION"   -> KateEventBus.emit(KateEvent.Suggestion(payload))
            "ERROR"        -> KateEventBus.emit(KateEvent.Error(payload))
        }
    }

    // Called directly from C bridge via JNI when the VAD wake-word path fires.
    // notify_wake_word_detected() in bridge.c looks up this exact method name.
    // Must be kept by ProGuard — see proguard-rules.pro.
    @Suppress("unused")
    fun onWakeWordDetected() {
        KateEventBus.emit(KateEvent.WakeWordDetected)
    }

    external fun nativeInit()
    external fun processText(text: String)
    external fun startAudio()
    external fun stopAudio()
    external fun updateAppList(apps: Array<String>)
    external fun loadHabits(habits: Array<String>)
    external fun requestSuggestion()

    companion object {
        init { System.loadLibrary("kate_core") }
    }
}
