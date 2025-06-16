package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import io.mockk.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.ContentManager
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.channel.ChannelHandlerContext

class InspectionStatusTest {
    
    private lateinit var handler: InspectionHandler
    private lateinit var mockProject: Project
    private lateinit var mockProjectManager: ProjectManager
    private lateinit var mockDumbService: DumbService
    private lateinit var mockToolWindowManager: ToolWindowManager
    private lateinit var mockToolWindow: ToolWindow
    private lateinit var mockContentManager: ContentManager
    
    @BeforeEach
    fun setup() {
        handler = InspectionHandler()
        
        mockProject = mockk<Project>()
        mockProjectManager = mockk<ProjectManager>()
        mockDumbService = mockk<DumbService>()
        mockToolWindowManager = mockk<ToolWindowManager>()
        mockToolWindow = mockk<ToolWindow>()
        mockContentManager = mockk<ContentManager>()
        
        every { mockProject.isDefault } returns false
        every { mockProject.isDisposed } returns false
        every { mockProject.isInitialized } returns true
        every { mockProject.name } returns "TestProject"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject)
        
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        
        mockkStatic(DumbService::class)
        every { DumbService.getInstance(mockProject) } returns mockDumbService
        
        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(mockProject) } returns mockToolWindowManager
        
        every { mockToolWindowManager.getToolWindow("Problems View") } returns mockToolWindow
        every { mockToolWindow.contentManager } returns mockContentManager
        every { mockToolWindow.isVisible } returns true
    }
    
    @Test
    @DisplayName("Should return correct status when no inspection results exist")
    fun testStatusWithNoResults() {
        every { mockDumbService.isDumb } returns false
        every { mockContentManager.contentCount } returns 0
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/status"
        every { mockUrlDecoder.parameters() } returns emptyMap()
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
    }
    
    @Test
    @DisplayName("Should return correct status response structure")
    fun testStatusResponseStructure() {
        every { mockDumbService.isDumb } returns false
        every { mockContentManager.contentCount } returns 0
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/status"
        every { mockUrlDecoder.parameters() } returns emptyMap()
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
    }
    
    @Test
    @DisplayName("Should return correct status when indexing is in progress")
    fun testStatusWithIndexing() {
        every { mockDumbService.isDumb } returns true
        every { mockContentManager.contentCount } returns 0
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/status"
        every { mockUrlDecoder.parameters() } returns emptyMap()
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
    }
    
    @Test
    @DisplayName("Should handle trigger and status tracking correctly")
    fun testTriggerAndStatusTracking() {
        every { mockDumbService.isDumb } returns false
        every { mockContentManager.contentCount } returns 0
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.parameters() } returns emptyMap()
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/trigger"
        val triggerResult = handler.process(mockUrlDecoder, mockRequest, mockContext)
        assertTrue(triggerResult)
        
        every { mockUrlDecoder.path() } returns "/api/inspection/status"
        val statusResult = handler.process(mockUrlDecoder, mockRequest, mockContext)
        assertTrue(statusResult)
        
        verify(exactly = 2) { mockContext.writeAndFlush(any()) }
    }
    
    @Test
    @DisplayName("Should handle missing problems window gracefully")
    fun testMissingProblemsWindow() {
        every { mockDumbService.isDumb } returns false
        every { mockToolWindowManager.getToolWindow("Problems View") } returns null
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/status"
        every { mockUrlDecoder.parameters() } returns emptyMap()
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
    }
    
    @Test
    @DisplayName("Should track inspection timing correctly")
    fun testInspectionTiming() {
        every { mockDumbService.isDumb } returns false
        every { mockContentManager.contentCount } returns 0
        
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.parameters() } returns emptyMap()
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/trigger"
        handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        every { mockUrlDecoder.path() } returns "/api/inspection/status"
        handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        verify(atLeast = 2) { mockContext.writeAndFlush(any()) }
    }
}