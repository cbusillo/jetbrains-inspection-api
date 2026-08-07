package com.shiny.inspectionmcp

import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.InspectListener
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiFile
import com.intellij.ui.content.ContentManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class NativeInspectionExecutionProofResult(
    val completedNormally: Boolean,
    val expectedFileCount: Int,
    val fileAnalyzedCount: Int,
    val uniqueAnalyzedFileCount: Int,
    val missingExpectedFileCount: Int,
    val unexpectedAnalyzedFileCount: Int,
    val inspectionFinishedCount: Int,
    val localInspectionFinishedCount: Int,
    val globalSimpleInspectionFinishedCount: Int,
    val globalInspectionFinishedCount: Int,
    val otherInspectionFinishedCount: Int,
    val activityFinishedCount: Int,
    val inspectionFailureCount: Int,
    val reportedProblemCount: Int,
    val completedToolCount: Int,
    val failedToolCount: Int,
    val skippedReason: String?,
) {
    val proofEstablished: Boolean
        get() = completedNormally &&
            skippedReason == null &&
            inspectionFailureCount == 0 &&
            expectedFileCount > 0 &&
            missingExpectedFileCount == 0 &&
            unexpectedAnalyzedFileCount == 0 &&
            inspectionFinishedCount > 0

    val proofClean: Boolean
        get() = proofEstablished && reportedProblemCount == 0

    val proofBlockReason: String?
        get() = when {
            skippedReason != null -> skippedReason
            !completedNormally -> "native_inspection_not_completed"
            inspectionFailureCount > 0 -> "native_inspection_failures"
            expectedFileCount == 0 -> "native_inspection_scope_empty"
            missingExpectedFileCount > 0 -> "native_inspection_scope_incomplete"
            unexpectedAnalyzedFileCount > 0 -> "native_inspection_scope_mismatch"
            inspectionFinishedCount == 0 -> "native_inspection_no_tools_completed"
            else -> null
        }
}

internal fun nativeInspectionProofNotEstablishedReason(
    proof: NativeInspectionExecutionProofResult?,
): String? = when {
    proof == null -> "native_attestation_missing"
    proof.proofEstablished -> null
    else -> proof.proofBlockReason ?: "native_attestation_incomplete"
}

