package com.jarvis.assistant.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/**
 * Lets JARVIS open any app installed on the device by spoken name,
 * e.g. "open YouTube", "open camera", "launch WhatsApp".
 *
 * Supports Dual Apps / Cloned Apps / Multi-user profiles (e.g. Samsung Dual Messenger,
 * Xiaomi Dual Apps, OnePlus Parallel Apps, Android Work Profile) by querying LauncherApps
 * across all active UserHandles.
 */
object AppLauncher {

    data class InstalledApp(
        val label: String,
        val packageName: String,
        val userHandle: UserHandle? = null,
        val componentName: ComponentName? = null,
        val isDualOrWorkProfile: Boolean = false
    )

    sealed class OpenAppResult {
        data class Success(val app: InstalledApp) : OpenAppResult()
        data class MultipleFound(val appName: String, val matches: List<InstalledApp>) : OpenAppResult()
        data class NotFound(val appName: String) : OpenAppResult()
        object Failure : OpenAppResult()
    }

    /**
     * Returns every user-launchable app across all user profiles (main user + dual app profiles).
     */
    fun getLaunchableApps(context: Context): List<InstalledApp> {
        val apps = mutableListOf<InstalledApp>()
        val pm = context.packageManager

        // 1. Query LauncherApps across all user profiles (handles Dual Apps / Cloned Apps / Work Profile)
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager

        if (launcherApps != null && userManager != null) {
            try {
                val profiles: List<UserHandle> = userManager.userProfiles ?: listOf(Process.myUserHandle())
                val myUser = Process.myUserHandle()
                for (profile in profiles) {
                    val isDualOrWork = profile != myUser
                    val activityList = launcherApps.getActivityList(null, profile)
                    for (item in activityList) {
                        val pkg = item.applicationInfo.packageName
                        val label = item.label.toString()
                        apps.add(
                            InstalledApp(
                                label = label,
                                packageName = pkg,
                                userHandle = profile,
                                componentName = item.componentName,
                                isDualOrWorkProfile = isDualOrWork
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AppLauncher", "LauncherApps query failed", e)
            }
        }

        // 2. Fallback / supplement with PackageManager for current user if LauncherApps returned nothing
        if (apps.isEmpty()) {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            } catch (e: Exception) {
                android.util.Log.e("AppLauncher", "queryIntentActivities failed", e)
                emptyList()
            }
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (!ri.activityInfo.enabled) continue
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                val label = ri.loadLabel(pm).toString()
                val component = ComponentName(pkg, ri.activityInfo.name)
                apps.add(
                    InstalledApp(
                        label = label,
                        packageName = pkg,
                        userHandle = null,
                        componentName = component,
                        isDualOrWorkProfile = false
                    )
                )
            }
        }

        // De-duplicate by combination of package name and user profile
        return apps
            .distinctBy { Pair(it.packageName, it.userHandle) }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.isDualOrWorkProfile }))
    }

    private val SPOKEN_ALIASES: Map<String, List<String>> = mapOf(
        "google pay" to listOf("gpay", "google pay"),
        "gpay" to listOf("gpay", "google pay"),
        "phone pay" to listOf("phonepe"),
        "phonepay" to listOf("phonepe"),
        "pubg" to listOf("bgmi", "pubg mobile", "pubg"),
        "pubg mobile" to listOf("bgmi", "pubg mobile", "pubg"),
        "free fire" to listOf("free fire max", "free fire"),
        "ff" to listOf("free fire max", "free fire"),
        "insta" to listOf("instagram"),
        "whats app" to listOf("whatsapp"),
        "snap chat" to listOf("snapchat"),
        "snap" to listOf("snapchat"),
        "fb" to listOf("facebook"),
        "yt" to listOf("youtube"),
        "photos" to listOf("photos", "google photos"),
        "google photos" to listOf("photos", "google photos"),
        "google photo" to listOf("photos", "google photos"),
        "photo" to listOf("photos", "google photos"),
        "chatgpt" to listOf("chatgpt", "chat gpt"),
        "chat gpt" to listOf("chatgpt", "chat gpt"),
        "gemini" to listOf("gemini", "google gemini"),
        "google gemini" to listOf("gemini", "google gemini"),
        "bard" to listOf("gemini", "google gemini")
    )

    /**
     * Finds matching launchable apps for a spoken query.
     */
    fun findMatchingApps(context: Context, spokenName: String): List<InstalledApp> {
        val rawQuery = spokenName.trim().lowercase()
        if (rawQuery.isEmpty()) return emptyList()

        // Strip ordinal / index keywords like "1", "2", "first", "second", "1st", "2nd", "dual", "clone"
        val cleanQuery = rawQuery
            .replace(Regex("\\b(1|2|first|second|1st|2nd|dual|clone)\\b"), "")
            .trim()
            .ifEmpty { rawQuery }

        val apps = getLaunchableApps(context)

        SPOKEN_ALIASES[cleanQuery]?.let { candidates ->
            for (candidate in candidates) {
                val hit = apps.filter { it.label.lowercase() == candidate }
                if (hit.isNotEmpty()) return hit
            }
            for (candidate in candidates) {
                val hit = apps.filter { it.label.lowercase().contains(candidate) }
                if (hit.isNotEmpty()) return hit
            }
        }

        // Matching strategy
        val exactMatches = apps.filter { it.label.lowercase() == cleanQuery }
        if (exactMatches.isNotEmpty()) return exactMatches

        val startsWithMatches = apps.filter { it.label.lowercase().startsWith(cleanQuery) }
        if (startsWithMatches.isNotEmpty()) return startsWithMatches

        val containsMatches = apps.filter { it.label.lowercase().contains(cleanQuery) }
        if (containsMatches.isNotEmpty()) return containsMatches

        val pkgMatches = apps.filter { it.packageName.lowercase().contains(cleanQuery.replace(" ", "")) }
        if (pkgMatches.isNotEmpty()) return pkgMatches

        // Try rawQuery if cleanQuery didn't match
        if (cleanQuery != rawQuery) {
            val rawExact = apps.filter { it.label.lowercase() == rawQuery }
            if (rawExact.isNotEmpty()) return rawExact

            val rawContains = apps.filter { it.label.lowercase().contains(rawQuery) }
            if (rawContains.isNotEmpty()) return rawContains
        }

        return emptyList()
    }

    /**
     * Checks if dual app / app cloning is enabled specifically for [packageName] on this device.
     */
    fun isDualAppEnabled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val pkgLower = packageName.lowercase()

        try {
            val cr = context.contentResolver
            val settingKeys = listOf(
                "double_app_start_list",
                "clone_app_list",
                "app_clone_list",
                "multi_app_installed_list",
                "dual_app_official_list",
                "clone_apps"
            )
            for (key in settingKeys) {
                val v1 = android.provider.Settings.Secure.getString(cr, key)
                if (!v1.isNullOrEmpty() && v1.lowercase().contains(pkgLower)) return true

                val v2 = android.provider.Settings.System.getString(cr, key)
                if (!v2.isNullOrEmpty() && v2.lowercase().contains(pkgLower)) return true

                val v3 = android.provider.Settings.Global.getString(cr, key)
                if (!v3.isNullOrEmpty() && v3.lowercase().contains(pkgLower)) return true
            }
        } catch (e: Exception) {
            // ignore
        }

        return false
    }

    /**
     * Attempts to open an app based on [spokenName] and optional [appNumber] (1 or 2).
     */
    fun openAppResult(context: Context, spokenName: String, appNumber: Int? = null): OpenAppResult {
        val matches = findMatchingApps(context, spokenName)
        if (matches.isEmpty()) return OpenAppResult.NotFound(spokenName)

        val requestedIndex = appNumber?.let { it - 1 } ?: run {
            val rawLower = spokenName.lowercase()
            when {
                rawLower.contains(Regex("\\b(2|2nd|second|dual)\\b")) -> 1
                rawLower.contains(Regex("\\b(1|1st|first)\\b")) -> 0
                else -> null
            }
        }

        if (requestedIndex != null) {
            val selected = matches.getOrNull(requestedIndex)
                ?: return OpenAppResult.NotFound("$spokenName (requested profile number is not installed or visible)")
            return if (launchInstalledApp(context, selected, requestedIndex + 1)) {
                OpenAppResult.Success(selected)
            } else OpenAppResult.Failure
        }

        if (matches.size == 1) {
            val selected = matches.single()
            return if (launchInstalledApp(context, selected, 1)) {
                OpenAppResult.Success(selected)
            } else OpenAppResult.Failure
        }
        return OpenAppResult.MultipleFound(spokenName, matches)
    }

    fun openApp(context: Context, spokenName: String): InstalledApp? {
        return when (val res = openAppResult(context, spokenName, null)) {
            is OpenAppResult.Success -> res.app
            // Ambiguous app names must be resolved by the caller; never silently open the first match.
            is OpenAppResult.MultipleFound -> null
            else -> null
        }
    }

    fun launchApp(context: Context, appName: String, appNumber: Int? = null): Boolean {
        return when (val result = openAppResult(context, appName, appNumber)) {
            is OpenAppResult.Success -> true
            // Do not guess when multiple installed apps/profiles match the spoken name.
            is OpenAppResult.MultipleFound -> false
            is OpenAppResult.NotFound, OpenAppResult.Failure -> false
        }
    }

    fun launchInstalledApp(context: Context, app: InstalledApp, appNumber: Int = 1): Boolean {
        return try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

            // Profile-specific apps must use the exact UserHandle returned by discovery.
            // Synthetic intent extras are not Android profile-routing APIs.
            if (app.userHandle != null && app.componentName != null && launcherApps != null) {
                launcherApps.startMainActivity(app.componentName, app.userHandle, null, null)
                return true
            }

            // PackageManager launch intents target the current user only.
            if (app.isDualOrWorkProfile) return false
            val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ActivityLauncherHelper.startActivitySafely(context, launchIntent)
        } catch (e: Exception) {
            android.util.Log.e("AppLauncher", "Failed to launch app: " + app.packageName, e)
            false
        }
    }

    fun isAppInstalled(context: Context, spokenName: String): Boolean {
        return findMatchingApps(context, spokenName).isNotEmpty()
    }
}
