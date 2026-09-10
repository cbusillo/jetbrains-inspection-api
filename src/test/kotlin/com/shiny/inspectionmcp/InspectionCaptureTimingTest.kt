package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectionCaptureTimingTest {
    @Test
    fun `slow proof does not substitute for clean observation or extend capture budget`() {
        val timing = InspectionCaptureTiming(captureStartedMs = 1_000L, settlingStartedMs = 56_000L)
        assertEquals(61_000L, timing.deadlineMs)
        assertEquals(5_000L, timing.pollingElapsedMs(61_000L))
        assertEquals(60_000L, timing.captureElapsedMs(61_000L))
        assertFalse(timing.hasBudget(61_000L))
        assertFalse(canTrustEmpty(timing.pollingElapsedMs(61_000L)))
        assertTrue(canTrustEmpty(timing.captureElapsedMs(61_000L)))
    }

    @Test
    fun `fast proof retains full observation gate within original budget`() {
        val timing = InspectionCaptureTiming(captureStartedMs = 1_000L, settlingStartedMs = 2_000L)
        assertTrue(timing.hasBudget(32_000L))
        assertFalse(canTrustEmpty(timing.pollingElapsedMs(31_999L)))
        assertTrue(canTrustEmpty(timing.pollingElapsedMs(32_000L)))
    }

    private fun canTrustEmpty(pollingElapsedMs: Long): Boolean = shouldTrustStableScopedEmptyResults(
        viewReadyOk = false,
        hasExecutionProofCleanEvidence = true,
        executionProofMode = InspectionExecutionProofMode.EXACT_BOUNDED,
        modelVerdict = InspectionModelVerdict.CLEAN,
        hasScopedMatcher = true,
        scopedContextResultsEmpty = true,
        bestResultsEmpty = true,
        observedNonEmptyInspectionTree = false,
        stableForMs = 5_000L,
        pollingElapsedMs = pollingElapsedMs,
    )
}
