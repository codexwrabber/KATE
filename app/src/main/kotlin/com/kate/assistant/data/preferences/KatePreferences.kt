package com.kate.assistant.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kate_prefs")

class KatePreferences(private val context: Context) {

    companion object {
        val ONBOARDING_COMPLETE  = booleanPreferencesKey("onboarding_complete")
        val USER_NAME            = stringPreferencesKey("user_name")
        val DEVICE_ID            = stringPreferencesKey("device_id")
        val IS_SUBSCRIBED        = booleanPreferencesKey("is_subscribed")
        val SUB_EXPIRY_MS        = longPreferencesKey("sub_expiry_ms")
        val DAILY_REQUEST_COUNT  = intPreferencesKey("daily_request_count")
        val LAST_REQUEST_DATE    = stringPreferencesKey("last_request_date")
        val WAKE_WORD_ENROLLED   = booleanPreferencesKey("wake_word_enrolled")
        val PREFERRED_VOICE      = stringPreferencesKey("preferred_voice")
        val PRIVACY_ACCEPTED     = booleanPreferencesKey("privacy_accepted")
    }

    val onboardingComplete: Flow<Boolean> =
        context.dataStore.data.map { it[ONBOARDING_COMPLETE] ?: false }

    val userName: Flow<String> =
        context.dataStore.data.map { it[USER_NAME] ?: "" }

    val deviceId: Flow<String> =
        context.dataStore.data.map { it[DEVICE_ID] ?: "" }

    val isSubscribed: Flow<Boolean> =
        context.dataStore.data.map {
            val sub    = it[IS_SUBSCRIBED] ?: false
            val expiry = it[SUB_EXPIRY_MS] ?: 0L
            sub && (expiry == 0L || expiry > System.currentTimeMillis())
        }

    val dailyRequestCount: Flow<Int> =
        context.dataStore.data.map { it[DAILY_REQUEST_COUNT] ?: 0 }

    val privacyAccepted: Flow<Boolean> =
        context.dataStore.data.map { it[PRIVACY_ACCEPTED] ?: false }

    suspend fun setOnboardingComplete(value: Boolean) {
        context.dataStore.edit { it[ONBOARDING_COMPLETE] = value }
    }

    suspend fun setUserName(name: String) {
        context.dataStore.edit { it[USER_NAME] = name.trim() }
    }

    suspend fun setPrivacyAccepted(value: Boolean) {
        context.dataStore.edit { it[PRIVACY_ACCEPTED] = value }
    }

    suspend fun setSubscribed(active: Boolean, expiryMs: Long = 0L) {
        context.dataStore.edit {
            it[IS_SUBSCRIBED] = active
            it[SUB_EXPIRY_MS] = expiryMs
        }
    }

    suspend fun incrementDailyRequests(): Int {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        var newCount = 0
        context.dataStore.edit { prefs ->
            val lastDate = prefs[LAST_REQUEST_DATE] ?: ""
            val current  = if (lastDate == today) prefs[DAILY_REQUEST_COUNT] ?: 0 else 0
            newCount     = current + 1
            prefs[DAILY_REQUEST_COUNT] = newCount
            prefs[LAST_REQUEST_DATE]   = today
        }
        return newCount
    }

    suspend fun ensureDeviceId(): String {
        val existing = context.dataStore.data.first()[DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { it[DEVICE_ID] = newId }
        return newId
    }

    suspend fun getUserNameOnce(): String =
        context.dataStore.data.first()[USER_NAME] ?: ""

    suspend fun isSubscribedOnce(): Boolean =
        isSubscribed.first()

    suspend fun getDailyCountOnce(): Int =
        context.dataStore.data.first()[DAILY_REQUEST_COUNT] ?: 0
}