@Suppress("UnstableApiUsage")
internal class NativeInspectionExecutionProofCollector(
    private val project: Project,
    expectedFilePaths: Set<String>,
) : InspectListener {
    private val expectedFiles = expectedFilePaths.toSet()
    private val completedNormally = AtomicBoolean(false)
    private val fileAnalyzedCount = AtomicInteger()
    private val inspectionFinishedCount = AtomicInteger()
    private val localInspectionFinishedCount = AtomicInteger()
    private val globalSimpleInspectionFinishedCount = AtomicInteger()
    private val globalInspectionFinishedCount = AtomicInteger()
    private val otherInspectionFinishedCount = AtomicInteger()
    private val activityFinishedCount = AtomicInteger()
    private val inspectionFailureCount = AtomicInteger()
    private val reportedProblemCount = AtomicInteger()
    private val analyzedFiles = ConcurrentHashMap.newKeySet<String>()
    private val completedTools = ConcurrentHashMap.newKeySet<String>()
    private val failedTools = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var skippedReason: String? = null

    override fun fileAnalyzed(file: PsiFile, eventProject: Project) = Unit

    fun recordExactFileAnalyzed(file: PsiFile, eventProject: Project) {
        if (eventProject !== project) return
        fileAnalyzedCount.incrementAndGet()
        runCatching { file.virtualFile?.path }.getOrNull()?.let(analyzedFiles::add)
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    override fun inspectionFinished(
        durationMillis: Long,
        threadId: Long,
        problemCount: Int,
        toolWrapper: InspectionToolWrapper<*, *>,
        inspectionKind: InspectListener.InspectionKind,
        file: PsiFile?,
        eventProject: Project,
    ) {
        if (eventProject !== project) return
        if (
            inspectionKind != InspectListener.InspectionKind.LOCAL &&
            inspectionKind != InspectListener.InspectionKind.LOCAL_PRIORITY
        ) {
            return
        }
        val filePath = runCatching { file?.virtualFile?.path }.getOrNull() ?: return
        if (filePath !in expectedFiles) return
        recordInspectionFinished(problemCount, toolWrapper, inspectionKind)
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    fun recordExactInspectionFinished(
        problemCount: Int,
        toolWrapper: InspectionToolWrapper<*, *>,
        inspectionKind: InspectListener.InspectionKind,
        eventProject: Project,
    ) {
        if (eventProject !== project) return
        if (
            inspectionKind == InspectListener.InspectionKind.LOCAL ||
            inspectionKind == InspectListener.InspectionKind.LOCAL_PRIORITY
        ) {
            return
        }
        recordInspectionFinished(problemCount, toolWrapper, inspectionKind)
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun recordInspectionFinished(
        problemCount: Int,
        toolWrapper: InspectionToolWrapper<*, *>,
        inspectionKind: InspectListener.InspectionKind,
    ) {
        inspectionFinishedCount.incrementAndGet()
        reportedProblemCount.addAndGet(problemCount.coerceAtLeast(0))
        runCatching { toolWrapper.shortName }.getOrNull()?.let(completedTools::add)
        when (inspectionKind) {
            InspectListener.InspectionKind.LOCAL,
            InspectListener.InspectionKind.LOCAL_PRIORITY,
            -> localInspectionFinishedCount.incrementAndGet()
            InspectListener.InspectionKind.GLOBAL_SIMPLE -> globalSimpleInspectionFinishedCount.incrementAndGet()
            InspectListener.InspectionKind.GLOBAL -> globalInspectionFinishedCount.incrementAndGet()
            else -> otherInspectionFinishedCount.incrementAndGet()
        }
    }

    override fun activityFinished(
        durationMillis: Long,
        threadId: Long,
        activityKind: String,
        eventProject: Project,
    ) = Unit

    fun recordExactActivityFinished(eventProject: Project) {
        if (eventProject === project) activityFinishedCount.incrementAndGet()
    }

    override fun inspectionFailed(
        toolShortName: String,
        throwable: Throwable,
        file: PsiFile?,
        eventProject: Project,
    ) {
        if (eventProject !== project) return
        inspectionFailureCount.incrementAndGet()
        failedTools += toolShortName
    }

    fun markCompletedNormally() {
        completedNormally.set(true)
    }

    fun markUnavailable(reason: String) {
        if (skippedReason == null) skippedReason = reason
    }

    fun result(): NativeInspectionExecutionProofResult = NativeInspectionExecutionProofResult(
        completedNormally = completedNormally.get(),
        expectedFileCount = expectedFiles.size,
        fileAnalyzedCount = fileAnalyzedCount.get(),
        uniqueAnalyzedFileCount = analyzedFiles.size,
        missingExpectedFileCount = (expectedFiles - analyzedFiles).size,
        unexpectedAnalyzedFileCount = (analyzedFiles - expectedFiles).size,
        inspectionFinishedCount = inspectionFinishedCount.get(),
        localInspectionFinishedCount = localInspectionFinishedCount.get(),
        globalSimpleInspectionFinishedCount = globalSimpleInspectionFinishedCount.get(),
        globalInspectionFinishedCount = globalInspectionFinishedCount.get(),
        otherInspectionFinishedCount = otherInspectionFinishedCount.get(),
        activityFinishedCount = activityFinishedCount.get(),
        inspectionFailureCount = inspectionFailureCount.get(),
        reportedProblemCount = reportedProblemCount.get(),
        completedToolCount = completedTools.size,
        failedToolCount = failedTools.size,
        skippedReason = skippedReason,
    )
}

@Suppress("UnstableApiUsage")
internal class NativeAttestedGlobalInspectionContext(
    project: Project,
    contentManager: NotNullLazyValue<out ContentManager>,
    collector: NativeInspectionExecutionProofCollector,
) : GlobalInspectionContextImpl(project, contentManager) {
    private val platformPublisher = project.messageBus.syncPublisher(GlobalInspectionContextEx.INSPECT_TOPIC)
    private val attestedPublisher = object : InspectListener {
        override fun fileAnalyzed(file: PsiFile, eventProject: Project) {
            platformPublisher.fileAnalyzed(file, eventProject)
            collector.recordExactFileAnalyzed(file, eventProject)
        }

        override fun inspectionFinished(
            durationMillis: Long,
            threadId: Long,
            problemCount: Int,
            toolWrapper: InspectionToolWrapper<*, *>,
            inspectionKind: InspectListener.InspectionKind,
            file: PsiFile?,
            eventProject: Project,
        ) {
            platformPublisher.inspectionFinished(
                durationMillis,
                threadId,
                problemCount,
                toolWrapper,
                inspectionKind,
                file,
                eventProject,
            )
            collector.recordExactInspectionFinished(problemCount, toolWrapper, inspectionKind, eventProject)
        }

        override fun activityFinished(
            durationMillis: Long,
            threadId: Long,
            activityKind: String,
            eventProject: Project,
        ) {
            platformPublisher.activityFinished(durationMillis, threadId, activityKind, eventProject)
            collector.recordExactActivityFinished(eventProject)
        }

        override fun inspectionFailed(
            toolShortName: String,
            throwable: Throwable,
            file: PsiFile?,
            eventProject: Project,
        ) {
            platformPublisher.inspectionFailed(toolShortName, throwable, file, eventProject)
        }
    }

    override fun getInspectionEventPublisher(): InspectListener = attestedPublisher

    fun openSynchronousFileTraversalGate() {
        myViewClosed = false
    }
}

@Suppress("UnstableApiUsage")
internal fun createAttestedGlobalInspectionContext(
    inspectionManager: InspectionManagerEx,
    project: Project,
    collector: NativeInspectionExecutionProofCollector,
): NativeAttestedGlobalInspectionContext {
    val context = NativeAttestedGlobalInspectionContext(project, inspectionManager.contentManager, collector)
    val runningContexts = inspectionManager.runningContexts
    runningContexts.add(context)
    return context
}
