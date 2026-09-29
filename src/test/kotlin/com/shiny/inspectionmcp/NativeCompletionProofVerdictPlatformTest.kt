package com.shiny.inspectionmcp

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.ExternalLanguageAnnotators
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
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
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class NativeCompletionProofVerdictPlatformTest {
    @Test
    fun `directory run with a C family file and an enabled batch annotator is not clean`() {
        val project = projectExtension.project
        val profile = registerProfile(project, CleanInspection(), BatchAnnotatorInspection())
        val cppStatus = runDirectory(project, createLocalContentRoot("main.cpp"), profile)
        val textStatus = runDirectory(project, createLocalContentRoot("fixture.txt"), profile)
        assertThat(cppStatus).describedAs(cppStatus).contains("\"inspection_verdict\": \"UNKNOWN\"")
        assertThat(cppStatus).describedAs(cppStatus).contains("\"execution_proof_block_reason\": \"$UNPROVEN_C_FAMILY_BATCH_ANNOTATOR_REASON\"")
        assertThat(textStatus).describedAs(textStatus).contains("\"inspection_verdict\": \"GREEN\"")
    }

    @Test
    fun `files runs prove a batch annotator by running it`() {
        val project = projectExtension.project
        fun javaStatus(tool: LocalInspectionTool): String {
            val root = createLocalContentRoot("Checked.java", "class Checked {}\n")
            return runFiles(project, Path.of(root, "Checked.java").toString(), registerProfile(project, CleanInspection(), tool))
        }
        val clean = javaStatus(JavaOnlyBatchAnnotatorInspection())
        val finding = javaStatus(FindingBatchAnnotatorInspection())
        val failing = javaStatus(FailingBatchAnnotatorInspection())
        assertThat(clean).describedAs(clean).contains("\"inspection_verdict\": \"GREEN\"")
        assertThat(finding).describedAs(finding).contains("\"inspection_verdict\": \"RED\"")
        assertThat(finding).describedAs(finding).contains("\"total_problems\": 1,")
        assertThat(failing).describedAs(failing).contains("\"inspection_verdict\": \"UNKNOWN\"")
    }

    @Test
    fun `a batch annotator paired with an annotator for the file is not clean outside C family`() {
        val project = projectExtension.project
        val profile = registerProfile(project, CleanInspection(), BatchAnnotatorInspection())
        val pairing = Disposer.newDisposable("paired-batch-annotator")
        try {
            ExternalLanguageAnnotators.INSTANCE.addExplicitExtension(
                PlainTextLanguage.INSTANCE, PairedExternalAnnotator(BatchAnnotatorInspection().shortName), pairing,
            )
            val root = createLocalContentRoot("check.txt")
            val directoryStatus = runDirectory(project, root, profile)
            assertThat(directoryStatus).describedAs(directoryStatus).contains("\"inspection_verdict\": \"UNKNOWN\"")
            assertThat(directoryStatus).describedAs(directoryStatus)
                .contains("\"execution_proof_block_reason\": \"$UNPROVEN_BATCH_ANNOTATOR_REASON\"")
        } finally {
            Disposer.dispose(pairing)
        }
    }

    @Test
    fun `a batch annotator declaring the file's language is not clean`() {
        val project = projectExtension.project
        val profile = registerProfile(project, CleanInspection(), XmlOnlyBatchAnnotatorInspection())
        val xmlStatus = runDirectory(project, createLocalContentRoot("declared.xml", "<declared/>\n"), profile)
        val textStatus = runDirectory(project, createLocalContentRoot("undeclared.txt"), profile)
        assertThat(xmlStatus).describedAs(xmlStatus).contains("\"inspection_verdict\": \"UNKNOWN\"")
        assertThat(xmlStatus).describedAs(xmlStatus).contains("\"execution_proof_block_reason\": \"$UNPROVEN_BATCH_ANNOTATOR_REASON\"")
        assertThat(textStatus).describedAs(textStatus).contains("\"inspection_verdict\": \"GREEN\"")
    }

    @Test
    fun `batch annotators restricted to another language do not block a C family file`() {
        val project = projectExtension.project
        val profile = registerProfile(project, JavaOnlyBatchAnnotatorInspection())
        val root = requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(Path.of(createLocalContentRoot("main.cpp"))))
        val file = ReadAction.compute<PsiFile, RuntimeException> {
            requireNotNull(PsiManager.getInstance(project).findFile(requireNotNull(root.findChild("main.cpp"))))
        }
        val applicableProfile = registerProfile(project, BatchAnnotatorInspection())
        ReadAction.run<RuntimeException> {
            assertThat(unprovenBatchAnnotatorReason(profile.getAllEnabledInspectionTools(project), listOf(file), false)).isNull()
            assertThat(unprovenBatchAnnotatorReason(applicableProfile.getAllEnabledInspectionTools(project), listOf(file), false))
                .isEqualTo(UNPROVEN_C_FAMILY_BATCH_ANNOTATOR_REASON)
        }
    }

    @Test
    fun `module interface files count as C family and exhausted budgets fail closed`() {
        val project = projectExtension.project
        val profile = registerProfile(project, BatchAnnotatorInspection())
        val root = requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(Path.of(createLocalContentRoot("module.cxxm"))))
        val file = ReadAction.compute<PsiFile, RuntimeException> {
            requireNotNull(PsiManager.getInstance(project).findFile(requireNotNull(root.findChild("module.cxxm"))))
        }
        val groups = profile.getAllEnabledInspectionTools(project)
        ReadAction.run<RuntimeException> {
            assertThat(unprovenBatchAnnotatorReason(groups, listOf(file), false)).isEqualTo(UNPROVEN_C_FAMILY_BATCH_ANNOTATOR_REASON)
            var budgetChecks = 0
            assertThatThrownBy {
                unprovenBatchAnnotatorReason(groups, listOf(file), false) {
                    budgetChecks += 1
                    if (budgetChecks > groups.size) throw NativeInspectionObservationUnavailable("time_limit")
                }
            }.isInstanceOf(NativeInspectionObservationUnavailable::class.java)
        }
    }

    private fun runDirectory(project: Project, directory: String, profile: InspectionProfileImpl): String =
        runScope(project, "scope=directory&dir=${encode(directory)}", profile)

    private fun runFiles(project: Project, file: String, profile: InspectionProfileImpl): String =
        runScope(project, "scope=files&file=${encode(file)}", profile)

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

    private fun createLocalContentRoot(fileName: String, content: String = "int main() { return 0; }\n"): String {
        val root = Files.createTempDirectory("native-completion-proof-")
        Files.writeString(root.resolve(fileName), content)
        runInEdtAndGet {
            val directory = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root))
            VfsUtil.markDirtyAndRefresh(false, true, true, directory)
            PsiTestUtil.addContentRoot(projectExtension.module, directory)
        }
        return root.toString()
    }

    private class CleanInspection : LocalInspectionTool() {
        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "C family batch annotator tests"

        override fun buildVisitor(
            holder: ProblemsHolder,
            isOnTheFly: Boolean,
            session: LocalInspectionToolSession,
        ): PsiElementVisitor = object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) = Unit
        }
    }

    private class BatchAnnotatorInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
        override fun getShortName(): String = "CFamilyBatchAnnotatorProbe"

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "C family batch annotator tests"
    }

    private class FindingBatchAnnotatorInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
        override fun getShortName(): String = "FindingBatchAnnotatorProbe"

        override fun getLanguage(): String = "JAVA"

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "C family batch annotator tests"

        override fun checkFile(file: PsiFile, context: GlobalInspectionContext, manager: InspectionManager): Array<ProblemDescriptor> =
            ReadAction.compute<Array<ProblemDescriptor>, RuntimeException> {
                arrayOf(manager.createProblemDescriptor(file, BATCH_ANNOTATOR_FINDING, false, emptyArray(), ProblemHighlightType.GENERIC_ERROR_OR_WARNING))
            }
    }

    private class FailingBatchAnnotatorInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
        override fun getShortName(): String = "FailingBatchAnnotatorProbe"

        override fun getLanguage(): String = "JAVA"

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "C family batch annotator tests"

        override fun checkFile(file: PsiFile, context: GlobalInspectionContext, manager: InspectionManager): Array<ProblemDescriptor> =
            throw IllegalStateException("external tool failed")
    }

    private class PairedExternalAnnotator(private val batchShortName: String) : ExternalAnnotator<Unit, Unit>() {
        override fun getPairedBatchInspectionShortName(): String = batchShortName
    }

    private class XmlOnlyBatchAnnotatorInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
        override fun getShortName(): String = "XmlOnlyBatchAnnotatorProbe"

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "C family batch annotator tests"

        override fun getLanguage(): String = "XML"
    }

    private class JavaOnlyBatchAnnotatorInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
        override fun getShortName(): String = "CFamilyJavaOnlyBatchAnnotatorProbe"

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "C family batch annotator tests"

        override fun getLanguage(): String = "JAVA"
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()

        private val profileCounter = AtomicInteger()
        private const val BATCH_ANNOTATOR_FINDING = "batch annotator finding"
    }
}
