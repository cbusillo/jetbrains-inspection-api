package com.shiny.inspectionmcp

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import io.mockk.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import java.lang.reflect.InvocationTargetException

class InspectionScopeFallbackTest {
    @AfterEach
    fun releaseStaticMocks() {
        unmockkAll()
    }

    private fun handler() = InspectionHandler()
    private fun mockProject(): Project = mockk(relaxed = true) {
        every { isDefault } returns false
        every { isDisposed } returns false
        every { isInitialized } returns true
        every { name } returns "TestProject"
        every { basePath } returns "/workspace"
    }

    @Test
    @DisplayName("files scope rejects paths that do not resolve")
    fun filesScopeRejectsMissingPaths() {
        val project = mockProject()
        mockkStatic(LocalFileSystem::class)
        val localFileSystem = mockk<LocalFileSystem>()
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.findFileByPath(any()) } returns null

        val method = InspectionHandler::class.java.getDeclaredMethod(
            "buildAnalysisScope",
            Project::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaObjectType,
            List::class.java,
        )
        method.isAccessible = true

        val error = assertThrows(InvocationTargetException::class.java) {
            method.invoke(
                handler(),
                project,
                "files",
                null,
                listOf("/does/not/exist.txt"),
                null,
                true,
                null,
                null,
                null,
            )
        }
        assertTrue(error.cause is BadRequestException)
    }

    @Test
    @DisplayName("changed_files analysis scope rejects an empty resolved set")
    fun changedFilesScopeRejectsEmptySet() {
        val project = mockProject()

        mockkStatic(ChangeListManager::class)
        val clm = mockk<ChangeListManager>(relaxed = true)
        every { ChangeListManager.getInstance(project) } returns clm
        every { clm.allChanges } returns emptyList()

        val method = InspectionHandler::class.java.getDeclaredMethod(
            "buildAnalysisScope",
            Project::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaObjectType,
            List::class.java,
        )
        method.isAccessible = true

        val error = assertThrows(InvocationTargetException::class.java) {
            method.invoke(
                handler(),
                project,
                "changed_files",
                null,
                null,
                null,
                /* includeUnversioned = */ false,
                null,
                10,
                null,
            )
        }
        assertTrue(error.cause is BadRequestException)
    }

    @Test
    fun changedFilesScopeRespectsModeAndUnversionedSelectionBeforeLimit() {
        val project = mockProject()
        fun file(name: String): VirtualFile = mockk { every { path } returns "/workspace/$name" }
        val unversionedFile = file("a.json")
        val unstagedFile = file("b.json")
        val stagedFile = file("z.json")
        val filePath = mockk<FilePath> { every { virtualFile } returns unversionedFile }
        val changeListManager = mockk<ChangeListManager>(relaxed = true)
        mockkStatic(ChangeListManager::class)
        every { ChangeListManager.getInstance(project) } returns changeListManager
        every { changeListManager.allChanges } returns listOf(unstagedFile, stagedFile).map { changedFile ->
            mockk<Change> { every { virtualFile } returns changedFile }
        }
        every { changeListManager.unversionedFilesPaths } returns listOf(filePath)
        val inspectionHandler = spyk(handler(), recordPrivateCalls = true)
        every { inspectionHandler["runBoundedGitCommand"](any<String>(), any<List<String>>()) } answers {
            when {
                "rev-parse" in secondArg<List<String>>() -> "/workspace\n".toByteArray()
                "status" in secondArg<List<String>>() -> "?? a.json\u0000 M b.json\u0000M  z.json\u0000".toByteArray()
                else -> error("Unexpected Git operation")
            }
        }

        val method = InspectionHandler::class.java.getDeclaredMethod(
            "resolveChangedFilesScopeFiles",
            Project::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaObjectType,
        )
        method.isAccessible = true

        val scenarios = listOf(
            Triple(null, true, listOf(unversionedFile, unstagedFile, stagedFile)),
            Triple("staged", true, listOf(stagedFile)),
            Triple("unstaged", true, listOf(unversionedFile, unstagedFile)),
            Triple("unstaged", false, listOf(unstagedFile)),
        )
        for ((mode, includeUnversioned, expected) in scenarios) {
            val files = method.invoke(inspectionHandler, project, includeUnversioned, mode, expected.size)
            assertEquals(expected, files, "mode=$mode, include_unversioned=$includeUnversioned")
        }
    }

    @Test
    @DisplayName("directory scope rejects an invalid directory")
    fun directoryScopeRejectsInvalidDirectory() {
        val project = mockProject()

        mockkStatic(LocalFileSystem::class)
        val lfs = mockk<LocalFileSystem>(relaxed = true)
        every { LocalFileSystem.getInstance() } returns lfs
        every { lfs.findFileByPath(any()) } returns null

        val method = InspectionHandler::class.java.getDeclaredMethod(
            "buildAnalysisScope",
            Project::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaObjectType,
            List::class.java,
        )
        method.isAccessible = true

        val error = assertThrows(InvocationTargetException::class.java) {
            method.invoke(
                handler(),
                project,
                "directory",
                "/no/such/dir",
                null,
                null,
                true,
                null,
                null,
                null,
            )
        }
        assertTrue(error.cause is BadRequestException)
    }
}
