package com.jarvis.assistant.automation.accessibility
import org.junit.Assert.*
import org.junit.Test
class AccessibilityWindowPolicyTest {
    private fun app(id:Int,focus:Boolean=false,active:Boolean=false,own:Boolean=false)=AccessibilityWindowCandidate(id,true,false,focus,active,own)
    @Test fun inactiveApplicationIsNeverSelected(){assertNull(AccessibilityWindowPolicy.select(listOf(app(1)),true))}
    @Test fun keyboardDoesNotHideActiveApplication(){
        val keyboard=AccessibilityWindowCandidate(2,false,false,true,false,false)
        assertEquals(1,AccessibilityWindowPolicy.select(listOf(app(1,active=true),keyboard),true))
    }
    @Test fun focusedDialogAndSplitScreenUseCurrentWindow(){
        assertEquals(2,AccessibilityWindowPolicy.select(listOf(app(1,active=true),app(2,focus=true)),true))
    }
    @Test fun ownOverlayCannotSelectAnUnfocusedBackgroundApp(){
        assertNull(AccessibilityWindowPolicy.select(listOf(app(1,focus=true,own=true),app(2)),true))
    }
}
