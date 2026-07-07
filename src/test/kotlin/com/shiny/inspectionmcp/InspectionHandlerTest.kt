package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
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
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFileManager
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
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.channel.ChannelHandlerContext
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise
import org.jetbrains.concurrency.rejectedPromise
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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
    
    @BeforeEach
    fun setup() {
        handler = InspectionHandler()
        handler.trustProjectPath = {}
        handler.inspectionRunExpirationMs = 300000L
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

        val response = processTriggerRequest("/api/inspection/trigger?scope=changed_files&include_unversioned=false")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"scope\": \"changed_files\""))
        verify(exactly = 1) { mockApplication.executeOnPooledThread(any<Runnable>()) }
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
        val status = buildInspectionStatus()
        assertEquals("clean_confirmed", status["snapshot_outcome"])
        assertEquals("empty_changed_files", status["results_source"])
        assertEquals(false, status["capture_incomplete"])
        assertEquals(false, status["results_may_be_stale"])
        assertEquals(0, status["total_problems"])
    }

    @Test
    fun `test explicit missing inspection profile publishes capture incomplete snapshot`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockVirtualFileManager.syncRefresh() } returns 0L
        every { mockProfileManager.profiles } returns emptyList()
        every { mockProfileManager.getProfile("RedLane") } returns mockProfile
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        val response = processTriggerRequest("/api/inspection/trigger?profile=RedLane")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"profile\": \"RedLane\""))
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
    fun `test explicit inspection profile with unreadable profile list is unknown`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockVirtualFileManager.syncRefresh() } returns 0L
        every { mockProfileManager.profiles } throws IllegalStateException("profiles unavailable")
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        val response = processTriggerRequest("/api/inspection/trigger?profile=RedLane")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"profile\": \"RedLane\""))
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
        val status = buildInspectionStatus()
        assertEquals("capture_incomplete", status["snapshot_outcome"])
        assertEquals("profile_resolution", status["results_source"])
        assertEquals("profile_resolution_error", status["capture_incomplete_reason"])
        assertEquals("UNKNOWN", status["inspection_verdict"])
        assertEquals("profile_resolution_error", status["inspection_verdict_reason"])
        @Suppress("UNCHECKED_CAST")
        val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
        assertEquals("RedLane", diagnostic["profile_requested"])
        assertEquals(true, diagnostic["profile_unverified"])
        assertEquals(false, diagnostic["profile_list_readable"])
        assertEquals("profile_list_unreadable", diagnostic["exit_reason"])
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
    fun `test problems endpoint applies requested files scope to cached snapshot`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
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
    fun `test problems endpoint does not refresh project state before reading snapshot`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
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
    fun `test same run non-empty snapshot survives psi churn`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
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

        assertTrue(body.contains("\"status\": \"results_available\""))
        assertFalse(body.contains("\"status\": \"stale_results\""))
        assertTrue(body.contains("\"total_problems\": 1"))
        assertTrue(body.contains("included problem"))
        assertFalse(body.contains("excluded problem"))
    }

    @Test
    fun `test expired inspection run no longer reports in progress`() {
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

        assertEquals(false, status["inspection_in_progress"])
        assertEquals(true, status["inspection_run_expired"])
        assertEquals("capture_incomplete", status["snapshot_outcome"])
    }

    @Test
    fun `test completed scoped run with empty extraction proves clean status without snapshot`() {
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

        assertEquals(true, status["has_inspection_results"])
        assertEquals(true, status["clean_inspection"])
        assertEquals(0, status["total_problems"])
        assertEquals(true, status["scoped_clean_extraction_succeeded"])
        assertEquals(true, status["scoped_clean_matcher_available"])
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
        assertEquals(true, status["scoped_clean_matcher_available"])
        assertEquals(false, status["scoped_clean_extraction_succeeded"])
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
    fun `test wait endpoint executes on pooled thread`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        runPooledTasksInline()
        mockInspectionPrerequisites(mockProject)

        val response = processGetRequest("/api/inspection/wait?timeout_ms=1000&poll_ms=200")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"completion_reason\":"))
        verify(exactly = 1) { mockApplication.executeOnPooledThread(any<Runnable>()) }
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
            java.lang.Long::class.java,
            java.lang.Long::class.java,
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
    fun `test route endpoint reports session drift as conflict`() {
        val response = processGetRequest("/api/inspection/route?session_id=old-session")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.CONFLICT, response.status())
        assertTrue(body.contains("\"session_drift\": true"))
        assertTrue(body.contains("\"inspection_verdict\": \"UNKNOWN\""))
        assertTrue(body.contains("\"inspection_verdict_reason\": \"session_drift\""))
        assertTrue(body.contains("\"expected_session_id\": \"old-session\""))
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

        val response = processGetRequest("/api/inspection/route?worktree_path=/repo/packages/app/src")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"Child\""))
        assertFalse(body.contains("Multiple open projects matched this request"))
    }

    @Test
    fun `test route endpoint includes project instance id`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"

        val response = processGetRequest("/api/inspection/route?worktree_path=/repo/app")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_instance_id\""))
    }

    @Test
    fun `test lifecycle claim returns close token for exact project instance`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)

        val response = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"claimed\""))
        assertTrue(body.contains("\"close_token\""))
        assertTrue(body.contains("\"lease_id\": \"test-lease\""))
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
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path ->
            openedPath = path
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(tempDir))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opened\": false"))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertTrue(body.contains(tempDir.toString()))
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
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
        handler.openProjectPath = { path: Path ->
            events += "open"
            openedPath = path
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
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path ->
            openedPath = path
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
        handler.openProjectPath = { path: Path ->
            openedPath = path
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
        handler.openProjectPath = { path: Path ->
            openedPath = path
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
        handler.openProjectPath = { path: Path ->
            openedPath = path
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
        handler.openProjectPath = { path: Path ->
            openedPath = path
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(nestedIdeaFile))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
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
        handler.openProjectPath = { path: Path ->
            openedPath = path
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
        handler.openProjectPath = { path: Path ->
            openedPath = path
            mockProject(
                name = "Feature",
                basePath = linkedWorktree.toString(),
                projectFilePath = linkedWorktree.resolve(".idea/misc.xml").toString(),
            )
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
        handler.openProjectPath = { null }

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
        val guardPolls = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.openProjectPath = {
            openProjects[0] = initializingProject
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)
        every { initializingProject.isInitialized } returns true
        guardPolls.single().run()
        val third = processGetRequest(lifecycleOpenUri(tempDir))
        val thirdBody = third.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(1, scheduled.size)
        assertEquals(1, guardPolls.size)
        assertTrue(secondBody.contains("\"reason\": \"already_opening\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
        assertEquals(HttpResponseStatus.OK, third.status())
        assertTrue(thirdBody.contains("\"status\": \"already_open\""))
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
        val guardPolls = mutableListOf<Runnable>()
        var inFlightResponseBody = ""
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.openProjectPath = {
            openProjects[0] = initializingProject
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        inFlightResponseBody = processGetRequest(lifecycleOpenUri(tempDir)).content().toString(Charsets.UTF_8)
        every { initializingProject.isInitialized } returns true
        guardPolls.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(1, scheduled.size)
        assertEquals(1, guardPolls.size)
        assertTrue(inFlightResponseBody.contains("\"reason\": \"already_opening\""))
        assertTrue(inFlightResponseBody.contains("\"opening_scheduled\": false"))
        assertTrue(secondBody.contains("\"status\": \"already_open\""))
    }

    @Test
    fun `test lifecycle open releases opening guard when project disappears before initialization`() {
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
        handler.openProjectPath = {
            openProjects[0] = initializingProject
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
        handler.openProjectPath = { mockProject }

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
        processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        )

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=wrong"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.FORBIDDEN, response.status())
        assertTrue(body.contains("\"status\": \"skipped\""))
        assertTrue(body.contains("\"reason\": \"token_mismatch\""))
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
    fun `test lifecycle close evicts lease when claimed project is missing`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
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
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
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
    fun `test lifecycle close keeps lease when close fails after retries`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
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
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val second = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )

        assertEquals(HttpResponseStatus.CONFLICT, first.status())
        assertEquals(HttpResponseStatus.CONFLICT, second.status())
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"reason\": \"close_failed\""))
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"reason\": \"close_failed\""))
        assertEquals(listOf(true, false, false, true, false, false), saveModes)
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"closed_verified\": false"))
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
            "/api/inspection/route?worktree_path=${java.net.URLEncoder.encode(nestedPath.resolve("src/main").toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"Child\""))
        assertTrue(body.contains("\"base_path\": \"$nestedPath\""))
        assertFalse(body.contains("Multiple open projects matched this request"))
    }

    @Test
    fun `test trigger endpoint prefers nested project file root over containing parent`() {
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
            "/api/inspection/trigger?worktree_path=${java.net.URLEncoder.encode(nestedPath.resolve("src/main").toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"Child\""))
        assertTrue(body.contains("\"base_path\": \"$nestedPath\""))
        assertFalse(body.contains("Multiple open projects matched this request"))
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

    private fun lifecycleOpenUri(path: Path): String {
        return lifecycleOpenUri(path.toString())
    }

    private fun lifecycleOpenUri(path: String): String {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val encodedSession = java.net.URLEncoder.encode(InspectionIdeSession.sessionId, "UTF-8")
        return "/api/inspection/lifecycle/open?worktree_path=$encodedPath&session_id=$encodedSession"
    }

    private fun runPooledTasksInline() {
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
    }

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

    private fun mockExtractor(problems: List<Map<String, Any>>) {
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblems(mockProject) } returns problems
        every { extractor.extractAllProblemsWithStatus(mockProject) } returns ProblemExtractionResult(
            problems = problems,
            succeeded = true,
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
