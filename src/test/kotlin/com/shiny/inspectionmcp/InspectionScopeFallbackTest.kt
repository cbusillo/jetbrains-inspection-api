package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import io.mockk.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vcs.changes.ChangeListManager

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
    @DisplayName("files scope falls back when none resolve")
    fun filesScopeFallback() {
        val project = mockProject()

        val method = InspectionHandler::class.java.getDeclaredMethod(
            "buildAnalysisScope",
            Project::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaObjectType,
        )
        method.isAccessible = true

        val scope = method.invoke(
            handler(),
            project,
            "files",
            null,
            listOf("/does/not/exist.txt"),
            true,
            null,
            null,
        )
        assertNotNull(scope)
    }

    @Test
    @DisplayName("changed_files scope falls back when no changes")
    fun changedFilesFallback() {
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
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaObjectType,
        )
        method.isAccessible = true

        val scope = method.invoke(
            handler(),
            project,
            "changed_files",
            null,
            null,
            /* includeUnversioned = */ false,
            null,
            10,
        )
        assertNotNull(scope)
    }

    @Test
    @DisplayName("directory scope falls back when directory is invalid")
    fun directoryFallback() {
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
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaObjectType,
        )
        method.isAccessible = true

        val scope = method.invoke(
            handler(),
            project,
            "directory",
            "/no/such/dir",
            null,
            true,
            null,
            null,
        )
        assertNotNull(scope)
    }
}
