package com.kate.assistant.features.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Log

class KateAppLauncher(private val context: Context) {
    private val pm = context.packageManager

    // All launchable apps cached at startup
    private val launchableApps: List<Pair<String, String>> by lazy {
        val intent  = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        pm.queryIntentActivities(intent, 0).map { info ->
            Pair(
                info.loadLabel(pm).toString().lowercase().trim(),
                info.activityInfo.packageName
            )
        }.also { Log.d("KateLauncher", "Cached ${it.size} apps") }
    }

    // Known apps with alternate name mappings
    private val knownApps = mapOf(
        "whatsapp"       to "com.whatsapp",
        "youtube"        to "com.google.android.youtube",
        "spotify"        to "com.spotify.music",
        "instagram"      to "com.instagram.android",
        "facebook"       to "com.facebook.katana",
        "fb"             to "com.facebook.katana",
        "twitter"        to "com.twitter.android",
        "x"              to "com.twitter.android",
        "tiktok"         to "com.zhiliaoapp.musically",
        "chrome"         to "com.android.chrome",
        "browser"        to "com.android.chrome",
        "camera"         to "com.android.camera2",
        "gallery"        to "com.android.gallery3d",
        "photos"         to "com.google.android.apps.photos",
        "settings"       to "com.android.settings",
        "calculator"     to "com.android.calculator2",
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
        "store"          to "com.android.vending",
        "phone"          to "com.android.dialer",
        "dialer"         to "com.android.dialer",
        "messages"       to "com.google.android.apps.messaging",
        "sms"            to "com.google.android.apps.messaging",
        "contacts"       to "com.android.contacts",
        "audiomack"      to "com.audiomack.audiomack",
        "netflix"        to "com.netflix.mediaclient",
        "telegram"       to "org.telegram.messenger",
        "snapchat"       to "com.snapchat.android",
        "claude"         to "com.anthropic.claude",
        "chatgpt"        to "com.openai.chatgpt",
        "deepseek"       to "com.deepseek.chat",
        "music"          to "com.audiomack.audiomack",
        "notepad"        to "com.google.android.keep",
        "notes"          to "com.google.android.keep",
        "keep"           to "com.google.android.keep",
        "youtube music"  to "com.google.android.youtube.music",
        "zoom"           to "us.zoom.videomeetings",
        "meet"           to "com.google.android.apps.meetings",
        "google meet"    to "com.google.android.apps.meetings",
        "microsoft word" to "com.microsoft.office.word",
        "word"           to "com.microsoft.office.word",
        "excel"          to "com.microsoft.office.excel",
        "powerpoint"     to "com.microsoft.office.powerpoint",
        "linkedin"       to "com.linkedin.android",
        "amazon"         to "com.amazon.mShop.android.shopping",
        "uber"           to "com.ubercab",
        "opera"          to "com.opera.browser",
        "firefox"        to "org.mozilla.firefox",
        "brave"          to "com.brave.browser",
        "twitter"        to "com.twitter.android",
        "reddit"         to "com.reddit.frontpage",
        "pinterest"      to "com.pinterest",
        "shazam"         to "com.shazam.android",
        "soundcloud"     to "com.soundcloud.android",
    )

    fun launchByVoiceCommand(command: String) {
        val cmd = command.lowercase().trim()
        Log.d("KateLauncher", "Launching: $cmd")

        // 1. Check known apps map
        for ((name, pkg) in knownApps) {
            if (cmd.contains(name)) {
                Log.d("KateLauncher", "Known: $name → $pkg")
                if (launch(pkg)) return
            }
        }

        // 2. Exact label match from installed apps
        val exact = launchableApps.firstOrNull { (label, _) ->
            label == cmd
        }
        if (exact != null) {
            Log.d("KateLauncher", "Exact: ${exact.first} → ${exact.second}")
            if (launch(exact.second)) return
        }

        // 3. Contains match
        val contains = launchableApps.firstOrNull { (label, _) ->
            cmd.contains(label) || label.contains(cmd)
        }
        if (contains != null) {
            Log.d("KateLauncher", "Contains: ${contains.first} → ${contains.second}")
            if (launch(contains.second)) return
        }

        // 4. Word-by-word match
        val words = cmd.split(" ").filter { it.length > 2 }
        for (word in words) {
            val wordMatch = launchableApps.firstOrNull { (label, _) ->
                label.contains(word)
            }
            if (wordMatch != null) {
                Log.d("KateLauncher", "Word match '$word': ${wordMatch.second}")
                if (launch(wordMatch.second)) return
            }
        }

        // 5. Nothing found — search Play Store
        Log.d("KateLauncher", "No match for '$cmd' — searching Play Store")
        searchPlayStore(cmd)
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
        music.firstOrNull { isInstalled(it) }?.let { launch(it) } ?: search("music player")
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
