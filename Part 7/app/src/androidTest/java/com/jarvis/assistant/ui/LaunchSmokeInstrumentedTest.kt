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
    @Test fun encryptedRoomAndReportedScreensOpen() = runBlocking {
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
            com.jarvis.assistant.ui.memory.MemoryActivity::class.java
        )) {
            val activity = instrumentation.startActivitySync(Intent(context, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { activity.recreate() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
