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

        speechManager = KateSpeechManager(this) { text -> bridge.processText(text) }

        bridge.updateAppList(loadInstalledApps())

        scope.launch {
            val formatted = habitDao.getAll()
                .map { "${it.intent}|${it.entity}|${it.count}" }.toTypedArray()
            bridge.loadHabits(formatted)
        }

        KateEventBus.subscribe { event ->
            when (event) {
                is KateEvent.WakeWordDetected -> {
                    Log.d("Kate", "Wake word!")
                    speechManager.startListening()
                }
                is KateEvent.IntentEvent  -> handleIntent(event)
                is KateEvent.HabitUpdate  -> persistHabit(event)
                is KateEvent.Suggestion   -> {
                    val ok = deviceController.openApp(event.entity)
                    tts.speak(if (ok) "Opening your usual app" else "You usually open this app now")
                }
                is KateEvent.AppOpened    -> {
                    phantomJournal.logAppOpen(event.packageName)
                    proactiveEngine.evaluate()
                }
                is KateEvent.Error        -> Log.e("Kate", event.message)
            }
        }

        bridge.startAudio()
        // Temporary — auto-trigger listening without wake word
scope.launch {
    kotlinx.coroutines.delay(2000)
    speechManager.startListening()
    tts.speak("Kate is ready. Speak your command.")
    
    }
}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() { bridge.stopAudio(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    // ── Intent handler — aligned with trained model labels ───
    private fun handleIntent(event: KateEvent.IntentEvent) {
        applyEmotion(event.emotion)
        when (event.intent) {
            IntentType.OPEN_APP -> {
                if (event.entity.isBlank()) { tts.speak("Which app should I open?"); return }
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
            EmotionType.CALM     -> Unit
            EmotionType.NEUTRAL  -> Unit
        }
    }

    private fun handleReminder(entity: String) {
        val parts = entity.split("|")
        val task  = parts.getOrNull(0) ?: "task"
        val delay = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        if (delay > 0) { reminderScheduler.schedule(task, delay); tts.speak("Reminder set for $task") }
        else tts.speak("I couldn't understand the time")
    }

    private fun handleCommunication(entity: String) {
        tts.speak("Who should I contact?")
        // Phase 3: extract contact name and route to call/SMS
    }

    private fun persistHabit(event: KateEvent.HabitUpdate) {
        scope.launch {
            val key      = "${event.intent}_${event.entity}"
            val existing = habitDao.getAll().find { it.key == key }
            habitDao.insert(HabitEntity(key = key, intent = event.intent, entity = event.entity, count = (existing?.count ?: 0) + 1))
        }
    }

    private fun loadInstalledApps(): Array<String> =
        packageManager.getInstalledApplications(0).map {
            "${packageManager.getApplicationLabel(it).toString().lowercase()}|${it.packageName}"
        }.toTypedArray()

    private fun startForegroundServiceSafe() {
        val channel = NotificationChannel(CHANNEL_ID, "Kate Assistant", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Kate is running")
                .setContentText("Listening for wake word...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true).setSilent(true).build()
        )
    }
}
