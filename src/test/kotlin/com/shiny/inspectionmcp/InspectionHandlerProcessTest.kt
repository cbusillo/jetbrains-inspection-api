package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import io.netty.handler.codec.http.*
import io.netty.channel.ChannelHandlerContext
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

class InspectionHandlerProcessTest {
    
    @Mock
    private lateinit var mockRequest: FullHttpRequest
    
    @Mock 
    private lateinit var mockContext: ChannelHandlerContext
    
    @Mock
    private lateinit var mockDecoder: QueryStringDecoder
    
    private lateinit var handler: InspectionHandler
    
    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        handler = InspectionHandler()
    }
    
    @Test
    @DisplayName("Should process problems endpoint with default parameters")
    fun testProcessProblemsEndpointDefaults() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        whenever(mockDecoder.parameters()).thenReturn(emptyMap())
        
        val result = handler.process(mockDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(any())
    }
    
    @Test
    @DisplayName("Should process problems endpoint with custom parameters")
    fun testProcessProblemsEndpointWithParams() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        whenever(mockDecoder.parameters()).thenReturn(mapOf(
            "scope" to listOf("current_file"),
            "severity" to listOf("error")
        ))
        
        val result = handler.process(mockDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(any())
    }
    
    @Test
    @DisplayName("Should process file-specific endpoint")
    fun testProcessFileSpecificEndpoint() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems/path/to/file.kt")
        whenever(mockDecoder.parameters()).thenReturn(mapOf(
            "severity" to listOf("warning")
        ))
        
        val result = handler.process(mockDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(any())
    }
    
    @Test
    @DisplayName("Should process inspections categories endpoint")
    fun testProcessInspectionsEndpoint() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/inspections")
        whenever(mockDecoder.parameters()).thenReturn(emptyMap())
        
        val result = handler.process(mockDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(any())
    }
    
    @Test
    @DisplayName("Should handle unknown endpoint with 404")
    fun testProcessUnknownEndpoint() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/unknown")
        whenever(mockDecoder.parameters()).thenReturn(emptyMap())
        
        val result = handler.process(mockDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(any())
    }
    
    @Test
    @DisplayName("Should handle complex file paths correctly")
    fun testProcessComplexFilePaths() {
        val complexPaths = listOf(
            "/api/inspection/problems/C:/Windows/System32/file.java",
            "/api/inspection/problems/home/user/project/src/main/kotlin/File.kt",
            "/api/inspection/problems/Users/user/Documents/My%20Project/file.js"
        )
        
        complexPaths.forEach { path ->
            whenever(mockDecoder.path()).thenReturn(path)
            whenever(mockDecoder.parameters()).thenReturn(emptyMap())
            
            val result = handler.process(mockDecoder, mockRequest, mockContext)
            
            assertTrue(result, "Should handle path: $path")
        }
        
        verify(mockContext, times(complexPaths.size)).writeAndFlush(any())
    }
    
    @Test
    @DisplayName("Should handle empty and null parameters gracefully")
    fun testProcessWithEmptyParameters() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        
        // Test with null parameters map
        whenever(mockDecoder.parameters()).thenReturn(null)
        var result = handler.process(mockDecoder, mockRequest, mockContext)
        assertTrue(result)
        
        // Test with empty parameters
        whenever(mockDecoder.parameters()).thenReturn(emptyMap())
        result = handler.process(mockDecoder, mockRequest, mockContext)
        assertTrue(result)
        
        // Test with empty parameter values
        whenever(mockDecoder.parameters()).thenReturn(mapOf(
            "scope" to emptyList(),
            "severity" to emptyList()
        ))
        result = handler.process(mockDecoder, mockRequest, mockContext)
        assertTrue(result)
        
        verify(mockContext, atLeast(3)).writeAndFlush(any())
    }
    
    @Test
    @DisplayName("Should handle multiple parameter values")
    fun testProcessWithMultipleParameterValues() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        whenever(mockDecoder.parameters()).thenReturn(mapOf(
            "scope" to listOf("current_file", "whole_project"),
            "severity" to listOf("error", "warning", "info")
        ))
        
        val result = handler.process(mockDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(any())
    }
    
    @Test
    @DisplayName("Should process all supported endpoints without errors")
    fun testProcessAllSupportedEndpoints() {
        val endpoints = listOf(
            "/api/inspection/problems",
            "/api/inspection/problems/test.kt", 
            "/api/inspection/inspections"
        )
        
        endpoints.forEach { endpoint ->
            whenever(mockDecoder.path()).thenReturn(endpoint)
            whenever(mockDecoder.parameters()).thenReturn(emptyMap())
            
            val result = handler.process(mockDecoder, mockRequest, mockContext)
            
            assertTrue(result, "Should process endpoint: $endpoint")
        }
        
        verify(mockContext, times(endpoints.size)).writeAndFlush(any())
    }
    
    @Test
    @DisplayName("Should return true for all valid requests")
    fun testProcessAlwaysReturnsTrue() {
        val testCases = listOf(
            "/api/inspection/problems",
            "/api/inspection/problems/file.kt",
            "/api/inspection/inspections",
            "/api/inspection/unknown"
        )
        
        testCases.forEach { path ->
            whenever(mockDecoder.path()).thenReturn(path)
            whenever(mockDecoder.parameters()).thenReturn(emptyMap())
            
            val result = handler.process(mockDecoder, mockRequest, mockContext)
            
            assertTrue(result, "Should always return true for path: $path")
        }
    }
}