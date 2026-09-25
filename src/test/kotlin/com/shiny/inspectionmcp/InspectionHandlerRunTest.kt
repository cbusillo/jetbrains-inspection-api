package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import io.mockk.*
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.channel.ChannelHandlerContext
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel

internal class InspectionHandlerRunTest : InspectionHandlerTestSupport() {
    @Test
    fun `inspection handler opens projects without private or interactive project APIs`() {
        val resourceName = InspectionHandler::class.java.name.replace('.', '/') + ".class"
        val classResource = requireNotNull(InspectionHandler::class.java.classLoader.getResource(resourceName))
        val classPath = when (classResource.protocol) {
            "jar" -> Paths.get(java.net.URI.create(classResource.toExternalForm().substringAfter("jar:").substringBefore("!/"))).toString()
            "file" -> {
                var root = Paths.get(classResource.toURI())
                repeat(resourceName.split('/').size) {
                    root = requireNotNull(root.parent)
                }
                root.toString()
            }
            else -> error("Unsupported InspectionHandler class resource: $classResource")
        }
        val javap = sequenceOf(System.getenv("JAVA_HOME"), System.getProperty("java.home"))
            .filterNotNull()
            .map { home -> Paths.get(home, "bin", "javap") }
            .firstOrNull(Files::isExecutable)
        assertNotNull(javap, "javap must be available from the test JDK")
        val process = ProcessBuilder(
            requireNotNull(javap).toString(),
            "-classpath",
            classPath,
            "-c",
            "-p",
            InspectionHandler::class.java.name,
            "com.shiny.inspectionmcp.InspectionHandlerKt",
        ).redirectErrorStream(true).start()
        val disassembly = process.inputStream.bufferedReader().use { it.readText() }

        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "javap did not finish")
        assertEquals(0, process.exitValue(), disassembly)
        assertFalse(
            disassembly.contains("com/intellij/ide/impl/OpenProjectTask"),
            "Lifecycle project opening must not use JetBrains private OpenProjectTask APIs.",
        )
        assertTrue(
            disassembly.contains("com/intellij/ide/impl/ProjectUtil.openProject"),
            "Lifecycle project opening must use the public noninteractive ProjectUtil open path.",
        )
        assertFalse(
            disassembly.contains("com/intellij/ide/impl/ProjectUtil.openOrImport"),
            "Lifecycle project opening must not use the interactive open-or-import processor path.",
        )
        assertFalse(
            disassembly.contains("runProcessWithProgressSynchronously"),
            "Agent-triggered inspections must not block lifecycle requests behind a modal progress task.",
        )
        assertTrue(
            disassembly.contains("com/intellij/openapi/progress/util/ProgressWindow"),
            "JetBrains global inspections require a non-modal ProgressWindow indicator.",
        )
    }

    @Test
    fun `lifecycle project store prepares a fresh directory for direct opening`() {
        val projectRoot = Files.createTempDirectory("inspection-project-store")

        val projectStore = prepareLifecycleProjectStore(projectRoot)

        assertEquals(projectRoot.resolve(Project.DIRECTORY_STORE_FOLDER), projectStore)
        assertTrue(Files.isDirectory(projectStore))
    }

    @Test
    fun `lifecycle project store preserves existing metadata`() {
        val projectRoot = Files.createTempDirectory("inspection-existing-project-store")
        val projectStore = projectRoot.resolve(Project.DIRECTORY_STORE_FOLDER)
        Files.createDirectories(projectStore)
        val metadata = projectStore.resolve("misc.xml")
        Files.writeString(metadata, "<project />")

        assertEquals(projectStore, prepareLifecycleProjectStore(projectRoot))
        assertEquals("<project />", Files.readString(metadata))
    }

    @Test
    fun `lifecycle project store skips explicit ipr project file`() {
        val projectRoot = Files.createTempDirectory("inspection-ipr-project-store")
        val projectFilePath = projectRoot.resolve("project.ipr")
        Files.writeString(projectFilePath, "<project />")

        assertNull(prepareLifecycleProjectStoreIfDirectory(projectFilePath))
        assertFalse(Files.exists(projectRoot.resolve(Project.DIRECTORY_STORE_FOLDER)))
    }

    @Test
    fun `directory scope ancestry uses native path components`() {
        val root = Files.createTempDirectory("inspection-directory-scope")
        val directory = root.resolve("app")
        val child = directory.resolve("nested").resolve("selected.py")
        val sibling = root.resolve("application").resolve("selected.py")

        assertTrue(inspectionPathWithinDirectory(child.toString(), directory.toString()))
        assertFalse(inspectionPathWithinDirectory(sibling.toString(), directory.toString()))
    }

    @Test
    fun `test changed files trigger with no targets publishes clean snapshot without IDE inspection`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }

        val fileDocumentManager = mockk<FileDocumentManager>(relaxed = true)
        mockkStatic(FileDocumentManager::class)
        every { FileDocumentManager.getInstance() } returns fileDocumentManager
        every { fileDocumentManager.unsavedDocuments } returns emptyArray()

        val psiDocumentManager = mockk<PsiDocumentManager>(relaxed = true)
        mockkStatic(PsiDocumentManager::class)
        every { PsiDocumentManager.getInstance(mockProject) } returns psiDocumentManager

        mockkStatic(ChangeListManager::class)
        val changeListManager = mockk<ChangeListManager>(relaxed = true)
        every { ChangeListManager.getInstance(mockProject) } returns changeListManager
        every { changeListManager.allChanges } returns emptyList()

        mockkStatic(PsiModificationTracker::class)
        val modificationTracker = mockk<PsiModificationTracker>()
        every { PsiModificationTracker.getInstance(mockProject) } returns modificationTracker
        every { modificationTracker.modificationCount } returns 11L

        mockkStatic(ToolWindowManager::class)
        val toolWindowManager = mockk<ToolWindowManager>()
        every { ToolWindowManager.getInstance(mockProject) } returns toolWindowManager
        every { toolWindowManager.getToolWindow(any()) } returns null

        mockkStatic(DumbService::class)
        val dumbService = mockk<DumbService>()
        every { DumbService.getInstance(mockProject) } returns dumbService
        every { dumbService.isDumb } returns false

        val inputFingerprint = projectInputsFingerprint(profileName = "TestProfile")
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        handler.projectContentTrackerFactory = { _, _ -> FakeInspectionProjectContentTracker() }

        val response = processTriggerRequest("/api/inspection/trigger?scope=changed_files&include_unversioned=false")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"scope\": \"changed_files\""))
        verify(exactly = 2) { mockApplication.executeOnPooledThread(any<Runnable>()) }
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
        val status = buildInspectionStatus()
        assertEquals("clean_confirmed", status["snapshot_outcome"])
        assertEquals("empty_changed_files", status["results_source"])
        assertEquals(false, status["capture_incomplete"])
        assertEquals(false, status["results_may_be_stale"])
        assertEquals(0, status["total_problems"])
        assertEquals("publish", status["inspection_stage"])
        assertEquals("completed", status["inspection_terminal_outcome"])
        @Suppress("UNCHECKED_CAST")
        val stageHistory = status["inspection_stage_history"] as List<Map<String, Any>>
        assertEquals(
            listOf("sync", "smart_wait", "python_sdk_readiness"),
            stageHistory.map { it["stage"] },
        )
    }

    @Test
    fun `test changed file disappearing before preflight fails closed with scope evidence`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        val queuedTasks = mutableListOf<Runnable>()
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            queuedTasks += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)
        val changedPath = "/tmp/TestProject/src/Disappeared.kt"
        mockChangedFiles(listOf(changedPath))
        val localFileSystem = mockk<LocalFileSystem>()
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath(changedPath) } returns null
        var analysisReadinessCalls = 0
        handler.projectAnalysisReadinessProvider = { _, _ ->
            analysisReadinessCalls += 1
            error("Unavailable changed-file evidence must fail before language analysis.")
        }

        try {
            val uri = "/api/inspection/trigger?scope=changed_files&include_unversioned=false"
            val urlDecoder = QueryStringDecoder(uri)
            val request = mockk<FullHttpRequest>()
            val context = mockk<ChannelHandlerContext>()
            val responseSlot = slot<Any>()
            every { request.uri() } returns uri
            every { context.writeAndFlush(capture(responseSlot)) } returns mockk(relaxed = true)

            assertTrue(handler.process(urlDecoder, request, context))
            assertEquals(1, queuedTasks.size)
            queuedTasks.removeAt(0).run()

            val response = responseSlot.captured as FullHttpResponse
            assertEquals(HttpResponseStatus.OK, response.status())
            assertEquals(1, queuedTasks.size)

            queuedTasks.removeAt(0).run()

            val status = buildInspectionStatus()
            val statusResponse = processGetRequest("/api/inspection/status")
            val statusBody = statusResponse.content().toString(Charsets.UTF_8)
            assertEquals("capture_incomplete", status["snapshot_outcome"])
            assertEquals("project_analysis_not_ready", status["capture_incomplete_reason"])
            assertEquals("project_analysis_readiness", status["results_source"])
            @Suppress("UNCHECKED_CAST")
            val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
            assertEquals("inspection_preflight", diagnostic["readiness_stage"])
            assertEquals("changed_files", diagnostic["requested_scope"])
            assertEquals("changed_files", diagnostic["resolved_scope"])
            assertEquals(1, diagnostic["resolved_scope_file_count"])
            assertEquals("scope_resolution_unavailable", diagnostic["analysis_state"])
            assertEquals(false, diagnostic["inspection_started"])
            assertEquals("tool", diagnostic["outcome_ownership"])
            assertEquals(1, diagnostic["scope_resolution_missing_file_count"])
            assertEquals(listOf(changedPath), diagnostic["scope_resolution_missing_files"])
            assertTrue(statusBody.contains("\"code\": \"project_analysis_not_ready\""), statusBody)
            assertEquals(0, analysisReadinessCalls)
            assertEquals(false, inspectionRunState(projectKey(mockProject))?.inProgress)
            verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
        } finally {
            unmockkStatic(LocalFileSystem::class)
        }
    }

    @Test
    fun `test refreshing Python analysis remains fail closed before inspection`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        val inputFingerprint = projectInputsFingerprint(profileName = "TestProfile")
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        handler.projectContentTrackerFactory = { _, _ -> FakeInspectionProjectContentTracker() }
        handler.projectAnalysisReadinessProvider = { _, _ ->
            InspectionProjectAnalysisReadiness(
                required = true,
                ready = false,
                reason = "python_sdk_updating",
                pythonFileCount = 1,
                pythonSdkCount = 1,
                updatingSdkCount = 1,
            )
        }

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")
        val status = buildInspectionStatus()

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals("capture_incomplete", status["snapshot_outcome"])
        assertEquals("project_analysis_not_ready", status["capture_incomplete_reason"])
        assertEquals("project_analysis_readiness", status["results_source"])
        @Suppress("UNCHECKED_CAST")
        val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
        assertEquals("inspection_preflight", diagnostic["readiness_stage"])
        assertEquals("python_sdk_updating", diagnostic["analysis_state"])
        assertEquals(false, diagnostic["inspection_started"])
        assertEquals("environment", diagnostic["outcome_ownership"])
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
    }

    @Test
    fun `test partially assigned local Python SDK timeout remains retryable`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        handler.pythonSdkSettleTimeoutMs = 25
        handler.pythonSdkSettleProgressGraceMs = 25
        handler.pythonSdkSettleMaxTimeoutMs = 50
        handler.pythonSdkSettlePollMs = 10
        handler.pythonSdkSettleNow = { 0L }
        handler.pythonSdkSettleSleep = {}
        handler.projectAnalysisReadinessProvider = { _, _ ->
            pythonSdkReadiness(ready = false, localInterpreterCandidate = true)
        }
        handler.projectAnalysisReadinessRefreshProvider = { _, _, _, _ ->
            pythonSdkReadiness(
                ready = false,
                localInterpreterCandidate = true,
                reason = "python_sdk_assignment_pending",
                registeredLocalPythonSdkCount = 1,
                assignedLocalPythonSdkCount = 1,
            )
        }

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")
        val status = buildInspectionStatus()

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals("capture_incomplete", status["snapshot_outcome"])
        assertEquals("project_analysis_not_ready", status["capture_incomplete_reason"])
        @Suppress("UNCHECKED_CAST")
        val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
        assertEquals("python_sdk_assignment_pending", diagnostic["analysis_state"])
        assertEquals("registered", diagnostic["sdk_registration_state"])
        assertEquals("pending", diagnostic["sdk_assignment_state"])
        assertEquals("environment", diagnostic["outcome_ownership"])
        assertEquals(true, diagnostic["python_sdk_settle_observed_registered_local_sdk"])
        assertEquals(1, diagnostic["python_sdk_settle_deadline_extension_count"])
        assertEquals(35L, diagnostic["python_sdk_settle_active_deadline_ms"])
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
    }

    @Test
    fun `test observed Python SDK registration remains retryable if the table entry disappears`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        val refreshedReadiness = ArrayDeque(
            listOf(
                pythonSdkReadiness(
                    ready = false,
                    localInterpreterCandidate = true,
                    reason = "python_sdk_assignment_pending",
                    registeredLocalPythonSdkCount = 1,
                ),
                pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
            ),
        )
        handler.pythonSdkSettleTimeoutMs = 25
        handler.pythonSdkSettleProgressGraceMs = 25
        handler.pythonSdkSettleMaxTimeoutMs = 50
        handler.pythonSdkSettlePollMs = 10
        handler.pythonSdkSettleNow = { 0L }
        handler.pythonSdkSettleSleep = {}
        handler.projectAnalysisReadinessProvider = { _, _ ->
            pythonSdkReadiness(ready = false, localInterpreterCandidate = true)
        }
        handler.projectAnalysisReadinessRefreshProvider = { _, _, _, _ ->
            refreshedReadiness.removeFirstOrNull()
                ?: pythonSdkReadiness(ready = false, localInterpreterCandidate = true)
        }

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")
        val status = buildInspectionStatus()

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals("capture_incomplete", status["snapshot_outcome"])
        assertEquals("project_analysis_not_ready", status["capture_incomplete_reason"])
        @Suppress("UNCHECKED_CAST")
        val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
        assertEquals("python_sdk_missing", diagnostic["analysis_state"])
        assertEquals("not_registered", diagnostic["sdk_registration_state"])
        assertEquals("environment", diagnostic["outcome_ownership"])
        assertEquals(true, diagnostic["python_sdk_settle_observed_registered_local_sdk"])
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
    }

    @Test
    fun `test observed Python SDK assignment remains retryable without registration`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        val assignedWithoutRegistration = pythonSdkReadiness(
            ready = false,
            localInterpreterCandidate = true,
            assignedLocalPythonSdkCount = 1,
            assignedLocalPythonFileCount = 1,
            missingSdkFileCount = 0,
        )
        handler.pythonSdkSettleTimeoutMs = 20
        handler.pythonSdkSettleProgressGraceMs = 10
        handler.pythonSdkSettleMaxTimeoutMs = 20
        handler.pythonSdkSettlePollMs = 10
        handler.pythonSdkSettleNow = { 0L }
        handler.pythonSdkSettleSleep = {}
        handler.projectAnalysisReadinessProvider = { _, _ -> assignedWithoutRegistration }
        handler.projectAnalysisReadinessRefreshProvider = { _, _, _, _ -> assignedWithoutRegistration }

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")
        val status = buildInspectionStatus()

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals("capture_incomplete", status["snapshot_outcome"])
        assertEquals("project_analysis_not_ready", status["capture_incomplete_reason"])
        @Suppress("UNCHECKED_CAST")
        val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
        assertEquals("python_sdk_missing", diagnostic["analysis_state"])
        assertEquals("not_registered", diagnostic["sdk_registration_state"])
        assertEquals("environment", diagnostic["outcome_ownership"])
        assertEquals(true, diagnostic["python_sdk_settle_observed_assigned_local_sdk"])
        assertEquals(false, diagnostic["python_sdk_settle_observed_registered_local_sdk"])
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
    }

    private fun comparisonFinding(
        tool: String,
        line: Int,
        severity: String = "warning",
    ): Map<String, Any> = mapOf(
        "inspectionType" to tool,
        "file" to "/tmp/TestProject/src/app.py",
        "line" to line,
        "column" to 1,
        "description" to "$tool at $line",
        "severity" to severity,
    )

    @Test
    fun `test source comparison separates native only, proof only, settle only and severity differences`() {
        val diagnostic = inspectionSourceComparisonDiagnostic(
            nativeContextFindings = listOf(
                comparisonFinding("PyUnusedLocal", 3),
                comparisonFinding("PyTypeChecker", 5, severity = "warning"),
                comparisonFinding("PyInjectedSql", 7),
                comparisonFinding("GlobalDuplicates", 9),
            ),
            exactProofFindings = listOf(
                comparisonFinding("PyUnusedLocal", 3),
                comparisonFinding("PyTypeChecker", 5, severity = "error"),
                comparisonFinding("PyShadowingNames", 11),
            ),
            settledFindings = listOf(
                comparisonFinding("PyUnusedLocal", 3),
                comparisonFinding("ToolWindowOnly", 13),
            ),
            exactProofToolShortNames = setOf("PyUnusedLocal", "PyTypeChecker", "PyInjectedSql", "PyShadowingNames"),
        )

        assertEquals(4, diagnostic["source_comparison_native_context_unique_finding_count"])
        assertEquals(3, diagnostic["source_comparison_exact_proof_unique_finding_count"])
        assertEquals(1, diagnostic["source_comparison_native_outside_proof_tools_unique_finding_count"])
        assertEquals(1, diagnostic["source_comparison_native_only_unique_finding_count"])
        assertEquals(listOf("PyInjectedSql"), diagnostic["source_comparison_native_only_tools"])
        assertEquals(1, diagnostic["source_comparison_exact_proof_only_unique_finding_count"])
        assertEquals(listOf("PyShadowingNames"), diagnostic["source_comparison_exact_proof_only_tools"])
        assertEquals(2, diagnostic["source_comparison_shared_unique_finding_count"])
        assertEquals(1, diagnostic["source_comparison_severity_mismatch_count"])
        assertEquals(1, diagnostic["source_comparison_settle_only_unique_finding_count"])
        assertEquals(listOf("ToolWindowOnly"), diagnostic["source_comparison_settle_only_tools"])
    }

    @Test
    fun `test source comparison reports wording differences separately from unmatched locations`() {
        val reworded = comparisonFinding("PyUnusedLocal", 3) + ("description" to "reworded by the other engine") + ("column" to 9)

        val diagnostic = inspectionSourceComparisonDiagnostic(
            nativeContextFindings = listOf(comparisonFinding("PyUnusedLocal", 3)),
            exactProofFindings = listOf(reworded),
            settledFindings = emptyList(),
            exactProofToolShortNames = setOf("PyUnusedLocal"),
        )

        assertEquals(1, diagnostic["source_comparison_native_only_unique_finding_count"])
        assertEquals(0, diagnostic["source_comparison_native_only_unmatched_location_count"])
        assertEquals(1, diagnostic["source_comparison_exact_proof_only_unique_finding_count"])
        assertEquals(0, diagnostic["source_comparison_exact_proof_only_unmatched_location_count"])
    }

    @Test
    fun `test source comparison omits proof parity when exact proof is not established`() {
        val diagnostic = inspectionSourceComparisonDiagnostic(
            nativeContextFindings = listOf(comparisonFinding("PyUnusedLocal", 3)),
            exactProofFindings = emptyList(),
            settledFindings = listOf(comparisonFinding("PyUnusedLocal", 3)),
            exactProofToolShortNames = null,
        )

        assertEquals(
            mapOf(
                "source_comparison_native_context_unique_finding_count" to 1,
                "source_comparison_settle_only_unique_finding_count" to 0,
                "source_comparison_settle_only_unmatched_location_count" to 0,
                "source_comparison_settle_only_tools" to emptyList<String>(),
            ),
            diagnostic,
        )
    }

    @Test
    fun `test unrelated assigned Python SDK does not mask local registration progress`() {
        val observations = ArrayDeque(
            listOf(
                pythonSdkReadiness(
                    ready = false,
                    localInterpreterCandidate = true,
                    reason = "python_sdk_assignment_pending",
                    registeredLocalPythonSdkCount = 1,
                    pythonSdkCount = 1,
                ),
                pythonSdkReadiness(
                    ready = true,
                    localInterpreterCandidate = true,
                    pythonSdkCount = 2,
                ),
                pythonSdkReadiness(
                    ready = true,
                    localInterpreterCandidate = true,
                    pythonSdkCount = 2,
                ),
            ),
        )

        val result = settlePythonSdkReadiness(
            initialReadiness = pythonSdkReadiness(
                ready = false,
                localInterpreterCandidate = true,
                pythonSdkCount = 1,
            ),
            now = { 0L },
            sleep = {},
            observe = { observations.removeFirst() },
            checkCanceled = {},
            timeoutMs = 20,
            pollMs = 10,
            progressGraceMs = 20,
            maxTimeoutMs = 60,
        )

        assertTrue(result.readiness.ready)
        assertEquals(2, result.evidence.deadlineExtensionCount)
        assertTrue(result.evidence.observedRegisteredLocalSdk)
        assertTrue(result.evidence.observedAssignedLocalSdk)
        assertFalse(result.evidence.timedOut)
    }

    @Test
    fun `test unresolved targeted analysis scope fails closed without widening`() {
        val productionHandler = InspectionHandler()

        val readiness = productionHandler.projectAnalysisReadinessProvider(
            mockProject,
            InspectionCaptureScope(
                scopeParam = "files",
                files = listOf("src/main/kotlin/App.kt"),
            ),
        )

        assertTrue(readiness.required)
        assertFalse(readiness.ready)
        assertEquals("scope_resolution_unavailable", readiness.reason)
        assertEquals(0, readiness.pythonFileCount)
    }

    @Test
    fun `test resolved non-python files and changed files do not require a Python SDK`() {
        val productionHandler = InspectionHandler()
        val localFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val kotlinFile = mockk<VirtualFile>(relaxed = true)
        val kotlinPath = "/tmp/TestProject/src/App.kt"
        every { kotlinFile.path } returns kotlinPath
        every { kotlinFile.name } returns "App.kt"
        every { kotlinFile.isValid } returns true
        every { kotlinFile.isDirectory } returns false
        every { kotlinFile.isInLocalFileSystem } returns true
        every { kotlinFile.extension } returns "kt"
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath(kotlinPath) } returns kotlinFile

        try {
            listOf("files", "changed_files").forEach { scopeKind ->
                val readiness = productionHandler.projectAnalysisReadinessProvider(
                    mockProject,
                    InspectionCaptureScope(
                        scopeParam = scopeKind,
                        files = listOf(kotlinPath).takeIf { scopeKind == "files" },
                        resolvedFiles = listOf(kotlinPath),
                    ),
                )

                assertFalse(readiness.required, scopeKind)
                assertTrue(readiness.ready, scopeKind)
                assertEquals("python_not_in_scope", readiness.reason, scopeKind)
                assertEquals(0, readiness.pythonFileCount, scopeKind)
            }
        } finally {
            unmockkStatic(LocalFileSystem::class)
        }
    }

    @Test
    fun `test selected Python file without SDK fails closed`() {
        val productionHandler = InspectionHandler()
        val localFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val pythonFile = mockk<VirtualFile>(relaxed = true)
        val pythonFileType = mockk<FileType>(relaxed = true)
        val fileTypeManager = mockk<FileTypeManager>(relaxed = true)
        val rootManager = mockk<ProjectRootManager>(relaxed = true)
        val pythonPath = "/tmp/TestProject/scripts/check.py"
        every { pythonFile.path } returns pythonPath
        every { pythonFile.name } returns "check.py"
        every { pythonFile.isValid } returns true
        every { pythonFile.isDirectory } returns false
        every { pythonFile.isInLocalFileSystem } returns true
        every { pythonFile.extension } returns "py"
        every { pythonFileType.name } returns "Plain Text"
        every { rootManager.projectSdk } returns null
        mockkStatic(LocalFileSystem::class)
        mockkStatic(FileTypeManager::class)
        mockkStatic(ProjectRootManager::class)
        mockkStatic(ModuleUtilCore::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath(pythonPath) } returns pythonFile
        every { FileTypeManager.getInstance() } returns fileTypeManager
        every { fileTypeManager.getFileTypeByExtension("py") } returns pythonFileType
        every { ProjectRootManager.getInstance(mockProject) } returns rootManager
        every { ModuleUtilCore.findModuleForFile(pythonFile, mockProject) } returns null

        try {
            val unsupportedReadiness = productionHandler.projectAnalysisReadinessProvider(
                mockProject,
                InspectionCaptureScope(
                    scopeParam = "files",
                    files = listOf(pythonPath),
                    resolvedFiles = listOf(pythonPath),
                ),
            )
            assertTrue(unsupportedReadiness.required)
            assertFalse(unsupportedReadiness.ready)
            assertEquals("python_support_unavailable", unsupportedReadiness.reason)
            assertEquals(1, unsupportedReadiness.pythonFileCount)

            every { pythonFileType.name } returns "Python"
            val readiness = productionHandler.projectAnalysisReadinessProvider(
                mockProject,
                InspectionCaptureScope(
                    scopeParam = "files",
                    files = listOf(pythonPath),
                    resolvedFiles = listOf(pythonPath),
                ),
            )

            assertTrue(readiness.required)
            assertFalse(readiness.ready)
            assertEquals("python_sdk_missing", readiness.reason)
            assertEquals(1, readiness.pythonFileCount)
            assertEquals(1, readiness.missingSdkFileCount)
        } finally {
            unmockkStatic(ModuleUtilCore::class)
            unmockkStatic(ProjectRootManager::class)
            unmockkStatic(FileTypeManager::class)
            unmockkStatic(LocalFileSystem::class)
        }
    }

    @Test
    fun `test worktree Python SDK production readiness requires local registration and assignment`() {
        val productionHandler = InspectionHandler()
        val localFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val pythonFile = mockk<VirtualFile>(relaxed = true)
        val pythonFileType = mockk<FileType>(relaxed = true)
        val fileTypeManager = mockk<FileTypeManager>(relaxed = true)
        val rootManager = mockk<ProjectRootManager>(relaxed = true)
        val localSdk = mockk<com.intellij.openapi.projectRoots.Sdk>(relaxed = true)
        val unrelatedSdk = mockk<com.intellij.openapi.projectRoots.Sdk>(relaxed = true)
        val pythonPath = "/tmp/TestProject/scripts/check.py"
        val interpreterPath = "/tmp/TestProject/.venv/bin/python"
        var projectSdk: com.intellij.openapi.projectRoots.Sdk? = unrelatedSdk
        var registeredSdks = emptyList<com.intellij.openapi.projectRoots.Sdk>()
        every { pythonFile.path } returns pythonPath
        every { pythonFile.name } returns "check.py"
        every { pythonFile.isValid } returns true
        every { pythonFile.isDirectory } returns false
        every { pythonFile.isInLocalFileSystem } returns true
        every { pythonFile.extension } returns "py"
        every { pythonFileType.name } returns "Python"
        every { rootManager.projectSdk } answers { projectSdk }
        every { localSdk.sdkType.name } returns "Python SDK"
        every { localSdk.name } returns "TestProject (.venv)"
        every { localSdk.homePath } returns interpreterPath
        every { unrelatedSdk.sdkType.name } returns "Python SDK"
        every { unrelatedSdk.name } returns "System Python"
        every { unrelatedSdk.homePath } returns "/usr/bin/python3"
        mockkStatic(LocalFileSystem::class)
        mockkStatic(FileTypeManager::class)
        mockkStatic(ProjectRootManager::class)
        mockkStatic(ModuleUtilCore::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath(pythonPath) } returns pythonFile
        every { FileTypeManager.getInstance() } returns fileTypeManager
        every { fileTypeManager.getFileTypeByExtension("py") } returns pythonFileType
        every { ProjectRootManager.getInstance(mockProject) } returns rootManager
        every { ModuleUtilCore.findModuleForFile(pythonFile, mockProject) } returns null
        productionHandler.localPythonInterpreterPathsProvider = { setOf(interpreterPath) }
        productionHandler.registeredPythonSdksProvider = { registeredSdks }
        productionHandler.pythonSdkUpdateScheduledProvider = { false }

        fun readiness(): InspectionProjectAnalysisReadiness =
            productionHandler.projectAnalysisReadinessProvider(
                mockProject,
                InspectionCaptureScope(
                    scopeParam = "files",
                    files = listOf(pythonPath),
                    resolvedFiles = listOf(pythonPath),
                ),
            )

        try {
            val neverRegistered = readiness()
            assertFalse(neverRegistered.ready)
            assertEquals("python_sdk_missing", neverRegistered.reason)
            assertEquals(0, neverRegistered.missingSdkFileCount)
            assertEquals(1, neverRegistered.mismatchedSdkFileCount)
            assertEquals(0, neverRegistered.registeredLocalPythonSdkCount)
            assertEquals(0, neverRegistered.assignedLocalPythonFileCount)
            assertTrue(neverRegistered.localPythonInterpreterCandidate)

            registeredSdks = listOf(localSdk)
            val registeredButUnassigned = readiness()
            assertFalse(registeredButUnassigned.ready)
            assertEquals("python_sdk_assignment_pending", registeredButUnassigned.reason)
            assertEquals(1, registeredButUnassigned.registeredLocalPythonSdkCount)
            assertEquals(0, registeredButUnassigned.assignedLocalPythonSdkCount)
            assertEquals(0, registeredButUnassigned.assignedLocalPythonFileCount)
            assertEquals(1, registeredButUnassigned.mismatchedSdkFileCount)

            projectSdk = localSdk
            val assigned = readiness()
            assertTrue(assigned.ready)
            assertEquals("ready", assigned.reason)
            assertEquals(1, assigned.registeredLocalPythonSdkCount)
            assertEquals(1, assigned.assignedLocalPythonSdkCount)
            assertEquals(1, assigned.assignedLocalPythonFileCount)
            assertEquals(0, assigned.mismatchedSdkFileCount)

            registeredSdks = emptyList()
            val registrationDisappeared = readiness()
            assertFalse(registrationDisappeared.ready)
            assertEquals("python_sdk_missing", registrationDisappeared.reason)
            assertEquals(0, registrationDisappeared.mismatchedSdkFileCount)
            assertEquals(1, registrationDisappeared.assignedLocalPythonSdkCount)
            assertEquals(1, registrationDisappeared.assignedLocalPythonFileCount)
            verify(exactly = 4) {
                mockApplication.runReadAction(any<ThrowableComputable<Any, Exception>>())
            }
        } finally {
            unmockkStatic(ModuleUtilCore::class)
            unmockkStatic(ProjectRootManager::class)
            unmockkStatic(FileTypeManager::class)
            unmockkStatic(LocalFileSystem::class)
        }
    }

    @Test
    fun `test worktree Python SDK symlink does not accept its system target`() {
        val productionHandler = InspectionHandler()
        val tempDir = Files.createTempDirectory("inspection-python-sdk-symlink")
        val projectRoot = tempDir.resolve("project")
        val virtualEnvironmentBin = projectRoot.resolve(".venv/bin")
        val systemInterpreter = tempDir.resolve("system-python")
        val localInterpreter = virtualEnvironmentBin.resolve("python")
        Files.createDirectories(virtualEnvironmentBin)
        Files.writeString(systemInterpreter, "#!/bin/sh\nexit 0\n")
        assertTrue(systemInterpreter.toFile().setExecutable(true))
        Files.createSymbolicLink(localInterpreter, systemInterpreter)

        val localFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val pythonFile = mockk<VirtualFile>(relaxed = true)
        val pythonFileType = mockk<FileType>(relaxed = true)
        val fileTypeManager = mockk<FileTypeManager>(relaxed = true)
        val rootManager = mockk<ProjectRootManager>(relaxed = true)
        val systemSdk = mockk<com.intellij.openapi.projectRoots.Sdk>(relaxed = true)
        val pythonPath = projectRoot.resolve("check.py").toString()
        every { mockProject.basePath } returns projectRoot.toString()
        every { pythonFile.path } returns pythonPath
        every { pythonFile.name } returns "check.py"
        every { pythonFile.isValid } returns true
        every { pythonFile.isDirectory } returns false
        every { pythonFile.isInLocalFileSystem } returns true
        every { pythonFile.extension } returns "py"
        every { pythonFileType.name } returns "Python"
        every { rootManager.projectSdk } returns systemSdk
        every { systemSdk.sdkType.name } returns "Python SDK"
        every { systemSdk.name } returns "System Python"
        every { systemSdk.homePath } returns systemInterpreter.toString()
        mockkStatic(LocalFileSystem::class)
        mockkStatic(FileTypeManager::class)
        mockkStatic(ProjectRootManager::class)
        mockkStatic(ModuleUtilCore::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath(pythonPath) } returns pythonFile
        every { FileTypeManager.getInstance() } returns fileTypeManager
        every { fileTypeManager.getFileTypeByExtension("py") } returns pythonFileType
        every { ProjectRootManager.getInstance(mockProject) } returns rootManager
        every { ModuleUtilCore.findModuleForFile(pythonFile, mockProject) } returns null
        productionHandler.registeredPythonSdksProvider = { listOf(systemSdk) }
        productionHandler.pythonSdkUpdateScheduledProvider = { false }

        try {
            val readiness = productionHandler.projectAnalysisReadinessProvider(
                mockProject,
                InspectionCaptureScope(
                    scopeParam = "files",
                    files = listOf(pythonPath),
                    resolvedFiles = listOf(pythonPath),
                ),
            )
            assertFalse(readiness.ready)
            assertEquals("python_sdk_missing", readiness.reason)
            assertEquals(setOf(localInterpreter.toAbsolutePath().normalize().toString()), readiness.localPythonInterpreterPaths)
            assertEquals(0, readiness.registeredLocalPythonSdkCount)
            assertEquals(0, readiness.assignedLocalPythonFileCount)
            assertEquals(1, readiness.mismatchedSdkFileCount)
        } finally {
            unmockkStatic(ModuleUtilCore::class)
            unmockkStatic(ProjectRootManager::class)
            unmockkStatic(FileTypeManager::class)
            unmockkStatic(LocalFileSystem::class)
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test production Python SDK readiness settles real registration assignment and disappearance`() {
        val productionHandler = InspectionHandler()
        val localFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val pythonFile = mockk<VirtualFile>(relaxed = true)
        val pythonFileType = mockk<FileType>(relaxed = true)
        val fileTypeManager = mockk<FileTypeManager>(relaxed = true)
        val rootManager = mockk<ProjectRootManager>(relaxed = true)
        val localSdk = mockk<com.intellij.openapi.projectRoots.Sdk>(relaxed = true)
        val unrelatedSdk = mockk<com.intellij.openapi.projectRoots.Sdk>(relaxed = true)
        val pythonPath = "/tmp/TestProject/scripts/check.py"
        val interpreterPath = "/tmp/TestProject/.venv/bin/python"
        var projectSdk: com.intellij.openapi.projectRoots.Sdk? = unrelatedSdk
        var registeredSdks = emptyList<com.intellij.openapi.projectRoots.Sdk>()
        every { pythonFile.path } returns pythonPath
        every { pythonFile.name } returns "check.py"
        every { pythonFile.isValid } returns true
        every { pythonFile.isDirectory } returns false
        every { pythonFile.isInLocalFileSystem } returns true
        every { pythonFile.extension } returns "py"
        every { pythonFileType.name } returns "Python"
        every { rootManager.projectSdk } answers { projectSdk }
        every { localSdk.sdkType.name } returns "Python SDK"
        every { localSdk.name } returns "TestProject (.venv)"
        every { localSdk.homePath } returns interpreterPath
        every { unrelatedSdk.sdkType.name } returns "Python SDK"
        every { unrelatedSdk.name } returns "System Python"
        every { unrelatedSdk.homePath } returns "/usr/bin/python3"
        mockkStatic(LocalFileSystem::class)
        mockkStatic(FileTypeManager::class)
        mockkStatic(ProjectRootManager::class)
        mockkStatic(ModuleUtilCore::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath(pythonPath) } returns pythonFile
        every { FileTypeManager.getInstance() } returns fileTypeManager
        every { fileTypeManager.getFileTypeByExtension("py") } returns pythonFileType
        every { ProjectRootManager.getInstance(mockProject) } returns rootManager
        every { ModuleUtilCore.findModuleForFile(pythonFile, mockProject) } returns null
        productionHandler.localPythonInterpreterPathsProvider = { setOf(interpreterPath) }
        productionHandler.registeredPythonSdksProvider = { registeredSdks }
        productionHandler.pythonSdkUpdateScheduledProvider = { false }

        fun readiness(): InspectionProjectAnalysisReadiness =
            productionHandler.projectAnalysisReadinessProvider(
                mockProject,
                InspectionCaptureScope(
                    scopeParam = "files",
                    files = listOf(pythonPath),
                    resolvedFiles = listOf(pythonPath),
                ),
            )

        try {
            var observation = 0
            val successful = settlePythonSdkReadiness(
                initialReadiness = readiness(),
                now = { 0L },
                sleep = {},
                observe = {
                    observation += 1
                    when (observation) {
                        1 -> registeredSdks = listOf(localSdk)
                        2 -> projectSdk = localSdk
                    }
                    readiness()
                },
                checkCanceled = {},
                timeoutMs = 100,
                pollMs = 10,
                progressGraceMs = 20,
                maxTimeoutMs = 100,
            )
            assertTrue(successful.readiness.ready)
            assertEquals(1, successful.readiness.assignedLocalPythonFileCount)
            assertTrue(successful.evidence.observedRegisteredLocalSdk)
            assertTrue(successful.evidence.observedAssignedLocalSdk)

            projectSdk = unrelatedSdk
            registeredSdks = emptyList()
            observation = 0
            val disappeared = settlePythonSdkReadiness(
                initialReadiness = readiness(),
                now = { 0L },
                sleep = {},
                observe = {
                    observation += 1
                    registeredSdks = if (observation == 1) listOf(localSdk) else emptyList()
                    readiness()
                },
                checkCanceled = {},
                timeoutMs = 20,
                pollMs = 5,
                progressGraceMs = 10,
                maxTimeoutMs = 20,
            )
            assertTrue(disappeared.evidence.timedOut)
            assertEquals("python_sdk_missing", disappeared.readiness.reason)
            assertTrue(disappeared.evidence.observedRegisteredLocalSdk)
            assertFalse(disappeared.evidence.observedAssignedLocalSdk)
        } finally {
            unmockkStatic(ModuleUtilCore::class)
            unmockkStatic(ProjectRootManager::class)
            unmockkStatic(FileTypeManager::class)
            unmockkStatic(LocalFileSystem::class)
        }
    }

    @Test
    fun `test mixed production Python SDK assignment remains pending`() {
        val productionHandler = InspectionHandler()
        val localFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val localFile = mockk<VirtualFile>(relaxed = true)
        val unrelatedFile = mockk<VirtualFile>(relaxed = true)
        val localModule = mockk<com.intellij.openapi.module.Module>(relaxed = true)
        val unrelatedModule = mockk<com.intellij.openapi.module.Module>(relaxed = true)
        val localModuleRoots = mockk<ModuleRootManager>(relaxed = true)
        val unrelatedModuleRoots = mockk<ModuleRootManager>(relaxed = true)
        val pythonFileType = mockk<FileType>(relaxed = true)
        val fileTypeManager = mockk<FileTypeManager>(relaxed = true)
        val rootManager = mockk<ProjectRootManager>(relaxed = true)
        val localSdk = mockk<com.intellij.openapi.projectRoots.Sdk>(relaxed = true)
        val unrelatedSdk = mockk<com.intellij.openapi.projectRoots.Sdk>(relaxed = true)
        val localPath = "/tmp/TestProject/local.py"
        val unrelatedPath = "/tmp/TestProject/unrelated.py"
        val interpreterPath = "/tmp/TestProject/.venv/bin/python"
        listOf(localFile to localPath, unrelatedFile to unrelatedPath).forEach { (file, path) ->
            every { file.path } returns path
            every { file.name } returns path.substringAfterLast('/')
            every { file.isValid } returns true
            every { file.isDirectory } returns false
            every { file.isInLocalFileSystem } returns true
            every { file.extension } returns "py"
        }
        every { pythonFileType.name } returns "Python"
        every { rootManager.projectSdk } returns null
        every { localModuleRoots.sdk } returns localSdk
        every { unrelatedModuleRoots.sdk } returns unrelatedSdk
        every { localSdk.sdkType.name } returns "Python SDK"
        every { localSdk.name } returns "TestProject (.venv)"
        every { localSdk.homePath } returns interpreterPath
        every { unrelatedSdk.sdkType.name } returns "Python SDK"
        every { unrelatedSdk.name } returns "System Python"
        every { unrelatedSdk.homePath } returns "/usr/bin/python3"
        mockkStatic(LocalFileSystem::class)
        mockkStatic(FileTypeManager::class)
        mockkStatic(ProjectRootManager::class)
        mockkStatic(ModuleUtilCore::class)
        mockkStatic(ModuleRootManager::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath(localPath) } returns localFile
        every { localFileSystem.findFileByPath(unrelatedPath) } returns unrelatedFile
        every { FileTypeManager.getInstance() } returns fileTypeManager
        every { fileTypeManager.getFileTypeByExtension("py") } returns pythonFileType
        every { ProjectRootManager.getInstance(mockProject) } returns rootManager
        every { ModuleUtilCore.findModuleForFile(localFile, mockProject) } returns localModule
        every { ModuleUtilCore.findModuleForFile(unrelatedFile, mockProject) } returns unrelatedModule
        every { ModuleRootManager.getInstance(localModule) } returns localModuleRoots
        every { ModuleRootManager.getInstance(unrelatedModule) } returns unrelatedModuleRoots
        productionHandler.localPythonInterpreterPathsProvider = { setOf(interpreterPath) }
        productionHandler.registeredPythonSdksProvider = { listOf(localSdk) }
        productionHandler.pythonSdkUpdateScheduledProvider = { false }

        try {
            val readiness = productionHandler.projectAnalysisReadinessProvider(
                mockProject,
                InspectionCaptureScope(
                    scopeParam = "files",
                    files = listOf(localPath, unrelatedPath),
                    resolvedFiles = listOf(localPath, unrelatedPath),
                ),
            )
            assertFalse(readiness.ready)
            assertEquals("python_sdk_assignment_pending", readiness.reason)
            assertEquals(2, readiness.pythonFileCount)
            assertEquals(0, readiness.missingSdkFileCount)
            assertEquals(1, readiness.mismatchedSdkFileCount)
            assertEquals(1, readiness.assignedLocalPythonSdkCount)
            assertEquals(1, readiness.assignedLocalPythonFileCount)
        } finally {
            unmockkStatic(ModuleRootManager::class)
            unmockkStatic(ModuleUtilCore::class)
            unmockkStatic(ProjectRootManager::class)
            unmockkStatic(FileTypeManager::class)
            unmockkStatic(LocalFileSystem::class)
        }
    }

    @Test
    fun `test directory and whole project preserve Python readiness boundaries`() {
        val productionHandler = InspectionHandler()
        val localFileSystem = mockk<LocalFileSystem>(relaxed = true)
        val directory = mockk<VirtualFile>(relaxed = true)
        val selectedPythonFile = mockk<VirtualFile>(relaxed = true)
        val unrelatedPythonFile = mockk<VirtualFile>(relaxed = true)
        val pythonFileType = mockk<FileType>(relaxed = true)
        val fileTypeManager = mockk<FileTypeManager>(relaxed = true)
        val rootManager = mockk<ProjectRootManager>(relaxed = true)
        val searchScope = mockk<GlobalSearchScope>(relaxed = true)
        val directoryPath = "/tmp/TestProject/scripts"
        val selectedPath = "$directoryPath/selected.py"
        val unrelatedPath = "/tmp/TestProject/fixtures/unrelated.py"
        every { directory.path } returns directoryPath
        every { directory.isValid } returns true
        every { directory.isDirectory } returns true
        every { selectedPythonFile.path } returns selectedPath
        every { selectedPythonFile.name } returns "selected.py"
        every { selectedPythonFile.isValid } returns true
        every { selectedPythonFile.isDirectory } returns false
        every { selectedPythonFile.extension } returns "py"
        every { unrelatedPythonFile.path } returns unrelatedPath
        every { unrelatedPythonFile.name } returns "unrelated.py"
        every { unrelatedPythonFile.isValid } returns true
        every { unrelatedPythonFile.isDirectory } returns false
        every { unrelatedPythonFile.extension } returns "py"
        every { pythonFileType.name } returns "Python"
        every { rootManager.projectSdk } returns null
        mockkStatic(LocalFileSystem::class)
        mockkStatic(FileTypeManager::class)
        mockkStatic(ProjectRootManager::class)
        mockkStatic(ModuleUtilCore::class)
        mockkStatic(FilenameIndex::class)
        mockkStatic(GlobalSearchScope::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath(directoryPath) } returns directory
        every { FileTypeManager.getInstance() } returns fileTypeManager
        every { fileTypeManager.getFileTypeByExtension("py") } returns pythonFileType
        every { ProjectRootManager.getInstance(mockProject) } returns rootManager
        every { ModuleUtilCore.findModuleForFile(any(), mockProject) } returns null
        every { GlobalSearchScope.projectScope(mockProject) } returns searchScope
        every {
            FilenameIndex.getAllFilesByExt(mockProject, "py", searchScope)
        } returns listOf(selectedPythonFile, unrelatedPythonFile)

        try {
            val directoryReadiness = productionHandler.projectAnalysisReadinessProvider(
                mockProject,
                InspectionCaptureScope(
                    scopeParam = "directory",
                    directoryParam = directoryPath,
                    resolvedDirectory = directoryPath,
                ),
            )
            val wholeProjectReadiness = productionHandler.projectAnalysisReadinessProvider(
                mockProject,
                InspectionCaptureScope(scopeParam = "whole_project"),
            )

            assertEquals("python_sdk_missing", directoryReadiness.reason)
            assertEquals(1, directoryReadiness.pythonFileCount)
            assertEquals(1, directoryReadiness.missingSdkFileCount)
            assertEquals("python_sdk_missing", wholeProjectReadiness.reason)
            assertEquals(2, wholeProjectReadiness.pythonFileCount)
            assertEquals(2, wholeProjectReadiness.missingSdkFileCount)
        } finally {
            unmockkStatic(GlobalSearchScope::class)
            unmockkStatic(FilenameIndex::class)
            unmockkStatic(ModuleUtilCore::class)
            unmockkStatic(ProjectRootManager::class)
            unmockkStatic(FileTypeManager::class)
            unmockkStatic(LocalFileSystem::class)
        }
    }

    private fun quiescenceObservation(
        dumb: Boolean = false,
        psiModificationCount: Long = 10L,
        projectFileEventCount: Long = 0L,
    ) = ProjectQuiescenceObservation(
        dumb = dumb,
        projectState = InspectionProjectStateSnapshot(psiModificationCount, unsavedProjectDocuments = 0),
        projectFileEventCount = projectFileEventCount,
    )

    @Test
    fun `test project quiescence returns after one stable window when project is already quiet`() {
        var clock = 0L

        val evidence = awaitProjectQuiescence(
            now = { clock },
            sleep = { millis -> clock += millis },
            observe = { quiescenceObservation() },
            checkCanceled = {},
            stableMs = 300,
            timeoutMs = 5_000,
            pollMs = 100,
        )

        assertTrue(evidence.attempted)
        assertFalse(evidence.timedOut)
        assertEquals(300, evidence.waitedMs)
        assertEquals(0, evidence.projectStateChangeCount)
        assertFalse(evidence.dumbModeObserved)
        assertFalse(evidence.projectFileEventsObserved)
    }

    @Test
    fun `test project quiescence restarts stable window after post open churn`() {
        var clock = 0L
        val observations = ArrayDeque(
            listOf(
                quiescenceObservation(),
                quiescenceObservation(dumb = true),
                quiescenceObservation(psiModificationCount = 11L),
                quiescenceObservation(psiModificationCount = 11L, projectFileEventCount = 1L),
            ),
        )
        val settled = quiescenceObservation(psiModificationCount = 11L, projectFileEventCount = 1L)

        val evidence = awaitProjectQuiescence(
            now = { clock },
            sleep = { millis -> clock += millis },
            observe = { observations.removeFirstOrNull() ?: settled },
            checkCanceled = {},
            stableMs = 300,
            timeoutMs = 5_000,
            pollMs = 100,
        )

        assertFalse(evidence.timedOut)
        assertEquals(600, evidence.waitedMs)
        assertTrue(evidence.dumbModeObserved)
        assertTrue(evidence.projectFileEventsObserved)
        assertEquals(1, evidence.projectStateChangeCount)
    }

    @Test
    fun `test project quiescence waits a full stable window after indexing ends`() {
        var clock = 0L
        val observations = ArrayDeque(List(5) { quiescenceObservation(dumb = true) })

        val evidence = awaitProjectQuiescence(
            now = { clock },
            sleep = { millis -> clock += millis },
            observe = { observations.removeFirstOrNull() ?: quiescenceObservation() },
            checkCanceled = {},
            stableMs = 300,
            timeoutMs = 5_000,
            pollMs = 100,
        )

        assertTrue(evidence.dumbModeObserved)
        assertFalse(evidence.timedOut)
        assertEquals(700, evidence.waitedMs)
    }

    @Test
    fun `test project quiescence is bounded when project never settles`() {
        var clock = 0L
        var psiModificationCount = 0L

        var polls = 0

        val evidence = awaitProjectQuiescence(
            now = { clock },
            sleep = { millis -> clock += millis },
            observe = { quiescenceObservation(psiModificationCount = psiModificationCount++) },
            checkCanceled = { check(++polls < 1_000) { "the wait never reached its upper bound" } },
            stableMs = 300,
            timeoutMs = 1_000,
            pollMs = 100,
        )

        assertTrue(evidence.timedOut)
        assertEquals(1_000, evidence.waitedMs)
        assertEquals(true, evidence.diagnosticMap()["project_quiescence_timed_out"])
    }

    @Test
    fun `test project quiescence reports dumb mode that outlasts the bound`() {
        var clock = 0L
        var polls = 0

        val evidence = awaitProjectQuiescence(
            now = { clock },
            sleep = { millis -> clock += millis },
            observe = { quiescenceObservation(dumb = true) },
            checkCanceled = { check(++polls < 1_000) { "the wait never reached its upper bound" } },
            stableMs = 300,
            timeoutMs = 100,
            pollMs = 100,
        )

        assertTrue(evidence.timedOut)
        assertTrue(evidence.dumbModeObserved)
        assertEquals(300, evidence.waitedMs)
    }

    @Test
    fun `test project quiescence propagates cancellation and is skipped when disabled`() {
        assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
            awaitProjectQuiescence(
                now = { 0L },
                sleep = {},
                observe = { quiescenceObservation() },
                checkCanceled = { throw com.intellij.openapi.progress.ProcessCanceledException() },
                stableMs = 300,
            )
        }

        val disabled = awaitProjectQuiescence(
            now = { 0L },
            sleep = { error("Disabled quiescence must not sleep.") },
            observe = { error("Disabled quiescence must not observe.") },
            checkCanceled = {},
            stableMs = 0,
        )
        assertFalse(disabled.attempted)
    }

    @Test
    fun `test inspection inputs are captured only after post-open project churn settles`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns emptyList()
        every { mockProfileManager.getProfile("RedLane", false) } returns null
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)
        val churnStartedMs = handler.currentTimeMs()
        val settledModificationCount = 5_000L
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } answers {
            val elapsedMs = handler.currentTimeMs() - churnStartedMs
            if (elapsedMs < 1_500) elapsedMs else settledModificationCount
        }

        processTriggerRequest("/api/inspection/trigger?profile=RedLane")

        assertEquals("profile_resolution_error", buildInspectionStatus()["capture_incomplete_reason"])
        assertEquals(
            settledModificationCount,
            InspectionResultsStore.getProjectState(projectKey(mockProject))?.psiModificationCount,
        )
    }

    @Test
    fun `test explicit missing inspection profile publishes capture incomplete snapshot`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns emptyList()
        every { mockProfileManager.getProfile("RedLane", false) } returns null
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        val response = processTriggerRequest("/api/inspection/trigger?profile=RedLane")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"profile\": \"RedLane\""))
        verify(exactly = 1) { mockProfileManager.getProfile("RedLane", false) }
        verify(exactly = 0) { mockProfileManager.getProfile("RedLane") }
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
        val status = buildInspectionStatus()
        assertEquals("capture_incomplete", status["snapshot_outcome"])
        assertEquals("profile_resolution", status["results_source"])
        assertEquals(true, status["capture_incomplete"])
        assertEquals("profile_resolution_error", status["capture_incomplete_reason"])
        assertEquals("UNKNOWN", status["inspection_verdict"])
        assertEquals("profile_resolution_error", status["inspection_verdict_reason"])
        val problemsResponse = processGetRequest("/api/inspection/problems?severity=all")
        val problemsBody = problemsResponse.content().toString(Charsets.UTF_8)
        assertEquals(HttpResponseStatus.OK, problemsResponse.status())
        assertTrue(problemsBody.contains("\"status\": \"capture_incomplete\""))
        assertTrue(problemsBody.contains("\"capture_incomplete\": true"))
        @Suppress("UNCHECKED_CAST")
        val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
        assertEquals("RedLane", diagnostic["profile_requested"])
        assertEquals(true, diagnostic["profile_missing"])
        assertEquals("profile_missing", diagnostic["exit_reason"])
    }

    @Test
    fun `test explicit missing inspection profile is checked before empty changed files shortcut`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns emptyList()
        every { mockProfileManager.getProfile("RedLane", false) } returns null
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        val response = processTriggerRequest("/api/inspection/trigger?scope=changed_files&include_unversioned=false&profile=RedLane")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"scope\": \"changed_files\""))
        assertTrue(body.contains("\"profile\": \"RedLane\""))
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
        val status = buildInspectionStatus()
        assertEquals("capture_incomplete", status["snapshot_outcome"])
        assertEquals("profile_resolution", status["results_source"])
        assertEquals(true, status["capture_incomplete"])
        assertEquals("UNKNOWN", status["inspection_verdict"])
        assertEquals("profile_resolution_error", status["inspection_verdict_reason"])
        @Suppress("UNCHECKED_CAST")
        val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
        assertEquals("RedLane", diagnostic["profile_requested"])
        assertEquals(true, diagnostic["profile_missing"])
        assertEquals("profile_missing", diagnostic["exit_reason"])
    }

    @Test
    fun `test exact inspection profile works when project profile list is unreadable`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } throws IllegalStateException("profiles unavailable")
        every { mockProfileManager.getProfile("RedLane", false) } returns mockProfile
        every { mockProfile.name } returns "RedLane"
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)
        val inputFingerprint = projectInputsFingerprint(profileName = "RedLane")
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        handler.projectContentTrackerFactory = { _, _ -> FakeInspectionProjectContentTracker() }

        val response = processTriggerRequest(
            "/api/inspection/trigger?scope=changed_files&include_unversioned=false&profile=RedLane",
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"profile\": \"RedLane\""))
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
        val status = buildInspectionStatus()
        assertEquals("clean_confirmed", status["snapshot_outcome"])
        assertEquals("empty_changed_files", status["results_source"])
        assertEquals(false, status["capture_incomplete"])
    }

    @Test
    fun `test isSupported returns true for inspection endpoints`() {
        val mockRequest = mockk<FullHttpRequest>()
        
        every { mockRequest.uri() } returns "/api/inspection/problems"
        every { mockRequest.method() } returns HttpMethod.GET
        
        assertTrue(handler.isSupported(mockRequest))
    }

    @Test
    fun `test isSupported returns false for non-inspection endpoints`() {
        val mockRequest = mockk<FullHttpRequest>()
        
        every { mockRequest.uri() } returns "/api/other/endpoint"
        every { mockRequest.method() } returns HttpMethod.GET
        
        assertFalse(handler.isSupported(mockRequest))
    }

    @Test
    fun `test process handles missing project gracefully`() {
        every { mockProjectManager.openProjects } returns emptyArray()

        val response = processGetRequest(
            "/api/inspection/route?project_key=path:/missing&client_run_id=abababab-abab-4bab-8bab-abababababab"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"inspection_verdict\": \"UNKNOWN\""), body)
        assertTrue(body.contains("\"code\": \"no_project\""), body)
        assertTrue(body.contains("\"classification\": \"configuration_blocked\""), body)
        assertTrue(body.contains("\"phase\": \"route\""), body)
        assertTrue(body.contains("\"client_run_id\": \"abababab-abab-4bab-8bab-abababababab\""), body)
    }

    @Test
    fun `test filtered findings stay unknown when scope diagnostics were truncated`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.setSnapshot(
            projectKey(mockProject),
            InspectionResultsSnapshot(
                problems = listOf(
                    mapOf(
                        "file" to "/tmp/TestProject/src/Warning.kt",
                        "severity" to "warning",
                        "description" to "warning outside requested filter",
                    ),
                ),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "test",
                captureDiagnostic = mapOf(
                    "scope_file_resolved_count" to 26,
                    "scope_file_diagnostic_count" to 25,
                    "scope_file_diagnostics_omitted_count" to 1,
                    "scope_file_diagnostics_truncated" to true,
                    "scope_file_diagnostics_complete" to false,
                    "scope_file_diagnostics" to emptyList<Map<String, Any?>>(),
                ),
                runId = 1L,
            ),
        )

        val response = processGetRequest("/api/inspection/problems?severity=error")
        val body = response.content().toString(Charsets.UTF_8)

        assertTrue(body.contains("\"total_problems\": 0"), body)
        assertTrue(body.contains("\"inspection_verdict\": \"UNKNOWN\""), body)
        assertTrue(body.contains("\"inspection_verdict_reason\": \"scope_semantic_coverage_truncated\""), body)
        assertTrue(body.contains("\"classification\": \"legitimate_fail_closed\""), body)
        assertTrue(Regex("\\\"project_key_hash\\\": \\\"sha256:[0-9a-f]{64}\\\"").containsMatchIn(body), body)
        assertTrue(body.contains("\"scope_file_diagnostics_complete\": false"), body)
    }

    @Test
    fun `test filtered findings stay decisive when aggregate semantic proof covers bounded diagnostics`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.setSnapshot(
            projectKey(mockProject),
            InspectionResultsSnapshot(
                problems = listOf(
                    mapOf(
                        "file" to "/tmp/TestProject/src/Warning.kt",
                        "severity" to "warning",
                        "description" to "warning outside requested filter",
                    ),
                ),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                source = "test",
                captureDiagnostic = mapOf(
                    "scope_file_resolved_count" to 26,
                    "scope_file_diagnostic_count" to 25,
                    "scope_file_diagnostics_omitted_count" to 1,
                    "scope_file_diagnostics_truncated" to true,
                    "scope_file_diagnostics_complete" to false,
                    "scope_file_semantic_evidence_complete" to true,
                    "scope_file_semantic_coverage" to mapOf(
                        "schema_version" to 1,
                        "evaluated_file_count" to 26,
                        "unproven_file_count" to 0,
                        "missing_file_count" to 0,
                        "reason_counts" to emptyMap<String, Int>(),
                        "missing_files" to emptyList<Map<String, Any?>>(),
                        "metadata_file_count" to 0,
                        "metadata_files" to emptyList<Map<String, Any?>>(),
                    ),
                    "scope_file_diagnostics" to emptyList<Map<String, Any?>>(),
                ),
                runId = 1L,
            ),
        )

        val response = processGetRequest("/api/inspection/problems?severity=error")
        val body = response.content().toString(Charsets.UTF_8)

        assertTrue(body.contains("\"total_problems\": 0"), body)
        assertTrue(body.contains("\"inspection_verdict\": \"GREEN\""), body)
        assertTrue(body.contains("\"inspection_verdict_reason\": \"no_matching_findings\""), body)
        assertTrue(body.contains("\"scope_file_diagnostics_complete\": false"), body)
        assertTrue(body.contains("\"scope_file_semantic_evidence_complete\": true"), body)
    }

    @Test
    fun `test changed files final publication reconciles verified psi churn`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val psiModificationCount = AtomicLong(11L)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } answers {
            psiModificationCount.get()
        }
        mockChangedFiles(listOf("/tmp/TestProject/src/Included.kt"))
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        val snapshotProblems = listOf(changedFileProblem())
        mockExtractor(snapshotProblems)
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )

        publishInspectionSnapshot(
            snapshot = changedFilesSnapshot(snapshotProblems),
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )

        val reconciledSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        assertEquals(11L, reconciledSnapshot.projectState.psiModificationCount)
        assertEquals("current_run_psi_churn", reconciledSnapshot.reconciliationChangeKind)
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = false),
        )
        val completedStatus = buildInspectionStatus()
        assertEquals(false, completedStatus["results_may_be_stale"])
        assertEquals("current_run_psi_churn", completedStatus["snapshot_change_kind"])

        psiModificationCount.set(12L)
        val editedStatus = buildInspectionStatus()
        assertEquals(true, editedStatus["results_may_be_stale"])
        assertEquals("project_changed_since_inspection", editedStatus["snapshot_change_kind"])
    }

    @Test
    fun `test empty changed files final publication reconciles verified psi churn without extraction`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 11L
        mockChangedFiles(emptyList())
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        val extractor = mockk<EnhancedTreeExtractor>()
        enhancedTreeExtractorFactory = { extractor }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )

        publishInspectionSnapshot(
            snapshot = changedFilesSnapshot(
                problems = emptyList(),
                resolvedFiles = emptyList(),
                source = "empty_changed_files",
                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
            ),
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )

        val reconciledSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        assertEquals(11L, reconciledSnapshot.projectState.psiModificationCount)
        assertEquals("empty_changed_files", reconciledSnapshot.source)
        assertEquals("current_run_psi_churn", reconciledSnapshot.reconciliationChangeKind)
        verify(exactly = 0) { extractor.extractAllProblemsWithStatus(any()) }
    }

    @Test
    fun `test changed files final publication rejects changed resolved scope`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 11L
        mockChangedFiles(
            listOf(
                "/tmp/TestProject/src/Included.kt",
                "/tmp/TestProject/src/Added.kt",
            )
        )
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        val extractor = mockk<EnhancedTreeExtractor>()
        enhancedTreeExtractorFactory = { extractor }
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )

        publishInspectionSnapshot(
            snapshot = changedFilesSnapshot(listOf(changedFileProblem())),
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )

        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        assertEquals(10L, publishedSnapshot.projectState.psiModificationCount)
        assertNull(publishedSnapshot.reconciliationChangeKind)
        verify(exactly = 0) { extractor.extractAllProblemsWithStatus(any()) }
    }

    @Test
    fun `test changed files final publication rejects unsaved documents`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )

        publishInspectionSnapshot(
            snapshot = changedFilesSnapshot(listOf(changedFileProblem())),
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 1),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )

        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        assertEquals(10L, publishedSnapshot.projectState.psiModificationCount)
        assertNull(publishedSnapshot.reconciliationChangeKind)
    }

    @Test
    fun `test changed files final publication does not replace a newer run`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        setInspectionRunState(
            key,
            InspectionRunState(runId = 2L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )

        publishInspectionSnapshot(
            snapshot = changedFilesSnapshot(listOf(changedFileProblem())),
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = projectInputsFingerprint(),
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )

        assertNull(InspectionResultsStore.getSnapshot(key))
    }

    @Test
    fun `test changed files final publication rejects unreadable live extraction`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 11L
        mockChangedFiles(listOf("/tmp/TestProject/src/Included.kt"))
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        mockExtractorFailure()
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )

        publishInspectionSnapshot(
            snapshot = changedFilesSnapshot(listOf(changedFileProblem())),
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )

        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        assertEquals(10L, publishedSnapshot.projectState.psiModificationCount)
        assertNull(publishedSnapshot.reconciliationChangeKind)
    }

    @Test
    fun `test changed files final publication rejects live identity mismatch`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 11L
        mockChangedFiles(listOf("/tmp/TestProject/src/Included.kt"))
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        mockExtractor(listOf(changedFileProblem(description = "different live finding")))
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )

        publishInspectionSnapshot(
            snapshot = changedFilesSnapshot(listOf(changedFileProblem())),
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )

        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        assertEquals(10L, publishedSnapshot.projectState.psiModificationCount)
        assertNull(publishedSnapshot.reconciliationChangeKind)
    }

    @Test
    fun `test changed files final publication rejects project input mismatch`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 11L
        mockChangedFiles(listOf("/tmp/TestProject/src/Included.kt"))
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        handler.projectInputsFingerprintProvider = { _, _ -> projectInputsFingerprint(profileName = "ChangedProfile") }
        mockExtractor(listOf(changedFileProblem()))
        setInspectionRunState(
            key,
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )

        publishInspectionSnapshot(
            snapshot = changedFilesSnapshot(listOf(changedFileProblem())),
            captureEndState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
            projectStateChangedDuringCapture = true,
            inspectionInputFingerprint = inputFingerprint,
            projectContentTracker = FakeInspectionProjectContentTracker(),
        )

        val publishedSnapshot = requireNotNull(InspectionResultsStore.getSnapshot(key))
        assertEquals(10L, publishedSnapshot.projectState.psiModificationCount)
        assertNull(publishedSnapshot.reconciliationChangeKind)
    }

    @Test
    fun `test project content tracker path matching excludes metadata and sibling roots`() {
        val roots = listOf("/tmp/TestProject", "/tmp/shared-content")

        assertTrue(isTrackedInspectionInputPath("/tmp/TestProject", roots, "/tmp/TestProject/src/App.kt"))
        assertTrue(isTrackedInspectionInputPath("/tmp/TestProject", roots, "/tmp/shared-content/lib.py"))
        assertTrue(isTrackedInspectionInputPath("/tmp/TestProject", roots, "/tmp/TestProject/.idea/misc.xml"))
        assertFalse(isTrackedInspectionInputPath("/tmp/TestProject", roots, "/tmp/TestProject/.idea"))
        assertFalse(isTrackedInspectionInputPath("/tmp/TestProject", roots, "/tmp/TestProject/.idea/workspace.xml"))
        assertFalse(
            isTrackedInspectionInputPath(
                "/tmp/TestProject",
                roots,
                "/tmp/TestProject/.idea/workspace.xml___jb_tmp___",
            )
        )
        assertFalse(isTrackedInspectionInputPath("/tmp/TestProject", roots, "/tmp/TestProject/.git/index"))
        assertFalse(isTrackedInspectionInputPath("/tmp/TestProject", roots, "/tmp/TestProject/modules/sub/.git/index"))
        assertFalse(isTrackedInspectionInputPath("/tmp/TestProject", roots, "/tmp/shared-content/.git/index"))
        assertFalse(isTrackedInspectionInputPath("/tmp/TestProject", roots, "/tmp/TestProject-copy/src/App.kt"))
        assertFalse(
            isTrackedInspectionInputPath(
                projectBasePath = "/tmp/TestProject",
                rootPaths = roots,
                eventPath = "/tmp/TestProject/build/classes/App.class",
                excludedRootPaths = listOf("/tmp/TestProject/build"),
            )
        )
    }

    @Test
    fun `test inspection event paths preserve both sides of moves and renames`() {
        val moveEvent = mockk<com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent>()
        every { moveEvent.oldPath } returns "/tmp/outside/App.kt"
        every { moveEvent.newPath } returns "/tmp/TestProject/src/App.kt"
        val renameEvent = mockk<com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent>()
        every { renameEvent.isRename } returns true
        every { renameEvent.oldPath } returns "/tmp/TestProject/src/Old.kt"
        every { renameEvent.newPath } returns "/tmp/TestProject/src/New.kt"

        assertEquals(
            listOf("/tmp/outside/App.kt", "/tmp/TestProject/src/App.kt"),
            inspectionEventPaths(moveEvent),
        )
        assertEquals(
            listOf("/tmp/TestProject/src/Old.kt", "/tmp/TestProject/src/New.kt"),
            inspectionEventPaths(renameEvent),
        )
    }

    @Test
    fun `test archive inspection roots map to their backing local jar`() {
        val archiveRoot = mockk<VirtualFile>()
        every { archiveRoot.path } returns "/tmp/sdk/lib/runtime.jar!/"
        every { archiveRoot.isInLocalFileSystem } returns false

        assertEquals("/tmp/sdk/lib/runtime.jar", localInspectionRootPath(archiveRoot))
    }

    @Test
    fun `test inspection profile fingerprint preserves scope order and tool options`() {
        val firstProfile = mockk<InspectionProfileImpl>()
        val reorderedProfile = mockk<InspectionProfileImpl>()
        val changedOptionProfile = mockk<InspectionProfileImpl>()
        every { firstProfile.writeExternal(any()) } answers {
            populateInspectionProfileElement(
                target = firstArg(),
                scopeNames = listOf("Production", "Tests"),
                optionValue = "strict",
            )
        }
        every { reorderedProfile.writeExternal(any()) } answers {
            populateInspectionProfileElement(
                target = firstArg(),
                scopeNames = listOf("Tests", "Production"),
                optionValue = "strict",
            )
        }
        every { changedOptionProfile.writeExternal(any()) } answers {
            populateInspectionProfileElement(
                target = firstArg(),
                scopeNames = listOf("Production", "Tests"),
                optionValue = "lenient",
            )
        }
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "inspectionProfileConfigurationHash",
            InspectionProfileImpl::class.java,
        )
        method.isAccessible = true

        val firstHash = method.invoke(handler, firstProfile)
        val reorderedHash = method.invoke(handler, reorderedProfile)
        val changedOptionHash = method.invoke(handler, changedOptionProfile)

        assertNotEquals(firstHash, reorderedHash)
        assertNotEquals(firstHash, changedOptionHash)
    }

    @Test
    fun `test long-running inspection remains serialized until worker finishes`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        handler.inspectionRunExpirationMs = 500L
        setInspectionRunState(
            projectKey(mockProject),
            InspectionRunState(runId = 1L, triggerTimeMs = System.currentTimeMillis() - 1000L, inProgress = true),
        )
        InspectionResultsStore.setSnapshot(
            projectKey(mockProject),
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = InspectionProjectStateSnapshot(psiModificationCount = 11L, unsavedProjectDocuments = 0),
                outcome = InspectionSnapshotOutcome.CAPTURE_INCOMPLETE,
                source = "inspection_view",
                note = "Inspection failed before results could be captured.",
                runId = 1L,
            )
        )

        val status = buildInspectionStatus()

        assertEquals(true, status["inspection_in_progress"])
        assertEquals(true, status["is_scanning"])
        assertEquals(true, status["inspection_run_expired"])
        assertEquals("capture_incomplete", status["snapshot_outcome"])
    }

    @Test
    fun `test cancellation endpoint cancels the active inspection indicator`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        val key = projectKey(mockProject)
        val indicator = mockk<ProgressIndicator>(relaxed = true)
        val worker = Thread.currentThread()
        handler.inspectionRunNowNanos = { 5_000_000_000L }
        handler.inspectionWorkerStackProvider = { listOf("inspection-worker-frame") }
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = true,
                runStartedNanos = 1_000_000_000L,
                stage = InspectionRunStage.NATIVE_EXECUTE,
                stageStartedNanos = 3_000_000_000L,
            ),
        )
        setInspectionRunControl(
            key,
            InspectionRunControl(runId = 7L, indicator = indicator).also { it.workerThread.set(worker) },
        )

        val response = processGetRequest(
            "/api/inspection/cancel?worktree_path=/tmp/TestProject&inspection_run_id=7",
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"cancel_requested\""))
        assertTrue(body.contains("\"inspection_run_id\": 7"))
        assertTrue(body.contains("\"source\": \"cancellation\""))
        assertTrue(body.contains("\"inspection_stage_at_failure\": \"native_execute\""))
        assertTrue(body.contains("\"inspection_stage_elapsed_ms\": 2000"))
        assertTrue(body.contains("\"inspection_run_elapsed_ms\": 4000"))
        assertTrue(body.contains("\"inspection_worker_stack\": [\"inspection-worker-frame\"]"))
        verify(exactly = 1) { indicator.cancel() }
    }

    @Test
    fun `test inspection stage timing uses the monotonic clock`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        val nowNanos = AtomicLong(2_000_000_000L)
        handler.inspectionRunNowNanos = nowNanos::get
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 11L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = true,
                runStartedNanos = 1_000_000_000L,
                stage = InspectionRunStage.SYNC,
                stageStartedNanos = 1_500_000_000L,
            ),
        )

        val syncStatus = buildInspectionStatus()
        nowNanos.set(3_000_000_000L)
        transitionInspectionRunStage(key, 11L, InspectionRunStage.SMART_WAIT)
        val smartWaitStatus = buildInspectionStatus()

        assertEquals("sync", syncStatus["inspection_stage"])
        assertEquals(500L, syncStatus["inspection_stage_elapsed_ms"])
        assertEquals(1000L, syncStatus["inspection_run_elapsed_ms"])
        assertEquals("smart_wait", smartWaitStatus["inspection_stage"])
        assertEquals(0L, smartWaitStatus["inspection_stage_elapsed_ms"])
        assertEquals(2000L, smartWaitStatus["inspection_run_elapsed_ms"])
        @Suppress("UNCHECKED_CAST")
        val history = smartWaitStatus["inspection_stage_history"] as List<Map<String, Any>>
        assertEquals(listOf(mapOf("stage" to "sync", "elapsed_ms" to 1500L)), history)

        val transitionsPerRound = 12
        val historySizeAfterEachRound = (1..2).map {
            repeat(transitionsPerRound) { index ->
                nowNanos.addAndGet(1_000_000L)
                val nextStage = InspectionRunStage.entries[(index + 2) % InspectionRunStage.entries.size]
                transitionInspectionRunStage(key, 11L, nextStage)
            }
            requireNotNull(inspectionRunState(key)).stageHistory.size
        }
        assertEquals(historySizeAfterEachRound[0], historySizeAfterEachRound[1])
        assertTrue(historySizeAfterEachRound[1] < transitionsPerRound)
    }

    @Test
    fun `test terminal timing freezes and rejects a late stage update`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        val key = projectKey(mockProject)
        val nowNanos = AtomicLong(4_000_000_000L)
        handler.inspectionRunNowNanos = nowNanos::get
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

        finishInspectionRun(key, 22L)
        nowNanos.set(9_000_000_000L)
        transitionInspectionRunStage(key, 22L, InspectionRunStage.NATIVE_EXECUTE)
        val status = buildInspectionStatus()

        assertEquals("smart_wait", status["inspection_stage"])
        assertEquals(2000L, status["inspection_stage_elapsed_ms"])
        assertEquals(3000L, status["inspection_run_elapsed_ms"])
        assertEquals("completed", status["inspection_terminal_outcome"])
    }

    @Test
    fun `test worker stack capture failure does not block cancellation`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        val key = projectKey(mockProject)
        val indicator = mockk<ProgressIndicator>(relaxed = true)
        handler.inspectionRunNowNanos = { 2_000_000_000L }
        val cancellationSignalled = AtomicBoolean(false)
        val signalledBeforeStack = AtomicBoolean(false)
        every { indicator.cancel() } answers { cancellationSignalled.set(true) }
        handler.inspectionWorkerStackProvider = {
            signalledBeforeStack.set(cancellationSignalled.get())
            error("stack unavailable")
        }
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = true,
                runStartedNanos = 1_000_000_000L,
                stage = InspectionRunStage.SMART_WAIT,
                stageStartedNanos = 1_500_000_000L,
            ),
        )
        setInspectionRunControl(
            key,
            InspectionRunControl(runId = 7L, indicator = indicator).also {
                it.workerThread.set(Thread.currentThread())
            },
        )

        val response = processGetRequest(
            "/api/inspection/cancel?worktree_path=/tmp/TestProject&inspection_run_id=7",
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"cancel_requested\""))
        assertTrue(body.contains("\"inspection_stage_at_failure\": \"smart_wait\""))
        assertTrue(body.contains("\"inspection_worker_stack\": []"))
        assertTrue(signalledBeforeStack.get())
        verify(exactly = 1) { indicator.cancel() }
    }

    @Test
    fun `test cancellation request preserves observed capture timeout outcome`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        val key = projectKey(mockProject)
        val indicator = mockk<ProgressIndicator>(relaxed = true)
        val timeoutDiagnostic = InspectionRunFailureDiagnostic(
            source = InspectionRunFailureSource.WAIT_TIMEOUT,
            stageAtFailure = InspectionRunStage.NATIVE_EXECUTE,
            stageElapsedMs = 118_000L,
            runElapsedMs = 120_000L,
            dumbMode = false,
            workerThreadName = "inspection-worker",
            workerStack = listOf("blocked-native-frame"),
        )
        val nowNanos = AtomicLong(123_000_000_000L)
        handler.inspectionRunNowNanos = nowNanos::get
        handler.inspectionWorkerStackProvider = { listOf("cancel-frame") }
        setInspectionRunState(
            key,
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = true,
                runStartedNanos = 1_000_000_000L,
                stage = InspectionRunStage.NATIVE_EXECUTE,
                stageStartedNanos = 3_000_000_000L,
                failureDiagnostics = listOf(timeoutDiagnostic),
            ),
        )
        transitionInspectionRunStage(key, 7L, InspectionRunStage.RESULT_SETTLING)
        setInspectionRunControl(
            key,
            InspectionRunControl(runId = 7L, indicator = indicator).also {
                it.workerThread.set(Thread.currentThread())
            },
        )
        nowNanos.set(124_000_000_000L)
        recordInspectionRunFailureDiagnostic(
            key,
            7L,
            InspectionRunFailureSource.CAPTURE_DEADLINE,
        )
        nowNanos.set(125_000_000_000L)

        val response = processGetRequest(
            "/api/inspection/cancel?worktree_path=/tmp/TestProject&inspection_run_id=7",
        )

        assertEquals(HttpResponseStatus.OK, response.status())
        finishInspectionRun(key, 7L)
        val terminalState = requireNotNull(inspectionRunState(key))
        val diagnostics = terminalState.failureDiagnostics
        assertEquals(
            listOf(
                InspectionRunFailureOutcome.TIMEOUT,
                InspectionRunFailureOutcome.TIMEOUT,
                InspectionRunFailureOutcome.CANCELLED,
            ),
            diagnostics.map { it.outcome },
        )
        assertEquals(
            listOf(
                InspectionRunFailureSource.WAIT_TIMEOUT,
                InspectionRunFailureSource.CAPTURE_DEADLINE,
                InspectionRunFailureSource.CANCELLATION,
            ),
            diagnostics.map { it.source },
        )
        assertEquals("blocked-native-frame", diagnostics.first().workerStack.single())
        assertEquals("cancel-frame", diagnostics[1].workerStack.single())
        assertEquals("cancel-frame", diagnostics.last().workerStack.single())
        assertEquals(false, terminalState.inProgress)
        assertEquals(InspectionRunTerminalOutcome.TIMED_OUT, terminalState.terminalOutcome)
        verify(exactly = 1) { indicator.cancel() }
    }

    @Test
    fun `test exact proof interruption keeps original worker evidence and freezes its terminal outcome`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        val key = projectKey(mockProject)
        val now = AtomicLong(10_000_000_000L)
        handler.inspectionRunNowNanos = now::get
        val worker = Thread("blocked-exact-proof")
        handler.inspectionWorkerStackProvider = { thread ->
            assertSame(worker, thread)
            listOf("BlockedInspection.awaitData")
        }
        val record = InspectionHandler::class.java.getDeclaredMethod(
            "recordInspectionRunFailureDiagnostic", String::class.java, Long::class.javaPrimitiveType,
            Project::class.java, InspectionRunFailureSource::class.java, ExactProofFailureContext::class.java,
        ).apply { isAccessible = true }
        listOf(
            InspectionRunFailureSource.EXACT_PROOF_DEADLINE to InspectionRunTerminalOutcome.TIMED_OUT,
            InspectionRunFailureSource.EXACT_PROOF_WRITE_PREEMPTED to InspectionRunTerminalOutcome.PREEMPTED,
        ).forEachIndexed { index, (source, expectedOutcome) ->
            val runId = 70L + index
            setInspectionRunState(key, InspectionRunState(
                runId = runId, triggerTimeMs = 1L, inProgress = true,
                runStartedNanos = 1_000_000_000L, stage = InspectionRunStage.EXACT_PROOF,
                stageStartedNanos = 2_000_000_000L,
            ))
            val initialDiagnostic = record.invoke(handler, key, runId, mockProject, source,
                ExactProofFailureContext("BlockedInspection", "/tmp/TestProject/test.py", worker))
            assertSame(initialDiagnostic, recordInspectionRunFailureDiagnostic(key, runId, source))
            assertEquals(1, inspectionRunState(key)?.failureDiagnostics?.size)
            transitionInspectionRunStage(key, runId, InspectionRunStage.PUBLISH)
            finishInspectionRun(key, runId)
            val state = requireNotNull(inspectionRunState(key))
            assertEquals(expectedOutcome, state.terminalOutcome)
            val evidence = state.failureDiagnostics.single()
            assertEquals(InspectionRunStage.EXACT_PROOF, evidence.stageAtFailure)
            assertEquals("BlockedInspection", evidence.toolShortName)
            assertEquals("/tmp/TestProject/test.py", evidence.filePath)
            assertEquals("execution", evidence.workerPhase)
            assertEquals("blocked-exact-proof", evidence.workerThreadName)
            assertEquals(listOf("BlockedInspection.awaitData"), evidence.workerStack)
            now.addAndGet(5_000_000_000L)
            record.invoke(handler, key, runId, mockProject, source,
                ExactProofFailureContext("LaterInspection", "/tmp/TestProject/other.py", worker))
            finishInspectionRun(key, runId)
            assertEquals(state, inspectionRunState(key))
        }
    }

    @Test
    fun `test cancellation endpoint requires the inspection run id`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        val key = projectKey(mockProject)
        val indicator = mockk<ProgressIndicator>(relaxed = true)
        setInspectionRunState(
            key,
            InspectionRunState(runId = 7L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        setInspectionRunControl(key, InspectionRunControl(runId = 7L, indicator = indicator))

        val response = processGetRequest("/api/inspection/cancel?worktree_path=/tmp/TestProject")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("Parameter 'inspection_run_id' is required."))
        verify(exactly = 0) { indicator.cancel() }
    }

    @Test
    fun `test cancellation endpoint refuses to cancel a newer inspection run`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        val key = projectKey(mockProject)
        val indicator = mockk<ProgressIndicator>(relaxed = true)
        setInspectionRunState(
            key,
            InspectionRunState(runId = 8L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        setInspectionRunControl(key, InspectionRunControl(runId = 8L, indicator = indicator))

        val response = processGetRequest(
            "/api/inspection/cancel?worktree_path=/tmp/TestProject&inspection_run_id=7",
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"run_changed\""))
        assertTrue(body.contains("\"expected_inspection_run_id\": 7"))
        assertTrue(body.contains("\"inspection_run_id\": 8"))
        verify(exactly = 0) { indicator.cancel() }
    }

    @Test
    fun `test cancellation endpoint detects a completed replacement run`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        val key = projectKey(mockProject)
        setInspectionRunState(
            key,
            InspectionRunState(runId = 8L, triggerTimeMs = System.currentTimeMillis(), inProgress = false),
        )

        val response = processGetRequest(
            "/api/inspection/cancel?worktree_path=/tmp/TestProject&inspection_run_id=7",
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"run_changed\""))
        assertTrue(body.contains("\"inspection_in_progress\": false"))
        assertTrue(body.contains("\"expected_inspection_run_id\": 7"))
        assertTrue(body.contains("\"inspection_run_id\": 8"))
    }

    @Test
    fun `test queued inspection can be cancelled before its worker starts`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        val queuedTasks = mutableListOf<Runnable>()
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            queuedTasks += firstArg<Runnable>()
            mockk(relaxed = true)
        }

        val triggerResponse = processTriggerRequest("/api/inspection/trigger")
        val cancelResponse = processGetRequest(
            "/api/inspection/cancel?worktree_path=/tmp/TestProject&inspection_run_id=1",
        )
        val cancelBody = cancelResponse.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, triggerResponse.status())
        assertTrue(triggerResponse.content().toString(Charsets.UTF_8).contains("\"run_id\": 1"))
        assertEquals(1, queuedTasks.size)
        assertEquals(HttpResponseStatus.OK, cancelResponse.status())
        assertTrue(cancelBody.contains("\"status\": \"cancel_requested\""))
        assertTrue(cancelBody.contains("\"inspection_run_id\": 1"))
        assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
            queuedTasks.single().run()
        }
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
        val terminalState = requireNotNull(inspectionRunState(projectKey(mockProject)))
        assertFalse(terminalState.inProgress)
        assertEquals(InspectionRunTerminalOutcome.CANCELLED, terminalState.terminalOutcome)
    }

    @Test
    fun `test indicator creation failure releases inspection run`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        handler.inspectionIndicatorFactory = { throw IllegalStateException("indicator failed") }

        val response = processTriggerRequest("/api/inspection/trigger")
        val state = inspectionRunState(projectKey(mockProject))

        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status())
        assertEquals(false, state?.inProgress)
        assertEquals(InspectionRunTerminalOutcome.FAILED, state?.terminalOutcome)
    }

    @Test
    fun `test runner setup failure releases inspection run`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        handler.inspectionProcessRunner = { _, _ -> throw IllegalStateException("runner failed") }

        val response = processTriggerRequest("/api/inspection/trigger")
        val state = inspectionRunState(projectKey(mockProject))

        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status())
        assertEquals(false, state?.inProgress)
        assertEquals(InspectionRunTerminalOutcome.FAILED, state?.terminalOutcome)
    }

    @Test
    fun `test native inspection setup failure records failed terminal outcome`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)
        val inputFingerprint = projectInputsFingerprint(profileName = "TestProfile")
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        handler.projectContentTrackerFactory = { _, _ -> FakeInspectionProjectContentTracker() }
        every { InspectionManager.getInstance(mockProject) } throws IllegalStateException("native setup failed")

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")
        val state = inspectionRunState(projectKey(mockProject))

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(false, state?.inProgress)
        assertEquals(InspectionRunTerminalOutcome.FAILED, state?.terminalOutcome)
        assertEquals(InspectionRunStage.NATIVE_CONFIGURE, state?.stage)
    }

    @Test
    fun `test a failed run withdraws the clean verdict of the earlier run`() {
        seedCleanSnapshotFromEarlierRun()
        val verdictBeforeTheFailedRun = inspectionVerdict("/api/inspection/status")
        handler.inspectionIndicatorFactory = { throw IllegalStateException("indicator failed") }

        val trigger = processTriggerRequest("/api/inspection/trigger")

        assertEquals("GREEN", verdictBeforeTheFailedRun)
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, trigger.status())
        assertEquals("UNKNOWN", inspectionVerdict("/api/inspection/status"))
        assertEquals("UNKNOWN", inspectionVerdict("/api/inspection/problems"))
    }

    @Test
    fun `test a run cancelled before it starts withdraws the clean verdict of the earlier run`() {
        seedCleanSnapshotFromEarlierRun()
        val queuedTasks = mutableListOf<Runnable>()
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            queuedTasks += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        processTriggerRequest("/api/inspection/trigger")
        processGetRequest("/api/inspection/cancel?worktree_path=/tmp/TestProject&inspection_run_id=1")
        assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
            queuedTasks.first().run()
        }
        runPooledTasksInline()

        assertEquals("UNKNOWN", inspectionVerdict("/api/inspection/status"))
        assertEquals("UNKNOWN", inspectionVerdict("/api/inspection/problems"))
    }

    @Test
    fun `test failure after cancellation request retains failed terminal outcome`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)
        val inputFingerprint = projectInputsFingerprint(profileName = "TestProfile")
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        handler.projectContentTrackerFactory = { _, _ -> FakeInspectionProjectContentTracker() }
        every { InspectionManager.getInstance(mockProject) } answers {
            processGetRequest("/api/inspection/cancel?worktree_path=/tmp/TestProject&inspection_run_id=1")
            throw IllegalStateException("native setup failed")
        }

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")
        val state = inspectionRunState(projectKey(mockProject))

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(false, state?.inProgress)
        assertEquals(InspectionRunTerminalOutcome.FAILED, state?.terminalOutcome)
        assertEquals(InspectionRunStage.NATIVE_CONFIGURE, state?.stage)
        assertEquals(InspectionRunFailureOutcome.CANCELLED, state?.failureDiagnostics?.single()?.outcome)
    }

    @Test
    fun `test capture failure diagnostic preserves exception details`() {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "captureFailureDiagnostic",
            Exception::class.java,
        )
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val diagnostic = method.invoke(handler, IllegalStateException("capture exploded")) as Map<String, Any>

        assertEquals("helper_plugin_error", diagnostic["exit_reason"])
        assertEquals(IllegalStateException::class.java.name, diagnostic["exception_type"])
        assertEquals("capture exploded", diagnostic["exception_message"])
    }

    @Test
    fun `test capture failure merge preserves nullable finding diagnostics`() {
        val findingDiagnostic = mapOf<String, Any?>("execution_proof_established" to true)
        val failureDiagnostic = mapOf<String, Any>(
            "source" to "capture_deadline",
            "outcome" to "timeout",
        )

        assertNull(mergeCaptureFailureDiagnostic(null, null))
        assertEquals(findingDiagnostic, mergeCaptureFailureDiagnostic(findingDiagnostic, null))
        assertEquals(
            findingDiagnostic + mapOf("inspection_failure_diagnostic" to failureDiagnostic),
            mergeCaptureFailureDiagnostic(findingDiagnostic, failureDiagnostic),
        )
    }

    @Test
    fun `test excluded dependency lockfile is classified as metadata`() {
        val lockfile = mapOf<String, Any?>(
            "path" to "/tmp/TestProject/uv.lock",
            "valid" to true,
            "directory" to false,
            "file_type" to "PLAIN_TEXT",
            "psi_language" to "TEXT",
            "psi_class" to "com.intellij.psi.impl.source.PsiPlainTextFile",
            "in_content" to false,
            "in_source" to false,
            "is_excluded" to true,
        )

        assertTrue(scopeFileIsExcludedDependencyLockfile(lockfile))
        assertEquals("excluded_dependency_lockfile", scopeFileCoverageRole(lockfile))
        assertTrue(scopeFileSemanticCoverageReasons(lockfile).isEmpty())

        val diagnostic = buildScopeFileDiagnosticPayload(
            scopeKind = "changed_files",
            resolutionStatus = "changed_files_resolved",
            directoryParam = null,
            requestedFileCount = 1,
            resolvedFileCount = 1,
            fileDiagnostics = listOf(lockfile),
        )
        @Suppress("UNCHECKED_CAST")
        val semanticCoverage = diagnostic["scope_file_semantic_coverage"] as Map<String, Any?>
        assertEquals(0, semanticCoverage["missing_file_count"])
        assertEquals(1, semanticCoverage["metadata_file_count"])
        @Suppress("UNCHECKED_CAST")
        val metadataFiles = semanticCoverage["metadata_files"] as List<Map<String, Any?>>
        assertEquals("excluded_dependency_lockfile", metadataFiles.single()["classification"])
        assertEquals(false, metadataFiles.single()["coverage_required"])
    }

    @Test
    fun `test dependency lockfile requires explicit IDE exclusion`() {
        val lockfile = mapOf<String, Any?>(
            "path" to "/tmp/TestProject/uv.lock",
            "valid" to true,
            "directory" to false,
            "file_type" to "PLAIN_TEXT",
            "psi_language" to "TEXT",
            "psi_class" to "com.intellij.psi.impl.source.PsiPlainTextFile",
            "in_content" to false,
            "in_source" to false,
            "is_excluded" to false,
        )

        assertFalse(scopeFileIsExcludedDependencyLockfile(lockfile))
        assertNull(scopeFileCoverageRole(lockfile))
        assertEquals(
            listOf("non_semantic_fallback", "outside_project_content"),
            scopeFileSemanticCoverageReasons(lockfile),
        )
    }

    @Test
    fun `test excluded lockfile does not hide source outside content`() {
        val lockfile = mapOf<String, Any?>(
            "path" to "/tmp/TestProject/uv.lock",
            "valid" to true,
            "directory" to false,
            "file_type" to "PLAIN_TEXT",
            "psi_language" to "TEXT",
            "psi_class" to "com.intellij.psi.impl.source.PsiPlainTextFile",
            "in_content" to false,
            "in_source" to false,
            "is_excluded" to true,
        )
        val source = mapOf<String, Any?>(
            "path" to "/tmp/TestProject/src/app.py",
            "valid" to true,
            "directory" to false,
            "file_type" to "Python",
            "psi_language" to "Python",
            "psi_class" to "com.jetbrains.python.psi.impl.PyFileImpl",
            "in_content" to false,
            "in_source" to false,
            "is_excluded" to false,
        )

        val diagnostic = buildScopeFileDiagnosticPayload(
            scopeKind = "changed_files",
            resolutionStatus = "changed_files_resolved",
            directoryParam = null,
            requestedFileCount = 2,
            resolvedFileCount = 2,
            fileDiagnostics = listOf(lockfile, source),
        )
        @Suppress("UNCHECKED_CAST")
        val semanticCoverage = diagnostic["scope_file_semantic_coverage"] as Map<String, Any?>
        assertEquals(1, semanticCoverage["missing_file_count"])
        assertEquals(1, semanticCoverage["metadata_file_count"])
        @Suppress("UNCHECKED_CAST")
        val reasonCounts = semanticCoverage["reason_counts"] as Map<String, Int>
        assertEquals(1, reasonCounts["outside_project_content"])
    }

    @Test
    fun `test aggregate semantic proof preserves gaps beyond emitted diagnostics`() {
        val semanticFiles = (1..25).map { index ->
            mapOf<String, Any?>(
                "path" to "/tmp/TestProject/src/File$index.py",
                "valid" to true,
                "directory" to false,
                "file_type" to "Python",
                "psi_language" to "Python",
                "psi_class" to "com.jetbrains.python.psi.impl.PyFileImpl",
                "in_content" to true,
            )
        }
        val textOnlyFile = mapOf<String, Any?>(
            "path" to "/tmp/TestProject/src/View.swift",
            "valid" to true,
            "directory" to false,
            "file_type" to "TextMate",
            "psi_language" to "textmate",
            "psi_class" to "org.jetbrains.plugins.textmate.psi.TextMateFile",
            "in_content" to true,
        )

        val diagnostic = buildScopeFileDiagnosticPayload(
            scopeKind = "changed_files",
            resolutionStatus = "changed_files_resolved",
            directoryParam = null,
            requestedFileCount = 0,
            resolvedFileCount = 26,
            fileDiagnostics = semanticFiles + textOnlyFile,
        )

        @Suppress("UNCHECKED_CAST")
        val semanticCoverage = diagnostic["scope_file_semantic_coverage"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val reasonCounts = semanticCoverage["reason_counts"] as Map<String, Int>
        @Suppress("UNCHECKED_CAST")
        val missingFiles = semanticCoverage["missing_files"] as List<Map<String, Any?>>
        assertEquals(true, diagnostic["scope_file_semantic_evidence_complete"])
        assertEquals(1, semanticCoverage["missing_file_count"])
        assertEquals(1, reasonCounts["non_semantic_fallback"])
        assertEquals("/tmp/TestProject/src/View.swift", missingFiles.single()["path"])
    }

    @Test
    fun `test finding capture diagnostic retains scope proof for filtered verdicts`() {
        val scopeDiagnostic = mapOf<String, Any?>(
            "scope_file_semantic_evidence_complete" to false,
            "scope_file_semantic_coverage" to mapOf(
                "unproven_file_count" to 1,
                "missing_file_count" to 1,
            ),
        )
        val diagnostic = buildFindingCaptureDiagnostic(
            scopeDiagnostics = scopeDiagnostic,
            stateDiagnostic = mapOf("project_state_changed_during_capture" to true),
            proofDiagnostic = mapOf("execution_proof_established" to false),
            projectStateChangedDuringCapture = false,
        )

        assertEquals(false, diagnostic?.get("scope_file_semantic_evidence_complete"))
        assertEquals(scopeDiagnostic["scope_file_semantic_coverage"], diagnostic?.get("scope_file_semantic_coverage"))
        assertEquals(false, diagnostic?.get("execution_proof_established"))
        assertFalse(diagnostic.orEmpty().containsKey("project_state_changed_during_capture"))
    }

    @Test
    fun `test inspection refreshes only the selected project root`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        mockInspectionPrerequisites(mockProject)
        val refreshedProjectRoots = mutableListOf<String>()
        handler.refreshProjectRoot = { path -> refreshedProjectRoots += path }
        val method = InspectionHandler::class.java.getDeclaredMethod("syncProjectState", Project::class.java)
        method.isAccessible = true

        method.invoke(handler, mockProject)

        assertEquals(listOf("/tmp/TestProject"), refreshedProjectRoots)
    }

    @Test
    fun `normalizeOptionalFilter handles all and blanks`() {
        assertNull(normalizeOptionalFilter(null))
        assertNull(normalizeOptionalFilter(""))
        assertNull(normalizeOptionalFilter("   "))
        assertNull(normalizeOptionalFilter("all"))
        assertNull(normalizeOptionalFilter("ALL"))
        assertEquals("src/", normalizeOptionalFilter(" src/ "))
    }

    @Test
    fun `test buildMissingProjectResponse includes recent project suggestions`() {
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "buildMissingProjectResponse",
            String::class.java,
        )
        method.isAccessible = true

        val recentProjectManager = mockk<com.intellij.ide.RecentProjectsManagerBase>()
        val recentProjectPath = Files.createTempDirectory("inspection-recent-project").toAbsolutePath().toString()

        every { recentProjectManager.getRecentPaths() } returns listOf(recentProjectPath)
        every { recentProjectManager.getProjectName(recentProjectPath) } returns "Odoo API"
        every { recentProjectManager.getDisplayName(recentProjectPath) } returns "Odoo API"

        val originalProvider = recentProjectsManagerProvider
        recentProjectsManagerProvider = { recentProjectManager }

        val response = try {
            method.invoke(handler, "odoo api") as Map<*, *>
        } finally {
            recentProjectsManagerProvider = originalProvider
        }

        assertEquals("Requested project 'odoo api' is not open in the IDE.", response["error"])
        assertEquals("no_project", response["status"])
        assertEquals("UNKNOWN", response["inspection_verdict"])
        assertEquals("no_project", response["inspection_verdict_reason"])
        val recentSuggestions = response["suggested_recent_projects"] as List<*>
        assertEquals(1, recentSuggestions.size)
        val suggestion = recentSuggestions.first() as Map<*, *>
        assertEquals("Odoo API", suggestion["name"])
        assertEquals(recentProjectPath, suggestion["path"])
    }

    @Test
    fun `test process trigger falls back for blank project query`() {
        val response = processTriggerRequest("/api/inspection/trigger?project=")

        assertEquals(HttpResponseStatus.OK, response.status())
        assertFalse(response.content().toString(Charsets.UTF_8).contains("No project found"))
    }

    @Test
    fun `test process trigger falls back for whitespace project query`() {
        val response = processTriggerRequest("/api/inspection/trigger?project=%20%20")

        assertEquals(HttpResponseStatus.OK, response.status())
        assertFalse(response.content().toString(Charsets.UTF_8).contains("No project found"))
    }

    @Test
    fun `test process trigger schedules inspection on pooled thread`() {
        val response = processTriggerRequest("/api/inspection/trigger")

        assertEquals(HttpResponseStatus.OK, response.status())
        verify(exactly = 1) { mockApplication.executeOnPooledThread(any<Runnable>()) }
        verify(exactly = 0) { mockApplication.invokeLater(any()) }
    }

    @Test
    fun `test concurrent trigger returns structured conflict`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        setInspectionRunState(
            projectKey(mockProject),
            InspectionRunState(runId = 42L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )

        val response = processTriggerRequest("/api/inspection/trigger")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.CONFLICT, response.status())
        assertTrue(body.contains("\"error\": \"inspection_in_progress\""))
        assertTrue(body.contains("\"status\": \"inspection_in_progress\""))
        assertTrue(body.contains("\"inspection_run_id\": 42"))
        assertTrue(body.contains("\"project_key\": \"path:/tmp/TestProject\""))
        assertTrue(body.contains("\"session_id\""))
    }

    @Test
    fun `test trigger rejects unsupported scope before scheduling`() {
        val response = processTriggerRequest("/api/inspection/trigger?scope=workspace")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("\"parameter\": \"scope\""))
        verify(exactly = 0) { mockApplication.executeOnPooledThread(any<Runnable>()) }
    }

    @Test
    fun `test trigger rejects conflicting targeting parameters before scheduling`() {
        listOf(
            "/api/inspection/trigger?scope=whole_project&dir=src",
            "/api/inspection/trigger?dir=src&file=src/App.kt",
            "/api/inspection/trigger?scope=directory&dir=src&file=src/App.kt",
        ).forEach { uri ->
            val response = processTriggerRequest(uri)
            val body = response.content().toString(Charsets.UTF_8)

            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status(), uri)
            assertTrue(body.contains("\"parameter\": \"scope\""), body)
        }
        verify(exactly = 0) { mockApplication.executeOnPooledThread(any<Runnable>()) }
    }

    @Test
    fun `test trigger rejects missing files before scheduling`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        val localFileSystem = mockk<LocalFileSystem>()
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath("/tmp/TestProject/src/Missing.kt") } returns null

        val response = processTriggerRequest("/api/inspection/trigger?scope=files&file=src/Missing.kt")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("\"parameter\": \"files\""))
        verify(exactly = 0) { mockApplication.executeOnPooledThread(any<Runnable>()) }
    }

    @Test
    fun `test trigger rejects missing directory before scheduling`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        val localFileSystem = mockk<LocalFileSystem>()
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath("/tmp/TestProject/missing") } returns null

        val response = processTriggerRequest("/api/inspection/trigger?scope=directory&dir=missing")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("\"parameter\": \"dir\""))
        verify(exactly = 0) { mockApplication.executeOnPooledThread(any<Runnable>()) }
    }

    @Test
    fun `test targeted analysis scopes are built under read actions`() {
        val directory = mockk<VirtualFile>()
        val currentFile = mockk<VirtualFile>()
        val psiDirectory = mockk<PsiDirectory>()
        val psiFile = mockk<PsiFile>()
        val psiManager = mockk<PsiManager>()
        mockkStatic(PsiManager::class)
        every { PsiManager.getInstance(mockProject) } returns psiManager
        val insideReadAction = java.util.concurrent.atomic.AtomicBoolean(false)
        every { mockApplication.runReadAction(any<ThrowableComputable<Any, Exception>>()) } answers {
            insideReadAction.set(true)
            try {
                firstArg<ThrowableComputable<Any, Exception>>().compute()
            } finally {
                insideReadAction.set(false)
            }
        }
        every { psiManager.findDirectory(directory) } answers {
            assertTrue(insideReadAction.get())
            psiDirectory
        }
        every { psiManager.findFile(currentFile) } answers {
            assertTrue(insideReadAction.get())
            psiFile
        }
        every { psiDirectory.project } answers {
            assertTrue(insideReadAction.get())
            mockProject
        }
        every { psiFile.project } answers {
            assertTrue(insideReadAction.get())
            mockProject
        }

        assertNotNull(invokeTargetedAnalysisScopeResolver("analysisScopeForDirectory", directory))
        assertNotNull(invokeTargetedAnalysisScopeResolver("analysisScopeForFile", currentFile))

        verify(exactly = 2) { mockApplication.runReadAction(any<ThrowableComputable<Any, Exception>>()) }
    }

    @Test
    fun `test active editor scope resolution uses a read action`() {
        val currentFile = mockk<VirtualFile>()
        val fileEditorManager = mockk<FileEditorManager>()
        val projectFileIndex = mockk<com.intellij.openapi.roots.ProjectFileIndex>()
        val insideReadAction = java.util.concurrent.atomic.AtomicBoolean(false)
        every { mockApplication.runReadAction(any<ThrowableComputable<Any, Exception>>()) } answers {
            insideReadAction.set(true)
            try {
                firstArg<ThrowableComputable<Any, Exception>>().compute()
            } finally {
                insideReadAction.set(false)
            }
        }
        mockkStatic(FileEditorManager::class)
        every { FileEditorManager.getInstance(mockProject) } answers {
            assertTrue(insideReadAction.get())
            fileEditorManager
        }
        every { fileEditorManager.selectedFiles } answers {
            assertTrue(insideReadAction.get())
            arrayOf(currentFile)
        }
        mockkStatic(com.intellij.openapi.roots.ProjectFileIndex::class)
        every { com.intellij.openapi.roots.ProjectFileIndex.getInstance(mockProject) } answers {
            assertTrue(insideReadAction.get())
            projectFileIndex
        }
        every { currentFile.isValid } returns true
        every { currentFile.isInLocalFileSystem } returns true
        every { projectFileIndex.isInContent(currentFile) } answers {
            assertTrue(insideReadAction.get())
            true
        }

        assertSame(currentFile, invokeActiveEditorFileResolver())

        verify(exactly = 1) { mockApplication.runReadAction(any<ThrowableComputable<Any, Exception>>()) }
    }

    @Test
    fun `test directory scope supplies files to inspection engine fallback`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        val directory = mockk<VirtualFile>()
        val pythonFile = mockk<VirtualFile>()
        val nonSourcePythonFile = mockk<VirtualFile>()
        val javascriptFile = mockk<VirtualFile>()
        val psiFile = mockk<PsiFile>()
        val localFileSystem = mockk<LocalFileSystem>()
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath("/tmp/TestProject/src") } returns directory
        every { directory.isDirectory } returns true
        every { pythonFile.isDirectory } returns false
        every { pythonFile.extension } returns "py"
        every { nonSourcePythonFile.isDirectory } returns false
        every { nonSourcePythonFile.extension } returns "py"
        every { javascriptFile.isDirectory } returns false
        every { javascriptFile.extension } returns "js"

        val projectFileIndex = mockk<com.intellij.openapi.roots.ProjectFileIndex>()
        mockkStatic(com.intellij.openapi.roots.ProjectFileIndex::class)
        every { com.intellij.openapi.roots.ProjectFileIndex.getInstance(mockProject) } returns projectFileIndex
        every { projectFileIndex.isInSourceContent(pythonFile) } returns true
        every { projectFileIndex.isInSourceContent(nonSourcePythonFile) } returns false
        every { projectFileIndex.isInSourceContent(javascriptFile) } returns true

        val psiManager = mockk<PsiManager>()
        mockkStatic(PsiManager::class)
        every { PsiManager.getInstance(mockProject) } returns psiManager
        every { psiManager.findFile(pythonFile) } returns psiFile

        mockkStatic(VfsUtilCore::class)
        every {
            VfsUtilCore.visitChildrenRecursively(directory, any<VirtualFileVisitor<Any>>())
        } answers {
            val visitor = secondArg<VirtualFileVisitor<Any>>()
            visitor.visitFile(directory)
            visitor.visitFile(pythonFile)
            visitor.visitFile(nonSourcePythonFile)
            visitor.visitFile(javascriptFile)
            VirtualFileVisitor.CONTINUE
        }

        assertEquals(listOf(psiFile), invokeDirectoryPsiFilesForInspectionEngine())
        verify(exactly = 1) { localFileSystem.findFileByPath("/tmp/TestProject/src") }
        verify(exactly = 0) { localFileSystem.findFileByPath("/tmp/TestProject") }
        verify(exactly = 1) {
            VfsUtilCore.visitChildrenRecursively(directory, any<VirtualFileVisitor<Any>>())
        }
        verify(exactly = 0) { psiManager.findFile(nonSourcePythonFile) }
        verify(exactly = 0) { psiManager.findFile(javascriptFile) }
    }

    @Test
    fun `test directory scope fallback fails closed when root disappears`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        val localFileSystem = mockk<LocalFileSystem>()
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath("/tmp/TestProject/src") } returns null

        mockkStatic(VfsUtilCore::class)

        assertTrue(invokeDirectoryPsiFilesForInspectionEngine().isEmpty())
        verify(exactly = 1) { localFileSystem.findFileByPath("/tmp/TestProject/src") }
        verify(exactly = 0) { localFileSystem.findFileByPath("/tmp/TestProject") }
        verify(exactly = 0) {
            VfsUtilCore.visitChildrenRecursively(any<VirtualFile>(), any<VirtualFileVisitor<Any>>())
        }
    }

    @Test
    fun `test staged changed files rejects unavailable Git classification`() {
        every { mockProject.basePath } returns "/tmp/NotAGitWorktree"
        runPooledTasksInline()
        val changeListManager = mockk<ChangeListManager>()
        mockkStatic(ChangeListManager::class)
        every { ChangeListManager.getInstance(mockProject) } returns changeListManager
        every { changeListManager.allChanges } returns emptyList()

        val response = processGetRequest(
            "/api/inspection/problems?scope=changed_files&changed_files_mode=staged&include_unversioned=false"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("\"parameter\": \"changed_files_mode\""), body)
        assertFalse(body.contains("\"status\": \"results_available\""))
    }

    @Test
    fun `test attribution hashes non uuid caller correlation`() {
        val response = processGetRequest(
            "/api/inspection/route?session_id=old-session&client_run_id=sensitive-value-not-an-id"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.CONFLICT, response.status())
        assertTrue(body.contains("\"client_run_id\": \"sha256:"), body)
        assertFalse(body.contains("sensitive-value-not-an-id"), body)
    }

    @Test
    fun `test extractProjectQueryParameter prefers stable selectors over project`() {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "extractProjectQueryParameter",
            QueryStringDecoder::class.java,
            FullHttpRequest::class.java,
        )
        method.isAccessible = true

        val urlDecoder = QueryStringDecoder("/api/inspection/route?project=legacy-name&project_key=path:%2Ftmp%2Fproject")
        val request = mockk<FullHttpRequest>()
        every { request.uri() } returns "/api/inspection/route?project=legacy-name&project_key=path:%2Ftmp%2Fproject"

        val result = method.invoke(handler, urlDecoder, request) as String?

        assertEquals("path:/tmp/project", result)
    }

    @Test
    fun `test project path selectors match nested directories but not siblings`() {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "projectMatches",
            Project::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true

        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"

        val nestedSelector = method.invoke(handler, mockProject, "ignored", "/repo/app/src/module") as Boolean
        val siblingSelector = method.invoke(handler, mockProject, "ignored", "/repo/application/src") as Boolean

        assertTrue(nestedSelector)
        assertFalse(siblingSelector)
    }

    @Test
    fun `test trigger endpoint honors project instance id over duplicate path keys`() {
        val mainProject = mockProject(
            name = "Main",
            basePath = "/repo/main",
            projectFilePath = "/repo/main/.idea/misc.xml",
        )
        val worktreeProject = mockProject(
            name = "Worktree",
            basePath = "/repo/worktree",
            projectFilePath = "/repo/worktree/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(mainProject, worktreeProject)
        every { mockApplication.isDispatchThread } returns true
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(worktreeProject)

        val response = processTriggerRequest(
            "/api/inspection/trigger?project_instance_id=${projectInstanceId(worktreeProject)}&scope=changed_files&include_unversioned=false"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_key\": \"path:/repo/worktree\""))
        assertFalse(body.contains("\"project_key\": \"path:/repo/main\""))
    }

    @Test
    fun `test trigger endpoint reports ambiguous project names as bad request`() {
        val firstProject = mockProject(
            name = "Shared",
            basePath = "/repo/one",
            projectFilePath = "/repo/one/.idea/misc.xml",
        )
        val secondProject = mockProject(
            name = "Shared",
            basePath = "/repo/two",
            projectFilePath = "/repo/two/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(firstProject, secondProject)

        val response = processTriggerRequest("/api/inspection/trigger?project=Shared")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("Multiple open projects matched this request"))
        assertFalse(body.contains("Requested project 'Shared' is not open in the IDE"))
    }

    @Test
    fun `test trigger endpoint cwd prefers nested project file root over containing parent`() {
        val tempDir = Files.createTempDirectory("inspection-trigger-file-root-nested")
        val nestedPath = tempDir.resolve("packages/app")
        val parentProject = mockProject(
            name = "Parent",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        val childProject = mockProject(
            name = "Child",
            basePath = null,
            projectFilePath = nestedPath.resolve(".idea/misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns arrayOf(parentProject, childProject)
        every { mockApplication.executeOnPooledThread(any()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        every { mockApplication.isDispatchThread } returns true
        mockInspectionPrerequisites(childProject)

        val response = processTriggerRequest(
            "/api/inspection/trigger?cwd=${java.net.URLEncoder.encode(nestedPath.resolve("src/main").toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"Child\""))
        assertTrue(body.contains("\"base_path\": \"$nestedPath\""))
        assertFalse(body.contains("Multiple open projects matched this request"))
    }

    @Test
    fun `test direct status endpoint requires exact worktree path`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"

        val response = processGetRequest("/api/inspection/status?worktree_path=/repo/app/src")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.NOT_FOUND, response.status())
        assertTrue(body.contains("\"status\": \"no_project\""))
        assertTrue(body.contains("Requested project '/repo/app/src' is not open"))
    }

    @Test
    fun `test clearPriorInspectionResults removes all stale inspection tabs`() {
        val toolWindowManager = mockk<ToolWindowManager>()
        val toolWindow = mockk<ToolWindow>()
        val contentManager = mockk<ContentManager>()
        val nestedContent = mockk<Content>()
        val directContent = mockk<Content>()
        val otherContent = mockk<Content>()
        val nestedInspectionView = mockk<InspectionResultsView>(relaxed = true)
        val directInspectionView = mockk<InspectionResultsView>(relaxed = true)
        val nestedPanel = JPanel()
        nestedPanel.add(nestedInspectionView)

        every { mockApplication.isDispatchThread } returns true
        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(mockProject) } returns toolWindowManager
        every { toolWindowManager.getToolWindow("Inspection Results") } returns toolWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns null
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns null
        every { toolWindow.contentManager } returns contentManager
        every { contentManager.contentCount } returns 3
        every { contentManager.getContent(2) } returns otherContent
        every { contentManager.getContent(1) } returns directContent
        every { contentManager.getContent(0) } returns nestedContent
        every { otherContent.component } returns JPanel()
        every { directContent.component } returns directInspectionView
        every { nestedContent.component } returns nestedPanel
        every { contentManager.removeContent(any(), true) } returns true

        val method = InspectionHandler::class.java.getDeclaredMethod("clearPriorInspectionResults", Project::class.java)
        method.isAccessible = true
        method.invoke(handler, mockProject)

        verify(exactly = 1) { contentManager.removeContent(directContent, true) }
        verify(exactly = 1) { contentManager.removeContent(nestedContent, true) }
        verify(exactly = 0) { contentManager.removeContent(otherContent, true) }
    }

    @Test
    fun `test process trigger reports invalid explicit project`() {
        val response = processTriggerRequest("/api/inspection/trigger?project=does-not-exist")

        assertEquals(HttpResponseStatus.NOT_FOUND, response.status())
        assertTrue(response.content().toString(Charsets.UTF_8).contains("Requested project 'does-not-exist' is not open in the IDE."))
    }
}
