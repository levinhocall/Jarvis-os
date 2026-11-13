package com.jarvis.assistant.automation.accessibility
import org.junit.Assert.*
import org.junit.Test
class AccessibilityTraversalBudgetTest {
    @Test fun depthAndFanoutShareOneBudget() {
        val budget=AccessibilityTraversalBudget(3,2,100){0}
        assertTrue(budget.enter());assertTrue(budget.enter());assertFalse(budget.enter())
        budget.leave();assertTrue(budget.enter());budget.leave();budget.leave()
        assertTrue(budget.exhausted());assertFalse(budget.enter())
    }
    @Test fun timeBudgetExpiresWithoutWallClock() {
        var now=5L;val budget=AccessibilityTraversalBudget(256,48,40){now}
        assertTrue(budget.enter());now=50;assertTrue(budget.exhausted());budget.leave()
        assertFalse(budget.enter())
    }
    @Test fun changedOrUnknownInputCannotReuseAnOldTarget() {
        val p=AccessibilityInputTargetPolicy
        assertTrue(p.sameTarget("app","app",1,1,"input","input",false))
        assertFalse(p.sameTarget("app","other",1,1,"input","input",true))
        assertFalse(p.sameTarget("app","app",1,2,"input","input",true))
        assertFalse(p.sameTarget("app","app",1,1,"input","password",true))
        assertFalse(p.sameTarget("app","app",1,1,null,null,false))
        assertTrue(p.sameTarget("app","app",1,1,null,null,true))
    }
}
