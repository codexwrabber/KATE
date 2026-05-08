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
import com.kate.assistant.core.ModelManager
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

    // ── Components ────────────────────────────────────────────
    private lateinit var bridge: KateBridge
    private lateinit var modelManager: ModelManager
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

    // ── Conversation context ──────────────────────────────────
    // When Kate asks a follow-up question (e.g., "Which app?"), the next
    // speech result is routed here instead of to the main command handler.
    private var pendingContext: String? = null      // "CALL", "APP", "MESSAGE_TO", etc.
    private var pendingData:    String? = null      // partial data from first turn

    companion object {
        private const val TAG        = "KateService"
        private const val CHANNEL_ID = "kate_channel"
        private const val NOTIF_ID   = 1
        private const val USER_NAME  = "Dewrabber"
    }

    // ══════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()

        // PHASE 1 — startForeground IMMEDIATELY (< 5s rule)
        startForegroundNow()

        // PHASE 2 — heavy init on background thread
        Thread {
            try {
                initAllComponents()
                handler.post { onInitComplete() }
            } catch (e: Exception) {
                Log.e(TAG, "Init failed: ${e.message}")
                handler.post {
                    if (::tts.isInitialized) tts.speak("Kate encountered a startup error. Please restart me.")
                }
            }
        }.also { it.name = "kate-init"; it.start() }
    }

    private fun initAllComponents() {
        tts          = KateTts(this)
        modelManager = ModelManager(this)
        bridge       = KateBridge(this)
        device       = KateDeviceController(this)
        hardware     = KateHardwareController(this)
        launcher     = KateAppLauncher(this)
        reminder     = ReminderScheduler(this)
        journal      = PhantomJournal(this)
        proactive    = ProactiveEngine(this)
        classifier   = IntentClassifier(this)
        vectorizer   = TextVectorizer()
        labelMapper  = LabelMapper(this)
        db           = KateDatabase.getDatabase(this)
        habits       = db.habitDao()

        // ── Wire TTS callbacks ─────────────────────────────────
        tts.onSpeechActive = {
            speech.setSpeaking(true)
            Log.d(TAG, "TTS started — mic paused")
        }
        tts.onSpeechIdle = {
            speech.setSpeaking(false)
            Log.d(TAG, "TTS finished — mic resumed")
        }

        // ── Model path injection ───────────────────────────────
        // ModelManager returns the best available path:
        //  - First launch  → small model (already present)
        //  - After upgrade → daanzu-lgraph (129MB, WER 8.2% vs 11.5%)
        speech = KateSpeechManager(
            context   = this,
            modelPath = modelManager.bestModelPath(),
            onResult  = { text -> handler.post { handleSpeech(text) } },
            onError   = { err ->
                when (err) {
                    "AUDIO_DEAD" -> {
                        Log.w(TAG, "Audio dead — restarting mic in 1.5s")
                        handler.postDelayed({
                            speech.stopListening()
                            speech.startListening()
                            updateNotification("Always listening \uD83C\uDFA4")
                        }, 1500)
                    }
                    else -> Log.e(TAG, "Speech error: $err")
                }
            }
        )

        // ── Model upgrade callback ─────────────────────────────
        // Fires on main thread when daanzu model finishes downloading.
        // Seamlessly switches VOSK to better model without restart.
        modelManager.onUpgradeReady = {
            Log.d(TAG, "Upgraded model ready — switching VOSK")
            speech.switchModel(modelManager.upgradeModelDir.absolutePath)
            speak("Speech upgrade complete. I can now hear you much better.")
        }

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
        speech.startListening()

        // ── Update notification ────────────────────────────────
        updateNotification("Always listening \uD83C\uDFA4")

        // ── Build dynamic grammar ──────────────────────────────
        // Restricts VOSK to Kate's command vocabulary.
        // Background noise/speech → "[unk]" → discarded.
        // Dramatically cuts false commands in noisy environments.
        handler.post { buildAndPushGrammar() }

        // ── Start model upgrade (silent background download) ───
        // Downloads daanzu-lgraph (129MB) once, then switches VOSK automatically.
        // Shows a progress notification; user can ignore it.
        modelManager.startUpgradeIfNeeded()

        // Greet after TTS is ready
        handler.postDelayed({ greetUser() }, 1200)

        startWatchdog()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?) = null

    override fun onDestroy() {
        if (::speech.isInitialized) speech.shutdown()
        if (::bridge.isInitialized)  bridge.stopAudio()
        if (::classifier.isInitialized) classifier.close()
        if (::modelManager.isInitialized) modelManager.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════
    // SPEECH ROUTER
    // ══════════════════════════════════════════════════════════

    private fun handleSpeech(text: String) {
        if (!initDone) return
        val clean = text.trim()

        when {
            clean.equals("WAKE", ignoreCase = true) -> {
                speak("Yes, $USER_NAME?")
                return
            }
            clean.length <= 2 -> return
        }

        // If Kate is waiting for a follow-up answer, route there first
        if (pendingContext != null) {
            handlePendingContext(clean)
            return
        }

        if (clean.length > 2) scope.launch { handleCommand(clean) }
    }

    // ── Multi-turn context handler ────────────────────────────
    private fun handlePendingContext(answer: String) {
        val ctx  = pendingContext ?: return
        val data = pendingData
        pendingContext = null
        pendingData    = null

        scope.launch {
            when (ctx) {
                "APP" -> {
                    speak("Opening $answer")
                    val ok = launcher.launchByVoiceCommand(answer)
                    if (!ok) speak("I couldn't find $answer, $USER_NAME.")
                }
                "CALL" -> {
                    val number = lookupContact(answer)
                    if (number != null) {
                        speak("Calling $answer")
                        handler.postDelayed({ makeCall(number) }, 1200)
                    } else speak("I couldn't find $answer in your contacts.")
                }
                "MESSAGE_TO" -> {
                    speak("What should I say to $answer?")
                    pendingContext = "MESSAGE_BODY"
                    pendingData    = answer
                }
                "MESSAGE_BODY" -> {
                    val name   = data ?: "them"
                    val number = lookupContact(name)
                    if (number != null) {
                        sendSms(number, answer)
                        speak("Message sent to $name.")
                    } else speak("Couldn't find $name in contacts.")
                }
                "SEARCH" -> {
                    speak("Searching for $answer")
                    launcher.search(answer)
                }
                "REMINDER_TASK" -> {
                    val ms = data?.toLongOrNull() ?: 0L
                    if (ms > 0) {
                        reminder.schedule(answer, ms)
                        speak("Got it. Reminder set for $answer.")
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // MAIN COMMAND HANDLER
    // ══════════════════════════════════════════════════════════

    private suspend fun handleCommand(raw: String) {
        val lower = raw.lowercase().trim()
        Log.d(TAG, "CMD: $lower")

        if (lower.all { !it.isLetter() }) return

        when {

            // ── Identity ──────────────────────────────────────
            lower.contains("who are you") ||
            lower.contains("what are you") ||
            lower.contains("about yourself") -> speak(
                "I'm Kate — Kernel-level Autonomous Task Engine. " +
                "Your personal offline voice assistant. I work with zero internet " +
                "so your data stays private. I can open apps, make calls, send messages, " +
                "search the web, control your phone hardware, set reminders, " +
                "navigate your device, read and type on screen, and much more. " +
                "The longer you use me, the smarter I get.")

            lower.contains("who made you") ||
            lower.contains("who created you") ||
            lower.contains("who built you") -> speak(
                "I was built by $USER_NAME with the help of an AI development partner. " +
                "I run on a C language brain with VOSK offline speech recognition. " +
                "Truly one of a kind.")

            lower.contains("what can you do") ||
            lower.contains("your capabilities") ||
            lower.contains("list your features") -> speak(
                "I can open any installed app, make phone calls, send texts, " +
                "search Google, YouTube, and Maps, control your flashlight, volume, " +
                "screen brightness, Do Not Disturb and silent mode, " +
                "set reminders and timers, tell the time and date, check battery level, " +
                "go back, go home, open recent apps, take screenshots, " +
                "pull down notifications and quick settings, read your screen, " +
                "and type for you. Just say the word.")

            lower.contains("how are you") ||
            lower.contains("are you okay") -> speak(
                "Running perfectly, $USER_NAME. All systems are green.")

            lower.contains("do you learn") ||
            lower.contains("can you learn") ||
            lower.contains("do you remember") -> speak(
                "Yes! I track your app usage and command patterns " +
                "to predict what you need next. The more you use me, the sharper I get.")

            // ── Greetings ─────────────────────────────────────
            lower.contains("hello") ||
            lower.contains("hi kate") ||
            lower.contains("hey kate") ||
            lower.matches(Regex("good (morning|afternoon|evening|night).*")) -> {
                val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                speak(when {
                    h in 5..11  -> "Good morning, $USER_NAME! Ready to help."
                    h in 12..16 -> "Good afternoon! What can I do for you?"
                    h in 17..20 -> "Good evening, $USER_NAME. How can I help?"
                    else        -> "Hello $USER_NAME! I'm awake. What do you need?"
                })
            }

            // ── Time & Date ───────────────────────────────────
            lower.contains("time") && !lower.contains("remind") &&
            !lower.contains("timer") -> {
                val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                speak("It's $t.")
            }

            lower.contains("date") ||
            lower.contains("what day") ||
            lower.contains("day is it") -> {
                val d = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                speak("Today is $d.")
            }

            // ── Battery ───────────────────────────────────────
            lower.contains("battery") ||
            lower.contains("power level") ||
            lower.contains("how much charge") -> {
                val bm = getSystemService(android.content.Context.BATTERY_SERVICE)
                    as? android.os.BatteryManager
                val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val charging = bm?.isCharging ?: false
                speak(when {
                    pct < 0  -> "I couldn't read the battery level right now."
                    charging -> "Battery is at $pct percent and currently charging."
                    pct <= 15 -> "Battery is low — $pct percent. You should plug in soon, $USER_NAME."
                    else     -> "Battery is at $pct percent."
                })
            }

            // ── App launching ─────────────────────────────────
            lower.startsWith("open ") ||
            lower.startsWith("launch ") ||
            lower.startsWith("start ") -> {
                val app = lower
                    .removePrefix("open").removePrefix("launch").removePrefix("start")
                    .trim()
                if (app.isBlank()) {
                    speak("Which app should I open?")
                    pendingContext = "APP"
                } else {
                    speak("Opening $app")
                    val ok = launcher.launchByVoiceCommand(app)
                    if (!ok) speak("I couldn't find $app. Want me to search for it?")
                }
            }

            // ── Music ─────────────────────────────────────────
            lower.contains("play music") ||
            lower.contains("play songs") ||
            lower == "music" -> {
                speak("Opening music."); launcher.openMusicApp()
            }

            // ── Search ────────────────────────────────────────
            lower.contains("search for") ||
            lower.contains("google ") ||
            lower.contains("look up") ||
            lower.contains("search ") -> {
                val q = lower
                    .replace("search for", "").replace("google", "")
                    .replace("look up", "").replace("search", "").trim()
                if (q.isBlank()) {
                    speak("What should I search?")
                    pendingContext = "SEARCH"
                } else {
                    speak("Searching for $q"); launcher.search(q)
                }
            }

            lower.contains("youtube") -> {
                val q = lower.replace("youtube", "").replace("search", "").trim()
                speak(if (q.isBlank()) "Opening YouTube" else "Searching YouTube for $q")
                launcher.search(q.ifBlank { "" }, SearchEngine.YOUTUBE)
            }

            lower.contains("navigate to") ||
            lower.contains("directions to") ||
            lower.contains("take me to") ||
            lower.contains("how to get to") -> {
                val dest = lower
                    .replace("navigate to", "").replace("directions to", "")
                    .replace("take me to", "").replace("how to get to", "").trim()
                if (dest.isBlank()) speak("Where should I navigate to?")
                else { speak("Navigating to $dest"); launcher.search(dest, SearchEngine.MAPS) }
            }

            // ── Calls ─────────────────────────────────────────
            lower.startsWith("call ") -> {
                val name = lower.removePrefix("call").trim()
                if (name.isBlank()) {
                    speak("Who should I call?"); pendingContext = "CALL"
                } else {
                    val number = lookupContact(name)
                    if (number != null) {
                        speak("Calling $name")
                        handler.postDelayed({ makeCall(number) }, 1400)
                    } else speak("I couldn't find $name in your contacts, $USER_NAME.")
                }
            }

            lower.startsWith("dial ") -> {
                val number = lower.removePrefix("dial").trim()
                if (number.isBlank()) speak("What number should I dial?")
                else { speak("Dialling $number"); handler.postDelayed({ makeCall(number) }, 1200) }
            }

            // ── SMS ───────────────────────────────────────────
            lower.contains("send message to") ||
            lower.contains("text ") ||
            (lower.startsWith("message ") && lower.contains(" saying ")) -> {
                val parts = lower
                    .replace("send message to", "").replace("text", "").replace("message", "")
                    .trim().split(" saying ")
                val name = parts.getOrNull(0)?.trim() ?: ""
                val msg  = parts.getOrNull(1)?.trim() ?: ""
                when {
                    name.isBlank() -> { speak("Who should I message?"); pendingContext = "MESSAGE_TO" }
                    msg.isBlank()  -> {
                        speak("What should I say to $name?")
                        pendingContext = "MESSAGE_BODY"
                        pendingData   = name
                    }
                    else -> {
                        val number = lookupContact(name)
                        if (number != null) { sendSms(number, msg); speak("Message sent to $name.") }
                        else speak("I couldn't find $name in your contacts.")
                    }
                }
            }

            // ── Flashlight ────────────────────────────────────
            lower.contains("torch on") || lower.contains("flashlight on") ||
            lower.contains("turn on torch") || lower.contains("turn on flashlight") ||
            lower.contains("turn on the torch") || lower.contains("light on") -> {
                hardware.torchOn(); speak("Flashlight on.")
            }
            lower.contains("torch off") || lower.contains("flashlight off") ||
            lower.contains("turn off torch") || lower.contains("turn off flashlight") ||
            lower.contains("light off") -> {
                hardware.torchOff(); speak("Flashlight off.")
            }

            // ── Volume ────────────────────────────────────────
            lower.contains("volume up") || lower.contains("increase volume") ||
            lower.contains("turn up") || lower.contains("louder") -> {
                hardware.volumeUp(); speak("Volume up.")
            }
            lower.contains("volume down") || lower.contains("decrease volume") ||
            lower.contains("turn down") || lower.contains("quieter") || lower.contains("lower volume") -> {
                hardware.volumeDown(); speak("Volume down.")
            }
            lower.contains("unmute") || lower.contains("turn on sound") -> {
                hardware.unmuteAll(); speak("Unmuted.")
            }
            lower.contains("mute") || lower.contains("silent mode") -> {
                hardware.muteAll(); speak("Muted.")
            }
            lower.contains("max volume") || lower.contains("full volume") -> {
                repeat(15) { hardware.volumeUp() }; speak("Volume maxed.")
            }

            // ── Do Not Disturb ────────────────────────────────
            lower.contains("do not disturb on") || lower.contains("don't disturb") ||
            lower.contains("enable dnd") || lower == "silence" -> {
                val nm = getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    speak("I need notification policy access. Opening settings.")
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else { hardware.setDND(true); speak("Do Not Disturb enabled.") }
            }
            lower.contains("do not disturb off") || lower.contains("disable dnd") ||
            lower.contains("disable silence") -> {
                val nm = getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    speak("I need notification policy access. Opening settings.")
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else { hardware.setDND(false); speak("Do Not Disturb disabled.") }
            }

            // ── Reminders / Timers ────────────────────────────
            lower.contains("remind me") || lower.contains("set reminder") ||
            lower.contains("set alarm") -> {
                val ms = parseTime(lower)
                if (ms > 0) {
                    val task = lower
                        .replace("remind me to", "").replace("remind me", "")
                        .replace("set reminder to", "").replace("set reminder", "")
                        .replace("set alarm for", "").replace("set alarm", "")
                        .replace(Regex("in \\d+ (second|minute|hour)s?"), "").trim()
                        .ifBlank { "reminder" }
                    reminder.schedule(task, ms)
                    val label = humanTime(ms)
                    speak("Done! I'll remind you to $task in $label.")
                } else {
                    speak("When should I remind you? For example, remind me in 10 minutes.")
                }
            }

            lower.contains("set timer") || lower.contains("timer for") ||
            lower.contains("countdown") -> {
                val ms = parseTime(lower)
                if (ms > 0) {
                    reminder.schedule("Timer", ms)
                    speak("Timer set for ${humanTime(ms)}.")
                } else speak("How long should the timer be?")
            }

            // ── Browser ───────────────────────────────────────
            lower.contains("open browser") || lower.contains("open chrome") ||
            lower.contains("open internet") -> {
                speak("Opening browser."); launcher.openBrowser()
            }

            // ── Accessibility navigation ──────────────────────
            lower.contains("go back") || lower == "back" -> {
                KateAccessibilityService.instance?.goBack()
                    ?: speak("Please enable Kate accessibility service first.")
            }
            lower.contains("go home") || lower == "home" ||
            lower.contains("home screen") -> {
                KateAccessibilityService.instance?.goHome()
                    ?: speak("Accessibility service needed.")
            }
            lower.contains("recent apps") || lower.contains("show recents") ||
            lower.contains("open recents") -> {
                KateAccessibilityService.instance?.openRecents()
                    ?: speak("Accessibility service needed.")
            }
            lower.contains("take screenshot") || lower.contains("screenshot") -> {
                if (KateAccessibilityService.instance != null) {
                    KateAccessibilityService.instance?.takeScreenshot()
                    speak("Screenshot taken.")
                } else speak("Accessibility service needed.")
            }
            lower.contains("show notifications") || lower.contains("open notifications") ||
            lower.contains("pull down notifications") -> {
                KateAccessibilityService.instance?.showNotifications()
                    ?: speak("Accessibility service needed.")
            }
            lower.contains("quick settings") || lower.contains("open quick settings") -> {
                KateAccessibilityService.instance?.openQuickSettings()
                    ?: speak("Accessibility service needed.")
            }
            lower.contains("read screen") || lower.contains("what's on screen") ||
            lower.contains("what is on screen") -> {
                if (KateAccessibilityService.instance != null) {
                    val content = KateAccessibilityService.instance?.readScreen() ?: ""
                    if (content.isNotBlank()) speak(content.take(400))
                    else speak("Nothing readable on screen right now.")
                } else speak("Please enable Kate accessibility service to read the screen.")
            }
            lower.startsWith("type ") || lower.startsWith("write ") -> {
                val typing = lower.removePrefix("type").removePrefix("write").trim()
                if (KateAccessibilityService.instance != null) {
                    val ok = KateAccessibilityService.instance?.ghostType(typing) ?: false
                    speak(if (ok) "Done." else "No text field was found. Please tap a text field first.")
                } else speak("Please enable Kate accessibility service to type.")
            }

            // ── Wifi / Bluetooth (open settings) ─────────────
            lower.contains("open wifi") || lower.contains("wifi settings") -> {
                speak("Opening Wi-Fi settings.")
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            lower.contains("bluetooth settings") || lower.contains("open bluetooth") -> {
                speak("Opening Bluetooth settings.")
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            lower.contains("open settings") || lower == "settings" -> {
                speak("Opening settings.")
                startActivity(Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            // ── Stop listening ────────────────────────────────
            lower.contains("stop listening") || lower.contains("goodbye kate") ||
            lower.contains("bye kate") || lower.contains("sleep kate") -> {
                speak("Goodbye, $USER_NAME! I'll be here when you need me.")
                handler.postDelayed({ speech.stopListening() }, 2500)
            }

            // ── Repeat ────────────────────────────────────────
            lower.contains("say that again") || lower.contains("repeat that") ||
            lower.contains("what did you say") -> {
                tts.speak(lastSpoken.ifBlank { "I haven't said anything yet." })
            }

            // ── TFLite ML fallback ────────────────────────────
            else -> {
                if (lower.length > 2) {
                    val intent = try {
                        withContext(Dispatchers.IO) { classifier.classify(raw) }
                    } catch (_: Exception) { "UNKNOWN" }
                    Log.d(TAG, "TFLite intent: $intent")
                    when (intent) {
                        "OPEN_APP"       -> { speak("Which app should I open?"); pendingContext = "APP" }
                        "MEDIA_CONTROL"  -> { speak("Opening music."); launcher.openMusicApp() }
                        "COMMUNICATION"  -> { speak("Who should I contact?");  pendingContext = "CALL" }
                        "REMINDER"       -> speak("When should I remind you? Say for example: in 10 minutes.")
                        "SYSTEM_CONTROL" -> speak("What system setting should I change?")
                        else             -> speak(nextFallback())
                    }
                }
            }
        }

        // Log command for self-training
        try { bridge.processText(raw) } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════
    // SPEAK — no more manual timing; TTS callbacks handle it
    // ══════════════════════════════════════════════════════════

    private var lastSpoken = ""

    private fun speak(text: String) {
        lastSpoken = text
        tts.speak(text)
    }

    // ── Rotating fallbacks ────────────────────────────────────
    private val fallbacks = listOf(
        "Hmm, I didn't quite get that, $USER_NAME. Say it again?",
        "Sorry, I'm not sure I understood. Try once more?",
        "I'm still learning! Could you rephrase that?",
        "My ears must have slipped — say that again?"
    )
    private var fallbackIdx = 0
    private fun nextFallback() = fallbacks[fallbackIdx++ % fallbacks.size]

    // ══════════════════════════════════════════════════════════
    // EVENT BUS
    // ══════════════════════════════════════════════════════════

    private fun setupEventBus() {
        KateEventBus.subscribe { event ->
            when (event) {
                is KateEvent.WakeWordDetected -> Log.d(TAG, "Wake word via C bridge")
                is KateEvent.HabitUpdate      -> saveHabit(event)
                is KateEvent.AppOpened        -> {
                    journal.logAppOpen(event.packageName)
                    proactive.evaluate()
                }
                is KateEvent.Error -> Log.e(TAG, "Bridge error: ${event.message}")
                else               -> Unit
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // GRAMMAR BUILDER
    // ══════════════════════════════════════════════════════════

    /**
     * Builds Kate's full command vocabulary and pushes it to VOSK.
     * Called once at startup (on main thread, fast enough).
     * Re-call any time apps are installed/uninstalled or contacts change.
     *
     * How it works: VOSK only tries to match phrases in this list.
     * Anything else → "[unk]" → KateSpeechManager drops it silently.
     * This is what eliminates random words from background noise.
     */
    private fun buildAndPushGrammar() {
        scope.launch(Dispatchers.IO) {
            val phrases = mutableListOf(
                // Wake words
                "hey kate", "hi kate", "okay kate", "ok kate",

                // Identity
                "who are you", "what are you", "what can you do",
                "how are you", "who made you", "who created you",

                // Greetings
                "hello", "good morning", "good afternoon", "good evening", "good night",

                // Time & date
                "what time is it", "what is the time", "time",
                "what day is it", "what is today", "what is the date",
                "what is today's date", "today's date",

                // Battery
                "battery level", "battery percentage", "how much battery",
                "check battery", "power level", "how much charge",

                // Volume
                "volume up", "increase volume", "turn up", "louder",
                "volume down", "decrease volume", "turn down", "quieter", "lower volume",
                "mute", "unmute", "silent mode", "max volume", "full volume", "turn on sound",

                // Flashlight
                "torch on", "flashlight on", "turn on torch", "turn on flashlight",
                "turn on the torch", "light on",
                "torch off", "flashlight off", "turn off torch", "turn off flashlight", "light off",

                // Navigation
                "go back", "back", "go home", "home", "home screen",
                "recent apps", "show recents", "open recents",
                "take screenshot", "screenshot",
                "show notifications", "open notifications", "pull down notifications",
                "quick settings", "open quick settings",
                "read screen", "what is on screen", "what's on screen",

                // System
                "open settings", "settings", "open wifi", "wifi settings",
                "bluetooth settings", "open bluetooth",
                "do not disturb on", "do not disturb off", "enable dnd", "disable dnd",

                // Search
                "search for [unk]", "google [unk]", "look up [unk]", "search [unk]",
                "youtube [unk]", "navigate to [unk]", "directions to [unk]",

                // Calls
                "call [unk]", "dial [unk]",

                // Messages
                "send message to [unk]", "text [unk]", "message [unk]",

                // Reminders
                "remind me to [unk]", "set reminder", "set alarm",
                "set timer", "timer for [unk]",

                // Apps (generic)
                "open [unk]", "launch [unk]", "start [unk]",
                "play music", "play songs", "music",
                "open browser", "open chrome", "open internet",

                // Stop
                "stop listening", "goodbye kate", "bye kate", "sleep kate",
                "say that again", "repeat that", "what did you say",

                // Typing
                "type [unk]", "write [unk]"
            )

            // Add every installed app name as "open <name>"
            getInstalledAppNames().forEach { name ->
                phrases.add("open $name")
                phrases.add("launch $name")
            }

            // Add every contact name as "call <name>" and "text <name>"
            getContactNames().forEach { name ->
                phrases.add("call $name")
                phrases.add("text $name")
                phrases.add("message $name")
            }

            Log.d(TAG, "Grammar: ${phrases.size} phrases built")
            withContext(Dispatchers.Main) {
                speech.updateGrammar(phrases)
            }
        }
    }

    private fun getInstalledAppNames(): List<String> =
        packageManager.getInstalledApplications(0)
            .map { packageManager.getApplicationLabel(it).toString().lowercase().trim() }
            .filter { it.isNotBlank() && it.length > 1 }
            .distinct()

    private fun getContactNames(): List<String> = try {
        val names = mutableListOf<String>()
        contentResolver.query(
            android.provider.ContactsContract.Contacts.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.lowercase()?.trim()
                if (!name.isNullOrBlank()) names.add(name)
            }
        }
        names
    } catch (e: Exception) {
        Log.e(TAG, "Contacts read: ${e.message}"); emptyList()
    }

    // ══════════════════════════════════════════════════════════
    // GREETING
    // ══════════════════════════════════════════════════════════

    private fun greetUser() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        speak(when {
            hour in 5..11  -> "Good morning, $USER_NAME! Kate is online and always listening."
            hour in 12..16 -> "Good afternoon, $USER_NAME! Kate here. What can I do for you?"
            hour in 17..20 -> "Good evening, $USER_NAME! Kate is ready. How can I help?"
            else           -> "Kate is online, $USER_NAME. Always here when you need me."
        })
    }

    // ══════════════════════════════════════════════════════════
    // NOTIFICATION
    // ══════════════════════════════════════════════════════════

    private fun startForegroundNow() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Kate Assistant", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notif = buildNotification("Starting up...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ── Update notification — must call startForeground again ─
    // notify() alone does NOT reliably update foreground service
    // notifications on many OEM Android builds (Tecno, Infinix, etc.)
    private fun updateNotification(text: String) {
        val notif = buildNotification(text)
        // Update via notify first (fast path)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
        // Then re-call startForeground with same ID — guaranteed to update on all OEMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kate")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ══════════════════════════════════════════════════════════
    // WATCHDOG
    // ══════════════════════════════════════════════════════════

    private fun startWatchdog() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (initDone && !speech.isListening() && !speech.isSpeaking()) {
                    Log.w(TAG, "Watchdog: restarting speech")
                    speech.startListening()
                }
                handler.postDelayed(this, 30_000L)  // 30s — was 60s
            }
        }, 30_000L)
    }

    // ══════════════════════════════════════════════════════════
    // UTILITIES
    // ══════════════════════════════════════════════════════════

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

    private fun humanTime(ms: Long): String = when {
        ms < 60_000L    -> "${ms / 1000} seconds"
        ms < 3_600_000L -> "${ms / 60_000} minutes"
        else            -> "${ms / 3_600_000} hours"
    }

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
    } catch (e: Exception) { Log.e(TAG, "Contact lookup: ${e.message}"); null }

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
}
