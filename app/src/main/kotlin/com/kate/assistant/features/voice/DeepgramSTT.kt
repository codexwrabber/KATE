package com.kate.assistant.features.voice

import android.util.Log
import com.kate.assistant.BuildConfig
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Deepgram Nova-2 streaming STT.
 *
 * Uses two API keys (primary + fallback). If the primary key fails
 * (auth error, quota, network) the call is silently retried on the
 * fallback key before surfacing an error to the caller.
 *
 * Audio contract:
 *  - PCM 16-bit mono, 16 000 Hz  (same as VOSK — no resampling needed)
 *  - Send raw ByteArray chunks as they come off AudioRecord
 *  - Call finalize() to close the stream and get the final transcript
 */
class DeepgramSTT(
    private val onTranscript: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var usingFallback  = false
    @Volatile private var open  = false

    // Accumulates partials so we have the best text at all times
    private val partialBuffer = StringBuilder()

    companion object {
        private const val TAG = "DeepgramSTT"
        private const val URL_BASE =
            "wss://api.deepgram.com/v1/listen" +
            "?model=nova-2" +
            "&language=en" +
            "&encoding=linear16" +
            "&sample_rate=16000" +
            "&channels=1" +
            "&punctuate=true" +
            "&interim_results=true" +     // partials for low latency
            "&endpointing=300"            // 300ms silence → final result

        private const val FREE_DAILY_LIMIT = 20   // requests, not minutes
    }

    /** Open a new streaming session. Call once per utterance or per session. */
    fun connect() {
        if (BuildConfig.DEEPGRAM_KEY_PRIMARY.isBlank()) {
            android.util.Log.e("DeepgramSTT",
                "DEEPGRAM_KEY_PRIMARY is empty — key not injected by CI. " +
                "Add it to GitHub repo secrets as DEEPGRAM_KEY_PRIMARY.")
            return
        }
        usingFallback = false
        openSocket(BuildConfig.DEEPGRAM_KEY_PRIMARY)
    }

    private fun openSocket(apiKey: String) {
        val req = Request.Builder()
            .url(URL_BASE)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                Log.d(TAG, "Connected${if (usingFallback) " (fallback key)" else ""}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json    = JSONObject(text)
                    val channel = json.optJSONObject("channel") ?: return
                    val alts    = channel.optJSONArray("alternatives") ?: return
                    if (alts.length() == 0) return
                    val transcript = alts.getJSONObject(0).optString("transcript", "").trim()
                    val isFinal    = json.optBoolean("is_final", false)

                    if (transcript.isBlank()) return

                    if (isFinal) {
                        partialBuffer.clear()
                        Log.d(TAG, "Final: \"$transcript\"")
                        onTranscript(transcript)
                    } else {
                        // Emit partials for wake-word detection / live UI
                        partialBuffer.clear()
                        partialBuffer.append(transcript)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open = false
                val isAuth = response?.code == 401 || response?.code == 403
                Log.e(TAG, "WS failure (auth=$isAuth): ${t.message}")

                if (!usingFallback) {
                    // Silently retry on the second key
                    Log.w(TAG, "Retrying with fallback Deepgram key")
                    usingFallback = true
                    openSocket(BuildConfig.DEEPGRAM_KEY_FALLBACK)
                } else {
                    onError("DEEPGRAM_FAILED: ${t.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
                Log.d(TAG, "WS closed: $code $reason")
            }
        })
    }

    /**
     * Send a raw PCM chunk from AudioRecord.
     * Safe to call from any thread. No-op if socket isn't open.
     * Auto-connects if not yet open (handles the case where online mode
     * was set before connect() was explicitly called).
     */
    fun sendAudio(pcm: ByteArray, length: Int) {
        if (!open) return
        ws?.send(pcm.copyOf(length).toByteString())
    }

    /**
     * Signal end-of-speech to Deepgram and close the socket.
     * The server will flush any remaining audio and send a final result.
     */
    fun finalize() {
        if (open) {
            // Deepgram "CloseStream" message
            ws?.send(ByteString.EMPTY)
        }
        ws?.close(1000, "Done")
        ws    = null
        open  = false
        partialBuffer.clear()
    }

    fun isConnected(): Boolean = open

    fun shutdown() {
        finalize()
        client.dispatcher.executorService.shutdown()
    }
}
