package com.jarvis.assistant.data.memory

import android.os.Looper
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ColdRoomDispatchInstrumentedTest {
    @Test fun coldFlowCollectedOnMainOpensOnlyOnWorkerAndWarmProbeReusesHandle() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "room-dispatch-${System.nanoTime()}.db"
        val opens = AtomicInteger()
        val database = withContext(Dispatchers.Main) {
            Room.databaseBuilder(context, MemoryDatabase::class.java, name)
                .openHelperFactory(DeferredSqlCipherOpenHelperFactory { config ->
                    assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                    opens.incrementAndGet()
                    FrameworkSQLiteOpenHelperFactory().create(config)
                }).build().also {
                    assertFalse(it.inTransaction())
                    assertEquals(0, opens.get())
                }
        }
        try {
            withContext(Dispatchers.Main) { assertTrue(database.memoryDao().getAllMemoriesFlow().first().isEmpty()) }
            assertEquals(1, opens.get())
            withContext(Dispatchers.Main) {
                assertFalse(database.inTransaction())
                assertTrue(database.openHelper.writableDatabase.isOpen)
            }
            assertEquals(1, opens.get())
        } finally {
            withContext(Dispatchers.IO) { database.close(); context.deleteDatabase(name) }
        }
    }
}
