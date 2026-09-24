package com.shiny.inspectionmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.runInEdtAndGet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit

class ProjectStateCapturePlatformTest {
    @Test
    fun `indexing transitions change the snapshot counter without editing project content`() {
        val project = projectExtension.project
        val file = createContentFile()
        val handler = InspectionHandler()
        val before = handler.captureProjectState(project)
        val content = file.contentsToByteArray()

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertThat(handler.captureProjectState(project).psiModificationCount)
                .isGreaterThan(before.psiModificationCount)
        }

        val after = handler.captureProjectState(project)
        assertThat(after.psiModificationCount).isGreaterThan(before.psiModificationCount)
        assertThat(after.unsavedProjectDocuments).isZero()
        assertThat(file.contentsToByteArray()).isEqualTo(content)
    }

    @Test
    fun `unsaved project documents are counted from a background thread without read access`() {
        val project = projectExtension.project
        val file = createContentFile()
        val fileDocumentManager = FileDocumentManager.getInstance()
        val document = runInEdtAndGet { requireNotNull(fileDocumentManager.getDocument(file)) }
        runInEdtAndGet {
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(document.textLength, "unsaved")
            }
        }
        try {
            val application = ApplicationManager.getApplication()
            val state = application.executeOnPooledThread<InspectionProjectStateSnapshot> {
                check(!application.isReadAccessAllowed) { "fixture thread already holds read access" }
                InspectionHandler().captureProjectState(project)
            }.get(10, TimeUnit.SECONDS)

            assertThat(state.unsavedProjectDocuments).isEqualTo(1)
        } finally {
            runInEdtAndGet { fileDocumentManager.saveDocument(document) }
        }
    }

    private fun createContentFile(): VirtualFile {
        val module = projectExtension.module
        return runInEdtAndGet {
            WriteAction.compute<VirtualFile, RuntimeException> {
                val contentRoot = ModuleRootManager.getInstance(module).contentRoots.single()
                val file = contentRoot.createChildData(this, "project-state-capture-${System.nanoTime()}.txt")
                VfsUtil.saveText(file, "saved")
                file
            }
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()
    }
}
