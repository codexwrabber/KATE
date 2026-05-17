package com.kate.assistant.features.ai

import android.util.Log
import com.kate.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Claude AI response engine.
 *
 * Model selection:
 *  - Short conversational queries → claude-haiku-4-5  (fast, cheap ~$0.80/M tokens)
 *  - Complex tasks / long context → claude-sonnet-4-6 (smarter, $3/M tokens)
 *
 * The caller passes a conversation history so Kate has multi-turn memory
 * within a session. History is trimmed to the last 10 turns to keep costs low.
 */
class ClaudeAI {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG      = "ClaudeAI"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val VERSION  = "2023-06-01"

        private const val HAIKU_MAX_INPUT_WORDS = 80

        // Correct Anthropic API model strings as of 2026
        // Using the versioned names to avoid "model not found" 404s
        private const val MODEL_HAIKU  = "claude-haiku-4-5-20251001"
        private const val MODEL_SONNET = "claude-sonnet-4-5"

        // Kate's system prompt — defines her personality
        private fun systemPrompt(userName: String) = """
            You are Kate, an intelligent personal AI assistant built by Dewrabber's Tech Institute (D.T.I).
            You are speaking aloud via text-to-speech, so:
            - Keep responses conversational and concise — 1 to 3 sentences for most replies.
            - Never use bullet points, markdown, or asterisks. Speak naturally.
            - Address the user as $userName.
            - Be warm, direct, and helpful. You have a calm, confident personality.
            - You can control the user's Android phone, set reminders, search the web, and more.
            - If you don't know something, say so honestly rather than guessing.
        """.trimIndent()
    }

    /**
     * Get a response from Claude.
     *
     * @param userText  The user's transcribed speech
     * @param userName  For personalised responses
     * @param history   List of prior (role, content) pairs — kept to last 10
     * @return          Claude's response text, or null on failure
     */
    suspend fun respond(
        userText: String,
        userName: String,
        history: List<Pair<String, String>> = emptyList()
    ): String? = withContext(Dispatchers.IO) {
        if (BuildConfig.CLAUDE_API_KEY.isBlank()) {
            android.util.Log.e("ClaudeAI",
                "CLAUDE_API_KEY is empty — key not injected by CI. " +
                "Add it to GitHub repo secrets as CLAUDE_API_KEY.")
            return@withContext null
        }
        try {
            val model = if (userText.split(" ").size <= HAIKU_MAX_INPUT_WORDS)
                MODEL_HAIKU else MODEL_SONNET

            // Build message history (trim to last 10 turns to manage cost)
            val messages = JSONArray()
            history.takeLast(10).forEach { (role, content) ->
                messages.put(JSONObject().apply {
                    put("role",    role)
                    put("content", content)
                })
            }
            // Append current user turn
            messages.put(JSONObject().apply {
                put("role",    "user")
                put("content", userText)
            })

            val body = JSONObject().apply {
                put("model",      model)
                put("max_tokens", 300)
                put("system",     systemPrompt(userName))
                put("messages",   messages)
            }

            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("x-api-key",         BuildConfig.CLAUDE_API_KEY)
                .addHeader("anthropic-version",  VERSION)
                .addHeader("content-type",       "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful) {
                Log.e(TAG, "API ${response.code} using $model: $responseBody")
                // Common failures:
                // 404 = wrong model name
                // 401 = bad API key
                // 529 = overloaded
                return@withContext null
            }

            val json    = JSONObject(responseBody)
            val content = json.getJSONArray("content")
            val text    = content.getJSONObject(0).optString("text", "").trim()

            Log.d(TAG, "[$model] → \"$text\"")
            text

        } catch (e: Exception) {
            Log.e(TAG, "Claude call failed: ${e.message}")
            null
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
    }
}
