package com.shiny.inspectionmcp

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
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
                captureDiagnostic = mapOf(
                    "exit_reason" to "timeout",
                    "view_ready_ok" to false,
                    "successful_extraction_count" to 1,
                ),
            ),
        )

        val status = buildInspectionStatus()

        assertEquals("capture_incomplete", status["snapshot_outcome"])
        assertEquals("capture note", status["snapshot_note"])
        assertEquals(
            mapOf(
                "exit_reason" to "timeout",
                "view_ready_ok" to false,
                "successful_extraction_count" to 1,
            ),
            status["capture_diagnostic"],
        )
        assertEquals("timeout", status["capture_incomplete_reason"])
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
        assertEquals("GREEN", status["inspection_verdict"])
        assertEquals("clean_confirmed", status["inspection_verdict_reason"])
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
    @DisplayName("Running inspection state overrides old clean verdict")
    fun testRunningInspectionOverridesOldCleanVerdict() {
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
        beginInspectionRun()

        val status = buildInspectionStatus()

        assertEquals(true, status["inspection_in_progress"])
        assertEquals(true, status["is_scanning"])
        assertEquals(false, status["clean_inspection"])
        assertEquals("UNKNOWN", status["inspection_verdict"])
        assertEquals("inspection_still_running", status["inspection_verdict_reason"])
    }

    @Test
    @DisplayName("Running inspection state overrides old findings verdict")
    fun testRunningInspectionOverridesOldFindingsVerdict() {
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(
                    mapOf(
                        "description" to "Old warning",
                        "file" to "/tmp/TestProject/src/app.js",
                        "line" to 12,
                        "column" to 4,
                        "severity" to "warning",
                        "inspectionType" to "JSUnresolvedReference",
                    )
                ),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
            ),
        )
        beginInspectionRun()

        val status = buildInspectionStatus()

        assertEquals(true, status["inspection_in_progress"])
        assertEquals(true, status["is_scanning"])
        assertEquals(1, status["total_problems"])
        assertEquals("UNKNOWN", status["inspection_verdict"])
        assertEquals("inspection_still_running", status["inspection_verdict_reason"])
    }

    @Test
    @DisplayName("Indexing overrides clean snapshot verdict")
    fun testIndexingOverridesCleanSnapshotVerdict() {
        every { DumbService.getInstance(mockProject).isDumb } returns true
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

        assertEquals(true, status["indexing"])
        assertEquals(true, status["is_scanning"])
        assertEquals(false, status["clean_inspection"])
        assertEquals("UNKNOWN", status["inspection_verdict"])
        assertEquals("inspection_still_running", status["inspection_verdict_reason"])
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
        assertEquals("RED", status["inspection_verdict"])
        assertEquals("actionable_findings", status["inspection_verdict_reason"])
    }

    @Test
    @DisplayName("Scoped clean snapshot ignores unrelated live tool-window problems")
    fun testScopedCleanSnapshotIgnoresUnrelatedLiveProblems() {
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblems(mockProject) } returns listOf(
            mapOf(
                "description" to "Unrelated warning",
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
                captureScope = InspectionCaptureScope(
                    scopeParam = "files",
                    files = listOf("README.md"),
                ),
            ),
        )

        val status = buildInspectionStatus()

        assertEquals(true, status["clean_inspection"])
        assertEquals(true, status["has_inspection_results"])
        assertEquals(0, status["total_problems"])
        assertEquals("clean_confirmed", status["snapshot_outcome"])
        assertEquals("inspection_view", status["results_source"])
    }

    @Test
    @DisplayName("Current-file clean snapshot stays bound to the captured file during reconciliation")
    fun testCurrentFileCleanSnapshotUsesCapturedFilePath() {
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblems(mockProject) } returns listOf(
            mapOf(
                "description" to "Different editor tab warning",
                "file" to "/tmp/TestProject/src/other.kt",
                "line" to 8,
                "column" to 2,
                "severity" to "warning",
                "inspectionType" to "DifferentFileInspection",
            )
        )
        enhancedTreeExtractorFactory = { extractor }

        val mockFileEditorManager = mockk<FileEditorManager>()
        val mockActiveFile = mockk<VirtualFile>()
        val mockProjectFileIndex = mockk<ProjectFileIndex>()
        mockkStatic(FileEditorManager::class)
        every { FileEditorManager.getInstance(mockProject) } returns mockFileEditorManager
        every { mockFileEditorManager.selectedFiles } returns arrayOf(mockActiveFile)
        every { mockFileEditorManager.openFiles } returns arrayOf(mockActiveFile)
        every { mockActiveFile.isValid } returns true
        every { mockActiveFile.isInLocalFileSystem } returns true
        every { mockActiveFile.path } returns "/tmp/TestProject/src/other.kt"

        mockkStatic(ProjectFileIndex::class)
        every { ProjectFileIndex.getInstance(mockProject) } returns mockProjectFileIndex
        every { mockProjectFileIndex.isInContent(mockActiveFile) } returns true

        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
                captureScope = InspectionCaptureScope(
                    scopeParam = "current_file",
                    resolvedCurrentFile = "/tmp/TestProject/src/original.kt",
                ),
            ),
        )

        val status = buildInspectionStatus()

        assertEquals(true, status["clean_inspection"])
        assertEquals(true, status["has_inspection_results"])
        assertEquals(0, status["total_problems"])
        assertEquals("clean_confirmed", status["snapshot_outcome"])
        assertEquals("inspection_view", status["results_source"])
    }

    @Test
    @DisplayName("Current-file snapshots without a pinned file reconcile as whole-project results")
    fun testCurrentFileSnapshotWithoutPinnedFileDoesNotUseLaterActiveTab() {
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblems(mockProject) } returns listOf(
            mapOf(
                "description" to "Whole-project warning",
                "file" to "/tmp/TestProject/src/other.kt",
                "line" to 8,
                "column" to 2,
                "severity" to "warning",
                "inspectionType" to "DifferentFileInspection",
            )
        )
        enhancedTreeExtractorFactory = { extractor }

        val mockFileEditorManager = mockk<FileEditorManager>()
        val mockActiveFile = mockk<VirtualFile>()
        val mockProjectFileIndex = mockk<ProjectFileIndex>()
        mockkStatic(FileEditorManager::class)
        every { FileEditorManager.getInstance(mockProject) } returns mockFileEditorManager
        every { mockFileEditorManager.selectedFiles } returns arrayOf(mockActiveFile)
        every { mockFileEditorManager.openFiles } returns arrayOf(mockActiveFile)
        every { mockActiveFile.isValid } returns true
        every { mockActiveFile.isInLocalFileSystem } returns true
        every { mockActiveFile.path } returns "/tmp/TestProject/src/active.kt"

        mockkStatic(ProjectFileIndex::class)
        every { ProjectFileIndex.getInstance(mockProject) } returns mockProjectFileIndex
        every { mockProjectFileIndex.isInContent(mockActiveFile) } returns true

        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
                captureScope = InspectionCaptureScope(
                    scopeParam = "current_file",
                    resolvedCurrentFile = null,
                ),
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
                captureDiagnostic = mapOf(
                    "exit_reason" to "timeout",
                    "view_ready_ok" to false,
                ),
            ),
        )

        val response = getInspectionProblems()

        assertTrue(response.contains("\"status\": \"capture_incomplete\""))
        assertTrue(response.contains("\"results_may_be_incomplete\": true"))
        assertTrue(response.contains("\"snapshot_outcome\": \"capture_incomplete\""))
        assertTrue(response.contains("\"capture_incomplete_reason\": \"timeout\""))
        assertTrue(response.contains("\"inspection_verdict\": \"UNKNOWN\""))
        assertTrue(response.contains("\"inspection_verdict_reason\": \"timeout\""))
        assertTrue(response.contains("\"capture_diagnostic\""))
        assertTrue(response.contains("\"exit_reason\": \"timeout\""))
        assertTrue(response.contains("\"view_ready_ok\": false"))
        assertFalse(response.contains("\"status\": \"results_available\""))
    }

    @Test
    @DisplayName("Problems endpoint returns normalized no_results response")
    fun testProblemsEndpointNormalizesNoResultsResponse() {
        setLastInspectionTriggerTime(System.currentTimeMillis() - 120000L)

        val response = getInspectionProblems()

        assertTrue(response.contains("\"status\": \"no_results\""))
        assertTrue(response.contains("\"inspection_verdict\": \"UNKNOWN\""))
        assertTrue(response.contains("\"inspection_verdict_reason\": \"no_results\""))
        assertTrue(response.contains("No trustworthy inspection result was captured"))
        assertFalse(response.contains("100% pass"))
        assertTrue(response.contains("\"project\": \"TestProject\""))
        assertTrue(response.contains("\"project_key\":"))
        assertTrue(response.contains("\"total_problems\": 0"))
        assertTrue(response.contains("\"problems_shown\": 0"))
        assertTrue(response.contains("\"problems\": []"))
        assertTrue(response.contains("\"pagination\":"))
        assertTrue(response.contains("\"filters\":"))
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
    @DisplayName("Problems endpoint verdict stays red for empty paginated pages with findings")
    fun testProblemsEndpointVerdictUsesTotalForEmptyPage() {
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(
                    staleProblem(description = "First warning", file = "/tmp/TestProject/src/app.js"),
                    staleProblem(description = "Second warning", file = "/tmp/TestProject/src/other.js"),
                ),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
            ),
        )

        val response = getInspectionProblems(limit = 1, offset = 2)

        assertTrue(response.contains("\"status\": \"results_available\""))
        assertTrue(response.contains("\"total_problems\": 2"))
        assertTrue(response.contains("\"problems_shown\": 0"))
        assertTrue(response.contains("\"problems\": []"))
        assertTrue(response.contains("\"inspection_verdict\": \"RED\""))
        assertTrue(response.contains("\"inspection_verdict_reason\": \"actionable_findings\""))
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
                captureDiagnostic = mapOf(
                    "exit_reason" to "timeout",
                    "view_ready_ok" to false,
                ),
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
                captureDiagnostic = mapOf(
                    "exit_reason" to "timeout",
                    "view_ready_ok" to false,
                ),
            ),
        )

        val response = waitForInspection()

        assertTrue(response.contains("\"completion_reason\": \"capture_incomplete\""))
        assertTrue(response.contains("\"capture_incomplete_reason\": \"timeout\""))
        assertTrue(response.contains("\"inspection_verdict\": \"UNKNOWN\""))
        assertTrue(response.contains("\"inspection_verdict_reason\": \"timeout\""))
        assertTrue(response.contains("\"capture_diagnostic\""))
        assertTrue(response.contains("\"exit_reason\": \"timeout\""))
        assertTrue(response.contains("\"view_ready_ok\": false"))
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
        assertTrue(response.contains("\"cached_total_problems\": 1"))
        assertFalse(response.contains("\"total_problems\":"))
        assertFalse(response.contains("\"completion_reason\": \"results\""))
    }

    @Test
    @DisplayName("Wait reconciles current-run PSI churn without stale_results")
    fun testWaitReconcilesCurrentRunPsiChurn() {
        val extractor = mockk<EnhancedTreeExtractor>()
        val currentRun = beginInspectionRun()
        finishInspectionRun(snapshotKey(), currentRun.runId)
        setLastInspectionTriggerTime(System.currentTimeMillis() - 16000L)
        val problems = listOf(staleProblem(description = "Fresh warning"))
        every { extractor.extractAllProblems(mockProject) } returns problems
        enhancedTreeExtractorFactory = { extractor }
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = problems,
                timestamp = System.currentTimeMillis() - 16000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
                runId = currentRun.runId,
                triggerTimeMs = currentRun.triggerTimeMs,
            ),
        )
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 8L

        val response = waitForInspection(timeoutMs = 7000L, pollMs = 200L)
        val status = buildInspectionStatus()

        assertTrue(response.contains("\"completion_reason\": \"results\""))
        assertFalse(response.contains("\"completion_reason\": \"stale_results\""))
        assertEquals(false, status["results_may_be_stale"])
        assertEquals("fresh", status["snapshot_change_kind"])
        assertEquals(8L, InspectionResultsStore.getProjectState(snapshotKey())?.psiModificationCount)
        assertEquals(currentRun.runId, InspectionResultsStore.getRunId(snapshotKey()))
    }

    @Test
    @DisplayName("Status distinguishes unreconciled current-run PSI churn")
    fun testStatusReportsCurrentRunPsiChurnWhenReconcileCannotConfirm() {
        val extractor = mockk<EnhancedTreeExtractor>()
        val currentRun = beginInspectionRun()
        finishInspectionRun(snapshotKey(), currentRun.runId)
        every { extractor.extractAllProblems(mockProject) } returns emptyList()
        enhancedTreeExtractorFactory = { extractor }
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(staleProblem(description = "Fresh warning")),
                timestamp = System.currentTimeMillis() - 16000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
                runId = currentRun.runId,
                triggerTimeMs = currentRun.triggerTimeMs,
            ),
        )
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 8L

        val status = buildInspectionStatus()

        assertEquals(true, status["results_may_be_stale"])
        assertEquals("project_changed_since_inspection", status["snapshot_change_kind"])
        assertEquals(listOf("project_changed_since_inspection"), status["stale_reasons"])
        assertEquals(currentRun.runId, status["snapshot_run_id"])
    }

    @Test
    @DisplayName("Status preserves stored capture reason during unreconciled PSI churn")
    fun testStatusPreservesStoredCaptureReasonDuringUnreconciledPsiChurn() {
        val extractor = mockk<EnhancedTreeExtractor>()
        val currentRun = beginInspectionRun()
        finishInspectionRun(snapshotKey(), currentRun.runId)
        every { extractor.extractAllProblems(mockProject) } returns listOf(staleProblem(description = "Unexpected live warning"))
        enhancedTreeExtractorFactory = { extractor }
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis() - 16000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CAPTURE_INCOMPLETE,
                source = "inspection_view",
                captureDiagnostic = mapOf(
                    "exit_reason" to "deadline",
                    "view_ready_ok" to true,
                    "observed_inspection_view" to true,
                    "extraction_failure_count" to 2,
                    "successful_extraction_count" to 0,
                ),
                captureIncompleteReason = CaptureIncompleteReason.EXTRACTOR_FAILURE,
                runId = currentRun.runId,
                triggerTimeMs = currentRun.triggerTimeMs,
            ),
        )
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 8L

        val status = buildInspectionStatus()

        assertEquals(true, status["results_may_be_stale"])
        assertEquals("project_changed_since_inspection", status["snapshot_change_kind"])
        assertEquals("extractor_failure", status["capture_incomplete_reason"])
    }

    @Test
    @DisplayName("Problems endpoint withholds unreconciled current-run PSI churn")
    fun testProblemsEndpointWithholdsUnreconciledCurrentRunPsiChurn() {
        val extractor = mockk<EnhancedTreeExtractor>()
        val currentRun = beginInspectionRun()
        finishInspectionRun(snapshotKey(), currentRun.runId)
        every { extractor.extractAllProblems(mockProject) } returns emptyList()
        enhancedTreeExtractorFactory = { extractor }
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(staleProblem(description = "Fresh warning")),
                timestamp = System.currentTimeMillis() - 16000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
                runId = currentRun.runId,
                triggerTimeMs = currentRun.triggerTimeMs,
            ),
        )
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 8L

        val response = getInspectionProblems()

        assertTrue(response.contains("\"status\": \"stale_results\""))
        assertTrue(response.contains("\"inspection_verdict\": \"UNKNOWN\""))
        assertTrue(response.contains("\"inspection_verdict_reason\": \"stale_results\""))
        assertTrue(response.contains("\"results_may_be_stale\": true"))
        assertTrue(response.contains("\"snapshot_change_kind\": \"project_changed_since_inspection\""))
        assertTrue(response.contains("\"cached_total_problems\": 1"))
        assertTrue(response.contains("\"cached_problems_shown\": 0"))
        assertFalse(response.contains("Fresh warning"))
    }

    @Test
    @DisplayName("Current-run snapshots with unsaved documents remain stale")
    fun testCurrentRunSnapshotWithUnsavedDocumentsRemainsStale() {
        val currentRun = beginInspectionRun()
        finishInspectionRun(snapshotKey(), currentRun.runId)
        setLastInspectionTriggerTime(System.currentTimeMillis() - 16000L)
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(staleProblem()),
                timestamp = System.currentTimeMillis() - 16000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
                runId = currentRun.runId,
                triggerTimeMs = currentRun.triggerTimeMs,
            ),
        )
        val unsavedDocument = mockk<Document>()
        val unsavedFile = mockk<VirtualFile>()
        val mockProjectFileIndex = mockk<ProjectFileIndex>()
        mockkStatic(ProjectFileIndex::class)
        every { ProjectFileIndex.getInstance(mockProject) } returns mockProjectFileIndex
        every { FileDocumentManager.getInstance().unsavedDocuments } returns arrayOf(unsavedDocument)
        every { FileDocumentManager.getInstance().getFile(unsavedDocument) } returns unsavedFile
        every { unsavedFile.path } returns "/tmp/TestProject/src/app.js"
        every { mockProjectFileIndex.isInContent(unsavedFile) } returns true
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 8L

        val response = waitForInspection(timeoutMs = 1000L, pollMs = 200L)

        assertTrue(response.contains("\"completion_reason\": \"stale_results\""))
        assertTrue(response.contains("\"snapshot_change_kind\": \"unsaved_documents\""))
    }

    @Test
    @DisplayName("Older run snapshot remains stale after a newer trigger")
    fun testOlderRunSnapshotRemainsStaleAfterNewerTrigger() {
        val oldRun = beginInspectionRun()
        finishInspectionRun(snapshotKey(), oldRun.runId)
        val currentRun = beginInspectionRun()
        finishInspectionRun(snapshotKey(), currentRun.runId)
        setLastInspectionTriggerTime(System.currentTimeMillis() - 16000L)
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(staleProblem()),
                timestamp = System.currentTimeMillis() - 16000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
                runId = oldRun.runId,
                triggerTimeMs = oldRun.triggerTimeMs,
            ),
        )
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 8L

        val response = waitForInspection(timeoutMs = 1000L, pollMs = 200L)

        assertTrue(response.contains("\"completion_reason\": \"stale_results\""))
        assertTrue(response.contains("\"snapshot_change_kind\": \"snapshot_predates_current_trigger\""))
        assertTrue(response.contains("\"snapshot_run_id\": ${oldRun.runId}"))
        assertTrue(response.contains("\"inspection_run_id\": ${currentRun.runId}"))
    }

    @Test
    @DisplayName("Status reports cached counts separately for stale snapshots")
    fun testStatusUsesCachedCountsForStaleSnapshots() {
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(staleProblem()),
                timestamp = System.currentTimeMillis() - 16000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
            ),
        )
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 8L

        val status = buildInspectionStatus()

        assertEquals(true, status["results_may_be_stale"])
        assertEquals(1, status["cached_total_problems"])
        assertFalse(status.containsKey("total_problems"))
    }

    @Test
    @DisplayName("Problems endpoint withholds stale findings by default")
    fun testProblemsEndpointWithholdsStaleFindingsByDefault() {
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(staleProblem()),
                timestamp = System.currentTimeMillis() - 16000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
            ),
        )
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 8L

        val response = getInspectionProblems(includeStale = false)

        assertTrue(response.contains("\"status\": \"stale_results\""))
        assertTrue(response.contains("\"results_may_be_stale\": true"))
        assertTrue(response.contains("\"stale_reasons\": [\"project_changed_since_inspection\"]"))
        assertTrue(response.contains("\"snapshot_outcome\": \"problems_found\""))
        assertTrue(response.contains("\"cached_total_problems\": 1"))
        assertTrue(response.contains("\"cached_problems_shown\": 0"))
        assertTrue(response.contains("\"include_stale\": false"))
        assertFalse(response.contains("\"problems\":"))
        assertFalse(response.contains("\"total_problems\":"))
    }

    @Test
    @DisplayName("Problems endpoint returns cached stale findings when explicitly requested")
    fun testProblemsEndpointCanIncludeStaleFindings() {
        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(staleProblem()),
                timestamp = System.currentTimeMillis() - 16000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
            ),
        )
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 8L

        val response = getInspectionProblems(includeStale = true)

        assertTrue(response.contains("\"status\": \"stale_results\""))
        assertTrue(response.contains("\"results_may_be_stale\": true"))
        assertTrue(response.contains("\"include_stale\": true"))
        assertTrue(response.contains("\"cached_total_problems\": 1"))
        assertTrue(response.contains("\"cached_problems_shown\": 1"))
        assertTrue(response.contains("\"problems\": ["))
        assertTrue(response.contains("Old warning"))
        assertTrue(response.contains("\"pagination\":"))
        assertFalse(response.contains("\"total_problems\":"))
    }

    @Test
    @DisplayName("Problems endpoint filters included stale findings by current file")
    fun testProblemsEndpointFiltersIncludedStaleFindingsByCurrentFile() {
        val mockFileEditorManager = mockk<FileEditorManager>()
        val mockActiveFile = mockk<VirtualFile>()
        mockkStatic(FileEditorManager::class)
        every { FileEditorManager.getInstance(mockProject) } returns mockFileEditorManager
        every { mockFileEditorManager.selectedFiles } returns arrayOf(mockActiveFile)
        every { mockActiveFile.path } returns "/tmp/TestProject/src/app.js"

        InspectionResultsStore.setSnapshot(
            snapshotKey(),
            InspectionResultsSnapshot(
                problems = listOf(
                    staleProblem(),
                    staleProblem(description = "Other warning", file = "/tmp/TestProject/src/other.js"),
                ),
                timestamp = System.currentTimeMillis() - 16000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 7L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
            ),
        )
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 8L

        val response = getInspectionProblems(scope = "current_file", includeStale = true)

        assertTrue(response.contains("\"status\": \"stale_results\""))
        assertTrue(response.contains("\"cached_total_problems\": 1"))
        assertTrue(response.contains("\"cached_problems_shown\": 1"))
        assertTrue(response.contains("Old warning"))
        assertFalse(response.contains("Other warning"))
        assertFalse(response.contains("\"total_problems\":"))
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
        assertTrue(response.contains("\"inspection_verdict\": \"UNKNOWN\""))
        assertTrue(response.contains("\"inspection_verdict_reason\": \"no_results\""))
        assertTrue(response.contains("Treat this as UNKNOWN, not clean"))
        assertFalse(response.contains("clean runs"))
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
    @DisplayName("Recently finished empty tool-window runs settle before the wait timeout")
    fun testRecentlyFinishedEmptyToolWindowRunSettlesBeforeTimeout() {
        val now = System.currentTimeMillis()

        assertFalse(
            noResultsWaitHasSettled(
                now = now,
                noResultsStableSince = now - 4000L,
                timeSinceTriggerMs = 16000L,
            )
        )
        assertTrue(
            noResultsWaitHasSettled(
                now = now,
                noResultsStableSince = now - 5000L,
                timeSinceTriggerMs = 16000L,
            )
        )
        assertTrue(
            noResultsWaitHasSettled(
                now = now,
                noResultsStableSince = null,
                timeSinceTriggerMs = 60000L,
            )
        )
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
    @DisplayName("Empty inspection capture is clean when a ready view has no problem tree")
    fun testClassifyEmptyInspectionCapture() {
        val ambiguous = classifyEmptyInspectionCapture(
            viewReadyOk = false,
            observedInspectionView = false,
            observedSettledEmptyInspectionView = false,
            observedStableReadableEmptyInspectionView = false,
            observedStableEmptyResultsWithoutInspectionView = false,
            observedNonEmptyInspectionTree = false,
        )

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, ambiguous.first)
        assertTrue(ambiguous.second?.contains("could not conclusively confirm") == true)

        val confirmedClean = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = true,
            observedSettledEmptyInspectionView = true,
            observedStableReadableEmptyInspectionView = false,
            observedStableEmptyResultsWithoutInspectionView = false,
            observedNonEmptyInspectionTree = false,
        )

        assertEquals(InspectionSnapshotOutcome.CLEAN_CONFIRMED, confirmedClean.first)
        assertEquals(null, confirmedClean.second)

        val nonEmptyTree = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = true,
            observedSettledEmptyInspectionView = true,
            observedStableReadableEmptyInspectionView = false,
            observedStableEmptyResultsWithoutInspectionView = false,
            observedModelCleanInspection = true,
            observedNonEmptyInspectionTree = true,
        )

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, nonEmptyTree.first)

        val stableReadableEmptyView = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = true,
            observedSettledEmptyInspectionView = false,
            observedStableReadableEmptyInspectionView = true,
            observedStableEmptyResultsWithoutInspectionView = false,
            observedNonEmptyInspectionTree = false,
        )

        assertEquals(InspectionSnapshotOutcome.CLEAN_CONFIRMED, stableReadableEmptyView.first)
        assertEquals(null, stableReadableEmptyView.second)

        val stableEmptyWithoutInspectionView = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = false,
            observedSettledEmptyInspectionView = false,
            observedStableReadableEmptyInspectionView = false,
            observedStableEmptyResultsWithoutInspectionView = true,
            observedNonEmptyInspectionTree = false,
        )

        assertEquals(InspectionSnapshotOutcome.CLEAN_CONFIRMED, stableEmptyWithoutInspectionView.first)
        assertEquals(null, stableEmptyWithoutInspectionView.second)

        val stableEmptyWithOpaqueInspectionView = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = true,
            observedSettledEmptyInspectionView = false,
            observedStableReadableEmptyInspectionView = false,
            observedStableEmptyResultsWithoutInspectionView = true,
            observedNonEmptyInspectionTree = false,
        )

        assertEquals(InspectionSnapshotOutcome.CLEAN_CONFIRMED, stableEmptyWithOpaqueInspectionView.first)
        assertEquals(null, stableEmptyWithOpaqueInspectionView.second)

        val modelCleanWithUpdatingView = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = true,
            observedSettledEmptyInspectionView = false,
            observedStableReadableEmptyInspectionView = false,
            observedStableEmptyResultsWithoutInspectionView = false,
            observedModelCleanInspection = true,
            observedNonEmptyInspectionTree = false,
        )

        assertEquals(InspectionSnapshotOutcome.CLEAN_CONFIRMED, modelCleanWithUpdatingView.first)
        assertEquals(null, modelCleanWithUpdatingView.second)

        val unreadableView = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = true,
            observedSettledEmptyInspectionView = false,
            observedStableReadableEmptyInspectionView = false,
            observedStableEmptyResultsWithoutInspectionView = false,
            observedNonEmptyInspectionTree = false,
        )

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, unreadableView.first)

        val stillUnreadableView = classifyEmptyInspectionCapture(
            viewReadyOk = true,
            observedInspectionView = true,
            observedSettledEmptyInspectionView = false,
            observedStableReadableEmptyInspectionView = false,
            observedStableEmptyResultsWithoutInspectionView = false,
            observedNonEmptyInspectionTree = false,
        )

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, stillUnreadableView.first)
    }

    @Test
    @DisplayName("Capture incomplete diagnostics map to stable reason taxonomy")
    fun testCaptureIncompleteReasonTaxonomy() {
        assertEquals(
            CaptureIncompleteReason.TIMEOUT,
            classifyCaptureIncompleteReason(
                mapOf(
                    "exit_reason" to "deadline",
                    "view_ready_ok" to false,
                ),
            ),
        )
        assertEquals(
            CaptureIncompleteReason.VIEW_NOT_READY,
            classifyCaptureIncompleteReason(
                mapOf(
                    "exit_reason" to "settled",
                    "view_ready_ok" to false,
                ),
            ),
        )
        assertEquals(
            CaptureIncompleteReason.VIEW_UPDATING_UNREADABLE,
            classifyCaptureIncompleteReason(
                mapOf(
                    "exit_reason" to "deadline",
                    "view_ready_ok" to true,
                    "observed_inspection_view" to true,
                    "inspection_view_updating" to true,
                    "null_root_child_observation_count" to 2,
                ),
            ),
        )
        assertEquals(
            CaptureIncompleteReason.UNREADABLE_TREE,
            classifyCaptureIncompleteReason(
                mapOf(
                    "exit_reason" to "deadline",
                    "view_ready_ok" to true,
                    "observed_inspection_view" to true,
                    "inspection_view_observation_count" to 2,
                    "null_root_child_observation_count" to 2,
                ),
            ),
        )
        assertEquals(
            CaptureIncompleteReason.EXTRACTOR_FAILURE,
            classifyCaptureIncompleteReason(
                mapOf(
                    "exit_reason" to "deadline",
                    "view_ready_ok" to true,
                    "observed_inspection_view" to true,
                    "extraction_failure_count" to 3,
                    "successful_extraction_count" to 0,
                ),
            ),
        )
        assertEquals(
            CaptureIncompleteReason.NON_EMPTY_UNMAPPED_TREE,
            classifyCaptureIncompleteReason(
                mapOf(
                    "exit_reason" to "deadline",
                    "view_ready_ok" to true,
                    "observed_inspection_view" to true,
                    "observed_non_empty_inspection_tree" to true,
                ),
            ),
        )
        assertEquals(
            CaptureIncompleteReason.CURRENT_RUN_PSI_CHURN,
            classifyCaptureIncompleteReason(emptyMap(), snapshotChangeKind = "current_run_psi_churn"),
        )
        assertEquals(
            CaptureIncompleteReason.TIMEOUT,
            classifyCaptureIncompleteReason(
                mapOf(
                    "exit_reason" to "deadline",
                    "view_ready_ok" to true,
                    "observed_inspection_view" to true,
                ),
            ),
        )
        assertEquals(
            CaptureIncompleteReason.TIMEOUT,
            classifyCaptureIncompleteReason(
                mapOf(
                    "exit_reason" to "timeout",
                    "view_ready_ok" to false,
                    "observed_inspection_view" to false,
                ),
            ),
        )
        assertEquals(
            CaptureIncompleteReason.HELPER_PLUGIN_ERROR,
            classifyCaptureIncompleteReason(mapOf("exit_reason" to "helper_plugin_error")),
        )
    }

    @Test
    @DisplayName("Non-empty unmapped inspection tree becomes a scoped fallback finding")
    fun testUnmappedInspectionFallbackFindingForSingleFileScope() {
        val problems = buildUnmappedInspectionFallbackProblems(
            project = mockProject,
            captureScope = InspectionCaptureScope(
                scopeParam = "files",
                files = listOf("src/main/java/RedFixture.java"),
            ),
            diagnostic = mapOf(
                "observed_non_empty_inspection_tree" to true,
                "model_problem_descriptor_count" to 0,
                "last_view_observation" to mapOf("root_child_count" to 1),
            ),
        )

        assertEquals(1, problems.size)
        val problem = problems.single()
        assertEquals("/tmp/TestProject/src/main/java/RedFixture.java", problem["file"])
        assertEquals("error", problem["severity"])
        assertEquals("UnmappedInspectionTree", problem["inspectionType"])
        assertEquals("unmapped_inspection_tree_fallback", problem["source"])
        assertEquals(false, problem["locationKnown"])
        assertTrue((problem["description"] as String).contains("non-empty problem tree"))
    }

    @Test
    @DisplayName("Clean or empty evidence does not create fallback findings")
    fun testUnmappedInspectionFallbackRequiresRedEvidence() {
        val problems = buildUnmappedInspectionFallbackProblems(
            project = mockProject,
            captureScope = InspectionCaptureScope(
                scopeParam = "files",
                files = listOf("src/main/java/CleanFixture.java"),
            ),
            diagnostic = mapOf(
                "observed_non_empty_inspection_tree" to false,
                "model_problem_descriptor_count" to 0,
            ),
        )

        assertEquals(emptyList<Map<String, Any>>(), problems)
    }

    @Test
    @DisplayName("Descriptor-only unmapped evidence becomes a fallback finding")
    fun testUnmappedInspectionFallbackFindingForDescriptorEvidence() {
        val problems = buildUnmappedInspectionFallbackProblems(
            project = mockProject,
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            diagnostic = mapOf(
                "observed_non_empty_inspection_tree" to false,
                "model_problem_descriptor_count" to 2,
            ),
        )

        assertEquals(1, problems.size)
        assertEquals("/tmp/TestProject", problems.single()["file"])
        assertTrue((problems.single()["description"] as String).contains("2 descriptor(s)"))
    }

    @Test
    @DisplayName("Whole-project non-empty tree evidence becomes a fallback finding")
    fun testUnmappedInspectionFallbackFindingForWholeProjectTreeEvidence() {
        val problems = buildUnmappedInspectionFallbackProblems(
            project = mockProject,
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            diagnostic = mapOf(
                "observed_non_empty_inspection_tree" to true,
                "model_problem_descriptor_count" to 0,
                "last_view_observation" to mapOf("root_child_count" to 2),
            ),
        )

        assertEquals(1, problems.size)
        assertEquals("/tmp/TestProject", problems.single()["file"])
        assertTrue((problems.single()["description"] as String).contains("non-empty problem tree"))
    }

    @Test
    @DisplayName("Unmapped fallback remains actionable when project base path is unavailable")
    fun testUnmappedInspectionFallbackWithoutProjectBasePath() {
        val projectWithoutBasePath = mockk<Project>()
        every { projectWithoutBasePath.basePath } returns null

        val problems = buildUnmappedInspectionFallbackProblems(
            project = projectWithoutBasePath,
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            diagnostic = mapOf("observed_non_empty_inspection_tree" to true),
        )

        assertEquals(1, problems.size)
        assertEquals("unknown", problems.single()["file"])
        assertEquals(false, problems.single()["locationKnown"])
    }

    @Test
    @DisplayName("Transient updating unreadable empty views provide guarded clean evidence")
    fun testTransientUpdatingUnreadableEmptyViewEvidence() {
        val transientUpdatingUnreadableEmpty = InspectionViewObservation(
            isUpdating = true,
            hasProblems = false,
            rootChildCount = 0,
            updateStateReadable = true,
            problemStateReadable = true,
        )

        assertTrue(isTransientUpdatingUnreadableEmptyCandidate(transientUpdatingUnreadableEmpty))
        assertFalse(isTransientUpdatingUnreadableEmptyCandidate(transientUpdatingUnreadableEmpty.copy(hasProblems = true)))
        assertFalse(isTransientUpdatingUnreadableEmptyCandidate(transientUpdatingUnreadableEmpty.copy(updateStateReadable = false)))
        assertFalse(isTransientUpdatingUnreadableEmptyCandidate(transientUpdatingUnreadableEmpty.copy(problemStateReadable = false)))
        assertFalse(isTransientUpdatingUnreadableEmptyCandidate(transientUpdatingUnreadableEmpty.copy(isUpdating = false)))
        assertFalse(isTransientUpdatingUnreadableEmptyCandidate(transientUpdatingUnreadableEmpty.copy(rootChildCount = null)))
        assertFalse(isTransientUpdatingUnreadableEmptyCandidate(transientUpdatingUnreadableEmpty.copy(rootChildCount = 1)))
    }

    @Test
    @DisplayName("Opaque settled empty views provide guarded clean evidence")
    fun testOpaqueSettledEmptyViewEvidence() {
        val opaqueSettledEmpty = InspectionViewObservation(
            isUpdating = false,
            hasProblems = false,
            rootChildCount = null,
            updateStateReadable = true,
            problemStateReadable = true,
        )

        assertTrue(isOpaqueSettledEmptyInspectionViewCandidate(opaqueSettledEmpty))
        assertFalse(isOpaqueSettledEmptyInspectionViewCandidate(opaqueSettledEmpty.copy(hasProblems = true)))
        assertFalse(isOpaqueSettledEmptyInspectionViewCandidate(opaqueSettledEmpty.copy(isUpdating = true)))
        assertFalse(isOpaqueSettledEmptyInspectionViewCandidate(opaqueSettledEmpty.copy(updateStateReadable = false)))
        assertFalse(isOpaqueSettledEmptyInspectionViewCandidate(opaqueSettledEmpty.copy(problemStateReadable = false)))
        assertFalse(isOpaqueSettledEmptyInspectionViewCandidate(opaqueSettledEmpty.copy(rootChildCount = 0)))
    }

    @Test
    @DisplayName("Stable readable empty views require repeated positive evidence")
    fun testStableReadableEmptyViewRequiresRepeatedPositiveEvidence() {
        assertFalse(
            shouldPromoteStableReadableEmptyInspectionView(
                readableEmptyInspectionViewStableSince = 1000L,
                readableEmptyInspectionViewObservationCount = 1,
                inspectionViewUpdating = false,
                now = 7000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertTrue(
            shouldPromoteStableReadableEmptyInspectionView(
                readableEmptyInspectionViewStableSince = 1000L,
                readableEmptyInspectionViewObservationCount = 2,
                inspectionViewUpdating = false,
                now = 7000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertFalse(
            shouldPromoteStableReadableEmptyInspectionView(
                readableEmptyInspectionViewStableSince = 1000L,
                readableEmptyInspectionViewObservationCount = 2,
                inspectionViewUpdating = false,
                now = 7000L,
                pollingElapsedMs = 29999L,
            )
        )
    }

    @Test
    @DisplayName("Stable transient empty views require repeated guarded evidence")
    fun testStableTransientEmptyViewRequiresRepeatedGuardedEvidence() {
        assertFalse(
            shouldPromoteStableReadableEmptyInspectionView(
                readableEmptyInspectionViewStableSince = 1000L,
                readableEmptyInspectionViewObservationCount = 0,
                transientUpdatingEmptyObservationCount = 4,
                inspectionViewUpdating = false,
                now = 7000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertTrue(
            shouldPromoteStableReadableEmptyInspectionView(
                readableEmptyInspectionViewStableSince = 1000L,
                readableEmptyInspectionViewObservationCount = 0,
                transientUpdatingEmptyObservationCount = 5,
                inspectionViewUpdating = false,
                now = 7000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertFalse(
            shouldPromoteStableReadableEmptyInspectionView(
                readableEmptyInspectionViewStableSince = 1000L,
                readableEmptyInspectionViewObservationCount = 0,
                transientUpdatingEmptyObservationCount = 5,
                inspectionViewUpdating = false,
                now = 7000L,
                pollingElapsedMs = 29999L,
            )
        )

        assertFalse(
            shouldPromoteStableReadableEmptyInspectionView(
                readableEmptyInspectionViewStableSince = 1000L,
                readableEmptyInspectionViewObservationCount = 0,
                transientUpdatingEmptyObservationCount = 5,
                inspectionViewUpdating = true,
                now = 7000L,
                pollingElapsedMs = 30000L,
            )
        )
    }

    @Test
    @DisplayName("Stable scoped empty results can confirm clean without readable view evidence")
    fun testStableScopedEmptyResultsConfirmCleanBeforeDeadline() {
        assertFalse(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 29999L,
            )
        )

        assertTrue(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertTrue(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = false,
                hasSettledInspectionViewEvidence = false,
                hasOpaqueSettledEmptyInspectionViewEvidence = true,
                extractionSucceeded = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertFalse(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                extractionSucceeded = false,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = true,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )
    }

    @Test
    @DisplayName("Scoped empty results do not hide extractor failures")
    fun testStableScopedEmptyResultsRequireSuccessfulExtraction() {
        assertFalse(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                extractionSucceeded = false,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertFalse(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = false,
                hasSettledInspectionViewEvidence = true,
                extractionSucceeded = false,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertTrue(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                extractionSucceeded = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )
    }

    @Test
    @DisplayName("Scoped empty results wait for observed inspection views to settle")
    fun testStableScopedEmptyResultsWaitForObservedInspectionViewToSettle() {
        assertFalse(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                hasSettledInspectionViewEvidence = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertFalse(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                hasTransientEmptyInspectionViewEvidence = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 59999L,
            )
        )

        assertFalse(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                hasTransientEmptyInspectionViewEvidence = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = true,
                stableForMs = 5000L,
                pollingElapsedMs = 60000L,
            )
        )

        assertTrue(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                hasTransientEmptyInspectionViewEvidence = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 60000L,
            )
        )

        assertFalse(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = false,
                hasSettledInspectionViewEvidence = false,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertTrue(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = false,
                hasSettledInspectionViewEvidence = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )
    }

    @Test
    @DisplayName("Model-clean evidence can prove scoped empty results while the view is updating")
    fun testModelCleanEvidenceCanProveScopedEmptyResults() {
        assertFalse(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                hasModelCleanEvidence = true,
                extractionSucceeded = false,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertFalse(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                hasModelCleanEvidence = true,
                extractionSucceeded = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = true,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )

        assertFalse(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                hasModelCleanEvidence = true,
                extractionSucceeded = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 4999L,
                pollingElapsedMs = 30000L,
            )
        )

        assertTrue(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                hasModelCleanEvidence = true,
                extractionSucceeded = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 5000L,
                pollingElapsedMs = 30000L,
            )
        )
    }

    @Test
    @DisplayName("Safe transient empty view evidence latches for scoped empty extraction")
    fun testTransientEmptyEvidenceCanUseSuccessfulToolExtraction() {
        assertFalse(
            shouldTreatScopedEmptyExtractionAsSucceeded(
                lastExtractionCycleSucceeded = false,
                observedTransientEmptyInspectionViewEvidence = false,
                lastToolExtractionSucceeded = true,
            )
        )

        assertFalse(
            shouldTreatScopedEmptyExtractionAsSucceeded(
                lastExtractionCycleSucceeded = false,
                observedTransientEmptyInspectionViewEvidence = true,
                lastToolExtractionSucceeded = false,
            )
        )

        assertTrue(
            shouldTreatScopedEmptyExtractionAsSucceeded(
                lastExtractionCycleSucceeded = false,
                observedTransientEmptyInspectionViewEvidence = true,
                lastToolExtractionSucceeded = true,
            )
        )

        assertTrue(
            shouldTreatScopedEmptyExtractionAsSucceeded(
                lastExtractionCycleSucceeded = true,
                observedTransientEmptyInspectionViewEvidence = false,
                lastToolExtractionSucceeded = false,
            )
        )
    }

    @Test
    @DisplayName("Updating problem-free views can prove scoped empty results after deadline")
    fun testUpdatingProblemFreeViewsCanProveScopedEmptyResults() {
        val updatingProblemFreeView = InspectionViewObservation(
            isUpdating = true,
            hasProblems = false,
            rootChildCount = 0,
            updateStateReadable = true,
            problemStateReadable = true,
        )

        assertTrue(isTransientUpdatingUnreadableEmptyCandidate(updatingProblemFreeView))
        assertTrue(
            shouldTreatScopedEmptyExtractionAsSucceeded(
                lastExtractionCycleSucceeded = false,
                observedTransientEmptyInspectionViewEvidence = true,
                lastToolExtractionSucceeded = true,
            )
        )
        assertTrue(
            shouldTrustStableScopedEmptyResults(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                hasTransientEmptyInspectionViewEvidence = true,
                extractionSucceeded = true,
                hasScopedMatcher = true,
                scopedContextResultsEmpty = true,
                bestResultsEmpty = true,
                observedNonEmptyInspectionTree = false,
                stableForMs = 60000L,
                pollingElapsedMs = 60000L,
            )
        )
    }

    @Test
    @DisplayName("Problem-free updating views without a readable empty tree do not prove emptiness")
    fun testProblemFreeUpdatingViewsWithoutZeroChildTreeDoNotProveEmptiness() {
        val updatingProblemFreeView = InspectionViewObservation(
            isUpdating = true,
            hasProblems = false,
            rootChildCount = null,
            updateStateReadable = true,
            problemStateReadable = true,
        )

        assertFalse(isTransientUpdatingUnreadableEmptyCandidate(updatingProblemFreeView))
    }

    @Test
    @DisplayName("Settled views without problems are clean even when the tree has grouping nodes")
    fun testSettledCleanInspectionViewAllowsGroupingNodes() {
        val groupedCleanView = InspectionViewObservation(
            isUpdating = false,
            hasProblems = false,
            rootChildCount = 3,
        )

        assertTrue(isSettledCleanInspectionView(groupedCleanView))
        assertFalse(hasInspectionViewProblems(groupedCleanView))
    }

    @Test
    @DisplayName("Root child count falls back to reflective getChildCount access")
    fun testReadInspectionRootChildCountFallback() {
        class ReflectiveRoot(private val childCount: Int) {
            @Suppress("unused")
            fun getChildCount(): Int = childCount
        }

        class OpaqueRoot

        assertEquals(4, readInspectionRootChildCount(ReflectiveRoot(4)))
        assertEquals(null, readInspectionRootChildCount(OpaqueRoot()))
        assertEquals(null, readInspectionRootChildCount(null))
    }

    @Test
    @DisplayName("Settled clean inspection views require finished problem state")
    fun testSettledCleanInspectionViewRequiresFinishedProblemState() {
        assertFalse(
            isReadableEmptyInspectionView(
                InspectionViewObservation(
                    isUpdating = true,
                    hasProblems = false,
                    rootChildCount = 0,
                    problemStateReadable = false,
                )
            )
        )

        assertFalse(
            isReadableEmptyInspectionView(
                InspectionViewObservation(
                    isUpdating = true,
                    hasProblems = false,
                    rootChildCount = 0,
                    problemStateReadable = true,
                )
            )
        )

        assertFalse(
            isSettledCleanInspectionView(
                InspectionViewObservation(
                    isUpdating = true,
                    hasProblems = false,
                    rootChildCount = 0,
                )
            )
        )

        assertFalse(
            isSettledCleanInspectionView(
                InspectionViewObservation(
                    isUpdating = false,
                    hasProblems = true,
                    rootChildCount = null,
                )
            )
        )

        assertTrue(
            isSettledCleanInspectionView(
                InspectionViewObservation(
                    isUpdating = false,
                    hasProblems = false,
                    rootChildCount = 0,
                )
            )
        )

        assertFalse(
            isSettledCleanInspectionView(
                InspectionViewObservation(
                    isUpdating = false,
                    hasProblems = false,
                    rootChildCount = null,
                )
            )
        )

        assertFalse(
            isSettledCleanInspectionView(
                InspectionViewObservation(
                    isUpdating = false,
                    hasProblems = false,
                    rootChildCount = null,
                    updateStateReadable = false,
                    problemStateReadable = true,
                )
            )
        )

        assertFalse(
            isSettledCleanInspectionView(
                InspectionViewObservation(
                    isUpdating = false,
                    hasProblems = false,
                    rootChildCount = null,
                    updateStateReadable = true,
                    problemStateReadable = false,
                )
            )
        )

        assertFalse(
            isReadableEmptyInspectionView(
                InspectionViewObservation(
                    isUpdating = false,
                    hasProblems = false,
                    rootChildCount = 1,
                )
            )
        )

        assertFalse(
            isReadableEmptyInspectionView(
                InspectionViewObservation(
                    isUpdating = false,
                    hasProblems = true,
                    rootChildCount = 0,
                )
            )
        )
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
                observedStableReadableEmptyInspectionView = false,
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
                observedStableReadableEmptyInspectionView = false,
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
                observedStableReadableEmptyInspectionView = false,
                bestResultsCount = 0,
                stableForMs = 4000,
                pollingElapsedMs = 16000,
            )
        )

        assertFalse(
            shouldStopCapturePolling(
                viewReadyOk = false,
                observedInspectionView = false,
                inspectionViewUpdating = false,
                observedSettledEmptyInspectionView = false,
                observedStableReadableEmptyInspectionView = false,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 16000,
            )
        )

        assertTrue(
            shouldStopCapturePolling(
                viewReadyOk = false,
                observedInspectionView = false,
                inspectionViewUpdating = false,
                observedSettledEmptyInspectionView = false,
                observedStableReadableEmptyInspectionView = false,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 60000,
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
                observedStableReadableEmptyInspectionView = false,
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
                observedStableReadableEmptyInspectionView = false,
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
                observedStableReadableEmptyInspectionView = false,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 16000,
            )
        )

        assertFalse(
            shouldStopCapturePolling(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                observedSettledEmptyInspectionView = false,
                observedStableReadableEmptyInspectionView = true,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 20000,
            )
        )

        assertFalse(
            shouldStopCapturePolling(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                observedSettledEmptyInspectionView = false,
                observedStableReadableEmptyInspectionView = true,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 30000,
            )
        )

        assertTrue(
            shouldStopCapturePolling(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = false,
                observedSettledEmptyInspectionView = false,
                observedStableReadableEmptyInspectionView = true,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 30000,
            )
        )

        assertTrue(
            shouldStopCapturePolling(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                observedSettledEmptyInspectionView = false,
                observedStableReadableEmptyInspectionView = false,
                observedModelCleanInspection = true,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 30000,
            )
        )
    }

    @Test
    @DisplayName("Model extraction verdict distinguishes clean from unreadable empty results")
    fun testInspectionModelExtractionVerdict() {
        assertEquals(
            InspectionModelVerdict.CLEAN,
            InspectionModelExtraction(
                problems = emptyList(),
                problemDescriptorCount = 0,
                enabledToolCount = 3,
                readableToolCount = 3,
                unreadableToolCount = 0,
                unreadableReasons = emptyList(),
            ).verdict,
        )

        assertEquals(
            InspectionModelVerdict.HAS_PROBLEMS,
            InspectionModelExtraction(
                problems = listOf(mapOf("description" to "warning")),
                problemDescriptorCount = 1,
                enabledToolCount = 3,
                readableToolCount = 3,
                unreadableToolCount = 0,
                unreadableReasons = emptyList(),
            ).verdict,
        )

        assertEquals(
            InspectionModelVerdict.UNREADABLE,
            InspectionModelExtraction(
                problems = emptyList(),
                problemDescriptorCount = 0,
                enabledToolCount = 3,
                readableToolCount = 2,
                unreadableToolCount = 1,
                unreadableReasons = listOf("descriptors:IllegalStateException"),
            ).verdict,
        )

        assertEquals(
            InspectionModelVerdict.HAS_PROBLEMS,
            InspectionModelExtraction(
                problems = emptyList(),
                problemDescriptorCount = 1,
                enabledToolCount = 3,
                readableToolCount = 3,
                unreadableToolCount = 0,
                unreadableReasons = emptyList(),
            ).verdict,
        )

        assertEquals(
            InspectionModelVerdict.UNKNOWN,
            InspectionModelExtraction(
                problems = emptyList(),
                problemDescriptorCount = 0,
                enabledToolCount = 0,
                readableToolCount = 0,
                unreadableToolCount = 0,
                unreadableReasons = emptyList(),
            ).verdict,
        )
    }

    @Test
    @DisplayName("Capture polling stops once scoped empty results are trusted")
    fun testShouldStopCapturePollingForTrustedScopedEmptyResults() {
        assertTrue(
            shouldStopCapturePolling(
                viewReadyOk = true,
                observedInspectionView = true,
                inspectionViewUpdating = true,
                observedSettledEmptyInspectionView = false,
                observedStableReadableEmptyInspectionView = false,
                observedStableEmptyResultsWithoutInspectionView = true,
                bestResultsCount = 0,
                stableForMs = 6000,
                pollingElapsedMs = 30000,
            )
        )
    }

    @Test
    @DisplayName("Scoped problem filtering removes unrelated results")
    fun testFilterProblemsForScope() {
        val scopedProblems = listOf(
            mapOf("file" to "/tmp/TestProject/README.md", "description" to "in scope"),
            mapOf("file" to "/tmp/TestProject/docs/guide.md", "description" to "also in scope"),
            mapOf("file" to "/tmp/OtherProject/README.md", "description" to "out of scope"),
        )

        val filtered = filterProblemsForScope(scopedProblems) { problem ->
            (problem["file"] as? String)?.startsWith("/tmp/TestProject/") == true
        }

        assertEquals(2, filtered.size)
        assertTrue(filtered.all { (it["file"] as String).startsWith("/tmp/TestProject/") })
        assertEquals(scopedProblems, filterProblemsForScope(scopedProblems, null))
    }

    @Test
    @DisplayName("Opaque inspection views do not count as usable empty-view evidence")
    fun testHasUsableInspectionViewEvidence() {
        assertFalse(
            hasUsableInspectionViewEvidence(
                inspectionViewObservationCount = 0,
                nullRootChildObservationCount = 0,
                observedSettledEmptyInspectionView = false,
                observedStableReadableEmptyInspectionView = false,
                observedNonEmptyInspectionTree = false,
            )
        )

        assertFalse(
            hasUsableInspectionViewEvidence(
                inspectionViewObservationCount = 1,
                nullRootChildObservationCount = 1,
                observedSettledEmptyInspectionView = false,
                observedStableReadableEmptyInspectionView = false,
                observedNonEmptyInspectionTree = false,
            )
        )

        assertTrue(
            hasUsableInspectionViewEvidence(
                inspectionViewObservationCount = 2,
                nullRootChildObservationCount = 1,
                observedSettledEmptyInspectionView = false,
                observedStableReadableEmptyInspectionView = false,
                observedNonEmptyInspectionTree = false,
            )
        )

        assertTrue(
            hasUsableInspectionViewEvidence(
                inspectionViewObservationCount = 1,
                nullRootChildObservationCount = 1,
                observedSettledEmptyInspectionView = true,
                observedStableReadableEmptyInspectionView = false,
                observedNonEmptyInspectionTree = false,
            )
        )
    }

    @Test
    @DisplayName("Whole-project no-view capture keeps tool-window results")
    fun testSelectTrustedToolResultsForWholeProjectCapture() {
        val toolResults = listOf(
            mapOf("file" to "/tmp/TestProject/src/app.js", "description" to "warning")
        )
        val compatibleToolResults = emptyList<Map<String, Any>>()

        assertEquals(
            toolResults,
            selectTrustedToolResults(
                toolResults = toolResults,
                compatibleToolResults = compatibleToolResults,
                observedInspectionView = false,
                hasScopedMatcher = false,
            )
        )

        assertEquals(
            compatibleToolResults,
            selectTrustedToolResults(
                toolResults = toolResults,
                compatibleToolResults = compatibleToolResults,
                observedInspectionView = false,
                hasScopedMatcher = true,
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
        return getInspectionProblems(includeStale = false)
    }

    private fun getInspectionProblems(
        scope: String = "whole_project",
        includeStale: Boolean = false,
        limit: Int = 100,
        offset: Int = 0,
    ): String {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "getInspectionProblems",
            Project::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(handler, mockProject, "all", scope, null, null, limit, offset, includeStale) as String
    }

    private fun staleProblem(
        description: String = "Old warning",
        file: String = "/tmp/TestProject/src/app.js",
    ): Map<String, Any> {
        return mapOf(
            "description" to description,
            "file" to file,
            "line" to 12,
            "column" to 4,
            "severity" to "weak_warning",
            "inspectionType" to "JSUnresolvedReference",
        )
    }

    private fun waitForInspection(timeoutMs: Long = 1000L, pollMs: Long = 200L): String {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "waitForInspection",
            String::class.java,
            Long::class.javaObjectType,
            Long::class.javaObjectType,
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
