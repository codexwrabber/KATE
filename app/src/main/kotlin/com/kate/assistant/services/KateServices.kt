package com.kate.assistant.services

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kate.assistant.bridge.KateBridge
import com.kate.assistant.bridge.KateEvent
import com.kate.assistant.bridge.KateEventBus
import com.kate.assistant.data.db.HabitDao
import com.kate.assistant.data.db.HabitEntity
import com.kate.assistant.data.db.KateDatabase
import com.kate.assistant.features.device.KateDeviceController
import com.kate.assistant.features.device.KateHardwareController
import com.kate.assistant.features.launcher.KateAppLauncher
import com.kate.assistant.features.launcher.SearchEngine
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
    private lateinit var hardware: KateHardwareController
    private lateinit var launcher: KateAppLauncher
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var db: KateDatabase
    private lateinit var habitDao: HabitDao
    private lateinit var phantomJournal: PhantomJournal
    private lateinit var proactiveEngine: ProactiveEngine
    private lateinit var intentClassifier: IntentClassifier
    private lateinit var vectorizer: TextVectorizer
    private lateinit var labelMapper: LabelMapper

    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

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
            hardware          = KateHardwareController(this)
            launcher          = KateAppLauncher(this)
            reminderScheduler = ReminderScheduler(this)
            phantomJournal    = PhantomJournal(this)
            proactiveEngine   = ProactiveEngine(this)
            intentClassifier  = IntentClassifier(this)
            vectorizer        = TextVectorizer()
            labelMapper       = LabelMapper(this)
            db                = KateDatabase.getDatabase(this)
            habitDao          = db.habitDao()

            speechManager = KateSpeechManager(
                context  = this,
                onResult = { text -> handleSpeech(text) },
                onError  = { error -> Log.e("Kate", "Speech error: $error") }
            )

            bridge.updateAppList(loadInstalledApps())

            scope.launch {
                val formatted = habitDao.getAll()
                    .map { "${it.intent}|${it.entity}|${it.count}" }
                    .toTypedArray()
                bridge.loadHabits(formatted)
            }

            KateEventBus.subscribe { event ->
                when (event) {
                    is KateEvent.WakeWordDetected -> Log.d("Kate", "Wake word!")
                    is KateEvent.HabitUpdate      -> persistHabit(event)
                    is KateEvent.AppOpened        -> {
                        phantomJournal.logAppOpen(event.packageName)
                        proactiveEngine.evaluate()
                    }
                    is KateEvent.Error -> Log.d("Kate", event.message)
                    else               -> Unit
                }
            }

            bridge.startAudio()

        } catch (e: Exception) {
            Log.e("KateService", "Init error: ${e.message}")
        }

        mainHandler.postDelayed({
            speechManager.startListening()
            mainHandler.postDelayed({
                speak("Kate is online.")
            }, 500)
        }, 800)

        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!speechManager.isListening() && !speechManager.isSpeaking()) {
                    Log.w("Kate", "Watchdog: restarting speech")
                    speechManager.startListening()
                }
                mainHandler.postDelayed(this, 300_000L)
            }
        }, 300_000L)
    }

    // ── Speech router ─────────────────────────────────────────
    private fun handleSpeech(text: String) {
        when (text.uppercase().trim()) {
            "WAKE" -> speak("Yes?", 600)
            else -> scope.launch { handleVoiceCommand(text) }
        }
    }

    private fun speak(text: String, delayMs: Long = -1L) {
        speechManager.setSpeaking(true)
        tts.speak(text)
        val words = text.split(" ").size
        val delay = if (delayMs > 0) delayMs else (words * 400L + 800L)
        mainHandler.postDelayed({
            speechManager.setSpeaking(false)
        }, delay)
    }

    // ── FULL COMMAND HANDLER ─────────────────────────────────
    private suspend fun handleVoiceCommand(text: String) {
        val lower = text.lowercase().trim()
        Log.d("Kate", "Command: $lower")

        if (lower.length < 3 || lower.split(" ").all { it.length < 2 }) return

        when {

            // ── APP LAUNCH FIX ───────────────────────────────
            lower.contains("open") || lower.contains("launch") -> {
                val appName = when {
                    "open" in lower -> lower.substringAfter("open")
                    "launch" in lower -> lower.substringAfter("launch")
                    else -> ""
                }.trim()

                if (appName.isBlank()) { speak("Which app?"); return }

                val launched = launcher.launchByVoiceCommand(appName)

                if (launched) speak("Opening $appName")
                else speak("I couldn't find $appName. Searching Play Store.")
            }

            // ── MUSIC ─────────────────────────────────────────
            lower.contains("play music") ||
            lower.contains("play songs") ||
            lower.contains("music") -> {
                speak("Opening music")
                launcher.openMusicApp()
            }

            // ── SEARCH ────────────────────────────────────────
            lower.contains("search for") ||
            lower.contains("google") -> {
                val query = lower
                    .replace("search for", "")
                    .replace("google", "")
                    .trim()

                if (query.isBlank()) { speak("What should I search?"); return }
                speak("Searching for $query")
                launcher.search(query)
            }

            lower.contains("youtube") -> {
                val query = lower.replace("youtube", "").trim()
                speak("Opening YouTube")
                launcher.search(query, SearchEngine.YOUTUBE)
            }

            // ── CALL FIX ──────────────────────────────────────
            lower.contains("call ") -> {
                val name = lower.substringAfter("call ").trim()

                if (name.isBlank()) { speak("Who should I call?"); return }

                val number = lookupContact(name)

                if (number != null) {
                    speak("Calling $name", 800)
                    makeCall(number)
                } else {
                    speak("I couldn't find $name in your contacts")
                }
            }

            lower.contains("dial ") -> {
                val number = lower.substringAfter("dial").trim()
                if (number.isBlank()) { speak("What number?"); return }
                speak("Dialing $number", 800)
                makeCall(number)
            }

            // ── SMS FIX (MINIMAL CHANGE) ──────────────────────
            lower.contains("send message to") ||
            lower.contains("text ") ||
            lower.contains("sms ") -> {

                val cleaned = lower
                    .replace("send message to", "")
                    .replace("text", "")
                    .replace("sms", "")
                    .trim()

                val parts = cleaned.split(" saying ", limit = 2)

                val name = parts.getOrNull(0)?.trim() ?: ""
                val message = parts.getOrNull(1)?.trim() ?: ""

                if (name.isBlank()) { speak("Who should I message?"); return }
                if (message.isBlank()) { speak("What should I say?"); return }

                val number = lookupContact(name)

                if (number != null) {
                    sendSms(number, message)
                    speak("Message sent to $name")
                } else {
                    speak("I couldn't find $name in your contacts")
                }
            }

            // ── (UNCHANGED REST OF FILE) ──────────────────────
            // Everything below remains exactly as your original code
            // ...
