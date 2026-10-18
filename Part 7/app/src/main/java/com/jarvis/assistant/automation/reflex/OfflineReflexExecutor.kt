package com.jarvis.assistant.automation.reflex

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.jarvis.assistant.automation.phone.PhoneControlManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OfflineReflexResult(
    val handled: Boolean,
    val spokenResponse: String,
    val actionName: String = ""
)

object OfflineReflexExecutor {
    private const val TAG = "OfflineReflexExecutor"

    /**
     * Inspects raw user utterance. If it matches a 100% local hardware, status, or protocol command,
     * executes it locally with <100ms latency without making any network or LLM calls.
     */
    fun tryExecuteReflex(
        rawText: String,
        context: Context,
        phoneControlManager: PhoneControlManager?
    ): OfflineReflexResult {
        val clean = rawText.lowercase(Locale.ROOT).trim()

        // 1. Instant Battery Status Readout
        if (clean.contains("battery") && (clean.contains("kitni") || clean.contains("kya") || clean.contains("status") || clean.contains("percent") || clean.contains("check"))) {
            val batteryStatus = getBatteryStatus(context)
            return OfflineReflexResult(true, batteryStatus, "battery_status")
        }

        // 2. Instant Time Readout
        if ((clean.contains("time") || clean.contains("samay") || clean.contains("waqt")) && (clean.contains("kya") || clean.contains("batao") || clean.contains("hua"))) {
            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return OfflineReflexResult(true, "Abhi $timeStr ho raha hai, Nazib Sir.", "time_status")
        }

        // 3. Instant Network Status Readout
        if (clean.contains("network") && (clean.contains("kaisa") || clean.contains("check") || clean.contains("status") || clean.contains("hai"))) {
            val networkStatus = getNetworkStatus(context)
            return OfflineReflexResult(true, networkStatus, "network_status")
        }

        // 4. Torch / Flashlight Reflex
        if (clean.contains("torch") || clean.contains("flashlight")) {
            val pm = phoneControlManager ?: PhoneControlManager(context)
            if (clean.contains("on") || clean.contains("jala") || clean.contains("chalu") || clean.contains("start")) {
                val res = pm.setFlashlight(true)
                return OfflineReflexResult(true, res, "torch_on")
            }
            if (clean.contains("off") || clean.contains("band") || clean.contains("bujha") || clean.contains("stop")) {
                val res = pm.setFlashlight(false)
                return OfflineReflexResult(true, res, "torch_off")
            }
            val res = pm.toggleFlashlight()
            return OfflineReflexResult(true, res, "torch_toggle")
        }

        // 5. Volume & Audio Reflex
        if (clean.contains("volume") || clean.contains("awaz") || clean.contains("sound")) {
            val pm = phoneControlManager ?: PhoneControlManager(context)
            if (clean.contains("mute") || clean.contains("silent") || clean.contains("band")) {
                val res = pm.muteVolume(true)
                return OfflineReflexResult(true, res, "volume_mute")
            }
            if (clean.contains("unmute") || clean.contains("awaz chalu")) {
                val res = pm.muteVolume(false)
                return OfflineReflexResult(true, res, "volume_unmute")
            }
            if (clean.contains("full") || clean.contains("max") || clean.contains("100")) {
                val res = pm.setVolumePercent(100)
                return OfflineReflexResult(true, res, "volume_full")
            }
            // Parse percentage (e.g., "volume 50 percent karo")
            val percentMatch = Regex("(\\d{1,3})\\s*(%|percent)").find(clean)
            if (percentMatch != null) {
                val pct = percentMatch.groupValues[1].toIntOrNull() ?: 50
                val res = pm.setVolumePercent(pct.coerceIn(0, 100))
                return OfflineReflexResult(true, res, "volume_percent")
            }
            if (clean.contains("badhao") || clean.contains("zyada") || clean.contains("up") || clean.contains("increase")) {
                val res = pm.adjustVolume(true)
                return OfflineReflexResult(true, res, "volume_up")
            }
            if (clean.contains("kam") || clean.contains("down") || clean.contains("ghatao") || clean.contains("decrease")) {
                val res = pm.adjustVolume(false)
                return OfflineReflexResult(true, res, "volume_down")
            }
        }

        // 6. Brightness Reflex
        if (clean.contains("brightness")) {
            val pm = phoneControlManager ?: PhoneControlManager(context)
            if (clean.contains("full") || clean.contains("max") || clean.contains("100")) {
                val res = pm.setBrightnessPercent(100)
                return OfflineReflexResult(true, res, "brightness_full")
            }
            if (clean.contains("zero") || clean.contains("minimum") || clean.contains("min")) {
                val res = pm.setBrightnessPercent(5)
                return OfflineReflexResult(true, res, "brightness_min")
            }
            val percentMatch = Regex("(\\d{1,3})\\s*(%|percent)").find(clean)
            if (percentMatch != null) {
                val pct = percentMatch.groupValues[1].toIntOrNull() ?: 50
                val res = pm.setBrightnessPercent(pct.coerceIn(0, 100))
                return OfflineReflexResult(true, res, "brightness_percent")
            }
            if (clean.contains("badhao") || clean.contains("zyada") || clean.contains("up") || clean.contains("increase")) {
                val res = pm.adjustBrightness(true)
                return OfflineReflexResult(true, res, "brightness_up")
            }
            if (clean.contains("kam") || clean.contains("down") || clean.contains("ghatao") || clean.contains("decrease")) {
                val res = pm.adjustBrightness(false)
                return OfflineReflexResult(true, res, "brightness_down")
            }
        }

        // 7. Offline App Launcher Reflex
        val openAppPrefixes = listOf("open ", "kholo ", "launch ")
        val openAppSuffixes = listOf(" kholo", " open karo", " launch karo")
        for (prefix in openAppPrefixes) {
            if (clean.startsWith(prefix)) {
                val appName = clean.removePrefix(prefix).trim()
                if (appName.isNotBlank() && !isCloudSearchQuery(appName)) {
                    val pm = phoneControlManager ?: PhoneControlManager(context)
                    val res = pm.openApp(appName)
                    return OfflineReflexResult(true, res, "open_app")
                }
            }
        }
        for (suffix in openAppSuffixes) {
            if (clean.endsWith(suffix)) {
                val appName = clean.removeSuffix(suffix).trim()
                if (appName.isNotBlank() && !isCloudSearchQuery(appName)) {
                    val pm = phoneControlManager ?: PhoneControlManager(context)
                    val res = pm.openApp(appName)
                    return OfflineReflexResult(true, res, "open_app")
                }
            }
        }

        return OfflineReflexResult(false, "", "")
    }

    private fun isCloudSearchQuery(appName: String): Boolean {
        val searchKeywords = listOf("search", "google", "flipkart", "amazon", "youtube", "karo", "dikhao", "batao", "price")
        return searchKeywords.any { appName.contains(it) }
    }

    private fun getBatteryStatus(context: Context): String {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else level
            if (isCharging) {
                "Battery $batteryPct percent hai aur device charge ho raha hai, Nazib Sir."
            } else {
                "Battery $batteryPct percent hai, Nazib Sir."
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking battery: ${e.message}")
            "Battery check karne mein dikkat hui, Sir."
        }
    }

    private fun getNetworkStatus(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(activeNetwork)
            when {
                caps == null -> "Aapka device abhi offline hai, Sir."
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Device high-speed Wi-Fi network se connected hai, Nazib Sir."
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Device mobile cellular network se connected hai, Nazib Sir."
                else -> "Device internet se connected hai, Nazib Sir."
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking network: ${e.message}")
            "Network check karne mein dikkat hui, Sir."
        }
    }
}
