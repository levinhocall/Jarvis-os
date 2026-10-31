package com.jarvis.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.memory.RecallCoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class CameraVisionResult(
    val success: Boolean,
    val summary: String,
    val extractedAmount: String? = null,
    val title: String? = null
)

/**
 * Sends an actual user-provided camera/gallery bitmap to the configured Gemini vision model.
 * This class deliberately never fabricates a frame or reports a failed request as success.
 */
object CameraVisionAnalyzer {
    private const val TAG = "CameraVisionAnalyzer"
    private const val MAX_IMAGE_DIMENSION = 1536
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeBitmap(
        context: Context,
        bitmap: Bitmap,
        prompt: String = "Describe the image. Extract visible text and key details. Keep the answer concise."
    ): CameraVisionResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? JarvisApp
            ?: return@withContext CameraVisionResult(false, "Jarvis application context is unavailable.")
        val apiKey = app.preferences.apiKey.trim()
        if (apiKey.isBlank()) {
            return@withContext CameraVisionResult(
                success = false,
                summary = "Image analysis requires a configured Gemini API key in Settings."
            )
        }
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext CameraVisionResult(false, "The selected image is no longer available. Capture or select it again.")
        }

        var resized: Bitmap? = null
        try {
            val scale = minOf(
                1f,
                MAX_IMAGE_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
            )
            val image = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { resized = it }
            } else bitmap

            val stream = ByteArrayOutputStream()
            if (!image.compress(Bitmap.CompressFormat.JPEG, 86, stream) || stream.size() == 0) {
                return@withContext CameraVisionResult(false, "Could not encode the selected image.")
            }
            val base64Data = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray()
                        .put(JSONObject().put("text", prompt))
                        .put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Data)
                        }))
                    )
                }))
            }
            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    return@withContext CameraVisionResult(
                        success = false,
                        summary = "Image analysis failed (HTTP ${response.code}). " +
                            (detail.ifBlank { "Check the Gemini API key, quota and network connection." })
                    )
                }
                if (body.isBlank()) {
                    return@withContext CameraVisionResult(false, "Image analysis returned an empty response.")
                }
                val json = JSONObject(body)
                val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                val candidateText = candidate?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.let { parts ->
                        (0 until parts.length()).mapNotNull { index ->
                            parts.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
                        }.joinToString("\n").trim()
                    }.orEmpty()
                if (candidateText.isBlank()) {
                    val reason = candidate?.optString("finishReason").orEmpty()
                    return@withContext CameraVisionResult(
                        success = false,
                        summary = "No image analysis text was returned" +
                            if (reason.isNotBlank()) " (finish reason: $reason)." else "."
                    )
                }

                val amount = Regex("(?:₹|rs\\.?|inr)\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
                    .find(candidateText)?.let { "₹" + it.groupValues[1] }
                runCatching {
                    RecallCoreManager.logProductResearch(
                        context = context.applicationContext,
                        productName = "Neural Lens Image",
                        price = amount ?: "Not detected",
                        platform = "Gemini Vision",
                        extraDetails = candidateText
                    )
                }.onFailure { Log.w(TAG, "Could not save image analysis to Recall Core", it) }

                CameraVisionResult(
                    success = true,
                    summary = candidateText,
                    extractedAmount = amount,
                    title = "Neural Lens"
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Image analysis failed", error)
            CameraVisionResult(
                success = false,
                summary = "Image analysis failed: ${error.localizedMessage ?: "network or provider error"}"
            )
        } finally {
            resized?.takeIf { it !== bitmap }?.recycle()
        }
    }

    /**
     * Compatibility entry point for legacy command callers. No image is available in this
     * call path, so it must fail honestly rather than sending a generated placeholder bitmap.
     */
    @Deprecated("Pass a real camera/gallery Bitmap to analyzeBitmap().")
    suspend fun analyzeSnapshot(
        context: Context,
        prompt: String = "Extract key details and visible text."
    ): CameraVisionResult = withContext(Dispatchers.IO) {
        CameraVisionResult(
            success = false,
            summary = "No real camera frame was supplied. Open Neural Lens and capture or select an image before analysis."
        )
    }
}
