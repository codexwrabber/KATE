package com.kate.assistant.services

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kate.assistant.bridge.*
import com.kate.assistant.data.db.*
import com.kate.assistant.features.device.KateDeviceController
import com.kate.assistant.features.device.KateHardwareController
import com.kate.assistant.features.launcher.KateAppLauncher
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

            // VOSK speech manager — fully offline
            speechManager = KateSpeechManager(this) { text ->
                Log.d("Kate", "VOSK heard: $text")
                KateEventBus.emit(KateEvent.Error("Heard: $text"))
                handleVoiceCommand(text)
                try { bridge.processText(text) } catch (e: Exception) { }
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
                    is KateEvent.WakeWordDetected -> Log.d("Kate", "Wake word!")
                    is KateEvent.IntentEvent      -> handleIntent(event)
                    is KateEvent.HabitUpdate      -> persistHabit(event)
                    is KateEvent.SpeechResult     -> handleVoiceCommand(event.text)
                    is KateEvent.Suggestion -> {
                        val ok = deviceController.openApp(event.entity)
                        tts.speak(if (ok) "Opening your usual app" else "You usually do this now")
                    }
                    is KateEvent.AppOpened -> {
                        phantomJournal.logAppOpen(event.packageName)
                        proactiveEngine.evaluate()
                    }
                    is KateEvent.Error -> Log.d("Kate", event.message)
                }
            }

            bridge.startAudio()

            // Start VOSK after greeting
            mainHandler.postDelayed({
                tts.speak("Kate is ready.")
                mainHandler.postDelayed({
                    speechManager.startListening()
                }, 2000)
            }, 2000)

        } catch (e: Exception) {
            Log.e("KateService", "Startup error: ${e.message}")
        }
    }

    // ── Complete voice command handler ───────────────────────
    private fun handleVoiceCommand(text: String) {
        val lower = text.lowercase().trim()
        Log.d("Kate", "Command: $lower")

        when {
            // ── App launching ────────────────────────────────
            lower.contains("open") ||
            lower.contains("launch") -> {
                val appName = lower
                    .replace("open", "")
                    .replace("launch", "")
                    .trim()
                if (appName.isBlank()) { tts.speak("Which app?"); return }
                tts.speak("Opening $appName")
                launcher.launchByVoiceCommand(appName)
            }

            // ── Music / Media ─────────────────────────────────
            lower.contains("play music") ||
            lower.contains("play songs") ||
            lower.contains("music") -> {
                tts.speak("Opening music")
                launcher.openMusicApp()
            }

            // ── Search ────────────────────────────────────────
            lower.contains("search for") ||
            lower.contains("google") -> {
                val query = lower
                    .replace("search for", "")
                    .replace("google", "")
                    .trim()
                tts.speak("Searching for $query")
                launcher.search(query)
            }

            lower.contains("youtube") -> {
                val query = lower.replace("youtube", "").trim()
                tts.speak("Opening YouTube")
                launcher.search(query, com.kate.assistant.features.launcher.SearchEngine.YOUTUBE)
            }

            // ── Phone calls ───────────────────────────────────
            lower.contains("call ") -> {
                val name = lower.substringAfter("call").trim()
                val number = lookupContact(name)
                if (number != null) {
                    tts.speak("Calling $name")
                    makeCall(number)
                } else {
                    tts.speak("I couldn't find $name in your contacts")
                }
            }

            lower.contains("dial ") -> {
                val number = lower.substringAfter("dial").trim()
                tts.speak("Dialing $number")
                makeCall(number)
            }

            // ── SMS ───────────────────────────────────────────
            lower.contains("send message to") ||
            lower.contains("text ") ||
            lower.contains("sms ") -> {
                val parts  = lower
                    .replace("send message to", "")
                    .replace("text", "")
                    .replace("sms", "")
                    .trim()
                    .split(" saying ")
                val name    = parts.getOrNull(0)?.trim() ?: ""
                val message = parts.getOrNull(1)?.trim() ?: ""
                if (name.isBlank()) { tts.speak("Who should I message?"); return }
                if (message.isBlank()) { tts.speak("What should I say?"); return }
                val number = lookupContact(name)
                if (number != null) {
                    sendSms(number, message)
                    tts.speak("Message sent to $name")
                } else {
                    tts.speak("I couldn't find $name in your contacts")
                }
            }

            // ── Hardware ──────────────────────────────────────
            lower.contains("torch on") ||
            lower.contains("flashlight on") ||
            lower.contains("turn on torch") ||
            lower.contains("turn on flashlight") -> {
                hardware.torchOn()
                tts.speak("Flashlight on")
            }

            lower.contains("torch off") ||
            lower.contains("flashlight off") ||
            lower.contains("turn off torch") ||
            lower.contains("turn off flashlight") -> {
                hardware.torchOff()
                tts.speak("Flashlight off")
            }

            lower.contains("volume up") ||
            lower.contains("increase volume") -> {
                hardware.volumeUp()
                tts.speak("Volume up")
            }

            lower.contains("volume down") ||
            lower.contains("decrease volume") ||
            lower.contains("lower volume") -> {
                hardware.volumeDown()
                tts.speak("Volume down")
            }

            lower.contains("mute") -> {
                hardware.muteAll()
                tts.speak("Muted")
            }

            lower.contains("do not disturb on") ||
            lower.contains("silence") -> {
                hardware.setDND(true)
                tts.speak("Do not disturb enabled")
            }

            lower.contains("do not disturb off") -> {
                hardware.setDND(false)
                tts.speak("Do not disturb disabled")
            }

            // ── Reminders ─────────────────────────────────────
            lower.contains("remind me") ||
            lower.contains("set reminder") ||
            lower.contains("set alarm") -> {
                tts.speak("Reminder noted. I'm still learning to schedule precisely.")
            }

            // ── Browser ───────────────────────────────────────
            lower.contains("open browser") ||
            lower.contains("open chrome") -> {
                tts.speak("Opening browser")
                launcher.openBrowser()
            }

            // ── Greetings ─────────────────────────────────────
            lower.contains("hello") ||
            lower.contains("hi kate") ||
            lower.contains("hey kate") -> {
                tts.speak("Hello! How can I help you?")
            }

            lower.contains("how are you") -> {
                tts.speak("I'm doing great, always ready to help!")
            }

            lower.contains("what can you do") ||
            lower.contains("help") -> {
                tts.speak("I can open apps, make calls, send messages, search the web, control your flashlight and volume, and much more.")
            }

            // ── Time / Date ───────────────────────────────────
            lower.contains("what time") -> {
                val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date())
                tts.speak("It is $time")
            }

            lower.contains("what date") ||
            lower.contains("today's date") -> {
                val date = java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault())
                    .format(java.util.Date())
                tts.speak("Today is $date")
            }

            // ── Accessibility actions ─────────────────────────────
