package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import io.mockk.*
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import org.jdom.Element
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JFrame

internal abstract class InspectionHandlerTestSupport {
    protected lateinit var handler: InspectionHandler

    protected lateinit var mockProject: Project

    protected lateinit var mockProjectManager: ProjectManager

    protected lateinit var mockVirtualFileManager: VirtualFileManager

    protected lateinit var mockWindowManager: WindowManager

    protected lateinit var mockInspectionManager: InspectionManager

    protected lateinit var mockGlobalContext: GlobalInspectionContext

    protected lateinit var mockProfileManager: InspectionProjectProfileManager

    protected lateinit var mockProfile: InspectionProfileImpl

    protected lateinit var mockApplication: Application

    @AfterEach
    fun releaseStaticMocks() {
        unmockkAll()
    }

    @BeforeEach
    fun setup() {
        handler = InspectionHandler()
        val skippedWaitMs = java.util.concurrent.atomic.AtomicLong()
        handler.waitPollSleep = { pollMs -> skippedWaitMs.addAndGet(pollMs) }
        handler.currentTimeMs = { System.currentTimeMillis() + skippedWaitMs.get() }
        handler.trustProjectPath = {}
        handler.refreshProjectRoot = {}
        handler.lifecycleContentRootReadinessProvider = { project, targetKey ->
            when {
                project.isDisposed -> InspectionHandler.LifecycleContentRootReadiness(
                    ready = false,
                    reason = "project_disposed",
                    targetKey = targetKey,
                )
                mockProjectManager.openProjects.none { openProject -> openProject === project } ->
                    InspectionHandler.LifecycleContentRootReadiness(
                        ready = false,
                        reason = "project_not_open",
                        targetKey = targetKey,
                    )
                project.basePath == null && project.projectFilePath == null ->
                    InspectionHandler.LifecycleContentRootReadiness(
                        ready = false,
                        reason = "route_mismatch",
                        targetKey = targetKey,
                    )
                !project.isInitialized -> InspectionHandler.LifecycleContentRootReadiness(
                    ready = false,
                    reason = "project_not_initialized",
                    targetKey = targetKey,
                )
                else -> InspectionHandler.LifecycleContentRootReadiness(
                    ready = true,
                    reason = "ready",
                    targetKey = targetKey,
                    contentRootCount = 1,
                    sourceRootCount = 1,
                    moduleCount = 1,
                    targetInsideContent = true,
                    contentRoots = listOf(targetKey),
                )
            }
        }
        handler.inspectionRunExpirationMs = 300000L
        handler.inspectionProcessRunner = { task, _ -> task.run() }
        handler.inspectionIndicatorFactory = { mockk(relaxed = true) }
        handler.projectAnalysisReadinessProvider = { _, _ ->
            InspectionProjectAnalysisReadiness(
                required = false,
                ready = true,
                reason = "python_not_in_scope",
            )
        }
        handler.lifecycleCloseExecutor = { task -> task.run() }
        handler.lifecycleCloseUnsavedDocumentGuard = { _, _ -> null }
        enhancedTreeExtractorFactory = { EnhancedTreeExtractor() }
        
        mockProject = mockk<Project>()
        mockProjectManager = mockk<ProjectManager>()
        mockVirtualFileManager = mockk<VirtualFileManager>()
        mockWindowManager = mockk<WindowManager>()
        mockInspectionManager = mockk<InspectionManager>()
        mockGlobalContext = mockk<GlobalInspectionContext>()
        mockProfileManager = mockk<InspectionProjectProfileManager>()
        mockProfile = mockk<InspectionProfileImpl>()
        mockApplication = mockk<Application>()
        
        every { mockProject.isDefault } returns false
        every { mockProject.isDisposed } returns false
        every { mockProject.isInitialized } returns true
        every { mockProject.name } returns "TestProject"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject)
        
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.runReadAction(any<ThrowableComputable<Any, Exception>>()) } answers {
            firstArg<ThrowableComputable<Any, Exception>>().compute()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } returns mockk(relaxed = true)
        every { mockApplication.invokeLater(any()) } just Runs
        
        mockkStatic(IdeFocusManager::class)
        val mockIdeFocusManager = mockk<IdeFocusManager>()
        val mockIdeFrame = mockk<IdeFrame>()
        every { mockIdeFrame.project } returns mockProject
        every { mockIdeFocusManager.lastFocusedFrame } returns mockIdeFrame
        every { IdeFocusManager.getGlobalInstance() } returns mockIdeFocusManager
        
        mockkStatic(DataManager::class)
        val mockDataManager = mockk<DataManager>()
        val mockDataContext = mockk<DataContext>()
        val promise: Promise<DataContext> = resolvedPromise(mockDataContext)
        every { mockDataManager.dataContextFromFocusAsync } returns promise
        every { DataManager.getInstance() } returns mockDataManager
        every { CommonDataKeys.PROJECT.getData(mockDataContext) } returns mockProject
        
        mockkStatic(WindowManager::class)
        every { WindowManager.getInstance() } returns mockWindowManager
        
        val mockWindow = mockk<JFrame>()
        every { mockWindow.isActive } returns true
        every { mockWindowManager.suggestParentWindow(mockProject) } returns mockWindow
        
        mockkStatic(VirtualFileManager::class)
        every { VirtualFileManager.getInstance() } returns mockVirtualFileManager
        
        every { InspectionManager.getInstance(mockProject) } returns mockInspectionManager
        every { mockInspectionManager.createNewGlobalContext() } returns mockGlobalContext
        
        every { InspectionProjectProfileManager.getInstance(mockProject) } returns mockProfileManager
        every { mockProfileManager.currentProfile } returns mockProfile
        every { mockProfile.name } returns "TestProfile"
    }

    protected fun usableProject(name: String): Project {
        val project = mockk<Project>()
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.isInitialized } returns true
        every { project.name } returns name
        every { project.basePath } returns "/tmp/$name"
        every { project.projectFilePath } returns "/tmp/$name/.idea/misc.xml"
        return project
    }

    protected fun currentProjectWithoutSelector(): Project? {
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true
        return method.invoke(handler, null) as Project?
    }

    protected fun seedCleanSnapshotFromEarlierRun() {
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
                runId = 0L,
            ),
        )
    }

    protected fun inspectionVerdict(uri: String): String? {
        val body = processGetRequest(uri).content().toString(Charsets.UTF_8)
        return Regex("\"inspection_verdict\": \"([^\"]*)\"").find(body)?.groupValues?.get(1)
    }

    protected fun processTriggerRequest(uri: String): FullHttpResponse {
        return processGetRequest(uri)
    }

    protected fun lifecycleOpenUri(
        path: Path,
        leaseId: String = "test-open-lease",
        probe: Boolean = false,
    ): String {
        return lifecycleOpenUri(path.toString(), leaseId, probe)
    }

    protected fun lifecycleOpenUri(
        path: String,
        leaseId: String = "test-open-lease",
        probe: Boolean = false,
    ): String {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val encodedSession = java.net.URLEncoder.encode(InspectionIdeSession.sessionId, "UTF-8")
        val encodedLeaseId = java.net.URLEncoder.encode(leaseId, "UTF-8")
        return "/api/inspection/lifecycle/open?worktree_path=$encodedPath&session_id=$encodedSession&lease_id=$encodedLeaseId&probe=$probe"
    }

    protected fun runPooledTasksInline() {
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected fun lifecycleLeases(): MutableMap<String, InspectionProjectLease> {
        val field = InspectionHandler::class.java.getDeclaredField("leasesByProjectInstance")
        field.isAccessible = true
        return field.get(handler) as MutableMap<String, InspectionProjectLease>
    }

    @Suppress("UNCHECKED_CAST")
    protected fun lifecycleOpenOwnership(): MutableMap<String, InspectionHandler.LifecycleOpenOwnership> {
        val field = InspectionHandler::class.java.getDeclaredField("lifecycleOpenOwnershipByProjectInstance")
        field.isAccessible = true
        return field.get(handler) as MutableMap<String, InspectionHandler.LifecycleOpenOwnership>
    }

    @Suppress("UNCHECKED_CAST")
    protected fun registerLifecycleOpenOwnership(project: Project, leaseId: String = "test-lease") {
        val field = InspectionHandler::class.java.getDeclaredField("lifecycleOpenOwnershipByProjectInstance")
        field.isAccessible = true
        val ownership = field.get(handler) as MutableMap<String, InspectionHandler.LifecycleOpenOwnership>
        val targetKey = Paths.get(requireNotNull(project.basePath)).normalize().toAbsolutePath().toString()
        ownership[projectInstanceId(project)] = InspectionHandler.LifecycleOpenOwnership(leaseId, targetKey, project)
    }

    protected fun semanticCoverageGapDiagnostic(): Map<String, Any?> = mapOf(

        "scope_file_resolved_count" to 26,
        "scope_file_diagnostic_count" to 25,
        "scope_file_diagnostics_truncated" to true,
        "scope_file_diagnostics_complete" to false,
        "scope_file_semantic_evidence_complete" to true,
        "scope_file_semantic_coverage" to mapOf(
            "schema_version" to 1,
            "evaluated_file_count" to 26,
            "unproven_file_count" to 0,
            "missing_file_count" to 1,
            "reason_counts" to mapOf("non_semantic_fallback" to 1),
            "missing_files" to listOf(
                mapOf(
                    "path" to "/tmp/TestProject/src/View.swift",
                    "valid" to true,
                    "directory" to false,
                    "file_type" to "TextMate",
                    "psi_language" to "textmate",
                    "psi_class" to "org.jetbrains.plugins.textmate.psi.TextMateFile",
                    "in_content" to true,
                    "reasons" to listOf("non_semantic_fallback"),
                ),
            ),
            "metadata_file_count" to 0,
            "metadata_files" to emptyList<Map<String, Any?>>(),
        ),
        "scope_file_diagnostics" to emptyList<Map<String, Any?>>(),
    )

    protected fun semanticCoverageTruncatedDiagnostic(): Map<String, Any?> = mapOf(

        "scope_file_resolved_count" to 1,
        "scope_file_diagnostic_count" to 0,
        "scope_file_diagnostics_truncated" to true,
        "scope_file_diagnostics_complete" to false,
        "scope_file_semantic_evidence_complete" to false,
        "scope_file_diagnostics" to emptyList<Map<String, Any?>>(),
    )

    protected fun processGetRequest(uri: String): FullHttpResponse = processRequest(uri, HttpMethod.GET)

    protected fun processRequest(
        uri: String,
        method: HttpMethod,
        content: io.netty.buffer.ByteBuf = Unpooled.EMPTY_BUFFER,
    ): FullHttpResponse {
        val responses = processRequestResponses(uri, method, content)
        assertEquals(1, responses.size)
        return responses.single()
    }

    protected fun processRequestResponses(
        uri: String,
        method: HttpMethod,
        content: io.netty.buffer.ByteBuf = Unpooled.EMPTY_BUFFER,
    ): MutableList<FullHttpResponse> {
        val urlDecoder = QueryStringDecoder(uri)
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        val responses = mutableListOf<FullHttpResponse>()

        every { mockRequest.uri() } returns uri
        every { mockRequest.method() } returns method
        every { mockRequest.content() } returns content
        every { mockContext.writeAndFlush(any()) } answers {
            responses += firstArg<FullHttpResponse>()
            mockk(relaxed = true)
        }

        val result = handler.process(urlDecoder, mockRequest, mockContext)

        assertTrue(result)
        return responses
    }

    protected fun pythonSdkPreparationUri(projectInstanceId: String, closeToken: String): String =
        "/api/inspection/lifecycle/prepare-python-sdk" +
            "?project_instance_id=$projectInstanceId" +
            "&project_key=path:/repo/app" +
            "&session_id=${InspectionIdeSession.sessionId}" +
            "&lease_id=test-lease" +
            "&close_token=$closeToken"

    protected fun buildInspectionStatus(): MutableMap<String, Any> {
        val method = InspectionHandler::class.java.getDeclaredMethod("buildInspectionStatus", Project::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(handler, mockProject) as MutableMap<String, Any>
    }

    protected fun invokeTargetedAnalysisScopeResolver(methodName: String, virtualFile: VirtualFile): Any? {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            methodName,
            Project::class.java,
            VirtualFile::class.java,
        )
        method.isAccessible = true
        return method.invoke(handler, mockProject, virtualFile)
    }

    protected fun invokeActiveEditorFileResolver(): VirtualFile? {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "resolveActiveEditorFile",
            Project::class.java,
        )
        method.isAccessible = true
        return method.invoke(handler, mockProject) as? VirtualFile
    }

    @Suppress("UNCHECKED_CAST")
    protected fun invokeDirectoryPsiFilesForInspectionEngine(): List<PsiFile> {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "scopedPsiFilesForInspectionEngine",
            Project::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            String::class.java,
            List::class.java,
            List::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            handler,
            mockProject,
            "directory",
            "src",
            null,
            null,
            null,
            emptyList<Map<String, Any?>>(),
            "PY",
        ) as List<PsiFile>
    }

    protected fun publishInspectionSnapshot(
        snapshot: InspectionResultsSnapshot,
        captureEndState: InspectionProjectStateSnapshot,
        projectStateChangedDuringCapture: Boolean,
        inspectionInputFingerprint: InspectionProjectInputsFingerprint?,
        projectContentTracker: InspectionProjectContentTracker?,
    ) {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "publishInspectionSnapshot",
            Project::class.java,
            Long::class.javaPrimitiveType,
            InspectionResultsSnapshot::class.java,
            InspectionProjectStateSnapshot::class.java,
            Boolean::class.javaPrimitiveType,
            InspectionProjectInputsFingerprint::class.java,
            InspectionProjectContentTracker::class.java,
        )
        method.isAccessible = true
        method.invoke(
            handler,
            mockProject,
            1L,
            snapshot,
            captureEndState,
            projectStateChangedDuringCapture,
            inspectionInputFingerprint,
            projectContentTracker,
        )
    }

    protected fun qualifyProjectAnalysisSnapshot(
        snapshot: InspectionResultsSnapshot,
        readiness: InspectionProjectAnalysisReadiness,
        fingerprint: InspectionProjectInputsFingerprint,
    ): InspectionResultsSnapshot {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "qualifyProjectAnalysisSnapshot",
            Project::class.java,
            InspectionResultsSnapshot::class.java,
            InspectionProjectAnalysisReadiness::class.java,
            InspectionProjectInputsFingerprint::class.java,
        )
        method.isAccessible = true
        return method.invoke(handler, mockProject, snapshot, readiness, fingerprint) as InspectionResultsSnapshot
    }

    protected fun pythonSdkReadiness(
        ready: Boolean,
        localInterpreterCandidate: Boolean,
        reason: String = if (ready) "ready" else "python_sdk_missing",
        registeredLocalPythonSdkCount: Int = if (ready) 1 else 0,
        assignedLocalPythonSdkCount: Int = if (ready) 1 else 0,
        pythonSdkCount: Int = assignedLocalPythonSdkCount,
        missingSdkFileCount: Int = if (ready || reason == "python_sdk_updating") 0 else 1,
        assignedLocalPythonFileCount: Int = assignedLocalPythonSdkCount,
    ): InspectionProjectAnalysisReadiness {
        return InspectionProjectAnalysisReadiness(
            required = true,
            ready = ready,
            reason = reason,
            pythonFileCount = 1,
            pythonSdkCount = pythonSdkCount,
            missingSdkFileCount = missingSdkFileCount,
            localPythonInterpreterCandidate = localInterpreterCandidate,
            registeredLocalPythonSdkCount = registeredLocalPythonSdkCount,
            assignedLocalPythonSdkCount = assignedLocalPythonSdkCount,
            assignedLocalPythonFileCount = assignedLocalPythonFileCount,
            localPythonInterpreterPaths = if (localInterpreterCandidate) setOf("/tmp/TestProject/.venv/bin/python") else emptySet(),
        )
    }

    protected fun projectInputsFingerprint(
        profileName: String = "RedLane",
        rootPaths: List<String> = listOf("/tmp/TestProject"),
    ): InspectionProjectInputsFingerprint {
        return InspectionProjectInputsFingerprint(
            rootPaths = rootPaths,
            excludedRootPaths = listOf("/tmp/TestProject/build"),
            projectSdkName = "Test SDK",
            projectSdkTypeName = "Python SDK",
            projectSdkVersion = "3.13",
            projectSdkHomePath = "/tmp/python",
            moduleSdkStates = listOf("TestProject\u0000Test SDK\u0000Python SDK\u00003.13\u0000/tmp/python"),
            requestedProfileName = profileName,
            resolvedProfileName = profileName,
            profileToolStates = listOf("CurrentRunInspection|null|true|WARNING|null"),
            namedScopeDefinitions = emptyList(),
            profileConfigurationHash = "profile-configuration-hash",
        )
    }

    protected fun changedFilesSnapshot(
        problems: List<Map<String, Any>>,
        resolvedFiles: List<String> = listOf("/tmp/TestProject/src/Included.kt"),
        psiModificationCount: Long = 10L,
        source: String = "global_context",
        outcome: InspectionSnapshotOutcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
    ): InspectionResultsSnapshot {
        return InspectionResultsSnapshot(
            problems = problems,
            timestamp = System.currentTimeMillis(),
            projectState = InspectionProjectStateSnapshot(
                psiModificationCount = psiModificationCount,
                unsavedProjectDocuments = 0,
            ),
            outcome = outcome,
            source = source,
            captureScope = InspectionCaptureScope(
                scopeParam = "changed_files",
                resolvedFiles = resolvedFiles,
                includeUnversioned = false,
            ),
            runId = 1L,
        )
    }

    protected fun changedFileProblem(
        file: String = "/tmp/TestProject/src/Included.kt",
        description: String = "current run finding",
    ): Map<String, Any> {
        return mapOf(
            "file" to file,
            "line" to 4,
            "column" to 7,
            "severity" to "warning",
            "inspectionType" to "CurrentRunInspection",
            "description" to description,
        )
    }

    protected fun mockChangedFiles(paths: List<String>): ChangeListManager {
        val changeListManager = mockk<ChangeListManager>(relaxed = true)
        every { ChangeListManager.getInstance(mockProject) } returns changeListManager
        every { changeListManager.allChanges } returns paths.map { path ->
            val file = mockk<VirtualFile>()
            every { file.path } returns path
            val change = mockk<Change>()
            every { change.virtualFile } returns file
            change
        }
        return changeListManager
    }

    protected fun populateInspectionProfileElement(
        target: Element,
        scopeNames: List<String>,
        optionValue: String,
    ) {
        val tool = Element("inspection_tool")
            .setAttribute("class", "CurrentRunInspection")
            .addContent(Element("option").setAttribute("name", "mode").setAttribute("value", optionValue))
        scopeNames.forEach { scopeName ->
            tool.addContent(Element("scope").setAttribute("name", scopeName))
        }
        target.addContent(tool)
    }

    protected class FakeInspectionProjectContentTracker(
        var changed: Boolean = false,
    ) : InspectionProjectContentTracker {
        var closed: Boolean = false
        var beforeRunIfUnchanged: (() -> Unit)? = null
        var firstChange: String? = null

        override fun hasChanges(): Boolean = changed

        override fun firstChangeDescription(): String? = firstChange

        override fun runIfUnchanged(action: () -> Unit): Boolean {
            beforeRunIfUnchanged?.invoke()
            if (changed) {
                return false
            }
            action()
            return true
        }

        override fun close() {
            closed = true
        }
    }

    protected fun mockExtractor(problems: List<Map<String, Any>>) {
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(mockProject) } returns ProblemExtractionResult(
            problems = problems,
            succeeded = true,
            source = ProblemExtractionSource.INSPECTION_RESULTS,
        )
        enhancedTreeExtractorFactory = { extractor }
    }

    protected fun mockExtractorFailure() {
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(mockProject) } returns ProblemExtractionResult(
            problems = emptyList(),
            succeeded = false,
        )
        enhancedTreeExtractorFactory = { extractor }
    }

    protected fun mockIncludedLocalFile(): VirtualFile {
        val path = "/tmp/TestProject/src/Included.kt"
        val file = mockk<VirtualFile>()
        val localFileSystem = mockk<LocalFileSystem>()
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath(path) } returns file
        every { file.path } returns path
        every { file.isValid } returns true
        every { file.isDirectory } returns false
        every { file.isInLocalFileSystem } returns true
        return file
    }

    protected fun getFileInspectionProblems(files: List<String>): String {
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
        return method.invoke(
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
            files,
            true,
            null,
            null,
        ) as String
    }

    protected fun setInspectionRunState(projectKey: String, state: InspectionRunState) {
        val field = InspectionHandler::class.java.getDeclaredField("inspectionRunStatesByProject")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val states = field.get(handler) as MutableMap<String, InspectionRunState>
        states[projectKey] = state
    }

    protected fun inspectionRunState(projectKey: String): InspectionRunState? {
        val field = InspectionHandler::class.java.getDeclaredField("inspectionRunStatesByProject")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val states = field.get(handler) as Map<String, InspectionRunState>
        return states[projectKey]
    }

    protected fun transitionInspectionRunStage(projectKey: String, runId: Long, stage: InspectionRunStage) {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "transitionInspectionRunStage",
            String::class.java,
            Long::class.javaPrimitiveType,
            InspectionRunStage::class.java,
        )
        method.isAccessible = true
        method.invoke(handler, projectKey, runId, stage)
    }

    protected fun finishInspectionRun(projectKey: String, runId: Long) {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "finishInspectionRun",
            String::class.java,
            Long::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(handler, projectKey, runId)
    }

    protected fun recordInspectionRunFailureDiagnostic(
        projectKey: String,
        runId: Long,
        source: InspectionRunFailureSource,
    ): InspectionRunFailureDiagnostic? {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "recordInspectionRunFailureDiagnostic",
            String::class.java,
            Long::class.javaPrimitiveType,
            Project::class.java,
            InspectionRunFailureSource::class.java,
        )
        method.isAccessible = true
        return method.invoke(handler, projectKey, runId, mockProject, source) as InspectionRunFailureDiagnostic?
    }

    protected fun setInspectionRunControl(projectKey: String, control: InspectionRunControl) {
        val field = InspectionHandler::class.java.getDeclaredField("inspectionRunControlsByProject")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val controls = field.get(handler) as MutableMap<String, InspectionRunControl>
        controls[projectKey] = control
    }

    protected fun mockProject(name: String, basePath: String?, projectFilePath: String): Project {
        val project = mockk<Project>()
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.isInitialized } returns true
        every { project.name } returns name
        every { project.basePath } returns basePath
        every { project.projectFilePath } returns projectFilePath
        return project
    }

    protected fun mockInspectionPrerequisites(project: Project) {
        val fileDocumentManager = mockk<FileDocumentManager>(relaxed = true)
        mockkStatic(FileDocumentManager::class)
        every { FileDocumentManager.getInstance() } returns fileDocumentManager
        every { fileDocumentManager.unsavedDocuments } returns emptyArray()

        val psiDocumentManager = mockk<PsiDocumentManager>(relaxed = true)
        mockkStatic(PsiDocumentManager::class)
        every { PsiDocumentManager.getInstance(project) } returns psiDocumentManager

        mockkStatic(ChangeListManager::class)
        val changeListManager = mockk<ChangeListManager>(relaxed = true)
        every { ChangeListManager.getInstance(project) } returns changeListManager
        every { changeListManager.allChanges } returns emptyList()

        mockkStatic(PsiModificationTracker::class)
        val modificationTracker = mockk<PsiModificationTracker>()
        every { PsiModificationTracker.getInstance(project) } returns modificationTracker
        every { modificationTracker.modificationCount } returns 11L

        mockkStatic(ToolWindowManager::class)
        val toolWindowManager = mockk<ToolWindowManager>()
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.getToolWindow(any()) } returns null

        mockkStatic(DumbService::class)
        val dumbService = mockk<DumbService>()
        every { DumbService.getInstance(project) } returns dumbService
        every { dumbService.isDumb } returns false
        every { dumbService.modificationTracker.modificationCount } returns 0L
    }
}
