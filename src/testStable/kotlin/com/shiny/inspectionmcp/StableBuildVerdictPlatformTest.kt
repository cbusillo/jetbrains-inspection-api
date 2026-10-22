package com.shiny.inspectionmcp

import com.intellij.codeHighlighting.HighlightDisplayLevel
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
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.ProblemGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

// Runs only in the Stable build (-PinspectionBuild=stable): without native attestation it must never say clean
// when it cannot prove every enabled inspection ran, and it must still report real findings.
class StableBuildVerdictPlatformTest {
    @Test
    fun `a clean directory run remains UNKNOWN`() {
        val project = projectExtension.project
        val profile = registerProfile(project, LocalInspectionToolWrapper(CleanInspection()))

        val status = runScope(project, "scope=directory&dir=${encode(createContentRoot("fixture.txt", "clean\n"))}", profile)

        assertThat(status["inspection_verdict"]?.jsonPrimitive?.content).isEqualTo("UNKNOWN")
        assertThat(status["capture_diagnostic"]?.jsonObject?.get("execution_proof_block_reason")?.jsonPrimitive?.content)
            .isEqualTo(STABLE_BUILD_BROAD_SCOPE_UNPROVEN_REASON)
    }

    @Test
    fun `a finding in a directory run is still RED`() {
        val project = projectExtension.project
        val profile = registerProfile(project, LocalInspectionToolWrapper(FindingInspection()))

        val status = runScope(project, "scope=directory&dir=${encode(createContentRoot("fixture.txt", "finding\n"))}", profile)

        assertThat(status["inspection_verdict"]?.jsonPrimitive?.content).isEqualTo("RED")
    }

    @Test
    fun `a clean files run with only local inspections is GREEN`() {
        val project = projectExtension.project
        val profile = registerProfile(project, LocalInspectionToolWrapper(CleanInspection()))
        val root = createContentRoot("Checked.java", "class Checked {}\n")

        val status = runScope(project, "scope=files&file=${encode(Path.of(root, "Checked.java").toString())}", profile)

        assertThat(status["inspection_verdict"]?.jsonPrimitive?.content).isEqualTo("GREEN")
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

        assertThat(status["inspection_verdict"]?.jsonPrimitive?.content).isEqualTo("GREEN")
    }

