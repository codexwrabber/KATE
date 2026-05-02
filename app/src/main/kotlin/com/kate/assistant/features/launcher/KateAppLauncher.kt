package com.kate.assistant.features.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

class KateAppLauncher(private val context: Context) {
    private val pm = context.packageManager

    // Cache installed apps at startup
    private val installedApps: List<Pair<String, String>> by lazy {
        pm.getInstalledApplications(PackageManager.GET_META_DATA).map {
            Pair(
                pm.getApplicationLabel(it).toString().lowercase(),
                it.packageName
            )
        }
    }

    fun launchByVoiceCommand(command: String) {
        val cmd = command.lowercase().trim()

        // Direct package known apps
        val known = mapOf(
            "whatsapp"      to "com.whatsapp",
            "youtube"       to "com.google.android.youtube",
            "spotify"       to "com.spotify.music",
            "instagram"     to "com.instagram.android",
            "facebook"      to "com.facebook.katana",
            "twitter"       to "com.twitter.android",
            "tiktok"        to "com.zhiliaoapp.musically",
            "chrome"        to "com.android.chrome",
            "camera"        to "com.android.camera2",
            "gallery"       to "com.android.gallery3d",
            "settings"      to "com.android.settings",
            "calculator"    to "com.android.calculator2",
            "calendar"      to "com.android.calendar",
            "clock"         to "com.android.deskclock",
            "maps"          to "com.google.android.apps.maps",
            "gmail"         to "com.google.android.gm",
            "files"         to "com.google.android.apps.nbu.files",
            "play store"    to "com.android.vending",
            "phone"         to "com.android.dialer",
            "messages"      to "com.google.android.apps.messaging",
            "contacts"      to "com.android.contacts",
            "audiomack"     to "com.audiomack.audiomack",
            "netflix"       to "com.netflix.mediaclient",
            "telegram"      to "org.telegram.messenger",
            "snapchat"      to "com.snapchat.android",
            "claude"        to "com.anthropic.claude",
            "chatgpt"       to "com.openai.chatgpt",
        )

        // Check known apps first
        for ((name, pkg) in known) {
            if (cmd.contains(name)) {
                Log.d("KateLauncher", "Known app: $name → $pkg")
                launch(pkg)
                return
            }
        }

        // Fuzzy match against installed apps
        val match = installedApps.firstOrNull { (label, _) ->
            cmd.contains(label) || label.contains(cmd)
        }
        if (match != null) {
            Log.d("KateLauncher", "Fuzzy match: ${match.first} → ${match.second}")
            launch(match.second)
            return
        }

        // Search Play Store as last resort
        Log.d("KateLauncher", "No match for: $cmd")
        search(cmd)
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
            "com.spotify.music",
            "com.audiomack.audiomack",
            "com.google.android.youtube.music",
            "com.soundcloud.android",
            "com.apple.android.music"
        )
        music.firstOrNull { isInstalled(it) }
            ?.let { launch(it) }
            ?: search("music player")
    }

    private fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun launch(packageName: String) {
        runCatching {
            pm.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { context.startActivity(it) }
                ?: run { Log.e("KateLauncher", "No launch intent for $packageName") }
        }
    }

    private fun isInstalled(pkg: String) =
        runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}

enum class SearchEngine { GOOGLE, YOUTUBE, MAPS }
