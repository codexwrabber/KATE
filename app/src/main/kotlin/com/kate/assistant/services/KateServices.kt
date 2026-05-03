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

        // Start listening
        mainHandler.postDelayed({
            speechManager.startListening()
            mainHandler.postDelayed({
                speak("Kate is online.")
            }, 500)
        }, 800)

        // Watchdog — restart if silent for 5 minutes
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
            "WAKE" -> {
                Log.d("Kate", "Wake word detected")
                speak("Yes?", 600)
            }
            else -> scope.launch { handleVoiceCommand(text) }
        }
    }

    // ── Speak — pauses mic while Kate talks ──────────────────
    // FIXED: Line 175 - removed boolean expectation since setSpeaking returns Unit
    private fun speak(text: String, delayMs: Long = -1L) {
        speechManager.setSpeaking(true)
        tts.speak(text)
        val words = text.split(" ").size
        val delay = if (delayMs > 0) delayMs else (words * 400L + 800L)
        mainHandler.postDelayed({
            speechManager.setSpeaking(false)  // ← This was line 175 - now fixed
        }, delay)
    }

    // ── Full command handler ──────────────────────────────────
    private suspend fun handleVoiceCommand(text: String) {
        val lower = text.lowercase().trim()
        Log.d("Kate", "Command: $lower")

        // Ignore noise
        if (lower.length < 3 || lower.split(" ").all { it.length < 2 }) {
            Log.d("Kate", "Ignoring noise: $lower")
            return
        }

        when {

            // ── App launching ─────────────────────────────────
            lower.contains("open") ||
            lower.contains("launch") -> {
                val appName = lower
                    .replace("open", "")
                    .replace("launch", "")
                    .trim()
                if (appName.isBlank()) { speak("Which app?"); return }
                val launched = launcher.launchByVoiceCommand(appName)
                if (launched) speak("Opening $appName")
                else speak("I couldn't find $appName. Searching Play Store.")
            }

            // ── Music ─────────────────────────────────────────
            lower.contains("play music") ||
            lower.contains("play songs") ||
            lower.contains("music") -> {
                speak("Opening music")
                launcher.openMusicApp()
            }

            // ── Search ────────────────────────────────────────
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

            // ── Calls ─────────────────────────────────────────
            lower.contains("call ") -> {
                val name = lower.substringAfter("call").trim()
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

            // ── SMS ───────────────────────────────────────────
            lower.contains("send message to") ||
            lower.contains("text ") ||
            lower.contains("sms ") -> {
                val parts   = lower
                    .replace("send message to", "")
                    .replace("text", "")
                    .replace("sms", "")
                    .trim()
                    .split(" saying ")
                val name    = parts.getOrNull(0)?.trim() ?: ""
                val message = parts.getOrNull(1)?.trim() ?: ""
                if (name.isBlank())    { speak("Who should I message?"); return }
                if (message.isBlank()) { speak("What should I say?");    return }
                val number = lookupContact(name)
                if (number != null) {
                    sendSms(number, message)
                    speak("Message sent to $name")
                } else {
                    speak("I couldn't find $name in your contacts")
                }
            }

            // ── Flashlight ────────────────────────────────────
            lower.contains("torch on")           ||
            lower.contains("flashlight on")      ||
            lower.contains("turn on torch")      ||
            lower.contains("turn on flashlight") -> {
                hardware.torchOn()
                speak("Flashlight on")
            }

            lower.contains("torch off")           ||
            lower.contains("flashlight off")      ||
            lower.contains("turn off torch")      ||
            lower.contains("turn off flashlight") -> {
                hardware.torchOff()
                speak("Flashlight off")
            }

            // ── Volume ────────────────────────────────────────
            lower.contains("volume up") ||
            lower.contains("increase volume") -> {
                hardware.volumeUp()
                speak("Volume up")
            }

            lower.contains("volume down")     ||
            lower.contains("decrease volume") ||
            lower.contains("lower volume")    -> {
                hardware.volumeDown()
                speak("Volume down")
            }

            lower.contains("unmute") -> {
                hardware.unmuteAll()
                speak("Unmuted")
            }

            lower.contains("mute") -> {
                hardware.muteAll()
                speak("Muted")
            }

            // ── DND ───────────────────────────────────────────
            lower.contains("do not disturb on") ||
            lower.contains("silence") -> {
                val nm = getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    speak("I need notification access. Opening settings.")
                    startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    if (hardware.setDND(true)) speak("Do not disturb enabled")
                    else speak("Could not enable do not disturb")
                }
            }

            lower.contains("do not disturb off") -> {
                val nm = getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    speak("I need notification access. Opening settings.")
                    startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    if (hardware.setDND(false)) speak("Do not disturb disabled")
                    else speak("Could not disable do not disturb")
                }
            }

            // ── Reminders ─────────────────────────────────────
            lower.contains("remind me") ||
            lower.contains("set reminder") ||
            lower.contains("set alarm") -> {
                val delayMs = parseTimeFromText(lower)
                if (delayMs > 0) {
                    val task = lower
                        .replace("remind me to", "")
                        .replace("remind me", "")
                        .replace("set reminder", "")
                        .replace("set alarm", "")
                        .trim()
                        .split(Regex("in \\d+ (minute|hour)"))
                        .firstOrNull()?.trim() ?: "task"
                    reminderScheduler.schedule(task, delayMs)
                    val unit = if (delayMs < 3_600_000L)
                        "${delayMs / 60_000} minutes"
                    else
                        "${delayMs / 3_600_000} hours"
                    speak("Reminder set for $unit from now")
                } else {
                    speak("When should I remind you? Say something like remind me in 10 minutes")
                }
            }

            // ── Browser ───────────────────────────────────────
            lower.contains("open browser") ||
            lower.contains("open chrome") -> {
                speak("Opening browser")
                launcher.openBrowser()
            }

            // ── Greetings ─────────────────────────────────────
            lower.contains("hello") ||
            lower.contains("hi kate") ||
            lower.contains("hey kate") -> {
                speak("Hello! How can I help you?")
            }

            lower.contains("how are you") -> {
                speak("I'm doing great, always ready to help!")
            }

            lower.contains("what can you do") ||
            lower.contains("help") -> {
                speak("I can open apps, make calls, send messages, search the web, control your flashlight, volume, set reminders, and much more.")
            }

            lower.contains("what's your name") ||
            lower.contains("who are you") -> {
                speak("I'm Kate. Your Kernel-level Autonomous Task Engine and Personal assistant.")
            }

            // ── Time / Date ───────────────────────────────────
            lower.contains("what time") ||
            lower.contains("current time") -> {
                val time = java.text.SimpleDateFormat(
                    "h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date())
                speak("It is $time")
            }

            lower.contains("what date")    ||
            lower.contains("today's date") ||
            lower.contains("what day") -> {
                val date = java.text.SimpleDateFormat(
                    "EEEE, MMMM d yyyy", java.util.Locale.getDefault())
                    .format(java.util.Date())
                speak("Today is $date")
            }

            // ── Accessibility ─────────────────────────────────
            lower.contains("go back") -> {
                if (KateAccessibilityService.instance != null) {
                    KateAccessibilityService.instance?.goBack()
                } else {
                    speak("Please enable Kate accessibility service first")
                }
            }

            lower.contains("go home") -> {
                if (KateAccessibilityService.instance != null) {
                    KateAccessibilityService.instance?.goHome()
                } else {
                    speak("Please enable Kate accessibility service first")
                }
            }

            lower.contains("show notifications") ||
            lower.contains("open notifications") -> {
                if (KateAccessibilityService.instance != null) {
                    KateAccessibilityService.instance?.showNotifications()
                } else {
                    speak("Accessibility service not enabled")
                }
            }

            lower.contains("take screenshot") -> {
                if (KateAccessibilityService.instance != null) {
                    KateAccessibilityService.instance?.takeScreenshot()
                    speak("Screenshot taken")
                } else {
                    speak("Accessibility service not enabled")
                }
            }

            lower.contains("recent apps") ||
            lower.contains("show recents") -> {
                if (KateAccessibilityService.instance != null) {
                    KateAccessibilityService.instance?.openRecents()
                } else {
                    speak("Accessibility service not enabled")
                }
            }

            lower.contains("type ") ||
            lower.contains("write ") -> {
                val typing = lower
                    .replace("type", "")
                    .replace("write", "")
                    .trim()
                if (KateAccessibilityService.instance != null) {
                    val ok = KateAccessibilityService.instance?.ghostType(typing) ?: false
                    speak(if (ok) "Done" else "Could not find a text field")
                } else {
                    speak("Please enable Kate accessibility service to type")
                }
            }

            lower.contains("read screen") ||
            lower.contains("what's on screen") -> {
                if (KateAccessibilityService.instance != null) {
                    val screen = KateAccessibilityService.instance?.readScreen() ?: ""
                    if (screen.isNotBlank()) speak(screen.take(200))
                    else speak("Nothing readable on screen")
                } else {
                    speak("Please enable Kate accessibility service to read the screen")
                }
            }

            // ── Stop ──────────────────────────────────────────
            lower.contains("stop listening") ||
            lower.contains("goodbye kate")   ||
            lower.contains("bye kate") -> {
                speak("Goodbye!")
                mainHandler.postDelayed({ speechManager.stopListening() }, 1500)
            }

            // ── TFLite fallback ───────────────────────────────
            else -> {
                if (lower.length > 2) {
                    val intent = try {
                        withContext(Dispatchers.IO) {
                            intentClassifier.classify(text)
                        }
                    } catch (e: Exception) { "UNKNOWN" }
                    Log.d("Kate", "TFLite: $intent")
                    when (intent) {
                        "OPEN_APP"       -> speak("Which app should I open?")
                        "MEDIA_CONTROL"  -> { speak("Opening music"); launcher.openMusicApp() }
                        "COMMUNICATION"  -> speak("Who should I contact?")
                        "REMINDER"       -> speak("When should I remind you?")
                        "SYSTEM_CONTROL" -> speak("What system setting would you like to change?")
                        else             -> speak("I didn't catch that. Try again.")
                    }
                }
            }
        }

        try { bridge.processText(text) } catch (e: Exception) { }
    }

    // ── Time parser ───────────────────────────────────────────
    private fun parseTimeFromText(text: String): Long {
        val minuteMatch = Regex("(\\d+)\\s*minute").find(text)
        val hourMatch   = Regex("(\\d+)\\s*hour").find(text)
        return when {
            minuteMatch != null -> minuteMatch.groupValues[1].toLong() * 60_000L
            hourMatch   != null -> hourMatch.groupValues[1].toLong()   * 3_600_000L
            else                -> -1L
        }
    }

    // ── Contact lookup ────────────────────────────────────────
    private fun lookupContact(name: String): String? {
        return try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst())
                    it.getString(it.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER))
                else null
            }
        } catch (e: Exception) {
            Log.e("Kate", "Contact lookup: ${e.message}")
            null
        }
    }

    private fun makeCall(number: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${number.trim()}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            )
        } catch (e: Exception) {
            speak("I couldn't make the call")
            Log.e("Kate", "Call failed: ${e.message}")
        }
    }

    private fun sendSms(number: String, message: String) {
        try {
            SmsManager.getDefault()
                .sendTextMessage(number.trim(), null, message, null, null)
        } catch (e: Exception) {
            speak("I couldn't send the message")
            Log.e("Kate", "SMS failed: ${e.message}")
        }
    }

    private fun persistHabit(event: KateEvent.HabitUpdate) {
        scope.launch {
            val key      = "${event.intent}_${event.entity}"
            val existing = habitDao.getAll().find { it.key == key }
            habitDao.insert(HabitEntity(
                key    = key,
                intent = event.intent,
                entity = event.entity,
                count  = (existing?.count ?: 0) + 1
            ))
        }
    }

    private fun loadInstalledApps(): Array<String> =
        packageManager.getInstalledApplications(0).map {
            "${packageManager.getApplicationLabel(it).toString().lowercase()}|${it.packageName}"
        }.toTypedArray()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        speechManager.shutdown()
        bridge.stopAudio()
        intentClassifier.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceSafe() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kate Assistant",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kate is running")
            .setContentText("Always listening...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
