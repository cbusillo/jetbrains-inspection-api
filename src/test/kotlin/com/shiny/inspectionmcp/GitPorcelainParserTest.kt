package com.shiny.inspectionmcp

import com.intellij.openapi.project.Project
import io.mockk.EqMatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.io.File
import java.util.concurrent.TimeUnit

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
        val gitRoot = Files.createTempDirectory("inspection-git-root").toRealPath()
        mockkConstructor(ProcessBuilder::class)
        try {
            val nestedProject = gitRoot.resolve("services/app")
            val sourceFile = nestedProject.resolve("src/App.kt")
            Files.createDirectories(sourceFile.parent)
            Files.writeString(sourceFile, "val staged = true\n")
            every { anyConstructed<ProcessBuilder>().start() } throws AssertionError("Unexpected Git command")
            val commands = listOf(
                listOf("git", "rev-parse", "--show-toplevel") to "$gitRoot\n",
                listOf("git", "status", "--porcelain", "-z") to "A  services/app/src/App.kt\u0000",
            )
            commands.forEach { (command, output) ->
                every { constructedWith<ProcessBuilder>(EqMatcher(command)).directory(any<File>()) } answers {
                    self as ProcessBuilder
                }
                every { constructedWith<ProcessBuilder>(EqMatcher(command)).redirectErrorStream(true) } answers {
                    self as ProcessBuilder
                }
                every { constructedWith<ProcessBuilder>(EqMatcher(command)).start() } returns gitProcess(output)
            }

            val project = mockk<Project>()
            every { project.basePath } returns nestedProject.toString()
            val method = InspectionHandler::class.java.getDeclaredMethod("computeGitStagingSets", Project::class.java)
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(InspectionHandler(), project) as Pair<Set<String>, Set<String>>

            assertEquals(setOf(sourceFile.toRealPath().toString()), result.first)
            assertTrue(result.second.isEmpty())
            verify(exactly = 1) { constructedWith<ProcessBuilder>(EqMatcher(commands[0].first)).directory(nestedProject.toFile()) }
            verify(exactly = 1) { constructedWith<ProcessBuilder>(EqMatcher(commands[1].first)).directory(gitRoot.toFile()) }
        } finally {
            unmockkConstructor(ProcessBuilder::class)
            gitRoot.toFile().deleteRecursively()
        }
    }

    private fun gitProcess(output: String): Process = mockk {
        every { inputStream } returns output.byteInputStream()
        every { waitFor(any<Long>(), TimeUnit.MILLISECONDS) } returns true
        every { exitValue() } returns 0
    }
}
