package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import io.mockk.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vcs.changes.ChangeListManager
import java.lang.reflect.InvocationTargetException

class InspectionScopeFallbackTest {

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
