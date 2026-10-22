package com.shiny.inspectionmcp

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
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

class UnusedDeclarationScopePlatformTest {
    @Test
    fun `directory run reports declarations the global unused pass finds`() {
        val project = projectExtension.project
        val profile = registerProfile(project, "unused", "DataFlowIssue")
        val root = createSourceRoot(
            "Correct.java",
            "public class Correct {\n    public int length(String value) {\n        return value.length();\n    }\n}\n",
        )
        val selector = "scope=directory&dir=${encode(root)}&project=${encode(project.name)}&profile=${encode(profile.name)}"
        val handler = InspectionHandler()
        val previousExtractorFactory = enhancedTreeExtractorFactory
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(any()) } returns
            ProblemExtractionResult(emptyList(), succeeded = true, source = ProblemExtractionSource.NONE)
        enhancedTreeExtractorFactory = { extractor }
        try {
            val trigger = request(handler, "/api/inspection/trigger?$selector")
            assertThat(trigger).describedAs(trigger).contains("\"status\": \"triggered\"")
            val status = awaitTerminalStatus(handler, project)
            assertThat(status).describedAs(status).contains("\"inspection_verdict\": \"RED\"")
            val problems = request(handler, "/api/inspection/problems?$selector")
            val unusedLines = Regex("\\{[^{}]*\"inspectionType\": \"unused\"[^{}]*}").findAll(problems)
                .filter { it.value.contains("/Correct.java\"") }
                .mapNotNull { Regex("\"line\": (\\d+)").find(it.value)?.groupValues?.get(1)?.toInt() }
                .toSet()
            assertThat(unusedLines).describedAs(problems).containsExactlyInAnyOrder(1, 2)
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

    private fun registerProfile(project: Project, vararg enabledShortNames: String): InspectionProfileImpl {
        val manager = ProjectInspectionProfileManager.getInstance(project)
        val profile = InspectionProfileImpl(
            "unused-scope-${profileCounter.incrementAndGet()}",
            InspectionToolRegistrar.getInstance(),
            manager,
        )
        val previousInitInspections = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true
        try {
            profile.initInspectionTools(project)
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = previousInitInspections
        }
        profile.disableAllTools(project)
        enabledShortNames.forEach { profile.enableTool(it, project) }
        manager.addProfile(profile)
        return profile
    }

    private fun createSourceRoot(fileName: String, content: String): String {
        val root = Files.createTempDirectory("unused-declaration-scope-")
        Files.writeString(root.resolve(fileName), content)
        runInEdtAndGet {
            val directory = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root))
            VfsUtil.markDirtyAndRefresh(false, true, true, directory)
            PsiTestUtil.addSourceRoot(projectExtension.module, directory)
        }
        return root.toString()
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()

        private val profileCounter = AtomicInteger()
    }
}
