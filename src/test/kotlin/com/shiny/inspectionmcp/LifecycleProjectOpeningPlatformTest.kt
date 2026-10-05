package com.shiny.inspectionmcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Paths

class LifecycleProjectOpeningPlatformTest {
    @Test
    fun `default opener registers the exact raw project and notifies its callback`() {
        val root = Files.createTempDirectory("default-project-open").toRealPath()
        VfsRootAccess.allowRootAccess(projectExtension.project, root.toString())
        val handler = InspectionHandler()
        var callbackProject: Project? = null
        var opened: Project? = null
        val previousDialog = TestDialogManager.setTestDialog { message ->
            fail<Int>("Unexpected project-open dialog: $message")
        }
        try {
            opened = runInEdtAndGet { handler.openProjectPath(root) { callbackProject = it } }
            assertNotNull(opened)
            val project = requireNotNull(opened)
            assertSame(project, callbackProject)
            assertEquals(root, Paths.get(requireNotNull(project.basePath)).toRealPath())
            assertTrue(ProjectManager.getInstance().openProjects.any { it === project })
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
            opened?.let { project ->
                runInEdtAndGet {
                    assertTrue(ProjectManagerEx.getInstanceEx().forceCloseProject(project, false))
                }
            }
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()
    }
}
