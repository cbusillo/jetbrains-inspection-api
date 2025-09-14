package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import io.mockk.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectFileIndex

class InspectionScopeResolutionTest {

    private fun mockProject(name: String = "TestProject"): Project {
        val p = mockk<Project>(relaxed = true)
        every { p.isDefault } returns false
        every { p.isDisposed } returns false
        every { p.isInitialized } returns true
        every { p.name } returns name
        return p
    }

    private fun mockVf(path: String, inLocal: Boolean = true, valid: Boolean = true): VirtualFile {
        val vf = mockk<VirtualFile>(relaxed = true)
        every { vf.path } returns path
        every { vf.isValid } returns valid
        every { vf.isInLocalFileSystem } returns inLocal
        return vf
    }

    @Test
    @DisplayName("resolveActiveEditorFile returns project content file and skips previews")
    fun testResolveActiveEditorFileSelection() {
        val project = mockProject()

        val preview = mockVf("/virtual/TabPreviewDiffVirtualFile", inLocal = false)
        val real = mockVf("/workspace/src/Main.kt", inLocal = true)

        mockkStatic(FileEditorManager::class)
        val fem = mockk<FileEditorManager>(relaxed = true)
        every { FileEditorManager.getInstance(project) } returns fem
        every { fem.selectedFiles } returns arrayOf(preview, real)
        every { fem.openFiles } returns arrayOf(preview, real)

        mockkStatic(ProjectFileIndex::class)
        val index = mockk<ProjectFileIndex>(relaxed = true)
        every { ProjectFileIndex.getInstance(project) } returns index
        every { index.isInContent(preview) } returns false
        every { index.isInContent(real) } returns true

        val handler = InspectionHandler()
        val m = InspectionHandler::class.java.getDeclaredMethod("resolveActiveEditorFile", Project::class.java)
        m.isAccessible = true
        val resolved = m.invoke(handler, project) as VirtualFile?

        assertNotNull(resolved)
        assertEquals(real, resolved)
    }

    @Test
    @DisplayName("buildAnalysisScope current_file falls back when no valid editor file")
    fun testCurrentFileFallbackToProject() {
        val project = mockProject()

        mockkStatic(FileEditorManager::class)
        val fem = mockk<FileEditorManager>(relaxed = true)
        every { FileEditorManager.getInstance(project) } returns fem
        every { fem.selectedFiles } returns emptyArray()
        every { fem.openFiles } returns emptyArray()

        mockkStatic(ProjectFileIndex::class)
        val index = mockk<ProjectFileIndex>(relaxed = true)
        every { ProjectFileIndex.getInstance(project) } returns index

        val handler = InspectionHandler()
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
            handler,
            project,
            "current_file",
            null,
            null,
            true,
            null,
            null,
        )

        assertNotNull(scope)
        // We can't easily introspect AnalysisScope internals here, but ensuring no exception
        // and a non-null return value covers the fallback path logic.
    }
}
