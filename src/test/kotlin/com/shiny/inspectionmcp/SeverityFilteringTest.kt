package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SeverityFilteringTest {
    @Test
    fun `grammar and typo are accepted severity filters`() {
        val handler = InspectionHandler()
        val parseSeverity = InspectionHandler::class.java.getDeclaredMethod("parseSeverity", Map::class.java)
        parseSeverity.isAccessible = true

        listOf("grammar", "typo").forEach { severity ->
            val parsed = parseSeverity.invoke(handler, mapOf("severity" to listOf(severity)))
            assertEquals(severity, parsed)
        }
    }
}
