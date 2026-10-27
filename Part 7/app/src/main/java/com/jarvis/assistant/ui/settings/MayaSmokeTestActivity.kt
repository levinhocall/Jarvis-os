package com.jarvis.assistant.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.network.GeminiModelResolver
import com.jarvis.assistant.vision.ScreenCaptureManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Additive, opt-in diagnostics. Source presence is never reported as device success. */
class MayaSmokeTestActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val report = linkedMapOf<String, String>()
    private val preferences by lazy { JarvisApp.instance.preferences }
    private lateinit var reportView: TextView
    private var frame: ByteArray? = null
    private var capture: ScreenCaptureManager? = null
    private var tts: android.speech.tts.TextToSpeech? = null
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            record("Screen capture", "FAIL — consent denied")
        } else try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(result.resultCode, result.data!!)
            val cm = ScreenCaptureManager.getInstance()
            capture = cm
            if (!cm.startCapture(projection, resources.displayMetrics) { bytes ->
                if (frame == null && bytes.isNotEmpty()) {
                    frame = bytes
                    handler.post {
                        record("Screen capture", "PASS — 1 JPEG frame, " + bytes.size + " bytes")
                        cm.stopCapture()
                        capture = null
                    }
                }
            }) {
                record("Screen capture", "FAIL — capture manager refused to start")
                cm.stopCapture()
                capture = null
            } else handler.postDelayed({
                if (frame == null) {
                    cm.stopCapture()
                    capture = null
                    record("Screen capture", "FAIL — no frame received within 5 seconds")
                }
            }, 5000L)
        } catch (e: Exception) {
            record("Screen capture", "FAIL — " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Maya Smoke Test"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(Color.rgb(15, 18, 28))
        }
        root.addView(TextView(this).apply {
            text = "MAYA SMOKE TEST"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Run each test manually. Network tests use the saved Gemini API key. Screen capture asks Android for consent."
            textSize = 13f
            setTextColor(Color.LTGRAY)
        })
        button(root, "Overlay — 100dp circle for 3 sec", ::testOverlay)
        button(root, "Maps — search Zudio Mall") { launch("Maps", Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=Zudio%20Mall"))) }
        button(root, "YouTube — search Arijit Singh") { launch("YouTube", Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=Arijit%20Singh"))) }
        button(root, "WhatsApp — open") {
            val i = packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            if (i == null) record("WhatsApp", "FAIL — app not installed or launch intent unavailable") else launch("WhatsApp", i)
        }
        button(root, "Screen capture — 1 frame", ::requestCapture)
        button(root, "Gemini — text call") { gemini(null) }
        button(root, "Gemini — image call (captured frame)") {
            val bytes = frame
            if (bytes == null) record("Gemini image", "FAIL — capture a frame first") else gemini(bytes)
        }
        button(root, "TTS — speak Hinglish sentence", ::testTts)
        button(root, "Copy report", ::copyReport)
        reportView = TextView(this).apply {
            text = "No tests run yet."
            textSize = 13f
            setTextColor(Color.rgb(215, 225, 240))
            setPadding(0, dp(14), 0, dp(8))
        }
        root.addView(reportView)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun button(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
    }

    private fun testOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            record("Overlay", "FAIL — permission off; opening Android settings")
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            return
        }
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val circle = TextView(this).apply {
                text = "M"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(35, 110, 220))
                    setStroke(dp(3), Color.CYAN)
                }
            }
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(dp(100), dp(100), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = dp(16); y = dp(120) }
            wm.addView(circle, params)
            record("Overlay", "PASS — 100dp circle shown for 3 seconds")
            handler.postDelayed({ runCatching { wm.removeView(circle) } }, 3000L)
        } catch (e: Exception) {
            record("Overlay", "FAIL — " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun launch(name: String, intent: Intent) {
        try {
            if (intent.resolveActivity(packageManager) == null) record(name, "FAIL — no activity can handle intent")
            else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                record(name, "PASS — Android accepted launch intent")
            }
        } catch (e: Exception) {
            record(name, "FAIL — " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun requestCapture() {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            record("Screen capture", "FAIL — " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun gemini(imageBytes: ByteArray?) {
        val key = preferences.apiKey.trim()
        val name = if (imageBytes == null) "Gemini text" else "Gemini image"
        if (key.isBlank()) {
            record(name, "FAIL — no saved Gemini API key; configure API Connection first")
            return
        }
        record(name, "RUNNING — request started")
        Thread {
            try {
                val answer = GeminiModelResolver.executeWithModelFallback(key) { model ->
                    val parts = JSONArray().put(JSONObject().put("text",
                        if (imageBytes == null) "Reply with exactly MAYA_TEXT_OK."
                        else "Describe this screen in one short sentence."))
                    if (imageBytes != null) parts.put(JSONObject().put("inline_data",
                        JSONObject().put("mime_type", "image/jpeg")
                            .put("data", Base64.getEncoder().encodeToString(imageBytes))))
                    val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                        .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                        .post(body).build()
                    http.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code + ": " + text.take(220))
                        val parsed = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
                        if (parsed.isBlank()) throw IllegalStateException("Response contained no text")
                        parsed
                    }
                }
                runOnUiThread { record(name, "PASS — " + answer.take(160)) }
            } catch (e: Exception) {
                runOnUiThread { record(name, "FAIL — " + e.javaClass.simpleName + ": " + e.message) }
            }
        }.start()
    }

    private fun testTts() {
        try {
            tts?.shutdown()
            tts = android.speech.tts.TextToSpeech(this) { status ->
                val engine = tts
                if (status == android.speech.tts.TextToSpeech.SUCCESS && engine != null) {
                    val result = engine.speak("Namaste, main Maya hoon. System test chal raha hai.",
                        android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "maya-smoke-test")
                    record("TTS", if (result == android.speech.tts.TextToSpeech.SUCCESS)
                        "PASS — speak request accepted; confirm audio is audible" else "FAIL — TTS speak request rejected")
                } else record("TTS", "FAIL — TTS engine initialization failed")
            }
        } catch (e: Exception) {
            record("TTS", "FAIL — " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun record(name: String, value: String) {
        report[name] = value
        if (::reportView.isInitialized) reportView.text = report.entries.joinToString("\n") { it.key + ": " + it.value }
    }

    private fun copyReport() {
        val text = if (report.isEmpty()) "Maya Smoke Test: no tests run."
            else report.entries.joinToString("\n") { it.key + ": " + it.value }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Maya Smoke Test Report", text))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        runCatching { capture?.stopCapture() }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
