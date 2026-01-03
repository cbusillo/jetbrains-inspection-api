package com.shiny.inspectionmcp.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ProblemFilteringTest {
    @Test
    @DisplayName("normalizeProblemsScope maps aliases and defaults")
    fun normalizeScope() {
        assertEquals("whole_project", normalizeProblemsScope(""))
        assertEquals("whole_project", normalizeProblemsScope("directory"))
        assertEquals("current_file", normalizeProblemsScope("current_file"))
        assertEquals("custom", normalizeProblemsScope("custom"))
    }

    @Test
    @DisplayName("filterProblems respects severity and warning aliases")
    fun filterBySeverity() {
        val problems = listOf(
            problem(file = "src/A.kt", severity = "error"),
            problem(file = "src/B.kt", severity = "warning"),
            problem(file = "src/C.kt", severity = "grammar"),
            problem(file = "src/D.kt", severity = "typo")
        )

        val warnings = filterProblems(
            problems = problems,
            severity = "warning",
            scope = "whole_project",
            currentFilePath = null,
            problemType = null,
            filePattern = null
        )

        assertEquals(3, warnings.size)
        assertTrue(warnings.any { it["severity"] == "warning" })
        assertTrue(warnings.any { it["severity"] == "grammar" })
        assertTrue(warnings.any { it["severity"] == "typo" })
    }

    @Test
    @DisplayName("filterProblems respects current_file scope")
    fun filterByCurrentFile() {
        val problems = listOf(
            problem(file = "/project/src/A.kt", severity = "warning"),
            problem(file = "/project/src/B.kt", severity = "warning")
        )

        val empty = filterProblems(
            problems = problems,
            severity = "all",
            scope = "current_file",
            currentFilePath = null,
            problemType = null,
            filePattern = null
        )
        assertTrue(empty.isEmpty())

        val scoped = filterProblems(
            problems = problems,
            severity = "all",
            scope = "current_file",
            currentFilePath = "/project/src/B.kt",
            problemType = null,
            filePattern = null
        )
        assertEquals(1, scoped.size)
        assertEquals("/project/src/B.kt", scoped.first()["file"])
    }

    @Test
    @DisplayName("filterProblems supports type and file pattern filtering")
    fun filterByTypeAndPattern() {
        val problems = listOf(
            problem(file = "src/app.py", severity = "warning", inspectionType = "SpellCheckingInspection"),
            problem(file = "src/app.kt", severity = "warning", inspectionType = "UnusedSymbol", category = "General"),
            problem(file = "src/[brackets].kt", severity = "warning", inspectionType = "UnusedSymbol", category = "Unused")
        )

        val typeFiltered = filterProblems(
            problems = problems,
            severity = "all",
            scope = "whole_project",
            currentFilePath = null,
            problemType = "Unused",
            filePattern = null
        )
        assertEquals(2, typeFiltered.size)

        val globFiltered = filterProblems(
            problems = problems,
            severity = "all",
            scope = "whole_project",
            currentFilePath = null,
            problemType = null,
            filePattern = "*.py"
        )
        assertEquals(1, globFiltered.size)
        assertEquals("src/app.py", globFiltered.first()["file"])

        val invalidRegexFiltered = filterProblems(
            problems = problems,
            severity = "all",
            scope = "whole_project",
            currentFilePath = null,
            problemType = null,
            filePattern = "["
        )
        assertEquals(1, invalidRegexFiltered.size)
        assertEquals("src/[brackets].kt", invalidRegexFiltered.first()["file"])
    }

    @Test
    @DisplayName("paginateProblems returns metadata")
    fun paginate() {
        val problems = listOf(
            problem(file = "src/A.kt", severity = "warning"),
            problem(file = "src/B.kt", severity = "warning"),
            problem(file = "src/C.kt", severity = "warning")
        )

        val page = paginateProblems(problems, limit = 2, offset = 0)
        assertEquals(3, page.total)
        assertEquals(2, page.shown)
        assertTrue(page.hasMore)
        assertEquals(2, page.nextOffset)
    }

    private fun problem(
        file: String,
        severity: String,
        inspectionType: String = "Inspection",
        category: String = "General"
    ): Map<String, Any> {
        return mapOf(
            "file" to file,
            "severity" to severity,
            "inspectionType" to inspectionType,
            "category" to category
        )
    }
}
