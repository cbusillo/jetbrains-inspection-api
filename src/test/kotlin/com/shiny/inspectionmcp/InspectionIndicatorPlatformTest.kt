package com.shiny.inspectionmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.LeakHunter
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import java.nio.file.Files
import com.intellij.testFramework.runInEdtAndGet
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit

class InspectionIndicatorPlatformTest {
    @Test
    fun `default inspection progress is non-modal and cancellable`() {
        val indicator = runInEdtAndGet {
            InspectionHandler().inspectionIndicatorFactory(projectExtension.project)
        }

        assertFalse(indicator.isModal)
        indicator.cancel()
        assertThrows(ProcessCanceledException::class.java) { indicator.checkCanceled() }
    }

    @Test
    fun `default runner executes on the caller thread with the supplied progress indicator`() {
        val handler = InspectionHandler()
        val indicator = runInEdtAndGet {
            handler.inspectionIndicatorFactory(projectExtension.project)
        }
        ApplicationManager.getApplication().executeOnPooledThread<Unit> {
            val thread = Thread.currentThread()
            val manager = ProgressManager.getInstance()
            val previousIndicator = manager.progressIndicator
            var executed = false
            handler.inspectionProcessRunner(Runnable {
                assertSame(thread, Thread.currentThread())
                assertSame(indicator, manager.progressIndicator)
                executed = true
            }, indicator)
            assertTrue(executed)
            assertSame(previousIndicator, manager.progressIndicator)
        }.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `completed background progress does not retain its closed project`() {
        val handler = InspectionHandler()
        val root = Files.createTempDirectory("indicator-retention").toRealPath()
        VfsRootAccess.allowRootAccess(projectExtension.project, root.toString())
        val project = runInEdtAndGet { requireNotNull(handler.openProjectPath(root) {}) }
        val indicator = runInEdtAndGet { handler.inspectionIndicatorFactory(project) }
        try {
            ApplicationManager.getApplication().executeOnPooledThread<Unit> {
                handler.inspectionProcessRunner(Runnable { indicator.checkCanceled() }, indicator)
            }.get(10, TimeUnit.SECONDS)
        } finally {
            runInEdtAndGet { assertTrue(ProjectManagerEx.getInstanceEx().forceCloseProject(project, false)) }
        }
        assertTrue(project.isDisposed)
        LeakHunter.checkLeak(indicator, Project::class.java) { it === project }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()
    }
}
