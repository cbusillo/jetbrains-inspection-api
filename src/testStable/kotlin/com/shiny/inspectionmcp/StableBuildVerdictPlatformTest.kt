package com.shiny.inspectionmcp

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.GlobalSimpleInspectionTool
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
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
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

// Runs only in the Stable build (-PinspectionBuild=stable): without native attestation it must never say clean
// when it cannot prove every enabled inspection ran, and it must still report real findings.
class StableBuildVerdictPlatformTest {
    @Test
    fun `a clean directory run is UNKNOWN with a plain Stable explanation`() {
        val project = projectExtension.project
        val profile = registerProfile(project, LocalInspectionToolWrapper(CleanInspection()))

        val status = runScope(project, "scope=directory&dir=${encode(createContentRoot("fixture.txt", "clean\n"))}", profile)

        assertThat(status).describedAs(status).contains("\"inspection_verdict\": \"UNKNOWN\"")
        assertThat(status).describedAs(status)
            .contains("\"execution_proof_block_reason\": \"$STABLE_BUILD_BROAD_SCOPE_UNPROVEN_REASON\"")
        assertThat(status).describedAs(status).contains("This is the Marketplace Stable build.")
    }

    @Test
    fun `a finding in a directory run is still RED`() {
        val project = projectExtension.project
        val profile = registerProfile(project, LocalInspectionToolWrapper(FindingInspection()))

        val status = runScope(project, "scope=directory&dir=${encode(createContentRoot("fixture.txt", "finding\n"))}", profile)

        assertThat(status).describedAs(status).contains("\"inspection_verdict\": \"RED\"")
    }

    @Test
    fun `a clean files run with only local inspections is GREEN`() {
        val project = projectExtension.project
        val profile = registerProfile(project, LocalInspectionToolWrapper(CleanInspection()))
        val root = createContentRoot("Checked.java", "class Checked {}\n")

        val status = runScope(project, "scope=files&file=${encode(Path.of(root, "Checked.java").toString())}", profile)

        assertThat(status).describedAs(status).contains("\"inspection_verdict\": \"GREEN\"")
    }

    @Test
    fun `a clean files run with an enabled global-simple inspection is UNKNOWN`() {
        val project = projectExtension.project
        val profile = registerProfile(
            project,
            LocalInspectionToolWrapper(CleanInspection()),
            GlobalInspectionToolWrapper(CleanGlobalSimpleInspection()),
        )
        val root = createContentRoot("Checked.java", "class Checked {}\n")

        val status = runScope(project, "scope=files&file=${encode(Path.of(root, "Checked.java").toString())}", profile)

        assertThat(status).describedAs(status).contains("\"inspection_verdict\": \"UNKNOWN\"")
        assertThat(status).describedAs(status)
            .contains("\"execution_proof_block_reason\": \"$STABLE_BUILD_GLOBAL_SIMPLE_UNPROVEN_REASON\"")
        assertThat(status).describedAs(status).contains("This is the Marketplace Stable build.")
    }

    private fun runScope(project: Project, scopeQuery: String, profile: InspectionProfileImpl): String {
        val handler = InspectionHandler()
        val previousExtractorFactory = enhancedTreeExtractorFactory
        val extractor = mockk<EnhancedTreeExtractor>()
        every { extractor.extractAllProblemsWithStatus(any()) } returns
            ProblemExtractionResult(emptyList(), succeeded = true, source = ProblemExtractionSource.NONE)
        enhancedTreeExtractorFactory = { extractor }
        try {
            val trigger = request(
                handler,
                "/api/inspection/trigger?$scopeQuery&project=${encode(project.name)}&profile=${encode(profile.name)}",
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

    private fun registerProfile(project: Project, vararg wrappers: InspectionToolWrapper<*, *>): InspectionProfileImpl {
        wrappers.forEach { HighlightDisplayKey.findOrRegister(it.shortName, it.displayName) }
        val manager = ProjectInspectionProfileManager.getInstance(project)
        val profile = InspectionProfileImpl(
            "stable-build-${profileCounter.incrementAndGet()}",
            InspectionToolsSupplier.Simple(wrappers.toList()),
            manager,
        )
        val previousInitInspections = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true
        try {
            profile.initInspectionTools(project)
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = previousInitInspections
        }
        wrappers.forEach { profile.setToolEnabled(it.shortName, true, project) }
        manager.addProfile(profile)
        return profile
    }

    private fun createContentRoot(fileName: String, content: String): String {
        val root = Files.createTempDirectory("stable-build-verdict-")
        Files.writeString(root.resolve(fileName), content)
        runInEdtAndGet {
            val directory = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root))
            VfsUtil.markDirtyAndRefresh(false, true, true, directory)
            PsiTestUtil.addContentRoot(projectExtension.module, directory)
        }
        return root.toString()
    }

    private class CleanInspection : LocalInspectionTool() {
        override fun getShortName(): String = "StableBuildCleanProbe"

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Stable build tests"

        override fun buildVisitor(
            holder: ProblemsHolder,
            isOnTheFly: Boolean,
            session: LocalInspectionToolSession,
        ): PsiElementVisitor = object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) = Unit
        }
    }

    private class FindingInspection : LocalInspectionTool() {
        override fun getShortName(): String = "StableBuildFindingProbe"

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Stable build tests"

        override fun buildVisitor(
            holder: ProblemsHolder,
            isOnTheFly: Boolean,
            session: LocalInspectionToolSession,
        ): PsiElementVisitor = object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                holder.registerProblem(file, "Stable build finding")
            }
        }
    }

    private class CleanGlobalSimpleInspection : GlobalSimpleInspectionTool() {
        override fun getShortName(): String = "StableBuildGlobalSimpleProbe"

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Stable build tests"

        override fun checkFile(
            file: PsiFile,
            manager: InspectionManager,
            holder: ProblemsHolder,
            context: GlobalInspectionContext,
            processor: ProblemDescriptionsProcessor,
        ) = Unit
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()

        private val profileCounter = AtomicInteger()
    }
}
