package com.jarvis.assistant.emergency

import org.junit.Assert.*
import org.junit.Test

class FallCandidateDetectorTest {
    @Test fun impactAloneOrWalkingDoesNotTrigger() {
        val d=FallCandidateDetector()
        for(t in 0L..10000L step 100) assertFalse(d.sample(t,if(t==1000L) 35f else 9.8f))
    }
    @Test fun fallImpactAndStillnessProduceOneCandidate() {
        val d=FallCandidateDetector()
        assertFalse(d.sample(1000,1f));assertFalse(d.sample(1300,34f))
        assertFalse(d.sample(1500,9.8f));assertFalse(d.sample(3000,9.8f))
        assertTrue(d.sample(4000,9.8f));assertFalse(d.sample(5000,9.8f))
    }
    @Test fun continuingMovementOrLateImpactDoesNotTrigger() {
        val d=FallCandidateDetector()
        d.sample(1000,1f);d.sample(1300,34f)
        for(t in 1500L..10000L step 100) assertFalse(d.sample(t,17f))
        d.sample(11000,1f);d.sample(14000,34f)
        for(t in 15000L..20000L step 100) assertFalse(d.sample(t,9.8f))
    }
}
