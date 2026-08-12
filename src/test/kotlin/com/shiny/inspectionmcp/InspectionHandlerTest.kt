package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import io.mockk.*
import com.intellij.lang.Language
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
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
import org.jdom.Element
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise
import org.jetbrains.concurrency.rejectedPromise
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JPanel

class InspectionHandlerTest {
    
    private lateinit var handler: InspectionHandler
    private lateinit var mockProject: Project
    private lateinit var mockProjectManager: ProjectManager
    private lateinit var mockVirtualFileManager: VirtualFileManager
    private lateinit var mockWindowManager: WindowManager
    private lateinit var mockInspectionManager: InspectionManager
    private lateinit var mockGlobalContext: GlobalInspectionContext
    private lateinit var mockProfileManager: InspectionProjectProfileManager
    private lateinit var mockProfile: InspectionProfileImpl
    private lateinit var mockApplication: Application

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
    
    @BeforeEach
    fun setup() {
        handler = InspectionHandler()
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

    @Test
    fun `test changed files trigger with no targets publishes clean snapshot without IDE inspection`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockVirtualFileManager.syncRefresh() } returns 0L
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
    }

    @Test
    fun `test changed file disappearing before preflight fails closed with scope evidence`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockVirtualFileManager.syncRefresh() } returns 0L
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
    fun `test python scope without sdk publishes capture incomplete before inspection`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockVirtualFileManager.syncRefresh() } returns 0L
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
                reason = "python_sdk_missing",
                pythonFileCount = 2,
                missingSdkFileCount = 2,
            )
        }

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")
        val body = response.content().toString(Charsets.UTF_8)
        val status = buildInspectionStatus()
        val statusResponse = processGetRequest("/api/inspection/status")
        val statusBody = statusResponse.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"scope\": \"whole_project\""), body)
        assertEquals("capture_incomplete", status["snapshot_outcome"])
        assertEquals(true, status["capture_incomplete"])
        assertEquals("language_sdk_missing", status["capture_incomplete_reason"])
        assertEquals("project_analysis_readiness", status["results_source"])
        assertEquals("UNKNOWN", status["inspection_verdict"])
        assertEquals(0, status["total_problems"])
        assertTrue(statusBody.contains("\"classification\": \"configuration_blocked\""), statusBody)
        assertTrue(statusBody.contains("\"code\": \"language_sdk_missing\""), statusBody)
        @Suppress("UNCHECKED_CAST")
        val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
        assertEquals("inspection_preflight", diagnostic["readiness_stage"])
        assertEquals("whole_project", diagnostic["requested_scope"])
        assertEquals("whole_project", diagnostic["resolved_scope"])
        assertEquals(2, diagnostic["selected_python_file_count"])
        assertEquals("available", diagnostic["language_support_state"])
        assertEquals("missing", diagnostic["sdk_assignment_state"])
        assertEquals("python_sdk_missing", diagnostic["analysis_state"])
        assertEquals(false, diagnostic["inspection_started"])
        assertEquals("configuration", diagnostic["outcome_ownership"])
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
    }

    @Test
    fun `test refreshing Python analysis remains fail closed before inspection`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockVirtualFileManager.syncRefresh() } returns 0L
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
    fun `test local Python SDK registration settles before stable input fingerprinting`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockVirtualFileManager.syncRefresh() } returns 0L
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        val readinessSequence = ArrayDeque(
            listOf(
                pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
            ),
        )
        var currentReadiness = readinessSequence.removeFirst()
        var fingerprintCalls = 0
        handler.pythonSdkSettleTimeoutMs = 100
        handler.pythonSdkSettlePollMs = 10
        handler.pythonSdkSettleNow = { 0L }
        handler.pythonSdkSettleSleep = {}
        handler.projectAnalysisReadinessProvider = { _, _ -> currentReadiness }
        handler.projectAnalysisReadinessRefreshProvider = { _, _, _ ->
            currentReadiness = readinessSequence.removeFirst()
            currentReadiness
        }
        handler.projectInputsFingerprintProvider = { _, _ ->
            assertTrue(currentReadiness.ready, "SDK readiness must settle before fingerprinting.")
            fingerprintCalls += 1
            projectInputsFingerprint(profileName = "TestProfile")
        }

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(1, fingerprintCalls)
    }

    @Test
    fun `test local Python SDK registration timeout remains language SDK missing`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockVirtualFileManager.syncRefresh() } returns 0L
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        var refreshCalls = 0
        handler.pythonSdkSettleTimeoutMs = 25
        handler.pythonSdkSettlePollMs = 10
        handler.pythonSdkSettleNow = { 0L }
        handler.pythonSdkSettleSleep = {}
        handler.projectAnalysisReadinessProvider = { _, _ ->
            pythonSdkReadiness(ready = false, localInterpreterCandidate = true)
        }
        handler.projectAnalysisReadinessRefreshProvider = { _, _, _ ->
            refreshCalls += 1
            pythonSdkReadiness(ready = false, localInterpreterCandidate = true)
        }
        handler.projectInputsFingerprintProvider = { _, _ ->
            error("Terminal SDK absence must not fingerprint inspection inputs.")
        }

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")
        val status = buildInspectionStatus()

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(3, refreshCalls)
        assertEquals("language_sdk_missing", status["capture_incomplete_reason"])
        @Suppress("UNCHECKED_CAST")
        val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
        assertEquals(true, diagnostic["python_sdk_settle_attempted"])
        assertEquals(true, diagnostic["python_sdk_settle_timed_out"])
        assertEquals(25L, diagnostic["python_sdk_settle_elapsed_ms"])
    }

    @Test
    fun `test Python SDK settle skips missing SDK without local interpreter candidate`() {
        var sleepCalls = 0
        var observationCalls = 0

        val result = settlePythonSdkReadiness(
            initialReadiness = pythonSdkReadiness(ready = false, localInterpreterCandidate = false),
            now = { 0L },
            sleep = { sleepCalls += 1 },
            observe = {
                observationCalls += 1
                error("No local interpreter candidate must not be retried.")
            },
            checkCanceled = {},
        )

        assertFalse(result.evidence.attempted)
        assertEquals(0, sleepCalls)
        assertEquals(0, observationCalls)
        assertEquals("python_sdk_missing", result.readiness.reason)
    }

    @Test
    fun `test Python SDK settle is zero cost for non Python scopes`() {
        var sleepCalls = 0
        var observationCalls = 0
        val initialReadiness = InspectionProjectAnalysisReadiness(
            required = false,
            ready = true,
            reason = "python_not_in_scope",
        )

        val result = settlePythonSdkReadiness(
            initialReadiness = initialReadiness,
            now = { 0L },
            sleep = { sleepCalls += 1 },
            observe = {
                observationCalls += 1
                error("Non-Python scopes must not be retried.")
            },
            checkCanceled = {},
        )

        assertFalse(result.evidence.attempted)
        assertEquals(0, sleepCalls)
        assertEquals(0, observationCalls)
        assertEquals(initialReadiness, result.readiness)
    }

    @Test
    fun `test Python SDK settle requires consecutive ready observations`() {
        val observations = ArrayDeque(
            listOf(
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
            ),
        )
        var sleepCalls = 0

        val result = settlePythonSdkReadiness(
            initialReadiness = pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
            now = { 0L },
            sleep = { sleepCalls += 1 },
            observe = { observations.removeFirst() },
            checkCanceled = {},
            timeoutMs = 100,
            pollMs = 10,
        )

        assertEquals(4, sleepCalls)
        assertEquals(5, result.evidence.observationCount)
        assertEquals(2, result.evidence.stableReadyObservations)
        assertTrue(result.readiness.ready)
        assertFalse(result.evidence.timedOut)
    }

    @Test
    fun `test Python SDK settle continues through assigned SDK update`() {
        val observations = ArrayDeque(
            listOf(
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
            ),
        )

        val result = settlePythonSdkReadiness(
            initialReadiness = InspectionProjectAnalysisReadiness(
                required = true,
                ready = false,
                reason = "python_sdk_updating",
                pythonFileCount = 1,
                pythonSdkCount = 1,
                updatingSdkCount = 1,
                localPythonInterpreterCandidate = true,
            ),
            now = { 0L },
            sleep = {},
            observe = { observations.removeFirst() },
            checkCanceled = {},
            timeoutMs = 100,
            pollMs = 10,
        )

        assertTrue(result.readiness.ready)
        assertEquals(2, result.evidence.stableReadyObservations)
        assertFalse(result.evidence.timedOut)
    }

    @Test
    fun `test Python SDK settle honors cancellation`() {
        var sleepCalls = 0

        assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
            settlePythonSdkReadiness(
                initialReadiness = pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
                now = { 0L },
                sleep = { sleepCalls += 1 },
                observe = { pythonSdkReadiness(ready = false, localInterpreterCandidate = true) },
                checkCanceled = { throw com.intellij.openapi.progress.ProcessCanceledException() },
            )
        }

        assertEquals(0, sleepCalls)
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

    @Test
    fun `test python analysis requires two identical SDK-backed snapshots`() {
        val readiness = InspectionProjectAnalysisReadiness(
            required = true,
            ready = true,
            reason = "ready",
            pythonFileCount = 1,
            pythonSdkCount = 1,
        )
        val fingerprint = projectInputsFingerprint(profileName = "qualification-identical")
        val snapshot = changedFilesSnapshot(
            problems = listOf(changedFileProblem(file = "/tmp/TestProject/qualification-identical.py")),
            resolvedFiles = listOf("/tmp/TestProject/qualification-identical.py"),
        )

        val first = qualifyProjectAnalysisSnapshot(snapshot, readiness, fingerprint)
        val second = qualifyProjectAnalysisSnapshot(snapshot, readiness, fingerprint)

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, first.outcome)
        assertEquals(CaptureIncompleteReason.PROJECT_ANALYSIS_NOT_READY, first.captureIncompleteReason)
        assertTrue(first.problems.isEmpty())
        assertEquals(InspectionSnapshotOutcome.PROBLEMS_FOUND, second.outcome)
        assertEquals(1, second.problems.size)
    }

    @Test
    fun `test python analysis fingerprint change resets qualification`() {
        val readiness = InspectionProjectAnalysisReadiness(
            required = true,
            ready = true,
            reason = "ready",
            pythonFileCount = 1,
            pythonSdkCount = 1,
        )
        val snapshot = changedFilesSnapshot(
            problems = listOf(changedFileProblem(file = "/tmp/TestProject/qualification-reset.py")),
            resolvedFiles = listOf("/tmp/TestProject/qualification-reset.py"),
        )
        val firstFingerprint = projectInputsFingerprint(profileName = "qualification-reset")
        val changedFingerprint = firstFingerprint.copy(
            moduleSdkStates = listOf("TestProject\u0000Updated SDK\u0000Python SDK\u00003.14\u0000/tmp/python-3.14"),
        )

        qualifyProjectAnalysisSnapshot(snapshot, readiness, firstFingerprint)
        val result = qualifyProjectAnalysisSnapshot(snapshot, readiness, changedFingerprint)

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, result.outcome)
        assertEquals(CaptureIncompleteReason.PROJECT_ANALYSIS_NOT_READY, result.captureIncompleteReason)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `test explicit missing inspection profile publishes capture incomplete snapshot`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockVirtualFileManager.syncRefresh() } returns 0L
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
        every { mockVirtualFileManager.syncRefresh() } returns 0L
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
        every { mockVirtualFileManager.syncRefresh() } returns 0L
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
    fun `test severity filtering logic`() {
        val handler = InspectionHandler()
        
        val mockRequest = mockk<FullHttpRequest>()
        every { mockRequest.uri() } returns "/api/inspection/problems?severity=error"
        every { mockRequest.method() } returns HttpMethod.GET
        
        assertTrue(handler.isSupported(mockRequest))
    }
    
    @Test
    fun `test severity parameter handling`() {
        val handler = InspectionHandler()
        
        val mockRequest = mockk<FullHttpRequest>()
        every { mockRequest.uri() } returns "/api/inspection/problems?severity=invalid"
        every { mockRequest.method() } returns HttpMethod.GET
        
        assertTrue(handler.isSupported(mockRequest))
    }
    
    @Test
    fun `test getCurrentProject returns valid project`() {
        val handler = InspectionHandler()
        
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true
        
        val result = method.invoke(handler, null) as Project?
        
        assertNotNull(result)
        assertEquals("TestProject", result?.name)
    }
    
    @Test
    fun `test getCurrentProject returns null when no valid project`() {
        every { mockProjectManager.openProjects } returns emptyArray()
        
        val mockIdeFocusManager = mockk<IdeFocusManager>()
        every { mockIdeFocusManager.lastFocusedFrame } returns null
        every { IdeFocusManager.getGlobalInstance() } returns mockIdeFocusManager
        
        val mockDataManager = mockk<DataManager>()
        val promise: Promise<DataContext> = rejectedPromise("No context")
        every { mockDataManager.dataContextFromFocusAsync } returns promise
        every { DataManager.getInstance() } returns mockDataManager
        
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true
        
        val result = method.invoke(handler, null) as Project?
        
        assertNull(result)
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
    fun `test problems endpoint returns valid response structure`() {
        val handler = InspectionHandler()
        runPooledTasksInline()
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/problems"
        every { mockUrlDecoder.parameters() } returns mapOf(
            "severity" to listOf("all")
        )
        
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
    }

    @Test
    fun `test problems endpoint without snapshot never trusts live tool window scrape`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.clear(projectKey(mockProject))
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblems(mockProject) } returns listOf(
            mapOf(
                "file" to "/tmp/TestProject/src/LiveOnly.kt",
                "severity" to "warning",
                "description" to "unverified live finding",
            ),
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
        verify(exactly = 0) { extractor.extractAllProblems(mockProject) }
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
        verify(exactly = 0) { mockVirtualFileManager.syncRefresh() }
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

    @Test
    fun `test final publication reconciles verified psi churn without absorbing later edits`() {
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
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
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
        assertEquals("fresh", completedStatus["snapshot_change_kind"])
        assertEquals(true, completedStatus["has_inspection_results"])
        assertEquals(true, editedStatus["results_may_be_stale"])
        assertEquals("project_changed_since_inspection", editedStatus["snapshot_change_kind"])
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
    fun `test final publication validates unchanged psi inputs before publishing`() {
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
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
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

    @Test
    fun `test final publication closes tracker race before fresh publication`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } returns 10L
        val key = projectKey(mockProject)
        InspectionResultsStore.clear(key)
        val inputFingerprint = projectInputsFingerprint()
        val contentTracker = FakeInspectionProjectContentTracker().apply {
            beforeRunIfUnchanged = { changed = true }
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
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
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

    @Test
    fun `test final publication rejects input drift without psi churn`() {
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
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
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

    @Test
    fun `test final publication rejects tracked file changes without psi churn`() {
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
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
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

    @Test
    fun `test final publication rejects unavailable validation without psi churn`() {
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
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
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

    @Test
    fun `test final publication keeps fail closed baseline for unsaved capture state`() {
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
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
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

    @Test
    fun `test final publication rejects saved content changes during inspection`() {
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
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(mockProject) } answers {
            contentTracker.changed = true
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
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
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

    @Test
    fun `test final publication rejects project input changes during inspection`() {
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
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
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
    fun `test final publication does not promote a narrow capture scope`() {
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

        assertEquals(10L, requireNotNull(InspectionResultsStore.getSnapshot(key)).projectState.psiModificationCount)
    }

    @Test
    fun `test final publication requires authoritative live extraction`() {
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
            captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
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
    fun `test requested inspection profile resolution never falls back`() {
        every { mockProfileManager.getProfile("RedLane", false) } returns null
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "resolveInspectionProfile",
            InspectionProjectProfileManager::class.java,
            String::class.java,
        )
        method.isAccessible = true

        val resolvedProfile = method.invoke(handler, mockProfileManager, "RedLane")

        assertNull(resolvedProfile)
        verify(exactly = 1) { mockProfileManager.getProfile("RedLane", false) }
        verify(exactly = 0) { mockProfileManager.getProfile("RedLane") }
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
        setInspectionRunState(
            key,
            InspectionRunState(runId = 7L, triggerTimeMs = System.currentTimeMillis(), inProgress = true),
        )
        setInspectionRunControl(key, InspectionRunControl(runId = 7L, indicator = indicator))

        val response = processGetRequest(
            "/api/inspection/cancel?worktree_path=/tmp/TestProject&inspection_run_id=7",
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"cancel_requested\""))
        assertTrue(body.contains("\"inspection_run_id\": 7"))
        verify(exactly = 1) { indicator.cancel() }
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
                viewReadyOk = true,
            ),
        )

        assertEquals(InspectionSnapshotOutcome.CLEAN_CONFIRMED, snapshot.outcome)
        assertNull(snapshot.captureIncompleteReason)
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
                viewReadyOk = true,
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
                viewReadyOk = true,
            ),
        )

        assertEquals(InspectionSnapshotOutcome.PROBLEMS_FOUND, snapshot.outcome)
        assertEquals(diagnostic, snapshot.captureDiagnostic)
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
        assertFalse(status.containsKey("scoped_clean_extraction_succeeded"))
        assertFalse(status.containsKey("scoped_clean_matcher_available"))
    }

    @Test
    fun `test completed scoped run does not prove clean when extraction fails`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        InspectionResultsStore.clear(projectKey(mockProject))
        mockExtractorFailure()
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
        assertFalse(status.containsKey("scoped_clean_matcher_available"))
        assertFalse(status.containsKey("scoped_clean_extraction_succeeded"))
    }

    @Test
    fun `test status endpoint does not refresh project state`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        mockInspectionPrerequisites(mockProject)
        every { mockApplication.isDispatchThread } returns true
        every { mockVirtualFileManager.syncRefresh() } returns 0L

        val response = processGetRequest("/api/inspection/status")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"TestProject\""))
        verify(exactly = 0) { mockVirtualFileManager.syncRefresh() }
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
        verify(exactly = 0) { mockVirtualFileManager.syncRefresh() }
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
        every { mockVirtualFileManager.syncRefresh() } returns 0L
        mockInspectionPrerequisites(mockProject)

        val response = processGetRequest("/api/inspection/problems?severity=all")

        assertEquals(HttpResponseStatus.OK, response.status())
        verify(exactly = 1) { mockApplication.executeOnPooledThread(any<Runnable>()) }
    }

    @Test
    fun `test scope parameter handling with whole_project`() {
        val handler = InspectionHandler()
        runPooledTasksInline()
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/problems"
        every { mockUrlDecoder.parameters() } returns mapOf(
            "scope" to listOf("whole_project"),
            "severity" to listOf("all")
        )
        
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
    }
    
    @Test
    fun `test scope parameter handling with current_file`() {
        val handler = InspectionHandler()
        runPooledTasksInline()
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/problems"
        every { mockUrlDecoder.parameters() } returns mapOf(
            "scope" to listOf("current_file"),
            "severity" to listOf("all")
        )
        
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
    }
    
    @Test
    fun `test scope parameter handling with custom scope`() {
        val handler = InspectionHandler()
        runPooledTasksInline()
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/problems"
        every { mockUrlDecoder.parameters() } returns mapOf(
            "scope" to listOf("odoo_intelligence_mcp"),
            "severity" to listOf("all")
        )
        
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
    }
    
    @Test
    fun `test scope parameter defaults to whole_project when missing`() {
        val handler = InspectionHandler()
        runPooledTasksInline()
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/problems"
        every { mockUrlDecoder.parameters() } returns mapOf(
            "severity" to listOf("all")
        )
        
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
    }
    
    @Test
    fun `test scope and severity parameters together`() {
        val handler = InspectionHandler()
        runPooledTasksInline()
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/problems"
        every { mockUrlDecoder.parameters() } returns mapOf(
            "scope" to listOf("custom_scope"),
            "severity" to listOf("error")
        )
        
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
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
    fun `test getInspectionProblems method signature accepts scope parameter`() {
        // Use reflection to verify the method signature includes all filtering parameters
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "getInspectionProblems", 
            Project::class.java, // project
            String::class.java,  // severity
            String::class.java,  // scope
            String::class.java,  // problemType (nullable)
            String::class.java,  // filePattern (nullable)
            Int::class.java,     // limit
            Int::class.java,     // offset
            Boolean::class.java, // includeStale
            String::class.java,  // directoryParam (nullable)
            List::class.java,    // files (nullable)
            Boolean::class.java, // includeUnversioned
            String::class.java,  // changedFilesMode (nullable)
            Int::class.javaObjectType, // maxFiles (nullable)
        )
        
        assertNotNull(method)
        assertEquals("getInspectionProblems", method.name)
        assertEquals(13, method.parameterCount)
    }
    
    @Test
    fun `test getCurrentProject with multiple projects returns active one`() {
        val mockProject1 = mockk<Project>()
        val mockProject2 = mockk<Project>()
        val mockProject3 = mockk<Project>()
        
        every { mockProject1.isDefault } returns false
        every { mockProject1.isDisposed } returns false
        every { mockProject1.isInitialized } returns true
        every { mockProject1.name } returns "Project1"
        
        every { mockProject2.isDefault } returns false
        every { mockProject2.isDisposed } returns false
        every { mockProject2.isInitialized } returns true
        every { mockProject2.name } returns "ActiveProject"
        
        every { mockProject3.isDefault } returns false
        every { mockProject3.isDisposed } returns false
        every { mockProject3.isInitialized } returns true
        every { mockProject3.name } returns "Project3"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject1, mockProject2, mockProject3)
        
        val mockIdeFocusManager = mockk<IdeFocusManager>()
        val mockIdeFrame = mockk<IdeFrame>()
        every { mockIdeFrame.project } returns mockProject2
        every { mockIdeFocusManager.lastFocusedFrame } returns mockIdeFrame
        every { IdeFocusManager.getGlobalInstance() } returns mockIdeFocusManager
        
        val mockWindow1 = mockk<JFrame>()
        val mockWindow2 = mockk<JFrame>()
        val mockWindow3 = mockk<JFrame>()
        
        every { mockWindow1.isActive } returns false
        every { mockWindow2.isActive } returns true
        every { mockWindow3.isActive } returns false
        
        every { mockWindowManager.suggestParentWindow(mockProject1) } returns mockWindow1
        every { mockWindowManager.suggestParentWindow(mockProject2) } returns mockWindow2
        every { mockWindowManager.suggestParentWindow(mockProject3) } returns mockWindow3
        
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true
        
        val result = method.invoke(handler, null) as Project?
        
        assertNotNull(result)
        assertEquals("ActiveProject", result?.name)
    }
    
    @Test
    fun `test getCurrentProject with no active window returns first valid project`() {
        val mockProject1 = mockk<Project>()
        val mockProject2 = mockk<Project>()
        
        every { mockProject1.isDefault } returns false
        every { mockProject1.isDisposed } returns false
        every { mockProject1.isInitialized } returns true
        every { mockProject1.name } returns "FirstProject"
        
        every { mockProject2.isDefault } returns false
        every { mockProject2.isDisposed } returns false
        every { mockProject2.isInitialized } returns true
        every { mockProject2.name } returns "SecondProject"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject1, mockProject2)
        
        val mockIdeFocusManager = mockk<IdeFocusManager>()
        every { mockIdeFocusManager.lastFocusedFrame } returns null
        every { IdeFocusManager.getGlobalInstance() } returns mockIdeFocusManager
        
        val mockDataManager = mockk<DataManager>()
        val promise: Promise<DataContext> = rejectedPromise("No context")
        every { mockDataManager.dataContextFromFocusAsync } returns promise
        every { DataManager.getInstance() } returns mockDataManager
        
        val mockWindow1 = mockk<JFrame>()
        val mockWindow2 = mockk<JFrame>()
        
        every { mockWindow1.isActive } returns false
        every { mockWindow2.isActive } returns false
        
        every { mockWindowManager.suggestParentWindow(mockProject1) } returns mockWindow1
        every { mockWindowManager.suggestParentWindow(mockProject2) } returns mockWindow2
        
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true
        
        val result = method.invoke(handler, null) as Project?
        
        assertNotNull(result)
        assertEquals("FirstProject", result?.name)
    }
    
    @Test
    fun `test getCurrentProject with null windows returns first valid project`() {
        val mockProject1 = mockk<Project>()
        val mockProject2 = mockk<Project>()
        
        every { mockProject1.isDefault } returns false
        every { mockProject1.isDisposed } returns false
        every { mockProject1.isInitialized } returns true
        every { mockProject1.name } returns "FirstProject"
        
        every { mockProject2.isDefault } returns false
        every { mockProject2.isDisposed } returns false
        every { mockProject2.isInitialized } returns true
        every { mockProject2.name } returns "SecondProject"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject1, mockProject2)
        
        val mockIdeFocusManager = mockk<IdeFocusManager>()
        every { mockIdeFocusManager.lastFocusedFrame } returns null
        every { IdeFocusManager.getGlobalInstance() } returns mockIdeFocusManager
        
        val mockDataManager = mockk<DataManager>()
        val promise: Promise<DataContext> = rejectedPromise("No context")
        every { mockDataManager.dataContextFromFocusAsync } returns promise
        every { DataManager.getInstance() } returns mockDataManager
        
        every { mockWindowManager.suggestParentWindow(mockProject1) } returns null
        every { mockWindowManager.suggestParentWindow(mockProject2) } returns null
        
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true
        
        val result = method.invoke(handler, null) as Project?
        
        assertNotNull(result)
        assertEquals("FirstProject", result?.name)
    }
    
    @Test
    fun `test getCurrentProject with explicit project name`() {
        val mockProject1 = mockk<Project>()
        val mockProject2 = mockk<Project>()
        
        every { mockProject1.isDefault } returns false
        every { mockProject1.isDisposed } returns false
        every { mockProject1.isInitialized } returns true
        every { mockProject1.name } returns "ProjectOne"
        
        every { mockProject2.isDefault } returns false
        every { mockProject2.isDisposed } returns false
        every { mockProject2.isInitialized } returns true
        every { mockProject2.name } returns "ProjectTwo"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject1, mockProject2)
        
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true
        
        val result1 = method.invoke(handler, "ProjectTwo") as Project?
        assertNotNull(result1)
        assertEquals("ProjectTwo", result1?.name)
        
        val result2 = method.invoke(handler, "ProjectOne") as Project?
        assertNotNull(result2)
        assertEquals("ProjectOne", result2?.name)
        
        val result3 = method.invoke(handler, "NonExistent") as Project?
        assertNull(result3)
    }

    @Test
    fun `test getCurrentProject treats blank project name as fallback selector`() {
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true

        val result = method.invoke(handler, "   ") as Project?

        assertNotNull(result)
        assertEquals("TestProject", result?.name)
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

    @Test
    fun `test route endpoint reports session drift as conflict`() {
        val response = processGetRequest(
            "/api/inspection/route?session_id=old-session&client_run_id=cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.CONFLICT, response.status())
        assertTrue(body.contains("\"session_drift\": true"))
        assertTrue(body.contains("\"inspection_verdict\": \"UNKNOWN\""))
        assertTrue(body.contains("\"inspection_verdict_reason\": \"session_drift\""))
        assertTrue(body.contains("\"expected_session_id\": \"old-session\""))
        assertTrue(body.contains("\"classification\": \"legitimate_fail_closed\""), body)
        assertTrue(body.contains("\"phase\": \"route\""), body)
        assertTrue(body.contains("\"http_status\": 409"), body)
        assertTrue(body.contains("\"client_run_id\": \"cccccccc-cccc-4ccc-8ccc-cccccccccccc\""), body)
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
    fun `test route endpoint selects the most specific nested project`() {
        val parentProject = mockProject(
            name = "Parent",
            basePath = "/repo",
            projectFilePath = "/repo/.idea/misc.xml",
        )
        val childProject = mockProject(
            name = "Child",
            basePath = "/repo/packages/app",
            projectFilePath = "/repo/packages/app/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(parentProject, childProject)

        val response = processGetRequest("/api/inspection/route?cwd=/repo/packages/app/src")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"Child\""))
        assertFalse(body.contains("Multiple open projects matched this request"))
    }

    @Test
    fun `test route endpoint requires exact worktree path`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"

        val response = processGetRequest("/api/inspection/route?worktree_path=/repo/app/src")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"no_project\""))
    }

    @Test
    fun `test route endpoint includes project instance id`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"

        val response = processGetRequest("/api/inspection/route?worktree_path=/repo/app")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_instance_id\""))
        assertTrue(body.contains("\"inspection_execution_proof_version\": 4"))
    }

    @Test
    fun `test lifecycle claim returns close token for exact project instance`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)

        val response = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"claimed\""))
        assertTrue(body.contains("\"ownership_proven\": true"))
        assertTrue(body.contains("\"close_token\""))
        assertTrue(body.contains("\"lease_id\": \"test-lease\""))
        assertTrue(body.contains("\"lifecycle_ownership_protocol\": \"lease_bound_v1\""))
    }

    @Test
    fun `test lifecycle claim preserves cleanup authority while content roots are not ready`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        handler.lifecycleContentRootReadinessProvider = { _, targetKey ->
            InspectionHandler.LifecycleContentRootReadiness(
                ready = false,
                reason = "no_content_roots",
                targetKey = targetKey,
            )
        }
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)

        val response = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"claimed\""))
        assertTrue(body.contains("\"ownership_proven\": true"))
        assertTrue(body.contains("\"close_token\""))
        assertTrue(body.contains("\"lifecycle_readiness\""))
        assertTrue(body.contains("\"ready\": false"))
        assertTrue(body.contains("\"reason\": \"no_content_roots\""))
    }

    @Test
    fun `test lifecycle content root readiness uses the real project model`() {
        val tempDir = Files.createTempDirectory("inspection-content-root-readiness")
        every { mockProject.basePath } returns tempDir.toString()
        every { mockProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val rootManager = mockk<ProjectRootManager>()
        val moduleManager = mockk<ModuleManager>()
        var contentRoots = emptyArray<VirtualFile>()
        var modules = emptyArray<com.intellij.openapi.module.Module>()
        val moduleRootManagers = mutableMapOf<com.intellij.openapi.module.Module, ModuleRootManager>()
        every { rootManager.contentRoots } answers { contentRoots }
        every { rootManager.contentSourceRoots } returns emptyArray()
        every { moduleManager.modules } answers { modules }
        mockkStatic(ProjectRootManager::class)
        mockkStatic(ModuleManager::class)
        mockkStatic(ModuleRootManager::class)
        every { ProjectRootManager.getInstance(mockProject) } returns rootManager
        every { ModuleManager.getInstance(mockProject) } returns moduleManager
        every { ModuleRootManager.getInstance(any()) } answers {
            moduleRootManagers.getValue(firstArg())
        }
        val productionHandler = InspectionHandler()
        var analysisReadinessCalls = 0
        productionHandler.projectAnalysisReadinessProvider = { _, _ ->
            analysisReadinessCalls += 1
            error("Lifecycle structural readiness must not evaluate whole-project language readiness.")
        }
        val targetKey = tempDir.toRealPath().toString()

        try {
            val noRoots = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertFalse(noRoots.ready)
            assertEquals("no_content_roots", noRoots.reason)

            val childRootPath = Files.createDirectories(tempDir.resolve("module-a"))
            val childRoot = mockk<VirtualFile>()
            every { childRoot.path } returns childRootPath.toString()
            every { childRoot.isInLocalFileSystem } returns true
            contentRoots = arrayOf(childRoot)
            val childReady = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertTrue(childReady.ready, childReady.toString())
            assertFalse(childReady.targetInsideContent)
            assertTrue(childReady.contentRootInsideTarget)
            assertFalse(childReady.analysisRequired)
            assertTrue(childReady.analysisReady)
            assertEquals("deferred_to_inspection_scope", childReady.analysisReason)

            val fallbackModule = mockk<com.intellij.openapi.module.Module>()
            val fallbackRootManager = mockk<ModuleRootManager>()
            every { fallbackModule.name } returns "__jetbrains_inspection_api_lifecycle_fallback__test"
            every { fallbackRootManager.contentRoots } returns arrayOf(childRoot)
            moduleRootManagers[fallbackModule] = fallbackRootManager
            modules = arrayOf(fallbackModule)
            val fallbackReady = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertTrue(fallbackReady.ready, fallbackReady.toString())
            assertEquals(1, fallbackReady.fallbackModuleCount)

            val outsideRootPath = Files.createDirectories(tempDir.resolveSibling("outside-module"))
            val outsideRoot = mockk<VirtualFile>()
            every { outsideRoot.path } returns outsideRootPath.toString()
            every { outsideRoot.isInLocalFileSystem } returns true
            contentRoots = arrayOf(outsideRoot)
            modules = emptyArray()
            val outside = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertFalse(outside.ready)
            assertEquals("content_roots_outside_target", outside.reason)

            val targetModule = mockk<com.intellij.openapi.module.Module>()
            val targetModuleRootManager = mockk<ModuleRootManager>()
            every { targetModule.name } returns "target-module"
            every { targetModuleRootManager.contentRoots } returns arrayOf(childRoot)
            moduleRootManagers[targetModule] = targetModuleRootManager
            val siblingModule = mockk<com.intellij.openapi.module.Module>()
            val siblingModuleRootManager = mockk<ModuleRootManager>()
            every { siblingModule.name } returns "sibling-module"
            every { siblingModuleRootManager.contentRoots } returns arrayOf(outsideRoot)
            moduleRootManagers[siblingModule] = siblingModuleRootManager
            contentRoots = arrayOf(childRoot, outsideRoot)
            modules = arrayOf(targetModule, siblingModule)
            val mixedRoots = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertTrue(mixedRoots.ready, mixedRoots.toString())
            assertEquals("ready", mixedRoots.reason)

            modules = arrayOf(fallbackModule, siblingModule)
            val fallbackWithSibling = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertFalse(fallbackWithSibling.ready)
            assertEquals("content_roots_outside_target", fallbackWithSibling.reason)
            assertEquals(0, analysisReadinessCalls)
        } finally {
            unmockkStatic(ProjectRootManager::class)
            unmockkStatic(ModuleManager::class)
            unmockkStatic(ModuleRootManager::class)
        }
    }

    @Test
    fun `test lifecycle fallback creator adds a non-persistent module content root`() {
        val moduleManager = mockk<ModuleManager>()
        val module = mockk<com.intellij.openapi.module.Module>()
        val targetRoot = mockk<VirtualFile>()
        val moduleName = slot<String>()
        every { mockProject.isDisposed } returns false
        every { mockApplication.runWriteAction(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
        }
        mockkStatic(ModuleManager::class)
        mockkStatic(ModuleRootModificationUtil::class)
        every { ModuleManager.getInstance(mockProject) } returns moduleManager
        every { moduleManager.findModuleByName(any()) } returns null
        every {
            moduleManager.newNonPersistentModule(capture(moduleName), EmptyModuleType.EMPTY_MODULE)
        } returns module
        every { moduleManager.disposeModule(any()) } just Runs
        every { ModuleRootModificationUtil.addContentRoot(module, targetRoot) } just Runs

        try {
            assertTrue(handler.lifecycleFallbackContentRootCreator(mockProject, targetRoot))
            assertTrue(
                moduleName.captured.startsWith("__jetbrains_inspection_api_lifecycle_fallback__"),
                moduleName.captured,
            )
            verify(exactly = 1) {
                moduleManager.newNonPersistentModule(moduleName.captured, EmptyModuleType.EMPTY_MODULE)
                ModuleRootModificationUtil.addContentRoot(module, targetRoot)
            }
            verify(exactly = 0) { moduleManager.disposeModule(any()) }
        } finally {
            unmockkStatic(ModuleRootModificationUtil::class)
            unmockkStatic(ModuleManager::class)
        }
    }

    @Test
    fun `test lifecycle claim does not authorize close for preexisting project`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)

        val response = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"not_owned\""))
        assertTrue(body.contains("\"ownership_proven\": false"))
        assertTrue(body.contains("\"reason\": \"project_preexisted\""))
        assertFalse(body.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle claim rejects ownership bound to another project object`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val otherProject = mockProject(
            name = "OtherProject",
            basePath = "/repo/app",
            projectFilePath = "/repo/app/.idea/misc.xml",
        )
        val field = InspectionHandler::class.java.getDeclaredField("lifecycleOpenOwnershipByProjectInstance")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val ownership = field.get(handler) as MutableMap<String, InspectionHandler.LifecycleOpenOwnership>
        ownership[projectInstanceId(mockProject)] = InspectionHandler.LifecycleOpenOwnership(
            leaseId = "test-lease",
            targetKey = Paths.get("/repo/app").normalize().toAbsolutePath().toString(),
            project = otherProject,
        )

        val response = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=${projectInstanceId(mockProject)}&lease_id=test-lease"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"not_owned\""))
        assertTrue(body.contains("\"reason\": \"project_instance_reused\""))
        assertFalse(body.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle claim rejects stale project instance id`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"

        val response = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=old-instance"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("does not match the resolved route"))
    }

    @Test
    fun `test lifecycle open rejects scheduling new project without session id`() {
        val tempDir = Files.createTempDirectory("inspection-open-missing-session")
        every { mockProjectManager.openProjects } returns emptyArray()

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("\"reason\": \"missing_session_id\""))
        verify(exactly = 0) { mockApplication.invokeLater(any()) }
    }

    @Test
    fun `test lifecycle open opens project path in running IDE`() {
        val tempDir = Files.createTempDirectory("inspection-open-test")
        val openedProject = mockProject(
            name = "Opened",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var trustedPath: Path? = null
        var openedPath: Path? = null
        handler.trustProjectPath = { path: Path -> trustedPath = path }
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(tempDir))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opened\": false"))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertTrue(body.contains("\"ownership_registered\": true"))
        assertTrue(body.contains("\"lifecycle_ownership_protocol\": \"lease_bound_v1\""))
        assertTrue(body.contains(tempDir.toString()))
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open binds fresh project to lease for claim`() {
        val tempDir = Files.createTempDirectory("inspection-open-owned")
        val openedProject = mockProject(
            name = "Owned",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(openedProject)
            openProjects = arrayOf(openedProject)
            openedProject
        }

        val openResponse = processGetRequest(lifecycleOpenUri(tempDir, "owned-lease"))
        val instanceId = projectInstanceId(openedProject)
        val claimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=owned-lease"
        )
        val claimBody = claimResponse.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, openResponse.status())
        assertEquals(HttpResponseStatus.OK, claimResponse.status())
        assertTrue(claimBody.contains("\"status\": \"claimed\""))
        assertTrue(claimBody.contains("\"ownership_proven\": true"))
        assertTrue(claimBody.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle route stays hidden until project ownership is registered`() {
        val tempDir = Files.createTempDirectory("inspection-open-claim-race")
        val openedProject = mockProject(
            name = "OwnedDuringOpen",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        val scheduled = AtomicReference<Runnable?>()
        val openedCallbackComplete = CountDownLatch(1)
        val allowOpenReturn = CountDownLatch(1)
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            scheduled.set(firstArg<Runnable>())
        }
        handler.openProjectPath = { _, onOpened ->
            onOpened(openedProject)
            openProjects = arrayOf(openedProject)
            openedCallbackComplete.countDown()
            assertTrue(allowOpenReturn.await(5, TimeUnit.SECONDS))
            openedProject
        }

        val openResponse = processGetRequest(lifecycleOpenUri(tempDir, "race-lease"))
        val openThread = Thread { scheduled.get()?.run() }
        openThread.start()
        assertTrue(openedCallbackComplete.await(5, TimeUnit.SECONDS))
        val instanceId = projectInstanceId(openedProject)
        val hiddenClaimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=race-lease"
        )
        val hiddenClaimBody = hiddenClaimResponse.content().toString(Charsets.UTF_8)
        val hiddenRouteResponse = processGetRequest(
            "/api/inspection/route?project=${java.net.URLEncoder.encode("OwnedDuringOpen", "UTF-8")}",
        )
        val hiddenRouteBody = hiddenRouteResponse.content().toString(Charsets.UTF_8)
        val hiddenIdentities = openProjectIdentities()
        val hiddenProjectVisible = LifecycleOpenRouteVisibility.isVisible(openedProject)
        allowOpenReturn.countDown()
        openThread.join(5_000)
        val claimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=race-lease"
        )
        val claimBody = claimResponse.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, openResponse.status())
        assertEquals(HttpResponseStatus.OK, hiddenClaimResponse.status())
        assertTrue(hiddenClaimBody.contains("\"status\": \"no_project\""))
        assertEquals(HttpResponseStatus.OK, hiddenRouteResponse.status())
        assertTrue(hiddenRouteBody.contains("\"status\": \"no_project\""), hiddenRouteBody)
        assertFalse(hiddenProjectVisible)
        assertTrue(hiddenIdentities.isEmpty(), hiddenIdentities.toString())
        assertTrue(openProjectIdentities().any { identity -> identity["project_instance_id"] == instanceId })
        assertEquals(HttpResponseStatus.OK, claimResponse.status())
        assertTrue(claimBody.contains("\"status\": \"claimed\""))
        assertTrue(claimBody.contains("\"ownership_proven\": true"))
        assertTrue(claimBody.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle open race with user project never grants ownership`() {
        val tempDir = Files.createTempDirectory("inspection-open-user-race")
        val userProject = mockProject(
            name = "UserOwned",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        val scheduled = mutableListOf<Runnable>()
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, _ -> error("scheduled open must not run after a user project appears") }

        val openResponse = processGetRequest(lifecycleOpenUri(tempDir, "raced-lease"))
        openProjects = arrayOf(userProject)
        scheduled.single().run()
        val instanceId = projectInstanceId(userProject)
        val claimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=raced-lease"
        )
        val claimBody = claimResponse.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, openResponse.status())
        assertTrue(openResponse.content().toString(Charsets.UTF_8).contains("\"ownership_registered\": true"))
        assertEquals(HttpResponseStatus.OK, claimResponse.status())
        assertTrue(claimBody.contains("\"status\": \"not_owned\""), claimBody)
        assertTrue(claimBody.contains("\"ownership_proven\": false"), claimBody)
        assertFalse(claimBody.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle open does not own a project that appeared before the open call`() {
        val tempDir = Files.createTempDirectory("inspection-open-late-user-race")
        val userProject = mockProject(
            name = "LateUserOwned",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var initialized = false
        var openProjects = emptyArray<Project>()
        val scheduled = mutableListOf<Runnable>()
        every { userProject.isInitialized } answers { initialized }
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, onOpened ->
            initialized = true
            onOpened(userProject)
            userProject
        }

        processGetRequest(lifecycleOpenUri(tempDir, "late-user-lease"))
        openProjects = arrayOf(userProject)
        scheduled.single().run()
        val instanceId = projectInstanceId(userProject)
        val claimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=late-user-lease",
        )
        val claimBody = claimResponse.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, claimResponse.status())
        assertTrue(claimBody.contains("\"status\": \"not_owned\""), claimBody)
        assertTrue(claimBody.contains("\"ownership_proven\": false"), claimBody)
        assertFalse(claimBody.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle open requires reported project identity to match returned project`() {
        val tempDir = Files.createTempDirectory("inspection-open-coalesced")
        val initializedProject = mockProject(
            name = "Initialized",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/initialized.xml").toString(),
        )
        val returnedProject = mockProject(
            name = "Returned",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(initializedProject)
            openProjects = arrayOf(returnedProject)
            returnedProject
        }

        processGetRequest(lifecycleOpenUri(tempDir, "coalesced-lease"))
        val instanceId = projectInstanceId(returnedProject)
        val claimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=coalesced-lease"
        )
        val claimBody = claimResponse.content().toString(Charsets.UTF_8)

        assertTrue(claimBody.contains("\"status\": \"not_owned\""))
        assertFalse(claimBody.contains("\"close_token\""))
        assertFalse(
            handler.lifecycleFallbackContentRootInstaller(returnedProject, tempDir.toRealPath().toString()),
            "A returned project that does not match the reported opened instance must not be repaired.",
        )
    }

    @Test
    fun `test lifecycle fallback installer requires a lease-bound open`() {
        val tempDir = Files.createTempDirectory("inspection-open-fallback-no-lease")
        val openedProject = mockProject(
            name = "NoLease",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(openedProject)
            openProjects = arrayOf(openedProject)
            openedProject
        }
        val encodedPath = java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")
        val encodedSession = java.net.URLEncoder.encode(InspectionIdeSession.sessionId, "UTF-8")

        processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=$encodedPath&session_id=$encodedSession"
        )

        assertFalse(
            handler.lifecycleFallbackContentRootInstaller(openedProject, tempDir.toRealPath().toString()),
            "Fallback repair requires the same lease used to register helper ownership.",
        )
    }

    @Test
    fun `test lifecycle fallback installer schedules exact owned root without blocking`() {
        val tempDir = Files.createTempDirectory("inspection-open-fallback-owned")
        val openedProject = mockProject(
            name = "OwnedFallback",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        val scheduled = mutableListOf<Runnable>()
        val pooled = mutableListOf<Runnable>()
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            pooled += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(openedProject)
            openProjects = arrayOf(openedProject)
            openedProject
        }
        handler.lifecycleContentRootReadinessProvider = { _, targetKey ->
            InspectionHandler.LifecycleContentRootReadiness(
                ready = false,
                reason = "no_content_roots",
                targetKey = targetKey,
            )
        }
        val localFileSystem = mockk<LocalFileSystem>()
        val targetRoot = mockk<VirtualFile>()
        val targetKey = tempDir.toRealPath().toString()
        var createdRoot: VirtualFile? = null
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.refreshAndFindFileByNioFile(tempDir.toRealPath()) } returns targetRoot
        handler.lifecycleFallbackContentRootCreator = { project, root ->
            assertSame(openedProject, project)
            createdRoot = root
            true
        }

        try {
            processGetRequest(lifecycleOpenUri(tempDir, "owned-fallback-lease"))
            scheduled.single().run()

            assertEquals(1, pooled.size)
            assertTrue(handler.lifecycleFallbackContentRootInstaller(openedProject, targetKey))
            assertEquals(2, scheduled.size, "The installer must enqueue work instead of blocking on the EDT.")
            assertNull(createdRoot)

            scheduled.last().run()
            assertSame(targetRoot, createdRoot)
        } finally {
            unmockkStatic(LocalFileSystem::class)
        }
    }

    @Test
    fun `test lifecycle open trusts project path before opening`() {
        val tempDir = Files.createTempDirectory("inspection-open-trust-test")
        val openedProject = mockProject(
            name = "TrustedOpen",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        val events = mutableListOf<String>()
        var trustedPath: Path? = null
        var openedPath: Path? = null
        handler.trustProjectPath = { path: Path ->
            events += "trust"
            trustedPath = path
        }
        handler.openProjectPath = { path: Path, beforeInit ->
            events += "open"
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(tempDir))

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(listOf("trust", "open"), events)
        assertEquals(tempDir.toAbsolutePath().normalize(), trustedPath)
        assertEquals(trustedPath, openedPath)
    }

    @Test
    fun `test lifecycle open opens ipr project file path in running IDE`() {
        val tempDir = Files.createTempDirectory("inspection-open-ipr-file")
        val projectFilePath = tempDir.resolve("project.ipr")
        Files.writeString(projectFilePath, "<project />")
        val openedProject = mockProject(
            name = "OpenedIpr",
            basePath = null,
            projectFilePath = projectFilePath.toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var trustedPath: Path? = null
        var openedPath: Path? = null
        handler.trustProjectPath = { path: Path -> trustedPath = path }
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(projectFilePath))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(tempDir.toAbsolutePath().normalize(), trustedPath)
        assertEquals(projectFilePath.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open opens idea project file path in running IDE`() {
        val tempDir = Files.createTempDirectory("inspection-open-idea-file")
        val projectFilePath = tempDir.resolve(".idea/misc.xml")
        Files.createDirectories(projectFilePath.parent)
        Files.writeString(projectFilePath, "<project />")
        val openedProject = mockProject(
            name = "OpenedIdeaFile",
            basePath = tempDir.toString(),
            projectFilePath = projectFilePath.toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(projectFilePath))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open treats ipr-named idea metadata as project root`() {
        val tempDir = Files.createTempDirectory("inspection-open-idea-ipr-file")
        val metadataPath = tempDir.resolve(".idea/project.ipr")
        Files.createDirectories(metadataPath.parent)
        Files.writeString(metadataPath, "<project />")
        val openedProject = mockProject(
            name = "OpenedIdeaIprMetadata",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var trustedPath: Path? = null
        var openedPath: Path? = null
        handler.trustProjectPath = { path: Path -> trustedPath = path }
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(metadataPath))

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(tempDir.toAbsolutePath().normalize(), trustedPath)
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open rejects regular non-project file outside idea`() {
        val tempDir = Files.createTempDirectory("inspection-open-non-project-file")
        val regularFile = tempDir.resolve("notes.txt")
        Files.writeString(regularFile, "not a project")
        every { mockProjectManager.openProjects } returns emptyArray()

        val response = processGetRequest(lifecycleOpenUri(regularFile))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("\"parameter\": \"worktree_path\""))
        assertTrue(body.contains(".ipr project file, or file inside .idea"))
    }

    @Test
    fun `test lifecycle open resolves idea directory to project root`() {
        val tempDir = Files.createTempDirectory("inspection-open-idea-dir")
        val ideaDir = tempDir.resolve(".idea")
        Files.createDirectories(ideaDir)
        val openedProject = mockProject(
            name = "OpenedIdeaDir",
            basePath = tempDir.toString(),
            projectFilePath = ideaDir.resolve("misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(ideaDir))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open resolves nested idea directory to project root`() {
        val tempDir = Files.createTempDirectory("inspection-open-nested-idea-dir")
        val nestedIdeaDir = tempDir.resolve(".idea/runConfigurations")
        Files.createDirectories(nestedIdeaDir)
        val openedProject = mockProject(
            name = "OpenedNestedIdeaDir",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(nestedIdeaDir))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open resolves nested idea file to project root`() {
        val tempDir = Files.createTempDirectory("inspection-open-nested-idea-file")
        val nestedIdeaFile = tempDir.resolve(".idea/runConfigurations/app.xml")
        Files.createDirectories(nestedIdeaFile.parent)
        Files.writeString(nestedIdeaFile, "<component />")
        val openedProject = mockProject(
            name = "OpenedNestedIdeaFile",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(nestedIdeaFile))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"project_root\": \"$tempDir\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open reports already open exact project`() {
        val tempDir = Files.createTempDirectory("inspection-open-existing")
        every { mockProject.basePath } returns tempDir.toString()
        every { mockProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
    }

    @Test
    fun `test lifecycle open schedules nested worktree when containing project is open`() {
        val tempDir = Files.createTempDirectory("inspection-open-containing")
        val nestedPath = tempDir.resolve("packages/app")
        Files.createDirectories(nestedPath)
        every { mockProject.basePath } returns tempDir.toString()
        every { mockProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        var openedPath: Path? = null
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(mockProject)
            mockProject
        }

        val response = processGetRequest(lifecycleOpenUri(nestedPath))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opened\": false"))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(nestedPath.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open schedules linked worktree when main checkout is open`() {
        val tempDir = Files.createTempDirectory("inspection-open-linked-worktree")
        val mainCheckout = tempDir.resolve("main")
        val linkedWorktree = tempDir.resolve("worktrees/feature")
        Files.createDirectories(mainCheckout)
        Files.createDirectories(linkedWorktree)
        every { mockProject.basePath } returns mainCheckout.toString()
        every { mockProject.projectFilePath } returns mainCheckout.resolve(".idea/misc.xml").toString()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            val openedProject = mockProject(
                name = "Feature",
                basePath = linkedWorktree.toString(),
                projectFilePath = linkedWorktree.resolve(".idea/misc.xml").toString(),
            )
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(linkedWorktree))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertFalse(body.contains("\"status\": \"already_open\""))
        assertEquals(linkedWorktree.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open reports already open project file path`() {
        val tempDir = Files.createTempDirectory("inspection-open-file-existing")
        val projectFilePath = tempDir.resolve(".idea/misc.xml").toString()
        every { mockProject.basePath } returns null
        every { mockProject.projectFilePath } returns projectFilePath

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(projectFilePath, "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
    }

    @Test
    fun `test lifecycle open detects already open project root from project file path`() {
        val tempDir = Files.createTempDirectory("inspection-open-file-root-existing")
        val projectFilePath = tempDir.resolve(".idea/misc.xml").toString()
        every { mockProject.basePath } returns null
        every { mockProject.projectFilePath } returns projectFilePath
        var scheduled = false
        every { mockApplication.invokeLater(any()) } answers {
            scheduled = true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
        assertFalse(scheduled)
    }

    @Test
    fun `test lifecycle open detects already open ipr project root`() {
        val tempDir = Files.createTempDirectory("inspection-open-ipr-root-existing")
        val projectFilePath = tempDir.resolve("project.ipr").toString()
        every { mockProject.basePath } returns null
        every { mockProject.projectFilePath } returns projectFilePath
        var scheduled = false
        every { mockApplication.invokeLater(any()) } answers {
            scheduled = true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
        assertFalse(scheduled)
    }

    @Test
    fun `test lifecycle open coalesces duplicate concurrent opens`() {
        val tempDir = Files.createTempDirectory("inspection-open-duplicate")
        every { mockProjectManager.openProjects } returns emptyArray()
        val scheduled = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, _ -> null }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(secondBody.contains("\"reason\": \"already_opening\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
        scheduled.single().run()
        val third = processGetRequest(lifecycleOpenUri(tempDir))
        assertEquals(2, scheduled.size)
        assertEquals(HttpResponseStatus.OK, third.status())
    }

    @Test
    fun `test lifecycle open keeps opening guard until returned project is initialized`() {
        val tempDir = Files.createTempDirectory("inspection-open-initializing")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-initializing"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)
        every { initializingProject.isInitialized } returns true
        val third = processGetRequest(lifecycleOpenUri(tempDir))
        val thirdBody = third.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(secondBody.contains("\"reason\": \"already_opening\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
        assertEquals(HttpResponseStatus.OK, third.status())
        assertTrue(thirdBody.contains("\"status\": \"already_open\""))
    }

    @Test
    fun `test lifecycle open repairs content root lost during project configuration`() {
        val tempDir = Files.createTempDirectory("inspection-open-root-repair")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns true
        every { initializingProject.name } returns "inspection-open-root-repair"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        var nowMs = 1_000L
        var fallbackInstalled = false
        var fallbackCreateCount = 0
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardPollMs = 200
        handler.lifecycleOpenGuardTimeoutMs = 2_000
        handler.lifecycleOpenRootStabilizationMs = 400
        handler.lifecycleFallbackRootStabilizationMs = 200
        handler.lifecycleFallbackFailureThreshold = 2
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis ->
            if (!fallbackInstalled && scheduled.size > 1) {
                scheduled.last().run()
            }
            nowMs += millis
        }
        handler.lifecycleProjectSmartProvider = { true }
        handler.lifecycleContentRootReadinessProvider = { _, targetKey ->
            when {
                fallbackInstalled -> InspectionHandler.LifecycleContentRootReadiness(
                    ready = true,
                    reason = "ready",
                    targetKey = targetKey,
                    contentRootCount = 1,
                    moduleCount = 1,
                    fallbackModuleCount = 1,
                    targetInsideContent = true,
                    contentRoots = listOf(targetKey),
                )
                nowMs < 1_200L -> InspectionHandler.LifecycleContentRootReadiness(
                    ready = true,
                    reason = "ready",
                    targetKey = targetKey,
                    contentRootCount = 1,
                    moduleCount = 1,
                    targetInsideContent = true,
                    contentRoots = listOf(targetKey),
                )
                else -> InspectionHandler.LifecycleContentRootReadiness(
                    ready = false,
                    reason = "no_content_roots",
                    targetKey = targetKey,
                )
            }
        }
        val localFileSystem = mockk<LocalFileSystem>()
        val targetRoot = mockk<VirtualFile>()
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.refreshAndFindFileByNioFile(tempDir.toRealPath()) } returns targetRoot
        handler.lifecycleFallbackContentRootCreator = { project, root ->
            assertSame(initializingProject, project)
            assertSame(targetRoot, root)
            fallbackCreateCount += 1
            fallbackInstalled = true
            true
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        try {
            val first = processGetRequest(lifecycleOpenUri(tempDir))
            scheduled.single().run()
            guardPolls.single().run()
            val second = processGetRequest(lifecycleOpenUri(tempDir))
            val secondBody = second.content().toString(Charsets.UTF_8)

            assertEquals(HttpResponseStatus.OK, first.status())
            assertEquals(HttpResponseStatus.OK, second.status())
            assertEquals(1, fallbackCreateCount)
            assertTrue(fallbackInstalled)
            assertTrue(secondBody.contains("\"status\": \"already_open\""))
            assertTrue(secondBody.contains("\"fallback_module_count\": 1"))
        } finally {
            unmockkStatic(LocalFileSystem::class)
        }
    }

    @Test
    fun `test lifecycle open keeps opening guard while project remains open but not initialized`() {
        val tempDir = Files.createTempDirectory("inspection-open-slow-initializing")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-slow-initializing"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        var inFlightResponseBody = ""
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        inFlightResponseBody = processGetRequest(lifecycleOpenUri(tempDir)).content().toString(Charsets.UTF_8)
        every { initializingProject.isInitialized } returns true
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(inFlightResponseBody.contains("\"reason\": \"already_opening\""))
        assertTrue(inFlightResponseBody.contains("\"opening_scheduled\": false"))
        assertTrue(secondBody.contains("\"status\": \"already_open\""))
    }

    @Test
    fun `test lifecycle open retries when project disappears before initialization`() {
        val tempDir = Files.createTempDirectory("inspection-open-disappears")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-disappears"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardSleep = {
            openProjects[0] = null
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        guardPolls.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(2, scheduled.size)
    }

    @Test
    fun `test lifecycle open keeps opening guard while returned project is never observed`() {
        val tempDir = Files.createTempDirectory("inspection-open-never-observed")
        every { mockProjectManager.openProjects } returns emptyArray()
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-never-observed"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        var nowMs = 1_000L
        var retryAfterOldNeverObservedTimeout = ""
        var scheduledAfterRetry = 0
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardTimeoutMs = 1_000
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis ->
            nowMs += millis
            if (nowMs >= 1_400L && retryAfterOldNeverObservedTimeout.isEmpty()) {
                retryAfterOldNeverObservedTimeout = processGetRequest(lifecycleOpenUri(tempDir)).content().toString(Charsets.UTF_8)
                scheduledAfterRetry = scheduled.size
            }
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val inFlight = processGetRequest(lifecycleOpenUri(tempDir)).content().toString(Charsets.UTF_8)
        guardPolls.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.CONFLICT, second.status())
        assertEquals(1, scheduledAfterRetry)
        assertEquals(1, scheduled.size)
        assertTrue(inFlight.contains("\"reason\": \"already_opening\""))
        assertTrue(retryAfterOldNeverObservedTimeout.contains("\"reason\": \"already_opening\""))
        assertTrue(retryAfterOldNeverObservedTimeout.contains("\"opening_scheduled\": false"))
        assertTrue(secondBody.contains("\"status\": \"failed\""))
        assertTrue(secondBody.contains("\"reason\": \"open_state_unknown\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
    }

    @Test
    fun `test lifecycle open remains unresolved when readiness misses stabilization deadline`() {
        val tempDir = Files.createTempDirectory("inspection-open-unstable-ready")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        val openedProject = mockProject(
            name = "UnstableReady",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        var nowMs = 1_000L
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardTimeoutMs = 400
        handler.lifecycleOpenRootStabilizationMs = 1_000
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis -> nowMs += millis }
        handler.lifecycleContentRootReadinessProvider = { _, targetKey ->
            InspectionHandler.LifecycleContentRootReadiness(
                ready = true,
                reason = "ready",
                targetKey = targetKey,
                contentRootCount = 1,
                sourceRootCount = 0,
                moduleCount = 1,
                targetInsideContent = true,
                contentRoots = listOf(targetKey),
            )
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = openedProject
            beforeInit(openedProject)
            openedProject
        }
        mockInspectionPrerequisites(openedProject)

        processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        guardPolls.single().run()
        val encodedPath = java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")
        val status = processGetRequest("/api/inspection/status?worktree_path=$encodedPath")
        val statusBody = status.content().toString(Charsets.UTF_8)
        val instanceId = projectInstanceId(openedProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=$encodedPath&project_instance_id=$instanceId&lease_id=test-open-lease"
        )
        val claimBody = claim.content().toString(Charsets.UTF_8)
        val unresolved = processGetRequest(lifecycleOpenUri(tempDir))
        val unresolvedBody = unresolved.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, status.status())
        assertTrue(statusBody.contains("\"ready\": false"))
        assertTrue(statusBody.contains("\"reason\": \"project_configuration_unstable\""))
        assertEquals(HttpResponseStatus.OK, claim.status())
        assertTrue(claimBody.contains("\"ready\": false"))
        assertTrue(claimBody.contains("\"reason\": \"project_configuration_unstable\""))
        assertEquals(HttpResponseStatus.CONFLICT, unresolved.status())
        assertTrue(unresolvedBody.contains("\"reason\": \"open_state_unknown\""))
        assertTrue(unresolvedBody.contains("\"ready\": false"))
        assertTrue(unresolvedBody.contains("\"reason\": \"project_configuration_unstable\""))
        assertFalse(unresolvedBody.contains("\"status\": \"already_open\""))
    }

    @Test
    fun `test lifecycle open retries after unresolved returned project is disposed`() {
        val tempDir = Files.createTempDirectory("inspection-open-unresolved-disposed")
        every { mockProjectManager.openProjects } returns emptyArray()
        var disposed = false
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } answers { disposed }
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-unresolved-disposed"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        var nowMs = 1_000L
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardTimeoutMs = 400
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis -> nowMs += millis }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(initializingProject)
            initializingProject
        }

        processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        guardPolls.single().run()
        val unknown = processGetRequest(lifecycleOpenUri(tempDir))
        disposed = true
        val retry = processGetRequest(lifecycleOpenUri(tempDir))

        assertEquals(HttpResponseStatus.CONFLICT, unknown.status())
        assertEquals(HttpResponseStatus.OK, retry.status())
        assertEquals(2, scheduled.size)
        assertTrue(unknown.content().toString(Charsets.UTF_8).contains("\"reason\": \"open_state_unknown\""))
    }

    @Test
    fun `test lifecycle open keeps unresolved guard when poller submission fails`() {
        val tempDir = Files.createTempDirectory("inspection-open-poller-submit-fails")
        every { mockProjectManager.openProjects } returns emptyArray()
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-poller-submit-fails"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } throws RejectedExecutionException("pool stopped")
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.CONFLICT, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(secondBody.contains("\"reason\": \"open_state_unknown\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
    }

    @Test
    fun `test lifecycle open reports unknown state at hard timeout when observed project stays unusable`() {
        val tempDir = Files.createTempDirectory("inspection-open-observed-timeout")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        var initialized = false
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } answers { initialized }
        every { initializingProject.name } returns "inspection-open-observed-timeout"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        var nowMs = 1_000L
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardTimeoutMs = 400
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis -> nowMs += millis }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.CONFLICT, second.status())
        assertEquals(1, scheduled.size)
        assertFalse(initialized)
        assertTrue(secondBody.contains("\"status\": \"failed\""))
        assertTrue(secondBody.contains("\"reason\": \"open_state_unknown\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
    }

    @Test
    fun `test lifecycle open keeps guard while observed project is not identifiable`() {
        val tempDir = Files.createTempDirectory("inspection-open-unidentifiable")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        every { mockProject.basePath } returns null
        every { mockProject.projectFilePath } returns null
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns true
        every { initializingProject.name } returns "inspection-open-unidentifiable"
        every { initializingProject.basePath } returns null
        every { initializingProject.projectFilePath } returns null
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        var nowMs = 1_000L
        var retryBeforeTimeoutBody = ""
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardTimeoutMs = 400
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis ->
            nowMs += millis
            retryBeforeTimeoutBody = processGetRequest(lifecycleOpenUri(tempDir)).content().toString(Charsets.UTF_8)
            nowMs = 1_400L
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(1, scheduled.size)
        scheduled.single().run()
        guardPolls.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.CONFLICT, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(retryBeforeTimeoutBody.contains("\"reason\": \"already_opening\""))
        assertTrue(retryBeforeTimeoutBody.contains("\"opening_scheduled\": false"))
        assertTrue(secondBody.contains("\"reason\": \"open_state_unknown\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
    }

    @Test
    fun `test lifecycle open releases opening guard when poller aborts`() {
        val tempDir = Files.createTempDirectory("inspection-open-aborted-poller")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-aborted-poller"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(secondBody.contains("\"status\": \"opening\""))
        assertTrue(secondBody.contains("\"reason\": \"already_opening\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
    }

    @Test
    fun `test lifecycle open coalesces symlink aliases`() {
        val tempDir = Files.createTempDirectory("inspection-open-real")
        val symlink = tempDir.parent.resolve("inspection-open-link-${System.nanoTime()}")
        try {
            Files.createSymbolicLink(symlink, tempDir)
        } catch (_: UnsupportedOperationException) {
            return
        }
        every { mockProjectManager.openProjects } returns emptyArray()
        val scheduled = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(mockProject)
            mockProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        val second = processGetRequest(lifecycleOpenUri(symlink))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(secondBody.contains("\"reason\": \"already_opening\""))

        Files.deleteIfExists(symlink)
    }

    @Test
    fun `test lifecycle open detects already open symlink alias`() {
        val realPath = Files.createTempDirectory("inspection-open-real-existing")
        val symlink = realPath.parent.resolve("inspection-open-existing-link-${System.nanoTime()}")
        try {
            Files.createSymbolicLink(symlink, realPath)
        } catch (_: UnsupportedOperationException) {
            return
        }
        every { mockProject.basePath } returns realPath.toString()
        every { mockProject.projectFilePath } returns realPath.resolve(".idea/misc.xml").toString()
        var scheduled = false
        every { mockApplication.invokeLater(any()) } answers {
            scheduled = true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(symlink.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
        assertFalse(scheduled)

        Files.deleteIfExists(symlink)
    }

    @Test
    fun `test lifecycle open normalizes home-relative path for already open project`() {
        val home = System.getProperty("user.home")
        val projectPath = Paths.get(home, "repo-open-existing").toString()
        every { mockProject.basePath } returns projectPath
        every { mockProject.projectFilePath } returns Paths.get(projectPath, ".idea/misc.xml").toString()

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode("~/repo-open-existing", "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
    }

    @Test
    fun `test route endpoint rejects missing project instance id without fallback`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"

        val response = processGetRequest(
            "/api/inspection/route?worktree_path=/repo/app&project_instance_id=old-instance"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("does not match the resolved route"))
        assertFalse(body.contains("\"status\": \"resolved\""))
    }

    @Test
    fun `test route endpoint rejects project instance id that conflicts with path selector`() {
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

        val response = processGetRequest(
            "/api/inspection/route?worktree_path=/repo/main&project_instance_id=${projectInstanceId(worktreeProject)}"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("does not match the resolved route"))
        assertFalse(body.contains("\"project_key\": \"path:/repo/worktree\""))
    }

    @Test
    fun `test lifecycle close rejects mismatched close token`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns true
        var closeCalls = 0
        handler.forceCloseProject = { _, _ ->
            closeCalls++
            every { mockProjectManager.openProjects } returns emptyArray()
            true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=wrong"
        )
        val validResponse = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.FORBIDDEN, response.status())
        assertTrue(body.contains("\"status\": \"skipped\""))
        assertTrue(body.contains("\"reason\": \"token_mismatch\""))
        assertEquals(HttpResponseStatus.OK, validResponse.status())
        assertTrue(validResponse.content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
        assertEquals(1, closeCalls)
    }

    @Test
    fun `test lifecycle close releases the HTTP event loop before close work`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)

        val scheduled = AtomicReference<Runnable?>()
        handler.lifecycleCloseExecutor = { task -> scheduled.set(task) }
        every { mockApplication.isDispatchThread } returns true
        var closed = false
        handler.forceCloseProject = { _, _ ->
            closed = true
            true
        }
        every { mockProjectManager.openProjects } answers { if (closed) emptyArray() else arrayOf(mockProject) }
        val uri = "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        val urlDecoder = QueryStringDecoder(uri)
        val request = mockk<FullHttpRequest>()
        val context = mockk<ChannelHandlerContext>()
        val responses = mutableListOf<FullHttpResponse>()
        every { request.uri() } returns uri
        every { context.writeAndFlush(any()) } answers {
            responses.add(firstArg())
            mockk(relaxed = true)
        }

        assertTrue(handler.process(urlDecoder, request, context))
        assertTrue(responses.isEmpty())
        assertNotNull(scheduled.get())

        scheduled.get()?.run()

        assertEquals(1, responses.size)
        assertEquals(HttpResponseStatus.OK, responses.single().status())
        assertTrue(responses.single().content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
    }

    @Test
    fun `test lifecycle close preserves claim while inspection is running`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        setInspectionRunState(
            projectKey(mockProject),
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = true,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        val closeCalls = AtomicInteger(0)
        handler.forceCloseProject = { _, _ ->
            closeCalls.incrementAndGet()
            true
        }

        val blocked = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token&lease_id=test-lease"
        )

        assertEquals(HttpResponseStatus.CONFLICT, blocked.status())
        assertTrue(blocked.content().toString(Charsets.UTF_8).contains("\"reason\": \"inspection_in_progress\""))
        assertTrue(blocked.content().toString(Charsets.UTF_8).contains("\"inspection_run_id\": 7"))
        assertEquals(0, closeCalls.get())

        setInspectionRunState(
            projectKey(mockProject),
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        every { mockProjectManager.openProjects } answers {
            if (closeCalls.get() == 0) arrayOf(mockProject) else emptyArray()
        }
        every { mockApplication.isDispatchThread } returns true

        val closed = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token&lease_id=test-lease"
        )

        assertEquals(HttpResponseStatus.OK, closed.status())
        assertTrue(closed.content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun `test lifecycle close uses claimed project instance even when route selectors drift`() {
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
        val instanceId = projectInstanceId(worktreeProject)
        registerLifecycleOpenOwnership(worktreeProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/worktree&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns true
        var closedProject: Project? = null
        handler.forceCloseProject = { project, _ ->
            closedProject = project
            every { mockProjectManager.openProjects } returns arrayOf(mainProject)
            true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?project_key=${projectKey(mainProject)}&project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"closed\""))
        assertSame(worktreeProject, closedProject)
    }

    @Test
    fun `test lifecycle close consumes close token before close work under concurrency`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns true
        val enteredClose = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeCalls = AtomicInteger(0)
        handler.forceCloseProject = { _, _ ->
            closeCalls.incrementAndGet()
            enteredClose.countDown()
            if (releaseClose.await(5, TimeUnit.SECONDS)) {
                every { mockProjectManager.openProjects } returns emptyArray()
                true
            } else {
                false
            }
        }

        val firstResponse = AtomicReference<FullHttpResponse>()
        val firstFailure = AtomicReference<Throwable>()
        val firstThread = Thread {
            try {
                firstResponse.set(
                    processGetRequest(
                        "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token"
                    )
                )
            } catch (t: Throwable) {
                firstFailure.set(t)
            }
        }
        firstThread.start()
        assertTrue(enteredClose.await(5, TimeUnit.SECONDS))

        val second = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token"
        )
        releaseClose.countDown()
        firstThread.join(5_000)
        firstFailure.get()?.let { throw it }
        val first = firstResponse.get() ?: fail("first close response was not captured")

        assertEquals(HttpResponseStatus.OK, first.status())
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
        assertEquals(HttpResponseStatus.OK, second.status())
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"reason\": \"not_claimed\""))
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun `test lifecycle close consumes stale-session lease without closing project`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        lifecycleLeases()[instanceId] = InspectionProjectLease(
            closeToken = "stale-token",
            leaseId = "test-lease",
            projectKey = projectKey(mockProject),
            projectInstanceId = instanceId,
            basePath = "/repo/app",
            sessionId = "stale-session",
            claimedAtMs = 1L,
            project = mockProject,
        )
        val closeCalls = AtomicInteger(0)
        handler.forceCloseProject = { _, _ ->
            closeCalls.incrementAndGet()
            true
        }

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=stale-token"
        )
        val second = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=stale-token"
        )

        assertEquals(HttpResponseStatus.CONFLICT, first.status())
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"reason\": \"session_drift\""))
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"reason\": \"not_claimed\""))
        assertEquals(0, closeCalls.get())
    }

    @Test
    fun `test lifecycle close consumes valid token when request session id drifted`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        val closeCalls = AtomicInteger(0)
        handler.forceCloseProject = { _, _ ->
            closeCalls.incrementAndGet()
            true
        }

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token&session_id=stale-session"
        )
        val second = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token&session_id=stale-session"
        )

        assertEquals(HttpResponseStatus.CONFLICT, first.status())
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"reason\": \"session_drift\""))
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"reason\": \"not_claimed\""))
        assertEquals(0, closeCalls.get())
    }

    @Test
    fun `test lifecycle close evicts lease when claimed project is missing`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockProjectManager.openProjects } returns emptyArray()

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val second = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )

        assertEquals(HttpResponseStatus.OK, first.status())
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"reason\": \"route_missing\""))
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"reason\": \"not_claimed\""))
    }

    @Test
    fun `test lifecycle close verifies closed project after false close result`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns true
        handler.forceCloseProject = { _, _ ->
            every { mockProjectManager.openProjects } returns emptyArray()
            false
        }

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token&client_run_id=dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        )
        val second = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val body = first.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertTrue(body.contains("\"status\": \"closed\""))
        assertTrue(body.contains("\"force_close_returned\": false"))
        assertTrue(body.contains("\"closed_verified\": true"))
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"reason\": \"not_claimed\""))
    }

    @Test
    fun `test lifecycle close retries no-save fallback after transient refusal`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns false
        every { mockApplication.invokeAndWait(any()) } answers { firstArg<Runnable>().run() }
        var nowMs = 0L
        handler.closeVerificationTimeoutMs = 300
        handler.closeVerificationNow = { nowMs }
        handler.closeVerificationSleep = { millis -> nowMs += millis }
        val saveModes = mutableListOf<Boolean>()
        handler.forceCloseProject = { _, save ->
            saveModes.add(save)
            if (!save) {
                every { mockProjectManager.openProjects } returns emptyArray()
                true
            } else {
                false
            }
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"closed\""))
        assertEquals(listOf(true, false), saveModes)
        assertTrue(body.contains("\"attempt\": 2"))
        assertTrue(body.contains("\"save\": false"))
    }

    @Test
    fun `test lifecycle close waits beyond short fixed window for slow verified close`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns false
        every { mockApplication.invokeAndWait(any()) } answers { firstArg<Runnable>().run() }
        handler.closeVerificationTimeoutMs = 2_000
        handler.closeVerificationPollMs = 100
        var nowMs = 0L
        handler.closeVerificationNow = { nowMs }
        handler.closeVerificationSleep = { millis -> nowMs += millis }
        handler.forceCloseProject = { _, _ -> true }
        every { mockProjectManager.openProjects } answers {
            if (nowMs >= 1_200) emptyArray() else arrayOf(mockProject)
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"closed\""))
        assertTrue(body.contains("\"closed_verified\": true"))
        assertTrue(nowMs >= 1_200)
    }

    @Test
    fun `test lifecycle close gives no-save retry a fresh verification window`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns false
        every { mockApplication.invokeAndWait(any()) } answers { firstArg<Runnable>().run() }
        handler.closeVerificationTimeoutMs = 300
        handler.closeVerificationPollMs = 100
        var nowMs = 0L
        var noSaveStartMs: Long? = null
        handler.closeVerificationNow = { nowMs }
        handler.closeVerificationSleep = { millis -> nowMs += millis }
        val saveModes = mutableListOf<Boolean>()
        handler.forceCloseProject = { _, save ->
            saveModes.add(save)
            if (save) {
                false
            } else {
                noSaveStartMs = nowMs
                true
            }
        }
        every { mockProjectManager.openProjects } answers {
            val retryStart = noSaveStartMs
            if (retryStart != null && nowMs - retryStart >= 200) emptyArray() else arrayOf(mockProject)
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(listOf(true, false), saveModes)
        assertTrue(body.contains("\"status\": \"closed\""))
        assertTrue(body.contains("\"attempt\": 2"))
        assertTrue(body.contains("\"closed_verified\": true"))
    }

    @Test
    fun `test lifecycle close does not poll for close verification on dispatch thread`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns true
        var sleepCount = 0
        handler.closeVerificationSleep = { sleepCount++ }
        handler.forceCloseProject = { _, _ -> false }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.CONFLICT, response.status())
        assertTrue(body.contains("\"reason\": \"close_failed\""))
        assertEquals(0, sleepCount)
    }

    @Test
    fun `test lifecycle close preserves token when close fails after retries`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns false
        every { mockApplication.invokeAndWait(any()) } answers { firstArg<Runnable>().run() }
        var nowMs = 0L
        handler.closeVerificationTimeoutMs = 300
        handler.closeVerificationNow = { nowMs }
        handler.closeVerificationSleep = { millis -> nowMs += millis }
        val saveModes = mutableListOf<Boolean>()
        handler.forceCloseProject = { _, save ->
            saveModes.add(save)
            false
        }

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token&client_run_id=dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        )
        var closed = false
        handler.forceCloseProject = { _, save ->
            saveModes.add(save)
            closed = true
            true
        }
        every { mockProjectManager.openProjects } answers { if (closed) emptyArray() else arrayOf(mockProject) }
        val second = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )

        assertEquals(HttpResponseStatus.CONFLICT, first.status())
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"reason\": \"close_failed\""))
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"classification\": \"legitimate_fail_closed\""))
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"phase\": \"lifecycle_close\""))
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"client_run_id\": \"dddddddd-dddd-4ddd-8ddd-dddddddddddd\""))
        assertEquals(HttpResponseStatus.OK, second.status())
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
        assertEquals(listOf(true, false, false, true), saveModes)
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"closed_verified\": false"))
    }

    @Test
    fun `test lifecycle close preserves token when verification throws`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns false
        every { mockApplication.invokeAndWait(any()) } answers { firstArg<Runnable>().run() }
        handler.forceCloseProject = { _, _ -> true }
        handler.closeVerificationSleep = { throw IllegalStateException("verification failed") }

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token&client_run_id=eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        )

        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, first.status())
        val firstBody = first.content().toString(Charsets.UTF_8)
        assertTrue(firstBody.contains("\"inspection_verdict_reason\": \"inspection_api_http_error\""), firstBody)
        assertTrue(firstBody.contains("\"phase\": \"lifecycle_close\""), firstBody)
        assertFalse(firstBody.contains("verification failed"), firstBody)
        assertFalse(firstBody.contains(requireNotNull(token)), firstBody)

        var closed = false
        handler.forceCloseProject = { _, _ ->
            closed = true
            true
        }
        handler.closeVerificationSleep = {}
        every { mockProjectManager.openProjects } answers { if (closed) emptyArray() else arrayOf(mockProject) }

        val second = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )

        assertEquals(HttpResponseStatus.OK, second.status())
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
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
        every { mockVirtualFileManager.syncRefresh() } returns 0L
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
    fun `test route endpoint rejects ambiguous project names`() {
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

        val response = processGetRequest("/api/inspection/route?project=Shared")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("Multiple open projects matched this request"))
    }

    @Test
    fun `test route endpoint rejects duplicate project names even when paths differ in specificity`() {
        val parentProject = mockProject(
            name = "Shared",
            basePath = "/repo",
            projectFilePath = "/repo/.idea/misc.xml",
        )
        val childProject = mockProject(
            name = "Shared",
            basePath = "/repo/packages/app",
            projectFilePath = "/repo/packages/app/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(parentProject, childProject)

        val response = processGetRequest("/api/inspection/route?project=Shared")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("Multiple open projects matched this request"))
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
    fun `test getCurrentProject uses nested path selector scoring`() {
        val parentProject = mockProject(
            name = "Parent",
            basePath = "/repo",
            projectFilePath = "/repo/.idea/misc.xml",
        )
        val childProject = mockProject(
            name = "Child",
            basePath = "/repo/packages/app",
            projectFilePath = "/repo/packages/app/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(parentProject, childProject)

        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true

        val result = method.invoke(handler, "/repo/packages/app/src") as Project?

        assertNotNull(result)
        assertEquals("Child", result?.name)
    }

    @Test
    fun `test getCurrentProject prefers exact project file path over longer containing base path`() {
        val exactProjectFileMatch = mockProject(
            name = "ExactProjectFile",
            basePath = "/repo/app",
            projectFilePath = "/repo/app/.idea/misc.xml",
        )
        val longerContainingBasePath = mockProject(
            name = "ContainingBasePath",
            basePath = "/repo/app/.idea",
            projectFilePath = "/repo/app/.idea/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(exactProjectFileMatch, longerContainingBasePath)

        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true

        val result = method.invoke(handler, "/repo/app/.idea/misc.xml") as Project?

        assertNotNull(result)
        assertEquals("ExactProjectFile", result?.name)
    }

    @Test
    fun `test route endpoint prefers exact project file path over longer containing base path`() {
        val exactProjectFileMatch = mockProject(
            name = "ExactProjectFile",
            basePath = "/repo/app",
            projectFilePath = "/repo/app/.idea/misc.xml",
        )
        val longerContainingBasePath = mockProject(
            name = "ContainingBasePath",
            basePath = "/repo/app/.idea",
            projectFilePath = "/repo/app/.idea/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(exactProjectFileMatch, longerContainingBasePath)

        val response = processGetRequest("/api/inspection/route?project_path=/repo/app/.idea/misc.xml")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"ExactProjectFile\""))
        assertFalse(body.contains("Multiple open projects matched this request"))
    }

    @Test
    fun `test route base url uses numeric loopback`() {
        val method = InspectionHandler::class.java.getDeclaredMethod("routeBaseUrl", Any::class.java)
        method.isAccessible = true

        val result = method.invoke(handler, 63342)

        val expected = "http://" + "127.0.0.1" + ":63342" + "/api/" + "inspection"
        assertEquals(expected, result)
    }

    @Test
    fun `test route endpoint exposes effective base path from project file when base path is missing`() {
        val tempDir = Files.createTempDirectory("inspection-route-file-root")
        every { mockProject.basePath } returns null
        every { mockProject.projectFilePath } returns tempDir.resolve(".idea/modules.xml").toString()

        val response = processGetRequest(
            "/api/inspection/route?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"base_path\": \"$tempDir\""))
        assertTrue(body.contains("\"project_file_path\": \"${tempDir.resolve(".idea/modules.xml")}\""))
    }

    @Test
    fun `test route endpoint prefers nested project file root over containing parent`() {
        val tempDir = Files.createTempDirectory("inspection-route-file-root-nested")
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

        val response = processGetRequest(
            "/api/inspection/route?cwd=${java.net.URLEncoder.encode(nestedPath.resolve("src/main").toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"Child\""))
        assertTrue(body.contains("\"base_path\": \"$nestedPath\""))
        assertFalse(body.contains("Multiple open projects matched this request"))
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
        every { mockVirtualFileManager.syncRefresh() } returns 0L
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

    @Test
    fun `test getCurrentProject still falls back when focus lookup throws`() {
        every { IdeFocusManager.getGlobalInstance() } throws IllegalStateException("focus unavailable")

        val mockDataManager = mockk<DataManager>()
        val promise: Promise<DataContext> = rejectedPromise("No context")
        every { mockDataManager.dataContextFromFocusAsync } returns promise
        every { DataManager.getInstance() } returns mockDataManager

        every { mockWindowManager.suggestParentWindow(mockProject) } returns null

        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true

        val result = method.invoke(handler, null) as Project?

        assertNotNull(result)
        assertEquals("TestProject", result?.name)
    }
    
    @Test
    fun `test resolveProjectSelector returns correct project`() {
        val mockProject1 = mockk<Project>()
        val mockProject2 = mockk<Project>()
        
        every { mockProject1.isDefault } returns false
        every { mockProject1.isDisposed } returns false
        every { mockProject1.isInitialized } returns true
        every { mockProject1.name } returns "TargetProject"
        
        every { mockProject2.isDefault } returns false
        every { mockProject2.isDisposed } returns false
        every { mockProject2.isInitialized } returns true
        every { mockProject2.name } returns "OtherProject"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject2, mockProject1)
        
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("resolveProjectSelector", String::class.java)
        method.isAccessible = true
        
        val result = method.invoke(handler, "TargetProject") as Project?
        assertNotNull(result)
        assertEquals("TargetProject", result?.name)
    }

    private fun processTriggerRequest(uri: String): FullHttpResponse {
        return processGetRequest(uri)
    }

    private fun lifecycleOpenUri(path: Path, leaseId: String = "test-open-lease"): String {
        return lifecycleOpenUri(path.toString(), leaseId)
    }

    private fun lifecycleOpenUri(path: String, leaseId: String = "test-open-lease"): String {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val encodedSession = java.net.URLEncoder.encode(InspectionIdeSession.sessionId, "UTF-8")
        val encodedLeaseId = java.net.URLEncoder.encode(leaseId, "UTF-8")
        return "/api/inspection/lifecycle/open?worktree_path=$encodedPath&session_id=$encodedSession&lease_id=$encodedLeaseId"
    }

    private fun runPooledTasksInline() {
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun lifecycleLeases(): MutableMap<String, InspectionProjectLease> {
        val field = InspectionHandler::class.java.getDeclaredField("leasesByProjectInstance")
        field.isAccessible = true
        return field.get(handler) as MutableMap<String, InspectionProjectLease>
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerLifecycleOpenOwnership(project: Project, leaseId: String = "test-lease") {
        val field = InspectionHandler::class.java.getDeclaredField("lifecycleOpenOwnershipByProjectInstance")
        field.isAccessible = true
        val ownership = field.get(handler) as MutableMap<String, InspectionHandler.LifecycleOpenOwnership>
        val targetKey = Paths.get(requireNotNull(project.basePath)).normalize().toAbsolutePath().toString()
        ownership[projectInstanceId(project)] = InspectionHandler.LifecycleOpenOwnership(leaseId, targetKey, project)
    }

    private fun semanticCoverageGapDiagnostic(): Map<String, Any?> = mapOf(
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

    private fun semanticCoverageTruncatedDiagnostic(): Map<String, Any?> = mapOf(
        "scope_file_resolved_count" to 1,
        "scope_file_diagnostic_count" to 0,
        "scope_file_diagnostics_truncated" to true,
        "scope_file_diagnostics_complete" to false,
        "scope_file_semantic_evidence_complete" to false,
        "scope_file_diagnostics" to emptyList<Map<String, Any?>>(),
    )

    private fun processGetRequest(uri: String): FullHttpResponse {
        val urlDecoder = QueryStringDecoder(uri)
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        val responseSlot = slot<Any>()

        every { mockRequest.uri() } returns uri
        every { mockContext.writeAndFlush(capture(responseSlot)) } returns mockk(relaxed = true)

        val result = handler.process(urlDecoder, mockRequest, mockContext)

        assertTrue(result)
        return responseSlot.captured as FullHttpResponse
    }

    private fun buildInspectionStatus(): MutableMap<String, Any> {
        val method = InspectionHandler::class.java.getDeclaredMethod("buildInspectionStatus", Project::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(handler, mockProject) as MutableMap<String, Any>
    }

    private fun invokeTargetedAnalysisScopeResolver(methodName: String, virtualFile: VirtualFile): Any? {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            methodName,
            Project::class.java,
            VirtualFile::class.java,
        )
        method.isAccessible = true
        return method.invoke(handler, mockProject, virtualFile)
    }

    private fun invokeActiveEditorFileResolver(): VirtualFile? {
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "resolveActiveEditorFile",
            Project::class.java,
        )
        method.isAccessible = true
        return method.invoke(handler, mockProject) as? VirtualFile
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeDirectoryPsiFilesForInspectionEngine(): List<PsiFile> {
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

    private fun publishInspectionSnapshot(
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

    private fun qualifyProjectAnalysisSnapshot(
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

    private fun pythonSdkReadiness(
        ready: Boolean,
        localInterpreterCandidate: Boolean,
    ): InspectionProjectAnalysisReadiness {
        return InspectionProjectAnalysisReadiness(
            required = true,
            ready = ready,
            reason = if (ready) "ready" else "python_sdk_missing",
            pythonFileCount = 1,
            pythonSdkCount = if (ready) 1 else 0,
            missingSdkFileCount = if (ready) 0 else 1,
            localPythonInterpreterCandidate = localInterpreterCandidate,
        )
    }

    private fun projectInputsFingerprint(
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

    private fun changedFilesSnapshot(
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

    private fun changedFileProblem(
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

    private fun mockChangedFiles(paths: List<String>): ChangeListManager {
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

    private fun populateInspectionProfileElement(
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

    private class FakeInspectionProjectContentTracker(
        var changed: Boolean = false,
    ) : InspectionProjectContentTracker {
        var closed: Boolean = false
        var beforeRunIfUnchanged: (() -> Unit)? = null

        override fun hasChanges(): Boolean = changed

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

    private fun mockExtractor(problems: List<Map<String, Any>>) {
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblems(mockProject) } returns problems
        every { extractor.extractAllProblemsWithStatus(mockProject) } returns ProblemExtractionResult(
            problems = problems,
            succeeded = true,
            source = ProblemExtractionSource.INSPECTION_RESULTS,
        )
        enhancedTreeExtractorFactory = { extractor }
    }

    private fun mockExtractorFailure() {
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblems(mockProject) } throws IllegalStateException("extractor failed")
        every { extractor.extractAllProblemsWithStatus(mockProject) } returns ProblemExtractionResult(
            problems = emptyList(),
            succeeded = false,
        )
        enhancedTreeExtractorFactory = { extractor }
    }

    private fun mockIncludedLocalFile(): VirtualFile {
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

    private fun getFileInspectionProblems(files: List<String>): String {
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

    private fun setInspectionRunState(projectKey: String, state: InspectionRunState) {
        val field = InspectionHandler::class.java.getDeclaredField("inspectionRunStatesByProject")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val states = field.get(handler) as MutableMap<String, InspectionRunState>
        states[projectKey] = state
    }

    private fun inspectionRunState(projectKey: String): InspectionRunState? {
        val field = InspectionHandler::class.java.getDeclaredField("inspectionRunStatesByProject")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val states = field.get(handler) as Map<String, InspectionRunState>
        return states[projectKey]
    }

    private fun setInspectionRunControl(projectKey: String, control: InspectionRunControl) {
        val field = InspectionHandler::class.java.getDeclaredField("inspectionRunControlsByProject")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val controls = field.get(handler) as MutableMap<String, InspectionRunControl>
        controls[projectKey] = control
    }

    private fun mockProject(name: String, basePath: String?, projectFilePath: String): Project {
        val project = mockk<Project>()
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.isInitialized } returns true
        every { project.name } returns name
        every { project.basePath } returns basePath
        every { project.projectFilePath } returns projectFilePath
        return project
    }

    private fun mockInspectionPrerequisites(project: Project) {
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
    }
}
