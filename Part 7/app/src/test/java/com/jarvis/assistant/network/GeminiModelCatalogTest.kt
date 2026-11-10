package com.jarvis.assistant.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GeminiModelCatalogTest {
    private val models = listOf(
        AvailableGeminiModel("models/test-flash-image", setOf("generateContent")),
        AvailableGeminiModel("models/test-flash", setOf("generateContent")),
        AvailableGeminiModel("models/test-pro", setOf("generateContent")),
        AvailableGeminiModel("models/test-live", setOf("bidiGenerateContent"))
    )

    @Test fun routesByAdvertisedMethodAndTask() {
        assertEquals("models/test-flash", GeminiModelSelection.choose(models, GeminiRequestKind.FAST_TEXT))
        assertEquals("models/test-pro", GeminiModelSelection.choose(models, GeminiRequestKind.REASONING))
        assertEquals("models/test-live", GeminiModelSelection.choose(models, GeminiRequestKind.LIVE_AUDIO))
    }

    @Test fun preservesAvailablePreferenceButRejectsIncompatibleOrUnadvertisedOne() {
        assertEquals("models/test-pro", GeminiModelSelection.choose(models, GeminiRequestKind.FAST_TEXT, "test-pro"))
        assertEquals("models/test-live", GeminiModelSelection.choose(models, GeminiRequestKind.LIVE_AUDIO, "models/test-pro"))
        assertEquals("models/test-flash", GeminiModelSelection.choose(models, GeminiRequestKind.FAST_TEXT, "models/invented-99"))
    }

    @Test(expected = IOException::class) fun neverUsesTextModelForLive() {
        GeminiModelSelection.choose(models.filter { it.name != "models/test-live" }, GeminiRequestKind.LIVE_AUDIO)
    }

    @Test(expected = IOException::class) fun modelNameCannotInjectAnEndpoint() {
        GeminiModelSelection.choose(listOf(AvailableGeminiModel("models/a/../../other?key=secret", setOf("generateContent"))), GeminiRequestKind.FAST_TEXT)
    }

    @Test fun paginationCacheTtlAndAccountIsolation() = runBlocking {
        var time = 0L
        val calls = mutableListOf<Pair<String, String?>>()
        val resolver = GeminiModelResolver({ key, token ->
            calls.add(key to token)
            if (token == null) """{"models":[],"nextPageToken":"page2"}"""
            else """{"models":[{"name":"models/test-flash","supportedGenerationMethods":["generateContent"]}]}"""
        }, now = { time }, ttlMs = 100)
        resolver.resolve("account-one", GeminiRequestKind.FAST_TEXT)
        resolver.resolve("account-one", GeminiRequestKind.FAST_TEXT)
        assertEquals(2, calls.size)
        resolver.resolve("account-two", GeminiRequestKind.FAST_TEXT)
        assertEquals(4, calls.size)
        time = 100
        resolver.resolve("account-one", GeminiRequestKind.FAST_TEXT)
        assertEquals(6, calls.size)
        assertEquals("page2", calls.last().second)
    }

    @Test fun cancelledDiscoveryDoesNotPoisonCacheOrLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val suspended = CompletableDeferred<String>()
        var attempt = 0
        val resolver = GeminiModelResolver({ _, _ ->
            if (attempt++ == 0) { entered.complete(Unit); suspended.await() }
            else """{"models":[{"name":"models/test-live","supportedGenerationMethods":["bidiGenerateContent"]}]}"""
        })
        val first = async { resolver.resolve("account", GeminiRequestKind.LIVE_AUDIO) }
        entered.await()
        first.cancelAndJoin()
        assertEquals("models/test-live", resolver.resolve("account", GeminiRequestKind.LIVE_AUDIO))
    }

    @Test(expected = IOException::class) fun repeatedPaginationFailsBoundedly() = runBlocking {
        GeminiModelResolver({ _, _ -> """{"models":[],"nextPageToken":"same"}""" })
            .resolve("account", GeminiRequestKind.FAST_TEXT)
        Unit
    }
}
