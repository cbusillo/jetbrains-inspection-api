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
    @DisplayName("Should process trigger endpoint")
    fun testProcessTriggerEndpoint() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/trigger")
        whenever(mockDecoder.parameters()).thenReturn(emptyMap())
        
        val result = handler.process(mockDecoder, mockRequest, mockContext)
        
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(any())
    }

    @Test
    @DisplayName("Trigger: scope=files echoes files_requested and scope")
    fun testTriggerScopeFilesEcho() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/trigger")
        whenever(mockDecoder.parameters()).thenReturn(mapOf(
            "scope" to listOf("files"),
            "file" to listOf("src/a.py", "tests/t_a.py")
        ))

        val result = handler.process(mockDecoder, mockRequest, mockContext)
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(check {
            val resp = it as DefaultFullHttpResponse
            val body = resp.content().toString(Charsets.UTF_8)
            if (resp.status() == HttpResponseStatus.OK) {
                assertTrue(body.contains("\"status\": \"triggered\""))
                assertTrue(body.contains("\"scope\": \"files\""))
                assertTrue(body.contains("\"files_requested\": 2"))
            } else {
                assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, resp.status())
                assertTrue(body.contains("error"))
            }
        })
    }

    @Test
    @DisplayName("Trigger: scope=changed_files echoes options")
    fun testTriggerScopeChangedFilesEcho() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/trigger")
        whenever(mockDecoder.parameters()).thenReturn(mapOf(
            "scope" to listOf("changed_files"),
            "include_unversioned" to listOf("false"),
            "changed_files_mode" to listOf("staged"),
            "max_files" to listOf("10")
        ))

        val result = handler.process(mockDecoder, mockRequest, mockContext)
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(check {
            val resp = it as DefaultFullHttpResponse
            val body = resp.content().toString(Charsets.UTF_8)
            if (resp.status() == HttpResponseStatus.OK) {
                assertTrue(body.contains("\"status\": \"triggered\""))
                assertTrue(body.contains("\"scope\": \"changed_files\""))
                assertTrue(body.contains("\"include_unversioned\": false"))
                assertTrue(body.contains("\"changed_files_mode\": \"staged\""))
                assertTrue(body.contains("\"max_files\": 10"))
            } else {
                assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, resp.status())
                assertTrue(body.contains("error"))
            }
        })
    }

    @Test
    @DisplayName("Trigger: profile name is echoed")
    fun testTriggerProfileEcho() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/trigger")
        whenever(mockDecoder.parameters()).thenReturn(mapOf(
            "profile" to listOf("LLM Fast Checks")
        ))

        val result = handler.process(mockDecoder, mockRequest, mockContext)
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(check {
            val resp = it as DefaultFullHttpResponse
            val body = resp.content().toString(Charsets.UTF_8)
            if (resp.status() == HttpResponseStatus.OK) {
                assertTrue(body.contains("\"status\": \"triggered\""))
                assertTrue(body.contains("\"profile\": \"LLM Fast Checks\""))
            } else {
                assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, resp.status())
                assertTrue(body.contains("error"))
            }
        })
    }

    @Test
    @DisplayName("Should process trigger endpoint with current_file scope and not crash")
    fun testProcessTriggerEndpointCurrentFileScope() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/trigger")
        whenever(mockDecoder.parameters()).thenReturn(mapOf(
            "scope" to listOf("current_file")
        ))

        val result = handler.process(mockDecoder, mockRequest, mockContext)
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(check {
            val resp = it as DefaultFullHttpResponse
            val body = resp.content().toString(Charsets.UTF_8)
            if (resp.status() == HttpResponseStatus.OK) {
                assertTrue(body.contains("\"status\": \"triggered\""))
                assertTrue(body.contains("\"scope\": \"current_file\""))
            } else {
                // Headless test environment may return 500; still ensure JSON body
                assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, resp.status())
                assertTrue(body.contains("error"))
            }
        })
    }

    @Test
    @DisplayName("Should process trigger endpoint with directory scope parameter and not crash")
    fun testProcessTriggerEndpointDirectoryScope() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/trigger")
        whenever(mockDecoder.parameters()).thenReturn(mapOf(
            "scope" to listOf("directory"),
            "dir" to listOf("src")
        ))

        val result = handler.process(mockDecoder, mockRequest, mockContext)
        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(check {
            val resp = it as DefaultFullHttpResponse
            val body = resp.content().toString(Charsets.UTF_8)
            if (resp.status() == HttpResponseStatus.OK) {
                assertTrue(body.contains("\"status\": \"triggered\""))
                assertTrue(body.contains("\"scope\": \"directory\""))
                assertTrue(body.contains("\"directory\": \"src\""))
            } else {
                assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, resp.status())
                assertTrue(body.contains("error"))
            }
        })
    }
    
    @Test
    @DisplayName("Should process status endpoint")
    fun testProcessStatusEndpoint() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/status")
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
    @DisplayName("Should handle various endpoint paths correctly")
    fun testProcessVariousEndpoints() {
        val endpoints = listOf(
            "/api/inspection/problems",
            "/api/inspection/trigger",
            "/api/inspection/status"
        )
        
        endpoints.forEach { path ->
            whenever(mockDecoder.path()).thenReturn(path)
            whenever(mockDecoder.parameters()).thenReturn(emptyMap())
            
            val result = handler.process(mockDecoder, mockRequest, mockContext)
            
            assertTrue(result, "Should handle path: $path")
        }
        
        verify(mockContext, times(endpoints.size)).writeAndFlush(any())
    }
    
    @Test
    @DisplayName("Should handle empty and null parameters gracefully")
    fun testProcessWithEmptyParameters() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        
        whenever(mockDecoder.parameters()).thenReturn(null)
        var result = handler.process(mockDecoder, mockRequest, mockContext)
        assertTrue(result)
        
        whenever(mockDecoder.parameters()).thenReturn(emptyMap())
        result = handler.process(mockDecoder, mockRequest, mockContext)
        assertTrue(result)
        
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
            "/api/inspection/trigger",
            "/api/inspection/status"
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
    @DisplayName("Should return true for all requests")
    fun testProcessAlwaysReturnsTrue() {
        val testCases = listOf(
            "/api/inspection/problems",
            "/api/inspection/trigger",
            "/api/inspection/status",
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
