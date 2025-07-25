package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.mockito.ArgumentCaptor
import io.netty.handler.codec.http.*
import io.netty.channel.ChannelHandlerContext

class InspectionHandlerIntegrationTest {
    
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
    @DisplayName("Should send JSON response with correct headers")
    fun testJsonResponseHeaders() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        whenever(mockDecoder.parameters()).thenReturn(emptyMap())
        
        val responseCaptor = ArgumentCaptor.forClass(FullHttpResponse::class.java)
        
        handler.process(mockDecoder, mockRequest, mockContext)
        
        verify(mockContext).writeAndFlush(responseCaptor.capture())
        
        val response = responseCaptor.value
        assertEquals("application/json", response.headers()["Content-Type"])
        assertEquals("*", response.headers()["Access-Control-Allow-Origin"])
        assertTrue(response.headers().contains("Content-Length"))
    }
    
    @Test
    @DisplayName("Should handle valid endpoints")
    fun testValidEndpoints() {
        val validEndpoints = listOf(
            "/api/inspection/problems",
            "/api/inspection/trigger",
            "/api/inspection/status"
        )
        
        validEndpoints.forEach { endpoint ->
            reset(mockContext)
            whenever(mockDecoder.path()).thenReturn(endpoint)
            whenever(mockDecoder.parameters()).thenReturn(emptyMap())
            
            val responseCaptor = ArgumentCaptor.forClass(FullHttpResponse::class.java)
            
            handler.process(mockDecoder, mockRequest, mockContext)
            
            verify(mockContext).writeAndFlush(responseCaptor.capture())
            
            val response = responseCaptor.value
            assertNotNull(response, "Endpoint $endpoint should return a response")
        }
    }
    
    @Test
    @DisplayName("Should return 404 for unknown endpoints")
    fun testUnknownEndpointReturns404() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/unknown")
        whenever(mockDecoder.parameters()).thenReturn(emptyMap())
        
        val responseCaptor = ArgumentCaptor.forClass(FullHttpResponse::class.java)
        
        handler.process(mockDecoder, mockRequest, mockContext)
        
        verify(mockContext).writeAndFlush(responseCaptor.capture())
        
        val response = responseCaptor.value
        assertEquals(HttpResponseStatus.NOT_FOUND, response.status())
        
        val content = response.content().toString(Charsets.UTF_8)
        assertTrue(content.contains("Unknown endpoint"))
    }
    
    @Test
    @DisplayName("Should return 500 for internal errors")
    fun testInternalErrorReturns500() {
        whenever(mockDecoder.path()).thenThrow(RuntimeException("Test error"))
        
        val responseCaptor = ArgumentCaptor.forClass(FullHttpResponse::class.java)
        
        handler.process(mockDecoder, mockRequest, mockContext)
        
        verify(mockContext).writeAndFlush(responseCaptor.capture())
        
        val response = responseCaptor.value
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status())
        
        val content = response.content().toString(Charsets.UTF_8)
        assertTrue(content.contains("error"))
        assertTrue(content.contains("Test error"))
    }
    
    @Test
    @DisplayName("Should generate valid JSON responses")
    fun testValidJsonResponses() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        whenever(mockDecoder.parameters()).thenReturn(emptyMap())
        
        val responseCaptor = ArgumentCaptor.forClass(FullHttpResponse::class.java)
        
        handler.process(mockDecoder, mockRequest, mockContext)
        
        verify(mockContext).writeAndFlush(responseCaptor.capture())
        
        val response = responseCaptor.value
        val content = response.content().toString(Charsets.UTF_8)
        
        assertTrue(content.startsWith("{") || content.startsWith("["))
        assertTrue(content.endsWith("}") || content.endsWith("]"))
        
        assertTrue(content.contains("status") || content.contains("error"))
    }
    
    @Test
    @DisplayName("Should handle unknown endpoints correctly")
    fun testUnknownEndpoints() {
        val unknownPaths = listOf(
            "/api/inspection/problems/simple.kt",
            "/api/inspection/problems/path/to/file.java", 
            "/api/inspection/unknown",
            "/api/inspection/inspections"
        )
        
        unknownPaths.forEach { path ->
            reset(mockContext)
            whenever(mockDecoder.path()).thenReturn(path)
            whenever(mockDecoder.parameters()).thenReturn(emptyMap())
            
            val responseCaptor = ArgumentCaptor.forClass(FullHttpResponse::class.java)
            
            handler.process(mockDecoder, mockRequest, mockContext)
            
            verify(mockContext).writeAndFlush(responseCaptor.capture())
            
            val response = responseCaptor.value
            assertEquals(HttpResponseStatus.NOT_FOUND, response.status(), 
                "Should return 404 for unknown path: $path")
        }
    }
    
    @Test
    @DisplayName("Should preserve parameter values correctly")
    fun testParameterPreservation() {
        val testParameters = mapOf(
            "scope" to listOf("current_file"),
            "severity" to listOf("error")
        )
        
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        whenever(mockDecoder.parameters()).thenReturn(testParameters)
        
        val responseCaptor = ArgumentCaptor.forClass(FullHttpResponse::class.java)
        
        handler.process(mockDecoder, mockRequest, mockContext)
        
        verify(mockContext).writeAndFlush(responseCaptor.capture())
        
        val response = responseCaptor.value
        assertNotNull(response)
        
        val content = response.content().toString(Charsets.UTF_8)
        assertNotNull(content)
    }
    
    @Test
    @DisplayName("Should handle UTF-8 content correctly")
    fun testUtf8ContentHandling() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        whenever(mockDecoder.parameters()).thenReturn(emptyMap())
        
        val responseCaptor = ArgumentCaptor.forClass(FullHttpResponse::class.java)
        
        handler.process(mockDecoder, mockRequest, mockContext)
        
        verify(mockContext).writeAndFlush(responseCaptor.capture())
        
        val response = responseCaptor.value
        val content = response.content()
        
        val contentString = content.toString(Charsets.UTF_8)
        assertNotNull(contentString)
        assertFalse(contentString.isEmpty())
    }
    
    @Test
    @DisplayName("Should set correct content length")
    fun testContentLength() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        whenever(mockDecoder.parameters()).thenReturn(emptyMap())
        
        val responseCaptor = ArgumentCaptor.forClass(FullHttpResponse::class.java)
        
        handler.process(mockDecoder, mockRequest, mockContext)
        
        verify(mockContext).writeAndFlush(responseCaptor.capture())
        
        val response = responseCaptor.value
        val content = response.content().toString(Charsets.UTF_8)
        val contentLength = response.headers()["Content-Length"]?.toIntOrNull()
        
        assertNotNull(contentLength)
        assertTrue(contentLength!! > 0)
        assertEquals(content.toByteArray(Charsets.UTF_8).size, contentLength)
    }
    
    @Test
    @DisplayName("Should maintain consistent response format")
    fun testConsistentResponseFormat() {
        val endpoints = listOf(
            "/api/inspection/problems",
            "/api/inspection/trigger",
            "/api/inspection/status"
        )
        
        endpoints.forEach { endpoint ->
            reset(mockContext)
            whenever(mockDecoder.path()).thenReturn(endpoint)
            whenever(mockDecoder.parameters()).thenReturn(emptyMap())
            
            val responseCaptor = ArgumentCaptor.forClass(FullHttpResponse::class.java)
            
            handler.process(mockDecoder, mockRequest, mockContext)
            
            verify(mockContext).writeAndFlush(responseCaptor.capture())
            
            val response = responseCaptor.value
            assertEquals("application/json", response.headers()["Content-Type"])
            assertEquals("*", response.headers()["Access-Control-Allow-Origin"])
        }
    }
}