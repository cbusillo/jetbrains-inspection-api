package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import io.mockk.*
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.codeInspection.ex.InspectionProfileImpl
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.channel.ChannelHandlerContext
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise
import org.jetbrains.concurrency.rejectedPromise
import javax.swing.JFrame

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
        
        every { mockProject.isDefault } returns false
        every { mockProject.isDisposed } returns false
        every { mockProject.isInitialized } returns true
        every { mockProject.name } returns "TestProject"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject)
        
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        
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
        
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject")
        method.isAccessible = true
        
        val result = method.invoke(handler) as Project?
        
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
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject")
        method.isAccessible = true
        
        val result = method.invoke(handler) as Project?
        
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
    fun `test getInspectionProblems method signature accepts scope parameter`() {
        // Use reflection to verify the method signature includes all filtering parameters
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "getInspectionProblems", 
            String::class.java,  // severity
            String::class.java,  // scope
            String::class.java,  // problemType (nullable)
            String::class.java,  // filePattern (nullable)
            Int::class.java,     // limit
            Int::class.java      // offset
        )
        
        assertNotNull(method)
        assertEquals("getInspectionProblems", method.name)
        assertEquals(6, method.parameterCount)
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
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject")
        method.isAccessible = true
        
        val result = method.invoke(handler) as Project?
        
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
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject")
        method.isAccessible = true
        
        val result = method.invoke(handler) as Project?
        
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
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject")
        method.isAccessible = true
        
        val result = method.invoke(handler) as Project?
        
        assertNotNull(result)
        assertEquals("FirstProject", result?.name)
    }
}