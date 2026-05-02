package com.kate.assistant.services

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
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
    }

    private fun handleSpeech(text: String) {
        when (text.uppercase().trim()) {
            "WAKE" -> {
                Log.d("Kate", "Wake word detected")
                speak("Yes?")
            }
            else -> scope.launch { handleVoiceCommand(text) }
        }
    }

    private fun speak(text: String, delayMs: Long = 1200L) {
        speechManager.setSpeaking(true)
        tts.speak(text)
        mainHandler.postDelayed({
            speechManager.setSpeaking(false)
        }, delayMs)
    }

    private suspend fun handleVoiceCommand(text: String) {
        val lower = text.lowercase().trim()
        Log.d("Kate", "Command: $lower")

        when {
            lower.contains("open") ||
            lower.contains("launch") -> {
                val appName = lower
                    .replace("open", "").replace("launch", "").trim()
                if (appName.isBlank()) { speak("Which app?"); return }
                speak("Opening $appName")
                launcher.launchByVoiceCommand(appName)
            }

            lower.contains("play music") ||
            lower.contains("play songs") ||
            lower.contains("music") -> {
                speak("Opening music")
                launcher.openMusicApp()
            }

            lower.contains("search for") ||
            lower.contains("google") -> {
                val query = lower
                    .replace("search for", "").replace("google", "").trim()
                speak("Searching for $query")
                launcher.search(query)
            }

            lower.contains("youtube") -> {
                val query = lower.replace("youtube", "").trim()
                speak("Opening YouTube")
                launcher.search(query, SearchEngine.YOUTUBE)
            }

            lower.contains("call ") -> {
                val name   = lower.substringAfter("call").trim()
                val number = lookupContact(name)
                if (number != null) { speak("Calling $name", 800); makeCall(number) }
                else speak("I couldn't find $name in your contacts")
            }

            lower.contains("dial ") -> {
                val number = lower.substringAfter("dial").trim()
                speak("Dialing $number", 800)
                makeCall(number)
            }

            lower.contains("send message to") ||
            lower.contains("text ") ||
            lower.contains("sms ") -> {
                val parts   = lower
                    .replace("send message to", "")
                    .replace("text", "").replace("sms", "")
                    .trim().split(" saying ")
                val name    = parts.getOrNull(0)?.trim() ?: ""
                val message = parts.getOrNull(1)?.trim() ?: ""
                if (name.isBlank())    { speak("Who should I message?"); return }
                if (message.isBlank()) { speak("What should I say?");    return }
                val number = lookupContact(name)
                if (number != null) { sendSms(number, message); speak("Message sent to $name") }
                else speak("I couldn't find $name in your contacts")
            }

            lower.contains("torch on") ||
            lower.contains("flashlight on") ||
            lower.contains("turn on torch") ||
            lower.contains("turn on flashlight") -> {
                hardware.torchOn(); speak("Flashlight on")
            }

            lower.contains("torch off") ||
            lower.contains("flashlight off") ||
            lower.contains("turn off torch") ||
            lower.contains("turn off flashlight") -> {
                hardware.torchOff(); speak("Flashlight off")
            }

            lower.contains("volume up") ||
            lower.contains("increase volume") -> {
                hardware.volumeUp(); speak("Volume up")
            }

            lower.contains("volume down") ||
            lower.contains("decrease volume") ||
            lower.contains("lower volume") -> {
                hardware.volumeDown(); speak("Volume down")
            }

            lower.contains("mute") -> {
                hardware.muteAll(); speak("Muted")
            }

            lower.contains("do not disturb on") ||
            lower.contains("silence") -> {
                if (hardware.setDND(true)) speak("Do not disturb enabled")
                else speak("Cannot enable DND. Please grant notification policy access.")
            }

            lower.contains("do not disturb off") -> {
                if (hardware.setDND(false)) speak("Do not disturb disabled")
                else speak("Cannot disable DND. Please grant notification policy access.")
            }

            lower.contains("remind me") ||
            lower.contains("set reminder") ||
            lower.contains("set alarm") -> {
                speak("Reminder noted. I'm still learning to schedule precisely.")
            }

            lower.contains("open browser") ||
            lower.contains("open chrome") -> {
                speak("Opening browser"); launcher.openBrowser()
            }

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
                speak("I can open apps, make calls, send messages, search the web, control your flashlight and volume, and much more.")
            }

            lower.contains("what time") -> {
                val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date())
                speak("It is $time")
            }

            lower.contains("what date") ||
            lower.contains("today's date") -> {
                val date = java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault())
                    .format(java.util.Date())
                speak("Today is $date")
            }

            lower.contains("go back") -> {
                KateAccessibilityService.instance?.goBack()
                speak("Going back")
            }

            lower.contains("go home") -> {
                KateAccessibilityService.instance?.goHome()
                speak("Going home")
            }

            lower.contains("show notifications") ||
            lower.contains("open notifications") -> {
                KateAccessibilityService.instance?.showNotifications()
                speak("Opening notifications")
            }

            lower.contains("take screenshot") -> {
                KateAccessibilityService.instance?.takeScreenshot()
                speak("Screenshot taken")
            }

            lower.contains("recent apps") ||
            lower.contains("show recents") -> {
                KateAccessibilityService.instance?.openRecents()
                speak("Recent apps")
            }

            lower.contains("type ") ||
            lower.contains("write ") -> {
                val typing = lower.replace("type", "").replace("write", "").trim()
                val ok = KateAccessibilityService.instance?.ghostType(typing) ?: false
                speak(if (ok) "Typed" else "Nothing to type into")
            }

            lower.contains("read screen") ||
            lower.contains("what's on screen") -> {
                val screen = KateAccessibilityService.instance?.readScreen() ?: ""
                speak(if (screen.isNotBlank()) screen.take(200) else "Nothing on screen")
            }

            lower.contains("stop listening") ||
            lower.contains("goodbye kate") ||
            lower.contains("bye kate") -> {
                speak("Goodbye!")
                speechManager.stopListening()
            }

            else -> {
                if (lower.isNotBlank()) {
                    val intent = try {
                        withContext(Dispatchers.IO) { intentClassifier.classify(text) }
                    } catch (e: Exception) { "UNKNOWN" }
                    Log.d("Kate", "TFLite: $intent")
                    when (intent) {
                        "OPEN_APP"       -> speak("Which app should I open?")
                        "MEDIA_CONTROL"  -> { speak("Opening music"); launcher.openMusicApp() }
                        "COMMUNICATION"  -> speak("Who should I contact?")
                        "REMINDER"       -> speak("What should I remind you about?")
                        "SYSTEM_CONTROL" -> speak("What system setting?")
                        else             -> speak("You said $text. I am still learning.")
                    }
                }
            }
        }

        try { bridge.processText(text) } catch (e: Exception) { }
    }

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
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .let { startActivity(it) }
        } catch (e: Exception) { speak("I couldn't make the call") }
    }

    private fun sendSms(number: String, message: String) {
        try {
            SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
        } catch (e: Exception) { speak("I couldn't send the message") }
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
        speechManager.stopListening()
        bridge.stopAudio()
        intentClassifier.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
