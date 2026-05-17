package com.kate.assistant.services

import android.app.*
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kate.assistant.core.KateDeviceAdmin
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class KateService : Service() {

    private lateinit var bridge:      KateBridge
    private lateinit var speech:      KateSpeechManager
    private lateinit var tts:         KateTts
    private lateinit var device:      KateDeviceController
    private lateinit var hardware:    KateHardwareController
    private lateinit var launcher:    KateAppLauncher
    private lateinit var reminder:    ReminderScheduler
    private lateinit var db:          KateDatabase
    private lateinit var habits:      HabitDao
    private lateinit var journal:     PhantomJournal
    private lateinit var proactive:   ProactiveEngine
    private lateinit var classifier:  IntentClassifier
    private lateinit var vectorizer:  TextVectorizer
    private lateinit var labelMapper: LabelMapper

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var initDone = false
    private var micRestartPending = false

    // ── WakeLock ───────────────────────────────────────────────
    // Keeps the CPU alive while the mic is open.
    // Without this, AudioRecord starves the moment the screen turns off,
    // the listen thread stalls, failStreak trips AUDIO_DEAD, and Android
    // kills the service — which was the "closes after you speak" bug.
    // PARTIAL_WAKE_LOCK keeps CPU on without keeping screen on.
    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "kate:micwakelock"
        ).also {
            it.setReferenceCounted(false)
            it.acquire(24 * 60 * 60 * 1000L) // 24h max — watchdog will re-acquire daily
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private val dpm by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val adminComponent by lazy {
        ComponentName(this, KateDeviceAdmin::class.java)
    }

    // ── Charging receiver ─────────────────────────────────────
    private val chargingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!initDone) return
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED    -> speak("Charger connected. Your phone is now charging.")
                Intent.ACTION_POWER_DISCONNECTED -> speak("Charger disconnected.")
            }
        }
    }
    private var chargingRegistered = false

    private var pendingContext: String? = null
    private var pendingData:    String? = null

    companion object {
        private const val TAG        = "KateService"
        private const val CHANNEL_ID = "kate_channel"
        private const val NOTIF_ID   = 1
        // USER_NAME is now loaded dynamically from KatePreferences on init.
        // Default fallback only if prefs haven't been written yet (should never happen
        // after the onboarding screen runs, but guards against edge cases).
        private const val DEFAULT_NAME = "there"
    }

    // Loaded from prefs in initAllComponents()
    private var userName: String = DEFAULT_NAME

    // ── Online pipeline ────────────────────────────────────────
    private lateinit var deepgram:      com.kate.assistant.features.voice.DeepgramSTT
    private lateinit var claudeAI:      com.kate.assistant.features.ai.ClaudeAI
    private lateinit var networkMonitor: com.kate.assistant.core.network.KateNetworkMonitor
    private lateinit var usageManager:  com.kate.assistant.features.subscription.KateUsageManager
    private lateinit var prefs:         com.kate.assistant.data.preferences.KatePreferences

    // Conversation history for multi-turn Claude context (last 10 turns kept)
    private val conversationHistory = ArrayDeque<Pair<String, String>>()

    @Volatile private var onlineMode = false

    // ══════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        startForegroundNow()
        Thread {
            try {
                initAllComponents()
                handler.post { onInitComplete() }
            } catch (e: Exception) {
                Log.e(TAG, "Init failed: ${e.message}")
                handler.post {
                    if (::tts.isInitialized)
                        tts.speak("Kate encountered a startup error. Please restart me.")
                }
            }
        }.also { it.name = "kate-init"; it.start() }
    }

    // Called when the user swipes the app away from recents.
    // START_STICKY alone isn't enough on all OEMs — explicitly restart here.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restart = Intent(applicationContext, KateService::class.java)
        val pending = PendingIntent.getService(
            applicationContext, 1, restart,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pending)
        Log.d(TAG, "onTaskRemoved — scheduled restart in 1s")
    }

    private fun initAllComponents() {
        // ── Load user name from prefs ──────────────────────────
        prefs    = com.kate.assistant.data.preferences.KatePreferences(this)
        userName = kotlinx.coroutines.runBlocking { prefs.getUserNameOnce() }
            .ifBlank { DEFAULT_NAME }

        // ── Online pipeline ────────────────────────────────────
        usageManager   = com.kate.assistant.features.subscription.KateUsageManager(prefs)
        claudeAI       = com.kate.assistant.features.ai.ClaudeAI()
        deepgram       = com.kate.assistant.features.voice.DeepgramSTT(
            onTranscript = { text -> handler.post { handleSpeech(text) } },
            onError      = { err  -> Log.e(TAG, "Deepgram error: $err") }
        )
        networkMonitor = com.kate.assistant.core.network.KateNetworkMonitor(this).also { nm ->
            nm.onOnline  = {
                onlineMode = true
                // Tap the AudioRecord stream into Deepgram when we come online
                if (::speech.isInitialized && ::deepgram.isInitialized) {
                    deepgram.connect()
                    speech.onAudioFrame = { pcm, len ->
                        if (!speech.isSpeaking()) deepgram.sendAudio(pcm, len)
                    }
                }
                handler.post {
                    KateEventBus.emit(KateEvent.OnlineModeChanged(online = true))
                    updateNotification("Online · Always listening")
                }
            }
            nm.onOffline = {
                onlineMode = false
                // Disconnect Deepgram and remove the audio tap — zero overhead offline
                if (::speech.isInitialized) speech.onAudioFrame = null
                if (::deepgram.isInitialized) deepgram.finalize()
                handler.post {
                    KateEventBus.emit(KateEvent.OnlineModeChanged(online = false))
                    updateNotification("Offline · Always listening")
                }
            }
            nm.start()
            onlineMode = nm.isOnline
            // If already online at startup, wire immediately
            if (onlineMode) {
                deepgram.connect()
                // speech isn't initialised yet at this point — the tap is set
                // in onInitComplete() after speech is created
            }
        }

        tts          = KateTts(this)
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

        tts.onSpeechActive = {
            if (::speech.isInitialized) speech.setSpeaking(true)
        }
        tts.onSpeechIdle = {
            if (::speech.isInitialized) speech.setSpeaking(false)
        }

        val modelPath = File(filesDir, "vosk-model").absolutePath

        speech = KateSpeechManager(
            context   = this,
            modelPath = modelPath,
            onResult  = { text ->
                // When online, Deepgram is the primary STT — suppress VOSK results
                // to avoid the same utterance being processed twice (once by each engine).
                // VOSK still runs for AudioRecord continuity and offline fallback.
                if (!onlineMode) handler.post { handleSpeech(text) }
                else Log.v(TAG, "VOSK result suppressed (online): \"$text\"")
            },
            onError   = { err ->
                when (err) {
                    "AUDIO_DEAD" -> {
                        // Staged restart: stop fully first, then wait 500ms for AudioRecord
                        // to release before opening a new one. This prevents the
                        // STATE_INITIALIZED failure that caused the on/off mic instability.
                        if (!micRestartPending) {
                            micRestartPending = true
                            Log.w(TAG, "Audio dead — staged restart in 2s")
                            KateEventBus.emit(KateEvent.MicStateChanged(listening = false))
                            handler.postDelayed({
                                if (::speech.isInitialized) {
                                    speech.stopListening()
                                    // Give the OS time to release the mic session before
                                    // allocating a new AudioRecord on top of it.
                                    handler.postDelayed({
                                        micRestartPending = false
                                        if (::speech.isInitialized) {
                                            speech.startListening()
                                            updateNotification("Always listening")
                                            KateEventBus.emit(KateEvent.MicStateChanged(listening = true))
                                        }
                                    }, 500)
                                }
                            }, 2000)
                        }
                    }
                    "VOSK_MODEL_MISSING", "NO_MIC_PERMISSION" -> {
                        // VOSK model is downloaded by CI (android-release.yml) at build time.
                        // A sideloaded/debug build without that step will hit this path.
                        // AudioRecord is already open so Android 14 FGS is satisfied.
                        // We just can't transcribe — inform the user and stop retrying.
                        Log.e(TAG, "STT unavailable: $err")
                        updateNotification("Voice model missing")
                        KateEventBus.emit(KateEvent.MicStateChanged(listening = false))
                        handler.post {
                            if (::tts.isInitialized)
                                tts.speak("Voice recognition is unavailable on this build.")
                        }
                    }
                    else -> Log.e(TAG, "Speech error: $err")
                }
            }
        )

        bridge.updateAppList(loadInstalledApps())
        val formatted = runBlocking {
            habits.getAll().map { "${it.intent}|${it.entity}|${it.count}" }.toTypedArray()
        }
        bridge.loadHabits(formatted)
        initDone = true
        Log.d(TAG, "All components initialised")
    }

    private fun onInitComplete() {
        setupEventBus()

        // If we came online before speech was ready, set the audio tap now
        if (onlineMode && ::deepgram.isInitialized) {
            speech.onAudioFrame = { pcm, len ->
                if (!speech.isSpeaking()) deepgram.sendAudio(pcm, len)
            }
            if (!deepgram.isConnected()) deepgram.connect()
        }

        speech.startListening()
        updateNotification(if (onlineMode) "Online · Always listening" else "Offline · Always listening")
        KateEventBus.emit(KateEvent.MicStateChanged(listening = true))

        // RECEIVER_NOT_EXPORTED is required on Android 13+ (targetSdk 34).
        // Without this flag, registerReceiver throws IllegalArgumentException on API 33+.
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(chargingReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(chargingReceiver, filter)
            }
            chargingRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Charging receiver failed: ${e.message}")
        }

        // Grammar building intentionally omitted — it caused a race condition:
        // updateGrammar() closes and recreates the VOSK Recognizer on the IO thread
        // while onSpeechIdle fires recognizer.reset() on the main thread right after
        // the greeting TTS finishes. Native VOSK code doesn't survive the collision.
        // VOSK runs full vocabulary recognition — still fully functional.

        handler.postDelayed({ greetUser() }, 1200)
        startWatchdog()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?) = null

    override fun onDestroy() {
        if (chargingRegistered) try { unregisterReceiver(chargingReceiver) } catch (_: Exception) {}
        if (::speech.isInitialized)         speech.shutdown()
        if (::tts.isInitialized)            tts.shutdown()
        if (::deepgram.isInitialized)       deepgram.shutdown()
        if (::claudeAI.isInitialized)       claudeAI.shutdown()
        if (::networkMonitor.isInitialized) networkMonitor.stop()
        if (::classifier.isInitialized)     classifier.close()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════
    // SPEECH ROUTER
    // ══════════════════════════════════════════════════════════

    private fun handleSpeech(text: String) {
        if (!initDone) return
        val clean = text.trim()
        when {
            clean.equals("WAKE", ignoreCase = true) -> { speak("Yes, $userName?"); return }
            clean.length <= 2 -> return
        }
        if (pendingContext != null) { handlePendingContext(clean); return }
        if (clean.length > 2) scope.launch { handleCommand(clean) }
    }

    private fun handlePendingContext(answer: String) {
        val ctx  = pendingContext ?: return
        val data = pendingData
        pendingContext = null
        pendingData    = null
        scope.launch {
            when (ctx) {
                "APP" -> {
                    speak("Opening $answer")
                    if (!launcher.launchByVoiceCommand(answer))
                        speak("I couldn't find $answer, $userName.")
                }
                "CALL" -> {
                    val number = lookupContact(answer)
                    if (number != null) { speak("Calling $answer"); handler.postDelayed({ makeCall(number) }, 1200) }
                    else speak("I couldn't find $answer in your contacts.")
                }
                "MESSAGE_TO" -> {
                    speak("What should I say to $answer?")
                    pendingContext = "MESSAGE_BODY"; pendingData = answer
                }
                "MESSAGE_BODY" -> {
                    val name = data ?: "them"
                    val number = lookupContact(name)
                    if (number != null) { sendSms(number, answer); speak("Message sent to $name.") }
                    else speak("Couldn't find $name in contacts.")
                }
                "SEARCH" -> { speak("Searching for $answer"); launcher.search(answer) }
                "SPOTIFY_SONG" -> {
                    speak("Playing $answer on Spotify.")
                    launcher.playOnSpotify(answer)
                }
                "REMINDER_TASK" -> {
                    val ms = data?.toLongOrNull() ?: 0L
                    if (ms > 0) { reminder.schedule(answer, ms); speak("Reminder set for $answer.") }
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
        if (lower.all { !it.isLetter() && !it.isDigit() }) return

        when {

            // ── Identity ──────────────────────────────────────
            lower.contains("who are you") || lower.contains("what are you") ||
            lower.contains("about yourself") -> speak(
                "I'm Kate — your personal AI assistant built by Dewrabber's Tech Institute. " +
                "I use cloud AI when you're connected for the smartest possible answers, " +
                "and switch to on-device processing automatically when you're offline. " +
                "I can open apps, make calls, send messages, control your phone, " +
                "set reminders, answer questions, and much more.")

            lower.contains("who made you") || lower.contains("who created you") ||
            lower.contains("who built you") -> speak("I was built by Dewrabber's Tech Institute, D.T.I.")

            lower.contains("what can you do") || lower.contains("your capabilities") ||
            lower.contains("list your features") -> speak(
                "I can open and close any installed app, make phone calls, send texts, " +
                "search Google, YouTube, and Maps, control your flashlight, volume, " +
                "Do Not Disturb and silent mode, solve math problems, play music on Spotify, " +
                "set reminders and timers, tell the time and date, check battery level, " +
                "lock your screen, toggle WiFi and Bluetooth, go back, go home, " +
                "open recent apps, take screenshots, pull down notifications, " +
                "read your screen, and type for you. Just say the word.")

            lower.contains("how are you") || lower.contains("are you okay") ->
                speak("Running perfectly, $userName. All systems are green.")

            lower.contains("do you learn") || lower.contains("can you learn") ||
            lower.contains("do you remember") -> speak(
                "Yes! I track your app usage and command patterns " +
                "to predict what you need next. The more you use me, the sharper I get.")

            // ── Greetings ─────────────────────────────────────
            lower.contains("hello") || lower.contains("hi kate") ||
            lower.contains("hey kate") ||
            lower.matches(Regex("good (morning|afternoon|evening|night).*")) -> {
                val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                speak(when {
                    h in 5..11  -> "Good morning, $userName! Ready to help."
                    h in 12..16 -> "Good afternoon! What can I do for you?"
                    h in 17..20 -> "Good evening, $userName. How can I help?"
                    else        -> "Hello $userName! I'm awake. What do you need?"
                })
            }

            // ── Time ─────────────────────────────────────────
            lower.contains("time") && !lower.contains("remind") && !lower.contains("timer") -> {
                val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                speak("It's $t.")
            }

            // ── Date ─────────────────────────────────────────
            lower.contains("date") || lower.contains("what day") || lower.contains("day is it") -> {
                val d = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                speak("Today is $d.")
            }

            // ── Battery ───────────────────────────────────────
            lower.contains("battery") || lower.contains("power level") ||
            lower.contains("how much charge") -> {
                val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
                val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val charging = bm?.isCharging ?: false
                speak(when {
                    pct < 0   -> "I couldn't read the battery level right now."
                    charging  -> "Battery is at $pct percent and currently charging."
                    pct <= 15 -> "Battery is low at $pct percent. Plug in soon, $userName."
                    else      -> "Battery is at $pct percent."
                })
            }

            // ── Math ──────────────────────────────────────────
            lower.contains("calculate") || lower.contains("how much is") ||
            lower.contains("how many is") || lower.contains("square root") ||
            lower.matches(Regex(".*\\b(plus|minus|times|divided by|divide|multiply|percent of|percent)\\b.*")) ||
            lower.matches(Regex(".*\\d+\\s*[+\\-*/]\\s*\\d+.*")) ||
            (lower.contains("what is") && lower.matches(
                Regex(".*\\b(\\d+|plus|minus|times|divided|multiply|percent|sqrt|square root)\\b.*"))) -> {
                val result = evaluateMath(lower)
                if (result != null) speak("The answer is $result.")
                else speak("Sorry, I couldn't calculate that. Try saying: calculate five plus three.")
            }

            // ── Close App ─────────────────────────────────────
            lower.startsWith("close ") || lower.startsWith("force stop ") -> {
                val appName = lower.removePrefix("close").removePrefix("force stop").trim()
                if (appName.isBlank()) speak("Which app should I close?")
                else {
                    val killed = launcher.forceStopApp(appName)
                    speak(if (killed) "Closed $appName." else "Could not close $appName.")
                }
            }

            // ── App launching ─────────────────────────────────
            lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ") -> {
                val app = lower.removePrefix("open").removePrefix("launch").removePrefix("start").trim()
                if (app.isBlank()) { speak("Which app should I open?"); pendingContext = "APP" }
                else {
                    speak("Opening $app")
                    if (!launcher.launchByVoiceCommand(app)) speak("I couldn't find $app.")
                }
            }

            // ── Spotify ───────────────────────────────────────
            lower.contains("play") && lower.contains("spotify") -> {
                val song = lower.replace("play","").replace("on spotify","").replace("spotify","").trim()
                if (launcher.isSpotifyInstalled()) {
                    if (song.isNotEmpty() && !song.equals("music", true) && !song.equals("song", true)) {
                        speak("Playing $song on Spotify."); launcher.playOnSpotify(song)
                    } else { speak("What song should I play on Spotify?"); pendingContext = "SPOTIFY_SONG" }
                } else speak("Spotify is not installed.")
            }

            lower.contains("is spotify installed") || lower.contains("do i have spotify") ->
                speak(if (launcher.isSpotifyInstalled()) "Yes, Spotify is installed." else "No, Spotify is not installed.")

            lower.contains("play music") -> { speak("Opening music."); launcher.openMusicApp() }

            // ── Search ────────────────────────────────────────
            lower.contains("search for") || lower.contains("google ") ||
            lower.contains("look up") || lower.contains("search ") -> {
                val q = lower.replace("search for","").replace("google","")
                    .replace("look up","").replace("search","").trim()
                if (q.isBlank()) { speak("What should I search?"); pendingContext = "SEARCH" }
                else { speak("Searching for $q"); launcher.search(q) }
            }

            lower.contains("youtube") -> {
                val q = lower.replace("youtube","").replace("search","").trim()
                speak(if (q.isBlank()) "Opening YouTube" else "Searching YouTube for $q")
                launcher.search(q.ifBlank { "" }, SearchEngine.YOUTUBE)
            }

            lower.contains("navigate to") || lower.contains("directions to") ||
            lower.contains("take me to") -> {
                val dest = lower.replace("navigate to","").replace("directions to","")
                    .replace("take me to","").trim()
                if (dest.isBlank()) speak("Where should I navigate to?")
                else { speak("Navigating to $dest"); launcher.search(dest, SearchEngine.MAPS) }
            }

            // ── Calls ─────────────────────────────────────────
            lower.startsWith("call ") -> {
                val name = lower.removePrefix("call").trim()
                if (name.isBlank()) { speak("Who should I call?"); pendingContext = "CALL" }
                else {
                    val number = lookupContact(name)
                    if (number != null) { speak("Calling $name"); handler.postDelayed({ makeCall(number) }, 1400) }
                    else speak("I couldn't find $name in your contacts.")
                }
            }

            lower.startsWith("dial ") -> {
                val number = lower.removePrefix("dial").trim()
                if (number.isBlank()) speak("What number should I dial?")
                else { speak("Dialling $number"); handler.postDelayed({ makeCall(number) }, 1200) }
            }

            // ── Messages ──────────────────────────────────────
            lower.contains("send message to") || lower.contains("text ") ||
            (lower.startsWith("message ") && lower.contains(" saying ")) -> {
                val parts = lower.replace("send message to","").replace("text","")
                    .replace("message","").trim().split(" saying ")
                val name = parts.getOrNull(0)?.trim() ?: ""
                val msg  = parts.getOrNull(1)?.trim() ?: ""
                when {
                    name.isBlank() -> { speak("Who should I message?"); pendingContext = "MESSAGE_TO" }
                    msg.isBlank()  -> { speak("What should I say to $name?"); pendingContext = "MESSAGE_BODY"; pendingData = name }
                    else -> {
                        val number = lookupContact(name)
                        if (number != null) { sendSms(number, msg); speak("Message sent to $name.") }
                        else speak("I couldn't find $name in your contacts.")
                    }
                }
            }

            // ── Flashlight ────────────────────────────────────
            lower.contains("torch on") || lower.contains("flashlight on") ||
            lower.contains("turn on torch") || lower.contains("turn on flashlight") || lower.contains("light on") ->
                if (hardware.torchOn()) speak("Flashlight on.") else speak("Couldn't turn on flashlight.")

            lower.contains("torch off") || lower.contains("flashlight off") ||
            lower.contains("turn off torch") || lower.contains("turn off flashlight") || lower.contains("light off") ->
                if (hardware.torchOff()) speak("Flashlight off.") else speak("Couldn't turn off flashlight.")

            // ── Volume ────────────────────────────────────────
            lower.contains("volume up") || lower.contains("increase volume") ||
            lower.contains("turn up") || lower.contains("louder") -> { hardware.volumeUp(); speak("Volume up.") }

            lower.contains("volume down") || lower.contains("decrease volume") ||
            lower.contains("turn down") || lower.contains("quieter") -> { hardware.volumeDown(); speak("Volume down.") }

            lower.contains("unmute") || lower.contains("turn on sound") -> { hardware.unmuteAll(); speak("Unmuted.") }

            lower.contains("mute") || lower.contains("silent mode") -> { hardware.muteAll(); speak("Muted.") }

            lower.contains("max volume") || lower.contains("full volume") ->
                { repeat(15) { hardware.volumeUp() }; speak("Volume maxed.") }

            // ── Do Not Disturb ────────────────────────────────
            lower.contains("do not disturb on") || lower.contains("enable dnd") -> {
                val nm = getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    speak("I need notification access. Opening settings.")
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else { hardware.setDND(true); speak("Do Not Disturb enabled.") }
            }

            lower.contains("do not disturb off") || lower.contains("disable dnd") -> {
                val nm = getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    speak("I need notification access. Opening settings.")
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else { hardware.setDND(false); speak("Do Not Disturb disabled.") }
            }

            // ── Lock Screen ───────────────────────────────────
            lower.contains("lock phone") || lower.contains("lock screen") ||
            lower.contains("lock the phone") -> {
                if (dpm.isAdminActive(adminComponent)) {
                    speak("Locking screen."); handler.postDelayed({ dpm.lockNow() }, 800)
                } else {
                    speak("I need Device Admin permission to lock your screen. Opening settings.")
                    startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Kate needs Device Admin to lock your screen on command.")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }

            // ── WiFi ──────────────────────────────────────────
            lower.contains("wifi on") || lower.contains("turn on wifi") || lower.contains("enable wifi") -> {
                when (hardware.setWifi(true)) {
                    KateHardwareController.WifiResult.TOGGLED -> speak("WiFi turned on.")
                    KateHardwareController.WifiResult.NEEDS_PANEL -> {
                        speak("Opening WiFi settings.")
                        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    KateHardwareController.WifiResult.FAILED -> speak("Couldn't toggle WiFi.")
                }
            }

            lower.contains("wifi off") || lower.contains("turn off wifi") || lower.contains("disable wifi") -> {
                when (hardware.setWifi(false)) {
                    KateHardwareController.WifiResult.TOGGLED -> speak("WiFi turned off.")
                    KateHardwareController.WifiResult.NEEDS_PANEL -> {
                        speak("Opening WiFi settings.")
                        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    KateHardwareController.WifiResult.FAILED -> speak("Couldn't toggle WiFi.")
                }
            }

            lower.contains("open wifi") || lower.contains("wifi settings") -> {
                speak("Opening WiFi settings.")
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            // ── Bluetooth ─────────────────────────────────────
            lower.contains("bluetooth on") || lower.contains("turn on bluetooth") || lower.contains("enable bluetooth") -> {
                when (hardware.setBluetooth(true)) {
                    KateHardwareController.BluetoothResult.TOGGLED -> speak("Bluetooth turned on.")
                    KateHardwareController.BluetoothResult.NEEDS_SETTINGS -> {
                        speak("Opening Bluetooth settings.")
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    KateHardwareController.BluetoothResult.FAILED -> speak("Couldn't toggle Bluetooth.")
                }
            }

            lower.contains("bluetooth off") || lower.contains("turn off bluetooth") || lower.contains("disable bluetooth") -> {
                when (hardware.setBluetooth(false)) {
                    KateHardwareController.BluetoothResult.TOGGLED -> speak("Bluetooth turned off.")
                    KateHardwareController.BluetoothResult.NEEDS_SETTINGS -> {
                        speak("Opening Bluetooth settings.")
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    KateHardwareController.BluetoothResult.FAILED -> speak("Couldn't toggle Bluetooth.")
                }
            }

            lower.contains("bluetooth settings") || lower.contains("open bluetooth") -> {
                speak("Opening Bluetooth settings.")
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            // ── Reminders & Timers ────────────────────────────
            lower.contains("remind me") || lower.contains("set reminder") || lower.contains("set alarm") -> {
                val ms = parseTime(lower)
                if (ms > 0) {
                    val task = lower.replace("remind me to","").replace("remind me","")
                        .replace("set reminder","").replace("set alarm","")
                        .replace(Regex("in \\d+ (second|minute|hour)s?"),"").trim().ifBlank { "reminder" }
                    reminder.schedule(task, ms)
                    speak("Done. I'll remind you to $task in ${humanTime(ms)}.")
                } else speak("When should I remind you? For example: remind me in 10 minutes.")
            }

            lower.contains("set timer") || lower.contains("timer for") -> {
                val ms = parseTime(lower)
                if (ms > 0) { reminder.schedule("Timer", ms); speak("Timer set for ${humanTime(ms)}.") }
                else speak("How long should the timer be?")
            }

            // ── Accessibility navigation ──────────────────────
            lower.contains("go back") || lower == "back" ->
                KateAccessibilityService.instance?.goBack()
                    ?: speak("Please enable Kate accessibility service first.")

            lower.contains("go home") || lower == "home" || lower.contains("home screen") ->
                KateAccessibilityService.instance?.goHome()
                    ?: speak("Accessibility service needed.")

            lower.contains("recent apps") || lower.contains("show recents") ->
                KateAccessibilityService.instance?.openRecents()
                    ?: speak("Accessibility service needed.")

            lower.contains("take screenshot") || lower == "screenshot" -> {
                KateAccessibilityService.instance?.takeScreenshot()
                    ?: speak("Accessibility service needed.")
                if (KateAccessibilityService.instance != null) speak("Screenshot taken.")
            }

            lower.contains("show notifications") || lower.contains("pull down notifications") ->
                KateAccessibilityService.instance?.showNotifications()
                    ?: speak("Accessibility service needed.")

            lower.contains("quick settings") || lower.contains("open quick settings") ->
                KateAccessibilityService.instance?.openQuickSettings()
                    ?: speak("Accessibility service needed.")

            lower.contains("read screen") || lower.contains("what's on screen") ||
            lower.contains("what is on screen") -> {
                val content = KateAccessibilityService.instance?.readScreen() ?: ""
                if (content.isNotBlank()) speak(content.take(400))
                else speak("Nothing readable on screen right now.")
            }

            lower.startsWith("type ") || lower.startsWith("write ") -> {
                val typing = lower.removePrefix("type").removePrefix("write").trim()
                val ok = KateAccessibilityService.instance?.ghostType(typing) ?: false
                speak(if (ok) "Done." else "No text field found. Tap a text field first.")
            }

            // ── System shortcuts ──────────────────────────────
            lower.contains("open settings") || lower == "settings" -> {
                speak("Opening settings.")
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            lower.contains("open browser") || lower.contains("open chrome") ->
                { speak("Opening browser."); launcher.openBrowser() }

            // ── Stop / Repeat ─────────────────────────────────
            lower.contains("stop listening") || lower.contains("goodbye kate") ||
            lower.contains("bye kate") || lower.contains("sleep kate") -> {
                speak("Goodbye, $userName! I'll be here when you need me.")
                handler.postDelayed({ if (::speech.isInitialized) speech.stopListening() }, 2500)
            }

            lower.contains("say that again") || lower.contains("repeat that") ||
            lower.contains("what did you say") ->
                tts.speak(lastSpoken.ifBlank { "I haven't said anything yet." })

            // ── TFLite ML fallback ────────────────────────────
            else -> {
                if (lower.length > 2) {
                    // ── Online path: Claude AI ─────────────────────────────
                    // Try Claude first when connected and quota allows.
                    // The user never sees the switch — it's seamless.
                    // handleCommand is already a suspend fun called from scope.launch,
                    // so we can call suspend fns directly — no runBlocking needed.
                    val usedOnline = if (onlineMode) {
                        val canUse = usageManager.canMakeOnlineRequest()
                        if (canUse) {
                            val response = claudeAI.respond(
                                userText  = raw,
                                userName  = userName,
                                history   = conversationHistory.toList()
                            )
                            if (response != null) {
                                conversationHistory.addLast(Pair("user",      raw))
                                conversationHistory.addLast(Pair("assistant", response))
                                while (conversationHistory.size > 20) conversationHistory.removeFirst()
                                usageManager.recordRequest()
                                // Finalize Deepgram session for this utterance
                                // and reopen for the next one
                                if (::deepgram.isInitialized) {
                                    deepgram.finalize()
                                    // Reopen after TTS finishes so we don't capture echo
                                    handler.postDelayed({
                                        if (onlineMode && ::deepgram.isInitialized)
                                            deepgram.connect()
                                    }, 1500)
                                }
                                speak(response)
                                true
                            } else false
                        } else {
                            val msg = usageManager.limitMessage()
                            speak(msg)
                            true
                        }
                    } else false

                    // ── Offline fallback: local intent classifier ──────────
                    if (!usedOnline) {
                        val intent = try {
                            withContext(Dispatchers.IO) { classifier.classify(raw) }
                        } catch (_: Exception) { "UNKNOWN" }
                        when (intent) {
                            "OPEN_APP"       -> { speak("Which app should I open?"); pendingContext = "APP" }
                            "MEDIA_CONTROL"  -> { speak("Opening music."); launcher.openMusicApp() }
                            "COMMUNICATION"  -> { speak("Who should I contact?"); pendingContext = "CALL" }
                            "REMINDER"       -> speak("When should I remind you? Say for example: in 10 minutes.")
                            "SYSTEM_CONTROL" -> speak("What system setting should I change?")
                            else             -> speak(nextFallback())
                        }
                    }
                }
            }
        }

        try { bridge.processText(raw) } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════
    // MATH ENGINE
    // ══════════════════════════════════════════════════════════

    private fun evaluateMath(input: String): String? {
        return try {
            var expr = input
                .replace(Regex("calculate|compute|solve|evaluate"), "")
                .replace("what is", "").replace("what's", "")
                .replace("how much is", "").replace("how many is", "")
                .replace("the answer to", "").replace("equals", "").trim()

            // Percentage of: "20 percent of 500"
            val pctOf = Regex("(\\d+(?:\\.\\d+)?)\\s*percent\\s*of\\s*(\\d+(?:\\.\\d+)?)")
            pctOf.find(expr)?.let {
                val pct = it.groupValues[1].toDouble()
                val num = it.groupValues[2].toDouble()
                return formatMathResult(pct / 100.0 * num)
            }

            // Square root
            if (expr.contains("square root") || expr.contains("sqrt")) {
                val cleaned = wordsToDigits(
                    expr.replace(Regex("square root of|square root|sqrt"), "").trim())
                return cleaned.toDoubleOrNull()
                    ?.let { kotlin.math.sqrt(it) }
                    ?.let { formatMathResult(it) }
            }

            // Word operators → symbols
            expr = expr
                .replace("plus", "+").replace(" add ", " + ")
                .replace("minus", "-").replace("subtract", "-").replace("take away", "-")
                .replace("times", "*").replace("multiplied by", "*").replace("multiply", "*").replace("into", "*")
                .replace("divided by", "/").replace("divide by", "/").replace("over", "/")
                .replace(Regex("\\s*percent\\b"), " / 100")

            // Number words → digits
            expr = wordsToDigits(expr)

            // Strip non-math characters
            expr = expr.replace(Regex("[^0-9+\\-*/.() ]"), " ").trim()
            if (expr.isBlank()) return null

            val result = SimpleEval.eval(expr) ?: return null
            formatMathResult(result)
        } catch (e: Exception) { null }
    }

    private fun wordsToDigits(text: String): String {
        val map = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3",
            "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
            "eight" to "8", "nine" to "9", "ten" to "10",
            "eleven" to "11", "twelve" to "12", "thirteen" to "13",
            "fourteen" to "14", "fifteen" to "15", "sixteen" to "16",
            "seventeen" to "17", "eighteen" to "18", "nineteen" to "19",
            "twenty" to "20", "thirty" to "30", "forty" to "40",
            "fifty" to "50", "sixty" to "60", "seventy" to "70",
            "eighty" to "80", "ninety" to "90", "hundred" to "100",
            "thousand" to "1000", "million" to "1000000"
        )
        var result = text
        map.entries.sortedByDescending { it.key.length }
            .forEach { (word, digit) -> result = result.replace(Regex("\\b$word\\b"), digit) }
        return result
    }

    private fun formatMathResult(d: Double): String = when {
        d.isInfinite() -> "undefined — you cannot divide by zero"
        d.isNaN()      -> "undefined"
        d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1_000_000_000 -> d.toLong().toString()
        else           -> "%.6f".format(d).trimEnd('0').trimEnd('.')
    }

    // ══════════════════════════════════════════════════════════
    // SPEAK
    // ══════════════════════════════════════════════════════════

    private var lastSpoken = ""
    private fun speak(text: String) { lastSpoken = text; tts.speak(text) }

    private val fallbacks = listOf(
        "Hmm, I didn't quite get that, $userName. Say it again?",
        "Sorry, I'm not sure I understood. Try once more?",
        "I'm still learning! Could you rephrase that?",
        "My ears must have slipped — say that again?"
    )
    private var fallbackIdx = 0
    private fun nextFallback() = fallbacks[fallbackIdx++ % fallbacks.size]

    // ══════════════════════════════════════════════════════════
    // GREETING
    // ══════════════════════════════════════════════════════════

    private fun greetUser() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        speak(when {
            h in 5..11  -> "Good morning, $userName! Kate is online and always listening."
            h in 12..16 -> "Good afternoon, $userName! Kate here. What can I do for you?"
            h in 17..20 -> "Good evening, $userName! Kate is ready. How can I help?"
            else        -> "Kate is online, $userName. Always here when you need me."
        })
    }

    // ══════════════════════════════════════════════════════════
    // EVENT BUS
    // ══════════════════════════════════════════════════════════

    private fun setupEventBus() {
        KateEventBus.subscribe { event ->
            when (event) {
                is KateEvent.WakeWordDetected -> Log.d(TAG, "Wake word via C bridge")
                is KateEvent.HabitUpdate      -> saveHabit(event)
                is KateEvent.AppOpened        -> { journal.logAppOpen(event.packageName); proactive.evaluate() }
                is KateEvent.Error            -> Log.e(TAG, "Bridge error: ${event.message}")
                else                          -> Unit
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // NOTIFICATION
    // ══════════════════════════════════════════════════════════

    private fun startForegroundNow() {
        val ch = NotificationChannel(CHANNEL_ID, "Kate Assistant", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val notif = buildNotification("Starting up...")
        // FOREGROUND_SERVICE_TYPE_MICROPHONE requires API 30 (R).
        // API 26-29: use 2-arg startForeground — mic still works without the type declaration.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val notif = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kate")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    // ══════════════════════════════════════════════════════════
    // WATCHDOG
    // ══════════════════════════════════════════════════════════

    private fun startWatchdog() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                // Only restart if mic is genuinely stopped AND no restart is already
                // in flight from the AUDIO_DEAD handler. Without this guard, the
                // watchdog could stack a second startListening() during the 2.5s
                // restart window, causing a double AudioRecord allocation.
                if (initDone && ::speech.isInitialized &&
                    !micRestartPending &&
                    !speech.isListening() && !speech.isSpeaking()) {
                    Log.w(TAG, "Watchdog: restarting speech")
                    speech.startListening()
                    updateNotification("Always listening")
                    KateEventBus.emit(KateEvent.MicStateChanged(listening = true))
                }
                handler.postDelayed(this, 30_000L)
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

// ══════════════════════════════════════════════════════════════
// SIMPLE EXPRESSION EVALUATOR — no external library needed.
// Handles: +, -, *, /, parentheses, correct operator precedence.
// ══════════════════════════════════════════════════════════════

private object SimpleEval {

    fun eval(expr: String): Double? = try {
        Parser(tokenize(expr.replace(" ", ""))).parseExpr()
    } catch (_: Exception) { null }

    private fun tokenize(expr: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < expr.length) {
            when {
                expr[i].isDigit() || expr[i] == '.' -> {
                    val start = i
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                    tokens.add(expr.substring(start, i))
                }
                expr[i] in "+-*/()" -> { tokens.add(expr[i].toString()); i++ }
                else -> i++
            }
        }
        return tokens
    }

    private class Parser(private val t: List<String>) {
        private var i = 0
        fun parseExpr(): Double {
            var v = parseTerm()
            while (i < t.size && t[i] in listOf("+", "-")) {
                val op = t[i++]; val r = parseTerm()
                v = if (op == "+") v + r else v - r
            }
            return v
        }
        private fun parseTerm(): Double {
            var v = parseFactor()
            while (i < t.size && t[i] in listOf("*", "/")) {
                val op = t[i++]; val r = parseFactor()
                v = if (op == "*") v * r else { if (r == 0.0) throw ArithmeticException("div/0"); v / r }
            }
            return v
        }
        private fun parseFactor(): Double {
            if (i >= t.size) throw RuntimeException("Unexpected end")
            return when {
                t[i] == "(" -> { i++; parseExpr().also { if (i < t.size && t[i] == ")") i++ } }
                t[i] == "-" -> { i++; -parseFactor() }
                t[i].toDoubleOrNull() != null -> t[i++].toDouble()
                else -> throw RuntimeException("Bad token: ${t[i]}")
            }
        }
    }
}
