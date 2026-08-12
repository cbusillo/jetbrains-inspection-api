package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.PairedUnfairLocalInspectionTool
import com.intellij.codeInspection.ex.UnfairLocalInspectionTool
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.profile.codeInspection.BaseInspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.runInEdtAndGet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SupportedInspectionExecutorPlatformTest {
    @Test
    fun `records findings and the submitted scope file`() {
        val tool = FindingInspection()
        resetVisits(tool)

        val result = execute(listOf(tool))

        assertThat(result.scopeFileCount).isEqualTo(1)
        assertThat(result.problemDescriptorCount).isEqualTo(1)
        assertThat(result.fileResults.single().suppliedToolShortNames).containsExactly(tool.shortName)
        assertThat(result.fileResults.single().returnedDescriptorsByToolShortName[tool.shortName])
            .singleElement()
            .extracting<String> { it.descriptionTemplate }
            .isEqualTo("supported finding")
        assertThat(visitCount(tool)).isEqualTo(1)
    }

    @Test
    fun `normal clean execution is distinguishable only by submitted tool evidence`() {
        val tool = CleanInspection()
        resetVisits(tool)

        val result = execute(listOf(tool))

        val fileResult = result.fileResults.single()
        assertThat(fileResult.suppliedToolShortNames).containsExactly(tool.shortName)
        assertThat(fileResult.returnedDescriptorsByToolShortName).isEmpty()
        assertThat(visitCount(tool)).isEqualTo(1)
    }

    @Test
    fun `sparse results retain findings while omitting clean tools`() {
        val findingTool = FindingInspection()
        val cleanTool = CleanInspection()

        val result = execute(listOf(findingTool, cleanTool))

        val fileResult = result.fileResults.single()
        assertThat(fileResult.suppliedToolShortNames)
            .containsExactlyInAnyOrder(findingTool.shortName, cleanTool.shortName)
        assertThat(fileResult.returnedDescriptorsByToolShortName.keys)
            .containsExactly(findingTool.shortName)
    }

    @Test
    fun `language inapplicable tools are omitted before visiting`() {
        val tool = JavaOnlyInspection()
        resetVisits(tool)

        val result = execute(listOf(tool))

        assertThat(result.fileResults.single().suppliedToolShortNames).containsExactly(tool.shortName)
        assertThat(result.fileResults.single().returnedDescriptorsByToolShortName).isEmpty()
        assertThat(visitCount(tool)).isZero()
    }

    @Test
    fun `suppressed findings are omitted when suppression filtering is enabled`() {
        val tool = SuppressedFindingInspection()

        val filtered = execute(listOf(tool), ignoreSuppressedElements = true)
        val unfiltered = execute(listOf(tool), ignoreSuppressedElements = false)

        assertThat(filtered.problemDescriptorCount).isZero()
        assertThat(unfiltered.problemDescriptorCount).isEqualTo(1)
    }

    @Test
    fun `tool failures escape instead of becoming clean results`() {
        assertThatThrownBy { execute(listOf(ThrowingInspection())) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("supported inspection failure")
    }

    @Test
    fun `pre-cancelled indicators escape before inspection execution`() {
        val indicator = EmptyProgressIndicator().apply { cancel() }

        assertThatThrownBy { execute(listOf(CleanInspection()), indicator = indicator) }
            .isInstanceOf(ProcessCanceledException::class.java)
    }

    @Test
    fun `an empty caller-selected wrapper set records no submitted tools`() {
        val tool = FindingInspection()
        resetVisits(tool)

        val result = execute(emptyList())

        assertThat(result.fileResults.single().suppliedToolShortNames).isEmpty()
        assertThat(result.problemDescriptorCount).isZero()
        assertThat(visitCount(tool)).isZero()
    }

    @Test
    fun `explicit analysis scope returns every distinct physical file`() {
        val project = projectExtension.project
        val firstFile = createPhysicalFile()
        val secondFile = createPhysicalFile()
        val scope = AnalysisScope(project, setOf(firstFile.virtualFile, secondFile.virtualFile))

        val result = execute(scope, listOf(CleanInspection()))

        assertThat(result.fileResults.map { it.filePath })
            .containsExactlyInAnyOrder(firstFile.virtualFile.path, secondFile.virtualFile.path)
    }

    @Test
    fun `inspectEx routing accepts local wrappers and preserves global fallback`() {
        assertThat(canExecuteWithInspectEx(LocalInspectionToolWrapper(CleanInspection()))).isTrue()
        assertThat(canExecuteWithInspectEx(GlobalInspectionToolWrapper(TestGlobalInspection()))).isFalse()
        assertThat(canExecuteWithInspectEx(GlobalInspectionToolWrapper(TestGlobalSimpleInspection()))).isFalse()
    }

    @Test
    fun `selected profile disables one obligation without weakening executed findings`() {
        val project = projectExtension.project
        val enabledTool = EnabledProfileFindingInspection()
        val disabledTool = DisabledProfileFindingInspection()
        resetVisits(enabledTool)
        resetVisits(disabledTool)
        val psiFile = createPhysicalFile()
        val profile = profileWith(enabledTool, disabledTool)
        profile.setToolEnabled(enabledTool.shortName, true, project)
        profile.setToolEnabled(disabledTool.shortName, false, project)

        val result = InspectionHandler().runBoundedExecutionProof(
            enabledTools = enabledTools(enabledTool, disabledTool),
            profile = profile,
            project = project,
            scopeFiles = listOf(psiFile),
            cancellationCheck = {},
        )

        assertThat(result.profileEnabledObligationCount).isEqualTo(1)
        assertThat(result.profileDisabledObligationCount).isEqualTo(1)
        assertThat(result.batchRunnableObligationCount).isEqualTo(1)
        assertThat(result.executedToolCount).isEqualTo(1)
        assertThat(result.proofEstablished).isTrue()
        assertThat(result.proofProblems).hasSize(1)
        assertThat(visitCount(enabledTool)).isEqualTo(1)
        assertThat(visitCount(disabledTool)).isZero()
    }

    @Test
    fun `zero proof deadline deterministically remains unproven`() {
        val project = projectExtension.project
        val tool = TimeoutProfileInspection()
        val psiFile = createPhysicalFile()
        val profile = profileWith(tool)
        val handler = InspectionHandler().apply { boundedExecutionProofTimeoutMs = 0 }

        val result = handler.runBoundedExecutionProof(
            enabledTools = enabledTools(tool),
            profile = profile,
            project = project,
            scopeFiles = listOf(psiFile),
            cancellationCheck = {},
        )

        assertThat(result.hitTimeLimit).isTrue()
        assertThat(result.proofEstablished).isFalse()
        assertThat(result.proofBlockReason).isEqualTo("time_limit")
        assertThat(result.unvisitedClassificationObligationCount).isEqualTo(1)
    }

    @Test
    fun `unpaired unfair tool is the only intentionally non-batch platform classification`() {
        val project = projectExtension.project
        val psiFile = createPhysicalFile()
        val tool = UnpairedUnfairInspection()
        val wrapper = LocalInspectionToolWrapper(tool)
        val profile = profileWith(tool)

        assertThat(
            LocalInspectionToolWrapper.findTool2RunInBatch(project, psiFile, profile, wrapper),
        ).isNull()

        val metadata = classifyMissingBatchWrapperPlatformMetadata(
            wrapper,
        )

        assertThat(metadata.classification)
            .isEqualTo(MissingBatchWrapperPlatformClassification.INTENTIONALLY_NON_BATCH)
        assertThat(metadata.sourceIsUnfair).isTrue()
        assertThat(metadata.sourceIsPairedUnfair).isFalse()
    }

    @Test
    fun `bounded proof excludes unpaired unfair tool when another tool executes on the file`() {
        val project = projectExtension.project
        val cleanTool = CleanInspection()
        val unfairTool = UnpairedUnfairInspection()
        resetVisits(cleanTool)
        resetVisits(unfairTool)
        val psiFile = createPhysicalFile()
        val profile = profileWith(cleanTool, unfairTool)
        profile.setToolEnabled(cleanTool.shortName, true, project)
        profile.setToolEnabled(unfairTool.shortName, true, project)

        val result = InspectionHandler().runBoundedExecutionProof(
            enabledTools = enabledTools(cleanTool, unfairTool),
            profile = profile,
            project = project,
            scopeFiles = listOf(psiFile),
            cancellationCheck = {},
        )

        assertThat(result.proofEstablished).isTrue()
        assertThat(result.batchRunnableObligationCount).isEqualTo(1)
        assertThat(result.nonBatchExcludedObligationCount).isEqualTo(1)
        assertThat(result.executedToolCount).isEqualTo(1)
        assertThat(result.executedScopeFileCount).isEqualTo(1)
        val nonBatchExample = result.nonBatchExamples.single()
        assertThat(nonBatchExample["short_name"]).isEqualTo(unfairTool.shortName)
        assertThat(nonBatchExample["missing_batch_wrapper_platform_classification"])
            .isEqualTo("intentionally_non_batch")
        assertThat(visitCount(cleanTool)).isEqualTo(1)
        assertThat(visitCount(unfairTool)).isZero()
    }

    @Test
    fun `bounded proof does not report clean when a file has only unpaired unfair tools`() {
        val project = projectExtension.project
        val unfairTool = UnpairedUnfairInspection()
        val psiFile = createPhysicalFile()
        val profile = profileWith(unfairTool)
        profile.setToolEnabled(unfairTool.shortName, true, project)

        val result = InspectionHandler().runBoundedExecutionProof(
            enabledTools = enabledTools(unfairTool),
            profile = profile,
            project = project,
            scopeFiles = listOf(psiFile),
            cancellationCheck = {},
        )

        assertThat(result.proofEstablished).isFalse()
        assertThat(result.proofBlockReason).isEqualTo("no_applicable_batch_tools")
        assertThat(result.nonBatchExcludedObligationCount).isEqualTo(1)
        assertThat(result.executedToolCount).isZero()
        assertThat(result.missingScopeExecutionCoverageCount).isEqualTo(1)
    }

    @Test
    fun `paired unfair and fair missing wrappers remain fail closed`() {
        val project = projectExtension.project
        val psiFile = createPhysicalFile()
        val pairedTool = PairedUnfairInspection()
        val pairedWrapper = LocalInspectionToolWrapper(pairedTool)
        val pairedProfile = profileWith(pairedTool)

        assertThat(
            LocalInspectionToolWrapper.findTool2RunInBatch(project, psiFile, pairedProfile, pairedWrapper),
        ).isNull()

        val pairedMetadata = classifyMissingBatchWrapperPlatformMetadata(
            pairedWrapper,
        )
        val fairMetadata = classifyMissingBatchWrapperPlatformMetadata(
            LocalInspectionToolWrapper(CleanInspection()),
        )

        assertThat(pairedMetadata.classification)
            .isEqualTo(MissingBatchWrapperPlatformClassification.MISSING_BATCH_WRAPPER)
        assertThat(pairedMetadata.sourceIsUnfair).isTrue()
        assertThat(pairedMetadata.sourceIsPairedUnfair).isTrue()
        assertThat(pairedMetadata.pairedBatchShortName).isEqualTo("MissingPairedBatchInspection")
        assertThat(fairMetadata.classification)
            .isEqualTo(MissingBatchWrapperPlatformClassification.MISSING_BATCH_WRAPPER)
        assertThat(fairMetadata.sourceIsUnfair).isFalse()
    }

    @Test
    fun `bounded example counters distinguish platform classifications`() {
        val examples = listOf(
            MissingBatchWrapperPlatformMetadata(
                MissingBatchWrapperPlatformClassification.INTENTIONALLY_NON_BATCH,
            ).diagnosticFields(),
            MissingBatchWrapperPlatformMetadata(
                MissingBatchWrapperPlatformClassification.METADATA_UNAVAILABLE,
            ).diagnosticFields(),
            emptyMap(),
        )

        assertThat(
            countMissingBatchWrapperPlatformClassificationExamples(
                examples,
                MissingBatchWrapperPlatformClassification.INTENTIONALLY_NON_BATCH,
            ),
        ).isEqualTo(1)
        assertThat(
            countMissingBatchWrapperPlatformClassificationExamples(
                examples,
                MissingBatchWrapperPlatformClassification.METADATA_UNAVAILABLE,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `unreadable unfair metadata remains fail closed`() {
        val metadata = classifyMissingBatchWrapperPlatformMetadata(
            ThrowingUnfairMetadataWrapper(CleanInspection()),
        )

        assertThat(metadata.classification)
            .isEqualTo(MissingBatchWrapperPlatformClassification.METADATA_UNAVAILABLE)
        assertThat(metadata.failureStage).isEqualTo("source_is_unfair")
    }

    @Test
    fun `selected profile severity raises inspectEx descriptor severity`() {
        val project = projectExtension.project
        val tool = SeverityProfileFindingInspection()
        val psiFile = createPhysicalFile()
        val profile = profileWith(tool)
        val key = requireNotNull(HighlightDisplayKey.find(tool.shortName))
        profile.setErrorLevel(key, HighlightDisplayLevel.ERROR, project)
        val wrapper = requireNotNull(profile.getInspectionTool(tool.shortName, psiFile)) as LocalInspectionToolWrapper
        val descriptor = ReadAction.compute<com.intellij.codeInspection.ProblemDescriptor, RuntimeException> {
            SupportedInspectionExecutor().executePreparedFile(
                psiFile,
                listOf(wrapper),
                EmptyProgressIndicator(),
            ).returnedDescriptorsByToolShortName.getValue(tool.shortName).single()
        }
        val mapped = InspectionHandler().buildProblemMap(descriptor, wrapper, profile, project)

        assertThat(profile.getErrorLevel(key, psiFile)).isEqualTo(HighlightDisplayLevel.ERROR)
        assertThat(mapped).isNotNull()
        assertThat(mapped!!["severity"]).isEqualTo("error")
    }

    @Test
    fun `expected and submitted files match for exact directory and project scopes`() {
        val project = projectExtension.project
        val outsideFile = createPhysicalFile()
        val directoryFiles = createPhysicalDirectoryFiles(2)
        val exactScope = AnalysisScope(project, directoryFiles.mapTo(linkedSetOf()) { it.virtualFile })
        val directoryScope = ReadAction.compute<AnalysisScope, RuntimeException> {
            AnalysisScope(requireNotNull(directoryFiles.first().containingDirectory))
        }
        val projectScope = AnalysisScope(project)
        val handler = InspectionHandler()

        assertScopeParity(handler, exactScope, directoryFiles.mapTo(linkedSetOf()) { it.virtualFile.path })
        assertScopeParity(handler, directoryScope, directoryFiles.mapTo(linkedSetOf()) { it.virtualFile.path })

        val projectPaths = scopePaths(handler, projectScope)
        assertThat(projectPaths.first).isEqualTo(projectPaths.second)
        assertThat(projectPaths.first).contains(outsideFile.virtualFile.path)
        assertThat(projectPaths.first).containsAll(directoryFiles.map { it.virtualFile.path })
    }

    private fun execute(
        tools: List<LocalInspectionTool>,
        indicator: EmptyProgressIndicator = EmptyProgressIndicator(),
        ignoreSuppressedElements: Boolean = true,
    ): SupportedInspectionExecutionResult {
        return execute(
            AnalysisScope(createPhysicalFile()),
            tools,
            indicator,
            ignoreSuppressedElements,
        )
    }

    private fun execute(
        scope: AnalysisScope,
        tools: List<LocalInspectionTool>,
        indicator: EmptyProgressIndicator = EmptyProgressIndicator(),
        ignoreSuppressedElements: Boolean = true,
    ): SupportedInspectionExecutionResult {
        return ReadAction.compute<SupportedInspectionExecutionResult, RuntimeException> {
            SupportedInspectionExecutor().execute(
                scope,
                tools.map(::LocalInspectionToolWrapper),
                indicator,
                ignoreSuppressedElements,
            )
        }
    }

    private fun createPhysicalFile(): PsiFile {
        val project = projectExtension.project
        val module = projectExtension.module
        return runInEdtAndGet {
            WriteAction.compute<PsiFile, RuntimeException> {
                val contentRoot = ModuleRootManager.getInstance(module).contentRoots.single()
                val virtualFile = contentRoot.createChildData(this, "supported-inspection-${fileCounter.incrementAndGet()}.txt")
                VfsUtil.saveText(virtualFile, "supported inspection fixture")
                requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
            }
        }
    }

    private fun createPhysicalDirectoryFiles(count: Int): List<PsiFile> {
        val project = projectExtension.project
        val module = projectExtension.module
        return runInEdtAndGet {
            WriteAction.compute<List<PsiFile>, RuntimeException> {
                val contentRoot = ModuleRootManager.getInstance(module).contentRoots.single()
                val directory = contentRoot.createChildDirectory(this, "evidence-directory-${fileCounter.incrementAndGet()}")
                (1..count).map { index ->
                    val virtualFile = directory.createChildData(this, "fixture-$index.txt")
                    VfsUtil.saveText(virtualFile, "directory evidence $index")
                    requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
                }
            }
        }
    }

    private fun assertScopeParity(handler: InspectionHandler, scope: AnalysisScope, expectedPaths: Set<String>) {
        val paths = scopePaths(handler, scope)
        assertThat(paths.first).isEqualTo(paths.second)
        assertThat(paths.first).containsExactlyInAnyOrderElementsOf(expectedPaths)
    }

    private fun scopePaths(handler: InspectionHandler, scope: AnalysisScope): Pair<Set<String>, Set<String>> =
        ReadAction.compute<Pair<Set<String>, Set<String>>, RuntimeException> {
            handler.nativeInspectionExpectedFilePaths(scope) to
                SupportedInspectionExecutor().collectScopeFiles(scope)
                    .mapNotNullTo(linkedSetOf()) { it.virtualFile?.path }
        }

    private fun profileWith(vararg tools: LocalInspectionTool): InspectionProfileImpl {
        val project = projectExtension.project
        val wrappers = tools.map(::LocalInspectionToolWrapper)
        wrappers.forEach { HighlightDisplayKey.findOrRegister(it.shortName, it.displayName) }
        val supplier = InspectionToolsSupplier.Simple(wrappers.map { it as InspectionToolWrapper<*, *> })
        val manager = InspectionProfileManager.getInstance() as BaseInspectionProfileManager
        val profile = InspectionProfileImpl("evidence-${profileCounter.incrementAndGet()}", supplier, manager)
        val previousInitInspections = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true
        try {
            profile.initInspectionTools(project)
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = previousInitInspections
        }
        return profile
    }

    private fun enabledTools(vararg tools: LocalInspectionTool): InspectionHandler.EnabledLocalToolEnumeration =
        InspectionHandler.EnabledLocalToolEnumeration(
            shortNames = tools.mapTo(linkedSetOf()) { it.shortName },
            errorCount = 0,
            errorExamples = emptyList(),
        )

    private open class RecordingInspection : LocalInspectionTool() {
        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Supported Inspection Tests"

        override fun buildVisitor(
            holder: ProblemsHolder,
            isOnTheFly: Boolean,
            session: LocalInspectionToolSession,
        ): PsiElementVisitor = object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                visitCounts.computeIfAbsent(shortName) { AtomicInteger() }.incrementAndGet()
                inspect(holder, file)
            }
        }

        open fun inspect(holder: ProblemsHolder, file: PsiFile) = Unit
    }

    private class CleanInspection : RecordingInspection()

    private open class FindingInspection : RecordingInspection() {
        override fun inspect(holder: ProblemsHolder, file: PsiFile) {
            holder.registerProblem(file, "supported finding")
        }
    }

    private class JavaOnlyInspection : RecordingInspection() {
        override fun getLanguage(): String = "JAVA"
    }

    private class SuppressedFindingInspection : FindingInspection() {
        override fun isSuppressedFor(element: PsiElement): Boolean = true
    }

    private class ThrowingInspection : RecordingInspection() {
        override fun inspect(holder: ProblemsHolder, file: PsiFile) {
            throw IllegalStateException("supported inspection failure")
        }
    }

    private class EnabledProfileFindingInspection : FindingInspection()

    private class DisabledProfileFindingInspection : FindingInspection()

    private class SeverityProfileFindingInspection : FindingInspection()

    private class TimeoutProfileInspection : RecordingInspection()

    private class UnpairedUnfairInspection : RecordingInspection(), UnfairLocalInspectionTool

    private class PairedUnfairInspection : RecordingInspection(), PairedUnfairLocalInspectionTool {
        override fun getInspectionForBatchShortName(): String = "MissingPairedBatchInspection"
    }

    private class ThrowingUnfairMetadataWrapper(tool: LocalInspectionTool) : LocalInspectionToolWrapper(tool) {
        override fun isUnfair(): Boolean = throw IllegalStateException("metadata unavailable")
    }

    private open class TestGlobalInspection : GlobalInspectionTool() {
        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Supported Inspection Tests"
    }

    private class TestGlobalSimpleInspection : TestGlobalInspection() {
        override fun isGlobalSimpleInspectionTool(): Boolean = true
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()

        private val fileCounter = AtomicInteger()
        private val profileCounter = AtomicInteger()
        private val visitCounts = ConcurrentHashMap<String, AtomicInteger>()

        private fun resetVisits(tool: LocalInspectionTool) {
            visitCounts.remove(tool.shortName)
        }

        private fun visitCount(tool: LocalInspectionTool): Int = visitCounts[tool.shortName]?.get() ?: 0
    }
}
