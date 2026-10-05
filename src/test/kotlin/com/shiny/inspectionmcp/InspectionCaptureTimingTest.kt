package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectionCaptureTimingTest {
    @Test
    fun `capture deadline remains anchored while polling starts after proof`() {
        val captureStartedMs = 1_000L
        val deadline = InspectionCaptureTiming(captureStartedMs, captureStartedMs).deadlineMs
        val pollingDurationMs = 5L
        val timing = InspectionCaptureTiming(captureStartedMs, deadline - pollingDurationMs)

        assertEquals(deadline, timing.deadlineMs)
        assertEquals(pollingDurationMs, timing.pollingElapsedMs(deadline))
        assertEquals(deadline - captureStartedMs, timing.captureElapsedMs(deadline))
        assertFalse(timing.hasBudget(deadline))
        assertTrue(timing.hasBudget(deadline - 1))
        val minimumPollingMs = 10L
        assertFalse(canTrustEmpty(timing.pollingElapsedMs(deadline), minimumPollingMs))
        assertTrue(canTrustEmpty(timing.captureElapsedMs(deadline), minimumPollingMs))
    }

    @Test
    fun `fast proof retains full observation gate within original budget`() {
        val settlingStartedMs = 2_000L
        val timing = InspectionCaptureTiming(captureStartedMs = 1_000L, settlingStartedMs = settlingStartedMs)
        val minimumPollingMs = resultSettlingWindow(
            "directory", exactProofEstablished = false, contextExtractionComplete = true,
        ).minCleanPollingMs
        val readyAtMs = settlingStartedMs + minimumPollingMs

        assertTrue(timing.hasBudget(readyAtMs))
        assertFalse(canTrustEmpty(timing.pollingElapsedMs(readyAtMs - 1), minimumPollingMs))
        assertTrue(canTrustEmpty(timing.pollingElapsedMs(readyAtMs), minimumPollingMs))
    }

    @Test
    fun `window for established exact proof on file scopes stops polling findings sooner than other windows`() {
        val proven = resultSettlingWindow("files", exactProofEstablished = true, contextExtractionComplete = true)
        val unproven = listOf(
            resultSettlingWindow("files", exactProofEstablished = false, contextExtractionComplete = true),
            resultSettlingWindow("changed_files", exactProofEstablished = true, contextExtractionComplete = false),
            resultSettlingWindow("current_file", exactProofEstablished = true, contextExtractionComplete = true),
            resultSettlingWindow("whole_project", exactProofEstablished = true, contextExtractionComplete = true),
        )
        val elapsedMs = proven.minResultsWaitMs

        assertTrue(stopsWithFindings(proven, elapsedMs))
        assertEquals(proven, resultSettlingWindow(" CHANGED_FILES ", exactProofEstablished = true, contextExtractionComplete = true))
        unproven.forEach { window -> assertFalse(stopsWithFindings(window, elapsedMs)) }
    }

    @Test
    fun `window for established exact proof on file scopes trusts empty results sooner than other windows`() {
        val proven = resultSettlingWindow("changed_files", exactProofEstablished = true, contextExtractionComplete = true)
        val unproven = resultSettlingWindow("changed_files", exactProofEstablished = false, contextExtractionComplete = true)
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
        minStableMs = minPollingMs,
        minPollingMs = minPollingMs,
    )
}
