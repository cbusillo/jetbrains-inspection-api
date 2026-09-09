package com.shiny.inspectionmcp

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.runInEdtAndGet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Paths

class LifecycleCloseGuardPlatformTest {
    @Test
    fun `real unsaved target document is refused without saving it`() {
        val project = projectExtension.project
        VfsRootAccess.allowRootAccess(project, requireNotNull(System.getProperty("idea.plugins.path")))
        val projectRoot = Paths.get(requireNotNull(project.basePath)).toRealPath()
        val targetPath = Files.createTempFile(projectRoot, "unsaved-target", ".txt")
        val targetFile = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetPath)
        )
        val fileDocumentManager = FileDocumentManager.getInstance()
        val document = runInEdtAndGet { requireNotNull(fileDocumentManager.getDocument(targetFile)) }
        runInEdtAndGet {
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(document.textLength, "unsaved")
            }
        }
        try {
            assertThat(fileDocumentManager.isDocumentUnsaved(document)).isTrue()

            val refusal = runInEdtAndGet {
                InspectionHandler().inspectUnsavedDocumentsBeforeLifecycleClose(project, projectRoot)
            }

            assertThat(refusal?.reason).isEqualTo("unsaved_documents")
            assertThat(refusal?.matchingPaths).contains(targetPath.toString())
            assertThat(fileDocumentManager.isDocumentUnsaved(document)).isTrue()
        } finally {
            runInEdtAndGet { fileDocumentManager.saveDocument(document) }
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()
    }
}
