package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.GlobalInspectionContextBase
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.PairedUnfairLocalInspectionTool
import com.intellij.codeInspection.ex.UnfairLocalInspectionTool
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressWindow
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
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
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Paths
import java.lang.reflect.InvocationTargetException
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    fun `build visitor construction failure escapes inspectEx`() {
        val tool = ThrowingBuildVisitorInspection()
        resetVisits(tool)

        assertThatThrownBy { execute(listOf(tool)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("build visitor construction failure")
        assertThat(visitCount(tool)).isZero()
    }

    @Test
    fun `pre-cancelled indicators escape before inspection execution`() {
        val indicator = EmptyProgressIndicator().apply { cancel() }

        assertThatThrownBy { execute(listOf(CleanInspection()), indicator = indicator) }
            .isInstanceOf(ProcessCanceledException::class.java)
    }

    @Test
    fun `synchronous global boundary opens the non-headless traversal gate and close keeps it closed`() {
        val project = projectExtension.project
        val file = createPhysicalFile()
        val inspectionManager = InspectionManager.getInstance(project) as InspectionManagerEx
        val ordinaryContext = inspectionManager.createNewGlobalContext()
        try {
            assertThatThrownBy { invokeNonHeadlessShouldProcess(ordinaryContext, file) }
                .isInstanceOf(ProcessCanceledException::class.java)
            assertThat(invokeShouldProcess(ordinaryContext, file, headless = true)).isNotNull()

            val boundary = GlobalInspectionContextBoundary.create(inspectionManager)
            try {
                assertThat(invokeNonHeadlessShouldProcess(boundary.contextForTest(), file)).isNotNull()
                runInEdtAndGet { boundary.close(save = true) }
                assertThatThrownBy { invokeNonHeadlessShouldProcess(boundary.contextForTest(), file) }
                    .isInstanceOf(ProcessCanceledException::class.java)
            } finally {
                boundary.cleanup()
                boundary.removeFromRunningContextsSynchronously()
            }
        } finally {
            ordinaryContext.cleanup()
            inspectionManager.runningContexts.remove(ordinaryContext)
        }
    }

    @Test
    fun `synchronous global boundary preserves caller cancellation at native entry`() {
        val project = projectExtension.project
        val file = createPhysicalFile()
        val inspectionManager = InspectionManager.getInstance(project) as InspectionManagerEx
        val boundary = GlobalInspectionContextBoundary.create(inspectionManager)
        val indicator = runInEdtAndGet { ProgressWindow(false, true, project).apply { cancel() } }
        val failure = AtomicReference<Throwable?>()
        try {
            boundary.configure(profileWith(CleanInspection()), AnalysisScope(file))
            val future = ApplicationManager.getApplication().executeOnPooledThread<Unit> {
                try {
                    ProgressManager.getInstance().runProcess(
                        Runnable { boundary.performInspectionsWithProgress(AnalysisScope(file)) },
                        indicator,
                    )
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

            future.get(5, TimeUnit.SECONDS)
            assertThat(failure.get()).isInstanceOf(ProcessCanceledException::class.java)
        } finally {
            boundary.cleanup()
            boundary.removeFromRunningContextsSynchronously()
        }
    }

    @Test
    fun `single-file fallback keeps its parent progress current and returns clean and finding results`() {
        val project = projectExtension.project
        val file = createPhysicalFile()
        val parentTool = CleanInspection()
        val parent = createCompletedParentInspectionContext(file, parentTool)
        val findingTool = FindingInspection()
        val parentTools = parent.context.toolGroups().map { it.shortName }
        val runningContextCount = (InspectionManager.getInstance(project) as InspectionManagerEx).runningContexts.size
        try {
            assertThat(parentTools).contains(parentTool.shortName)
            assertThat(parent.currentIndicator.get()).isNull()

            val cleanDescriptors = runWithCurrentParentIndicator(parent) {
                InspectionHandler().runTargetInspectionEngineOnFile(file, LocalInspectionToolWrapper(CleanInspection()))
            }
            assertThat(cleanDescriptors).isEmpty()
            assertParentContextIntact(parent, parentTools)
            assertThat((InspectionManager.getInstance(project) as InspectionManagerEx).runningContexts)
                .hasSize(runningContextCount)

            val findingDescriptors = runWithCurrentParentIndicator(parent) {
                InspectionHandler().runTargetInspectionEngineOnFile(file, LocalInspectionToolWrapper(findingTool))
            }

            assertThat(findingDescriptors).singleElement().extracting<String> { it.descriptionTemplate }
                .isEqualTo("supported finding")
            assertParentContextIntact(parent, parentTools)
            assertThat((InspectionManager.getInstance(project) as InspectionManagerEx).runningContexts)
                .hasSize(runningContextCount)
        } finally {
            closeParentInspectionContext(parent)
        }
    }

    @Test
    fun `shared parent context is consumed by the raw single-file platform API`() {
        val file = createPhysicalFile()
        val parentTool = CleanInspection()
        val parent = createCompletedParentInspectionContext(file, parentTool)
        try {
            assertThat(parent.context.toolGroups()).isNotEmpty()

            assertThatThrownBy {
                runWithCurrentParentIndicator(parent) {
                    InspectionEngine.runInspectionOnFile(
                        file,
                        LocalInspectionToolWrapper(FindingInspection()),
                        parent.context.publicContext(),
                    )
                }
            }.isInstanceOf(ProcessCanceledException::class.java)

            assertThat(requireNotNull(parent.currentIndicator.get()).isCanceled).isTrue()
            assertThatThrownBy { parent.context.toolGroups() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Tools are not initialized")
        } finally {
            closeParentInspectionContext(parent)
        }
    }

    @Test
    fun `single-file fallback propagates cancellation and removes its isolated context`() {
        val project = projectExtension.project
        val file = createPhysicalFile()
        val inspectionManager = InspectionManager.getInstance(project) as InspectionManagerEx
        val parent = createCompletedParentInspectionContext(file, CleanInspection())
        val runningContextCount = inspectionManager.runningContexts.size

        try {
            assertThatThrownBy {
                runWithCurrentParentIndicator(parent) {
                    InspectionHandler().runTargetInspectionEngineOnFile(file, LocalInspectionToolWrapper(CancellingInspection()))
                }
            }.isInstanceOf(ProcessCanceledException::class.java)

            assertThat(inspectionManager.runningContexts).hasSize(runningContextCount)
            assertThat(requireNotNull(parent.currentIndicator.get()).isCanceled).isFalse()
        } finally {
            closeParentInspectionContext(parent)
        }
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
    fun `bounded proof preempts cooperative inspection before a pending write`() {
        val project = projectExtension.project
        val control = BlockingInspectionControl(CountDownLatch(1), AtomicInteger())
        val writeRan = CountDownLatch(1)
        val observedBeforeToolUnwind = AtomicReference<Boolean>()
        val observedFailure = AtomicReference<ExactProofFailureContext>()
        val observedSource = AtomicReference<InspectionRunFailureSource>()
        blockingInspectionControl.set(control)
        val tool = WritePriorityBlockingInspection()
        val psiFile = createPhysicalFile()
        val profile = profileWith(tool)
        profile.setToolEnabled(tool.shortName, true, project)
        val result = AtomicReference<BoundedExecutionProofResult>()

        val future = ApplicationManager.getApplication().executeOnPooledThread<Unit> {
            result.set(
                InspectionHandler().runBoundedExecutionProof(
                    enabledTools = enabledTools(tool),
                    profile = profile,
                    project = project,
                    scopeFiles = listOf(psiFile),
                    failureObserver = { source, context ->
                        observedSource.set(source)
                        observedFailure.set(context)
                        observedBeforeToolUnwind.set(control.toolExited.get() == 0)
                    },
                    cancellationCheck = {},
                ),
            )
        }

        assertThat(control.enteredTool.await(5, TimeUnit.SECONDS)).isTrue()
        ApplicationManager.getApplication().invokeLater {
            WriteAction.run<RuntimeException> { writeRan.countDown() }
        }

        assertThat(writeRan.await(5, TimeUnit.SECONDS)).isTrue()
        future.get(5, TimeUnit.SECONDS)
        val proof = requireNotNull(result.get())
        assertThat(proof.hitWritePreemption).isTrue()
        assertThat(proof.proofEstablished).isFalse()
        assertThat(proof.proofBlockReason).isEqualTo("write_action_preempted")
        assertThat(observedSource.get()).isEqualTo(InspectionRunFailureSource.EXACT_PROOF_WRITE_PREEMPTED)
        assertThat(observedFailure.get()?.toolShortName).isEqualTo(tool.shortName)
        assertThat(observedFailure.get()?.filePath).isEqualTo(psiFile.virtualFile.path)
        assertThat(observedFailure.get()?.workerThread).isNotNull()
        assertThat(observedBeforeToolUnwind.get()).isTrue()
        assertThat(control.toolExited.get()).isEqualTo(1)
        blockingInspectionControl.compareAndSet(control, null)
    }

    @Test
    fun `bounded proof deadline captures cooperative inspection before unwind`() {
        val project = projectExtension.project
        val control = BlockingInspectionControl(CountDownLatch(1), AtomicInteger())
        val observedBeforeToolUnwind = AtomicReference<Boolean>()
        val observedSource = AtomicReference<InspectionRunFailureSource>()
        blockingInspectionControl.set(control)
        val tool = WritePriorityBlockingInspection()
        val psiFile = createPhysicalFile()
        val profile = profileWith(tool)
        profile.setToolEnabled(tool.shortName, true, project)
        val result = AtomicReference<BoundedExecutionProofResult>()
        val handler = InspectionHandler().apply { boundedExecutionProofTimeoutMs = 250L }

        val future = ApplicationManager.getApplication().executeOnPooledThread<Unit> {
            result.set(
                handler.runBoundedExecutionProof(
                    enabledTools = enabledTools(tool),
                    profile = profile,
                    project = project,
                    scopeFiles = listOf(psiFile),
                    failureObserver = { source, _ ->
                        observedSource.set(source)
                        observedBeforeToolUnwind.set(control.toolExited.get() == 0)
                    },
                    cancellationCheck = {},
                ),
            )
        }

        assertThat(control.enteredTool.await(5, TimeUnit.SECONDS)).isTrue()
        future.get(5, TimeUnit.SECONDS)
        val proof = requireNotNull(result.get())
        assertThat(proof.hitTimeLimit).isTrue()
        assertThat(proof.proofEstablished).isFalse()
        assertThat(proof.proofBlockReason).isEqualTo("time_limit")
        assertThat(observedSource.get()).isEqualTo(InspectionRunFailureSource.EXACT_PROOF_DEADLINE)
        assertThat(observedBeforeToolUnwind.get()).isTrue()
        assertThat(control.toolExited.get()).isEqualTo(1)
        blockingInspectionControl.compareAndSet(control, null)
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
    fun `bounded proof keeps paired unfair missing counterpart fail closed`() {
        val project = projectExtension.project
        val cleanTool = CleanInspection()
        val pairedTool = PairedUnfairInspection()
        val psiFile = createPhysicalFile()
        val profile = profileWith(cleanTool, pairedTool)
        profile.setToolEnabled(cleanTool.shortName, true, project)
        profile.setToolEnabled(pairedTool.shortName, true, project)

        val result = InspectionHandler().runBoundedExecutionProof(
            enabledTools = enabledTools(cleanTool, pairedTool),
            profile = profile,
            project = project,
            scopeFiles = listOf(psiFile),
            cancellationCheck = {},
        )

        assertThat(result.proofEstablished).isFalse()
        assertThat(result.proofBlockReason).isEqualTo("applicable_missing_batch_wrapper")
        assertThat(result.missingWrapperCount).isEqualTo(1)
        assertThat(result.nonBatchExcludedObligationCount).isZero()
        assertThat(result.blockingExamples).anySatisfy { example ->
            assertThat(example["short_name"]).isEqualTo(pairedTool.shortName)
            assertThat(example["missing_batch_wrapper_platform_classification"])
                .isEqualTo("missing_batch_wrapper")
            assertThat(example["source_is_paired_unfair"]).isEqualTo(true)
        }
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
    fun `selected profile severity controls generic inspectEx descriptor severity`() {
        val project = projectExtension.project
        val tool = SeverityProfileFindingInspection()
        val psiFile = createPhysicalFile()
        val profile = profileWith(tool)
        val key = requireNotNull(HighlightDisplayKey.find(tool.shortName))
        val wrapper = requireNotNull(profile.getInspectionTool(tool.shortName, psiFile)) as LocalInspectionToolWrapper
        val descriptor = ReadAction.compute<com.intellij.codeInspection.ProblemDescriptor, RuntimeException> {
            SupportedInspectionExecutor().executePreparedFile(
                psiFile,
                listOf(wrapper),
                EmptyProgressIndicator(),
            ).returnedDescriptorsByToolShortName.getValue(tool.shortName).single()
        }
        assertThat(descriptor.highlightType)
            .isEqualTo(com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        for ((level, expected) in listOf(
            requireNotNull(HighlightDisplayLevel.find("INFORMATION")) to "info",
            HighlightDisplayLevel.WEAK_WARNING to "weak_warning",
            HighlightDisplayLevel.WARNING to "warning",
            HighlightDisplayLevel.ERROR to "error",
        )) {
            profile.setErrorLevel(key, level, project)
            val mapped = ReadAction.compute<Map<String, Any>?, RuntimeException> {
                InspectionHandler().buildProblemMap(descriptor, wrapper, profile, project)
            }
            assertThat(profile.getErrorLevel(key, psiFile)).isEqualTo(level)
            assertThat(mapped).isNotNull()
            assertThat(requireNotNull(mapped)["severity"]).isEqualTo(expected)
        }
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

    private fun createCompletedParentInspectionContext(
        file: PsiFile,
        tool: LocalInspectionTool,
    ): ParentInspectionContext {
        val project = projectExtension.project
        val inspectionManager = InspectionManager.getInstance(project) as InspectionManagerEx
        val context = GlobalInspectionContextBoundary.create(inspectionManager)
        val indicator = runInEdtAndGet {
            ProgressWindow(false, true, project).apply { setDelayInMillis(Int.MAX_VALUE) }
        }
        val scope = AnalysisScope(file)
        val profile = profileWith(tool)
        try {
            context.configure(profile, scope)
            ApplicationManager.getApplication().executeOnPooledThread<Unit> {
                ProgressManager.getInstance().runProcess(
                    Runnable { context.performInspectionsWithProgress(scope) },
                    indicator,
                )
            }.get(5, TimeUnit.SECONDS)
            val toolsField = GlobalInspectionContextBase::class.java.getDeclaredField("myTools")
            check(toolsField.trySetAccessible()) { "Cannot seed native tool state for fallback regression" }
            toolsField.set(context.contextForTest(), linkedMapOf(tool.shortName to profile.getTools(tool.shortName, project)))
            return ParentInspectionContext(context)
        } catch (error: Throwable) {
            runCatching { closeParentInspectionContext(ParentInspectionContext(context)) }
            throw error
        }
    }

    private fun closeParentInspectionContext(context: ParentInspectionContext) {
        try {
            runInEdtAndGet { context.context.close(save = true) }
        } finally {
            context.context.removeFromRunningContextsSynchronously()
        }
    }

    private fun <T> runWithCurrentParentIndicator(
        parent: ParentInspectionContext,
        action: () -> T,
    ): T {
        val indicator = runInEdtAndGet {
            ProgressWindow(false, true, projectExtension.project).apply { setDelayInMillis(Int.MAX_VALUE) }
        }
        setParentProgressIndicator(parent.context.contextForTest(), indicator)
        parent.currentIndicator.set(indicator)
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable?>()
        ApplicationManager.getApplication().executeOnPooledThread<Unit> {
            try {
                ProgressManager.getInstance().runProcess(
                    Runnable {
                        result.set(ReadAction.compute<T, RuntimeException> { action() })
                        ProgressManager.checkCanceled()
                    },
                    indicator,
                )
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.get(5, TimeUnit.SECONDS)
        failure.get()?.let { throw it }
        return result.get()
    }

    private fun assertParentContextIntact(
        parent: ParentInspectionContext,
        expectedToolShortNames: List<String>,
    ) {
        assertThat(requireNotNull(parent.currentIndicator.get()).isCanceled).isFalse()
        assertThat(parent.context.toolGroups().map { it.shortName })
            .containsExactlyInAnyOrderElementsOf(expectedToolShortNames)
    }

    private fun GlobalInspectionContextBoundary.contextForTest(): GlobalInspectionContextImpl {
        return publicContext() as GlobalInspectionContextImpl
    }

    private fun setParentProgressIndicator(
        context: GlobalInspectionContextImpl,
        indicator: ProgressWindow,
    ) {
        val field = GlobalInspectionContextBase::class.java.getDeclaredField("myProgressIndicator")
        check(field.trySetAccessible()) { "Cannot set GlobalInspectionContextBase.myProgressIndicator for fallback regression" }
        field.set(context, indicator)
    }

    private fun invokeNonHeadlessShouldProcess(context: GlobalInspectionContextImpl, file: PsiFile): Any? {
        return invokeShouldProcess(context, file, headless = false)
    }

    private fun invokeShouldProcess(
        context: GlobalInspectionContextImpl,
        file: PsiFile,
        headless: Boolean,
    ): Any? {
        val method = GlobalInspectionContextImpl::class.java.getDeclaredMethod(
            "shouldProcess",
            PsiFile::class.java,
            Boolean::class.javaPrimitiveType,
            Collection::class.java,
        )
        method.isAccessible = true
        return try {
            ReadAction.compute<Any?, RuntimeException> {
                method.invoke(context, file, headless, ArrayList<com.intellij.openapi.vfs.VirtualFile>())
            }
        } catch (error: InvocationTargetException) {
            throw requireNotNull(error.cause)
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

    private class CancellingInspection : RecordingInspection() {
        override fun inspect(holder: ProblemsHolder, file: PsiFile) {
            throw ProcessCanceledException()
        }
    }

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

    private class ThrowingBuildVisitorInspection : RecordingInspection() {
        override fun buildVisitor(
            holder: ProblemsHolder,
            isOnTheFly: Boolean,
            session: LocalInspectionToolSession,
        ): PsiElementVisitor = throw IllegalStateException("build visitor construction failure")
    }

    private class EnabledProfileFindingInspection : FindingInspection()

    private class DisabledProfileFindingInspection : FindingInspection()

    private class SeverityProfileFindingInspection : FindingInspection()

    private class TimeoutProfileInspection : RecordingInspection()

    private class WritePriorityBlockingInspection : RecordingInspection() {
        override fun buildVisitor(
            holder: ProblemsHolder,
            isOnTheFly: Boolean,
            session: LocalInspectionToolSession,
        ): PsiElementVisitor {
            val control = requireNotNull(blockingInspectionControl.get())
            control.enteredTool.countDown()
            try {
                while (true) {
                    CountDownLatch(1).await(25, TimeUnit.MILLISECONDS)
                    ProgressManager.checkCanceled()
                }
            } finally {
                control.toolExited.incrementAndGet()
            }
        }
    }

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
        @Order(0)
        val pluginLibraryRootAccess = PluginLibraryRootAccessExtension()

        @JvmField
        @RegisterExtension
        @Order(1)
        val projectExtension = ProjectExtension()

        private val fileCounter = AtomicInteger()
        private val profileCounter = AtomicInteger()
        private val visitCounts = ConcurrentHashMap<String, AtomicInteger>()
        private val blockingInspectionControl = AtomicReference<BlockingInspectionControl?>()

        private fun resetVisits(tool: LocalInspectionTool) {
            visitCounts.remove(tool.shortName)
        }

        private fun visitCount(tool: LocalInspectionTool): Int = visitCounts[tool.shortName]?.get() ?: 0
    }

    private data class BlockingInspectionControl(
        val enteredTool: CountDownLatch,
        val toolExited: AtomicInteger,
    )

    private data class ParentInspectionContext(
        val context: GlobalInspectionContextBoundary,
        val currentIndicator: AtomicReference<ProgressWindow?> = AtomicReference(),
    )

    class PluginLibraryRootAccessExtension : BeforeAllCallback, AfterAllCallback {
        private var disposable: Disposable? = null

        override fun beforeAll(context: ExtensionContext) {
            val codeSource = Paths.get(PathManager.getJarPathForClass(InspectionHandler::class.java)).toRealPath()
            val libraryRoot = requireNotNull(codeSource.parent) { "Plugin code source has no parent: $codeSource" }
            require(libraryRoot.fileName.toString() == "lib") { "Expected plugin code source below lib: $codeSource" }
            val rootDisposable = Disposer.newDisposable("supported-inspection-plugin-library-root")
            VfsRootAccess.allowRootAccess(rootDisposable, libraryRoot.toString())
            disposable = rootDisposable
        }

        override fun afterAll(context: ExtensionContext) {
            disposable?.let(Disposer::dispose)
            disposable = null
        }
    }
}
