package com.shiny.inspectionmcp.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    @DisplayName("filterProblems matches current_file across absolute, relative, and slash-normalized paths")
    fun filterByCurrentFileNormalizesPaths() {
        val problems = listOf(
            problem(file = "src/main/kotlin/App.kt", severity = "warning"),
            problem(file = "/project/src/main/kotlin/Other.kt", severity = "warning"),
            problem(file = "src\\main\\kotlin\\Win.kt", severity = "warning")
        )

        val relativeProblem = filterProblems(
            problems = problems,
            severity = "all",
            scope = "current_file",
            currentFilePath = "/project/src/main/kotlin/App.kt",
            problemType = null,
            filePattern = null
        )
        assertEquals(listOf("src/main/kotlin/App.kt"), relativeProblem.map { it["file"] })

        val relativeCurrentFile = filterProblems(
            problems = problems,
            severity = "all",
            scope = "current_file",
            currentFilePath = "src/main/kotlin/Other.kt",
            problemType = null,
            filePattern = null
        )
        assertEquals(listOf("/project/src/main/kotlin/Other.kt"), relativeCurrentFile.map { it["file"] })

        val slashNormalized = filterProblems(
            problems = problems,
            severity = "all",
            scope = "current_file",
            currentFilePath = "/project/src/main/kotlin/Win.kt",
            problemType = null,
            filePattern = null
        )
        assertEquals(listOf("src\\main\\kotlin\\Win.kt"), slashNormalized.map { it["file"] })
    }

    @Test
    @DisplayName("filterProblems preserves significant spaces in current_file paths")
    fun filterByCurrentFilePreservesSignificantPathSpaces() {
        val problems = listOf(
            problem(file = "/project/src/Name.kt", severity = "warning"),
            problem(file = "/project/src/Name.kt ", severity = "warning"),
            problem(file = "/project/src/ Leading.kt", severity = "warning")
        )

        val trailingSpaceFile = filterProblems(
            problems = problems,
            severity = "all",
            scope = "current_file",
            currentFilePath = "/project/src/Name.kt ",
            problemType = null,
            filePattern = null
        )
        assertEquals(listOf("/project/src/Name.kt "), trailingSpaceFile.map { it["file"] })

        val leadingSpaceFile = filterProblems(
            problems = problems,
            severity = "all",
            scope = "current_file",
            currentFilePath = "/project/src/ Leading.kt",
            problemType = null,
            filePattern = null
        )
        assertEquals(listOf("/project/src/ Leading.kt"), leadingSpaceFile.map { it["file"] })
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
    @DisplayName("filterProblems treats plain file patterns literally while keeping regex support")
    fun filterByLiteralAndRegexFilePatterns() {
        val problems = listOf(
            problem(file = "src/app.py", severity = "warning"),
            problem(file = "src/appXpy", severity = "warning"),
            problem(file = "src/test/app.js", severity = "warning"),
            problem(file = "src/test/app.jsx", severity = "warning")
        )

        val literalFiltered = filterProblems(
            problems = problems,
            severity = "all",
            scope = "whole_project",
            currentFilePath = null,
            problemType = null,
            filePattern = "app.py"
        )
        assertEquals(listOf("src/app.py"), literalFiltered.map { it["file"] })
        assertFalse(literalFiltered.any { it["file"] == "src/appXpy" })

        val regexFiltered = filterProblems(
            problems = problems,
            severity = "all",
            scope = "whole_project",
            currentFilePath = null,
            problemType = null,
            filePattern = "src/.*\\.js$"
        )
        assertEquals(listOf("src/test/app.js"), regexFiltered.map { it["file"] })
    }

    @Test
    @DisplayName("filterProblems treats paths with regex metacharacters literally when they match")
    fun filterByLiteralFilePatternWithRegexMetacharacters() {
        val problems = listOf(
            problem(file = "src/generated[fixture].kt", severity = "warning"),
            problem(file = "src/generatedf.kt", severity = "warning"),
            problem(file = "src/generatedi.kt", severity = "warning")
        )

        val literalFiltered = filterProblems(
            problems = problems,
            severity = "all",
            scope = "whole_project",
            currentFilePath = null,
            problemType = null,
            filePattern = "generated[fixture].kt"
        )

        assertEquals(listOf("src/generated[fixture].kt"), literalFiltered.map { it["file"] })
    }

    @Test
    @DisplayName("filterProblems keeps plain file patterns literal when there are no matches")
    fun filterByPlainPatternDoesNotFallBackToRegex() {
        val problems = listOf(
            problem(file = "src/appXpy", severity = "warning"),
            problem(file = "src/componentstory.tsx", severity = "warning"),
            problem(file = "src/generatedf.kt", severity = "warning")
        )

        val dotPattern = filterProblems(
            problems = problems,
            severity = "all",
            scope = "whole_project",
            currentFilePath = null,
            problemType = null,
            filePattern = "app.py"
        )
        assertEquals(emptyList<String>(), dotPattern.map { it["file"] })

        val plusPattern = filterProblems(
            problems = problems,
            severity = "all",
            scope = "whole_project",
            currentFilePath = null,
            problemType = null,
            filePattern = "component+story.tsx"
        )
        assertEquals(emptyList<String>(), plusPattern.map { it["file"] })

        val bracketPattern = filterProblems(
            problems = problems,
            severity = "all",
            scope = "whole_project",
            currentFilePath = null,
            problemType = null,
            filePattern = "generated[fixture].kt"
        )
        assertEquals(emptyList<String>(), bracketPattern.map { it["file"] })
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
