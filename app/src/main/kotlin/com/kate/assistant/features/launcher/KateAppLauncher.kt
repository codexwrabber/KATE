package com.kate.assistant.features.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.os.Build
import android.provider.Settings

class KateAppLauncher(private val context: Context) {
    private val pm = context.packageManager

    // All launchable apps — queried properly
    private val launchableApps: List<Pair<String, String>> by lazy {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
            .map { info ->
                Pair(
                    info.loadLabel(pm).toString().lowercase().trim(),
                    info.activityInfo.packageName
                )
            }
            .distinctBy { it.second }
            .also { Log.d("KateLauncher", "Found ${it.size} launchable apps") }
    }

    // Known apps map with alternates (including Nigerian apps)
    private val knownApps = mapOf(
        // ─────────────────────────────────────────────────────────
        // NIGERIAN BANKING & PAYMENT APPS
        // ─────────────────────────────────────────────────────────
        "opay"           to "com.opay.wallet",
        "o pay"          to "com.opay.wallet",
        "opay wallet"    to "com.opay.wallet",
        "palm pay"       to "com.palmpay",
        "palmplay"       to "com.palmpay",
        "palm"           to "com.palmpay",
        "moniepoint"     to "com.moniepoint.moniepoint",
        "monie point"    to "com.moniepoint.moniepoint",
        "paga"           to "com.paga.android",
        "paga app"       to "com.paga.android",
        "gthold"         to "com.gtb.gtbank",
        "gtbank"         to "com.gtb.gtbank", 
        "gt bank"        to "com.gtb.gtbank",
        "access bank"    to "com.accessbank.access",
        "access"         to "com.accessbank.access",
        "first bank"     to "com.firstbank.fib",
        "firstbank"      to "com.firstbank.fib",
        "uba"            to "com.ubagroup.ubamobile",
        "united bank for africa" to "com.ubagroup.ubamobile",
        "zenith bank"    to "com.zenithbank.zenithmobile",
        "zenith"         to "com.zenithbank.zenithmobile",
        "fidelity bank"  to "com.fidelitybank.fidelity",
        "fidelity"       to "com.fidelitybank.fidelity",
        "kuda"           to "com.kuda.android",
        "kuda bank"      to "com.kuda.android",
        "carbon"         to "com.carbon.app",
        "carbon app"     to "com.carbon.app",
        "fairmoney"      to "com.fairmoney.fairmoney",
        "fair money"     to "com.fairmoney.fairmoney",
        "palmpay"        to "com.palmpay",
        
        // ─────────────────────────────────────────────────────────
        // NIGERIAN FOOD DELIVERY & SERVICES
        // ─────────────────────────────────────────────────────────
        "bolt food"      to "com.bolt.delivery",
        "glovo"          to "com.glovo",
        "chowdeck"       to "com.chowdeck.app",
        "chow deck"      to "com.chowdeck.app",
        
        // ─────────────────────────────────────────────────────────
        // NIGERIAN E-COMMERCE
        // ─────────────────────────────────────────────────────────
        "jumia"          to "com.jumia.android",
        "konga"          to "com.konga.android",
        "konga app"      to "com.konga.android",
        
        // ─────────────────────────────────────────────────────────
        // NIGERIAN TRANSPORTATION
        // ─────────────────────────────────────────────────────────
        "lagos ride"     to "com.lagosride.app",
        "lagos ride app" to "com.lagosride.app",
        "gokada"         to "com.gokada",
        "gokada app"     to "com.gokada",
        "uru"            to "com.urumobility",
        "uru ride"       to "com.urumobility",
        
        // ─────────────────────────────────────────────────────────
        // NIGERIAN TELECOMS
        // ─────────────────────────────────────────────────────────
        "mtn"            to "com.mtn.ngmyaccount",
        "mtn my account" to "com.mtn.ngmyaccount",
        "airtel"         to "com.airtel.airtelcare",
        "airtel care"    to "com.airtel.airtelcare",
        "glo"            to "com.glo.gloworld",
        "glo world"      to "com.glo.gloworld",
        "etisalat"       to "com.etisalat.etisalatng",
        "9mobile"        to "com.etisalat.etisalatng",
        
        // ─────────────────────────────────────────────────────────
        // NIGERIAN MEDIA & NEWS
        // ─────────────────────────────────────────────────────────
        "pulse nigeria"  to "com.pulse.ng",
        "pulse"          to "com.pulse.ng",
        "guardian nigeria" to "com.guardian.ng",
        "guardian"       to "com.guardian.ng",
        "legit ng"       to "com.legit",
        "legit"          to "com.legit",
        
        // ─────────────────────────────────────────────────────────
        // STANDARD APPS
        // ─────────────────────────────────────────────────────────
        "whatsapp"       to "com.whatsapp",
        "watsapp"        to "com.whatsapp",
        "what's app"     to "com.whatsapp",
        "youtube"        to "com.google.android.youtube",
        "you tube"       to "com.google.android.youtube",
        "spotify"        to "com.spotify.music",
        "instagram"      to "com.instagram.android",
        "insta"          to "com.instagram.android",
        "facebook"       to "com.facebook.katana",
        "fb"             to "com.facebook.katana",
        "twitter"        to "com.twitter.android",
        "tiktok"         to "com.zhiliaoapp.musically",
        "tick tock"      to "com.zhiliaoapp.musically",
        "chrome"         to "com.android.chrome",
        "browser"        to "com.android.chrome",
        "camera"         to "com.android.camera2",
        "gallery"        to "com.android.gallery3d",
        "photos"         to "com.google.android.apps.photos",
        "settings"       to "com.android.settings",
        "setting"        to "com.android.settings",
        "calculator"     to "com.android.calculator2",
        "calculate"      to "com.android.calculator2",
        "calendar"       to "com.android.calendar",
        "clock"          to "com.android.deskclock",
        "alarm"          to "com.android.deskclock",
        "maps"           to "com.google.android.apps.maps",
        "google maps"    to "com.google.android.apps.maps",
        "gmail"          to "com.google.android.gm",
        "email"          to "com.google.android.gm",
        "files"          to "com.google.android.apps.nbu.files",
        "file manager"   to "com.google.android.apps.nbu.files",
        "play store"     to "com.android.vending",
        "playstore"      to "com.android.vending",
        "store"          to "com.android.vending",
        "phone"          to "com.android.dialer",
        "dialer"         to "com.android.dialer",
        "messages"       to "com.google.android.apps.messaging",
        "sms"            to "com.google.android.apps.messaging",
        "contacts"       to "com.android.contacts",
        "contact"        to "com.android.contacts",
        "audiomack"      to "com.audiomack.audiomack",
        "audio mack"     to "com.audiomack.audiomack",
        "netflix"        to "com.netflix.mediaclient",
        "telegram"       to "org.telegram.messenger",
        "snapchat"       to "com.snapchat.android",
        "snap"           to "com.snapchat.android",
        "claude"         to "com.anthropic.claude",
        "chatgpt"        to "com.openai.chatgpt",
        "chat gpt"       to "com.openai.chatgpt",
        "deepseek"       to "com.deepseek.chat",
        "deep seek"      to "com.deepseek.chat",
        "notes"          to "com.google.android.keep",
        "keep"           to "com.google.android.keep",
        "youtube music"  to "com.google.android.youtube.music",
        "zoom"           to "us.zoom.videomeetings",
        "meet"           to "com.google.android.apps.meetings",
        "google meet"    to "com.google.android.apps.meetings",
        "word"           to "com.microsoft.office.word",
        "excel"          to "com.microsoft.office.excel",
        "powerpoint"     to "com.microsoft.office.powerpoint",
        "linkedin"       to "com.linkedin.android",
        "opera"          to "com.opera.browser",
        "firefox"        to "org.mozilla.firefox",
        "brave"          to "com.brave.browser",
        "reddit"         to "com.reddit.frontpage",
        "pinterest"      to "com.pinterest",
        "shazam"         to "com.shazam.android",
        "soundcloud"     to "com.soundcloud.android",
        "codespaces"     to "com.github.android",
        "github"         to "com.github.android",
        "lite"           to "com.facebook.lite",
        "facebook lite"  to "com.facebook.lite",
    )

