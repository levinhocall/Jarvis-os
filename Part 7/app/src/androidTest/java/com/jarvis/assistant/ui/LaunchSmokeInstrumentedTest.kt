package com.jarvis.assistant.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeInstrumentedTest {
    private fun prepareHomePermissions() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val pkg=instrumentation.targetContext.packageName
        for(permission in listOf(android.Manifest.permission.RECORD_AUDIO,android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            instrumentation.uiAutomation.grantRuntimePermission(pkg,permission)
        }
        if(android.os.Build.VERSION.SDK_INT>=33) instrumentation.uiAutomation.grantRuntimePermission(pkg,android.Manifest.permission.POST_NOTIFICATIONS)
    }
    @Test fun captureHomeForVisualReview() {
        prepareHomePermissions()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val scenario=androidx.test.core.app.ActivityScenario.launch<com.jarvis.assistant.ui.home.MainActivity>(Intent(context,com.jarvis.assistant.ui.home.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            instrumentation.waitForIdleSync()
            Thread.sleep(2500)
            val capture=instrumentation.uiAutomation.takeScreenshot()
            org.junit.Assert.assertNotNull("Emulator screenshot unavailable",capture)
            val bitmap=capture!!
            val scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,360,(bitmap.height*360f/bitmap.width).toInt(),true)
            try {
                java.io.File(context.cacheDir,"jarvis-home-preview.jpg").outputStream().use { output ->
                    org.junit.Assert.assertTrue(scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,output))
                }
                if(android.os.Build.VERSION.SDK_INT>=29) {
                    val resolver=context.contentResolver
                    val uri=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"jarvis-home-preview.jpg")
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/jpeg")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/JarvisTestEvidence")
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
                    }) ?: error("Emulator visual evidence storage unavailable")
                    resolver.openOutputStream(uri)!!.use { output ->org.junit.Assert.assertTrue(scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,output))}
                    resolver.update(uri,android.content.ContentValues().apply {put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)},null,null)
                }
            } finally {if(scaled!==bitmap)scaled.recycle();bitmap.recycle()}
        } finally {scenario.close()}
    }
    @Test fun retiringAccessibilityInstanceCannotDisconnectReplacement() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance==null)
        instrumentation.runOnMainSync {
            val old=com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService()
            val replacement=com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService()
            try {
                val connect=com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible=true }
                connect.invoke(old);connect.invoke(replacement)
                old.onUnbind(null);old.onDestroy()
                org.junit.Assert.assertSame(replacement,com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance)
                org.junit.Assert.assertTrue(com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel.isOperational())
            } finally {replacement.onDestroy()}
        }
    }
    @Test fun offlineVoskModelLoadsAndSilenceDoesNotWake() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.jarvis.assistant.audio.VoskModelManager.init(context,this,includeSpeaker=false)
        kotlinx.coroutines.withTimeout(90_000) { com.jarvis.assistant.audio.VoskModelManager.isModelReady.first { it } }
        val recognizer = org.vosk.Recognizer(com.jarvis.assistant.audio.VoskModelManager.model,16000f)
        try {
            recognizer.acceptWaveForm(ByteArray(32000),32000)
            org.junit.Assert.assertEquals("",org.json.JSONObject(recognizer.finalResult).optString("text"))
        } finally { recognizer.close() }
    }
    @Test fun foregroundMicrophoneTestStartsAndStopsWithoutCloudSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        val activity = instrumentation.startActivitySync(Intent(context,com.jarvis.assistant.ui.directives.WakeWordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        try {
            instrumentation.runOnMainSync {
                androidx.core.content.ContextCompat.startForegroundService(activity,Intent(activity,com.jarvis.assistant.background.JarvisConversationService::class.java).setAction(com.jarvis.assistant.background.JarvisConversationService.ACTION_TEST_WAKE))
            }
            kotlinx.coroutines.withTimeout(60_000) { com.jarvis.assistant.audio.wake.WakeDiagnostics.status.first { it == "Microphone test listening locally" } }
            org.junit.Assert.assertFalse(com.jarvis.assistant.background.JarvisConversationService.isSessionOn.value)
            org.junit.Assert.assertTrue(com.jarvis.assistant.background.JarvisConversationService.isStandby.value)
            org.junit.Assert.assertTrue(com.jarvis.assistant.audio.wake.WakeDiagnostics.listening.value)
            org.junit.Assert.assertTrue(com.jarvis.assistant.audio.wake.WakeDiagnostics.testing.value)
            org.junit.Assert.assertEquals(com.jarvis.assistant.audio.MicrophoneOwner.WAKE_WORD,
                com.jarvis.assistant.audio.MicrophoneOwnership.owner.value)
            org.junit.Assert.assertNull(com.jarvis.assistant.audio.MicrophoneOwnership.acquire(
                com.jarvis.assistant.audio.MicrophoneOwner.VOICE_ENROLLMENT))
        } finally {
            instrumentation.runOnMainSync { activity.startService(Intent(activity,com.jarvis.assistant.background.JarvisConversationService::class.java).setAction(com.jarvis.assistant.background.JarvisConversationService.ACTION_STOP_ALL)) }
            kotlinx.coroutines.withTimeout(10_000) { com.jarvis.assistant.background.JarvisConversationService.isStandby.first { !it } }
            org.junit.Assert.assertFalse(com.jarvis.assistant.audio.wake.WakeDiagnostics.listening.value)
            org.junit.Assert.assertFalse(com.jarvis.assistant.audio.wake.WakeDiagnostics.testing.value)
            org.junit.Assert.assertNull(com.jarvis.assistant.audio.MicrophoneOwnership.owner.value)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    @Test fun realAudioCaptureIsExclusiveAndScopeCancellationReleasesIt() = runBlocking {
        prepareHomePermissions()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scenario = androidx.test.core.app.ActivityScenario.launch<com.jarvis.assistant.ui.directives.WakeWordActivity>(
            Intent(context,com.jarvis.assistant.ui.directives.WakeWordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val frame = kotlinx.coroutines.CompletableDeferred<Unit>()
        val recorder = com.jarvis.assistant.audio.AudioRecorder { bytes, _, _ -> if(bytes.isNotEmpty()) frame.complete(Unit) }
        val competing = com.jarvis.assistant.audio.AudioRecorder { _, _, _ -> }
        try {
            withContext(Dispatchers.IO) {
                org.junit.Assert.assertTrue("Android AudioRecord failed to start", recorder.start(scope))
                org.junit.Assert.assertFalse("Second capture must be denied", competing.start(scope))
            }
            kotlinx.coroutines.withTimeout(8000) { frame.await() }
            org.junit.Assert.assertEquals(com.jarvis.assistant.audio.MicrophoneOwner.VOICE_SESSION,
                com.jarvis.assistant.audio.MicrophoneOwnership.owner.value)
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            kotlinx.coroutines.withTimeout(8000) { com.jarvis.assistant.audio.MicrophoneOwnership.owner.first { it == null } }
            org.junit.Assert.assertFalse(recorder.isActive)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                recorder.stop(); competing.stop(); scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            }
            scenario.close()
        }
    }
    @Test fun customWakeNameUpdatesReferenceStylePhrasePreview() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val settings=com.jarvis.assistant.audio.wake.WakeSettings(context)
        val previousName=settings.name; val previousAlias=settings.alias
        val activity=instrumentation.startActivitySync(Intent(context,com.jarvis.assistant.ui.directives.WakeWordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) + if(view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            instrumentation.runOnMainSync {
                val views=descendants(activity.window.decorView)
                org.junit.Assert.assertTrue(views.filterIsInstance<android.widget.TextView>().any { it.text.toString()=="Microphone" })
                org.junit.Assert.assertTrue(views.filterIsInstance<android.widget.Button>().any { it.text.toString()=="Test Microphone" })
                org.junit.Assert.assertTrue(views.filterIsInstance<com.google.android.material.card.MaterialCardView>().size >= 5)
                val inputs=views.filterIsInstance<android.widget.EditText>()
                inputs[0].setText("myra"); inputs[1].setText("mira")
                views.filterIsInstance<android.widget.Button>().single { it.text.toString()=="Save wake word" }.performClick()
                org.junit.Assert.assertEquals("myra",settings.name)
                org.junit.Assert.assertTrue(views.filterIsInstance<android.widget.TextView>().any { it.text.contains("Hey myra") && it.text.contains("Wake up myra") })
            }
        } finally {
            settings.name=previousName; settings.alias=previousAlias
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    @Test fun eachIconSelectionLeavesExactlyOneLauncherEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            com.jarvis.assistant.ui.aura.AppIconSwitcher.icons.keys.forEach { label ->
                com.jarvis.assistant.ui.aura.AppIconSwitcher.select(context,label)
                val entries = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),0)
                org.junit.Assert.assertEquals(1,entries.size)
                org.junit.Assert.assertTrue(entries.single().activityInfo.name.endsWith(com.jarvis.assistant.ui.aura.AppIconSwitcher.icons.getValue(label)))
            }
        } finally { com.jarvis.assistant.ui.aura.AppIconSwitcher.select(context,"Default") }
    }
    @Test fun encryptedRoomAndReportedScreensOpen() = runBlocking {
        prepareHomePermissions()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as JarvisApp
        withContext(Dispatchers.IO) {
            app.memoryDatabase.memoryDao().getAllMemoriesFlow().first()
            app.memoryDatabase.notificationHistoryDao().observeRecent().first()
        }
        instrumentation.runOnMainSync { org.junit.Assert.assertFalse(app.memoryDatabase.inTransaction()) }
        for (screen in listOf(
            com.jarvis.assistant.ui.notification.NotificationHistoryActivity::class.java,
            com.jarvis.assistant.ui.memory.MemoryActivity::class.java,
            com.jarvis.assistant.ui.home.MainActivity::class.java,
            com.jarvis.assistant.ui.settings.AuraControlActivity::class.java,
            com.jarvis.assistant.ui.directives.WakeWordActivity::class.java,
            com.jarvis.assistant.emergency.EmergencyContactActivity::class.java,
            com.jarvis.assistant.ui.aura.AvatarStudioActivity::class.java,
            com.jarvis.assistant.ui.aura.DecisionLogsActivity::class.java,
            com.jarvis.assistant.ui.aura.MediaStudioActivity::class.java,
            com.jarvis.assistant.ui.wallpaper.VideoWallpaperActivity::class.java,
            com.jarvis.assistant.ui.directives.VoiceModeActivity::class.java,
            com.jarvis.assistant.ui.directives.IdeasActivity::class.java,
            com.jarvis.assistant.ui.directives.EnglishTeacherActivity::class.java
        )) {
            val scenario=androidx.test.core.app.ActivityScenario.launch<android.app.Activity>(Intent(context,screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            try {scenario.recreate()} catch(error:Throwable) {throw AssertionError("Lifecycle failure in ${screen.simpleName}",error)} finally {scenario.close()}
        }
    }
}
