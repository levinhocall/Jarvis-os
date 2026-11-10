package com.jarvis.assistant.ui.aura

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import com.google.gson.JsonParser
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.agent.awaitCouncilResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Explicit cloud requests with bounded, cancellable responses. No personal memory is read. */
object AuraCloudClient {
    private val http = OkHttpClient.Builder().callTimeout(50, TimeUnit.SECONDS).build()
    private suspend fun call(model: String, payload: JSONObject, limit: Long = 128*1024L): JSONObject {
        val prefs = JarvisApp.instance.preferences
        val key = (prefs.getNextActiveKey("gemini") ?: prefs.apiKey).trim()
        check(key.isNotEmpty()) { "Configure Gemini first" }
        // Image output has a separate explicit model contract; do not route it to a text fallback.
        val selectedModel = if (model.contains("image", true)) model else
            com.jarvis.assistant.network.GeminiModelCatalog.resolve(key, com.jarvis.assistant.network.GeminiRequestKind.FAST_TEXT)
        val response = http.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/${selectedModel.removePrefix("models/")}:generateContent")
            .header("x-goog-api-key",key).post(payload.toString().toRequestBody("application/json".toMediaType())).build()).awaitCouncilResponse(limit)
        check(response.isSuccessful) { "Provider request failed (${response.code})" }
        return JSONObject(response.body)
    }
    private fun contents(prompt: String) = JSONArray().put(JSONObject().put("role","user").put("parts",JSONArray().put(JSONObject().put("text",prompt))))
    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        require(query.isNotBlank() && query.length <= 2000)
        check(AuraOptions.enabled("backgroundSearch")) { "Enable Background Web Search in Aura first" }
        val response = call(com.jarvis.assistant.data.model.GeminiConstants.MODEL_3_8_FLASH,JSONObject()
            .put("contents",contents("Search the web for this question. Use retrieved sources and distinguish uncertain claims: $query"))
            .put("tools",JSONArray().put(JSONObject().put("google_search",JSONObject()))))
        val candidate = response.getJSONArray("candidates").getJSONObject(0)
        val chunks = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks") ?: error("Provider supplied no grounded web sources")
        val sources = (0 until chunks.length()).mapNotNull { chunks.getJSONObject(it).optJSONObject("web")?.let { web ->
            val url=web.optString("uri"); if (url.startsWith("https://")) "${web.optString("title")} — $url" else null
        } }.distinct().take(8)
        check(sources.isNotEmpty()) { "No usable web sources returned" }
        val parts=candidate.getJSONObject("content").getJSONArray("parts")
        (0 until parts.length()).joinToString("\n") { parts.getJSONObject(it).optString("text") }.take(10000)+"\n\nSources:\n"+sources.joinToString("\n")
    }
    suspend fun image(context: Context, prompt: String): Uri = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank() && prompt.length <= 4000)
        check(AuraOptions.enabled("imageGeneration")) { "Enable Gemini Images in Aura first" }
        check(android.os.Build.VERSION.SDK_INT >= 29) { "Gallery generation requires Android 10 or newer" }
        val response=call("gemini-2.5-flash-image",JSONObject().put("contents",contents(prompt))
            .put("generationConfig",JSONObject().put("responseModalities",JSONArray().put("TEXT").put("IMAGE"))),16*1024*1024L)
        val parts=response.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val inline=(0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("inlineData") }.firstOrNull() ?: error("Provider returned no image")
        val mime=inline.getString("mimeType"); require(mime in setOf("image/png","image/jpeg","image/webp"))
        val bytes=Base64.decode(inline.getString("data"),Base64.DEFAULT); require(bytes.size in 1..12*1024*1024)
        val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image data" }
        val extension=when(mime){"image/jpeg"->"jpg";"image/webp"->"webp";else->"png"}
        val resolver=context.contentResolver
        val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"JARVIS-${System.currentTimeMillis()}.$extension")
            put(MediaStore.Images.Media.MIME_TYPE,mime); put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Jarvis")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }) ?: error("Gallery unavailable")
        try {
            (resolver.openOutputStream(uri) ?: error("Gallery output unavailable")).use { it.write(bytes) }
            resolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            uri
        } catch(error: Exception) { resolver.delete(uri,null,null); throw error }
    }
    suspend fun decide(telemetry: String): Pair<String,String> = withContext(Dispatchers.IO) {
        val response=call(com.jarvis.assistant.data.model.GeminiConstants.MODEL_3_8_FLASH,JSONObject().put("contents",contents(
            "Review these device counts, not message contents. Return JSON action ('notify' or 'none') and reason (under 200 characters). Notify only for a useful battery warning or unread/missed communication reminder. Never claim a message was sent or an external task completed. Telemetry: $telemetry"))
            .put("generationConfig",JSONObject().put("responseMimeType","application/json")))
        val json=response.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val decision=JSONObject(json); val action=decision.getString("action"); require(action in setOf("notify","none"))
        action to decision.getString("reason").take(300)
    }
}
