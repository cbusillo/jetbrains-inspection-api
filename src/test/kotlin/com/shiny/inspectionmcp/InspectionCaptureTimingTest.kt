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

    @Test
    fun `established exact proof on file scopes settles findings sooner than other runs`() {
        val proven = resultSettlingWindow("files", exactProofEstablished = true, contextExtractionSucceeded = true)
        val unproven = listOf(
            resultSettlingWindow("files", exactProofEstablished = false, contextExtractionSucceeded = true),
            resultSettlingWindow("changed_files", exactProofEstablished = true, contextExtractionSucceeded = false),
            resultSettlingWindow("current_file", exactProofEstablished = true, contextExtractionSucceeded = true),
            resultSettlingWindow("whole_project", exactProofEstablished = true, contextExtractionSucceeded = true),
        )
        val elapsedMs = proven.minResultsWaitMs

        assertTrue(stopsWithFindings(proven, elapsedMs))
        assertEquals(proven, resultSettlingWindow(" CHANGED_FILES ", exactProofEstablished = true, contextExtractionSucceeded = true))
        unproven.forEach { window -> assertFalse(stopsWithFindings(window, elapsedMs)) }
    }

    @Test
    fun `established clean exact proof on file scopes trusts empty results sooner than other runs`() {
        val proven = resultSettlingWindow("changed_files", exactProofEstablished = true, contextExtractionSucceeded = true)
        val unproven = resultSettlingWindow("changed_files", exactProofEstablished = false, contextExtractionSucceeded = true)
        val elapsedMs = proven.minCleanPollingMs

        assertTrue(canTrustEmpty(elapsedMs, proven.minCleanPollingMs))
        assertFalse(canTrustEmpty(elapsedMs, unproven.minCleanPollingMs))
    }

    private fun stopsWithFindings(window: ResultSettlingWindow, pollingElapsedMs: Long): Boolean = shouldStopCapturePolling(
        bestResultsCount = 1,
        stableForMs = pollingElapsedMs,
        pollingElapsedMs = pollingElapsedMs,
        minResultsWaitMs = window.minResultsWaitMs,
    )

    private fun canTrustEmpty(pollingElapsedMs: Long): Boolean = shouldTrustStableScopedEmptyResults(
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

    private fun canTrustEmpty(pollingElapsedMs: Long, minPollingMs: Long): Boolean = shouldTrustStableScopedEmptyResults(
        hasExecutionProofCleanEvidence = true,
        executionProofMode = InspectionExecutionProofMode.EXACT_BOUNDED,
        modelVerdict = InspectionModelVerdict.CLEAN,
        hasScopedMatcher = true,
        scopedContextResultsEmpty = true,
        bestResultsEmpty = true,
        observedNonEmptyInspectionTree = false,
        stableForMs = pollingElapsedMs,
        pollingElapsedMs = pollingElapsedMs,
        minPollingMs = minPollingMs,
    )
}
