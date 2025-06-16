package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*

class JsonUtilsTest {
    
    private lateinit var handler: InspectionHandler
    
    @BeforeEach
    fun setUp() {
        handler = InspectionHandler()
    }
    
    @Test
    @DisplayName("Should escape JSON strings correctly")
    fun testJsonEscaping() {
        assertEquals("\"\\\"\"", formatJsonManually("\""))
        
        assertEquals("\"\\\\\"", formatJsonManually("\\"))
        
        assertEquals("\"\\n\"", formatJsonManually("\n"))
        assertEquals("\"\\t\"", formatJsonManually("\t"))
        assertEquals("\"\\r\"", formatJsonManually("\r"))
        
        assertEquals("\"normal text\"", formatJsonManually("normal text"))
        
        val complex = "Line 1\nLine 2\tTabbed \"quoted\" \\backslash"
        val expected = "\"Line 1\\nLine 2\\tTabbed \\\"quoted\\\" \\\\backslash\""
        assertEquals(expected, formatJsonManually(complex))
    }
    
    @Test
    @DisplayName("Should format JSON manually for basic types")
    fun testBasicJsonFormatting() {
        val testData = mapOf(
            "string" to "test",
            "number" to 42,
            "boolean" to true,
            "null_value" to null
        )
        
        val result = formatJsonManually(testData)
        
        assertTrue(result.contains("\"string\": \"test\""))
        assertTrue(result.contains("\"number\": 42"))
        assertTrue(result.contains("\"boolean\": true"))
        assertTrue(result.contains("\"null_value\": null"))
        
        assertTrue(result.startsWith("{"))
        assertTrue(result.endsWith("}"))
    }
    
    @Test
    @DisplayName("Should format nested JSON structures")
    fun testNestedJsonFormatting() {
        val testData = mapOf(
            "status" to "results_available",
            "data" to mapOf(
                "count" to 5,
                "items" to listOf("item1", "item2")
            ),
            "metadata" to mapOf(
                "timestamp" to 1234567890L
            )
        )
        
        val result = formatJsonManually(testData)
        
        assertTrue(result.contains("\"status\": \"results_available\""))
        assertTrue(result.contains("\"count\": 5"))
        assertTrue(result.contains("[\"item1\",\"item2\"]"))
        assertTrue(result.contains("\"timestamp\": 1234567890"))
    }
    
    @Test
    @DisplayName("Should handle special characters in JSON")
    fun testSpecialCharactersInJson() {
        val testData = mapOf(
            "description" to "Test \"quoted\" text with\nnewline and\ttab",
            "file_path" to "C:\\Windows\\System32\\file.txt",
            "unicode" to "Unicode: \u00E9\u00F1\u00FC"
        )
        
        val result = formatJsonManually(testData)
        
        @Suppress("SpellCheckingInspection")
        assertTrue(result.contains("Test \\\"quoted\\\" text with\\nnewline and\\ttab"))
        assertTrue(result.contains("C:\\\\Windows\\\\System32\\\\file.txt"))
        assertTrue(result.contains("Unicode: éñü"))
    }
    
    @Test
    @DisplayName("Should handle empty and null values")
    fun testEmptyAndNullValues() {
        val testData = mapOf(
            "empty_string" to "",
            "null_value" to null,
            "empty_list" to listOf<String>(),
            "empty_map" to mapOf<String, Any>()
        )
        
        val result = formatJsonManually(testData)
        
        assertTrue(result.contains("\"empty_string\": \"\""))
        assertTrue(result.contains("\"null_value\": null"))
        assertTrue(result.contains("\"empty_list\": []"))
        assertTrue(result.contains("\"empty_map\": {"))
    }
    
    @Test
    @DisplayName("Should handle large JSON structures")
    fun testLargeJsonStructures() {
        val largeList = (1..100).map { "item_$it" }
        val testData = mapOf(
            "large_list" to largeList,
            "metadata" to mapOf(
                "size" to largeList.size,
                "generated" to true
            )
        )
        
        val result = formatJsonManually(testData)
        
        assertNotNull(result)
        assertTrue(result.contains("\"size\": 100"))
        assertTrue(result.contains("\"item_1\""))
        assertTrue(result.contains("\"item_100\""))
    }
    
    private fun formatJsonManually(data: Any?): String {
        val method = handler::class.java.getDeclaredMethod("formatJsonManually", Any::class.java)
        method.isAccessible = true
        return method.invoke(handler, data) as String
    }
}