    fun launchByVoiceCommand(command: String): Boolean {
        val cmd = command.lowercase().trim()
        Log.d("KateLauncher", "Launch command: '$cmd'")

        // 1. Known apps map — fastest and most reliable
        for ((name, pkg) in knownApps) {
            if (cmd.contains(name)) {
                Log.d("KateLauncher", "Known match: $name → $pkg")
                if (isInstalled(pkg) && launch(pkg)) return true
            }
        }

        // 2. Exact label match
        val exact = launchableApps.firstOrNull { (label, _) -> label == cmd }
        if (exact != null) {
            Log.d("KateLauncher", "Exact: ${exact.first} → ${exact.second}")
            if (launch(exact.second)) return true
        }

        // 3. Label contains command
        val contains = launchableApps.firstOrNull { (label, _) ->
            label.contains(cmd) || cmd.contains(label)
        }
        if (contains != null) {
            Log.d("KateLauncher", "Contains: ${contains.first} → ${contains.second}")
            if (launch(contains.second)) return true
        }

        // 4. Word-by-word match — ignore short words
        val words = cmd.split(" ").filter { it.length > 3 }
        for (word in words) {
            val match = launchableApps.firstOrNull { (label, _) ->
                label.contains(word)
            }
            if (match != null) {
                Log.d("KateLauncher", "Word '$word': ${match.first} → ${match.second}")
                if (launch(match.second)) return true
            }
        }

        // 5. Nothing found — search Play Store
        Log.d("KateLauncher", "No match for '$cmd' — searching Play Store")
        searchPlayStore(cmd)
        return false
    }

