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
        InspectionResultsStore.clear("TestProject")
        enhancedTreeExtractorFactory = { EnhancedTreeExtractor() }
    }

    @AfterEach
    fun tearDown() {
        InspectionResultsStore.clear("TestProject")
        unmockkAll()
    }

    @Test
    @DisplayName("Empty capture snapshot is not reported as a clean inspection")
    fun testCaptureIncompleteSnapshotDoesNotLookClean() {
        InspectionResultsStore.setSnapshot(
            "TestProject",
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
            "TestProject",
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
            "TestProject",
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
            "TestProject",
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
            "TestProject",
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
    @DisplayName("Wait returns capture_incomplete instead of clean for empty inconclusive snapshots")
    fun testWaitReturnsCaptureIncompleteForInconclusiveSnapshot() {
        InspectionResultsStore.setSnapshot(
            "TestProject",
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
    @DisplayName("Wait does not declare clean until the zero-problem snapshot has stabilized")
    fun testWaitDoesNotReturnCleanBeforeFreshSnapshotStabilizes() {
        val extractor = mockk<EnhancedTreeExtractor>()
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
            "TestProject",
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
    @DisplayName("Empty inspection capture is only marked clean when the tree is explicitly empty")
    fun testClassifyEmptyInspectionCapture() {
        val ambiguous = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = true,
            observedExplicitlyEmptyInspectionTree = false,
            observedNonEmptyInspectionTree = false,
        )

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, ambiguous.first)
        assertTrue(ambiguous.second?.contains("could not conclusively confirm") == true)

        val confirmedClean = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = true,
            observedExplicitlyEmptyInspectionTree = true,
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
    }

    @Test
    @DisplayName("Capture polling settles on stable tool-window findings without inspection view")
    fun testShouldStopCapturePollingForStableToolResultsWithoutView() {
        assertTrue(
            shouldStopCapturePolling(
                viewReadyOk = false,
                observedInspectionView = false,
                bestResultsCount = 3,
                stableForMs = 6000,
                pollingElapsedMs = 7000,
            )
        )

        assertFalse(
            shouldStopCapturePolling(
                viewReadyOk = false,
                observedInspectionView = false,
                bestResultsCount = 0,
                stableForMs = 4000,
                pollingElapsedMs = 16000,
            )
        )

        assertTrue(
            shouldStopCapturePolling(
                viewReadyOk = false,
                observedInspectionView = false,
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

    private fun setLastInspectionTriggerTime(value: Long) {
        val field = InspectionHandler::class.java.getDeclaredField("lastInspectionTriggerTime")
        field.isAccessible = true
        field.setLong(handler, value)
    }
}
