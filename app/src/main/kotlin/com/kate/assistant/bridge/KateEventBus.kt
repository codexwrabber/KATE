package com.kate.assistant.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

object KateEventBus {
    private val _events = MutableSharedFlow<KateEvent>(extraBufferCapacity = 64)
    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun emit(event: KateEvent) { _events.tryEmit(event) }

    // Called from MainActivity with speech results
    fun emitSpeech(text: String) {
        _events.tryEmit(KateEvent.Error("Heard: $text"))
        _events.tryEmit(KateEvent.SpeechResult(text))
    }

    fun subscribe(handler: (KateEvent) -> Unit) {
        scope.launch { _events.collect { handler(it) } }
    }
}
