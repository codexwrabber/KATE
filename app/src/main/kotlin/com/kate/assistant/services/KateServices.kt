package com.kate.assistant.services

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kate.assistant.bridge.*
import com.kate.assistant.data.db.*
import com.kate.assistant.features.device.KateDeviceController
import com.kate.assistant.features.nlp.IntentClassifier
import com.kate.assistant.features.nlp.LabelMapper
import com.kate.assistant.features.nlp.TextVectorizer
import com.kate.assistant.features.phantom.PhantomJournal
import com.kate.assistant.features.phantom.ProactiveEngine
import com.kate.assistant.features.tasks.ReminderScheduler
import com.kate.assistant.features.voice.KateSpeechManager
import com.kate.assistant.features.voice.KateTts
import kotlinx.coroutines.*

class KateService : Service() {

    private lateinit var bridge: KateBridge
    private lateinit var speechManager: KateSpeechManager
    private lateinit var tts: KateTts
    private lateinit var deviceController: KateDeviceController
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var db: KateDatabase
    private lateinit var habitDao: HabitDao
    private lateinit var phantomJournal: PhantomJournal
    private lateinit var proactiveEngine: ProactiveEngine
    private lateinit var intentClassifier: IntentClassifier
    private lateinit var vectorizer: TextVectorizer
    private lateinit var labelMapper: LabelMapper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val CHANNEL_ID      = "kate_service_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceSafe()

        try {
            bridge            = KateBridge(this)
            tts               = KateTts(this)
            deviceController  = KateDeviceController(this)
            reminderScheduler = ReminderScheduler(this)
            phantomJournal    = PhantomJournal(this)
            proactiveEngine   = ProactiveEngine(this)
            intentClassifier  = IntentClassifier(this)
            vectorizer        = TextVectorizer()
            labelMapper       = LabelMapper(this)
            db                = KateDatabase.getDatabase(this)
            habitDao          = db.habitDao()

            speechManager = KateSpeechManager(this) { text ->
                Log.d("Kate", "Speech result: $text")
                val lower = text.lowercase()

                // Show what was heard on screen
                KateEventBus.emit(KateEvent.Error("Heard: $text"))

                // Handle directly in Kotlin
                when {
                    lower.contains("open") ||
                    lower.contains("launch") -> {
                        val appName = lower
                            .replace("open", "")
                            .replace("launch", "")
                            .trim()
                        tts.speak("Opening $appName")
                        val ok = deviceController.openApp(appName)
                        if (!ok) tts.speak("I couldn't find $appName")
                    }
                    lower.contains("play") -> {
                        tts.speak("Playing music")
                        deviceController.openApp("com.spotify.music")
                    }
                    lower.contains("call") -> {
                        tts.speak("Who should I call?")
                    }
                    lower.contains("remind") -> {
                        tts.speak("Reminder noted")
                    }
                    lower.contains("torch") ||
                    lower.contains("flashlight") -> {
                        tts.speak("Toggling flashlight")
                    }
                    lower.contains("hello") ||
                    lower.contains("hi") -> {
                        tts.speak("Hello! How can I help you?")
                    }
                    lower.contains("volume up") -> {
                        tts.speak("Turning volume up")
                    }
                    lower.contains("volume down") -> {
                        tts.speak("Turning volume down")
                    }
                    lower.contains("stop") ||
                    lower.contains("bye") -> {
                        tts.speak("Goodbye!")
                    }
                    else -> {
                        tts.speak("You said $text. I am still learning.")
                    }
                }

                // Also send to C engine for habit learning
                try { bridge.processText(text) } catch (e: Exception) { }

                // Re-listen automatically
                scope.launch(Dispatchers.Main) {
                    delay(500)
                    speechManager.startListening()
                }
            }

            bridge.updateAppList(loadInstalledApps())

            scope.launch {
                val formatted = habitDao.getAll()
                    .map { "${it.intent}|${it.entity}|${it.count}" }
                    .toTypedArray()
                bridge.loadHabits(formatted)
            }

            KateEventBus.subscribe { event ->
                when (event) {
                    is KateEvent.WakeWordDetected -> {
                        Log.d("Kate", "Wake word detected!")
                        speechManager.startListening()
                    }
                    is KateEvent.IntentEvent  -> handleIntent(event)
                    is KateEvent.HabitUpdate  -> persistHabit(event)
                    is KateEvent.Suggestion   -> {
                        val ok = deviceController.openApp(event.entity)
                        tts.speak(
                            if (ok) "Opening your usual app"
                            else "You usually open this app now"
                        )
                    }
                    is KateEvent.AppOpened -> {
                        phantomJournal.logAppOpen(event.packageName)
                        proactiveEngine.evaluate()
                    }
                    is KateEvent.Error -> Log.e("Kate", event.message)
                }
            }

            bridge.startAudio()

// Auto-trigger on main thread — SpeechRecognizer requires main looper
Handler(Looper.getMainLooper()).postDelayed({
    tts.speak("Kate is ready. Speak your command.")
    Handler(Looper.getMainLooper()).postDelayed({
        speechManager.startListening()
    }, 2000)
}, 3000)
        } catch (e: Exception) {
            Log.e("KateService", "Startup error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        bridge.stopAudio()
        speechManager.stopListening()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleIntent(event: KateEvent.IntentEvent) {
        applyEmotion(event.emotion)
        when (event.intent) {
            IntentType.OPEN_APP -> {
                if (event.entity.isBlank()) {
                    tts.speak("Which app should I open?")
                    return
                }
                val ok = deviceController.openApp(event.entity)
                tts.speak(if (ok) "Opening app" else "I couldn't find that app")
            }
            IntentType.MEDIA_CONTROL  -> tts.speak("Handling media")
            IntentType.COMMUNICATION  -> handleCommunication(event.entity)
            IntentType.REMINDER       -> handleReminder(event.entity)
            IntentType.SYSTEM_CONTROL -> tts.speak("Handling system control")
            IntentType.UNKNOWN        -> tts.speak("Sorry, I didn't understand that")
        }
    }

    private fun applyEmotion(emotion: EmotionType) {
        when (emotion) {
            EmotionType.STRESSED -> tts.speak("You sound stressed. I'll keep it simple.")
            EmotionType.URGENT   -> tts.speak("Got it. On it now.")
            EmotionType.CALM,
            EmotionType.NEUTRAL  -> Unit
        }
    }

    private fun handleReminder(entity: String) {
        val parts = entity.split("|")
        val task  = parts.getOrNull(0) ?: "task"
        val delay = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        if (delay > 0) {
            reminderScheduler.schedule(task, delay)
            tts.speak("Reminder set for $task")
        } else {
            tts.speak("I couldn't understand the time")
        }
    }

    private fun handleCommunication(entity: String) {
        tts.speak("Who should I contact?")
    }

    private fun persistHabit(event: KateEvent.HabitUpdate) {
        scope.launch {
            val key      = "${event.intent}_${event.entity}"
            val existing = habitDao.getAll().find { it.key == key }
            habitDao.insert(
                HabitEntity(
                    key    = key,
                    intent = event.intent,
                    entity = event.entity,
                    count  = (existing?.count ?: 0) + 1
                )
            )
        }
    }

    private fun loadInstalledApps(): Array<String> =
        packageManager.getInstalledApplications(0).map {
            "${packageManager.getApplicationLabel(it).toString().lowercase()}|${it.packageName}"
        }.toTypedArray()

    private fun startForegroundServiceSafe() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kate Assistant",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Kate is running")
                .setContentText("Listening for your command...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setSilent(true)
                .build()
        )
    }
}
