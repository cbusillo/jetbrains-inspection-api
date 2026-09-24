package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Assertions.*
import io.mockk.*
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.openapi.fileEditor.FileDocumentManager
import io.netty.handler.codec.http.HttpResponseStatus
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class InspectionHandlerResultsTest : InspectionHandlerTestSupport() {
    @Test
    fun `test problems endpoint accepts grammar and typo severity filters`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.clear(projectKey(mockProject))

        listOf("grammar", "typo").forEach { severity ->
            val response = processGetRequest("/api/inspection/problems?severity=$severity")
            val body = response.content().toString(Charsets.UTF_8)

            assertEquals(HttpResponseStatus.OK, response.status(), body)
            assertTrue(body.contains("\"severity\": \"$severity\""), body)
        }
    }

    @Test
    fun `test problems endpoint without snapshot never trusts live tool window scrape`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.clear(projectKey(mockProject))
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(mockProject) } returns ProblemExtractionResult(
            problems = listOf(
                mapOf(
                    "file" to "/tmp/TestProject/src/LiveOnly.kt",
                    "severity" to "warning",
                    "description" to "unverified live finding",
                ),
            ),
            succeeded = true,
            source = ProblemExtractionSource.INSPECTION_RESULTS,
        )
        enhancedTreeExtractorFactory = { extractor }

        val unfilteredBody = processGetRequest("/api/inspection/problems?severity=all")
            .content().toString(Charsets.UTF_8)
        val filteredBody = processGetRequest("/api/inspection/problems?severity=error")
            .content().toString(Charsets.UTF_8)

        listOf(unfilteredBody, filteredBody).forEach { body ->
            assertTrue(body.contains("\"status\": \"no_results\""), body)
            assertTrue(body.contains("\"inspection_verdict\": \"UNKNOWN\""), body)
            assertTrue(body.contains("\"total_problems\": 0"), body)
            assertFalse(body.contains("unverified live finding"), body)
        }
        verify(exactly = 0) { extractor.extractAllProblemsWithStatus(mockProject) }
    }

    @Test
    fun `test problems endpoint applies requested files scope to cached snapshot`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockIncludedLocalFile()
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.setSnapshot(
            projectKey(mockProject),
            InspectionResultsSnapshot(
                problems = listOf(
                    mapOf(
                        "file" to "/tmp/TestProject/src/Included.kt",
                        "line" to 1,
                        "column" to 1,
                        "severity" to "warning",
                        "inspectionType" to "Included",
                        "description" to "included problem",
                    ),
                    mapOf(
                        "file" to "/tmp/TestProject/src/Excluded.kt",
                        "line" to 1,
                        "column" to 1,
                        "severity" to "warning",
                        "inspectionType" to "Excluded",
                        "description" to "excluded problem",
                    ),
                ),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "test",
                runId = 1L,
            )
        )

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
            String::class.java,
            List::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaObjectType,
        )
        method.isAccessible = true
        val body = method.invoke(
            handler,
            mockProject,
            "all",
            "files",
            null,
            null,
            100,
            0,
            false,
            null,
            listOf("src/Included.kt"),
            true,
            null,
            null,
        ) as String

        assertTrue(body.contains("\"total_problems\": 1"))
        assertTrue(body.contains("included problem"))
        assertFalse(body.contains("excluded problem"))
        assertTrue(body.contains("\"scope\": \"files\""))
        assertTrue(body.contains("\"files_requested\": 1"))
    }

    @Test
    fun `test clean problems fail closed when aggregate semantic coverage is missing`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.setSnapshot(
            projectKey(mockProject),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "test",
                captureDiagnostic = semanticCoverageGapDiagnostic(),
                runId = 1L,
            ),
        )

        val response = processGetRequest("/api/inspection/problems")
        val body = response.content().toString(Charsets.UTF_8)

        assertTrue(body.contains("\"total_problems\": 0"), body)
        assertTrue(body.contains("\"inspection_verdict\": \"UNKNOWN\""), body)
        assertTrue(body.contains("\"inspection_verdict_reason\": \"scope_semantic_coverage_missing\""), body)
        assertTrue(body.contains("\"classification\": \"configuration_blocked\""), body)
    }

    @Test
    fun `test problems endpoint does not refresh project state before reading snapshot`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        val includedFile = mockk<VirtualFile>()
        val localFileSystem = mockk<LocalFileSystem>()
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath("/tmp/TestProject/src/Included.kt") } returns includedFile
        every { includedFile.path } returns "/tmp/TestProject/src/Included.kt"
        every { includedFile.isValid } returns true
        every { includedFile.isDirectory } returns false
        every { includedFile.isInLocalFileSystem } returns true
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.setSnapshot(
            projectKey(mockProject),
            InspectionResultsSnapshot(
                problems = listOf(
                    mapOf(
                        "file" to "/tmp/TestProject/src/Included.kt",
                        "severity" to "warning",
                        "description" to "included problem",
                    ),
                ),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "test",
                runId = 1L,
            )
        )

        val response = processGetRequest("/api/inspection/problems?scope=files&file=src/Included.kt")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"results_available\""))
        assertFalse(body.contains("\"status\": \"stale_results\""))
        val fileDocumentManager = FileDocumentManager.getInstance()
        verify(exactly = 0) { fileDocumentManager.saveAllDocuments() }
    }

    @Test
    fun `test completed run snapshot becomes stale after psi change`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockIncludedLocalFile()
        mockInspectionPrerequisites(mockProject)
        setInspectionRunState(projectKey(mockProject), InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = false))
        val snapshotProblems = listOf(
            mapOf(
                "file" to "/tmp/TestProject/src/Included.kt",
                "severity" to "warning",
                "description" to "included problem",
            ),
            mapOf(
                "file" to "/tmp/TestProject/src/Excluded.kt",
                "severity" to "warning",
                "description" to "excluded problem",
            ),
        )
        mockExtractor(snapshotProblems)
        InspectionResultsStore.setSnapshot(
            projectKey(mockProject),
            InspectionResultsSnapshot(
                problems = snapshotProblems,
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "test",
                captureScope = InspectionCaptureScope(scopeParam = "files", files = listOf("src/Included.kt")),
                runId = 1L,
            )
        )

        val body = getFileInspectionProblems(listOf("src/Included.kt"))

        assertTrue(body.contains("\"status\": \"stale_results\""))
        assertTrue(body.contains("\"cached_total_problems\": 1"))
        assertFalse(body.contains("included problem"))
        assertFalse(body.contains("excluded problem"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["whole_project", "files"])
    fun `test final publication reconciles verified psi churn without absorbing later edits`(scope: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val psiModificationCount = AtomicLong(11L)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } answers {
            psiModificationCount.get()
        }
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        val contentTracker = FakeInspectionProjectContentTracker()
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        val snapshotProblems = listOf(
            mapOf(
                "file" to "/tmp/TestProject/src/Included.kt",
                "line" to 4,
                "column" to 7,
                "severity" to "warning",
                "inspectionType" to "CurrentRunInspection",
                "description" to "current run finding",
                "source" to "inspection_context",
            ),
        )
        val liveProblems = snapshotProblems.map { problem ->
            problem + mapOf(
                "source" to "enhanced_tree_extractor",
                "locationKnown" to true,
            )
        }
        val extractionCount = AtomicInteger()
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(mockProject) } answers {
            if (extractionCount.getAndIncrement() == 0) {
                assertNull(InspectionResultsStore.getSnapshot(key))
            }
            ProblemExtractionResult(
                problems = liveProblems,
                succeeded = true,
                source = ProblemExtractionSource.INSPECTION_RESULTS,
            )
        }
        enhancedTreeExtractorFactory = { extractor }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = snapshotProblems,
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
            source = "global_context",
            captureScope = publicationScope(scope),
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = contentTracker,
        )
        val reconciledSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        verify(exactly = 1) {
            mockApplication.runReadAction(any<ThrowableComputable<Any, Exception>>())
        }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = false),
        )
        val completedStatus = buildInspectionStatus()
        psiModificationCount.set(12L)
        val editedStatus = buildInspectionStatus()

        assertEquals(11L, reconciledSnapshot.projectState.psiModificationCount)
        assertEquals(false, completedStatus["results_may_be_stale"])
        assertEquals(if (scope == "files") "current_run_psi_churn" else "fresh", completedStatus["snapshot_change_kind"])
        assertEquals(true, completedStatus["has_inspection_results"])
        assertEquals(true, editedStatus["results_may_be_stale"])
        assertEquals("project_changed_since_inspection", editedStatus["snapshot_change_kind"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["whole_project", "files"])
    fun `test final publication validates unchanged psi inputs before publishing`(scope: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 10L
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        val contentTracker = FakeInspectionProjectContentTracker()
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = listOf(
                mapOf(
                    "file" to "/tmp/TestProject/src/Included.kt",
                    "severity" to "warning",
                    "description" to "current run finding",
                ),
            ),
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
            source = "global_context",
            captureScope = publicationScope(scope),
            captureDiagnostic = if (scope == "files") exactFilesProof(false) else null,
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = snapshot.projectState,
            projectStateChangedDuringCapture = false,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = contentTracker,
        )

        assertEquals(snapshot, InspectionResultsStore.getSnapshot(key))
        verify(exactly = 1) {
            mockApplication.runReadAction(any<ThrowableComputable<Any, Exception>>())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["whole_project", "files"])
    fun `test final publication closes tracker race before fresh publication`(scope: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 10L
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        val contentTracker = FakeInspectionProjectContentTracker().apply {
            beforeRunIfUnchanged = {
                changed = true
                firstChange = "file:.venv/bin/activate_this.py"
            }
        }
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = emptyList(),
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
            source = "inspection_view",
            captureScope = publicationScope(scope),
            captureDiagnostic = if (scope == "files") exactFilesProof(true) else null,
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = snapshot.projectState,
            projectStateChangedDuringCapture = false,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = contentTracker,
        )

        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, publishedSnapshot.outcome)
        assertEquals(CaptureIncompleteReason.INSPECTION_INPUTS_CHANGED, publishedSnapshot.captureIncompleteReason)
        assertEquals("inputs_changed", publishedSnapshot.captureDiagnostic?.get("final_input_validation"))
        assertEquals(
            "file:.venv/bin/activate_this.py",
            publishedSnapshot.captureDiagnostic?.get("final_input_first_change"),
        )
    }

    @Test
    fun `test current file clean snapshot becomes unknown after failed churn validation`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 10L
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        handler.projectInputsFingerprintProvider = { _, _ -> projectInputsFingerprint(profileName = "ChangedProfile") }
        mockExtractor(emptyList())
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = emptyList(),
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 1),
            outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
            source = "inspection_view",
            captureScope = InspectionCaptureScope(
                scopeParam = "current_file",
                resolvedCurrentFile = "/tmp/TestProject/src/Probe.py",
            ),
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = false),
        )

        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        val status = buildInspectionStatus()

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, publishedSnapshot.outcome)
        assertEquals(CaptureIncompleteReason.INSPECTION_INPUTS_CHANGED, publishedSnapshot.captureIncompleteReason)
        assertEquals("inputs_changed", publishedSnapshot.captureDiagnostic?.get("final_input_validation"))
        assertEquals("UNKNOWN", status["inspection_verdict"])
    }

    @Test
    fun `test current file clean snapshot validates unchanged project inputs`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 10L
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        handler.projectInputsFingerprintProvider = { _, _ -> projectInputsFingerprint(profileName = "ChangedProfile") }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = emptyList(),
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
            source = "inspection_view",
            captureScope = InspectionCaptureScope(
                scopeParam = "current_file",
                resolvedCurrentFile = "/tmp/TestProject/src/Probe.py",
            ),
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = snapshot.projectState,
            projectStateChangedDuringCapture = false,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )

        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, publishedSnapshot.outcome)
        assertEquals(CaptureIncompleteReason.INSPECTION_INPUTS_CHANGED, publishedSnapshot.captureIncompleteReason)
        assertEquals("inputs_changed", publishedSnapshot.captureDiagnostic?.get("final_input_validation"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["whole_project", "files"])
    fun `test final publication rejects input drift without psi churn`(scope: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 10L
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        val changedFingerprint = inputFingerprint.copy(
            profileConfigurationHash = "changed-profile-configuration-hash",
        )
        val contentTracker = FakeInspectionProjectContentTracker()
        handler.projectInputsFingerprintProvider = { _, _ -> changedFingerprint }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = listOf(
                mapOf(
                    "file" to "/tmp/TestProject/src/Included.kt",
                    "severity" to "warning",
                    "description" to "current run finding",
                ),
            ),
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
            source = "global_context",
            captureScope = publicationScope(scope),
            captureDiagnostic = if (scope == "files") exactFilesProof(false) else null,
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = snapshot.projectState,
            projectStateChangedDuringCapture = false,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = contentTracker,
        )

        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, publishedSnapshot.outcome)
        assertEquals("inspection_input_validation", publishedSnapshot.source)
        assertEquals(CaptureIncompleteReason.INSPECTION_INPUTS_CHANGED, publishedSnapshot.captureIncompleteReason)
        assertTrue(publishedSnapshot.problems.isEmpty())
        assertEquals("inputs_changed", publishedSnapshot.captureDiagnostic?.get("final_input_validation"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["whole_project", "files"])
    fun `test final publication rejects tracked file changes without psi churn`(scope: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 10L
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        val contentTracker = FakeInspectionProjectContentTracker(changed = true)
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = emptyList(),
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
            source = "inspection_view",
            captureScope = publicationScope(scope),
            captureDiagnostic = if (scope == "files") exactFilesProof(true) else null,
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = snapshot.projectState,
            projectStateChangedDuringCapture = false,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = contentTracker,
        )

        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, publishedSnapshot.outcome)
        assertEquals(CaptureIncompleteReason.INSPECTION_INPUTS_CHANGED, publishedSnapshot.captureIncompleteReason)
        assertEquals("inputs_changed", publishedSnapshot.captureDiagnostic?.get("final_input_validation"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["whole_project", "files"])
    fun `test final publication rejects unavailable validation without psi churn`(scope: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = emptyList(),
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
            source = "inspection_view",
            captureScope = publicationScope(scope),
            captureDiagnostic = if (scope == "files") exactFilesProof(true) else null,
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = snapshot.projectState,
            projectStateChangedDuringCapture = false,
            inspectionInputFingerprint = null,
            projectContentTracker = null,
        )

        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, publishedSnapshot.outcome)
        assertEquals(CaptureIncompleteReason.HELPER_PLUGIN_ERROR, publishedSnapshot.captureIncompleteReason)
        assertEquals("validation_unavailable", publishedSnapshot.captureDiagnostic?.get("final_input_validation"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["whole_project", "files"])
    fun `test final publication keeps fail closed baseline for unsaved capture state`(scope: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        val contentTracker = FakeInspectionProjectContentTracker()
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        val snapshotProblems = listOf(
            mapOf(
                "file" to "/tmp/TestProject/src/Included.kt",
                "severity" to "warning",
                "description" to "current run finding",
            ),
        )
        mockExtractor(snapshotProblems)
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = snapshotProblems,
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
            source = "global_context",
            captureScope = publicationScope(scope),
            captureDiagnostic = if (scope == "files") exactFilesProof(false) else null,
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 1),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = contentTracker,
        )
        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = false),
        )
        val completedStatus = buildInspectionStatus()

        assertEquals(10L, publishedSnapshot.projectState.psiModificationCount)
        assertEquals(true, completedStatus["results_may_be_stale"])
        assertEquals("project_changed_since_inspection", completedStatus["snapshot_change_kind"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["whole_project", "files"])
    fun `test final publication rejects saved content changes during inspection`(scope: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        val contentTracker = FakeInspectionProjectContentTracker()
        handler.projectInputsFingerprintProvider = { _, _ ->
            contentTracker.changed = true
            inputFingerprint
        }
        val snapshotProblems = listOf(
            mapOf(
                "file" to "/tmp/TestProject/src/Included.kt",
                "severity" to "warning",
                "description" to "current run finding",
            ),
        )
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(mockProject) } answers {
            ProblemExtractionResult(
                problems = snapshotProblems,
                succeeded = true,
                source = ProblemExtractionSource.INSPECTION_RESULTS,
            )
        }
        enhancedTreeExtractorFactory = { extractor }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = snapshotProblems,
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
            source = "global_context",
            captureScope = publicationScope(scope),
            captureDiagnostic = if (scope == "files") exactFilesProof(false) else null,
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = contentTracker,
        )
        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = false),
        )
        assertEquals(10L, publishedSnapshot.projectState.psiModificationCount)
        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, publishedSnapshot.outcome)
        assertEquals(CaptureIncompleteReason.INSPECTION_INPUTS_CHANGED, publishedSnapshot.captureIncompleteReason)
        assertTrue(publishedSnapshot.problems.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["whole_project", "files"])
    fun `test final publication rejects project input changes during inspection`(scope: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        val changedFingerprint = inputFingerprint.copy(
            moduleSdkStates = listOf("TestProject\u0000Updated SDK\u0000Python SDK\u00003.14\u0000/tmp/python-3.14"),
        )
        val contentTracker = FakeInspectionProjectContentTracker()
        handler.projectInputsFingerprintProvider = { _, _ -> changedFingerprint }
        val snapshotProblems = listOf(
            mapOf(
                "file" to "/tmp/TestProject/src/Included.kt",
                "severity" to "warning",
                "description" to "current run finding",
            ),
        )
        mockExtractor(snapshotProblems)
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = snapshotProblems,
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
            source = "global_context",
            captureScope = publicationScope(scope),
            captureDiagnostic = if (scope == "files") exactFilesProof(false) else null,
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = contentTracker,
        )

        assertEquals(10L, requireNotNull(InspectionResultsStore.getSnapshot(key)).projectState.psiModificationCount)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `test files execution proof reconciles psi churn without a results window`(clean: Boolean) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        val contentTracker = FakeInspectionProjectContentTracker()
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        val snapshotProblems = if (clean) emptyList() else listOf(
            mapOf("file" to "/tmp/TestProject/src/Included.kt", "severity" to "warning", "description" to "current finding"),
        )
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(mockProject) } returns ProblemExtractionResult(
            emptyList(), true, ProblemExtractionSource.NONE,
        )
        enhancedTreeExtractorFactory = { extractor }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = snapshotProblems,
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            outcome = if (clean) InspectionSnapshotOutcome.CLEAN_CONFIRMED else InspectionSnapshotOutcome.PROBLEMS_FOUND,
            captureDiagnostic = exactFilesProof(clean),
            source = "inspection_view",
            captureScope = InspectionCaptureScope(
                scopeParam = "files",
                files = listOf("src/Included.kt"),
                resolvedFiles = listOf("/tmp/TestProject/src/Included.kt"),
            ),
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = contentTracker,
        )

        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = false),
        )
        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        val status = buildInspectionStatus()
        assertEquals(11L, publishedSnapshot.projectState.psiModificationCount)
        assertEquals(snapshot.outcome, publishedSnapshot.outcome)
        assertEquals(snapshotProblems, publishedSnapshot.problems)
        assertEquals(false, status["results_may_be_stale"])
        assertEquals(if (clean) "GREEN" else "RED", status["inspection_verdict"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["whole_project", "files"])
    fun `test final publication requires authoritative live extraction`(scope: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        val contentTracker = FakeInspectionProjectContentTracker()
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(mockProject) } returns ProblemExtractionResult(
            problems = emptyList(),
            succeeded = false,
            source = ProblemExtractionSource.NONE,
        )
        enhancedTreeExtractorFactory = { extractor }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        val snapshot = InspectionResultsSnapshot(
            problems = emptyList(),
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(psiModificationCount = 10L, unsavedProjectDocuments = 0),
            outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
            source = "inspection_view",
            captureScope = publicationScope(scope),
            runId = 1L,
        )

        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = contentTracker,
        )

        assertEquals(10L, requireNotNull(InspectionResultsStore.getSnapshot(key)).projectState.psiModificationCount)
    }

    @Test
    fun `test stale run cannot replace current stage or terminal state`() {
        val key = projectKey(mockProject)
        handler.inspectionRunNowNanos = { 4_000_000_000L }
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 22L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = true,
                runStartedNanos = 1_000_000_000L,
                stage = InspectionRunStage.SMART_WAIT,
                stageStartedNanos = 2_000_000_000L,
            ),
        )

        transitionInspectionRunStage(key, 21L, InspectionRunStage.NATIVE_EXECUTE)
        recordInspectionRunFailureDiagnostic(key, 21L, InspectionRunFailureSource.WAIT_TIMEOUT)
        finishInspectionRun(key, 21L)

        val state = requireNotNull(inspectionRunState(key))
        assertEquals(22L, state.runId)
        assertEquals(true, state.inProgress)
        assertEquals(InspectionRunStage.SMART_WAIT, state.stage)
        assertNull(state.terminalOutcome)
    }

    @Test
    fun `test wait timeout alone does not mark a normally completed run timed out`() {
        val key = projectKey(mockProject)
        setInspectionRunState(key, InspectionRunState(
            runId = 81L, triggerTimeMs = 1L, inProgress = true,
            failureDiagnostics = listOf(InspectionRunFailureDiagnostic(
                source = InspectionRunFailureSource.WAIT_TIMEOUT,
                stageAtFailure = InspectionRunStage.EXACT_PROOF,
                stageElapsedMs = 1L, runElapsedMs = 1L,
                dumbMode = false, workerThreadName = null, workerStack = emptyList(),
            )),
        ))
        finishInspectionRun(key, 81L)
        assertEquals(InspectionRunTerminalOutcome.COMPLETED, inspectionRunState(key)?.terminalOutcome)
    }

    @Test
    fun `test bounded scope diagnostics can publish clean snapshot with complete semantic proof`() {
        val fileDiagnostics = (1..26).map { index ->
            mapOf<String, Any?>(
                "path" to "/tmp/TestProject/src/File$index.kt",
                "valid" to true,
                "directory" to false,
                "file_type" to "Kotlin",
                "psi_language" to "kotlin",
                "psi_class" to "org.jetbrains.kotlin.psi.KtFile",
                "in_content" to true,
            )
        }
        val diagnostic = buildScopeFileDiagnosticPayload(
            scopeKind = "changed_files",
            resolutionStatus = "changed_files_resolved",
            directoryParam = null,
            requestedFileCount = 0,
            resolvedFileCount = 26,
            fileDiagnostics = fileDiagnostics,
        )

        assertEquals(26, diagnostic["scope_file_resolved_count"])
        assertEquals(25, diagnostic["scope_file_diagnostic_count"])
        assertEquals(25, diagnostic["scope_file_diagnostics_limit"])
        assertEquals(1, diagnostic["scope_file_diagnostics_omitted_count"])
        assertEquals(true, diagnostic["scope_file_diagnostics_truncated"])
        assertEquals(false, diagnostic["scope_file_diagnostics_complete"])
        assertEquals(true, diagnostic["scope_file_semantic_evidence_complete"])
        @Suppress("UNCHECKED_CAST")
        val semanticCoverage = diagnostic["scope_file_semantic_coverage"] as Map<String, Any?>
        assertEquals(26, semanticCoverage["evaluated_file_count"])
        assertEquals(0, semanticCoverage["unproven_file_count"])
        assertEquals(0, semanticCoverage["missing_file_count"])

        val snapshot = buildInspectionCaptureSnapshot(
            InspectionCaptureSnapshotInput(
                bestResults = emptyList(),
                bestSource = "inspection_view",
                snapshotTimeMs = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 1L, unsavedProjectDocuments = 0),
                emptyOutcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                emptyNote = "Inspection completed cleanly.",
                captureScope = InspectionCaptureScope(scopeParam = "changed_files"),
                captureDiagnostic = diagnostic,
                runId = 1L,
                triggerTimeMs = System.currentTimeMillis(),
            ),
        )

        assertEquals(InspectionSnapshotOutcome.CLEAN_CONFIRMED, snapshot.outcome)
        assertNull(snapshot.captureIncompleteReason)
    }

    @Test
    fun `test scope file diagnostics emit explicit exclusion and coverage role`() {
        val file = mockk<VirtualFile>()
        val fileType = mockk<FileType>()
        val projectIndex = mockk<ProjectFileIndex>()
        val psiManager = mockk<PsiManager>()
        val psiFile = mockk<PsiFile>()
        val language = mockk<Language>()
        every { file.path } returns "/tmp/TestProject/uv.lock"
        every { file.isValid } returns true
        every { file.isDirectory } returns false
        every { file.fileType } returns fileType
        every { fileType.name } returns "PLAIN_TEXT"
        every { projectIndex.isInContent(file) } returns false
        every { projectIndex.isInSourceContent(file) } returns false
        every { projectIndex.isExcluded(file) } returns true
        every { psiManager.findFile(file) } returns psiFile
        every { psiFile.language } returns language
        every { language.id } returns "TEXT"

        mockkStatic(ProjectFileIndex::class)
        mockkStatic(PsiManager::class)
        mockkStatic(ModuleUtilCore::class)
        every { ProjectFileIndex.getInstance(mockProject) } returns projectIndex
        every { PsiManager.getInstance(mockProject) } returns psiManager
        every { ModuleUtilCore.findModuleForFile(file, mockProject) } returns null
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "inspectVirtualFileForDiagnostics",
            Project::class.java,
            VirtualFile::class.java,
        )
        method.isAccessible = true

        try {
            @Suppress("UNCHECKED_CAST")
            val diagnostic = method.invoke(handler, mockProject, file) as Map<String, Any?>
            assertEquals(true, diagnostic["is_excluded"])
            assertEquals("excluded_dependency_lockfile", diagnostic["coverage_role"])
        } finally {
            unmockkStatic(ProjectFileIndex::class)
            unmockkStatic(PsiManager::class)
            unmockkStatic(ModuleUtilCore::class)
        }
    }

    @Test
    fun `test legacy truncated scope diagnostics still fail closed`() {
        val diagnostic = mapOf<String, Any?>(
            "scope_file_resolved_count" to 26,
            "scope_file_diagnostic_count" to 25,
            "scope_file_diagnostics_omitted_count" to 1,
            "scope_file_diagnostics_truncated" to true,
            "scope_file_diagnostics_complete" to false,
            "scope_file_diagnostics" to emptyList<Map<String, Any?>>(),
        )

        val snapshot = buildInspectionCaptureSnapshot(
            InspectionCaptureSnapshotInput(
                bestResults = emptyList(),
                bestSource = "inspection_view",
                snapshotTimeMs = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 1L, unsavedProjectDocuments = 0),
                emptyOutcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                emptyNote = "Inspection completed cleanly.",
                captureScope = InspectionCaptureScope(scopeParam = "changed_files"),
                captureDiagnostic = diagnostic,
                runId = 1L,
                triggerTimeMs = System.currentTimeMillis(),
            ),
        )

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, snapshot.outcome)
        assertEquals(CaptureIncompleteReason.SCOPE_NOT_COVERED, snapshot.captureIncompleteReason)
    }

    @Test
    fun `test findings snapshot preserves truncated scope diagnostics`() {
        val diagnostic = mapOf<String, Any?>(
            "scope_file_resolved_count" to 26,
            "scope_file_diagnostic_count" to 25,
            "scope_file_diagnostics_omitted_count" to 1,
            "scope_file_diagnostics_truncated" to true,
            "scope_file_diagnostics_complete" to false,
            "scope_file_diagnostics" to emptyList<Map<String, Any?>>(),
        )
        val snapshot = buildInspectionCaptureSnapshot(
            InspectionCaptureSnapshotInput(
                bestResults = listOf(mapOf("description" to "actionable finding")),
                bestSource = "inspection_view",
                snapshotTimeMs = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 1L, unsavedProjectDocuments = 0),
                emptyOutcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                emptyNote = null,
                captureScope = InspectionCaptureScope(scopeParam = "changed_files"),
                captureDiagnostic = diagnostic,
                runId = 1L,
                triggerTimeMs = System.currentTimeMillis(),
            ),
        )

        assertEquals(InspectionSnapshotOutcome.PROBLEMS_FOUND, snapshot.outcome)
        assertEquals(diagnostic, snapshot.captureDiagnostic)
    }

    @Test
    fun `test completed scoped run without snapshot remains unknown`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.clear(projectKey(mockProject))
        mockExtractor(emptyList())
        setInspectionRunState(
            projectKey(mockProject),
            InspectionRunState(
                runId = 1L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "files", files = listOf("src/Included.kt")),
            ),
        )

        val status = buildInspectionStatus()

        assertEquals(false, status["has_inspection_results"])
        assertEquals(false, status["clean_inspection"])
        assertEquals(0, status["total_problems"])
    }

    @Test
    fun `test status endpoint does not refresh project state`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { mockApplication.isDispatchThread } returns true

        val response = processGetRequest("/api/inspection/status")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"TestProject\""))
        val fileDocumentManager = FileDocumentManager.getInstance()
        verify(exactly = 0) { fileDocumentManager.saveAllDocuments() }
    }

    @Test
    fun `test wait endpoint executes on pooled thread`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)

        val response = processGetRequest(
            "/api/inspection/wait?timeout_ms=1000&poll_ms=200&client_run_id=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"completion_reason\":"))
        assertTrue(body.contains("\"inspection_attribution\":"), body)
        assertTrue(body.contains("\"schema_version\": 1"), body)
        assertTrue(body.contains("\"client_run_id\": \"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\""), body)
        verify(exactly = 1) { mockApplication.executeOnPooledThread(any<Runnable>()) }
    }

    @Test
    fun `test wait timeout preserves earlier capture failure and complete history`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = true,
                stage = InspectionRunStage.NATIVE_EXECUTE,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        recordInspectionRunFailureDiagnostic(key, 7L, InspectionRunFailureSource.CAPTURE_DEADLINE)
        transitionInspectionRunStage(key, 7L, InspectionRunStage.RESULT_SETTLING)

        val response = processGetRequest(
            "/api/inspection/wait?timeout_ms=1000&poll_ms=200&inspection_run_id=7"
        )
        val body = com.google.gson.JsonParser.parseString(response.content().toString(Charsets.UTF_8)).asJsonObject

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals("timeout", body["completion_reason"].asString)
        assertEquals("result_settling", body["inspection_stage"].asString)
        assertEquals("capture_deadline", body["inspection_failure_diagnostic"].asJsonObject["source"].asString)
        val history = body["inspection_failure_history"].asJsonArray
        assertEquals(listOf("capture_deadline", "wait_timeout"), history.map { it.asJsonObject["source"].asString })
    }

    @Test
    fun `test wait endpoint refuses a replacement inspection run`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.clear(projectKey(mockProject))
        setInspectionRunState(
            projectKey(mockProject),
            InspectionRunState(
                runId = 8L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = true,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )

        val response = processGetRequest(
            "/api/inspection/wait?timeout_ms=1000&poll_ms=200&inspection_run_id=7"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"run_changed\""))
        assertTrue(body.contains("\"expected_inspection_run_id\": 7"))
        assertTrue(body.contains("\"inspection_run_id\": 8"))
        assertTrue(body.contains("\"timed_out\": false"))
    }

    @Test
    fun `test wait endpoint refuses a snapshot from an older run`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        InspectionResultsStore.setSnapshot(
            key,
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
                runId = 6L,
            ),
        )

        val response = processGetRequest(
            "/api/inspection/wait?timeout_ms=1000&poll_ms=200&inspection_run_id=7"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"run_changed\""))
        assertTrue(body.contains("\"expected_inspection_run_id\": 7"))
        assertTrue(body.contains("\"inspection_run_id\": 7"))
        assertTrue(body.contains("\"snapshot_run_id\": 6"))
    }

    @Test
    fun `test wait endpoint keeps accepted run while its snapshot is pending`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.clear(projectKey(mockProject))
        setInspectionRunState(
            projectKey(mockProject),
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )

        val response = processGetRequest(
            "/api/inspection/wait?timeout_ms=1000&poll_ms=200&inspection_run_id=7"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"completion_reason\": \"timeout\""))
        assertTrue(body.contains("\"inspection_run_id\": 7"))
        assertFalse(body.contains("\"status\": \"run_changed\""))
    }

    @Test
    fun `test wait endpoint completes for settled semantic coverage gaps`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 10L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        InspectionResultsStore.setSnapshot(
            key,
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
                captureDiagnostic = semanticCoverageGapDiagnostic(),
                runId = 10L,
            ),
        )

        val response = processGetRequest(
            "/api/inspection/wait?timeout_ms=1000&poll_ms=200&inspection_run_id=10"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"wait_completed\": true"), body)
        assertTrue(body.contains("\"timed_out\": false"), body)
        assertTrue(body.contains("\"completion_reason\": \"scope_semantic_coverage_missing\""), body)
        assertTrue(body.contains("\"inspection_verdict\": \"UNKNOWN\""), body)
    }

    @Test
    fun `test wait endpoint keeps delayed truncated semantic coverage unknown`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 11L,
                triggerTimeMs = System.currentTimeMillis() - 20_000L,
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        InspectionResultsStore.setSnapshot(
            key,
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis() - 10_000L,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
                captureDiagnostic = semanticCoverageTruncatedDiagnostic(),
                runId = 11L,
            ),
        )

        val response = processGetRequest(
            "/api/inspection/wait?timeout_ms=1000&poll_ms=200&inspection_run_id=11"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"wait_completed\": true"), body)
        assertTrue(body.contains("\"timed_out\": false"), body)
        assertTrue(body.contains("\"completion_reason\": \"scope_semantic_coverage_truncated\""), body)
        assertTrue(body.contains("\"inspection_verdict\": \"UNKNOWN\""), body)
        assertFalse(body.contains("\"completion_reason\": \"clean\""), body)
    }

    @Test
    fun `test clean problems response includes decisive attribution`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        InspectionResultsStore.setSnapshot(
            key,
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
                runId = 7L,
            ),
        )

        val response = processGetRequest(
            "/api/inspection/problems?inspection_run_id=7&client_run_id=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"inspection_verdict\": \"GREEN\""), body)
        assertTrue(body.contains("\"classification\": \"decisive\""), body)
        assertTrue(body.contains("\"code\": \"no_matching_findings\""), body)
        assertTrue(body.contains("\"inspection_run_id\": 7"), body)
        assertTrue(body.contains("\"client_run_id\": \"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\""), body)
    }

    @Test
    fun `test clean status response includes decisive attribution`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 9L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        InspectionResultsStore.setSnapshot(
            key,
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
                runId = 9L,
            ),
        )

        val response = processGetRequest(
            "/api/inspection/status?client_run_id=cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"inspection_verdict\": \"GREEN\""), body)
        assertTrue(body.contains("\"inspection_verdict_reason\": \"clean_confirmed\""), body)
        assertTrue(body.contains("\"classification\": \"decisive\""), body)
        assertTrue(body.contains("\"code\": \"clean_confirmed\""), body)
        assertTrue(body.contains("\"inspection_run_id\": 9"), body)
        assertTrue(body.contains("\"client_run_id\": \"cccccccc-cccc-4ccc-8ccc-cccccccccccc\""), body)
    }

    @Test
    fun `test clean status fails closed when aggregate semantic coverage is missing`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 10L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        InspectionResultsStore.setSnapshot(
            key,
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                source = "inspection_view",
                captureDiagnostic = semanticCoverageGapDiagnostic(),
                runId = 10L,
            ),
        )

        val response = processGetRequest("/api/inspection/status")
        val body = response.content().toString(Charsets.UTF_8)

        assertTrue(body.contains("\"clean_inspection\": false"), body)
        assertTrue(body.contains("\"inspection_verdict\": \"UNKNOWN\""), body)
        assertTrue(body.contains("\"inspection_verdict_reason\": \"scope_semantic_coverage_missing\""), body)
        assertTrue(body.contains("\"classification\": \"configuration_blocked\""), body)
    }

    @Test
    fun `test findings problems response includes decisive attribution`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 8L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        InspectionResultsStore.setSnapshot(
            key,
            InspectionResultsSnapshot(
                problems = listOf(
                    mapOf(
                        "file" to "/tmp/TestProject/src/App.kt",
                        "severity" to "warning",
                        "description" to "known warning",
                    ),
                ),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
                runId = 8L,
            ),
        )

        val response = processGetRequest(
            "/api/inspection/problems?inspection_run_id=8&client_run_id=bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"inspection_verdict\": \"RED\""), body)
        assertTrue(body.contains("\"classification\": \"decisive\""), body)
        assertTrue(body.contains("\"code\": \"actionable_findings\""), body)
        assertTrue(body.contains("\"inspection_run_id\": 8"), body)
        assertTrue(body.contains("\"client_run_id\": \"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\""), body)
    }

    @Test
    fun `test problems endpoint refuses a replacement inspection run`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        setInspectionRunState(
            projectKey(mockProject),
            InspectionRunState(
                runId = 8L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )

        val response = processGetRequest(
            "/api/inspection/problems?severity=all&inspection_run_id=7"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"run_changed\""))
        assertTrue(body.contains("\"expected_inspection_run_id\": 7"))
        assertTrue(body.contains("\"inspection_run_id\": 8"))
    }

    @Test
    fun `test problems endpoint keeps active run while prior snapshot remains`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = true,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        InspectionResultsStore.setSnapshot(
            key,
            InspectionResultsSnapshot(
                problems = listOf(
                    mapOf(
                        "file" to "/tmp/TestProject/src/Old.kt",
                        "severity" to "warning",
                        "description" to "old run finding",
                    ),
                ),
                timestamp = System.currentTimeMillis() - 10_000,
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "inspection_view",
                runId = 6L,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )

        val response = processGetRequest(
            "/api/inspection/problems?severity=all&inspection_run_id=7"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"inspection_in_progress\""))
        assertTrue(body.contains("\"inspection_in_progress\": true"))
        assertTrue(body.contains("\"inspection_run_id\": 7"))
        assertTrue(body.contains("\"snapshot_run_id\": 6"))
        assertFalse(body.contains("old run finding"))
        assertFalse(body.contains("\"status\": \"run_changed\""))
    }

    @Test
    fun `test problems endpoint executes on pooled thread`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        every { mockApplication.isDispatchThread } returns true
        mockInspectionPrerequisites(mockProject)

        val response = processGetRequest("/api/inspection/problems?severity=all")

        assertEquals(HttpResponseStatus.OK, response.status())
        verify(exactly = 1) { mockApplication.executeOnPooledThread(any<Runnable>()) }
    }

    @Test
    fun `test waitForInspection reports missing explicit project clearly`() {
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "waitForInspection",
            String::class.java,
            Long::class.javaObjectType,
            Long::class.javaObjectType,
        )
        method.isAccessible = true

        val response = method.invoke(handler, "NonExistent", 10L, 10L) as String

        assertTrue(response.contains("Requested project 'NonExistent' is not open in the IDE."))
        assertTrue(response.contains("\"completion_reason\": \"no_project\""))
        assertTrue(response.contains("\"inspection_verdict\": \"UNKNOWN\""))
        assertTrue(response.contains("\"inspection_verdict_reason\": \"no_project\""))
        assertTrue(response.contains("\"wait_completed\": false"))
        assertTrue(response.contains("\"timed_out\": false"))
        assertTrue(response.contains("\"wait_note\":"))
    }

    @Test
    fun `test problems rejects invalid filters`() {
        listOf(
            "/api/inspection/problems?severity=fatal" to "severity",
            "/api/inspection/problems?file_pattern=(" to "file_pattern",
            "/api/inspection/problems?changed_files_mode=index" to "changed_files_mode",
            "/api/inspection/problems?include_stale=yes" to "include_stale",
        ).forEach { (uri, parameter) ->
            val response = processGetRequest(uri)
            val body = response.content().toString(Charsets.UTF_8)

            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status(), uri)
            assertTrue(body.contains("\"parameter\": \"$parameter\""), body)
        }
    }

    @Test
    fun `test problems rejects unresolved targeted scopes`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        runPooledTasksInline()
        val localFileSystem = mockk<LocalFileSystem>()
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath(any()) } returns null
        val fileEditorManager = mockk<com.intellij.openapi.fileEditor.FileEditorManager>()
        mockkStatic(com.intellij.openapi.fileEditor.FileEditorManager::class)
        every { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(mockProject) } returns fileEditorManager
        every { fileEditorManager.selectedFiles } returns emptyArray()
        val projectFileIndex = mockk<com.intellij.openapi.roots.ProjectFileIndex>()
        mockkStatic(com.intellij.openapi.roots.ProjectFileIndex::class)
        every { com.intellij.openapi.roots.ProjectFileIndex.getInstance(mockProject) } returns projectFileIndex

        listOf(
            "/api/inspection/problems?scope=files&file=src/Missing.kt" to "files",
            "/api/inspection/problems?scope=directory&dir=missing" to "dir",
            "/api/inspection/problems?scope=current_file" to "scope",
        ).forEach { (uri, parameter) ->
            val response = processGetRequest(uri)
            val body = response.content().toString(Charsets.UTF_8)

            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status(), uri)
            assertTrue(body.contains("\"parameter\": \"$parameter\""), body)
        }
    }

    @Test
    fun `test status runtime failure returns HTTP 500`() {
        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(mockProject) } throws IllegalStateException("boom")

        val response = processGetRequest(
            "/api/inspection/status?client_run_id=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status())
        assertTrue(body.contains("Internal server error"))
        assertTrue(body.contains("\"inspection_verdict\": \"UNKNOWN\""), body)
        assertTrue(body.contains("\"inspection_verdict_reason\": \"inspection_api_http_error\""), body)
        assertTrue(body.contains("\"classification\": \"tool_caused\""), body)
        assertTrue(body.contains("\"phase\": \"status\""), body)
        assertTrue(body.contains("\"http_status\": 500"), body)
        assertTrue(body.contains("\"client_run_id\": \"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\""), body)
        assertFalse(body.contains("boom"), body)
    }

    @Test
    fun `test problems runtime failure returns HTTP 500`() {
        runPooledTasksInline()
        every { mockApplication.runReadAction(any<ThrowableComputable<Any, Exception>>()) } throws
            IllegalStateException("boom")

        val response = processGetRequest(
            "/api/inspection/problems?client_run_id=bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status())
        assertTrue(body.contains("Internal server error"))
        assertTrue(body.contains("\"endpoint\": \"/api/inspection/problems\""), body)
        assertTrue(body.contains("\"client_run_id\": \"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\""), body)
        assertFalse(body.contains("boom"), body)
    }
    @ParameterizedTest
    @ValueSource(strings = ["/tmp/outside/Included.kt", "/tmp/TestProject/build/Included.kt", "/tmp/TestProject/.idea/workspace.xml"])
    fun `test files publication cannot reconcile a selected file the tracker does not watch`(path: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val fingerprint = projectInputsFingerprint().copy(excludedRootPaths = listOf("/tmp/TestProject/build"))
        handler.projectInputsFingerprintProvider = { _, _ -> fingerprint }
        val problems = listOf(mapOf("file" to path, "severity" to "warning", "description" to "finding before edit"))
        mockExtractor(problems)
        setInspectionRunState(key, InspectionRunState(1L, System.currentTimeMillis(), true))
        val snapshot = InspectionResultsSnapshot(
            problems = problems,
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(10L, 0),
            outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
            source = "global_context",
            captureDiagnostic = exactFilesProof(false),
            captureScope = InspectionCaptureScope(scopeParam = "files", files = listOf(path), resolvedFiles = listOf(path)),
            runId = 1L,
        )

        assertFalse(isTrackedInspectionInputPath(mockProject.basePath, fingerprint.rootPaths, path, fingerprint.excludedRootPaths))
        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = InspectionProjectStateSnapshot(11L, 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = fingerprint,
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )
        setInspectionRunState(key, InspectionRunState(1L, System.currentTimeMillis(), false))

        val status = buildInspectionStatus()
        assertEquals("UNKNOWN", status["inspection_verdict"])
        assertEquals(true, status["results_may_be_stale"])
        assertEquals(10L, requireNotNull(InspectionResultsStore.getSnapshot(key)).projectState.psiModificationCount)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "native_run", "incomplete", "not_clean"])
    fun `test files snapshots without complete exact proof still require live findings`(proof: String) {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val fingerprint = projectInputsFingerprint()
        handler.projectInputsFingerprintProvider = { _, _ -> fingerprint }
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(mockProject) } returns ProblemExtractionResult(
            emptyList(), true, ProblemExtractionSource.NONE,
        )
        enhancedTreeExtractorFactory = { extractor }
        setInspectionRunState(key, InspectionRunState(1L, System.currentTimeMillis(), true))
        val diagnostic = when (proof) {
            "missing" -> emptyMap()
            "native_run" -> exactFilesProof(true) + ("execution_proof_mode" to "native_run")
            "not_clean" -> exactFilesProof(false)
            else -> exactFilesProof(true) + ("execution_proof_established" to false)
        }
        val snapshot = InspectionResultsSnapshot(
            problems = emptyList(),
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(10L, 0),
            outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
            source = "inspection_view",
            captureScope = publicationScope("files"),
            captureDiagnostic = diagnostic,
            runId = 1L,
        )
        publishInspectionSnapshot(
            snapshot = snapshot,
            captureEndState = InspectionProjectStateSnapshot(11L, 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = fingerprint,
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )
        setInspectionRunState(key, InspectionRunState(1L, System.currentTimeMillis(), false))
        assertEquals("UNKNOWN", buildInspectionStatus()["inspection_verdict"])
        assertEquals(10L, requireNotNull(InspectionResultsStore.getSnapshot(key)).projectState.psiModificationCount)
    }

    private fun exactFilesProof(clean: Boolean): Map<String, Any?> = mapOf(
        "execution_proof_mode" to "exact_bounded",
        "execution_proof_established" to true,
        "execution_proof_clean" to clean,
    )

    private fun publicationScope(scope: String) = InspectionCaptureScope(
        scopeParam = scope,
        files = if (scope == "files") listOf("src/Included.kt") else null,
        resolvedFiles = if (scope == "files") listOf("/tmp/TestProject/src/Included.kt") else null,
    )

}
