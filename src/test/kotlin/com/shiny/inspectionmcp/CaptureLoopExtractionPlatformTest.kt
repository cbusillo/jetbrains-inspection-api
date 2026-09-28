package com.shiny.inspectionmcp

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.runInEdtAndGet
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class CaptureLoopExtractionPlatformTest {
    @Test
    fun `failing tool-window extraction keeps a clean directory run polling until the capture deadline`() {
        val project = projectExtension.project
        val directory = createLocalContentRoot()
        val profile = registerProfile(project, CleanInspection())
        val status = runWithFailingExtraction(project, directory, profile)
        assertThat(status).describedAs(status).contains("\"exit_reason\": \"deadline\"")
        assertThat(status).describedAs(status).contains("\"inspection_verdict\": \"GREEN\"")
        assertThat(status).describedAs(status).doesNotContain("\"extraction_failure_count\": 0")
    }

    private fun runWithFailingExtraction(project: Project, directory: String, profile: InspectionProfileImpl): String {
        val handler = InspectionHandler()
        val previousExtractorFactory = enhancedTreeExtractorFactory
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(any()) } returns
            ProblemExtractionResult(emptyList(), succeeded = false, source = ProblemExtractionSource.NONE)
        enhancedTreeExtractorFactory = { extractor }
        try {
            val trigger = request(
                handler,
                "/api/inspection/trigger?scope=directory&dir=${encode(directory)}&project=${encode(project.name)}&profile=${encode(profile.name)}",
            )
            assertThat(trigger).describedAs(trigger).contains("\"status\": \"triggered\"")
            return awaitTerminalStatus(handler, project)
        } finally {
            enhancedTreeExtractorFactory = previousExtractorFactory
        }
    }

    private fun awaitTerminalStatus(handler: InspectionHandler, project: Project): String {
        val deadline = System.nanoTime() + 180_000_000_000L
        var status = ""
        while (System.nanoTime() < deadline) {
            status = request(handler, "/api/inspection/status?project=${encode(project.name)}")
            if (status.contains("\"inspection_in_progress\": false") && status.contains("\"inspection_verdict\"")) {
                return status
            }
            Thread.sleep(500)
        }
        error("inspection did not finish: $status")
    }

    private fun request(handler: InspectionHandler, uri: String): String {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri)
            assertThat(handler.process(QueryStringDecoder(uri), request, channel.pipeline().firstContext())).isTrue()
            val response = channel.readOutbound<Any>()
            val content = (response as? FullHttpResponse)?.content() ?: response as ByteBuf
            return content.toString(Charsets.UTF_8)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun registerProfile(project: Project, vararg tools: LocalInspectionTool): InspectionProfileImpl {
        val wrappers = tools.map(::LocalInspectionToolWrapper)
        wrappers.forEach { HighlightDisplayKey.findOrRegister(it.shortName, it.displayName) }
        val supplier = InspectionToolsSupplier.Simple(wrappers.map { it as InspectionToolWrapper<*, *> })
        val manager = ProjectInspectionProfileManager.getInstance(project)
        val profile = InspectionProfileImpl("capture-loop-${profileCounter.incrementAndGet()}", supplier, manager)
        val previousInitInspections = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true
        try {
            profile.initInspectionTools(project)
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = previousInitInspections
        }
        tools.forEach { profile.setToolEnabled(it.shortName, true, project) }
        manager.addProfile(profile)
        return profile
    }

    private fun createLocalContentRoot(): String {
        val root = Files.createTempDirectory("capture-loop-")
        Files.writeString(root.resolve("fixture.txt"), "capture loop fixture")
        runInEdtAndGet {
            val directory = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root))
            VfsUtil.markDirtyAndRefresh(false, true, true, directory)
            PsiTestUtil.addContentRoot(projectExtension.module, directory)
        }
        return root.toString()
    }

    private class CleanInspection : LocalInspectionTool() {
        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Capture loop tests"

        override fun buildVisitor(
            holder: ProblemsHolder,
            isOnTheFly: Boolean,
            session: LocalInspectionToolSession,
        ): PsiElementVisitor = object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) = Unit
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()

        private val profileCounter = AtomicInteger()
    }
}
