package com.jarvis.assistant.automation.briefing

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import com.jarvis.assistant.automation.notification.JarvisNotificationListenerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProactiveBriefingManager {
    private const val TAG = "ProactiveBriefing"

    fun generateBriefing(context: Context, userName: String = "Nazib Sir"): String {
        val timeGreeting = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Welcome back"
        }

        val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

        // 1. Battery Telemetry
        val (batteryPct, isCharging) = getBatteryTelemetry(context)
        val batteryStr = if (isCharging) "$batteryPct% and charging" else "$batteryPct%"

        // 2. Network Telemetry
        val networkType = getNetworkType(context)

        // 3. Notification Telemetry
        val waCount = JarvisNotificationListenerService.getUnreadWhatsAppCount()
        val notifStr = when {
            waCount == 1 -> "You have 1 pending WhatsApp message."
            waCount > 1 -> "You have $waCount pending WhatsApp messages."
            else -> "No pending priority messages."
        }

        return "$timeGreeting, $userName. It is $timeStr. All systems nominal. Battery is at $batteryStr, network is $networkType. $notifStr Ready for instructions, Sir."
    }

    private fun getBatteryTelemetry(context: Context): Pair<Int, Boolean> {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
            pct to isCharging
        } catch (_: Exception) {
            100 to false
        }
    }

    private fun getNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(activeNetwork)
            when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "high-speed Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular 5G"
                else -> "online"
            }
        } catch (_: Exception) {
            "online"
        }
    }
}
