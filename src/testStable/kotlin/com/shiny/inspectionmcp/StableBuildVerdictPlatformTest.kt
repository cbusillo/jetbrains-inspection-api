package com.shiny.inspectionmcp

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.GlobalSimpleInspectionTool
import com.intellij.codeInspection.InspectionEngine
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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.runInEdtAndGet
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
    fun `a clean files run with an enabled global-simple inspection is GREEN`() {
        val project = projectExtension.project
        val profile = registerProfile(
            project,
            LocalInspectionToolWrapper(CleanInspection()),
            GlobalInspectionToolWrapper(CleanGlobalSimpleInspection()),
        )
        val root = createContentRoot("Checked.java", "class Checked {}\n")

        val status = runScope(project, "scope=files&file=${encode(Path.of(root, "Checked.java").toString())}", profile)

        assertThat(status).describedAs(status).contains("\"inspection_verdict\": \"GREEN\"")
    }

    @Test
    fun `skipping a global-simple inspection on one file keeps the run UNKNOWN`() {
        val project = projectExtension.project
        val profile = registerProfile(
            project,
            LocalInspectionToolWrapper(CleanInspection()),
            GlobalInspectionToolWrapper(CleanGlobalSimpleInspection()),
        )
        val root = createContentRoot("Checked.java", "class Checked {}\n")
        val skipped = Path.of(root, "Skipped.java")
        Files.writeString(skipped, "class Skipped {}\n")
        runInEdtAndGet { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(skipped) }
        mockkStatic(InspectionEngine::class)
        try {
            every {
                InspectionEngine.runInspectionOnFile(match { it.name == skipped.fileName.toString() }, any(), any())
            } returns emptyList()

            val status = runScope(
                project,
                "scope=files&file=${encode(Path.of(root, "Checked.java").toString())}&file=${encode(skipped.toString())}",
                profile,
            )

            assertThat(status).describedAs(status).contains("\"inspection_verdict\": \"UNKNOWN\"")
            assertThat(status).describedAs(status).contains("\"execution_proof_failed_obligation_count\": 1")
        } finally {
            unmockkStatic(InspectionEngine::class)
        }
    }

    @Test
    fun `bounded global-simple proof retains both finding channels and completes its lifecycle`() {
        val root = createContentRoot("Checked.java", "class Checked {}\n")

        val proof = runGlobalSimpleProof(root, "Checked.java")

        assertThat(proof.proofEstablished).isTrue()
        assertThat(proof.proofProblems.map { it["description"] })
            .containsExactlyInAnyOrder(HOLDER_FINDING, PROCESSOR_FINDING, FINISHED_FINDING)
    }

    @Test
    fun `a global-simple failure on one file leaves proof incomplete and keeps other findings`() {
        val root = createContentRoot("Checked.java", "class Checked {}\n")
        Files.writeString(Path.of(root, "Failed.java"), "class Failed {}\n")

        val proof = runGlobalSimpleProof(root, "Checked.java", "Failed.java")

        assertThat(proof.proofEstablished).isFalse()
        assertThat(proof.failedObligationCount).isEqualTo(1)
        assertThat(proof.executedScopeFileCount).isEqualTo(1)
        assertThat(proof.proofProblems.map { it["description"] })
            .containsExactlyInAnyOrder(HOLDER_FINDING, PROCESSOR_FINDING, FINISHED_FINDING)
    }

    private fun runGlobalSimpleProof(root: String, vararg fileNames: String): BoundedExecutionProofResult {
        val project = projectExtension.project
        val tool = LifecycleGlobalSimpleInspection()
        val profile = registerProfile(project, GlobalInspectionToolWrapper(tool))
        val files = fileNames.map { fileName ->
            val virtualFile = runInEdtAndGet {
                requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(root, fileName)))
            }
            ApplicationManager.getApplication().runReadAction<PsiFile, Exception> {
                requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
            }
        }
        return InspectionHandler().runBoundedExecutionProof(
            enabledTools = InspectionHandler.EnabledFileToolEnumeration(
                emptySet(), 0, emptyList(), setOf(tool.shortName),
            ),
            profile = profile,
            project = project,
            scopeFiles = files,
            analysisDumbModeCount = DumbService.getInstance(project).modificationTracker.modificationCount,
            cancellationCheck = {},
        )
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

    private class LifecycleGlobalSimpleInspection : GlobalSimpleInspectionTool() {
        private var started = false
        private var checkedFile: PsiFile? = null

        override fun getShortName(): String = "StableBuildLifecycleProbe"

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Stable build tests"

        override fun inspectionStarted(
            manager: InspectionManager,
            context: GlobalInspectionContext,
            processor: ProblemDescriptionsProcessor,
        ) {
            started = true
        }

        override fun checkFile(
            file: PsiFile,
            manager: InspectionManager,
            holder: ProblemsHolder,
            context: GlobalInspectionContext,
            processor: ProblemDescriptionsProcessor,
        ) {
            check(started)
            check(file.name != "Failed.java") { "Inspection failed on this file" }
            checkedFile = file
            holder.registerProblem(file, HOLDER_FINDING)
            reportThroughProcessor(file, manager, context, processor, PROCESSOR_FINDING)
        }

        override fun inspectionFinished(
            manager: InspectionManager,
            context: GlobalInspectionContext,
            processor: ProblemDescriptionsProcessor,
        ) {
            reportThroughProcessor(requireNotNull(checkedFile), manager, context, processor, FINISHED_FINDING)
        }

        private fun reportThroughProcessor(
            file: PsiFile,
            manager: InspectionManager,
            context: GlobalInspectionContext,
            processor: ProblemDescriptionsProcessor,
            description: String,
        ) {
            val holder = ProblemsHolder(manager, file, false)
            holder.registerProblem(file, description)
            processor.addProblemElement(requireNotNull(context.refManager.getReference(file)), *holder.resultsArray)
        }
    }

    companion object {
        private const val HOLDER_FINDING = "Holder finding"
        private const val PROCESSOR_FINDING = "Processor finding"
        private const val FINISHED_FINDING = "Finished finding"

        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()

        private val profileCounter = AtomicInteger()
    }
}
