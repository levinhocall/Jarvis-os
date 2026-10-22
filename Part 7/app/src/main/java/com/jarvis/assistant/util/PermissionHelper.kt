package com.jarvis.assistant.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService

object PermissionHelper {

    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasBatteryOptimizationPermission(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasWriteSettingsPermission(context: Context): Boolean {
        return Settings.System.canWrite(context)
    }

    fun requestWriteSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        AccessibilityHealthModel.refreshFromSystem(context)
        if (JarvisAccessibilityService.isRunning() || JarvisAccessibilityService.instance != null) {
            return true
        }
        return AccessibilityHealthModel.isServiceEnabledInSettings(context)
    }

    fun isAccessibilityServiceOperational(): Boolean {
        return JarvisAccessibilityService.isRunning()
    }

    fun isAccessibilityServiceConnecting(): Boolean {
        return AccessibilityHealthModel.isConnecting()
    }

    fun isRestrictedSettingsActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                "android:access_restricted_settings",
                android.os.Process.myUid(),
                context.packageName
            )
            mode == android.app.AppOpsManager.MODE_ERRORED || mode == android.app.AppOpsManager.MODE_IGNORED
        } catch (_: Exception) {
            false
        }
    }

    fun openAccessibilitySettings(context: Context) {
        val targetComponent = android.content.ComponentName(context, JarvisAccessibilityService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(":settings:fragment_args_key", targetComponent)
            putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                putString(":settings:fragment_args_key", targetComponent)
            })
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {}
        }
    }

    fun isVivoDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
        val brand = Build.BRAND.lowercase(java.util.Locale.ROOT)
        return manufacturer.contains("vivo") || brand.contains("vivo") || manufacturer.contains("iqoo") || brand.contains("iqoo")
    }

    fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
        return manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")
    }

    fun openVivoAutostart(context: Context): Boolean {
        val intents = listOf(
            Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(android.content.ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingActivity")),
            Intent().setComponent(android.content.ComponentName("com.vivo.abe", "com.vivo.abe.feature.power.BatteryOptimizationActivity")),
            Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"))
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    fun openVivoHighBackgroundPower(context: Context): Boolean {
        val intents = listOf(
            Intent().setComponent(android.content.ComponentName("com.vivo.abe", "com.vivo.abe.restriction.CheckManageRestrictionActivity")),
            Intent().setComponent(android.content.ComponentName("com.vivo.abe", "com.vivo.abe.feature.power.BatteryOptimizationActivity")),
            Intent().setComponent(android.content.ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingActivity"))
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    fun isAppSideloaded(context: Context): Boolean {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            installer.isNullOrBlank() ||
            installer.contains("packageinstaller") ||
            installer.contains("filemanager") ||
            installer.contains("download") ||
            installer.contains("shell")
        } catch (_: Exception) {
            true
        }
    }

    fun showVivoAccessibilitySetupDialog(activity: android.app.Activity) {
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("Vivo / iQOO Accessibility Setup")
            .setMessage(
                "On Vivo devices (Funtouch OS / Origin OS), 3 steps are required for Jarvis to work:\n\n" +
                "Step 1: In Accessibility Settings → Installed services (or Downloaded apps) → Toggle JARVIS ON.\n\n" +
                "Step 2: If 'Restricted setting' appears, tap '2. Unlock App Info' → Tap 3 dots (⋮) top-right → 'Allow restricted settings' and confirm PIN.\n\n" +
                "Step 3: In iManager / Battery settings, enable 'Autostart' and 'Allow high background power consumption' so Vivo does not kill the accessibility service."
            )
            .setPositiveButton("1. Open Accessibility") { _, _ ->
                openAccessibilitySettings(activity)
            }
            .setNeutralButton("2. Unlock App Info") { _, _ ->
                openAppSettings(activity)
            }
            .setNegativeButton("3. Vivo Battery/Power") { _, _ ->
                if (!openVivoHighBackgroundPower(activity)) {
                    if (!openVivoAutostart(activity)) {
                        openAppSettings(activity)
                    }
                }
            }
        builder.show()
    }

    fun requestAccessibilityWithRestrictedGuidance(activity: android.app.Activity) {
        if (isAccessibilityServiceOperational()) {
            openAccessibilitySettings(activity)
            return
        }

        if (isVivoDevice()) {
            showVivoAccessibilitySetupDialog(activity)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (isRestrictedSettingsActive(activity) || isAppSideloaded(activity))) {
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Security Authorization Required")
                .setMessage(
                    "Android 13/14 requires a 2-step approval for sideloaded automation apps:\n\n" +
                    "Step 1: Tap '1. Trigger In Settings' → Find JARVIS → Tap it (system will show 'Restricted setting').\n\n" +
                    "Step 2: Tap '2. Unlock App Info' → Tap 3 dots (⋮) top-right (or scroll to bottom on Xiaomi/Vivo) → Select 'Allow restricted settings' and confirm PIN.\n\n" +
                    "Step 3: Return to Accessibility Settings and toggle JARVIS ON."
                )
                .setPositiveButton("1. Trigger In Settings") { _, _ ->
                    openAccessibilitySettings(activity)
                }
                .setNeutralButton("2. Unlock App Info") { _, _ ->
                    openAppSettings(activity)
                }
                .setNegativeButton("Cancel", null)

            builder.show()
        } else {
            openAccessibilitySettings(activity)
        }
    }

    fun hasAllRequiredPermissions(context: Context): Boolean {
        return hasRecordAudioPermission(context) &&
               hasNotificationPermission(context)
    }

    fun hasOptimalBackgroundPerformance(context: Context): Boolean {
        return hasBatteryOptimizationPermission(context)
    }

    fun requestBatteryOptimization(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (_: Exception) {}
            }
        }
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}