    fun launch(packageName: String): Boolean {
        return runCatching {
            val intent = pm.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) {
                context.startActivity(intent)
                Log.d("KateLauncher", "Launched: $packageName")
                true
            } else {
                Log.w("KateLauncher", "No launch intent: $packageName")
                false
            }
        }.getOrDefault(false)
    }

    // ─────────────────────────────────────────────────────────
    // FORCE STOP APP - Closes an app completely
    // ─────────────────────────────────────────────────────────
    fun forceStopApp(appName: String): Boolean {
        val cmd = appName.lowercase().trim()
        Log.d("KateLauncher", "Force stop command: '$cmd'")
        
        // First try to find the package name
        var targetPackage: String? = null
        
        // Check known apps map
        for ((name, pkg) in knownApps) {
            if (cmd.contains(name)) {
                targetPackage = pkg
                break
            }
        }
        
        // If not found, check launchable apps
        if (targetPackage == null) {
            val match = launchableApps.firstOrNull { (label, _) ->
                label.contains(cmd) || cmd.contains(label)
            }
            targetPackage = match?.second
        }
        
        // If still not found, try exact label match
        if (targetPackage == null) {
            val exact = launchableApps.firstOrNull { (label, _) -> label == cmd }
            targetPackage = exact?.second
        }
        
        return if (targetPackage != null) {
            forceStopPackage(targetPackage)
        } else {
            Log.w("KateLauncher", "App not found for force stop: $cmd")
            false
        }
    }
    
    private fun forceStopPackage(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                activityManager.killBackgroundProcesses(packageName)
                true
            } else {
                @Suppress("DEPRECATION")
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                activityManager.killBackgroundProcesses(packageName)
                true
            }
        } catch (e: Exception) {
            Log.e("KateLauncher", "Failed to force stop: $packageName - ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────
    // SPOTIFY PLAYBACK
    // ─────────────────────────────────────────────────────────
    fun playOnSpotify(songQuery: String) {
        Log.d("KateLauncher", "Playing on Spotify: $songQuery")
        val encodedQuery = Uri.encode(songQuery)
        
        // Try to search and play on Spotify
        val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encodedQuery"))
        searchIntent.setPackage("com.spotify.music")
        searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
        try {
            context.startActivity(searchIntent)
            Log.d("KateLauncher", "Spotify search opened for: $songQuery")
        } catch (e: Exception) {
            // Fallback: Just open Spotify
            val fallbackIntent = pm.getLaunchIntentForPackage("com.spotify.music")
            if (fallbackIntent != null) {
                context.startActivity(fallbackIntent)
                Log.d("KateLauncher", "Spotify app opened as fallback")
            } else {
                Log.e("KateLauncher", "Spotify not installed")
            }
        }
    }
    
    fun isSpotifyInstalled(): Boolean {
        return try {
            pm.getPackageInfo("com.spotify.music", PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun search(query: String, engine: SearchEngine = SearchEngine.GOOGLE) {
        if (query.isBlank()) return
        val url = when (engine) {
            SearchEngine.GOOGLE  -> "https://www.google.com/search?q=${Uri.encode(query)}"
            SearchEngine.YOUTUBE -> "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
            SearchEngine.MAPS    -> "geo:0,0?q=${Uri.encode(query)}"
        }
        openUrl(url)
    }

    fun openBrowser(url: String = "https://www.google.com") = openUrl(url)

    fun openMusicApp() {
        val music = listOf(
            "com.audiomack.audiomack",
            "com.spotify.music",
            "com.google.android.youtube.music",
            "com.soundcloud.android",
            "com.apple.android.music"
        )
        val found = music.firstOrNull { isInstalled(it) }
        if (found != null) launch(found)
        else search("music player")
    }

    private fun searchPlayStore(query: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://search?q=${Uri.encode(query)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            openUrl("https://play.google.com/store/search?q=${Uri.encode(query)}")
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun isInstalled(pkg: String) =
        runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}

enum class SearchEngine { GOOGLE, YOUTUBE, MAPS }
