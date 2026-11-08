package com.jarvis.assistant.ui.orb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Security regression tests for the orb WebViews (audit finding F2). */
class OrbWebViewSecurityTest {

    @Test
    fun onlyBundledAssetsOnTheAssetOriginAreAllowed() {
        assertTrue(OrbWebViewSecurity.isLocalAssetUrl("https", OrbWebViewSecurity.ASSET_HOST, "/assets/orb.html"))
        assertTrue(OrbWebViewSecurity.isLocalAssetUrl("https", OrbWebViewSecurity.ASSET_HOST, "/assets/hud/index.html"))
        assertTrue(OrbWebViewSecurity.isLocalAssetUrl("https", OrbWebViewSecurity.ASSET_HOST, "/assets/three.min.js"))
    }

    @Test
    fun everythingElseIsRejected() {
        val host = OrbWebViewSecurity.ASSET_HOST
        assertFalse("file scheme", OrbWebViewSecurity.isLocalAssetUrl("file", "", "/android_asset/orb.html"))
        assertFalse("plain http", OrbWebViewSecurity.isLocalAssetUrl("http", host, "/assets/orb.html"))
        assertFalse("other host", OrbWebViewSecurity.isLocalAssetUrl("https", "evil.example.com", "/assets/orb.html"))
        assertFalse("look-alike host", OrbWebViewSecurity.isLocalAssetUrl("https", "$host.evil.example.com", "/assets/orb.html"))
        assertFalse("outside /assets/", OrbWebViewSecurity.isLocalAssetUrl("https", host, "/res/raw/secret"))
        assertFalse("missing path", OrbWebViewSecurity.isLocalAssetUrl("https", host, null))
        assertFalse("missing scheme", OrbWebViewSecurity.isLocalAssetUrl(null, host, "/assets/orb.html"))
        assertFalse("javascript scheme", OrbWebViewSecurity.isLocalAssetUrl("javascript", null, null))
    }

    @Test
    fun entryUrlsAreLocalAssetUrls() {
        listOf(OrbWebViewSecurity.ORB_URL, OrbWebViewSecurity.HUD_URL).forEach { url ->
            val withoutQuery = url.substringBefore('?')
            val host = withoutQuery.removePrefix("https://").substringBefore('/')
            val path = "/" + withoutQuery.removePrefix("https://").substringAfter('/')
            assertTrue(url, url.startsWith("https://"))
            assertTrue(url, OrbWebViewSecurity.isLocalAssetUrl("https", host, path))
        }
    }

    @Test
    fun orbWebViewSourcesNeverReEnableFileAccessOrLoadFileUrls() {
        val sources = listOf(
            "src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt",
            "src/main/java/com/jarvis/assistant/ui/orb/OrbHelper.kt",
            "src/main/java/com/jarvis/assistant/ui/orb/OrbWebViewSecurity.kt"
        ).map(::File)
        assumeTrue("module sources not reachable from the test working directory", sources.all { it.isFile })

        sources.forEach { file ->
            val text = file.readText()
            assertFalse("${file.name} enables universal file access", Regex("allowUniversalAccessFromFileURLs\\s*=\\s*true").containsMatchIn(text))
            assertFalse("${file.name} enables file-to-file access", Regex("allowFileAccessFromFileURLs\\s*=\\s*true").containsMatchIn(text))
            assertFalse("${file.name} enables file access", Regex("allowFileAccess\\s*=\\s*true").containsMatchIn(text))
            assertFalse("${file.name} loads a file:// URL", text.contains("file:///android_asset"))
        }
    }
}
