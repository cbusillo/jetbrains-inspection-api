package com.shiny.inspectionmcp

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.runInEdtAndGet
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class BackgroundDiskFreshnessPlatformTest {
    @Test
    fun `timestamp-preserving disk write is UNKNOWN until PSI matches the disk`() {
        val project = projectExtension.project
        val path = runInEdtAndGet {
            val diskRoot = Files.createTempDirectory("background-disk-")
            val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(diskRoot))
            PsiTestUtil.addContentRoot(projectExtension.module, root)
            val oldText = "<root>\n <old/></root>\n"
            val newText = "<root>\n\n<new/></root>\n"
            val file = WriteAction.compute<com.intellij.psi.PsiFile, RuntimeException> {
                val virtualFile = root.createChildData(this, "background-disk-${System.nanoTime()}.xml")
                VfsUtil.saveText(virtualFile, oldText)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
            }
            assertThat(ReadAction.compute<String, RuntimeException> { file.text }).isEqualTo(oldText)
            val virtualFile = file.virtualFile
            Files.writeString(Path.of(virtualFile.path), newText)
            Files.setLastModifiedTime(Path.of(virtualFile.path), FileTime.fromMillis(virtualFile.timeStamp))
            (root as NewVirtualFile).markClean()
            (virtualFile as NewVirtualFile).markClean()
            assertThat(ReadAction.compute<String, RuntimeException> { file.text }).isEqualTo(oldText)

            val handler = InspectionHandler()
            handler.refreshProjectRoot(diskRoot.toString())
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertThat(ReadAction.compute<String, RuntimeException> { file.text }).isEqualTo(oldText)
            virtualFile.path
        }
        val tool = DiskFindingInspection()
        val profile = registerProfile(project, tool)
        val handler = InspectionHandler()
        val uri = "/api/inspection/trigger?scope=files&file=${encode(path)}&project=${encode(project.name)}&profile=${encode(profile.name)}"
        val stale = inspect(handler, uri, project)
        assertThat(stale).describedAs(stale).contains("\"inspection_verdict\": \"UNKNOWN\"")
        assertThat(stale).contains("disk_psi_content_mismatch", "inspection_inputs_changed", path)
        assertThat(stale).contains("inspection_verdict_next_action", "Reload from Disk", "modification time")
        assertThat(tool.visits.get()).isZero()

        runInEdtAndGet {
            val bytes = "\uFEFF<root>\r\n\r\n<new/><!-- ".toByteArray() + byteArrayOf(0xff.toByte()) + " --></root>\r\n".toByteArray()
            Files.write(Path.of(path), bytes)
            Files.setLastModifiedTime(Path.of(path), FileTime.fromMillis(Files.getLastModifiedTime(Path.of(path)).toMillis() + 2000))
            val file = requireNotNull(LocalFileSystem.getInstance().findFileByPath(path))
            VfsUtil.markDirtyAndRefresh(false, false, false, file)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertThat(file.charset).isEqualTo(Charsets.UTF_8)
            assertThat(ReadAction.compute<String, RuntimeException> { requireNotNull(PsiManager.getInstance(project).findFile(file)).text })
                .contains("\uFFFD")
        }
        val fresh = inspect(handler, uri, project)
        assertThat(fresh).describedAs(fresh).contains("\"inspection_verdict\": \"RED\"")
        val problems = request(handler, "/api/inspection/problems?scope=files&file=${encode(path)}&project=${encode(project.name)}&profile=${encode(profile.name)}")
        assertThat(problems).describedAs(problems).contains("disk finding new", "\"line\": 3")
        assertThat(problems).doesNotContain("disk finding old")
        assertThat(tool.visits.get()).isPositive()

        val synchronizedBytes = Files.readAllBytes(Path.of(path))
        tool.replaceDuringInspection = true
        val changedDuringRun = inspect(handler, uri, project)
        assertThat(changedDuringRun).describedAs(changedDuringRun)
            .contains("\"inspection_verdict\": \"UNKNOWN\"", "disk_psi_content_mismatch", "inspection_inputs_changed")

        Files.write(Path.of(path), synchronizedBytes)
        tool.replaceDuringInspection = false
        tool.deleteDuringInspection = true
        val deletedDuringRun = inspect(handler, uri, project)
        assertThat(deletedDuringRun).describedAs(deletedDuringRun)
            .contains("\"inspection_verdict\": \"UNKNOWN\"", "scoped_disk_read_failed", "inspection_inputs_changed")
    }

    @Test
    fun `VFS deletion between lookup and read is an unavailable input`() {
        val project = projectExtension.project
        val localFileSystem = LocalFileSystem.getInstance()
        val file = runInEdtAndGet {
            val root = Files.createTempDirectory("disk-invalidated-")
            val path = Files.writeString(root.resolve("invalidated.xml"), "<root/>")
            requireNotNull(localFileSystem.refreshAndFindFileByNioFile(path))
        }
        val path = file.path
        io.mockk.mockkStatic(LocalFileSystem::class)
        try {
            val lookup = io.mockk.mockk<LocalFileSystem>()
            io.mockk.every { LocalFileSystem.getInstance() } returns lookup
            io.mockk.every { lookup.findFileByPath(path) } answers {
                runInEdtAndGet { WriteAction.run<RuntimeException> { file.delete(this) } }
                assertThat(file.isValid).isFalse()
                file
            }
            val method = InspectionHandler::class.java.getDeclaredMethod(
                "inspectionScopeDiskFailure", Project::class.java, InspectionCaptureScope::class.java,
                String::class.java, Long::class.javaPrimitiveType,
            )
            method.isAccessible = true
            val result = method.invoke(
                InspectionHandler(), project, InspectionCaptureScope(scopeParam = "files", resolvedFiles = listOf(path)),
                project.name, 1L,
            )
            assertThat(result.toString()).contains("scoped_psi_unavailable", path)
        } finally {
            io.mockk.unmockkStatic(LocalFileSystem::class)
        }
    }

    private fun inspect(handler: InspectionHandler, uri: String, project: Project): String {
        assertThat(request(handler, uri)).contains("\"status\": \"triggered\"")
        val deadline = System.nanoTime() + 60_000_000_000L
        var status = ""
        while (System.nanoTime() < deadline) {
            status = request(handler, "/api/inspection/status?project=${encode(project.name)}")
            if (status.contains("\"inspection_in_progress\": false")) return status
            Thread.sleep(100)
        }
        error("inspection did not finish: $status")
    }

    private fun registerProfile(project: Project, tool: DiskFindingInspection): InspectionProfileImpl {
        val wrapper = LocalInspectionToolWrapper(tool)
        HighlightDisplayKey.findOrRegister(tool.shortName, tool.displayName)
        val manager = ProjectInspectionProfileManager.getInstance(project)
        val profile = InspectionProfileImpl("background-disk", InspectionToolsSupplier.Simple(listOf(wrapper)), manager)
        val previous = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true
        try {
            profile.initInspectionTools(project)
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = previous
        }
        profile.setToolEnabled(tool.shortName, true, project)
        manager.addProfile(profile)
        return profile
    }

    private fun request(handler: InspectionHandler, uri: String): String {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri)
            assertThat(handler.process(QueryStringDecoder(uri), request, channel.pipeline().firstContext())).isTrue()
            val deadline = System.nanoTime() + 20_000_000_000L
            var response = channel.readOutbound<Any>()
            while (response == null && System.nanoTime() < deadline) {
                channel.runPendingTasks()
                Thread.sleep(10)
                response = channel.readOutbound<Any>()
            }
            val content = (response as? FullHttpResponse)?.content() ?: response as ByteBuf
            return content.toString(Charsets.UTF_8)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private class DiskFindingInspection : LocalInspectionTool() {
        val visits = AtomicInteger()
        var replaceDuringInspection = false
        var deleteDuringInspection = false
        override fun getDisplayName(): String = shortName
        override fun getGroupDisplayName(): String = "Disk freshness tests"
        override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor =
            object : PsiElementVisitor() {
                override fun visitFile(file: PsiFile) {
                    val tag = (file as? com.intellij.psi.xml.XmlFile)?.rootTag?.subTags?.singleOrNull() ?: return
                    visits.incrementAndGet()
                    holder.registerProblem(tag, "disk finding ${tag.name}")
                    if (replaceDuringInspection) {
                        val path = Path.of(file.virtualFile.path)
                        val timestamp = Files.getLastModifiedTime(path)
                        val bytes = String(Files.readAllBytes(path), Charsets.ISO_8859_1)
                            .replace("<new/>", "<old/>").toByteArray(Charsets.ISO_8859_1)
                        Files.write(path, bytes)
                        Files.setLastModifiedTime(path, timestamp)
                    }
                    if (deleteDuringInspection) Files.deleteIfExists(Path.of(file.virtualFile.path))
                }
            }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()
    }
}
