package com.jarvis.assistant.forge

import com.jarvis.assistant.automation.mission.MissionRisk
import com.jarvis.assistant.automation.mission.ToolRegistry
import com.jarvis.assistant.automation.mission.ToolValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteForgeTest {

    // --- 1. SSE Parser Tests ---

    @Test
    fun testSseParser_extractTextSinglePart() {
        val sseData = """data: {"candidates":[{"content":{"parts":[{"text":"<!DOCTYPE html><html>"}]}}]}"""
        val text = SseParser.extractText(sseData)
        assertEquals("<!DOCTYPE html><html>", text)
    }

    @Test
    fun testSseParser_extractTextMultiPart() {
        val sseData = """{"candidates":[{"content":{"parts":[{"text":"<head>"},{"text":"<title>Test</title></head>"}]}}]}"""
        val text = SseParser.extractText(sseData)
        assertEquals("<head><title>Test</title></head>", text)
    }

    @Test
    fun testSseParser_extractError() {
        val errData = """{"error":{"code":403,"message":"API key expired","status":"PERMISSION_DENIED"}}"""
        val err = SseParser.extractError(errData)
        assertEquals("API key expired", err)
    }

    @Test
    fun testSseParser_extractFinishReason() {
        val data = """{"candidates":[{"content":{"parts":[{"text":"</html>"}]},"finishReason":"STOP"}]}"""
        val finish = SseParser.extractFinishReason(data)
        assertEquals("STOP", finish)
    }

    @Test
    fun testSseParser_isDone() {
        assertTrue(SseParser.isDone("[DONE]"))
        assertTrue(SseParser.isDone("data: [DONE]"))
        assertTrue(SseParser.isDone("data:[DONE]"))
        assertFalse(SseParser.isDone("data: {\"candidates\":[]}"))
    }

    // --- 2. HTML Utilities Tests ---

    @Test
    fun testForgeHtml_stripFences() {
        val fenced = """```html
<!DOCTYPE html>
<html>
<body><h1>Hello</h1></body>
</html>
```"""
        val stripped = ForgeHtml.stripFences(fenced)
        assertTrue(stripped.startsWith("<!DOCTYPE html>"))
        assertTrue(stripped.endsWith("</html>"))
        assertFalse(stripped.contains("```"))
    }

    @Test
    fun testForgeHtml_looksLikeHtml() {
        assertTrue(ForgeHtml.looksLikeHtml("<!DOCTYPE html><html><body></body></html>"))
        assertTrue(ForgeHtml.looksLikeHtml("<section id='main'>Hello</section>"))
        assertFalse(ForgeHtml.looksLikeHtml("This is just a regular sentence without html markup."))
        assertFalse(ForgeHtml.looksLikeHtml(""))
    }

    @Test
    fun testForgeHtml_repairTruncatedUnderThreshold() {
        val shortTruncated = "<!DOCTYPE html><html><body><script>const x = 1;"
        val repaired = ForgeHtml.repairTruncated(shortTruncated)
        // Under 14000 characters, returns as-is without faking full page
        assertEquals(shortTruncated, repaired)
    }

    @Test
    fun testForgeHtml_repairTruncatedOverThreshold() {
        val sb = StringBuilder("<!DOCTYPE html><html><body><script>console.log('long');")
        while (sb.length < 14500) {
            sb.append("\n<p>Repeating filler content to simulate large streamed page.</p>")
        }
        val raw = sb.toString()
        val repaired = ForgeHtml.repairTruncated(raw)
        assertTrue(repaired.contains("</script>"))
        assertTrue(repaired.contains("</body>"))
        assertTrue(repaired.contains("</html>"))
    }

    @Test
    fun testForgeHtml_safetyNetIdempotency() {
        val initialHtml = "<!DOCTYPE html><html><body><h1>Title</h1></body></html>"
        val withNet1 = ForgeHtml.withSafetyNet(initialHtml)
        assertTrue(withNet1.contains("__forgeSafetyNetInstalled"))

        // Applying second time should be idempotent (no duplicate script)
        val withNet2 = ForgeHtml.withSafetyNet(withNet1)
        assertEquals(withNet1, withNet2)
    }

    // --- 3. Stage Detector Tests ---

    @Test
    fun testForgeStageDetector_blueprintForHeadOnly() {
        val headOnly = "<!DOCTYPE html><html><head><title>Luxury Watch</title><meta name='viewport'></head>"
        val stage = ForgeStageDetector.detect(headOnly)
        assertEquals(ForgeStage.BLUEPRINT, stage)
    }

    @Test
    fun testForgeStageDetector_stagedProgression() {
        val s1 = "<!DOCTYPE html><html><head></head>"
        assertEquals(ForgeStage.BLUEPRINT, ForgeStageDetector.detect(s1))

        val s2 = "$s1<body><nav>Home</nav>"
        assertEquals(ForgeStage.STRUCTURE, ForgeStageDetector.detect(s2))

        val s3 = "$s2<style>body { background: #000; }</style>"
        assertEquals(ForgeStage.STYLING, ForgeStageDetector.detect(s3))

        val s4 = "$s3<script src='gsap.min.js'></script>"
        assertEquals(ForgeStage.MOTION, ForgeStageDetector.detect(s4))

        val s5 = "$s4<canvas id='heroCanvas'></canvas>"
        assertEquals(ForgeStage.VISUALS, ForgeStageDetector.detect(s5))

        val s6 = "$s5</body></html>"
        assertEquals(ForgeStage.FINALIZING, ForgeStageDetector.detect(s6))
    }

    @Test
    fun testForgeStageDetector_canvasCountsAsVisuals() {
        val markup = "<html><body><main><canvas id='starfield'></canvas></main></body>"
        val stage = ForgeStageDetector.detect(markup)
        assertEquals(ForgeStage.VISUALS, stage)
    }

    @Test
    fun testForgeStageDetector_monotonicityNeverRegresses() {
        val previous = ForgeStage.MOTION
        // Even if markup only matches structure markers, previous stage MOTION prevents regression
        val partial = "<html><body><section>New section</section>"
        val stage = ForgeStageDetector.detect(partial, previousStage = previous)
        assertEquals(ForgeStage.MOTION, stage)
    }

    @Test
    fun testForgeStageDetector_sectionExtraction() {
        val html = """
            <html><body>
                <section id="hero-banner"><h1>Exquisite Coffee</h1></section>
                <section aria-label="Our Special Beans"><p>Details</p></section>
                <section id="testimonials-carousel"><h3>Happy Patrons</h3></section>
            </body></html>
        """.trimIndent()

        val sections = ForgeStageDetector.extractSections(html)
        assertTrue(sections.contains("Exquisite Coffee"))
        assertTrue(sections.contains("Our Special Beans"))
        assertTrue(sections.contains("Happy Patrons"))
    }

    // --- 4. Intent Recognition Tests ---

    @Test
    fun testForgeIntent_matchesCreationCommands() {
        assertTrue(ForgeIntent.matches("build a website for my coffee shop"))
        assertTrue(ForgeIntent.matches("create a landing page for my mobile app"))
        assertTrue(ForgeIntent.matches("make a portfolio website"))
        assertTrue(ForgeIntent.matches("ek portfolio website banao"))
        assertTrue(ForgeIntent.matches("landing page bana"))
        assertTrue(ForgeIntent.matches("design a luxury web app landing page"))
    }

    @Test
    fun testForgeIntent_ignoresQuestionsAndUnrelated() {
        assertFalse(ForgeIntent.matches("what is a website?"))
        assertFalse(ForgeIntent.matches("how to make a website"))
        assertFalse(ForgeIntent.matches("can you explain how websites work?"))
        assertFalse(ForgeIntent.matches("search for coffee shops near me"))
        assertFalse(ForgeIntent.matches("open chrome"))
        assertFalse(ForgeIntent.matches(""))
    }

    @Test
    fun testForgeIntent_extractPrompt() {
        val extracted1 = ForgeIntent.extractPrompt("Jarvis, please build a website for a luxury car rental service")
        assertEquals("luxury car rental service", extracted1)

        val extracted2 = ForgeIntent.extractPrompt("create a landing page for AI productivity tools")
        assertEquals("AI productivity tools", extracted2)
    }

    // --- 5. Tool Registry Integration Tests ---

    @Test
    fun testToolRegistry_buildWebsiteRegisteredWithLowRisk() {
        val tool = ToolRegistry.getTool("build_website")
        assertNotNull("build_website tool must be registered in ToolRegistry", tool)
        assertEquals(MissionRisk.LOW, tool!!.riskLevel)
        assertFalse("Website building is a stateful action", tool.isIdempotent)

        val promptParam = tool.parameters.firstOrNull { it.name == "prompt" }
        assertNotNull(promptParam)
        assertTrue(promptParam!!.required)

        val modeParam = tool.parameters.firstOrNull { it.name == "mode" }
        assertNotNull(modeParam)
        assertFalse(modeParam!!.required)
    }

    @Test
    fun testToolRegistry_buildWebsiteValidation() {
        val invalidRes = ToolRegistry.validate("build_website", emptyMap())
        assertTrue(invalidRes is ToolValidationResult.MissingParameters)

        val validRes = ToolRegistry.validate("build_website", mapOf("prompt" to "Art Gallery Showcase"))
        assertEquals(ToolValidationResult.Valid, validRes)
    }
}
