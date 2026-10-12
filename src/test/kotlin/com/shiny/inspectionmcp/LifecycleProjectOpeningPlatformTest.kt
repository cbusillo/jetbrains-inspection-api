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
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `closed helper projects release their inspection snapshots across cycles`() {
        val handler = InspectionHandler()
        val begin = InspectionHandler::class.java.getDeclaredMethod(
            "beginInspectionRunInternal", Project::class.java, InspectionCaptureScope::class.java,
        ).apply { isAccessible = true }
        val states = InspectionHandler::class.java.getDeclaredField("inspectionRunStatesByProject")
            .apply { isAccessible = true }
        val roots = List(3) { Files.createTempDirectory("retention-cycle").toRealPath() }
        roots.forEach { root ->
            VfsRootAccess.allowRootAccess(projectExtension.project, root.toString())
            val project = runInEdtAndGet { requireNotNull(handler.openProjectPath(root) {}) }
            val key = projectKey(project)
            try {
                val run = begin.invoke(handler, project, InspectionCaptureScope(scopeParam = "whole_project")) as InspectionRunState
                InspectionResultsStore.setSnapshot(key, InspectionResultsSnapshot(
                    problems = listOf(mapOf("description" to "retained fixture finding")),
                    timestamp = run.triggerTimeMs,
                    projectState = InspectionProjectStateSnapshot(0, 0),
                    outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                    source = "retention_fixture",
                    runId = run.runId,
                ))
                assertNotNull(InspectionResultsStore.getSnapshot(key))
            } finally {
                runInEdtAndGet { assertTrue(ProjectManagerEx.getInstanceEx().forceCloseProject(project, false)) }
            }
            assertTrue(project.isDisposed)
            assertNull(InspectionResultsStore.getSnapshot(key), "Closed-project findings must not accumulate")
            assertTrue((states.get(handler) as Map<*, *>).keys.none { it == key }, "Closed-project run state must be released")
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()
    }
}