    @ParameterizedTest
    @CsvSource("false,false,false", "false,true,false", "true,false,false", "true,true,false",
        "false,false,true", "false,true,true", "true,false,true", "true,true,true")
    fun `global-simple problem groups use the selected native reporting tools`(
        groupEnabled: Boolean,
        useProcessor: Boolean,
        useFallback: Boolean,
    ) {
        val project = projectExtension.project
        val localTool = CleanInspection()
        val globalTool = if (useProcessor) GroupedProcessorInspection() else GroupedHolderInspection()
        val profile = registerProfile(
            project,
            LocalInspectionToolWrapper(localTool),
            GlobalInspectionToolWrapper(globalTool),
        )
        profile.setToolEnabled(localTool.shortName, groupEnabled, project)
        profile.setErrorLevel(requireNotNull(HighlightDisplayKey.find(localTool.shortName)), HighlightDisplayLevel.ERROR, project)
        val root = createContentRoot("Checked.java", "class Checked {}\n")
        val scope = "scope=files&file=${encode(Path.of(root, "Checked.java").toString())}"

        val shouldReport = groupEnabled || !useProcessor
        val problems = if (useFallback) {
            val handler = InspectionHandler()
            val virtualFile = runInEdtAndGet {
                requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(root, "Checked.java")))
            }
            ProgressManager.getInstance().runProcess(
                Computable {
                    ApplicationManager.getApplication().runReadAction<List<Map<String, Any>>, Exception> {
                        val file = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
                        val reportingWrappers = if (groupEnabled) {
                            mapOf(localTool.shortName to LocalInspectionToolWrapper(localTool))
                        } else {
                            emptyMap()
                        }
                        handler.runTargetInspectionEngineOnFile(
                            file, GlobalInspectionToolWrapper(globalTool), profile, reportingWrappers,
                        ).map { problem ->
                            requireNotNull(handler.buildProblemMap(problem.descriptor, problem.reportingWrapper, profile, project))
                        }
                    }
                },
                EmptyProgressIndicator(),
            )
        } else {
            val status = runScope(project, scope, profile)
            assertThat(status["inspection_verdict"]?.jsonPrimitive?.content).isEqualTo(if (shouldReport) "RED" else "GREEN")
            assertThat(status["total_problems"]?.jsonPrimitive?.int).isEqualTo(if (shouldReport) 1 else 0)
            val results = request(InspectionHandler(), "/api/inspection/problems?$scope&project=${encode(project.name)}")
            requireNotNull(results["problems"]).jsonArray.map { problem ->
                problem.jsonObject.mapValues { it.value.jsonPrimitive.content }
            }
        }
        assertThat(problems).hasSize(if (shouldReport) 1 else 0)
        if (shouldReport) {
            val problem = problems.single()
            assertThat(problem["inspectionType"])
                .isEqualTo(if (useProcessor) localTool.shortName else globalTool.shortName)
            assertThat(problem["severity"]).isEqualTo(if (useProcessor) "error" else "warning")
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `global-simple cross-file findings respect the requested scope`(includeOtherFile: Boolean, useProcessor: Boolean) {
        val project = projectExtension.project
        val profile = registerProfile(
            project,
            LocalInspectionToolWrapper(CleanInspection()),
            GlobalInspectionToolWrapper(
                if (useProcessor) CrossFileProcessorInspection() else CrossFileHolderInspection(),
            ),
        )
        val root = createContentRoot("Checked.java", "class Checked {}\n")
        val other = Path.of(root, "Other.java")
        Files.writeString(other, "class Other {}\n")
        runInEdtAndGet { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(other) }

        val scope = "scope=files&file=${encode(Path.of(root, "Checked.java").toString())}" +
            if (includeOtherFile) "&file=${encode(other.toString())}" else ""
        val status = runScope(project, scope, profile)

        assertThat(status["inspection_verdict"]?.jsonPrimitive?.content).isEqualTo(if (includeOtherFile) "RED" else "GREEN")
        assertThat(status["total_problems"]?.jsonPrimitive?.int).isEqualTo(if (includeOtherFile) 1 else 0)
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

            assertThat(status["inspection_verdict"]?.jsonPrimitive?.content).isEqualTo("UNKNOWN")
            assertThat(status["capture_diagnostic"]?.jsonObject?.get("execution_proof_failed_obligation_count")?.jsonPrimitive?.int)
                .isEqualTo(1)
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
    fun `global-simple processor suppression preserves native holder semantics`() {
        val root = createContentRoot("Suppressed.java", "class Suppressed {}\n")

        val proof = runGlobalSimpleProof(root, "Suppressed.java")

        assertThat(proof.proofEstablished).describedAs(proof.toString()).isTrue()
        assertThat(proof.proofProblems.map { it["description"] }).containsExactly(HOLDER_FINDING)
    }

    @Test
    fun `a processor finding without a reference leaves global-simple proof incomplete`() {
        val root = createContentRoot("Checked.java", "class Checked {}\n")
        Files.writeString(Path.of(root, "Other.java"), "class Other {}\n")

        val proof = runGlobalSimpleProof(root, "Checked.java", "Other.java", tool = UnmappedProcessorInspection())

        assertThat(proof.proofEstablished).isFalse()
        assertThat(proof.failedObligationCount).isEqualTo(1)
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

    private fun runGlobalSimpleProof(
        root: String,
        vararg fileNames: String,
        tool: GlobalSimpleInspectionTool = LifecycleGlobalSimpleInspection(),
    ): BoundedExecutionProofResult {
        val project = projectExtension.project
        val profile = registerProfile(project, GlobalInspectionToolWrapper(tool))
        val manager = InspectionManager.getInstance(project) as InspectionManagerEx
        val runningContexts = manager.runningContexts.toSet()
        val files = fileNames.map { fileName ->
            val virtualFile = runInEdtAndGet {
                requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(root, fileName)))
            }
            ApplicationManager.getApplication().runReadAction<PsiFile, Exception> {
                requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
            }
        }
        val proof = InspectionHandler().runBoundedExecutionProof(
            enabledTools = InspectionHandler.EnabledFileToolEnumeration(
                emptySet(), 0, emptyList(), setOf(tool.shortName),
            ),
            profile = profile,
            project = project,
            scopeFiles = files,
            analysisDumbModeCount = DumbService.getInstance(project).modificationTracker.modificationCount,
            cancellationCheck = {},
        )
        assertThat(manager.runningContexts).containsExactlyInAnyOrderElementsOf(runningContexts)
        return proof
    }

    private fun runScope(project: Project, scopeQuery: String, profile: InspectionProfileImpl): JsonObject {
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
            assertThat(trigger["status"]?.jsonPrimitive?.content).isEqualTo("triggered")
            return awaitTerminalStatus(handler, project)
        } finally {
            enhancedTreeExtractorFactory = previousExtractorFactory
        }
    }

    private fun awaitTerminalStatus(handler: InspectionHandler, project: Project): JsonObject {
        val deadline = System.nanoTime() + 180_000_000_000L
        var status: JsonObject? = null
        while (System.nanoTime() < deadline) {
            status = request(handler, "/api/inspection/status?project=${encode(project.name)}")
            if (status["inspection_in_progress"]?.jsonPrimitive?.booleanOrNull == false && status.containsKey("inspection_verdict")) {
                return status
            }
            Thread.sleep(500)
        }
        error("inspection did not finish: $status")
    }

    private fun request(handler: InspectionHandler, uri: String): JsonObject {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri)
            assertThat(handler.process(QueryStringDecoder(uri), request, channel.pipeline().firstContext())).isTrue()
            val deadline = System.nanoTime() + 30_000_000_000L
            var response = channel.readOutbound<Any>()
            while (response == null) {
                check(System.nanoTime() < deadline) { "No response for $uri" }
                Thread.sleep(10)
                channel.runPendingTasks()
                response = channel.readOutbound<Any>()
            }
            val content = (response as? FullHttpResponse)?.content() ?: response as ByteBuf
            return Json.parseToJsonElement(content.toString(Charsets.UTF_8)).jsonObject
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

    private open class GroupedHolderInspection : GlobalSimpleInspectionTool() {
        protected open val useProcessor: Boolean = false

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Stable build tests"

        override fun checkFile(
            file: PsiFile,
            manager: InspectionManager,
            holder: ProblemsHolder,
            context: GlobalInspectionContext,
            processor: ProblemDescriptionsProcessor,
        ) {
            val descriptor = manager.createProblemDescriptor(
                file,
                PROCESSOR_FINDING,
                false,
                null,
                com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            )
            descriptor.problemGroup = object : ProblemGroup {
                override fun getProblemName(): String = CleanInspection().shortName
            }
            if (useProcessor) {
                processor.addProblemElement(context.refManager.getReference(file), descriptor)
            } else {
                holder.registerProblem(descriptor)
            }
        }
    }

    private class GroupedProcessorInspection : GroupedHolderInspection() {
        override val useProcessor: Boolean = true
    }

    private open class CrossFileHolderInspection : GlobalSimpleInspectionTool() {
        protected open val useProcessor: Boolean = false

        protected open val referenceCurrentFile: Boolean = true

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Stable build tests"

        override fun checkFile(
            file: PsiFile,
            manager: InspectionManager,
            holder: ProblemsHolder,
            context: GlobalInspectionContext,
            processor: ProblemDescriptionsProcessor,
        ) {
            val other = requireNotNull(file.containingDirectory.findFile("Other.java"))
            val descriptor = manager.createProblemDescriptor(
                other,
                HOLDER_FINDING,
                false,
                null,
                com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            )
            if (useProcessor) {
                val reference = context.refManager.getReference(if (referenceCurrentFile) file else other)
                processor.addProblemElement(reference, descriptor)
            } else {
                holder.registerProblem(descriptor)
            }
        }
    }

    private class CrossFileProcessorInspection : CrossFileHolderInspection() {
        override val useProcessor: Boolean = true
    }

    private class UnmappedProcessorInspection : CrossFileHolderInspection() {
        override val useProcessor: Boolean = true

        override val referenceCurrentFile: Boolean = false
    }

    private class LifecycleGlobalSimpleInspection : GlobalSimpleInspectionTool() {
        private var started = false
        private var checkedFile: PsiFile? = null

        override fun getShortName(): String = "StableBuildLifecycleProbe"

        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Stable build tests"

        override fun isSuppressedFor(element: com.intellij.psi.PsiElement): Boolean =
            element.containingFile.name == "Suppressed.java"

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
