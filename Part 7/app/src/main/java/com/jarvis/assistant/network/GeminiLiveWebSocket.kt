package com.jarvis.assistant.network

import android.util.Base64
import android.util.Log
import com.jarvis.assistant.data.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.LinkedHashSet

class GeminiLiveWebSocket(
    private val apiKey: String,
    private val model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val listener: Listener,
    private val apiKeyProvider: () -> String = { apiKey },
    private val onApiKeyRateLimited: (String) -> Unit = {},
    private val onApiKeyInvalid: (String) -> Unit = {},
    private val onApiKeySuccess: (String) -> Unit = {},
    /** When true, VoiceSessionManager is the sole reconnect authority. */
    private val externalReconnectOwner: Boolean = false,
    private val socketFactory: WebSocket.Factory = sharedClient
) {
    @Volatile private var activeApiKey: String = apiKey
    @Volatile private var pendingApiKey: String? = null
    private var reconnectAttempts: Int = 0
    interface Listener {
        fun onConnectionStateChanged(status: String)
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onAssistantTextReceived(textChunk: String)
        fun onUserTextReceived(textChunk: String)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onError(message: String, isFatal: Boolean = false, errorCode: String? = null)
        fun onToolCall(callId: String, name: String, args: JSONObject)
    }

    companion object {
        private const val TAG = "GeminiLiveWebSocket"
        private const val MAX_RECONNECT_ATTEMPTS = 8
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        // Shared OkHttpClient across all sessions — reuses connection pool, DNS cache, and TLS sessions
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val connectionEpoch = AtomicLong(0L)

    // The owner selects a ListModels-discovered Bidi model. Never rewrite its version here.
    private var activeModel: String = model
    private val resumption = LiveSessionResumption()
    @Volatile private var toolContracts = LiveToolContracts(emptyList())

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)
    private var coroutineScope: CoroutineScope? = null

    // A repeated server toolCall frame must never execute a phone action twice.
    // Keep a bounded per-connection set so memory remains stable for long sessions.
    private val processedToolCallIds = LinkedHashSet<String>()
    private val toolCallIdLock = Any()
    private val maxRememberedToolCallIds = 512

    private fun shouldDispatchToolCall(callId: String): Boolean {
        if (callId.isBlank()) {
            Log.w(TAG, "Ignoring tool call without an id")
            return false
        }
        synchronized(toolCallIdLock) {
            if (!processedToolCallIds.add(callId)) {
                Log.w(TAG, "Ignoring duplicate tool call id=$callId")
                return false
            }
            while (processedToolCallIds.size > maxRememberedToolCallIds) {
                val oldest = processedToolCallIds.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                } else break
            }
            return true
        }
    }

    private fun resetToolCallDeduplication() {
        synchronized(toolCallIdLock) { processedToolCallIds.clear() }
    }

    private var sessionRenewalJob: Job? = null
    private var reconnectJob: Job? = null
    var promptProvider: (() -> String)? = null

    fun isSessionReady(): Boolean = isConnected.get() && isSetupComplete.get()

    fun connect(scope: CoroutineScope) {
        coroutineScope = scope
        isManuallyStopped.set(false)
        reconnectAttempts = 0
        val queuedKey = pendingApiKey
        pendingApiKey = null
        val selectedKey = runCatching { apiKeyProvider().trim() }.getOrDefault("")
        activeApiKey = queuedKey?.takeIf { it.isNotBlank() } ?: selectedKey.ifBlank { apiKey.trim() }
        initiateConnection()
    }

    private fun initiateConnection() {
        val selectedApiKey = pendingApiKey?.also { pendingApiKey = null }
            ?: activeApiKey.trim().ifBlank { runCatching { apiKeyProvider().trim() }.getOrDefault("") }
        if (selectedApiKey.isBlank()) {
            Log.e(TAG, "Gemini API Key is empty. Aborting connection.")
            listener.onError("Gemini API Key is missing. Please configure it in Settings.", isFatal = true, errorCode = "API_KEY_MISSING")
            listener.onConnectionStateChanged("OFFLINE")
            return
        }
        activeApiKey = selectedApiKey
        val epoch = connectionEpoch.incrementAndGet()

        listener.onConnectionStateChanged("CONNECTING...")

        try {
            // Keep the API key out of the URL: URLs may be retained in proxy/server
            // access logs. Gemini accepts the key through the x-goog-api-key header.
            val request = Request.Builder()
                .url(GeminiConstants.WS_BASE_URL)
                .header("x-goog-api-key", activeApiKey)
                .build()

            webSocket = socketFactory.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (epoch != connectionEpoch.get()) { webSocket.close(1000, "Superseded connection"); return }
                    Log.d(TAG, "WebSocket connected to Gemini Live. Sending BidiSetup...")
                    // Retain bounded tool IDs across a resumed connection; replay must not repeat side effects.
                    isConnected.set(true)
                    isSetupComplete.set(false)
                    listener.onConnectionStateChanged("CONFIGURING...")
                    // Send setup message FIRST before enabling audio streaming
                    sendSetupMessage(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (epoch != connectionEpoch.get()) return
                    handleIncomingMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (epoch != connectionEpoch.get()) return
                    handleIncomingMessage(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: code=$code")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (epoch != connectionEpoch.get()) return
                    Log.d(TAG, "WebSocket closed: code=$code")
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()

                    if (reason.contains("API_ACCESS_DENIED", ignoreCase = true) ||
                        reason.contains("PERMISSION_DENIED", ignoreCase = true)) {
                        listener.onConnectionStateChanged("OFFLINE")
                        return
                    }
                    if (reason.contains("SETUP_REJECTED", ignoreCase = true)) {
                        listener.onConnectionStateChanged("OFFLINE")
                        return
                    }

                    val isApiKeyError = reason.contains("API_KEY_INVALID", ignoreCase = true) ||
                        reason.contains("API key not valid", ignoreCase = true) ||
                        reason.contains("invalid API key", ignoreCase = true)
                    if (isApiKeyError) {
                        runCatching { onApiKeyInvalid(activeApiKey) }
                        Log.e(TAG, "Gemini explicitly rejected the active API credential (code=$code)")
                        listener.onError("Google explicitly rejected this Gemini API key. Check the key and project restrictions.", isFatal = true, errorCode = "API_KEY_INVALID")
                        listener.onConnectionStateChanged("ERROR • INVALID KEY")
                        if (!isManuallyStopped.get()) requestReconnect(rotateKey = true)
                        return
                    }

                    val isQuotaError = reason.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                        reason.contains("quota", ignoreCase = true)
                    if (isQuotaError) {
                        runCatching { onApiKeyRateLimited(activeApiKey) }
                        Log.w(TAG, "Gemini rate-limited the active key (code=$code). Rotating/retrying with bounded backoff.")
                        listener.onError("Gemini rate limit or quota was reached. JARVIS will retry using an available key.", isFatal = false, errorCode = "QUOTA_EXCEEDED")
                        listener.onConnectionStateChanged("COOLDOWN • RETRYING")
                        requestReconnect(minDelayMs = 5_000L, rotateKey = true)
                        return
                    }

                    if (code == 1008) {
                        listener.onError("Gemini setup was rejected. Check the selected model and API access.", isFatal = true, errorCode = "SETUP_REJECTED")
                        listener.onConnectionStateChanged("OFFLINE")
                    } else if (!isManuallyStopped.get()) {
                        listener.onConnectionStateChanged("RECONNECTING...")
                        requestReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (epoch != connectionEpoch.get()) { response?.close(); return }
                    val code = response?.code ?: 0
                    val retryAfterMs = response?.header("Retry-After")?.toLongOrNull()?.let { (it * 1_000L).coerceAtMost(MAX_RECONNECT_DELAY_MS) }
                    val responseBody = try { response?.body?.string() } catch (_: Exception) { null }
                    response?.close()
                    val errorMsg = t.message ?: "WebSocket connection failure"
                    Log.e(TAG, "WebSocket failure: httpStatus=$code, errorType=${t.javaClass.simpleName}")
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()

                    val bodyExplicitlyRejectsKey =
                        responseBody?.contains("API_KEY_INVALID", ignoreCase = true) == true ||
                        responseBody?.contains("API key not valid", ignoreCase = true) == true ||
                        responseBody?.contains("invalid API key", ignoreCase = true) == true
                    val isApiKeyError = code == 401 || bodyExplicitlyRejectsKey ||
                        errorMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                        errorMsg.contains("API key not valid", ignoreCase = true)

                    val isQuotaError = code == 429 ||
                        responseBody?.contains("RESOURCE_EXHAUSTED", ignoreCase = true) == true ||
                        errorMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                        errorMsg.contains("quota", ignoreCase = true)

                    if (isApiKeyError) {
                        runCatching { onApiKeyInvalid(activeApiKey) }
                        Log.e(TAG, "Gemini explicitly rejected the active API credential (HTTP $code)")
                        listener.onError("Google explicitly rejected this Gemini API key. Check the key and project restrictions.", isFatal = true, errorCode = "API_KEY_INVALID")
                        listener.onConnectionStateChanged("ERROR • INVALID KEY")
                        if (!isManuallyStopped.get()) requestReconnect(rotateKey = true)
                        return
                    }

                    if (code == 403 || responseBody?.contains("PERMISSION_DENIED", ignoreCase = true) == true) {
                        listener.onError("Gemini denied access to this API key or project (HTTP 403). This does not prove the key is invalid; check API enablement, project restrictions and billing.", isFatal = true, errorCode = "API_ACCESS_DENIED")
                        listener.onConnectionStateChanged("ERROR • ACCESS DENIED")
                        return
                    }

                    if (isQuotaError) {
                        runCatching { onApiKeyRateLimited(activeApiKey) }
                        Log.w(TAG, "Gemini quota/rate limit hit (HTTP $code); applying cooldown and bounded retry.")
                        listener.onError("Gemini rate limit or quota reached. JARVIS will retry using an available key.", isFatal = false, errorCode = "QUOTA_EXCEEDED")
                        listener.onConnectionStateChanged("COOLDOWN • RETRYING")
                        requestReconnect(minDelayMs = retryAfterMs ?: 5_000L, rotateKey = true)
                        return
                    }

                    if (code == 400) {
                        listener.onError("Gemini rejected the session request (HTTP 400). Check the selected Live model and request configuration.", isFatal = true, errorCode = "SETUP_REJECTED")
                        listener.onConnectionStateChanged("ERROR • SETUP REJECTED")
                        return
                    }

                    if (!isManuallyStopped.get()) {
                        listener.onError("Connection lost. Retrying with bounded backoff...", isFatal = false, errorCode = "CONNECTION_ERROR")
                        requestReconnect(minDelayMs = retryAfterMs ?: 0L)
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating WebSocket connection: ${e.javaClass.simpleName}")
            listener.onError("Connection could not be started. Check network access and settings.", isFatal = true, errorCode = "CONNECTION_FAILED")
            listener.onConnectionStateChanged("OFFLINE")
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val effectivePrompt = systemPrompt
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", activeModel)
                put("session_resumption", JSONObject().apply {
                    resumption.forAccount(activeApiKey)?.let { put("handle", it) }
                })
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", effectivePrompt)))
                })
                put("input_audio_transcription", JSONObject())
                put("output_audio_transcription", JSONObject())
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().put("AUDIO"))
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voiceName)
                            })
                        })
                    })
                    put("temperature", 0.6)
                })
                put("tools", JSONArray().put(JSONObject().apply {
                    put("functionDeclarations", JSONArray().apply {
                        listOf(
                            Triple("background_search", "Search the web without opening a browser. Requires the user's Aura Background Web Search toggle. Return retrieved sources.", "query"),
                            Triple("generate_image", "Generate an image with Gemini and save it to the phone gallery. Requires Gemini Images enabled. Never claim saved until tool succeeds.", "prompt"),
                            Triple("delegate_ui_task", "Delegate a long user-requested UI task to a separate bounded mission while continuing conversation. Requires Background Agent enabled. Accepted is not completed.", "goal")
                        ).forEach { (toolName, description, parameter) ->
                            put(JSONObject().put("name", toolName).put("description", description).put("parameters", JSONObject()
                                .put("type", "OBJECT").put("properties", JSONObject().put(parameter, JSONObject().put("type", "STRING")))
                                .put("required", JSONArray().put(parameter))))
                        }
                        put(JSONObject().put("name", "share_generated_image").put("description", "Only when the user asks, open WhatsApp's contact picker for the last generated image. User confirms Send; opening is not delivery.")
                            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))

                        // 1. open_app
                        put(JSONObject().apply {
                            put("name", "open_app")
                            put("description", "Opens an app installed on the user's phone, e.g. YouTube, WhatsApp, Camera, Settings. If multiple instances exist, app_number specifies 1 or 2.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("app_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The name of the app to open (e.g. \"YouTube\", \"WhatsApp\", \"Instagram\").")
                                    })
                                    put("app_number", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based index (1 or 2) when opening a dual/cloned app instance.")
                                    })
                                })
                                put("required", JSONArray().put("app_name"))
                            })
                        })

                        // 2. open_website
                        put(JSONObject().apply {
                            put("name", "open_website")
                            put("description", "Opens one or more websites/URLs in Google Chrome in separate tabs. Use whenever the user asks to open a website, visit a URL, or open multiple websites/URLs in different tabs (e.g. \"open github.com and names21st.dev in different tabs\", \"open website google.com\", \"open vercel.com\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("urls", JSONObject().apply {
                                        put("type", "ARRAY")
                                        put("description", "Array of website URLs or domain names to open, each in its own tab (e.g. [\"github.com\", \"names21st.dev\"]).")
                                        put("items", JSONObject().apply { put("type", "STRING") })
                                    })
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional string containing the website URL(s) or query.")
                                    })
                                })
                            })
                        })

                        // 3. search_in_chrome
                        put(JSONObject().apply {
                            put("name", "search_in_chrome")
                            put("description", "Opens Google Chrome and searches for a topic/question, or opens a website URL in a tab (e.g. \"search xyz in Chrome\", \"google xyz\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Query or topic to search in Google Chrome, or website URL.")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 4. search_and_play_youtube
                        put(JSONObject().apply {
                            put("name", "search_and_play_youtube")
                            put("description", "Searches YouTube and IMMEDIATELY OPENS AND PLAYS the requested video or song. Use whenever the user directly commands you to PLAY a video or song (e.g. \"play Tum Hi Ho\", \"play Believer on YouTube\", \"play XYZ song or youtube\", \"YouTube pe Arijit Singh chalao\", \"Jarvis video play karo\"). CRITICAL RULE: If the user says 'play', ALWAYS use search_and_play_youtube, NEVER use search_youtube. STRICT PROHIBITION: NEVER use this tool when the user is casually conversing or humming without an explicit play command.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "What video/song to search for and immediately play, e.g. \"Tum Hi Ho Arijit Singh\".")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 5. search_youtube
                        put(JSONObject().apply {
                            put("name", "search_youtube")
                            put("description", "Opens YouTube and searches for a topic/query, displaying the SEARCH RESULTS SCREEN ONLY WITHOUT auto-playing or tapping any video. Use ONLY when the user explicitly asks to SEARCH on YouTube without asking to play (e.g. \"search this on YouTube\", \"YouTube pe search karo xyz\", \"search Python tutorial on YouTube\"). NEVER use this tool when the user says to play a video or song.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The query/topic to search on YouTube, e.g. \"Python tutorial\", \"latest news\".")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 6. youtube_accessibility_action
                        put(JSONObject().apply {
                            put("name", "youtube_accessibility_action")
                            put("description", "Performs an in-app YouTube action using accessibility: skipping an ad ('skip_ad'), liking the video ('like'), subscribing to the channel ('subscribe'), opening the channel ('open_channel'), or toggling fullscreen ('fullscreen').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("enum", JSONArray().apply {
                                            put("skip_ad"); put("like"); put("subscribe"); put("open_channel"); put("fullscreen"); put("click_first")
                                        })
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 7. media_playback_control
                        put(JSONObject().apply {
                            put("name", "media_playback_control")
                            put("description", "Controls whatever video/audio is currently playing (e.g. YouTube): play, pause, next, previous, stop.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("enum", JSONArray().apply {
                                            put("play"); put("pause"); put("next"); put("previous"); put("stop")
                                        })
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 8. send_whatsapp_message
                        put(JSONObject().apply {
                            put("name", "send_whatsapp_message")
                            put("description", "Opens WhatsApp and queues sending a text to a named contact (e.g. \"message Rahul that I will be late\"). The tool response distinguishes opening/queuing from a verified send; inspect the current chat before claiming the message was sent. If 2 WhatsApp apps exist and app_number is not specified, ask the user which one.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("recipient_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the contact as spoken by the user (e.g. \"Rahul\", \"Priya\", \"Dad\").")
                                    })
                                    put("message", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The text message content to send to the recipient.")
                                    })
                                    put("app_number", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based index (1 or 2) if dual WhatsApp is installed.")
                                    })
                                    put("confirmed", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True if user already confirmed sending.")
                                    })
                                })
                                put("required", JSONArray().apply { put("recipient_name"); put("message") })
                            })
                        })

                        // 9. whatsapp_call
                        put(JSONObject().apply {
                            put("name", "whatsapp_call")
                            put("description", "Initiates a WhatsApp voice call or WhatsApp video call to a contact (e.g. \"WhatsApp call Mom\", \"WhatsApp video call Rahul\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("recipient_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the contact to call as spoken by the user.")
                                    })
                                    put("call_type", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "\"voice\" for voice call or \"video\" for video call.")
                                    })
                                    put("app_number", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based index (1 or 2) if dual WhatsApp is installed.")
                                    })
                                    put("confirmed", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True if user already confirmed calling.")
                                    })
                                })
                                put("required", JSONArray().apply { put("recipient_name"); put("call_type") })
                            })
                        })

                        // 10. call_contact
                        put(JSONObject().apply {
                            put("name", "call_contact")
                            put("description", "Places a standard phone call to a saved contact by spoken name, e.g. \"call mom\", \"call Rahul\", \"call Gufran Khan 4\". If the user specifies an ordinal or index (e.g. 'call the 4th Gufran Khan', 'call Gufran Khan 4', 'chautha wala'), pass contact_name='Gufran Khan' and contact_index=4 (or include the digit in contact_name). If multiple matches were returned and the user chooses an option, pass contact_index.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("contact_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The contact's name as spoken by the user, e.g. \"Gufran Khan\", \"Papa 2\", \"Home 1\".")
                                    })
                                    put("contact_index", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based index (e.g. 1, 2, 3, 4) if the user requested a specific contact number or choice.")
                                    })
                                })
                                put("required", JSONArray().put("contact_name"))
                            })
                        })

                        // 11. set_volume
                        put(JSONObject().apply {
                            put("name", "set_volume")
                            put("description", "Adjusts media volume on device. Supports relative commands ('increase', 'decrease') or exact level ('set').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Action to perform: 'increase', 'decrease', or 'set'.")
                                    })
                                    put("percentage", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Target percentage level from 0 to 100 if specified.")
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 12. set_brightness
                        put(JSONObject().apply {
                            put("name", "set_brightness")
                            put("description", "Adjusts screen brightness on device. Supports relative commands ('increase', 'decrease') or exact level ('set').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Action to perform: 'increase', 'decrease', or 'set'.")
                                    })
                                    put("percentage", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Target percentage level from 0 to 100 if specified.")
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 13. builtin_chrome_search
                        put(JSONObject().apply {
                            put("name", "builtin_chrome_search")
                            put("description", "Performs invisible, background web research using JARVIS's built-in Chrome engine. Use whenever the user asks a question, topic, real-time factual query, news, weather, or live information that you don't know off-hand.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Search query or topic to look up on the web.")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 14. go_to_background
                        put(JSONObject().apply {
                            put("name", "go_to_background")
                            put("description", "Sends JARVIS to the background, minimizes the app to the home screen, and enters standby wake-word listening. Use immediately whenever user says 'go to background', 'go background', 'minimize', 'close', 'background me jao', 'go to home screen', 'go to sleep', or 'enter standby'.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 15. set_flashlight
                        put(JSONObject().apply {
                            put("name", "set_flashlight")
                            put("description", "Turns the phone's torch or flashlight on or off. ALWAYS call this tool unconditionally whenever the user commands to turn on, enable, turn off, or disable the torch/flashlight (e.g. \"turn on flash lite\", \"turn on flashlight\", \"turn off flashlight\", \"torch on\", \"torch off\", \"torch chalu karo\", \"torch band karo\"). NEVER claim it is already on or off without calling this tool.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("enabled", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to turn on the torch/flashlight, false to turn it off.")
                                    })
                                })
                                put("required", JSONArray().put("enabled"))
                            })
                        })

                        // 16. click_ui_element
                        put(JSONObject().apply {
                            put("name", "click_ui_element")
                            put("description", "Clicks or toggles a specific UI button, switch, link, icon, tab, or element. Prefer a unique current element_id or target_name. When Screen Share is active, normalized coordinates x/y (0-1000) may be used for a clearly visible unlabeled target; the tool checks the hit target and returns whether a screen change was verified. Never treat a dispatched gesture alone as proof of success.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("target_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Label, text, icon name, or description of the UI element to click (e.g., 'Generate', 'Arrow', 'Video', 'Search', 'Install').")
                                    })
                                    put("x", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized X coordinate (0-1000) of the visual center of the element on screen.")
                                    })
                                    put("y", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized Y coordinate (0-1000) of the visual center of the element on screen.")
                                    })
                                    put("element_id", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based element ID if known from current screen state summary.")
                                    })
                                    put("is_toggle", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True if flipping a toggle switch or checkbox.")
                                    })
                                    put("description", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Additional visual context.")
                                    })
                                })
                                put("required", JSONArray().put("target_name"))
                            })
                        })

                        // 17. type_text
                        put(JSONObject().apply {
                            put("name", "type_text")
                            put("description", "Types text into a grounded input field. The result is successful only when the text is read back from the target; a clipboard paste or dispatched gesture alone is not proof. For Google Flow, use the named prompt field or, only with a confirmed live Screen Share frame, current normalized x/y coordinates of the visible prompt box. Never report text entered unless success=true and verified=true.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("text", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The exact text query or prompt to type.")
                                    })
                                    put("x", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized X coordinate (0-1000) of the text box center on screen.")
                                    })
                                    put("y", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized Y coordinate (0-1000) of the text box center on screen.")
                                    })
                                    put("target_input_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional name or hint of the input field (e.g., 'What do you want to create', 'Search').")
                                    })
                                    put("submit", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "Whether to submit or press enter after typing.")
                                    })
                                })
                                put("required", JSONArray().put("text"))
                            })
                        })

                        // 18. scroll_screen
                        put(JSONObject().apply {
                            put("name", "scroll_screen")
                            put("description", "Scrolls the active screen in a given direction to reveal elements that are offscreen. Supports gentle line-by-line scrolling for inspecting photos and lists.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("direction", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Direction to scroll: 'down', 'up', 'left', or 'right'.")
                                    })
                                    put("distance", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional scroll distance: 'small' (gentle line-by-line scroll, moves 1-2 rows at a time with zero fling, mandatory for browsing photos and documents), 'medium' (half screen), or 'large' (full page). Defaults to 'small'.")
                                    })
                                })
                                put("required", JSONArray().put("direction"))
                            })
                        })

                        // 19. press_key
                        put(JSONObject().apply {
                            put("name", "press_key")
                            put("description", "Executes system-level navigation gestures: 'back', 'home', or 'recents'.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("key", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The system action: 'back', 'home', or 'recents'.")
                                    })
                                })
                                put("required", JSONArray().put("key"))
                            })
                        })

                        // 20. task_completed
                        put(JSONObject().apply {
                            put("name", "task_completed")
                            put("description", "Call this tool only after the user's requested task is visibly confirmed complete. Do not call it right after typing into a search bar; when searching for a chat or file, first open the matching result and verify the resulting screen.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("summary", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Brief summary of what was completed (e.g., 'Opened Bharat's chat on Telegram.').")
                                    })
                                })
                                put("required", JSONArray().put("summary"))
                            })
                        })

                        // 21. inspect_screen
                        put(JSONObject().apply {
                            put("name", "inspect_screen")
                            put("description", "Reads the foreground app, screen title, and accessible UI elements (text, buttons, tabs). This tool returns accessibility text, not a visual image. Only claim live visual access when Screen Share is confirmed active and a recent captured frame has been received. Call when you need current accessible labels or element IDs before interacting, or when the user asks about screen content. Do not use for normal conversation.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })


                    })
                }))
            })
        }
        val json = setup.toString()
        val declarations = setup.getJSONObject("setup").getJSONArray("tools").getJSONObject(0)
            .getJSONArray("functionDeclarations")
        toolContracts = LiveToolContracts((0 until declarations.length()).map {
            com.google.gson.JsonParser.parseString(declarations.getJSONObject(it).toString()).asJsonObject
        })
        Log.d(TAG, "Sending BidiSetup payload to Gemini Live with tools")
        ws.send(json)
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

            // Check if server returned an error payload
            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message", "") ?: json.optString("error", "Unknown error")
                val errCode = errObj?.optInt("code", 0) ?: 0
                val errStatus = errObj?.optString("status", "").orEmpty()
                Log.e(TAG, "Server returned error: code=$errCode, status=$errStatus")
                val isApiKey = errStatus.equals("API_KEY_INVALID", ignoreCase = true) ||
                    errMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                    errMsg.contains("API key not valid", ignoreCase = true) ||
                    errMsg.contains("invalid API key", ignoreCase = true) ||
                    errCode == 401
                val isQuota = errMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                    errStatus.equals("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                    errMsg.contains("quota", ignoreCase = true) ||
                    errCode == 429
                if (isApiKey) {
                    // Let onClosed perform key invalidation and the single bounded reconnect.
                    webSocket?.close(1008, "API_KEY_INVALID")
                } else if (isQuota) {
                    // Let onClosed apply the cooldown once and rotate to another available key.
                    webSocket?.close(1013, "RESOURCE_EXHAUSTED")
                } else if (errCode == 403 || errStatus.equals("PERMISSION_DENIED", ignoreCase = true)) {
                    listener.onError("Gemini denied access to this API key or project (HTTP 403). This does not prove the key is invalid; check API enablement, project restrictions and billing.", isFatal = true, errorCode = "API_ACCESS_DENIED")
                    listener.onConnectionStateChanged("ERROR • ACCESS DENIED")
                    webSocket?.close(1008, "API_ACCESS_DENIED")
                } else if (errCode == 400) {
                    listener.onError("Gemini rejected the request (HTTP 400). Check the selected model and request configuration.", isFatal = true, errorCode = "SETUP_REJECTED")
                    listener.onConnectionStateChanged("ERROR • SETUP REJECTED")
                    webSocket?.close(1008, "SETUP_REJECTED")
                } else {
                    listener.onError("Gemini returned an API error (HTTP $errCode).", isFatal = false)
                }
                return
            }

            json.optJSONObject("sessionResumptionUpdate")?.let { update ->
                resumption.update(update.optBoolean("resumable", false), update.optString("newHandle").takeIf { it.isNotBlank() })
            }

            // 0. Server setupComplete handshake acknowledgment
            if (json.has("setupComplete")) {
                Log.i(TAG, "Server acknowledged setup: setupComplete received. Session is now LIVE!")
                if (!isSetupComplete.getAndSet(true)) {
                    reconnectAttempts = 0
                    runCatching { onApiKeySuccess(activeApiKey) }
                    listener.onConnectionStateChanged("LIVE")
                    startSessionRenewal()
                }
                return
            }

            // Ensure session is marked ready if server sends content before explicit setupComplete
            if (!isSetupComplete.get() && (json.has("serverContent") || json.has("toolCall"))) {
                isSetupComplete.set(true)
                reconnectAttempts = 0
                runCatching { onApiKeySuccess(activeApiKey) }
                listener.onConnectionStateChanged("LIVE")
                startSessionRenewal()
            }

            // 1. Top-level toolCall
            val topToolCall = json.optJSONObject("toolCall")
            if (topToolCall != null) {
                val functionCalls = topToolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    for (i in 0 until functionCalls.length()) {
                        val call = functionCalls.getJSONObject(i)
                        val name = call.optString("name", "")
                        val id = call.optString("id", "")
                        val args = call.optJSONObject("args") ?: JSONObject()
                        if (name.isNotEmpty() && shouldDispatchToolCall(id)) {
                            // Never log tool arguments or returned data: these can contain
                            // private message text, contact details, or other user content.
                            Log.i(TAG, "Gemini invoked toolCall: $name (id=$id)")
                            dispatchValidatedToolCall(id, name, args)
                        }
                    }
                }
                return
            }

            // 2. serverContent
            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                val scToolCall = serverContent.optJSONObject("toolCall")
                if (scToolCall != null) {
                    val functionCalls = scToolCall.optJSONArray("functionCalls")
                    if (functionCalls != null) {
                        for (i in 0 until functionCalls.length()) {
                            val call = functionCalls.getJSONObject(i)
                            val name = call.optString("name", "")
                            val id = call.optString("id", "")
                            val args = call.optJSONObject("args") ?: JSONObject()
                            if (name.isNotEmpty() && shouldDispatchToolCall(id)) {
                                Log.i(TAG, "Gemini invoked toolCall (serverContent): $name (id=$id)")
                                dispatchValidatedToolCall(id, name, args)
                            }
                        }
                    }
                }

                val inputTrans = serverContent.optJSONObject("inputTranscription")
                if (inputTrans != null) {
                    var userText = inputTrans.optString("text", "").trim()
                    if (userText.isEmpty()) {
                        val parts = inputTrans.optJSONArray("parts")
                        if (parts != null) {
                            val sb = StringBuilder()
                            for (p in 0 until parts.length()) {
                                val pObj = parts.optJSONObject(p)
                                sb.append(pObj?.optString("text", "") ?: "")
                            }
                            userText = sb.toString().trim()
                        }
                    }
                    if (userText.isNotBlank()) {
                        listener.onUserTextReceived(userText)
                    }
                }

                var hasOutputTranscriptionText = false
                val outputTrans = serverContent.optJSONObject("outputTranscription")
                if (outputTrans != null) {
                    var outText = outputTrans.optString("text", "")
                    if (outText.isEmpty()) {
                        val parts = outputTrans.optJSONArray("parts")
                        if (parts != null) {
                            val sb = StringBuilder()
                            for (p in 0 until parts.length()) {
                                val pObj = parts.optJSONObject(p)
                                sb.append(pObj?.optString("text", "") ?: "")
                            }
                            outText = sb.toString()
                        }
                    }
                    if (outText.isNotEmpty()) {
                        hasOutputTranscriptionText = true
                        listener.onAssistantTextReceived(outText)
                    }
                }

                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Server signaled turn interrupted")
                    listener.onInterrupted()
                }

                val modelTurn = serverContent.optJSONObject("modelTurn")
                if (modelTurn != null) {
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            // Filter out internal thinking/reasoning parts (Gemini 2.5 thinking tokens)
                            val isThought = part.optBoolean("thought", false)
                            if (isThought) {
                                Log.d(TAG, "Suppressed model thought part.")
                                continue
                            }
                            val text = part.optString("text", "")
                            val isThinkingLeak = text.startsWith("**") && (
                                    text.contains("thinking", ignoreCase = true) ||
                                    text.contains("crafting", ignoreCase = true) ||
                                    text.contains("formulating", ignoreCase = true) ||
                                    text.contains("acknowledge", ignoreCase = true) ||
                                    text.contains("inquire", ignoreCase = true) ||
                                    text.contains("greeting", ignoreCase = true)
                            )
                            if (text.isNotEmpty() && !isThinkingLeak && !hasOutputTranscriptionText) {
                                listener.onAssistantTextReceived(text)
                            }

                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val base64Data = inlineData.optString("data", "")
                                if (base64Data.length > 8) {
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    if (pcmBytes.size >= 16) {
                                        val validLen = pcmBytes.size and 1.inv()
                                        val aligned = if (validLen == pcmBytes.size) pcmBytes else pcmBytes.copyOf(validLen)
                                        listener.onAudioDataReceived(aligned)
                                    }
                                }
                            }
                        }
                    }
                }

                val isTurnDone = serverContent.optBoolean("turnComplete", false) ||
                                 serverContent.optBoolean("generationComplete", false) ||
                                 serverContent.optBoolean("endOfTurn", false)
                if (isTurnDone) {
                    Log.d(TAG, "Server signaled turnComplete/generationComplete")
                    listener.onTurnCompleted()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming server JSON: ${e.message}", e)
        }
    }

    private fun dispatchValidatedToolCall(id: String, name: String, args: JSONObject) {
        val rejection = toolContracts.rejection(name, com.google.gson.JsonParser.parseString(args.toString()).asJsonObject)
        if (rejection == null) listener.onToolCall(id, name, args)
        else sendToolResponse(id, name, JSONObject().put("success", false).put("error", "INVALID_TOOL_ARGUMENTS").put("message", rejection))
    }

    fun sendToolResponse(callId: String, functionName: String, result: JSONObject) {
        try {
            val msg = JSONObject().apply {
                put("tool_response", JSONObject().apply {
                    put("function_responses", JSONArray().put(JSONObject().apply {
                        put("id", callId)
                        put("name", functionName)
                        put("response", result)
                    }))
                })
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "Sent tool_response for $functionName")
        } catch (e: Exception) {
            Log.e(TAG, "sendToolResponse failed: ${e.message}", e)
        }
    }

    fun sendAudioChunk(pcm16k: ByteArray) {
        if (!isConnected.get() || !isSetupComplete.get() || pcm16k.isEmpty()) return

        try {
            val base64Audio = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
            val json = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$base64Audio\"}}}"
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    private var mediaChunksSent = 0
    private val lastVideoFrameSentAt = AtomicLong(0L)

    /**
     * Streams real-time screen frames to Gemini Live (BidiGenerateContent).
     */
    fun sendMediaChunk(jpegBytes: ByteArray) {
        if (!isConnected.get() || !isSetupComplete.get() || jpegBytes.isEmpty()) return
        // Gemini Live accepts video as a continuous realtimeInput stream at up to 1 FPS.
        // Enforce the cap here so periodic and post-action frames share one rate limiter.
        val now = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastVideoFrameSentAt.get()
            if (now - previous < 1000L) return
            if (lastVideoFrameSentAt.compareAndSet(previous, now)) break
        }
        try {
            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val json = "{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\"$base64Image\"}}}"
            val sent = webSocket?.send(json) ?: false
            mediaChunksSent++
            if (mediaChunksSent % 10 == 1) {
                Log.i(TAG, "Sent video frame #$mediaChunksSent to Gemini Live (sent=$sent, size=${jpegBytes.size / 1024} KB)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending media chunk: ${e.message}")
        }
    }

    /**
     * In Gemini Live BidiGenerateContent, streaming audio via realtimeInput handles turn-taking
     * natively via server-side neural VAD. Sending empty client_content cancels active generation.
     */
    fun sendAudioStreamEnd() {
        // No-op: server-side VAD handles turn completion smoothly
    }

    /**
     * Sends a text prompt to Gemini Live using the client_content turn protocol.
     */
    fun sendClientContent(text: String) {
        if (!isConnected.get() || !isSetupComplete.get() || text.isBlank()) return
        try {
            val msg = JSONObject().apply {
                put("client_content", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", text)
                        }))
                    }))
                    put("turn_complete", true)
                })
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "Sent client_content turn.")
        } catch (e: Exception) {
            Log.e(TAG, "sendClientContent failed: ${e.message}", e)
        }
    }

    private fun startSessionRenewal() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(GeminiConstants.SESSION_RENEWAL_MS)
            if (isActive && !isManuallyStopped.get()) {
                Log.d(TAG, "9-minute session threshold reached. Seamlessly renewing session...")
                webSocket?.close(1000, "Session renewal")
            }
        }
    }

    /** Routes all transport retries through exactly one lifecycle owner. */
    private fun requestReconnect(minDelayMs: Long = 0L, rotateKey: Boolean = false) {
        if (isManuallyStopped.get()) return
        if (externalReconnectOwner) {
            if (rotateKey) {
                val candidate = runCatching { apiKeyProvider().trim() }.getOrDefault("")
                if (candidate.isNotBlank() && candidate != activeApiKey) pendingApiKey = candidate
            }
            listener.onConnectionStateChanged("OFFLINE")
        } else if (rotateKey) {
            reconnectWithNextKey(minDelayMs)
        } else {
            scheduleReconnect(minDelayMs)
        }
    }

    private fun reconnectWithNextKey(minDelayMs: Long = 0L) {
        if (isManuallyStopped.get()) return
        val candidate = runCatching { apiKeyProvider().trim() }.getOrDefault("")
        if (candidate.isBlank() || candidate == activeApiKey) {
            listener.onError(
                "No alternate active Gemini API key is available. Add another key or retry after the current cooldown.",
                isFatal = false,
                errorCode = "KEY_POOL_EXHAUSTED"
            )
            listener.onConnectionStateChanged("OFFLINE")
            return
        }
        pendingApiKey = candidate
        scheduleReconnect(minDelayMs)
    }

    private fun scheduleReconnect(minDelayMs: Long = 0L) {
        if (isManuallyStopped.get()) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Reconnect limit reached ($MAX_RECONNECT_ATTEMPTS); waiting for manual retry.")
            listener.onError("Gemini could not reconnect after $MAX_RECONNECT_ATTEMPTS attempts. Restart the voice session to try again.", isFatal = false, errorCode = "RECONNECT_EXHAUSTED")
            listener.onConnectionStateChanged("OFFLINE")
            return
        }

        val attempt = ++reconnectAttempts
        val multiplier = 1L shl (attempt - 1).coerceAtMost(6)
        val exponentialDelay = (BASE_RECONNECT_DELAY_MS * multiplier).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        val jitter = (Math.random() * (exponentialDelay / 4L + 1L)).toLong()
        val delayMs = maxOf(minDelayMs, exponentialDelay + jitter)

        reconnectJob?.cancel()
        reconnectJob = coroutineScope?.launch(Dispatchers.IO) {
            Log.i(TAG, "Scheduling reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS in ${delayMs}ms.")
            delay(delayMs)
            if (!isManuallyStopped.get()) {
                initiateConnection()
            }
        }
    }

    private fun stopTimers() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = null
    }

    fun disconnect() {
        connectionEpoch.incrementAndGet()
        isManuallyStopped.set(true)
        stopTimers()
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        pendingApiKey = null
        isConnected.set(false)
        isSetupComplete.set(false)

        try {
            webSocket?.close(1000, "Client stopped session")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing webSocket: ${e.message}")
        } finally {
            webSocket = null
        }
        listener.onConnectionStateChanged("OFFLINE")
    }
}
