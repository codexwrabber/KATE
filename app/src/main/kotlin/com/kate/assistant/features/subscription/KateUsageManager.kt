package com.kate.assistant.features.subscription

import android.util.Log
import com.kate.assistant.data.preferences.KatePreferences
import kotlinx.coroutines.flow.first

/**
 * Enforces request limits per tier.
 *
 * Free tier:  20 online AI requests per day. Offline VOSK is unlimited.
 * Pro tier:   500 online requests per day (generous but prevents abuse).
 *
 * Limits reset at midnight device time. The device UUID is assigned on first
 * install — reinstalling or installing on a new device gives a fresh counter.
 */
class KateUsageManager(private val prefs: KatePreferences) {

    companion object {
        private const val TAG             = "KateUsageManager"
        const val FREE_DAILY_LIMIT        = 20
        const val PRO_DAILY_LIMIT         = 500
    }

    /**
     * Returns true if the user may make an online request right now.
     * Call BEFORE hitting any cloud API.
     */
    suspend fun canMakeOnlineRequest(): Boolean {
        val subscribed = prefs.isSubscribedOnce()
        val limit      = if (subscribed) PRO_DAILY_LIMIT else FREE_DAILY_LIMIT
        val count      = prefs.getDailyCountOnce()
        val allowed    = count < limit
        Log.d(TAG, "Usage: $count/$limit (subscribed=$subscribed) → allowed=$allowed")
        return allowed
    }

    /**
     * Record one online request. Call AFTER a successful cloud API call.
     * Returns the new count.
     */
    suspend fun recordRequest(): Int = prefs.incrementDailyRequests()

    /**
     * Human-readable message when limit is hit.
     * Kate speaks this to the user.
     */
    suspend fun limitMessage(): String {
        val subscribed = prefs.isSubscribedOnce()
        return if (subscribed)
            "You've reached the daily limit. It resets at midnight."
        else
            "You've used all ${FREE_DAILY_LIMIT} free daily requests. " +
            "Upgrade to Kate Pro for more, or I'll continue in offline mode."
    }

    suspend fun getRemainingRequests(): Int {
        val subscribed = prefs.isSubscribedOnce()
        val limit      = if (subscribed) PRO_DAILY_LIMIT else FREE_DAILY_LIMIT
        val count      = prefs.getDailyCountOnce()
        return (limit - count).coerceAtLeast(0)
    }
}
