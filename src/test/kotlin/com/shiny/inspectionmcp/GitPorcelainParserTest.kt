package com.shiny.inspectionmcp

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class GitPorcelainParserTest {

    @Test
    @DisplayName("computeGitStagingSets parses staged and unstaged porcelain -z entries")
    fun testParseStagedUnstaged() {
        val porcelain = buildString {
            append("M  src/A.kt\u0000")
            append(" M src/B.kt\u0000")
            append("A  added/C.kt\u0000")
            append(" D deleted/D.kt\u0000")
            append("?? untracked/E.kt\u0000")
        }

        val (staged, unstaged) = parseGitStatusPorcelainZ(porcelain)

        assertEquals(setOf("src/A.kt", "added/C.kt"), staged)
        assertEquals(setOf("src/B.kt", "deleted/D.kt", "untracked/E.kt"), unstaged)
    }

    @Test
    @DisplayName("computeGitStagingSets parses renamed paths without consuming old path payload")
    fun testParseRenamedPath() {
        val porcelain = "R  src/new name.kt\u0000src/old name.kt\u0000"

        val (staged, unstaged) = parseGitStatusPorcelainZ(porcelain)

        assertEquals(setOf("src/new name.kt"), staged)
        assertEquals(emptySet<String>(), unstaged)
        assertFalse(staged.contains("name.kt"))
        assertFalse(unstaged.contains("name.kt"))
    }

    @Test
    @DisplayName("computeGitStagingSets resolves nested project paths against the Git root")
    fun testNestedProjectGitRoot() {
        val gitRoot = Files.createTempDirectory("inspection-git-root")
        try {
            runGit(gitRoot, "init", "--quiet")
            runGit(gitRoot, "config", "user.email", "inspection@example.com")
            runGit(gitRoot, "config", "user.name", "Inspection Test")
            val nestedProject = gitRoot.resolve("services/app")
            val sourceFile = nestedProject.resolve("src/App.kt")
            Files.createDirectories(sourceFile.parent)
            Files.writeString(sourceFile, "val initial = true\n")
            runGit(gitRoot, "add", ".")
            runGit(gitRoot, "commit", "--quiet", "-m", "initial")
            Files.writeString(sourceFile, "val changed = true\n")
            runGit(gitRoot, "add", "services/app/src/App.kt")

            val project = mockk<Project>()
            every { project.basePath } returns nestedProject.toString()
            val method = InspectionHandler::class.java.getDeclaredMethod("computeGitStagingSets", Project::class.java)
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(InspectionHandler(), project) as Pair<Set<String>, Set<String>>

            assertEquals(setOf(sourceFile.toRealPath().toString()), result.first)
            assertTrue(result.second.isEmpty())
        } finally {
            gitRoot.toFile().deleteRecursively()
        }
    }

    private fun runGit(directory: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), output)
    }
}
