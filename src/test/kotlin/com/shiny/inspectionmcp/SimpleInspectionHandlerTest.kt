package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import io.netty.handler.codec.http.*

class SimpleInspectionHandlerTest {
    
    @Mock
    private lateinit var mockRequest: FullHttpRequest
    
    private lateinit var handler: InspectionHandler
    
    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        handler = InspectionHandler()
    }
    
    @Test
    @DisplayName("Should support inspection API endpoints")
    fun testIsSupported() {
        // Test supported endpoints
        whenever(mockRequest.uri()).thenReturn("/api/inspection/problems")
        whenever(mockRequest.method()).thenReturn(HttpMethod.GET)
        assertTrue(handler.isSupported(mockRequest))
        
        whenever(mockRequest.uri()).thenReturn("/api/inspection/problems/path/to/file.kt")
        assertTrue(handler.isSupported(mockRequest))
        
        whenever(mockRequest.uri()).thenReturn("/api/inspection/inspections")
        assertTrue(handler.isSupported(mockRequest))
        
        // Test unsupported endpoints
        whenever(mockRequest.uri()).thenReturn("/api/other")
        assertFalse(handler.isSupported(mockRequest))
        
        whenever(mockRequest.uri()).thenReturn("/api/inspection/problems")
        whenever(mockRequest.method()).thenReturn(HttpMethod.POST)
        assertFalse(handler.isSupported(mockRequest))
    }
    
    @Test
    @DisplayName("Should handle endpoint path matching correctly")
    fun testEndpointPathMatching() {
        whenever(mockRequest.method()).thenReturn(HttpMethod.GET)
        
        // Test exact matches
        whenever(mockRequest.uri()).thenReturn("/api/inspection/problems")
        assertTrue(handler.isSupported(mockRequest))
        
        whenever(mockRequest.uri()).thenReturn("/api/inspection/inspections")
        assertTrue(handler.isSupported(mockRequest))
        
        // Test-file-specific paths
        whenever(mockRequest.uri()).thenReturn("/api/inspection/problems/home/user/test.kt")
        assertTrue(handler.isSupported(mockRequest))
        
        whenever(mockRequest.uri()).thenReturn("/api/inspection/problems/C:/Windows/test.java")
        assertTrue(handler.isSupported(mockRequest))
        
        // Test paths that don't start with /api/inspection
        whenever(mockRequest.uri()).thenReturn("/api/other")
        assertFalse(handler.isSupported(mockRequest))
        
        whenever(mockRequest.uri()).thenReturn("/other/inspection/problems")
        assertFalse(handler.isSupported(mockRequest))
        
        whenever(mockRequest.uri()).thenReturn("/inspection/problems")
        assertFalse(handler.isSupported(mockRequest))
    }
    
    @Test
    @DisplayName("Should handle different HTTP methods")
    fun testHttpMethods() {
        whenever(mockRequest.uri()).thenReturn("/api/inspection/problems")
        
        // Should support GET
        whenever(mockRequest.method()).thenReturn(HttpMethod.GET)
        assertTrue(handler.isSupported(mockRequest))
        
        // Should not support other methods
        whenever(mockRequest.method()).thenReturn(HttpMethod.POST)
        assertFalse(handler.isSupported(mockRequest))
        
        whenever(mockRequest.method()).thenReturn(HttpMethod.PUT)
        assertFalse(handler.isSupported(mockRequest))
        
        whenever(mockRequest.method()).thenReturn(HttpMethod.DELETE)
        assertFalse(handler.isSupported(mockRequest))
        
        whenever(mockRequest.method()).thenReturn(HttpMethod.PATCH)
        assertFalse(handler.isSupported(mockRequest))
    }
    
    @Test
    @DisplayName("Should handle URL with query parameters")
    fun testUrlWithParameters() {
        whenever(mockRequest.method()).thenReturn(HttpMethod.GET)
        
        // URLs with parameters should still be supported
        whenever(mockRequest.uri()).thenReturn("/api/inspection/problems?scope=whole_project")
        assertTrue(handler.isSupported(mockRequest))
        
        whenever(mockRequest.uri()).thenReturn("/api/inspection/problems?scope=current_file&severity=error")
        assertTrue(handler.isSupported(mockRequest))
        
        whenever(mockRequest.uri()).thenReturn("/api/inspection/inspections?format=json")
        assertTrue(handler.isSupported(mockRequest))
    }
}