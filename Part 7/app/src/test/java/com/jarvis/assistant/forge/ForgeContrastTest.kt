package com.jarvis.assistant.forge

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ForgeContrastTest {

    private fun parseHexColor(hex: String): Triple<Double, Double, Double> {
        val clean = hex.removePrefix("#")
        val r = clean.substring(0, 2).toInt(16) / 255.0
        val g = clean.substring(2, 4).toInt(16) / 255.0
        val b = clean.substring(4, 6).toInt(16) / 255.0
        return Triple(r, g, b)
    }

    private fun channelLuminance(c: Double): Double {
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(hex: String): Double {
        val (r, g, b) = parseHexColor(hex)
        return 0.2126 * channelLuminance(r) + 0.7152 * channelLuminance(g) + 0.0722 * channelLuminance(b)
    }

    private fun contrastRatio(colorA: String, colorB: String): Double {
        val l1 = relativeLuminance(colorA)
        val l2 = relativeLuminance(colorB)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun testForgeBackgroundContrastRatios_exceedWcagThreshold() {
        val forgeBackground = "#070709"

        // 1. Stage title & percent text (#EEE9FF)
        val stageLabelContrast = contrastRatio("#EEE9FF", forgeBackground)
        assertTrue(
            "Stage label contrast $stageLabelContrast must be >= 4.5:1",
            stageLabelContrast >= 4.5
        )

        // 2. Stage description & error text (#CBD5E1)
        val stageDescContrast = contrastRatio("#CBD5E1", forgeBackground)
        assertTrue(
            "Stage description contrast $stageDescContrast must be >= 4.5:1",
            stageDescContrast >= 4.5
        )

        // 3. Button white text (#FFFFFF)
        val buttonTextContrast = contrastRatio("#FFFFFF", forgeBackground)
        assertTrue(
            "Button text contrast $buttonTextContrast must be >= 4.5:1",
            buttonTextContrast >= 4.5
        )

        // 4. Accent cyan prompt chip (#06B6D4)
        val promptChipContrast = contrastRatio("#06B6D4", forgeBackground)
        assertTrue(
            "Prompt chip contrast $promptChipContrast must be >= 4.5:1",
            promptChipContrast >= 4.5
        )
    }
}
