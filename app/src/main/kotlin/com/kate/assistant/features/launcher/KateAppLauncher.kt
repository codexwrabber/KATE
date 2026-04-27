package com.kate.assistant.features.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

class KateAppLauncher(private val context: Context) {
    private val pm = context.packageManager

    fun launchByVoiceCommand(command: String) {
        val cmd = command.lowercase()
        when {
            cmd.contains("spotify")  -> launch("com.spotify.music")
            cmd.contains("youtube")  -> search(cmd.replace("youtube","").trim(), SearchEngine.YOUTUBE)
            cmd.contains("maps") ||
            cmd.contains("navigate") -> search(cmd.replace("maps","").replace("navigate","").trim(), SearchEngine.MAPS)
            cmd.contains("search for") ||
            cmd.contains("google")   -> search(cmd.replace("search for","").replace("google","").trim())
            cmd.contains("open")     -> findAndLaunch(cmd.substringAfter("open").trim())
            else                     -> findAndLaunch(cmd)
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
        val candidates = listOf(
            "com.spotify.music",
            "com.google.android.youtube.music",
            "com.soundcloud.android",
            "com.apple.android.music",
            "com.audiomack.audiomack"
        )
        candidates.firstOrNull { isInstalled(it) }
            ?.let { launch(it) }
            ?: search("music player", SearchEngine.GOOGLE)
    }

    private fun openUrl(url: String) {
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .let { context.startActivity(it) }
    }

    private fun findAndLaunch(name: String) {
        if (name.isBlank()) return
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .firstOrNull {
                pm.getApplicationLabel(it).toString().contains(name, ignoreCase = true)
            }
            ?.let { launch(it.packageName) }
    }

    private fun launch(packageName: String) {
        pm.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { context.startActivity(it) }
    }

    private fun isInstalled(pkg: String) =
        runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}

enum class SearchEngine { GOOGLE, YOUTUBE, MAPS }
