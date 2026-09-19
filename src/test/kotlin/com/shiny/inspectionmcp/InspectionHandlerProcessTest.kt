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
    @DisplayName("Problems endpoint rejects unknown severity values")
    fun testProblemsEndpointRejectsUnknownSeverity() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        whenever(mockDecoder.parameters()).thenReturn(mapOf("severity" to listOf("invalid_severity")))

        val result = handler.process(mockDecoder, mockRequest, mockContext)

        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(check {
            val response = it as DefaultFullHttpResponse
            val body = response.content().toString(Charsets.UTF_8)
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
            assertTrue(body.contains("\"parameter\": \"severity\""))
        })
    }

    @Test
    @DisplayName("Problems endpoint rejects invalid pagination parameters")
    fun testProblemsEndpointRejectsInvalidPaginationParameters() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        whenever(mockDecoder.parameters()).thenReturn(mapOf("limit" to listOf("0")))

        val result = handler.process(mockDecoder, mockRequest, mockContext)

        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(check {
            val response = it as DefaultFullHttpResponse
            val body = response.content().toString(Charsets.UTF_8)
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
            assertTrue(body.contains("\"parameter\": \"limit\""))
            assertTrue(body.contains("at least 1"))
        })
    }

    @Test
    @DisplayName("Problems endpoint rejects negative offsets")
    fun testProblemsEndpointRejectsNegativeOffsets() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/problems")
        whenever(mockDecoder.parameters()).thenReturn(mapOf("offset" to listOf("-1")))

        val result = handler.process(mockDecoder, mockRequest, mockContext)

        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(check {
            val response = it as DefaultFullHttpResponse
            val body = response.content().toString(Charsets.UTF_8)
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
            assertTrue(body.contains("\"parameter\": \"offset\""))
            assertTrue(body.contains("at least 0"))
        })
    }
    
    @Test
    @DisplayName("Trigger endpoint rejects invalid max_files")
    fun testTriggerRejectsInvalidMaxFiles() {
        whenever(mockDecoder.path()).thenReturn("/api/inspection/trigger")
        whenever(mockDecoder.parameters()).thenReturn(mapOf("max_files" to listOf("0")))

        val result = handler.process(mockDecoder, mockRequest, mockContext)

        assertTrue(result)
        verify(mockContext, times(1)).writeAndFlush(check {
            val response = it as DefaultFullHttpResponse
            val body = response.content().toString(Charsets.UTF_8)
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
            assertTrue(body.contains("\"parameter\": \"max_files\""))
            assertTrue(body.contains("at least 1"))
        })
    }
}
