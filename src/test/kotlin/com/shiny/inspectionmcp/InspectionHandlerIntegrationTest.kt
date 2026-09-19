package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*
import org.assertj.core.api.Assertions.assertThat
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
        val contentType = response.headers()["Content-Type"]
            ?: error("Content-Type header missing")
        val corsOrigin = response.headers()["Access-Control-Allow-Origin"]
            ?: error("Access-Control-Allow-Origin header missing")
        assertThat(contentType).isEqualTo("application/json")
        assertThat(corsOrigin).isEqualTo("*")
        assertTrue(response.headers().contains("Content-Length"))
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
        assertTrue(content.contains("Internal server error"))
    }
    
    @Test
    @DisplayName("Should handle unknown endpoints correctly")
    fun testUnknownEndpoints() {
        val unknownPaths = listOf(
            "/api/inspection/problems/simple.kt",
            "/api/inspection/problems/path/to/file.java", 
            "/api/inspection/unknown",
            "/api/inspection/problems/path with spaces"
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
            ?: error("Content-Length header missing or not an int")
        
        assertTrue(contentLength > 0)
        assertThat(contentLength).isEqualTo(content.toByteArray(Charsets.UTF_8).size)
    }
    
}