lower.contains("go back") -> {
    KateAccessibilityService.instance?.goBack()
    tts.speak("Going back")
}

lower.contains("go home") -> {
    KateAccessibilityService.instance?.goHome()
    tts.speak("Going home")
}

lower.contains("show notifications") ||
lower.contains("open notifications") -> {
    KateAccessibilityService.instance?.showNotifications()
    tts.speak("Opening notifications")
}

lower.contains("take screenshot") -> {
    KateAccessibilityService.instance?.takeScreenshot()
    tts.speak("Screenshot taken")
}

lower.contains("recent apps") ||
lower.contains("show recents") -> {
    KateAccessibilityService.instance?.openRecents()
    tts.speak("Recent apps")
}

lower.contains("type ") ||
lower.contains("write ") -> {
    val text = lower
        .replace("type", "")
        .replace("write", "")
        .trim()
    val ok = KateAccessibilityService.instance?.ghostType(text) ?: false
    tts.speak(if (ok) "Typed" else "Nothing to type into")
}

lower.contains("read screen") ||
lower.contains("what's on screen") -> {
    val screen = KateAccessibilityService.instance?.readScreen() ?: ""
    tts.speak(if (screen.isNotBlank()) screen.take(200) else "Nothing on screen")
}

            // ── Stop ──────────────────────────────────────────
            lower.contains("stop listening") ||
            lower.contains("goodbye kate") ||
            lower.contains("bye kate") -> {
                tts.speak("Goodbye!")
                speechManager.stopListening()
            }

            // ── Unknown ───────────────────────────────────────
            else -> {
                if (lower.isNotBlank()) {
                    tts.speak("You said $text. I'm still learning.")
                }
            }
        }
    }

    // ── Contact lookup ───────────────────────────────────────
    private fun lookupContact(name: String): String? {
        return try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER))
                } else null
            }
        } catch (e: Exception) {
            Log.e("Kate", "Contact lookup failed: ${e.message}")
            null
        }
    }

    // ── Make call ────────────────────────────────────────────
    private fun makeCall(number: String) {
        try {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .let { startActivity(it) }
        } catch (e: Exception) {
            tts.speak("I couldn't make the call")
            Log.e("Kate", "Call failed: ${e.message}")
        }
    }

    // ── Send SMS ─────────────────────────────────────────────
    private fun sendSms(number: String, message: String) {
        try {
            SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
        } catch (e: Exception) {
            tts.speak("I couldn't send the message")
            Log.e("Kate", "SMS failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        speechManager.stopListening()
        bridge.stopAudio()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleIntent(event: KateEvent.IntentEvent) {
        when (event.intent) {
            IntentType.OPEN_APP -> {
                if (event.entity.isBlank()) { tts.speak("Which app?"); return }
                val ok = deviceController.openApp(event.entity)
                tts.speak(if (ok) "Opening" else "App not found")
            }
            IntentType.MEDIA_CONTROL  -> launcher.openMusicApp()
            IntentType.COMMUNICATION  -> tts.speak("Who should I contact?")
            IntentType.REMINDER       -> handleReminder(event.entity)
            IntentType.SYSTEM_CONTROL -> tts.speak("Handling system control")
            IntentType.UNKNOWN        -> { }
        }
    }

    private fun handleReminder(entity: String) {
        val parts = entity.split("|")
        val task  = parts.getOrNull(0) ?: "task"
        val delay = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        if (delay > 0) {
            reminderScheduler.schedule(task, delay)
            tts.speak("Reminder set for $task")
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

    private fun startForegroundServiceSafe() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Kate Assistant", NotificationManager.IMPORTANCE_LOW)
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
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
