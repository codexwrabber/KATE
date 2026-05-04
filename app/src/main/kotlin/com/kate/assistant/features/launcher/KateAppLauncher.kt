package com.kate.assistant.features.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

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

    // Known apps map with alternates
    private val knownApps = mapOf(
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
        "twitter"        to "com.twitter.android",
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

    private fun launch(packageName: String): Boolean {
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

    private fun isInstalled(pkg: String) =
        runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}

enum class SearchEngine { GOOGLE, YOUTUBE, MAPS }
