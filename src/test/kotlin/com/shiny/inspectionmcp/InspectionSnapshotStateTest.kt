package com.shiny.inspectionmcp

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.openapi.util.ThrowableComputable
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class InspectionSnapshotStateTest {

    private lateinit var handler: InspectionHandler
    private lateinit var mockProject: Project

    @BeforeEach
    fun setup() {
        handler = InspectionHandler()
        mockProject = mockk<Project>()
        val mockProjectManager = mockk<ProjectManager>()
        val mockDumbService = mockk<DumbService>()
        val mockToolWindowManager = mockk<ToolWindowManager>()
        val mockFileDocumentManager = mockk<FileDocumentManager>()
        val mockPsiModificationTracker = mockk<PsiModificationTracker>()
        val mockApplication = mockk<Application>()

        every { mockProject.isDefault } returns false
        every { mockProject.isDisposed } returns false
        every { mockProject.isInitialized } returns true
        every { mockProject.name } returns "TestProject"
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/TestProject.iml"

        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns arrayOf(mockProject)

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.runReadAction(any<ThrowableComputable<Any, Exception>>()) } answers {
            firstArg<ThrowableComputable<Any, Exception>>().compute()
        }

        mockkStatic(DumbService::class)
        every { DumbService.getInstance(mockProject) } returns mockDumbService
        every { mockDumbService.isDumb } returns false

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(mockProject) } returns mockToolWindowManager
        every { mockToolWindowManager.getToolWindow(any()) } returns null

        mockkStatic(FileDocumentManager::class)
        every { FileDocumentManager.getInstance() } returns mockFileDocumentManager
        every { mockFileDocumentManager.unsavedDocuments } returns emptyArray<Document>()

        mockkStatic(PsiModificationTracker::class)
        every { PsiModificationTracker.getInstance(mockProject) } returns mockPsiModificationTracker
        every { mockPsiModificationTracker.modificationCount } returns 7L

        setLastInspectionTriggerTime(System.currentTimeMillis())
        InspectionResultsStore.clear(snapshotKey())
        enhancedTreeExtractorFactory = { EnhancedTreeExtractor() }
    }

    @AfterEach
    fun tearDown() {
        InspectionResultsStore.clear(snapshotKey())
        unmockkAll()
    }

    @Test
    @DisplayName("Empty capture snapshot is not reported as a clean inspection")
    fun testCaptureIncompleteSnapshotDoesNotLookClean() {
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CAPTURE_INCOMPLETE,
                source = "inspection_view",
                note = "capture note",
            ),
        )

        val status = buildInspectionStatus()

        assertEquals("capture_incomplete", status["snapshot_outcome"])
        assertEquals("capture note", status["snapshot_note"])
        assertEquals(true, status["capture_incomplete"])
        assertFalse(status["clean_inspection"] as Boolean)
        assertFalse(status["has_inspection_results"] as Boolean)
    }

    @Test
    @DisplayName("Confirmed clean snapshot is the only zero-problem state reported as clean")
    fun testCleanSnapshotRequiresConfirmedOutcome() {
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
            ),
        )

        val status = buildInspectionStatus()

        assertEquals("clean_confirmed", status["snapshot_outcome"])
        assertEquals(true, status["clean_inspection"])
        assertEquals(true, status["has_inspection_results"])
        assertEquals(false, status["capture_incomplete"])
    }

    @Test
    @DisplayName("Confirmed clean snapshot remains clean after the recent trigger window")
    fun testCleanSnapshotDoesNotExpireAfterRecentTriggerWindow() {
        setLastInspectionTriggerTime(System.currentTimeMillis() - 120000L)
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis() - 120000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
            ),
        )

        val status = buildInspectionStatus()

        assertEquals(true, status["clean_inspection"])
        assertEquals(true, status["has_inspection_results"])
        assertEquals("clean_confirmed", status["snapshot_outcome"])
    }

    @Test
    @DisplayName("Snapshots are isolated for same-name projects with different paths")
    fun testSnapshotsUseStableProjectKeysForSameNameProjects() {
        val otherProject = mockk<Project>()
        every { otherProject.name } returns "TestProject"
        every { otherProject.basePath } returns "/tmp/OtherTestProject"
        every { otherProject.projectFilePath } returns "/tmp/OtherTestProject/.idea/TestProject.iml"

        InspectionResultsStore.setSnapshot(
            projectKey(otherProject),
            InspectionResultsSnapshot(
                problems = listOf(
                    mapOf(
                        "description" to "Other project warning",
                        "file" to "/tmp/OtherTestProject/src/app.js",
                        "line" to 12,
                        "column" to 4,
                        "severity" to "weak_warning",
                        "inspectionType" to "JSUnresolvedReference",
                    )
                ),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
            ),
        )
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
            ),
        )

        val status = buildInspectionStatus()

        assertEquals(true, status["clean_inspection"])
        assertEquals(0, status["total_problems"])
        assertEquals(snapshotKey(), status["project_key"])
    }

    @Test
    @DisplayName("Older run completion does not clear a newer run")
    fun testOlderRunCannotFinishNewerRun() {
        val firstRun = beginInspectionRun()
        val secondRun = beginInspectionRun()

        finishInspectionRun(snapshotKey(), firstRun.runId)
        val statusAfterOldFinish = buildInspectionStatus()

        assertEquals(true, statusAfterOldFinish["inspection_in_progress"])
        assertEquals(secondRun.runId, statusAfterOldFinish["inspection_run_id"])

        finishInspectionRun(snapshotKey(), secondRun.runId)
        val statusAfterCurrentFinish = buildInspectionStatus()

        assertEquals(false, statusAfterCurrentFinish["inspection_in_progress"])
        assertEquals(secondRun.runId, statusAfterCurrentFinish["inspection_run_id"])
    }

    @Test
    @DisplayName("Clean snapshot is reconciled when live Problems view still has findings")
    fun testCleanSnapshotReconcilesWithLiveProblems() {
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblems(mockProject) } returns listOf(
            mapOf(
                "description" to "Live warning",
                "file" to "/tmp/TestProject/src/app.js",
                "line" to 12,
                "column" to 4,
                "severity" to "weak_warning",
                "inspectionType" to "JSUnresolvedReference",
            )
        )
        enhancedTreeExtractorFactory = { extractor }

        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
            ),
        )

        val status = buildInspectionStatus()

        assertEquals(false, status["clean_inspection"])
        assertEquals(true, status["has_inspection_results"])
        assertEquals(1, status["total_problems"])
        assertEquals("problems_found", status["snapshot_outcome"])
        assertEquals("tool_window", status["results_source"])
    }

    @Test
    @DisplayName("Problems endpoint reports capture incomplete instead of fake empty results")
    fun testProblemsEndpointReportsCaptureIncomplete() {
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CAPTURE_INCOMPLETE,
                source = "inspection_view",
                note = "capture note",
            ),
        )

        val response = getInspectionProblems()

        assertTrue(response.contains("\"status\": \"capture_incomplete\""))
        assertTrue(response.contains("\"results_may_be_incomplete\": true"))
        assertTrue(response.contains("\"snapshot_outcome\": \"capture_incomplete\""))
        assertFalse(response.contains("\"status\": \"results_available\""))
    }

    @Test
    @DisplayName("Problems endpoint returns live findings when clean snapshot is contradicted")
    fun testProblemsEndpointReconcilesCleanSnapshot() {
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblems(mockProject) } returns listOf(
            mapOf(
                "description" to "Live warning",
                "file" to "/tmp/TestProject/src/app.js",
                "line" to 12,
                "column" to 4,
                "severity" to "weak_warning",
                "inspectionType" to "JSUnresolvedReference",
            )
        )
        enhancedTreeExtractorFactory = { extractor }

        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
            ),
        )

        val response = getInspectionProblems()

        assertTrue(response.contains("\"status\": \"results_available\""))
        assertTrue(response.contains("\"total_problems\": 1"))
        assertTrue(response.contains("Live warning"))
        assertTrue(response.contains("\"method\": \"tool_window\""))
    }

    @Test
    @DisplayName("Problems endpoint returns live findings when incomplete snapshot is contradicted")
    fun testProblemsEndpointReconcilesCaptureIncompleteSnapshot() {
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblems(mockProject) } returns listOf(
            mapOf(
                "description" to "Late warning",
                "file" to "/tmp/TestProject/src/app.js",
                "line" to 12,
                "column" to 4,
                "severity" to "weak_warning",
                "inspectionType" to "JSUnresolvedReference",
            )
        )
        enhancedTreeExtractorFactory = { extractor }

        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CAPTURE_INCOMPLETE,
                source = "inspection_view",
                note = "capture note",
            ),
        )

        val response = getInspectionProblems()

        assertTrue(response.contains("\"status\": \"results_available\""))
        assertTrue(response.contains("\"total_problems\": 1"))
        assertTrue(response.contains("Late warning"))
        assertFalse(response.contains("\"status\": \"capture_incomplete\""))
    }

    @Test
    @DisplayName("Wait returns capture_incomplete instead of clean for empty inconclusive snapshots")
    fun testWaitReturnsCaptureIncompleteForInconclusiveSnapshot() {
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CAPTURE_INCOMPLETE,
                source = "inspection_view",
                note = "capture note",
            ),
        )

        val response = waitForInspection()

        assertTrue(response.contains("\"completion_reason\": \"capture_incomplete\""))
        assertTrue(response.contains("\"wait_completed\": true"))
        assertFalse(response.contains("\"completion_reason\": \"clean\""))
    }

    @Test
    @DisplayName("Wait returns stale_results when cached results changed after inspection")
    fun testWaitReturnsStaleResultsForStaleSnapshot() {
        setLastInspectionTriggerTime(System.currentTimeMillis() - 16000L)
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(
                    mapOf(
                        "description" to "Old warning",
                        "file" to "/tmp/TestProject/src/app.js",
                        "line" to 12,
                        "column" to 4,
                        "severity" to "weak_warning",
                        "inspectionType" to "JSUnresolvedReference",
                    )
                ),
                timestamp = System.currentTimeMillis() - 16000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
            ),
        )
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 8L

        val response = waitForInspection(timeoutMs = 1000L, pollMs = 200L)

        assertTrue(response.contains("\"completion_reason\": \"stale_results\""))
        assertTrue(response.contains("\"results_may_be_stale\": true"))
        assertFalse(response.contains("\"completion_reason\": \"results\""))
    }

    @Test
    @DisplayName("Wait does not declare clean until the zero-problem snapshot has stabilized")
    fun testWaitDoesNotReturnCleanBeforeFreshSnapshotStabilizes() {
        val extractor = mockk<EnhancedTreeExtractor>()
        setLastInspectionTriggerTime(System.currentTimeMillis() - 16000L)
        val liveProblems = listOf(
            mapOf(
                "description" to "Live warning",
                "file" to "/tmp/TestProject/src/app.js",
                "line" to 12,
                "column" to 4,
                "severity" to "weak_warning",
                "inspectionType" to "JSUnresolvedReference",
            )
        )
        var extractorCalls = 0
        every { extractor.extractAllProblems(mockProject) } answers {
            extractorCalls += 1
            if (extractorCalls == 1) emptyList() else liveProblems
        }
        enhancedTreeExtractorFactory = { extractor }

        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
            ),
        )

        val response = waitForInspection(timeoutMs = 7000L, pollMs = 200L)

        assertTrue(extractorCalls > 1)
        assertTrue(response.contains("\"completion_reason\": \"results\""))
        assertFalse(response.contains("\"completion_reason\": \"clean\""))
    }

    @Test
    @DisplayName("Wait keeps polling through a transient empty live view after a recent trigger")
    fun testWaitKeepsPollingWhenRecentRunHasNoSnapshotYet() {
        val extractor = mockk<EnhancedTreeExtractor>()
        setLastInspectionTriggerTime(System.currentTimeMillis() - 16000L)
        val liveProblems = listOf(
            mapOf(
                "description" to "Late warning",
                "file" to "/tmp/TestProject/src/app.js",
                "line" to 12,
                "column" to 4,
                "severity" to "weak_warning",
                "inspectionType" to "JSUnresolvedReference",
            )
        )
        var extractorCalls = 0
        every { extractor.extractAllProblems(mockProject) } answers {
            extractorCalls += 1
            if (extractorCalls == 1) emptyList() else liveProblems
        }
        enhancedTreeExtractorFactory = { extractor }

        val response = waitForInspection(timeoutMs = 7000L, pollMs = 200L)

        assertTrue(extractorCalls > 1)
        assertTrue(response.contains("\"completion_reason\": \"results\""))
        assertFalse(response.contains("\"completion_reason\": \"no_results\""))
    }

    @Test
    @DisplayName("Wait returns no_results, not capture_incomplete, for settled empty states without a snapshot")
    fun testWaitDoesNotInferCaptureIncompleteFromGenericNoResults() {
        setLastInspectionTriggerTime(System.currentTimeMillis() - 120000L)

        val response = waitForInspection()

        assertTrue(response.contains("\"completion_reason\": \"no_results\""))
        assertTrue(response.contains("clean runs"))
        assertFalse(response.contains("\"completion_reason\": \"capture_incomplete\""))
    }

    @Test
    @DisplayName("Wait returns no_results at timeout for short settled empty waits")
    fun testWaitReturnsNoResultsAtShortTimeoutForSettledEmptyRun() {
        setLastInspectionTriggerTime(System.currentTimeMillis() - 16000L)

        val response = waitForInspection(timeoutMs = 1000L, pollMs = 200L)

        assertTrue(response.contains("\"completion_reason\": \"no_results\""))
        assertTrue(response.contains("\"wait_completed\": true"))
        assertFalse(response.contains("\"completion_reason\": \"timeout\""))
    }

    @Test
    @DisplayName("Wait reports no recent inspection before any trigger has run")
    fun testWaitReportsNoRecentInspectionBeforeFirstTrigger() {
        setLastInspectionTriggerTime(0L)

        val response = waitForInspection()

        assertTrue(response.contains("\"completion_reason\": \"no_recent_inspection\""))
        assertTrue(response.contains("\"wait_completed\": false"))
        assertFalse(response.contains("\"completion_reason\": \"no_results\""))
    }

    @Test
    @DisplayName("Wait does not treat another project's trigger as this project's trigger")
    fun testWaitNoRecentInspectionIgnoresOtherProjectTrigger() {
        setLastInspectionTriggerTime("OtherProject", System.currentTimeMillis() - 16000L)
        setLastInspectionTriggerTime("TestProject", 0L)

        val response = waitForInspection()

        assertTrue(response.contains("\"completion_reason\": \"no_recent_inspection\""))
        assertFalse(response.contains("\"completion_reason\": \"no_results\""))
    }

    @Test
    @DisplayName("Wait does not report results immediately for fresh inspection-view snapshots")
    fun testWaitDoesNotReturnResultsBeforeSnapshotSettles() {
        setLastInspectionTriggerTime(System.currentTimeMillis())
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(
                    mapOf(
                        "description" to "Early warning",
                        "file" to "/tmp/TestProject/src/app.js",
                        "line" to 12,
                        "column" to 4,
                        "severity" to "weak_warning",
                        "inspectionType" to "JSUnresolvedReference",
                    )
                ),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
            ),
        )

        val response = waitForInspection(timeoutMs = 3000L, pollMs = 200L)

        assertTrue(response.contains("\"completion_reason\": \"timeout\""))
        assertFalse(response.contains("\"completion_reason\": \"results\""))
    }

    @Test
    @DisplayName("Empty inspection capture is only marked clean when the inspection view has settled empty")
    fun testClassifyEmptyInspectionCapture() {
        val ambiguous = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = true,
            observedSettledEmptyInspectionView = false,
            observedNonEmptyInspectionTree = false,
        )

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, ambiguous.first)
        assertTrue(ambiguous.second?.contains("could not conclusively confirm") == true)

        val confirmedClean = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = true,
            observedSettledEmptyInspectionView = true,
            observedNonEmptyInspectionTree = false,
        )

        assertEquals(InspectionSnapshotOutcome.CLEAN_CONFIRMED, confirmedClean.first)
        assertEquals(null, confirmedClean.second)
    }

    @Test
    @DisplayName("Clean wait requires a post-trigger settle window")
    fun testCleanWaitHasSettledRequiresTriggerAge() {
        val now = System.currentTimeMillis()

        assertFalse(
            cleanWaitHasSettled(
                now = now,
                resultsTimestampMs = now - 8000,
                cleanStableSince = null,
                timeSinceTriggerMs = 10000,
            )
        )

        assertTrue(
            cleanWaitHasSettled(
                now = now,
                resultsTimestampMs = now - 8000,
                cleanStableSince = null,
                timeSinceTriggerMs = 16000,
            )
        )

        assertFalse(
            resultsWaitHasSettled(
                now = now,
                resultsStableSince = now - 8000,
                timeSinceTriggerMs = 10000,
            )
        )

        assertTrue(
            resultsWaitHasSettled(
                now = now,
                resultsStableSince = now - 8000,
                timeSinceTriggerMs = 16000,
            )
        )
    }

    @Test
    @DisplayName("Capture polling settles on stable tool-window findings without inspection view")
    fun testShouldStopCapturePollingForStableToolResultsWithoutView() {
        assertFalse(
            shouldStopCapturePolling(
                viewReadyOk = false,
                observedInspectionView = false,
                inspectionViewUpdating = false,
                observedSettledEmptyInspectionView = false,
                bestResultsCount = 3,
                stableForMs = 6000,
                pollingElapsedMs = 7000,
            )
        )

        assertTrue(
            shouldStopCapturePolling(
                viewReadyOk = false,
                observedInspectionView = false,
                inspectionViewUpdating = false,
                observedSettledEmptyInspectionView = false,
                bestResultsCount = 3,
                stableForMs = 6000,
                pollingElapsedMs = 16000,
            )
        )

        assertFalse(
            shouldStopCapturePolling(
                viewReadyOk = false,
                observedInspectionView = false,
                inspectionViewUpdating = false,
                observedSettledEmptyInspectionView = false,
                bestResultsCount = 0,
                stableForMs = 4000,
                pollingElapsedMs = 16000,
            )
        )

        assertTrue(
            shouldStopCapturePolling(
                viewReadyOk = false,
                observedInspectionView = false,
                inspectionViewUpdating = false,
                observedSettledEmptyInspectionView = false,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 16000,
            )
        )
    }

    @Test
    @DisplayName("Capture polling does not stop on an empty inspection view until the view finishes updating")
    fun testShouldNotStopCapturePollingForUpdatingEmptyInspectionView() {
        assertFalse(
            shouldStopCapturePolling(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                observedSettledEmptyInspectionView = false,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 16000,
            )
        )

        assertFalse(
            shouldStopCapturePolling(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = false,
                observedSettledEmptyInspectionView = true,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 7000,
            )
        )

        assertTrue(
            shouldStopCapturePolling(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = false,
                observedSettledEmptyInspectionView = true,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 16000,
            )
        )
    }

    private fun buildInspectionStatus(): MutableMap<String, Any> {
        val method = InspectionHandler::class.java.getDeclaredMethod("buildInspectionStatus", Project::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(handler, mockProject) as MutableMap<String, Any>
    }

    private fun getInspectionProblems(): String {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "getInspectionProblems",
            Project::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(handler, mockProject, "all", "whole_project", null, null, 100, 0) as String
    }

    private fun waitForInspection(timeoutMs: Long = 1000L, pollMs: Long = 200L): String {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "waitForInspection",
            String::class.java,
            java.lang.Long::class.java,
            java.lang.Long::class.java,
        )
        method.isAccessible = true
        return method.invoke(handler, "TestProject", timeoutMs, pollMs) as String
    }

    private fun snapshotKey(project: Project = mockProject): String {
        return projectKey(project)
    }

    private fun beginInspectionRun(): InspectionRunState {
        val method = InspectionHandler::class.java.getDeclaredMethod("beginInspectionRun", Project::class.java)
        method.isAccessible = true
        return method.invoke(handler, mockProject) as InspectionRunState
    }

    private fun finishInspectionRun(projectKey: String, runId: Long) {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "finishInspectionRun",
            String::class.java,
            Long::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(handler, projectKey, runId)
    }

    private fun setLastInspectionTriggerTime(value: Long) {
        setLastInspectionTriggerTime("TestProject", value)
    }

    private fun setLastInspectionTriggerTime(projectName: String, value: Long) {
        val field = InspectionHandler::class.java.getDeclaredField("inspectionRunStatesByProject")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val runStates = field.get(handler) as MutableMap<String, InspectionRunState>
        val key = if (projectName == "TestProject") snapshotKey() else "name:$projectName"
        if (value > 0L) {
            val current = runStates[key]
            runStates[key] = InspectionRunState(
                runId = current?.runId ?: 1L,
                triggerTimeMs = value,
                inProgress = current?.inProgress ?: false,
            )
        } else {
            runStates.remove(key)
        }
    }
}
