package com.jarvis.assistant.automation.dev
import org.junit.Assert.*
import org.junit.Test
class ShellPolicyTest {
    @Test fun diagnosticAllowlistDoesNotAcceptShellSuffixes() {
        assertEquals(ShellPolicy.Decision.ALLOW,ShellPolicy.evaluate("git --version"))
        assertEquals(ShellPolicy.Decision.REQUIRE_REVIEW,ShellPolicy.evaluate("pwd; curl example.com"))
        assertEquals(ShellPolicy.Decision.REQUIRE_REVIEW,ShellPolicy.evaluate("pwd\nwhoami"))
    }
    @Test fun modelTextCannotApproveItselfAndChangedCommandLosesReview() {
        assertEquals(ShellPolicy.Decision.REQUIRE_REVIEW,ShellPolicy.evaluate("./gradlew test"))
        assertEquals(ShellPolicy.Decision.ALLOW,ShellPolicy.evaluate("./gradlew test","./gradlew test"))
        assertEquals(ShellPolicy.Decision.REQUIRE_REVIEW,ShellPolicy.evaluate("./gradlew test; other","./gradlew test"))
        assertEquals(ShellPolicy.Decision.REJECT,ShellPolicy.evaluate("bad\u0000","bad\u0000"))
    }
    @Test fun workspaceIsOneQuotedArgument() {
        assertEquals("'a'\\''; whoami; '",ShellPolicy.quoteArgument("a'; whoami; "))
    }
    @Test fun officialBundleSuccessUsesMinusOneInternalError() {
        val r=TermuxResultDecoder.decode(0,-1,"")
        assertEquals(0,r.error);assertEquals(0,r.exit)
    }
    @Test fun missingExitCodeCannotBecomeSuccess() {
        assertNotEquals(0,TermuxResultDecoder.decode(null,null,"").error)
        assertNotEquals(0,TermuxResultDecoder.decode(null,-1,"").error)
        assertNotEquals(0,TermuxResultDecoder.decode(null,0,"",true).error)
    }
    @Test fun internalFailureAndLegacyRepliesAreDistinct() {
        assertNotEquals(0,TermuxResultDecoder.decode(null,5,"Execution failed").error)
        assertNotEquals(0,TermuxResultDecoder.decode(0,0,"").error)
        assertEquals(0,TermuxResultDecoder.decode(0,0,"",true).error)
        assertEquals(7,TermuxResultDecoder.decode(7,-1,"").exit)
    }
}
