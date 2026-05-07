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
import java.text.SimpleDateFormat
import java.util.*

class KateService : Service() {

    // ── All lateinit — init happens in background thread ─────
    private lateinit var bridge: KateBridge
    private lateinit var speech: KateSpeechManager
    private lateinit var tts: KateTts
    private lateinit var device: KateDeviceController
    private lateinit var hardware: KateHardwareController
    private lateinit var launcher: KateAppLauncher
    private lateinit var reminder: ReminderScheduler
    private lateinit var db: KateDatabase
    private lateinit var habits: HabitDao
    private lateinit var journal: PhantomJournal
    private lateinit var proactive: ProactiveEngine
    private lateinit var classifier: IntentClassifier
    private lateinit var vectorizer: TextVectorizer
    private lateinit var labelMapper: LabelMapper

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var initDone = false

    companion object {
        private const val TAG        = "KateService"
        private const val CHANNEL_ID = "kate_channel"
        private const val NOTIF_ID   = 1
        private const val USER_NAME  = "Dewrabber"
    }

    override fun onCreate() {
        super.onCreate()

        // ════════════════════════════════════════════════════
        // PHASE 1 — startForeground IMMEDIATELY
        // Android kills the app if this isn't done in < 5s
        // Do NOTHING else before this call
        // ════════════════════════════════════════════════════
        startForegroundNow()

        // PHASE 2 — heavy init on background thread
        Thread {
            try {
                initAllComponents()
                // Switch back to main for UI/speech operations
                handler.post { onInitComplete() }
            } catch (e: Exception) {
                Log.e(TAG, "Init failed: ${e.message}")
                handler.post {
                    tts.speak("Kate encountered an error starting up. Please restart me.")
                }
            }
        }.also { it.name = "kate-init"; it.start() }
    }

    private fun initAllComponents() {
        // Order matters — tts first so we can speak errors
        tts         = KateTts(this)
        bridge      = KateBridge(this)
        device      = KateDeviceController(this)
        hardware    = KateHardwareController(this)
        launcher    = KateAppLauncher(this)
        reminder    = ReminderScheduler(this)
        journal     = PhantomJournal(this)
        proactive   = ProactiveEngine(this)
        classifier  = IntentClassifier(this)
        vectorizer  = TextVectorizer()
        labelMapper = LabelMapper(this)
        db          = KateDatabase.getDatabase(this)
        habits      = db.habitDao()

        // Speech manager — model loads internally on its own thread
        speech = KateSpeechManager(
            context  = this,
            onResult = { text -> handler.post { handleSpeech(text) } },
            onError  = { err  -> Log.e(TAG, "Speech: $err") }
        )

        // Load apps and habits
        bridge.updateAppList(loadInstalledApps())
        val formatted = runBlocking {
            habits.getAll().map { "${it.intent}|${it.entity}|${it.count}" }.toTypedArray()
        }
        bridge.loadHabits(formatted)
        initDone = true
        Log.d(TAG, "✅ All components initialised")
    }

    private fun onInitComplete() {
        setupEventBus()
        bridge.startAudio()

        // Start speech — waits internally until VOSK model is ready
        speech.startListening()

        // Greet user after TTS engine is ready
        handler.postDelayed({ greetUser() }, 1500)

        // Watchdog — restart speech every 60s if dead
        startWatchdog()
    }

    private fun setupEventBus() {
        KateEventBus.subscribe { event ->
            when (event) {
                is KateEvent.WakeWordDetected -> Log.d(TAG, "Wake!")
                is KateEvent.HabitUpdate      -> saveHabit(event)
                is KateEvent.AppOpened        -> {
                    journal.logAppOpen(event.packageName)
                    proactive.evaluate()
                }
                is KateEvent.Error -> Log.d(TAG, event.message)
                else               -> Unit
            }
        }
    }

