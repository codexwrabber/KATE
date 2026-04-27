package com.kate.assistant.bridge

enum class IntentType {
    OPEN_APP, MEDIA_CONTROL, COMMUNICATION,
    REMINDER, SYSTEM_CONTROL, UNKNOWN
}

enum class EmotionType { NEUTRAL, CALM, STRESSED, URGENT }

sealed class KateEvent {
    object WakeWordDetected                                                                       : KateEvent()
    data class SpeechResult(val text: String)                                                    : KateEvent()
    data class IntentEvent(val intent: IntentType, val entity: String, val emotion: EmotionType) : KateEvent()
    data class HabitUpdate(val intent: String, val entity: String)                               : KateEvent()
    data class Suggestion(val entity: String)                                                    : KateEvent()
    data class AppOpened(val packageName: String)                                                : KateEvent()
    data class Error(val message: String)                                                        : KateEvent()
}
