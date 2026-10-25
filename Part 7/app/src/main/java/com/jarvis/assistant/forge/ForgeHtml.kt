package com.jarvis.assistant.forge

import java.util.Locale

/**
 * Robust HTML sanitization, repair, and safety-net injection utilities for Website Forge.
 */
object ForgeHtml {

    private val FENCE_START_REGEX = Regex("""^\s*```(?:html|xml)?\s*\n?""", RegexOption.IGNORE_CASE)
    private val FENCE_END_REGEX = Regex("""\n?\s*```\s*$""")

    /**
     * Strips leading and trailing markdown code fences (` ```html ` and ` ``` `).
     */
    fun stripFences(raw: String): String {
        var cleaned = raw.trim()
        cleaned = FENCE_START_REGEX.replace(cleaned, "")
        cleaned = FENCE_END_REGEX.replace(cleaned, "")
        return cleaned.trim()
    }

    /**
     * Fast structural check to determine if the string looks like valid HTML markup.
     */
    fun looksLikeHtml(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        return lower.contains("<!doctype html") ||
                lower.contains("<html") ||
                lower.contains("<head") ||
                lower.contains("<body") ||
                lower.contains("<main") ||
                lower.contains("<section") ||
                lower.contains("<div")
    }

    /**
     * Repairs prematurely cut-off HTML streams if output reached >= 14000 characters.
     * Safely closes unclosed script, style, and body containers.
     */
    fun repairTruncated(truncatedHtml: String): String {
        var html = stripFences(truncatedHtml)
        if (html.length < 14000) {
            return html
        }

        val lower = html.lowercase(Locale.ROOT)

        // Close unclosed <script>
        val lastScriptOpen = lower.lastIndexOf("<script")
        val lastScriptClose = lower.lastIndexOf("</script>")
        if (lastScriptOpen != -1 && (lastScriptClose == -1 || lastScriptClose < lastScriptOpen)) {
            html += "\n</script>"
        }

        // Close unclosed <style>
        val lastStyleOpen = lower.lastIndexOf("<style")
        val lastStyleClose = lower.lastIndexOf("</style>")
        if (lastStyleOpen != -1 && (lastStyleClose == -1 || lastStyleClose < lastStyleOpen)) {
            html += "\n</style>"
        }

        // Close body and html if missing
        val lowerUpdated = html.lowercase(Locale.ROOT)
        if (!lowerUpdated.contains("</body>")) {
            html += "\n</body>"
        }
        if (!lowerUpdated.contains("</html>")) {
            html += "\n</html>"
        }

        return html
    }

    /**
     * Idempotent injected safety net script that detects and reveals any content
     * accidentally left hidden (opacity 0) by unplayed animation hooks after 1.4 seconds.
     * Skips fixed overlays, pointer-events:none, aria-hidden, and canvas elements.
     */
    fun withSafetyNet(html: String): String {
        if (html.contains("__forgeSafetyNetInstalled")) {
            return html
        }

        val safetyScript = """
<script>
(function() {
  if (window.__forgeSafetyNetInstalled) return;
  window.__forgeSafetyNetInstalled = true;
  window.addEventListener('DOMContentLoaded', function() {
    setTimeout(function() {
      var elements = document.querySelectorAll('body *:not(canvas):not([aria-hidden="true"])');
      for (var i = 0; i < elements.length; i++) {
        var el = elements[i];
        var style = window.getComputedStyle(el);
        if (style.position === 'fixed' || style.pointerEvents === 'none') continue;
        if (parseFloat(style.opacity) < 0.05) {
          el.style.transition = 'opacity 0.4s ease-in';
          el.style.opacity = '1';
        }
      }
    }, 1400);
  });
})();
</script>
""".trimIndent()

        return if (html.contains("</body>", ignoreCase = true)) {
            val idx = html.lastIndexOf("</body>", ignoreCase = true)
            html.substring(0, idx) + "\n" + safetyScript + "\n" + html.substring(idx)
        } else {
            html + "\n" + safetyScript
        }
    }
}
