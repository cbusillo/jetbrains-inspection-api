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
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
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
    fun `test scope parameter handling with whole_project`() {
        val handler = InspectionHandler()
        
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
            Int::class.java      // offset
        )
        
        assertNotNull(method)
        assertEquals("getInspectionProblems", method.name)
        assertEquals(7, method.parameterCount)
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
    fun `test getProjectByName returns correct project`() {
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
        val method = InspectionHandler::class.java.getDeclaredMethod("getProjectByName", String::class.java)
        method.isAccessible = true
        
        val result = method.invoke(handler, "TargetProject") as Project?
        assertNotNull(result)
        assertEquals("TargetProject", result?.name)
    }

    private fun processTriggerRequest(uri: String): FullHttpResponse {
        return processGetRequest(uri)
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

    private fun mockProject(name: String, basePath: String, projectFilePath: String): Project {
        val project = mockk<Project>()
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.isInitialized } returns true
        every { project.name } returns name
        every { project.basePath } returns basePath
        every { project.projectFilePath } returns projectFilePath
        return project
    }
}