    // ── Time-based greeting ───────────────────────────────────
    private fun greetUser() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour in 5..11  -> "Good morning, $USER_NAME! Kate is online and ready to assist you. How can I help you today?"
            hour in 12..16 -> "Good afternoon, $USER_NAME! Kate here. What can I do for you?"
            hour in 17..20 -> "Good evening, $USER_NAME! Kate is always listening. How can I help?"
            else           -> "Kate is online, $USER_NAME. Always here when you need me."
        }
        speak(greeting, 4000)
    }

    // ── Speech router ─────────────────────────────────────────
    private fun handleSpeech(text: String) {
        if (!initDone) return  // Ignore speech before init completes
        when (text.uppercase().trim()) {
            "WAKE" -> speak("Yes, $USER_NAME?", 600)
            else   -> {
                val clean = text.trim()
                if (clean.length > 2) scope.launch { handleCommand(clean) }
            }
        }
    }

    // ── Speak with mic pause ──────────────────────────────────
    private fun speak(text: String, holdMs: Long = -1L) {
        speech.setSpeaking(true)
        tts.speak(text)
        val hold = if (holdMs > 0) holdMs else (text.split(" ").size * 380L + 700L)
        handler.postDelayed({ speech.setSpeaking(false) }, hold)
    }

    // ── Rotating friendly fallback responses ──────────────────
    private val fallbacks = listOf(
        "Hmm, I didn't quite catch that, $USER_NAME. Could you say it again?",
        "Sorry, I'm not sure I understood. Try once more?",
        "I'm still learning! Could you rephrase that?",
        "My ears must have slipped! Say that once more?"
    )
    private var fallbackIdx = 0
    private fun fallback() = fallbacks[fallbackIdx++ % fallbacks.size]

    // ════════════════════════════════════════════════════════
    // MAIN COMMAND HANDLER
    // ════════════════════════════════════════════════════════
    private suspend fun handleCommand(raw: String) {
        val lower = raw.lowercase().trim()
        Log.d(TAG, "Command: $lower")

        // Noise gate
        if (lower.length < 3 || lower.all { !it.isLetter() }) return

        when {

            // ── About Kate ───────────────────────────────────
            lower.contains("who are you") ||
            lower.contains("what are you") ||
            lower.contains("about yourself") -> speak(
                "I'm Kate, short for Kernel-level Autonomous Task Engine. " +
                "I'm your personal offline voice assistant. I work completely " +
                "without internet — your privacy is guaranteed. I can open apps, " +
                "make calls, send messages, search the web, control your phone, " +
                "set reminders, navigate apps, and much more.")

            lower.contains("who made you") ||
            lower.contains("who created you") ||
            lower.contains("who built you") -> speak(
                "I was built by $USER_NAME with the help of an AI development partner. " +
                "I run on a C language engine with VOSK offline speech recognition. " +
                "Truly one of a kind.")

            lower.contains("what can you do") ||
            lower.contains("your capabilities") ||
            lower.contains("help me") -> speak(
                "I can open any app, make phone calls, send text messages, " +
                "search Google or YouTube, control your flashlight, volume, " +
                "Do Not Disturb, set reminders, tell you the time and date, " +
                "navigate your phone, take screenshots, read your screen, " +
                "and type on your behalf. Just tell me what you need!")

            lower.contains("how are you") ||
            lower.contains("are you okay") -> speak(
                "I'm running perfectly, $USER_NAME! Always ready to assist.")

            lower.contains("do you learn") ||
            lower.contains("can you learn") ||
            lower.contains("do you remember") -> speak(
                "Yes! I track your app usage patterns and command habits " +
                "to predict what you'll need next. The more you use me, " +
                "the better I get at serving you.")

            lower.contains("hello") ||
            lower.contains("hi kate") ||
            lower.contains("hey kate") ||
            lower.contains("good morning") ||
            lower.contains("good afternoon") ||
            lower.contains("good evening") -> {
                val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                speak(when {
                    h in 5..11  -> "Good morning, $USER_NAME! Ready to help."
                    h in 12..16 -> "Good afternoon! What do you need?"
                    h in 17..20 -> "Good evening, $USER_NAME! How can I help?"
                    else        -> "Hello $USER_NAME! I'm here."
                })
            }

            // ── Time & Date ───────────────────────────────────
            lower.contains("time") -> {
                val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                speak("It's $t, $USER_NAME.")
            }

            lower.contains("date") ||
            lower.contains("what day") ||
            lower.contains("today") -> {
                val d = SimpleDateFormat("EEEE, MMMM d yyyy", Locale.getDefault()).format(Date())
                speak("Today is $d.")
            }

            // ── App launching ─────────────────────────────────
            lower.contains("open ") ||
            lower.contains("launch ") ||
            lower.contains("start ") -> {
                val app = lower.replace("open","").replace("launch","").replace("start","").trim()
                if (app.isBlank()) { speak("Which app, $USER_NAME?"); return }
                speak("Opening $app")
                val ok = launcher.launchByVoiceCommand(app)
                if (!ok) speak("I couldn't find $app. Checking Play Store.")
            }

            // ── Music ─────────────────────────────────────────
            lower.contains("play music") ||
            lower.contains("play songs") ||
            lower == "music" -> {
                speak("Opening your music app.")
                launcher.openMusicApp()
            }

            // ── Search ────────────────────────────────────────
            lower.contains("search for") ||
            lower.contains("google") ||
            lower.contains("look up") -> {
                val q = lower.replace("search for","").replace("google","")
                    .replace("look up","").trim()
                if (q.isBlank()) { speak("What should I search?"); return }
                speak("Searching for $q")
                launcher.search(q)
            }

            lower.contains("youtube") -> {
                val q = lower.replace("youtube","").replace("search","").trim()
                speak("Opening YouTube")
                launcher.search(q, SearchEngine.YOUTUBE)
            }

            lower.contains("navigate to") ||
            lower.contains("directions to") ||
            lower.contains("take me to") -> {
                val dest = lower.replace("navigate to","")
                    .replace("directions to","").replace("take me to","").trim()
                speak("Navigating to $dest")
                launcher.search(dest, SearchEngine.MAPS)
            }

            // ── Calls ─────────────────────────────────────────
            lower.startsWith("call ") -> {
                val name = lower.removePrefix("call").trim()
                if (name.isBlank()) { speak("Who should I call?"); return }
                val number = lookupContact(name)
                if (number != null) {
                    speak("Calling $name", 1200)
                    handler.postDelayed({ makeCall(number) }, 1400)
                } else speak("I couldn't find $name in your contacts, $USER_NAME.")
            }

            lower.startsWith("dial ") -> {
                val number = lower.removePrefix("dial").trim()
                if (number.isBlank()) { speak("What number?"); return }
                speak("Dialling $number", 800)
                handler.postDelayed({ makeCall(number) }, 1000)
            }

            // ── SMS ───────────────────────────────────────────
            lower.contains("send message to") ||
            lower.contains("text ") ||
            lower.contains("message ") -> {
                val parts   = lower.replace("send message to","")
                    .replace("text","").replace("message","")
                    .trim().split(" saying ")
                val name    = parts.getOrNull(0)?.trim() ?: ""
                val msg     = parts.getOrNull(1)?.trim() ?: ""
                if (name.isBlank())  { speak("Who should I message?"); return }
                if (msg.isBlank())   { speak("What should I say?");    return }
                val number = lookupContact(name)
                if (number != null) { sendSms(number, msg); speak("Message sent to $name.") }
                else speak("I couldn't find $name in your contacts.")
            }

            // ── Flashlight ────────────────────────────────────
            lower.contains("torch on") || lower.contains("flashlight on") ||
            lower.contains("turn on torch") || lower.contains("turn on flashlight") -> {
                hardware.torchOn(); speak("Flashlight on.")
            }
            lower.contains("torch off") || lower.contains("flashlight off") ||
            lower.contains("turn off torch") || lower.contains("turn off flashlight") -> {
                hardware.torchOff(); speak("Flashlight off.")
            }

            // ── Volume ────────────────────────────────────────
            lower.contains("volume up") || lower.contains("increase volume") ||
            lower.contains("louder") -> { hardware.volumeUp(); speak("Volume increased.") }

            lower.contains("volume down") || lower.contains("decrease volume") ||
            lower.contains("lower volume") || lower.contains("quieter") -> {
                hardware.volumeDown(); speak("Volume decreased.")
            }
            lower.contains("unmute") -> { hardware.unmuteAll(); speak("Unmuted.") }
            lower.contains("mute")   -> { hardware.muteAll();   speak("Muted.")   }

            // ── DND ───────────────────────────────────────────
            lower.contains("do not disturb on") || lower.contains("don't disturb") ||
            lower == "silence" -> {
                val nm = getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    speak("I need notification access for that. Opening settings.")
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else { hardware.setDND(true); speak("Do Not Disturb enabled.") }
            }
            lower.contains("do not disturb off") || lower.contains("disable silence") -> {
                val nm = getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    speak("I need notification access. Opening settings.")
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else { hardware.setDND(false); speak("Do Not Disturb disabled.") }
            }

            // ── Reminders ─────────────────────────────────────
            lower.contains("remind me") || lower.contains("set reminder") ||
            lower.contains("set alarm") -> {
                val ms = parseTime(lower)
                if (ms > 0) {
                    val task = lower
                        .replace("remind me to","").replace("remind me","")
                        .replace("set reminder","").replace("set alarm","")
                        .replace(Regex("in \\d+ (second|minute|hour)s?"),"").trim()
                        .ifBlank { "reminder" }
                    reminder.schedule(task, ms)
                    val unit = when {
                        ms < 60_000L    -> "${ms / 1000} seconds"
                        ms < 3_600_000L -> "${ms / 60_000} minutes"
                        else            -> "${ms / 3_600_000} hours"
                    }
                    speak("Got it! I'll remind you in $unit.")
                } else speak("When should I remind you? Say for example: remind me in 10 minutes.")
            }

            // ── Browser ───────────────────────────────────────
            lower.contains("open browser") || lower.contains("open chrome") -> {
                speak("Opening browser."); launcher.openBrowser()
            }

            // ── Accessibility navigation ──────────────────────
            lower.contains("go back") || lower == "back" -> {
                if (KateAccessibilityService.instance != null) {
                    KateAccessibilityService.instance?.goBack()
                    Unit
                } else speak("Please enable Kate accessibility service first.")
            }
            lower.contains("go home") || lower == "home" -> {
                if (KateAccessibilityService.instance != null) {
                    KateAccessibilityService.instance?.goHome()
                    Unit
                } else speak("Accessibility service needed.")
            }
            lower.contains("recent apps") || lower.contains("show recents") -> {
                if (KateAccessibilityService.instance != null) {
                    KateAccessibilityService.instance?.openRecents()
                    Unit
                } else speak("Accessibility service needed.")
            }
            lower.contains("take screenshot") || lower.contains("screenshot") -> {
                if (KateAccessibilityService.instance != null) {
                    KateAccessibilityService.instance?.takeScreenshot()
                    speak("Screenshot taken.")
                } else speak("Accessibility service needed.")
            }
            lower.contains("show notifications") || lower.contains("open notifications") -> {
                if (KateAccessibilityService.instance != null) {
                    KateAccessibilityService.instance?.showNotifications()
                    Unit
                } else speak("Accessibility service needed.")
            }
            lower.contains("read screen") || lower.contains("what's on screen") -> {
                if (KateAccessibilityService.instance != null) {
                    val content = KateAccessibilityService.instance?.readScreen() ?: ""
                    if (content.isNotBlank()) speak(content.take(300))
                    else speak("Nothing readable on screen right now.")
                } else speak("Please enable Kate accessibility service to read the screen.")
            }
            lower.startsWith("type ") || lower.startsWith("write ") -> {
                val typing = lower.removePrefix("type").removePrefix("write").trim()
                if (KateAccessibilityService.instance != null) {
                    val ok = KateAccessibilityService.instance?.ghostType(typing) ?: false
                    speak(if (ok) "Done." else "No text field found. Please tap a text field first.")
                } else speak("Please enable Kate accessibility service to type.")
            }

            // ── Stop ──────────────────────────────────────────
            lower.contains("stop listening") || lower.contains("goodbye kate") ||
            lower.contains("bye kate") -> {
                speak("Goodbye, $USER_NAME! I'll be here when you need me.")
                handler.postDelayed({ speech.stopListening() }, 2500)
            }

            // ── TFLite fallback ───────────────────────────────
            else -> {
                if (lower.length > 2) {
                    val intent = try {
                        withContext(Dispatchers.IO) { classifier.classify(raw) }
                    } catch (_: Exception) { "UNKNOWN" }
                    Log.d(TAG, "TFLite: $intent")
                    when (intent) {
                        "OPEN_APP"       -> speak("Which app should I open, $USER_NAME?")
                        "MEDIA_CONTROL"  -> { speak("Opening music."); launcher.openMusicApp() }
                        "COMMUNICATION"  -> speak("Who should I contact?")
                        "REMINDER"       -> speak("When should I remind you?")
                        "SYSTEM_CONTROL" -> speak("What system setting?")
                        else             -> speak(fallback())
                    }
                }
            }
        }

        // Self-training — log every command
        try { bridge.processText(raw) } catch (_: Exception) {}
    }

    // ── Time parser ───────────────────────────────────────────
    private fun parseTime(text: String): Long {
        val s = Regex("(\\d+)\\s*second").find(text)
        val m = Regex("(\\d+)\\s*minute").find(text)
        val h = Regex("(\\d+)\\s*hour").find(text)
        return when {
            s != null -> s.groupValues[1].toLong() * 1_000L
            m != null -> m.groupValues[1].toLong() * 60_000L
            h != null -> h.groupValues[1].toLong() * 3_600_000L
            else      -> -1L
        }
    }

    // ── Contact lookup ────────────────────────────────────────
    private fun lookupContact(name: String): String? = try {
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(
                c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            else null
        }
    } catch (e: Exception) { Log.e(TAG, "Contact: ${e.message}"); null }

    private fun makeCall(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${number.trim()}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY))
        } catch (e: Exception) { speak("I couldn't make the call."); Log.e(TAG, e.message ?: "") }
    }

    private fun sendSms(number: String, message: String) {
        try {
            SmsManager.getDefault().sendTextMessage(number.trim(), null, message, null, null)
        } catch (e: Exception) { speak("Couldn't send the message."); Log.e(TAG, e.message ?: "") }
    }

    private fun saveHabit(event: KateEvent.HabitUpdate) {
        scope.launch {
            val key  = "${event.intent}_${event.entity}"
            val prev = habits.getAll().find { it.key == key }
            habits.insert(HabitEntity(key = key, intent = event.intent,
                entity = event.entity, count = (prev?.count ?: 0) + 1))
        }
    }

    private fun loadInstalledApps(): Array<String> =
        packageManager.getInstalledApplications(0).map {
            "${packageManager.getApplicationLabel(it).toString().lowercase()}|${it.packageName}"
        }.toTypedArray()

    // ── Watchdog ──────────────────────────────────────────────
    private fun startWatchdog() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (initDone && !speech.isListening() && !speech.isSpeaking()) {
                    Log.w(TAG, "Watchdog: restarting speech")
                    speech.startListening()
                }
                handler.postDelayed(this, 60_000L)
            }
        }, 60_000L)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?) = null

    override fun onDestroy() {
        if (::speech.isInitialized) speech.shutdown()
        if (::bridge.isInitialized) bridge.stopAudio()
        if (::classifier.isInitialized) classifier.close()
        scope.cancel()
        super.onDestroy()
    }

    // ── Foreground notification — MUST be absolute first call ─
    private fun startForegroundNow() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Kate Assistant", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kate")
            .setContentText("Starting up...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
