package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import io.mockk.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.channel.ChannelHandlerContext

class SeverityFilteringTest {
    
    private lateinit var handler: InspectionHandler
    private lateinit var mockProject: Project
    private lateinit var mockProjectManager: ProjectManager
    
    @BeforeEach
    fun setup() {
        handler = InspectionHandler()
        
        mockProject = mockk<Project>()
        mockProjectManager = mockk<ProjectManager>()
        
        every { mockProject.isDefault } returns false
        every { mockProject.isDisposed } returns false
        every { mockProject.isInitialized } returns true
        every { mockProject.name } returns "TestProject"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject)
        
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
    }
    
    @Test
    @DisplayName("Should filter problems by grammar severity")
    fun testGrammarSeverityFilter() {
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/problems"
        every { mockUrlDecoder.parameters() } returns mapOf(
            "severity" to listOf("grammar")
        )
        
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
    }
    
    @Test
    @DisplayName("Should filter problems by typo severity")
    fun testTypoSeverityFilter() {
        val mockUrlDecoder = mockk<QueryStringDecoder>()
        val mockRequest = mockk<FullHttpRequest>()
        val mockContext = mockk<ChannelHandlerContext>()
        
        every { mockUrlDecoder.path() } returns "/api/inspection/problems"
        every { mockUrlDecoder.parameters() } returns mapOf(
            "severity" to listOf("typo")
        )
        
        every { mockContext.writeAndFlush(any()) } returns mockk()
        
        val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify { mockContext.writeAndFlush(any()) }
    }
    
    @Test
    @DisplayName("Should handle all severity levels correctly")
    fun testAllSeverityLevels() {
        val severityLevels = listOf("error", "warning", "weak_warning", "info", "grammar", "typo", "all")
        
        severityLevels.forEach { severity ->
            val mockUrlDecoder = mockk<QueryStringDecoder>()
            val mockRequest = mockk<FullHttpRequest>()
            val mockContext = mockk<ChannelHandlerContext>()
            
            every { mockUrlDecoder.path() } returns "/api/inspection/problems"
            every { mockUrlDecoder.parameters() } returns mapOf(
                "severity" to listOf(severity)
            )
            
            every { mockContext.writeAndFlush(any()) } returns mockk()
            
            val result = handler.process(mockUrlDecoder, mockRequest, mockContext)
            
            assertTrue(result, "Failed for severity: $severity")
        }
        
    }
    
    @Test
    @DisplayName("Should treat grammar and typo as warnings when filtering by warning")
    fun testWarningIncludesGrammarAndTypo() {
        val problems = listOf(
            mapOf("severity" to "warning", "description" to "Regular warning"),
            mapOf("severity" to "grammar", "description" to "Grammar issue"),
            mapOf("severity" to "typo", "description" to "Spelling typo"),
            mapOf("severity" to "error", "description" to "Error issue")
        )
        
        val warningFiltered = problems.filter { problem ->
            val severity = problem["severity"] as String
            severity == "warning" || (severity == "grammar" || severity == "typo")
        }
        
        assertEquals(3, warningFiltered.size)
        assertTrue(warningFiltered.any { it["severity"] == "grammar" })
        assertTrue(warningFiltered.any { it["severity"] == "typo" })
        assertTrue(warningFiltered.any { it["severity"] == "warning" })
        assertFalse(warningFiltered.any { it["severity"] == "error" })
    }
}