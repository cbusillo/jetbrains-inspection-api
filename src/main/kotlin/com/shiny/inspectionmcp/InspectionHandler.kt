package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.ide.DataManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressWindow
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.shiny.inspectionmcp.core.filterProblems
import com.shiny.inspectionmcp.core.formatJsonManually
import com.shiny.inspectionmcp.core.InspectionRouteIdentity
import com.shiny.inspectionmcp.core.InspectionRouteProject
import com.shiny.inspectionmcp.core.InspectionRouteSelector
import com.shiny.inspectionmcp.core.effectiveProjectRoot
import com.shiny.inspectionmcp.core.compileFilePatternRegex
import com.shiny.inspectionmcp.core.normalizeProblemsScope
import com.shiny.inspectionmcp.core.paginateProblems
import com.shiny.inspectionmcp.core.projectRootFromIdeaMetadataPath
import com.shiny.inspectionmcp.core.projectRootFromProjectFilePath
import com.shiny.inspectionmcp.core.scoreInspectionRouteCandidates
import com.shiny.inspectionmcp.core.usesPatternFileSyntax
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.ide.HttpRequestHandler
import java.awt.Component
import java.awt.Container
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JTree
import javax.swing.tree.TreeNode

private const val EXACT_PROJECT_PATH_SELECTOR_PREFIX = "exact-project-path:"
private const val EXACT_WORKTREE_PATH_SELECTOR_PREFIX = "exact-worktree-path:"
internal const val LIFECYCLE_OWNERSHIP_PROTOCOL = "lease_bound_v1"

internal fun lifecycleOpenProjectTask(openPath: Path, onBeforeInit: (Project) -> Unit): OpenProjectTask =
    OpenProjectTaskCompat.build(openPath, onBeforeInit)

internal fun normalizeOptionalFilter(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isBlank()) {
        return null
    }
    if (trimmed.equals("all", ignoreCase = true)) {
        return null
    }
    return trimmed
}

internal enum class InspectionSnapshotOutcome(val apiValue: String) {
    PROBLEMS_FOUND("problems_found"),
    CLEAN_CONFIRMED("clean_confirmed"),
    CAPTURE_INCOMPLETE("capture_incomplete"),
}

internal enum class InspectionModelVerdict(val apiValue: String) {
    CLEAN("clean"),
    HAS_PROBLEMS("has_problems"),
    UNREADABLE("unreadable"),
    UNKNOWN("unknown"),
}

internal enum class CaptureIncompleteReason(val apiValue: String) {
    VIEW_NOT_READY("view_not_ready"),
    VIEW_UPDATING_UNREADABLE("view_updating_unreadable"),
    UNREADABLE_TREE("unreadable_tree"),
    EXTRACTOR_FAILURE("extractor_failure"),
    NON_EMPTY_UNMAPPED_TREE("non_empty_unmapped_tree"),
    CURRENT_RUN_PSI_CHURN("current_run_psi_churn"),
    INSPECTION_TRIGGER_EMPTY_MODEL("inspection_trigger_empty_model"),
    TIMEOUT("timeout"),
    PROFILE_RESOLUTION_ERROR("profile_resolution_error"),
    SCOPE_NOT_COVERED("scope_not_covered"),
    HELPER_PLUGIN_ERROR("helper_plugin_error"),
    UNKNOWN("unknown"),
}

internal data class InspectionProjectStateSnapshot(
    val psiModificationCount: Long,
    val unsavedProjectDocuments: Int,
)

internal data class InspectionCaptureScope(
    val scopeParam: String? = null,
    val directoryParam: String? = null,
    val files: List<String>? = null,
    val resolvedCurrentFile: String? = null,
    val resolvedFiles: List<String>? = null,
    val resolvedDirectory: String? = null,
    val includeUnversioned: Boolean = true,
    val changedFilesMode: String? = null,
    val maxFiles: Int? = null,
)

internal data class InspectionResultsSnapshot(
    val problems: List<Map<String, Any>>,
    val timestamp: Long,
    val projectState: InspectionProjectStateSnapshot,
    val outcome: InspectionSnapshotOutcome,
    val source: String,
    val note: String? = null,
    val captureScope: InspectionCaptureScope? = null,
    val captureDiagnostic: Map<String, Any?>? = null,
    val captureIncompleteReason: CaptureIncompleteReason? = null,
    val runId: Long? = null,
    val triggerTimeMs: Long? = null,
)

internal data class InspectionViewObservation(
    val isUpdating: Boolean,
    val hasProblems: Boolean,
    val rootChildCount: Int?,
    val updateStateReadable: Boolean = true,
    val problemStateReadable: Boolean = true,
)

internal data class InspectionModelExtraction(
    val problems: List<Map<String, Any>>,
    val problemDescriptorCount: Int,
    val enabledToolCount: Int,
    val readableToolCount: Int,
    val unreadableToolCount: Int,
    val unreadableReasons: List<String> = emptyList(),
    val targetToolStates: List<Map<String, Any?>> = emptyList(),
) {
    constructor(
        problems: List<Map<String, Any>>,
        problemDescriptorCount: Int,
        enabledToolCount: Int,
        readableToolCount: Int,
        unreadableToolCount: Int,
        unreadableReasons: List<String> = emptyList(),
    ) : this(
        problems = problems,
        problemDescriptorCount = problemDescriptorCount,
        enabledToolCount = enabledToolCount,
        readableToolCount = readableToolCount,
        unreadableToolCount = unreadableToolCount,
        unreadableReasons = unreadableReasons,
        targetToolStates = emptyList(),
    )

    val verdict: InspectionModelVerdict
        get() = when {
            problems.isNotEmpty() || problemDescriptorCount > 0 -> InspectionModelVerdict.HAS_PROBLEMS
            unreadableToolCount > 0 -> InspectionModelVerdict.UNREADABLE
            enabledToolCount > 0 && readableToolCount == enabledToolCount -> InspectionModelVerdict.CLEAN
            else -> InspectionModelVerdict.UNKNOWN
        }
}

internal data class InspectionCaptureSnapshotInput(
    val bestResults: List<Map<String, Any>>,
    val bestSource: String,
    val snapshotTimeMs: Long,
    val projectState: InspectionProjectStateSnapshot,
    val emptyOutcome: InspectionSnapshotOutcome,
    val emptyNote: String?,
    val captureScope: InspectionCaptureScope,
    val captureDiagnostic: Map<String, Any?>?,
    val runId: Long,
    val triggerTimeMs: Long?,
    val viewReadyOk: Boolean,
)

internal fun buildInspectionCaptureSnapshot(input: InspectionCaptureSnapshotInput): InspectionResultsSnapshot {
    val captureIncompleteReason = classifyCaptureIncompleteReason(input.captureDiagnostic)
    return when {
        input.bestResults.isNotEmpty() -> InspectionResultsSnapshot(
            problems = input.bestResults,
            timestamp = input.snapshotTimeMs,
            projectState = input.projectState,
            outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
            source = input.bestSource,
            captureScope = input.captureScope,
            runId = input.runId,
            triggerTimeMs = input.triggerTimeMs,
        )

        input.emptyOutcome == InspectionSnapshotOutcome.CLEAN_CONFIRMED -> InspectionResultsSnapshot(
            problems = emptyList(),
            timestamp = input.snapshotTimeMs,
            projectState = input.projectState,
            outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
            source = "inspection_view",
            captureScope = input.captureScope,
            captureDiagnostic = input.captureDiagnostic,
            runId = input.runId,
            triggerTimeMs = input.triggerTimeMs,
        )

        else -> InspectionResultsSnapshot(
            problems = emptyList(),
            timestamp = input.snapshotTimeMs,
            projectState = input.projectState,
            outcome = InspectionSnapshotOutcome.CAPTURE_INCOMPLETE,
            source = if (input.viewReadyOk) "inspection_view" else "tool_window",
            note = input.emptyNote,
            captureScope = input.captureScope,
            captureDiagnostic = input.captureDiagnostic,
            captureIncompleteReason = captureIncompleteReason,
            runId = input.runId,
            triggerTimeMs = input.triggerTimeMs,
        )
    }
}

private data class CurrentRunPsiChurnReconciliation(
    val snapshot: InspectionResultsSnapshot?,
    val reconciled: Boolean,
)

private data class InspectionVerdict(
    val verdict: String,
    val reason: String,
    val message: String,
    val nextAction: String,
)

internal fun readInspectionRootChildCount(root: Any?): Int? {
    return when (root) {
        null -> null
        is TreeNode -> root.childCount
        else -> try {
            val childCountMethod = root.javaClass.getMethod("getChildCount")
            (childCountMethod.invoke(root) as? Number)?.toInt()
        } catch (_: Exception) {
            null
        }
    }
}

internal data class InspectionRunState(
    val runId: Long,
    val triggerTimeMs: Long,
    val inProgress: Boolean,
    val captureScope: InspectionCaptureScope? = null,
) {
    constructor(runId: Long, triggerTimeMs: Long, inProgress: Boolean) : this(
        runId = runId,
        triggerTimeMs = triggerTimeMs,
        inProgress = inProgress,
        captureScope = null,
    )
}

internal data class InspectionRunControl(
    val runId: Long,
    val indicator: ProgressIndicator,
    val cancellationRequested: AtomicBoolean = AtomicBoolean(false),
)

private data class InspectionProof(
    val summary: Map<String, Any?>,
    val failures: List<String>,
)

internal data class InspectionProjectLease(
    val closeToken: String,
    val leaseId: String,
    val projectKey: String,
    val projectInstanceId: String,
    val basePath: String?,
    val sessionId: String,
    val claimedAtMs: Long,
)

internal fun parseGitStatusPorcelainZ(output: String): Pair<Set<String>, Set<String>> {
    val staged = mutableSetOf<String>()
    val unstaged = mutableSetOf<String>()
    var i = 0
    while (i < output.length) {
        val zero = output.indexOf('\u0000', i)
        if (zero == -1) break
        val entry = output.substring(i, zero)
        i = zero + 1

        if (entry.length < 3) {
            continue
        }

        val x = entry[0]
        val y = entry[1]
        val spaceIdx = entry.indexOf(' ', 2)
        if (spaceIdx < 2) {
            continue
        }

        val pathPart = entry.substring(spaceIdx + 1).trimStart()
        val normalized = pathPart.replace('\\', '/')
        if (normalized.isNotBlank()) {
            if (x != ' ' && x != '?' && x != '!') staged.add(normalized)
            if (y != ' ' && y != '!') unstaged.add(normalized)
        }

        if (x == 'R' || x == 'C') {
            val nextZero = output.indexOf('\u0000', i)
            i = if (nextZero == -1) output.length else nextZero + 1
        }
    }
    return Pair(staged, unstaged)
}

internal data class BoundedProcessOutput(
    val exitCode: Int?,
    val output: ByteArray,
    val timedOut: Boolean,
    val outputLimitExceeded: Boolean,
)

private fun destroyProcessTree(process: Process) {
    runCatching {
        process.descendants().use { descendants ->
            descendants.forEach { descendant -> descendant.destroyForcibly() }
        }
    }
    process.destroyForcibly()
}

internal fun collectBoundedProcessOutput(
    process: Process,
    timeoutMs: Long,
    maxOutputBytes: Int,
): BoundedProcessOutput {
    val output = AtomicReference(ByteArray(0))
    val readerFailure = AtomicReference<Throwable?>(null)
    var interrupted = false
    val reader = Thread.ofVirtual().start {
        try {
            output.set(process.inputStream.readNBytes(maxOutputBytes + 1))
        } catch (error: Throwable) {
            readerFailure.set(error)
        }
    }
    val completed = try {
        process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        interrupted = true
        false
    }
    if (!completed) {
        destroyProcessTree(process)
        try {
            process.waitFor(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    try {
        reader.join(1000L)
    } catch (_: InterruptedException) {
        interrupted = true
    }
    if (reader.isAlive) {
        destroyProcessTree(process)
        reader.interrupt()
    }
    if (interrupted) {
        Thread.currentThread().interrupt()
        throw InterruptedException("Interrupted while collecting process output.")
    }
    val captured = output.get()
    val exceeded = captured.size > maxOutputBytes
    if (readerFailure.get() != null && completed && !exceeded) {
        throw readerFailure.get()!!
    }
    return BoundedProcessOutput(
        exitCode = if (completed) runCatching { process.exitValue() }.getOrNull() else null,
        output = if (exceeded) captured.copyOf(maxOutputBytes) else captured,
        timedOut = !completed || reader.isAlive,
        outputLimitExceeded = exceeded,
    )
}

internal class BadRequestException(
    val parameter: String,
    override val message: String,
) : RuntimeException(message)

internal class InspectionRunConflictException(
    val runId: Long,
) : RuntimeException("Inspection run $runId is already in progress for this project.")

internal fun projectKey(project: Project): String {
    val basePath = runCatching { project.basePath }.getOrNull()
    normalizeFileSystemPath(basePath)?.let { return "path:$it" }

    val projectFilePath = runCatching { project.projectFilePath }.getOrNull()
    normalizeFileSystemPath(projectFilePath)?.let { return "file:$it" }

    return "name:${project.name}"
}

@Suppress("SameReturnValue")
private fun normalizeFileSystemPath(value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val expanded = if (raw.startsWith("~")) {
        System.getProperty("user.home") + raw.removePrefix("~")
    } else {
        raw
    }
    return try {
        Paths.get(expanded).normalize().toAbsolutePath().toString()
    } catch (_: Exception) {
        null
    }
}

private fun looksLikePath(value: String): Boolean {
    return value.contains('/') || value.contains('\\') || value.startsWith("~") || value.startsWith(".")
}

internal fun inspectionCaptureScopeCoversRequest(
    captureScope: InspectionCaptureScope?,
    requestScope: InspectionCaptureScope,
): Boolean {
    val captureKind = captureScope?.scopeParam?.trim()?.lowercase().orEmpty().ifBlank { "whole_project" }
    val requestKind = requestScope.scopeParam?.trim()?.lowercase().orEmpty().ifBlank { "whole_project" }
    if (captureScope == null || captureKind == "whole_project") return true
    if (requestKind == "whole_project") return false

    fun normalizedFiles(scope: InspectionCaptureScope): Set<String> {
        return scope.resolvedFiles.orEmpty().mapNotNull(::normalizeFileSystemPath).toSet()
    }

    fun isWithinDirectory(path: String, directory: String): Boolean {
        return runCatching {
            Paths.get(path).normalize().toAbsolutePath().startsWith(
                Paths.get(directory).normalize().toAbsolutePath(),
            )
        }.getOrDefault(false)
    }

    val captureFiles = normalizedFiles(captureScope)
    val requestFiles = normalizedFiles(requestScope)
    return when (captureKind) {
        "directory" -> {
            val captureDirectory = normalizeFileSystemPath(captureScope.resolvedDirectory) ?: return false
            when (requestKind) {
                "directory" -> normalizeFileSystemPath(requestScope.resolvedDirectory)
                    ?.let { isWithinDirectory(it, captureDirectory) } == true
                "files", "current_file" -> requestFiles.isNotEmpty() &&
                    requestFiles.all { isWithinDirectory(it, captureDirectory) }
                "changed_files" -> requestFiles.all { isWithinDirectory(it, captureDirectory) }
                else -> false
            }
        }
        "files", "current_file", "changed_files" -> when (requestKind) {
            "files", "current_file" -> requestFiles.isNotEmpty() && captureFiles.containsAll(requestFiles)
            "changed_files" -> captureFiles.containsAll(requestFiles)
            else -> false
        }
        else -> false
    }
}

internal fun inspectionCaptureScopeHasProof(captureScope: InspectionCaptureScope?): Boolean {
    if (captureScope == null) return true
    return when (captureScope.scopeParam?.trim()?.lowercase().orEmpty().ifBlank { "whole_project" }) {
        "whole_project" -> true
        "files" -> captureScope.resolvedFiles?.isNotEmpty() == true
        "directory" -> !captureScope.resolvedDirectory.isNullOrBlank()
        "current_file" -> !captureScope.resolvedCurrentFile.isNullOrBlank()
        "changed_files" -> captureScope.resolvedFiles != null
        else -> false
    }
}

internal object InspectionResultsStore {
    private val snapshotsByProject = java.util.concurrent.ConcurrentHashMap<String, InspectionResultsSnapshot>()

    fun getSnapshot(projectName: String): InspectionResultsSnapshot? {
        return snapshotsByProject[projectName]
    }

    fun getTimestamp(projectName: String): Long? {
        return snapshotsByProject[projectName]?.timestamp
    }

    fun getProjectState(projectName: String): InspectionProjectStateSnapshot? {
        return snapshotsByProject[projectName]?.projectState
    }

    fun getRunId(projectName: String): Long? {
        return snapshotsByProject[projectName]?.runId
    }

    fun setSnapshot(projectName: String, snapshot: InspectionResultsSnapshot) {
        snapshotsByProject[projectName] = snapshot
    }

    fun replaceSnapshot(
        projectName: String,
        expected: InspectionResultsSnapshot,
        replacement: InspectionResultsSnapshot,
    ): Boolean {
        return snapshotsByProject.replace(projectName, expected, replacement)
    }

    fun clear(projectName: String) {
        snapshotsByProject.remove(projectName)
    }
}

internal var enhancedTreeExtractorFactory: () -> EnhancedTreeExtractor = { EnhancedTreeExtractor() }
internal var recentProjectsManagerProvider: () -> RecentProjectsManagerBase? = {
    try {
        RecentProjectsManagerBase.getInstanceEx()
    } catch (_: Exception) {
        null
    }
}

internal fun classifyEmptyInspectionCapture(
    viewReadyOk: Boolean,
    observedInspectionView: Boolean,
    observedSettledEmptyInspectionView: Boolean,
    observedStableReadableEmptyInspectionView: Boolean,
    observedStableEmptyResultsWithoutInspectionView: Boolean,
    observedModelCleanInspection: Boolean = false,
    observedNonEmptyInspectionTree: Boolean,
    suspiciousEmptyModelReason: String? = null,
): Pair<InspectionSnapshotOutcome, String?> {
    if (!suspiciousEmptyModelReason.isNullOrBlank()) {
        return InspectionSnapshotOutcome.CAPTURE_INCOMPLETE to
            "Inspection finished with an empty model in a proof lane where findings were expected. Treat this as an IDE/plugin/helper issue, not a clean result."
    }

    if (
        viewReadyOk &&
            !observedNonEmptyInspectionTree &&
            (
                (observedInspectionView && (observedSettledEmptyInspectionView || observedStableReadableEmptyInspectionView)) ||
                    observedStableEmptyResultsWithoutInspectionView ||
                    observedModelCleanInspection
                )
    ) {
        return InspectionSnapshotOutcome.CLEAN_CONFIRMED to null
    }

    return InspectionSnapshotOutcome.CAPTURE_INCOMPLETE to
        "Inspection finished, but the plugin could not conclusively confirm that the IDE results were empty. Re-run the inspection or open the Inspection Results/Problems tool window."
}

internal fun suspiciousEmptyInspectionModelReason(
    ideProductCode: String?,
    requestedProfileName: String?,
    modelVerdict: InspectionModelVerdict,
    problemDescriptorCount: Int,
    bestResultsEmpty: Boolean,
    observedNonEmptyInspectionTree: Boolean,
): String? {
    val isSupportedProofLane =
        ideProductCode.equals("WS", ignoreCase = true) ||
            ideProductCode.equals("IU", ignoreCase = true) ||
            ideProductCode.equals("IC", ignoreCase = true) ||
            isPyCharmProductCode(ideProductCode)
    val isRedLaneProof = requestedProfileName.equals("RedLane", ignoreCase = true)
    return if (
        isSupportedProofLane &&
            isRedLaneProof &&
            bestResultsEmpty &&
            !observedNonEmptyInspectionTree &&
            modelVerdict == InspectionModelVerdict.CLEAN &&
            problemDescriptorCount == 0
    ) {
        CaptureIncompleteReason.INSPECTION_TRIGGER_EMPTY_MODEL.apiValue
    } else {
        null
    }
}

internal fun isPyCharmProductCode(ideProductCode: String?): Boolean {
    return ideProductCode.equals("PY", ignoreCase = true) || ideProductCode.equals("PC", ignoreCase = true)
}

internal fun isSettledCleanInspectionView(observation: InspectionViewObservation): Boolean {
    return observation.updateStateReadable &&
        observation.problemStateReadable &&
        observation.rootChildCount != null &&
        !observation.isUpdating &&
        !observation.hasProblems
}

internal fun isReadableEmptyInspectionView(observation: InspectionViewObservation): Boolean {
    return observation.updateStateReadable &&
        observation.problemStateReadable &&
        !observation.isUpdating &&
        observation.rootChildCount == 0 &&
        !observation.hasProblems
}

internal fun isTransientUpdatingUnreadableEmptyCandidate(observation: InspectionViewObservation): Boolean {
    return observation.updateStateReadable &&
        observation.problemStateReadable &&
        observation.isUpdating &&
        !observation.hasProblems &&
        observation.rootChildCount == 0
}

internal fun isOpaqueSettledEmptyInspectionViewCandidate(observation: InspectionViewObservation): Boolean {
    return observation.updateStateReadable &&
        observation.problemStateReadable &&
        !observation.isUpdating &&
        !observation.hasProblems &&
        observation.rootChildCount == null
}

internal fun shouldPromoteStableReadableEmptyInspectionView(
    readableEmptyInspectionViewStableSince: Long?,
    readableEmptyInspectionViewObservationCount: Int,
    transientUpdatingEmptyObservationCount: Int = 0,
    inspectionViewUpdating: Boolean,
    now: Long,
    pollingElapsedMs: Long,
    minStableMs: Long = 5000L,
    minPollingMs: Long = 30000L,
    minReadableEmptyObservations: Int = 2,
    minTransientUpdatingEmptyObservations: Int = 5,
): Boolean {
    val stableSince = readableEmptyInspectionViewStableSince ?: return false
    val hasEmptyEvidence = readableEmptyInspectionViewObservationCount >= minReadableEmptyObservations ||
        transientUpdatingEmptyObservationCount >= minTransientUpdatingEmptyObservations
    return hasEmptyEvidence &&
        !inspectionViewUpdating &&
        now - stableSince >= minStableMs &&
        pollingElapsedMs >= minPollingMs
}

internal fun hasInspectionViewProblems(observation: InspectionViewObservation): Boolean {
    return observation.hasProblems
}

internal fun filterProblemsForScope(
    problems: List<Map<String, Any>>,
    scopeProblemMatcher: ((Map<String, Any>) -> Boolean)?,
): List<Map<String, Any>> {
    return scopeProblemMatcher?.let { matcher ->
        problems.filter(matcher)
    } ?: problems
}

internal fun hasUsableInspectionViewEvidence(
    inspectionViewObservationCount: Int,
    nullRootChildObservationCount: Int,
    observedSettledEmptyInspectionView: Boolean,
    observedStableReadableEmptyInspectionView: Boolean,
    observedNonEmptyInspectionTree: Boolean,
): Boolean {
    return observedSettledEmptyInspectionView ||
        observedStableReadableEmptyInspectionView ||
        observedNonEmptyInspectionTree ||
        (inspectionViewObservationCount > 0 && nullRootChildObservationCount < inspectionViewObservationCount)
}

internal fun selectTrustedToolResults(
    toolResults: List<Map<String, Any>>,
    compatibleToolResults: List<Map<String, Any>>,
    observedInspectionView: Boolean,
    hasScopedMatcher: Boolean,
): List<Map<String, Any>> {
    return when {
        observedInspectionView -> compatibleToolResults
        hasScopedMatcher -> compatibleToolResults
        else -> toolResults
    }
}

internal fun cleanWaitHasSettled(
    now: Long,
    resultsTimestampMs: Long?,
    cleanStableSince: Long?,
    timeSinceTriggerMs: Long?,
    minStableMs: Long = 5000L,
    minTimeSinceTriggerMs: Long = 15000L,
): Boolean {
    val cleanStableStart = resultsTimestampMs ?: cleanStableSince ?: now
    val cleanStableEnough = now - cleanStableStart >= minStableMs
    val triggerSettled = timeSinceTriggerMs == null || timeSinceTriggerMs >= minTimeSinceTriggerMs
    return cleanStableEnough && triggerSettled
}

internal fun resultsWaitHasSettled(
    now: Long,
    resultsStableSince: Long?,
    timeSinceTriggerMs: Long?,
    minStableMs: Long = 5000L,
    minTimeSinceTriggerMs: Long = 15000L,
): Boolean {
    val resultsStableStart = resultsStableSince ?: now
    val resultsStableEnough = now - resultsStableStart >= minStableMs
    val triggerSettled = timeSinceTriggerMs == null || timeSinceTriggerMs >= minTimeSinceTriggerMs
    return resultsStableEnough && triggerSettled
}

internal fun noResultsWaitHasSettled(
    now: Long,
    noResultsStableSince: Long?,
    timeSinceTriggerMs: Long?,
    minStableMs: Long = 5000L,
    minTimeSinceTriggerMs: Long = 15000L,
    immediateNoResultsAfterTriggerMs: Long = 60000L,
): Boolean {
    val timeSinceTrigger = timeSinceTriggerMs ?: return false
    if (timeSinceTrigger >= immediateNoResultsAfterTriggerMs) {
        return true
    }

    val stableStart = noResultsStableSince ?: now
    val stableEnough = now - stableStart >= minStableMs
    val triggerSettled = timeSinceTrigger >= minTimeSinceTriggerMs
    return stableEnough && triggerSettled
}

internal fun shouldStopCapturePolling(
    viewReadyOk: Boolean,
    observedInspectionView: Boolean,
    inspectionViewUpdating: Boolean,
    observedSettledEmptyInspectionView: Boolean,
    observedStableReadableEmptyInspectionView: Boolean,
    observedStableEmptyResultsWithoutInspectionView: Boolean = false,
    observedModelCleanInspection: Boolean = false,
    bestResultsCount: Int,
    stableForMs: Long,
    pollingElapsedMs: Long,
    minStableMs: Long = 5000L,
    minResultsWaitMs: Long = 15000L,
    minEmptyResultsWaitMs: Long = 15000L,
    minReadableEmptyResultsWaitMs: Long = 30000L,
    maxFallbackWaitMs: Long = 60000L,
): Boolean {
    if (bestResultsCount > 0 && stableForMs >= minStableMs && pollingElapsedMs >= minResultsWaitMs) {
        return true
    }

    if (
        viewReadyOk &&
            observedInspectionView &&
            !inspectionViewUpdating &&
            observedSettledEmptyInspectionView &&
            stableForMs >= minStableMs &&
            pollingElapsedMs >= minEmptyResultsWaitMs
    ) {
        return true
    }

    if (
        viewReadyOk &&
            observedInspectionView &&
            !inspectionViewUpdating &&
            observedStableReadableEmptyInspectionView &&
            stableForMs >= minStableMs &&
            pollingElapsedMs >= minReadableEmptyResultsWaitMs
    ) {
        return true
    }

    if (observedStableEmptyResultsWithoutInspectionView) {
        return true
    }

    if (
        viewReadyOk &&
            observedModelCleanInspection &&
            stableForMs >= minStableMs &&
            pollingElapsedMs >= minReadableEmptyResultsWaitMs
    ) {
        return true
    }

    if ((!viewReadyOk || !observedInspectionView) && stableForMs >= minStableMs && pollingElapsedMs >= maxFallbackWaitMs) {
        return true
    }

    return false
}

internal fun shouldTrustStableScopedEmptyResults(
    viewReadyOk: Boolean,
    observedInspectionView: Boolean = false,
    inspectionViewUpdating: Boolean = false,
    hasSettledInspectionViewEvidence: Boolean = false,
    hasOpaqueSettledEmptyInspectionViewEvidence: Boolean = false,
    hasTransientEmptyInspectionViewEvidence: Boolean = false,
    hasModelCleanEvidence: Boolean = false,
    extractionSucceeded: Boolean = true,
    hasScopedMatcher: Boolean,
    scopedContextResultsEmpty: Boolean,
    bestResultsEmpty: Boolean,
    observedNonEmptyInspectionTree: Boolean,
    stableForMs: Long,
    pollingElapsedMs: Long,
    minStableMs: Long = 5000L,
    minPollingMs: Long = 30000L,
    maxUpdatingInspectionViewWaitMs: Long = 60000L,
): Boolean {
    val hasUsableInspectionViewEvidence = !observedInspectionView ||
        (!inspectionViewUpdating && hasSettledInspectionViewEvidence) ||
        (!inspectionViewUpdating && hasOpaqueSettledEmptyInspectionViewEvidence && extractionSucceeded) ||
        hasModelCleanEvidence ||
        (
            inspectionViewUpdating &&
                hasTransientEmptyInspectionViewEvidence &&
                pollingElapsedMs >= maxUpdatingInspectionViewWaitMs
        )

    return viewReadyOk &&
        hasUsableInspectionViewEvidence &&
        extractionSucceeded &&
        hasScopedMatcher &&
        scopedContextResultsEmpty &&
        bestResultsEmpty &&
        !observedNonEmptyInspectionTree &&
        stableForMs >= minStableMs &&
        pollingElapsedMs >= minPollingMs
}

internal fun shouldTreatScopedEmptyExtractionAsSucceeded(
    lastExtractionCycleSucceeded: Boolean,
    observedTransientEmptyInspectionViewEvidence: Boolean,
    lastToolExtractionSucceeded: Boolean,
): Boolean {
    return lastExtractionCycleSucceeded ||
        (observedTransientEmptyInspectionViewEvidence && lastToolExtractionSucceeded)
}

internal fun shouldTreatNonEmptyInspectionTreeAsStaleCleanEvidence(
    observedNonEmptyInspectionTree: Boolean,
    modelExtractionClean: Boolean,
    modelProblemDescriptorCount: Int,
    bestResultsEmpty: Boolean,
    extractionFailureCount: Int,
    lastExtractionCycleSucceeded: Boolean,
    lastToolExtractionSucceeded: Boolean,
    inspectionViewUpdating: Boolean,
    stableForMs: Long,
    pollingElapsedMs: Long,
    minStableMs: Long = 5000L,
    minPollingMs: Long = 30000L,
): Boolean {
    return observedNonEmptyInspectionTree &&
        modelExtractionClean &&
        modelProblemDescriptorCount == 0 &&
        bestResultsEmpty &&
        extractionFailureCount == 0 &&
        lastExtractionCycleSucceeded &&
        lastToolExtractionSucceeded &&
        !inspectionViewUpdating &&
        stableForMs >= minStableMs &&
        pollingElapsedMs >= minPollingMs
}

internal fun classifyCaptureIncompleteReason(
    captureDiagnostic: Map<String, Any?>?,
    snapshotChangeKind: String? = null,
    fallbackReason: CaptureIncompleteReason = CaptureIncompleteReason.UNKNOWN,
): CaptureIncompleteReason {
    if (snapshotChangeKind == CaptureIncompleteReason.CURRENT_RUN_PSI_CHURN.apiValue) {
        return CaptureIncompleteReason.CURRENT_RUN_PSI_CHURN
    }

    val diagnostic = captureDiagnostic ?: return fallbackReason
    val exitReason = diagnostic["exit_reason"] as? String
    val viewReadyOk = diagnostic["view_ready_ok"] as? Boolean
    val observedInspectionView = diagnostic["observed_inspection_view"] as? Boolean
    val inspectionViewUpdating = diagnostic["inspection_view_updating"] as? Boolean
    val observedNonEmptyInspectionTree = diagnostic["observed_non_empty_inspection_tree"] as? Boolean
    val extractionFailureCount = (diagnostic["extraction_failure_count"] as? Number)?.toInt() ?: 0
    val successfulExtractionCount = (diagnostic["successful_extraction_count"] as? Number)?.toInt() ?: 0
    val lastExtractionCycleSucceeded = diagnostic["last_extraction_cycle_succeeded"] as? Boolean
    val unreadableProblemStateObservationCount =
        (diagnostic["unreadable_problem_state_observation_count"] as? Number)?.toInt() ?: 0
    val nullRootChildObservationCount = (diagnostic["null_root_child_observation_count"] as? Number)?.toInt() ?: 0
    val inspectionViewObservationCount = (diagnostic["inspection_view_observation_count"] as? Number)?.toInt() ?: 0

    return when {
        exitReason == "helper_plugin_error" -> CaptureIncompleteReason.HELPER_PLUGIN_ERROR
        exitReason == CaptureIncompleteReason.INSPECTION_TRIGGER_EMPTY_MODEL.apiValue ->
            CaptureIncompleteReason.INSPECTION_TRIGGER_EMPTY_MODEL
        observedNonEmptyInspectionTree == true -> CaptureIncompleteReason.NON_EMPTY_UNMAPPED_TREE
        inspectionViewUpdating == true &&
            (unreadableProblemStateObservationCount > 0 || nullRootChildObservationCount > 0) ->
            CaptureIncompleteReason.VIEW_UPDATING_UNREADABLE
        unreadableProblemStateObservationCount > 0 ||
            (
                inspectionViewObservationCount > 0 &&
                    nullRootChildObservationCount in inspectionViewObservationCount..Int.MAX_VALUE
                ) ->
            CaptureIncompleteReason.UNREADABLE_TREE
        extractionFailureCount > 0 &&
            (successfulExtractionCount == 0 || lastExtractionCycleSucceeded == false) ->
            CaptureIncompleteReason.EXTRACTOR_FAILURE
        exitReason == "deadline" || exitReason == "timeout" -> CaptureIncompleteReason.TIMEOUT
        viewReadyOk == false || observedInspectionView == false -> CaptureIncompleteReason.VIEW_NOT_READY
        else -> fallbackReason
    }
}

class InspectionHandler : HttpRequestHandler() {
    private val logger = Logger.getInstance(InspectionHandler::class.java)

    private data class ProjectSuggestion(
        val name: String,
        val path: String,
        val score: Int,
    )

    private data class ResolvedInspectionRoute(
        val project: Project,
        val identity: Map<String, Any?>,
        val projectIdentity: Map<String, Any?>,
        val score: Int? = null,
    )

    private data class LifecycleOpenTarget(
        val path: Path,
        val openPath: Path,
        val projectRoot: Path,
        val key: String,
    )

    private data class LifecycleOpenRequest(
        val leaseId: String?,
    )

    internal data class LifecycleOpenOwnership(
        val leaseId: String,
        val targetKey: String,
    )

    private data class LifecycleCloseAttempt(
        val attempt: Int,
        val save: Boolean,
        val forceCloseReturned: Boolean,
        val closedVerified: Boolean,
        val projectDisposed: Boolean,
    )
    
    private val runIdSequence = AtomicLong()
    private val inspectionRunStatesByProject = java.util.concurrent.ConcurrentHashMap<String, InspectionRunState>()
    private val inspectionRunControlsByProject = java.util.concurrent.ConcurrentHashMap<String, InspectionRunControl>()
    private val leasesByProjectInstance = java.util.concurrent.ConcurrentHashMap<String, InspectionProjectLease>()
    private val openingProjectRequests = java.util.concurrent.ConcurrentHashMap<String, LifecycleOpenRequest>()
    private val lifecycleOpenOwnershipByProjectInstance = java.util.concurrent.ConcurrentHashMap<String, LifecycleOpenOwnership>()
    private val unresolvedLifecycleOpenProjects = java.util.concurrent.ConcurrentHashMap<String, Project>()
    internal var forceCloseProject: (Project, Boolean) -> Boolean = { project, save ->
        ProjectManagerEx.getInstanceEx().forceCloseProject(project, save)
    }
    internal var closeVerificationTimeoutMs: Long = 10_000
    internal var closeVerificationPollMs: Long = 100
    internal var closeVerificationNow: () -> Long = { System.currentTimeMillis() }
    internal var closeVerificationSleep: (Long) -> Unit = { millis -> Thread.sleep(millis) }
    internal var lifecycleOpenGuardPollMs: Long = 200
    internal var lifecycleOpenGuardTimeoutMs: Long = 10_000
    internal var lifecycleOpenGuardNow: () -> Long = { System.currentTimeMillis() }
    internal var lifecycleOpenGuardSleep: (Long) -> Unit = { millis -> Thread.sleep(millis) }
    internal var inspectionRunExpirationMs: Long = 300000L
    internal var inspectionProcessRunner: (Runnable, ProgressIndicator) -> Unit = { task, indicator ->
        ProgressManager.getInstance().runProcess(task, indicator)
    }
    internal var inspectionIndicatorFactory: (Project) -> ProgressIndicator = { project ->
        ProgressWindow(false, true, project).apply {
            setDelayInMillis(Int.MAX_VALUE)
        }
    }
    internal var lifecycleCloseExecutor: (Runnable) -> Unit = { task ->
        ApplicationManager.getApplication().executeOnPooledThread(task)
    }
    internal var trustProjectPath: (Path) -> Unit = { path ->
        TrustedProjects.setProjectTrusted(canonicalTrustPath(path), true)
    }
    internal var openProjectPath: (Path, (Project) -> Unit) -> Project? = { path, beforeInit ->
        val openPath = canonicalTrustPath(path)
        ProjectManagerEx.getInstanceEx().openProject(
            openPath,
            lifecycleOpenProjectTask(openPath, beforeInit),
        )
    }

    private val resultsStore = InspectionResultsStore

    private data class SnapshotStaleness(
        val stale: Boolean,
        val reasons: List<String>,
        val changeKind: String = "fresh",
    )

    private fun captureIncompleteReason(
        snapshot: InspectionResultsSnapshot?,
        staleness: SnapshotStaleness = SnapshotStaleness(false, emptyList()),
    ): CaptureIncompleteReason? {
        if (snapshot?.outcome != InspectionSnapshotOutcome.CAPTURE_INCOMPLETE) {
            return null
        }
        snapshot.captureIncompleteReason?.let { return it }
        if (staleness.changeKind == CaptureIncompleteReason.CURRENT_RUN_PSI_CHURN.apiValue) {
            return CaptureIncompleteReason.CURRENT_RUN_PSI_CHURN
        }
        return classifyCaptureIncompleteReason(
            captureDiagnostic = snapshot.captureDiagnostic,
            snapshotChangeKind = staleness.changeKind,
        )
    }

    private fun addCaptureIncompleteReason(
        target: MutableMap<String, Any?>,
        snapshot: InspectionResultsSnapshot?,
        staleness: SnapshotStaleness = SnapshotStaleness(false, emptyList()),
    ) {
        captureIncompleteReason(snapshot, staleness)?.let { reason ->
            target["capture_incomplete_reason"] = reason.apiValue
        }
    }

    private fun addInspectionVerdict(target: MutableMap<String, Any?>) {
        addInspectionProof(target)
        target.putAll(inspectionVerdictFields(target))
    }

    private fun addStatusInspectionVerdict(target: MutableMap<String, Any>) {
        @Suppress("UNCHECKED_CAST")
        addInspectionProof(target as MutableMap<String, Any?>)
        target.putAll(inspectionVerdictFields(target))
    }

    private fun addInspectionProof(target: MutableMap<String, Any?>) {
        val proof = inspectionProof(target)
        target["inspection_proof"] = proof.summary.filterValues { it != null }
        if (proof.failures.isNotEmpty()) {
            target["proof_failures"] = proof.failures
        } else {
            target.remove("proof_failures")
        }
    }

    private fun inspectionVerdictFields(payload: Map<String, Any?>): Map<String, String> {
        val verdict = inspectionVerdict(payload)
        return mapOf(
            "inspection_verdict" to verdict.verdict,
            "inspection_verdict_reason" to verdict.reason,
            "inspection_verdict_message" to verdict.message,
            "inspection_verdict_next_action" to verdict.nextAction,
        )
    }

    private fun inspectionVerdict(payload: Map<String, Any?>): InspectionVerdict {
        val status = payload["status"] as? String
        val completionReason = payload["completion_reason"] as? String
        val unknownReason = unknownVerdictReason(payload)
        if (unknownReason != null) {
            return InspectionVerdict(
                verdict = "UNKNOWN",
                reason = unknownReason,
                message = "Inspection did not produce a trustworthy GREEN or RED result.",
                nextAction = unknownVerdictNextAction(unknownReason, payload),
            )
        }
        if ((payload["proof_failures"] as? List<*>)?.isNotEmpty() == true) {
            return InspectionVerdict(
                verdict = "UNKNOWN",
                reason = "inspection_proof_failed",
                message = "Inspection returned contradictory proof and did not establish a trustworthy GREEN or RED result.",
                nextAction = unknownVerdictNextAction("inspection_proof_failed", payload),
            )
        }
        val totalProblems = (payload["total_problems"] as? Number)?.toInt()
        val problems = payload["problems"] as? List<*>
        val hasExplicitZeroResult = totalProblems == 0
        val hasCurrentFindings = ((totalProblems ?: problems?.size ?: 0) > 0) &&
            payload["results_may_be_stale"] != true &&
            payload["capture_incomplete"] != true
        if (hasCurrentFindings) {
            return InspectionVerdict(
                verdict = "RED",
                reason = "actionable_findings",
                message = "Inspection worked and returned actionable findings for the selected scope/filter.",
                nextAction = "Fix the reported findings, then rerun inspection.",
            )
        }
        if (
            (status == "results_available" && (hasExplicitZeroResult || payload["clean_inspection"] == true)) ||
            status == "clean" ||
            completionReason == "clean" ||
            payload["clean_inspection"] == true
        ) {
            return InspectionVerdict(
                verdict = "GREEN",
                reason = if (status == "results_available") "no_matching_findings" else "clean_confirmed",
                message = "Inspection worked and found no actionable findings for the selected scope/filter.",
                nextAction = "No inspection action required for this scope/filter.",
            )
        }
        return InspectionVerdict(
            verdict = "UNKNOWN",
            reason = completionReason ?: status ?: "unknown",
            message = "Inspection did not produce a trustworthy GREEN or RED result.",
            nextAction = unknownVerdictNextAction(completionReason ?: status ?: "unknown", payload),
        )
    }

    private fun unknownVerdictReason(payload: Map<String, Any?>): String? {
        val status = payload["status"] as? String
        val completionReason = payload["completion_reason"] as? String
        return when {
            payload["session_drift"] == true -> "session_drift"
            payload["ambiguous"] == true -> "ambiguous_route"
            payload["unavailable"] == true -> "inspection_api_unavailable"
            payload["results_may_be_stale"] == true || status == "stale_results" || completionReason == "stale_results" -> "stale_results"
            payload["capture_incomplete"] == true || status == "capture_incomplete" || completionReason == "capture_incomplete" -> {
                (payload["capture_incomplete_reason"] as? String) ?: "capture_incomplete"
            }
            payload["timed_out"] == true || completionReason == "timeout" -> "timeout"
            payload["indexing"] == true || payload["is_scanning"] == true || payload["inspection_in_progress"] == true -> "inspection_still_running"
            status == "scope_mismatch" -> "scope_mismatch"
            status == "no_results" || completionReason == "no_results" -> "no_results"
            else -> null
        }
    }

    private fun unknownVerdictNextAction(reason: String, payload: Map<String, Any?>): String {
        if (reason in setOf("non_empty_unmapped_tree", "extractor_failure", "helper_plugin_error", "inspection_trigger_empty_model")) {
            return "Treat this as a plugin/helper bug: include capture_diagnostic, update the plugin or helper, and rerun."
        }
        val diagnostic = payload["capture_diagnostic"] as? Map<*, *>
        if (diagnostic?.get("observed_non_empty_inspection_tree") == true) {
            return "Treat this as a plugin/helper capture bug and include capture_diagnostic when reporting it."
        }
        return when (reason) {
            "view_not_ready", "view_updating_unreadable", "unreadable_tree", "no_results" ->
                "Open the IDE Inspection Results or Problems view for the exact worktree, then rerun inspection."
            "current_run_psi_churn" ->
                "Save documents and rerun inspection after the IDE finishes updating PSI state."
            "stale_results" ->
                "Rerun inspection; stale cached findings must not be treated as current."
            "timeout" ->
                "Wait for indexing/scanning to settle or rerun with a larger timeout."
            "inspection_still_running" ->
                "Wait for indexing/scanning to finish, then rerun inspection."
            "inspection_api_unavailable" ->
                "Open the exact worktree in the configured JetBrains IDE with the inspection plugin installed."
            "profile_resolution_error" ->
                "Check that the requested inspection profile exists and is loaded in the target IDE project, then rerun inspection."
            "inspection_proof_failed" ->
                "Resolve the route/profile/scope proof failure, then trigger and wait for a fresh inspection before reporting GREEN or RED."
            "scope_mismatch" ->
                "Trigger and wait for a fresh inspection whose scope covers the requested problems scope."
            "scope_not_covered" ->
                "Trigger and wait for a fresh inspection with a valid explicit scope before reporting GREEN or RED."
            "ambiguous_route" ->
                "Pass project_key, project_path, or worktree_path so the plugin can inspect the exact project."
            "session_drift" ->
                "Resolve the route again and rerun; the IDE/plugin session changed."
            "no_project" ->
                "Open the exact worktree in the configured JetBrains IDE, or pass the exact project_key, project_path, or worktree_path."
            else ->
                "Do not report GREEN or RED. Rerun inspection and include diagnostics if it remains UNKNOWN."
        }
    }

    private fun inspectionProof(payload: Map<String, Any?>): InspectionProof {
        val status = payload["status"] as? String
        val completionReason = payload["completion_reason"] as? String
        val route = payload["route"] as? Map<*, *>
        val diagnostic = payload["capture_diagnostic"] as? Map<*, *>
        val filters = payload["filters"] as? Map<*, *>
        val captureScope = payload["capture_scope"] as? Map<*, *>
        val snapshotRunId = (payload["snapshot_run_id"] as? Number)?.toLong()
        val inspectionRunId = (payload["inspection_run_id"] as? Number)?.toLong()
        val currentRunId = inspectionRunId ?: (payload["run_id"] as? Number)?.toLong()
        val captureIncompleteReason = payload["capture_incomplete_reason"] as? String
        val proofStatus = when {
            payload["session_drift"] == true -> "failed"
            payload["ambiguous"] == true -> "failed"
            payload["unavailable"] == true -> "failed"
            status == "no_project" || completionReason == "no_project" -> "failed"
            payload["results_may_be_stale"] == true -> "failed"
            payload["capture_incomplete"] == true -> "failed"
            payload["timed_out"] == true -> "failed"
            payload["indexing"] == true || payload["is_scanning"] == true || payload["inspection_in_progress"] == true -> "pending"
            payload["clean_inspection"] == true -> "complete"
            status == "results_available" || status == "clean" || completionReason == "clean" || completionReason == "results" -> "complete"
            else -> "unknown"
        }
        val scope = (filters?.get("scope") as? String)
            ?: (captureScope?.get("scope") as? String)
            ?: (diagnostic?.get("scope") as? String)
        val profileRequested = diagnostic?.get("profile_requested") as? String
        val profileResolved = diagnostic?.get("profile_resolved_name") as? String
            ?: payload["profile"] as? String
        val proof = mapOf(
            "status" to proofStatus,
            "project_key" to payload["project_key"],
            "route_base_path" to route?.get("base_path"),
            "project_instance_id" to route?.get("project_instance_id"),
            "session_id" to payload["session_id"],
            "scope" to scope,
            "profile" to profileResolved,
            "expected_profile" to profileRequested,
            "snapshot_outcome" to payload["snapshot_outcome"],
            "capture_complete" to (payload["capture_incomplete"] != true && status != "capture_incomplete"),
            "inspection_completed" to (payload["inspection_in_progress"] != true && payload["is_scanning"] != true),
            "indexing_complete" to (payload["indexing"] != true),
            "session_fresh" to (payload["session_drift"] != true),
            "results_fresh" to (payload["results_may_be_stale"] != true),
            "snapshot_run_id" to snapshotRunId,
            "inspection_run_id" to currentRunId,
            "capture_incomplete_reason" to captureIncompleteReason,
        )

        val failures = mutableListOf<String>()
        if (payload["session_drift"] == true) failures.add("session_drift")
        if (payload["ambiguous"] == true) failures.add("ambiguous_route")
        if (payload["unavailable"] == true) failures.add("inspection_api_unavailable")
        if (payload["results_may_be_stale"] == true || status == "stale_results" || completionReason == "stale_results") failures.add("stale_results")
        if (payload["capture_incomplete"] == true || status == "capture_incomplete" || completionReason == "capture_incomplete") failures.add(captureIncompleteReason ?: "capture_incomplete")
        if (payload["timed_out"] == true || completionReason == "timeout") failures.add("timeout")
        if (payload["indexing"] == true) failures.add("indexing")
        if (payload["is_scanning"] == true || payload["inspection_in_progress"] == true) failures.add("inspection_still_running")
        if (status == "no_results" || completionReason == "no_results") failures.add("no_results")
        if (status == "no_recent_inspection" || completionReason == "no_recent_inspection") failures.add("no_recent_inspection")
        if (diagnostic?.get("profile_missing") == true || diagnostic?.get("profile_unverified") == true) failures.add("profile_mismatch")
        if (snapshotRunId != null && currentRunId != null && snapshotRunId != currentRunId) failures.add("run_mismatch")

        return InspectionProof(proof, failures.distinct())
    }

    override fun isSupported(request: FullHttpRequest): Boolean {
        return request.uri().startsWith("/api/inspection") && request.method() == HttpMethod.GET
    }

    @Suppress("SameReturnValue")
    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        try {
            val path = urlDecoder.path()
            val parameters = urlDecoder.parameters() ?: emptyMap()
            when (path) {
                "/api/inspection/identity" -> {
                    val result = ApplicationManager.getApplication().runReadAction<String, Exception> {
                        formatJsonManually(buildInspectionIdentity())
                    }
                    sendJsonResponse(context, result)
                }
                "/api/inspection/route" -> {
                    responseHasSessionDrift(parameters)?.let {
                        sendJsonResponse(context, it, HttpResponseStatus.CONFLICT)
                        return true
                    }
                    val result = ApplicationManager.getApplication().runReadAction<String, Exception> {
                        buildRouteResponse(parameters)
                    }
                    sendJsonResponse(context, result)
                }
                "/api/inspection/lifecycle/claim" -> {
                    responseHasSessionDrift(parameters)?.let {
                        sendJsonResponse(context, it, HttpResponseStatus.CONFLICT)
                        return true
                    }
                    val result = ApplicationManager.getApplication().runReadAction<String, Exception> {
                        buildLifecycleClaimResponse(parameters)
                    }
                    sendJsonResponse(context, result)
                }
                "/api/inspection/lifecycle/open" -> {
                    responseHasSessionDrift(parameters)?.let {
                        sendJsonResponse(context, it, HttpResponseStatus.CONFLICT)
                        return true
                    }
                    val result = openLifecycleProject(parameters)
                    sendJsonResponse(context, formatJsonManually(result.first), result.second)
                }
                "/api/inspection/lifecycle/close" -> {
                    val scheduled = runCatching {
                        lifecycleCloseExecutor(Runnable {
                            processLifecycleCloseRequest(parameters, context)
                        })
                    }.isSuccess
                    if (!scheduled) {
                        sendJsonResponse(
                            context,
                            """{"error": "Internal server error"}""",
                            HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        )
                    }
                }
                "/api/inspection/cancel" -> {
                    responseHasSessionDrift(parameters)?.let {
                        sendJsonResponse(context, it, HttpResponseStatus.CONFLICT)
                        return true
                    }
                    val result = ApplicationManager.getApplication().runReadAction<String, Exception> {
                        buildInspectionCancellationResponse(parameters)
                    }
                    sendJsonResponse(context, result)
                }
                "/api/inspection/problems" -> {
                    responseHasSessionDrift(parameters)?.let {
                        sendJsonResponse(context, it, HttpResponseStatus.CONFLICT)
                        return true
                    }
                    val severity = parseSeverity(parameters)
                    val scope = parameters["scope"]?.firstOrNull()?.trim().orEmpty().ifBlank { "whole_project" }
                    val problemTypeRaw = parameters["problem_type"]?.firstOrNull()
                    val filePatternRaw = parameters["file_pattern"]?.firstOrNull()
                    val problemType = normalizeOptionalFilter(problemTypeRaw)
                    val filePattern = normalizeOptionalFilter(filePatternRaw)
                    validateFilePattern(filePattern)
                    val limit = parseIntParameter(parameters, "limit", defaultValue = 100, min = 1, max = 1000)
                    val offset = parseIntParameter(parameters, "offset", defaultValue = 0, min = 0)
                    val includeStale = parseBooleanParameter(parameters, "include_stale", defaultValue = false)
                    val directory = parameters["dir"]?.firstOrNull()
                        ?: parameters["directory"]?.firstOrNull()
                        ?: parameters["path"]?.firstOrNull()
                    val filesList = requestedFiles(parameters)
                    val includeUnversioned = parseBooleanParameter(parameters, "include_unversioned", defaultValue = true)
                    val changedFilesMode = parseChangedFilesMode(parameters)
                    val maxFiles = parseOptionalIntParameter(parameters, "max_files", min = 1)
                    val projectName = extractProjectSelector(urlDecoder, request)
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            withCurrentProject(context, projectName, refreshProjectState = false) { project ->
                                val validatedRequestScope = validateInspectionScopeRequest(
                                    project = project,
                                    scopeParam = scope,
                                    directoryParam = directory,
                                    files = filesList,
                                    includeUnversioned = includeUnversioned,
                                    changedFilesMode = changedFilesMode,
                                    maxFiles = maxFiles,
                                    allowLegacyProblemsScope = true,
                                )
                                val result = ApplicationManager.getApplication().runReadAction<String, Exception> {
                                    getInspectionProblemsInternal(
                                        project,
                                        severity,
                                        scope,
                                        problemType,
                                        filePattern,
                                        limit,
                                        offset,
                                        includeStale,
                                        directory,
                                        filesList.takeIf { it.isNotEmpty() },
                                        includeUnversioned,
                                        changedFilesMode,
                                        maxFiles,
                                        validatedRequestScope,
                                    )
                                }
                                sendJsonResponse(context, result)
                            }
                        } catch (error: BadRequestException) {
                            sendJsonResponse(context, formatBadRequest(error), HttpResponseStatus.BAD_REQUEST)
                        } catch (error: Exception) {
                            logger.warn("Failed to process inspection problems request", error)
                            sendJsonResponse(context, """{"error": "Internal server error"}""", HttpResponseStatus.INTERNAL_SERVER_ERROR)
                        }
                    }
                }
                "/api/inspection/trigger" -> {
                    responseHasSessionDrift(parameters)?.let {
                        sendJsonResponse(context, it, HttpResponseStatus.CONFLICT)
                        return true
                    }
                    val scope = parameters["scope"]?.firstOrNull()?.trim()
                    // Accept either `dir`, `directory`, or `path` for directory scoping
                    val directory = parameters["dir"]?.firstOrNull()
                        ?: parameters["directory"]?.firstOrNull()
                        ?: parameters["path"]?.firstOrNull()
                    val filesList = mutableListOf<String>()
                    filesList.addAll(requestedFiles(parameters))
                    val includeUnversioned = parseBooleanParameter(parameters, "include_unversioned", defaultValue = true)
                    val changedFilesMode = parseChangedFilesMode(parameters)
                    val maxFiles = parseOptionalIntParameter(parameters, "max_files", min = 1)
                    val profile = parameters["profile"]?.firstOrNull()
                    val projectName = extractProjectSelector(urlDecoder, request)
                    val triggerRequest = Runnable {
                        try {
                            startInspectionTrigger(
                                context = context,
                                projectName = projectName,
                                scope = scope,
                                directory = directory,
                                files = filesList,
                                includeUnversioned = includeUnversioned,
                                changedFilesMode = changedFilesMode,
                                maxFiles = maxFiles,
                                profile = profile,
                            )
                        } catch (error: InspectionRunConflictException) {
                            sendInspectionRunConflict(context, error)
                        } catch (error: BadRequestException) {
                            sendJsonResponse(context, formatBadRequest(error), HttpResponseStatus.BAD_REQUEST)
                        } catch (error: Exception) {
                            logger.warn("Failed to process inspection trigger request", error)
                            sendJsonResponse(
                                context,
                                """{"error": "Internal server error"}""",
                                HttpResponseStatus.INTERNAL_SERVER_ERROR,
                            )
                        }
                    }
                    val requestedScope = scope?.lowercase()?.trim().orEmpty().ifBlank {
                        when {
                            !directory.isNullOrBlank() -> "directory"
                            filesList.isNotEmpty() -> "files"
                            else -> "whole_project"
                        }
                    }
                    if (requestedScope == "changed_files") {
                        ApplicationManager.getApplication().executeOnPooledThread(triggerRequest)
                    } else {
                        triggerRequest.run()
                    }
                }
                "/api/inspection/status" -> {
                    responseHasSessionDrift(parameters)?.let {
                        sendJsonResponse(context, it, HttpResponseStatus.CONFLICT)
                        return true
                    }
                    val projectName = extractProjectSelector(urlDecoder, request)
                    withCurrentProject(context, projectName) { project ->
                        val result = ApplicationManager.getApplication().runReadAction<String, Exception> {
                            getInspectionStatus(project)
                        }
                        sendJsonResponse(context, result)
                    }
                }
                "/api/inspection/wait" -> {
                    responseHasSessionDrift(parameters)?.let {
                        sendJsonResponse(context, it, HttpResponseStatus.CONFLICT)
                        return true
                    }
                    val projectName = extractProjectSelector(urlDecoder, request)
                    val timeoutMs = parameters["timeout_ms"]?.firstOrNull()?.toLongOrNull()
                    val pollMs = parameters["poll_ms"]?.firstOrNull()?.toLongOrNull()
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            val result = waitForInspection(projectName, timeoutMs, pollMs)
                            sendJsonResponse(context, result)
                        } catch (error: BadRequestException) {
                            sendJsonResponse(context, formatBadRequest(error), HttpResponseStatus.BAD_REQUEST)
                        } catch (_: Exception) {
                            sendJsonResponse(context, """{"error": "Internal server error"}""", HttpResponseStatus.INTERNAL_SERVER_ERROR)
                        }
                    }
                }
                else -> {
                    sendJsonResponse(context, """{"error": "Unknown endpoint"}""", HttpResponseStatus.NOT_FOUND)
                }
            }
            return true
        } catch (error: InspectionRunConflictException) {
            sendInspectionRunConflict(context, error)
            return true
        } catch (error: BadRequestException) {
            sendJsonResponse(context, formatBadRequest(error), HttpResponseStatus.BAD_REQUEST)
            return true
        } catch (error: Exception) {
            logger.warn("Failed to process inspection API request", error)
            sendJsonResponse(context, """{"error": "Internal server error"}""", HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return true
        }
    }

    private fun startInspectionTrigger(
        context: ChannelHandlerContext,
        projectName: String?,
        scope: String?,
        directory: String?,
        files: List<String>,
        includeUnversioned: Boolean,
        changedFilesMode: String?,
        maxFiles: Int?,
        profile: String?,
    ) {
        withCurrentProject(context, projectName) { project ->
            val captureScope = validateInspectionScopeRequest(
                project = project,
                scopeParam = scope,
                directoryParam = directory,
                files = files,
                includeUnversioned = includeUnversioned,
                changedFilesMode = changedFilesMode,
                maxFiles = maxFiles,
                allowLegacyProblemsScope = false,
            )
            val key = projectKey(project)
            val runState = beginInspectionRun(project, captureScope)
            val runControl = InspectionRunControl(
                runId = runState.runId,
                indicator = inspectionIndicatorFactory(project),
            )
            inspectionRunControlsByProject[key] = runControl
            try {
                ApplicationManager.getApplication().executeOnPooledThread {
                    triggerInspectionAsync(
                        project = project,
                        runId = runState.runId,
                        captureScope = captureScope,
                        profileName = profile,
                        control = runControl,
                    )
                }
            } catch (error: Exception) {
                inspectionRunControlsByProject.remove(key, runControl)
                finishInspectionRun(key, runState.runId)
                throw error
            }
            val details = mutableMapOf<String, Any>(
                "status" to "triggered",
                "message" to "Inspection triggered. Wait 10-15 seconds then check status",
                "run_id" to runState.runId,
                "session_id" to InspectionIdeSession.sessionId,
                "project_key" to key,
                "route" to routeMetadata(project),
                "scope" to captureScope.scopeParam.orEmpty().ifBlank { "whole_project" },
                "include_unversioned" to includeUnversioned,
            )
            if (!directory.isNullOrBlank()) details["directory"] = directory
            if (files.isNotEmpty()) details["files_requested"] = files.size
            if (!changedFilesMode.isNullOrBlank()) details["changed_files_mode"] = changedFilesMode
            if (maxFiles != null) details["max_files"] = maxFiles
            if (!profile.isNullOrBlank()) details["profile"] = profile
            sendJsonResponse(context, formatJsonManually(details))
        }
    }

    private fun sendInspectionRunConflict(
        context: ChannelHandlerContext,
        error: InspectionRunConflictException,
    ) {
        sendJsonResponse(
            context,
            formatJsonManually(
                mapOf(
                    "error" to "inspection_in_progress",
                    "status" to "inspection_in_progress",
                    "inspection_in_progress" to true,
                    "inspection_run_id" to error.runId,
                    "message" to error.message,
                ),
            ),
            HttpResponseStatus.CONFLICT,
        )
    }

    private fun parseIntParameter(
        parameters: Map<String, List<String>>,
        name: String,
        defaultValue: Int,
        min: Int? = null,
        max: Int? = null,
    ): Int {
        return parseOptionalIntParameter(parameters, name, min, max) ?: defaultValue
    }

    private fun parseOptionalIntParameter(
        parameters: Map<String, List<String>>,
        name: String,
        min: Int? = null,
        max: Int? = null,
    ): Int? {
        val raw = parameters[name]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val value = raw.toIntOrNull()
            ?: throw BadRequestException(name, "Parameter '$name' must be an integer.")
        if (min != null && value < min) {
            throw BadRequestException(name, "Parameter '$name' must be at least $min.")
        }
        if (max != null && value > max) {
            throw BadRequestException(name, "Parameter '$name' must be at most $max.")
        }
        return value
    }

    private fun parseBooleanParameter(
        parameters: Map<String, List<String>>,
        name: String,
        defaultValue: Boolean,
    ): Boolean {
        val raw = parameters[name]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return defaultValue
        return when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw BadRequestException(name, "Parameter '$name' must be 'true' or 'false'.")
        }
    }

    private fun parseSeverity(parameters: Map<String, List<String>>): String {
        val severity = parameters["severity"]?.firstOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "all" }
        if (severity !in setOf("all", "error", "warning", "weak_warning", "info", "grammar", "typo")) {
            throw BadRequestException(
                "severity",
                "Parameter 'severity' must be one of: all, error, warning, weak_warning, info, grammar, typo.",
            )
        }
        return severity
    }

    private fun parseChangedFilesMode(parameters: Map<String, List<String>>): String? {
        val mode = parameters["changed_files_mode"]?.firstOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (mode != null && mode !in setOf("all", "staged", "unstaged")) {
            throw BadRequestException(
                "changed_files_mode",
                "Parameter 'changed_files_mode' must be one of: all, staged, unstaged.",
            )
        }
        return mode
    }

    private fun validateFilePattern(filePattern: String?) {
        if (filePattern != null && usesPatternFileSyntax(filePattern) && compileFilePatternRegex(filePattern) == null) {
            throw BadRequestException(
                "file_pattern",
                "Parameter 'file_pattern' must be a valid regular expression, glob, or literal path fragment.",
            )
        }
    }

    private fun requestedFiles(parameters: Map<String, List<String>>): List<String> {
        val repeatedFiles = parameters["file"].orEmpty()
        val filesParam = parameters["files"].orEmpty().flatMap { raw ->
            raw.split('\n', ',', ';')
        }
        return (repeatedFiles + filesParam)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun formatBadRequest(error: BadRequestException): String {
        return formatJsonManually(
            mapOf(
                "error" to "Invalid request parameter",
                "parameter" to error.parameter,
                "message" to error.message,
            )
        )
    }

    private fun responseHasSessionDrift(parameters: Map<String, List<String>>): String? {
        val expectedSessionId = firstParameter(parameters, "session_id") ?: return null
        if (expectedSessionId == InspectionIdeSession.sessionId) {
            return null
        }
        val response = mutableMapOf<String, Any>(
                "error" to "IDE session changed",
                "session_drift" to true,
                "expected_session_id" to expectedSessionId,
                "session_id" to InspectionIdeSession.sessionId,
                "started_at_ms" to InspectionIdeSession.startedAtMs,
                "message" to "The JetBrains IDE session changed. Resolve the route again and re-trigger inspection before trusting cached results.",
            )
        addStatusInspectionVerdict(response)
        return formatJsonManually(response)
    }

    private fun buildRouteResponse(parameters: Map<String, List<String>>): String {
        val resolved = resolveInspectionRoute(parameters)
            ?: return formatJsonManually(buildMissingProjectResponse(routeProjectSelector(parameters)))
        return formatJsonManually(
            mapOf(
                "status" to "resolved",
                "route" to routeMetadata(resolved),
                "selectors" to routeSelectorMetadata(parameters),
                "registry" to registryMetadata(),
            )
        )
    }

    private fun buildLifecycleClaimResponse(parameters: Map<String, List<String>>): String {
        val resolved = resolveInspectionRoute(parameters)
            ?: return formatJsonManually(buildMissingProjectResponse(routeProjectSelector(parameters)))
        val expectedProjectInstanceId = firstParameter(parameters, "project_instance_id")
        val actualProjectInstanceId = resolved.projectIdentity["project_instance_id"] as? String
            ?: projectInstanceId(resolved.project)
        if (expectedProjectInstanceId != null && expectedProjectInstanceId != actualProjectInstanceId) {
            throw BadRequestException(
                "project_instance_id",
                "Project instance changed. Resolve the route again before claiming lifecycle ownership.",
            )
        }

        val requestedLeaseId = firstParameter(parameters, "lease_id")
        val ownership = lifecycleOpenOwnershipByProjectInstance[actualProjectInstanceId]
        val ownershipProven = requestedLeaseId != null &&
            requestedLeaseId == ownership?.leaseId &&
            lifecycleOpenKeys(resolved.project).contains(ownership.targetKey)
        if (!ownershipProven) {
            return formatJsonManually(
                mapOf(
                    "status" to "not_owned",
                    "ownership_proven" to false,
                    "reason" to when {
                        ownership == null -> "project_preexisted"
                        requestedLeaseId != ownership.leaseId -> "lease_mismatch"
                        else -> "route_mismatch"
                    },
                    "lease_id" to requestedLeaseId,
                    "project_instance_id" to actualProjectInstanceId,
                    "project_key" to (resolved.projectIdentity["project_key"] as? String ?: projectKey(resolved.project)),
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(resolved),
                    "lifecycle_ownership_protocol" to LIFECYCLE_OWNERSHIP_PROTOCOL,
                ).filterValues { value -> value != null }
            )
        }

        val closeToken = UUID.randomUUID().toString()
        val lease = InspectionProjectLease(
            closeToken = closeToken,
            leaseId = requestedLeaseId,
            projectKey = resolved.projectIdentity["project_key"] as? String ?: projectKey(resolved.project),
            projectInstanceId = actualProjectInstanceId,
            basePath = resolved.projectIdentity["base_path"] as? String,
            sessionId = InspectionIdeSession.sessionId,
            claimedAtMs = System.currentTimeMillis(),
        )
        leasesByProjectInstance[actualProjectInstanceId] = lease

        return formatJsonManually(
            mapOf(
                "status" to "claimed",
                "ownership_proven" to true,
                "close_token" to closeToken,
                "lease_id" to lease.leaseId,
                "project_instance_id" to actualProjectInstanceId,
                "project_key" to lease.projectKey,
                "session_id" to InspectionIdeSession.sessionId,
                "route" to routeMetadata(resolved),
                "claimed_at_ms" to lease.claimedAtMs,
                "lifecycle_ownership_protocol" to LIFECYCLE_OWNERSHIP_PROTOCOL,
            )
        )
    }

    private fun openLifecycleProject(parameters: Map<String, List<String>>): Pair<Map<String, Any?>, HttpResponseStatus> {
        val rawPath = firstParameter(parameters, "worktree_path")
            ?: firstParameter(parameters, "project_path")
            ?: throw BadRequestException("worktree_path", "Parameter 'worktree_path' is required.")
        val normalizedPath = normalizeFileSystemPath(rawPath)
            ?: throw BadRequestException("worktree_path", "Parameter 'worktree_path' must be a valid local path.")
        val path = Paths.get(normalizedPath)
        findOpenProjectForLifecycleOpen(path.toString())?.let { project ->
            return lifecycleOpenAlreadyOpen(project)
        }
        val target = resolveLifecycleOpenTarget(path)
        if (target.projectRoot != target.path) {
            findOpenProjectForLifecycleOpen(target.projectRoot.toString())?.let { project ->
                return lifecycleOpenAlreadyOpen(project)
            }
        }
        unresolvedLifecycleOpenProjects[target.key]?.let { project ->
            if (isUnresolvedLifecycleOpenActive(project)) {
                return lifecycleOpenStateUnknown(target)
            }
            unresolvedLifecycleOpenProjects.remove(target.key, project)
        }
        findOpenProjectForLifecycleOpenKey(target.key)?.let { project ->
            unresolvedLifecycleOpenProjects.remove(target.key)
            return if (isUsableProject(project)) {
                lifecycleOpenAlreadyOpen(project)
            } else {
                lifecycleOpenAlreadyOpening(target)
            }
        }
        firstParameter(parameters, "session_id") ?: return mapOf(
            "status" to "failed",
            "opened" to false,
            "reason" to "missing_session_id",
            "message" to "Parameter 'session_id' is required before scheduling a lifecycle project open.",
            "worktree_path" to target.path.toString(),
            "session_id" to InspectionIdeSession.sessionId,
        ) to HttpResponseStatus.BAD_REQUEST
        val requestedLeaseId = firstParameter(parameters, "lease_id")
        val request = LifecycleOpenRequest(leaseId = requestedLeaseId)
        val existingRequest = openingProjectRequests.putIfAbsent(target.key, request)
        if (existingRequest != null) {
            val sameLease = requestedLeaseId != null && requestedLeaseId == existingRequest.leaseId
            return mapOf(
                "status" to "opening",
                "opened" to false,
                "opening_scheduled" to false,
                "reason" to "already_opening",
                "worktree_path" to target.path.toString(),
                "session_id" to InspectionIdeSession.sessionId,
                "ownership_registered" to sameLease,
                "lease_id" to requestedLeaseId,
                "lifecycle_ownership_protocol" to LIFECYCLE_OWNERSHIP_PROTOCOL,
            ) to HttpResponseStatus.OK
        }
        unresolvedLifecycleOpenProjects.remove(target.key)

        val scheduled = runCatching {
            ApplicationManager.getApplication().invokeLater {
                var opened: Project? = null
                var keepOpeningGuard = false
                try {
                    val preexistingProject = findOpenProjectForLifecycleOpen(target.projectRoot.toString())
                    if (preexistingProject != null) {
                        opened = preexistingProject
                        return@invokeLater
                    }
                    val initializedProject = AtomicReference<Project?>()
                    trustProjectPath(target.openPath)
                    opened = openProjectPath(target.openPath) { project ->
                        initializedProject.compareAndSet(null, project)
                    }
                    if (
                        requestedLeaseId != null &&
                        opened != null &&
                        initializedProject.get() === opened
                    ) {
                        lifecycleOpenOwnershipByProjectInstance[projectInstanceId(opened)] = LifecycleOpenOwnership(
                            leaseId = requestedLeaseId,
                            targetKey = target.key,
                        )
                    }
                    if (opened != null) {
                        keepOpeningGuard = true
                        releaseLifecycleOpenGuardWhenUsable(target.key, opened, request)
                    }
                } finally {
                    if (!keepOpeningGuard) {
                        openingProjectRequests.remove(target.key, request)
                    }
                }
            }
        }.isSuccess
        if (!scheduled) {
            openingProjectRequests.remove(target.key, request)
            return mapOf(
                "status" to "failed",
                "opened" to false,
                "reason" to "open_schedule_failed",
                "message" to "JetBrains IDE declined or failed to schedule opening the requested project path.",
                "worktree_path" to target.path.toString(),
                "project_root" to target.projectRoot.toString(),
                "session_id" to InspectionIdeSession.sessionId,
                "lifecycle_ownership_protocol" to LIFECYCLE_OWNERSHIP_PROTOCOL,
            ) to HttpResponseStatus.CONFLICT
        }

        return mapOf(
            "status" to "opening",
            "opened" to false,
            "opening_scheduled" to true,
            "worktree_path" to target.path.toString(),
            "project_root" to target.projectRoot.toString(),
            "session_id" to InspectionIdeSession.sessionId,
            "ownership_registered" to (requestedLeaseId != null),
            "lease_id" to requestedLeaseId,
            "lifecycle_ownership_protocol" to LIFECYCLE_OWNERSHIP_PROTOCOL,
        ) to HttpResponseStatus.OK
    }

    private fun releaseLifecycleOpenGuardWhenUsable(key: String, project: Project, request: LifecycleOpenRequest) {
        if (project.isDisposed) {
            openingProjectRequests.remove(key, request)
            unresolvedLifecycleOpenProjects.remove(key)
            return
        }
        if (isLifecycleOpenProjectUsable(key, project)) {
            openingProjectRequests.remove(key, request)
            unresolvedLifecycleOpenProjects.remove(key)
            return
        }
        val submitted = runCatching {
            ApplicationManager.getApplication().executeOnPooledThread {
                var unresolvedOpenState = false
                try {
                    val startedAtMs = lifecycleOpenGuardNow()
                    val deadlineMs = startedAtMs + lifecycleOpenGuardTimeoutMs
                    var observedOpenProject = false
                    while (openingProjectRequests.containsKey(key)) {
                        val nowMs = lifecycleOpenGuardNow()
                        val lifecycleComplete = runCatching {
                            ApplicationManager.getApplication().runReadAction<Boolean, Exception> {
                                val isOpenProject = ProjectManager.getInstance().openProjects.any { openProject -> openProject === project }
                                val isIdentifiableProject = isOpenProject && lifecycleOpenKeys(project).contains(key)
                                val isUsableLifecycleProject = isIdentifiableProject && isUsableProject(project)
                                observedOpenProject = observedOpenProject || isOpenProject
                                unresolvedOpenState = !isUsableLifecycleProject && nowMs >= deadlineMs
                                project.isDisposed ||
                                    isUsableLifecycleProject ||
                                    (observedOpenProject && !isOpenProject) ||
                                    unresolvedOpenState
                            }
                        }.getOrDefault(false)
                        if (lifecycleComplete) {
                            return@executeOnPooledThread
                        }
                        lifecycleOpenGuardSleep(lifecycleOpenGuardPollMs)
                    }
                } finally {
                    openingProjectRequests.remove(key, request)
                    if (unresolvedOpenState && isUnresolvedLifecycleOpenActive(project)) {
                        unresolvedLifecycleOpenProjects[key] = project
                    }
                }
            }
        }.isSuccess
        if (!submitted) {
            openingProjectRequests.remove(key, request)
            if (isUnresolvedLifecycleOpenActive(project)) {
                unresolvedLifecycleOpenProjects[key] = project
            }
        }
    }

    private fun isUnresolvedLifecycleOpenActive(project: Project): Boolean {
        return runCatching {
            ApplicationManager.getApplication().runReadAction<Boolean, Exception> {
                !project.isDisposed
            }
        }.getOrDefault(!project.isDisposed)
    }

    private fun isLifecycleOpenProjectUsable(key: String, project: Project): Boolean {
        return runCatching {
            ApplicationManager.getApplication().runReadAction<Boolean, Exception> {
                isLifecycleOpenProject(project) &&
                    ProjectManager.getInstance().openProjects.any { openProject -> openProject === project } &&
                    lifecycleOpenKeys(project).contains(key) &&
                    isUsableProject(project)
            }
        }.getOrDefault(false)
    }

    private fun lifecycleOpenAlreadyOpen(project: Project): Pair<Map<String, Any?>, HttpResponseStatus> {
        return mapOf(
            "status" to "already_open",
            "opened" to false,
            "session_id" to InspectionIdeSession.sessionId,
            "route" to routeMetadata(ResolvedInspectionRoute(project, safeInspectionIdentity(), openProjectIdentity(project))),
        ) to HttpResponseStatus.OK
    }

    private fun lifecycleOpenAlreadyOpening(target: LifecycleOpenTarget): Pair<Map<String, Any?>, HttpResponseStatus> {
        return mapOf(
            "status" to "opening",
            "opened" to false,
            "opening_scheduled" to false,
            "reason" to "already_opening",
            "worktree_path" to target.path.toString(),
            "project_root" to target.projectRoot.toString(),
            "session_id" to InspectionIdeSession.sessionId,
        ) to HttpResponseStatus.OK
    }

    private fun lifecycleOpenStateUnknown(target: LifecycleOpenTarget): Pair<Map<String, Any?>, HttpResponseStatus> {
        return mapOf(
            "status" to "failed",
            "opened" to false,
            "opening_scheduled" to false,
            "reason" to "open_state_unknown",
            "message" to "The IDE accepted the lifecycle open request but did not report the project before the guard timeout.",
            "worktree_path" to target.path.toString(),
            "project_root" to target.projectRoot.toString(),
            "session_id" to InspectionIdeSession.sessionId,
        ) to HttpResponseStatus.CONFLICT
    }

    private fun resolveLifecycleOpenTarget(path: Path): LifecycleOpenTarget {
        val projectRoot = lifecycleOpenProjectRoot(path)
            ?: throw BadRequestException(
                "worktree_path",
                "Parameter 'worktree_path' must point to an existing directory, .ipr project file, or file inside .idea.",
            )
        return LifecycleOpenTarget(
            path = path,
            openPath = projectRoot,
            projectRoot = projectRoot,
            key = canonicalLifecycleOpenKey(projectRoot),
        )
    }

    private fun lifecycleOpenProjectRoot(path: Path): Path? {
        projectRootFromIdeaMetadataPath(path)?.let { return it }
        if (Files.isDirectory(path)) return path
        if (!Files.isRegularFile(path)) return null
        val fileName = path.fileName?.toString() ?: return null
        if (fileName.endsWith(".ipr")) return path.parent
        return null
    }

    private fun canonicalLifecycleOpenKey(path: Path): String {
        return runCatching { path.toRealPath().toString() }
            .getOrElse { path.normalize().toAbsolutePath().toString() }
    }

    private fun canonicalTrustPath(path: Path): Path {
        return runCatching { path.toRealPath() }
            .getOrElse { path.normalize().toAbsolutePath() }
    }

    @Suppress("SameReturnValue")
    private fun findOpenProjectForLifecycleOpen(path: String): Project? {
        val normalized = normalizeFileSystemPath(path) ?: return null
        val canonical = runCatching { canonicalLifecycleOpenKey(Paths.get(normalized)) }.getOrNull()
        return ApplicationManager.getApplication().runReadAction<Project?, Exception> {
            for (project in ProjectManager.getInstance().openProjects) {
                if (!isUsableProject(project)) continue
                val candidatePaths = projectCandidatePaths(project)
                val matchesNormalizedPath = candidatePaths.any { candidatePath ->
                    normalizeFileSystemPath(candidatePath) == normalized
                }
                val matchesCanonicalPath = canonical?.let { key ->
                    candidatePaths.any { candidatePath ->
                        runCatching { canonicalLifecycleOpenKey(Paths.get(candidatePath)) }.getOrNull() == key
                    }
                } == true
                if (matchesNormalizedPath || matchesCanonicalPath) {
                    return@runReadAction project
                }
            }
            null
        }
    }

    private fun findOpenProjectForLifecycleOpenKey(key: String): Project? {
        return ApplicationManager.getApplication().runReadAction<Project?, Exception> {
            ProjectManager.getInstance().openProjects.firstOrNull { project ->
                isLifecycleOpenProject(project) && lifecycleOpenKeys(project).contains(key)
            }
        }
    }

    private fun isLifecycleOpenProject(project: Project?): Boolean {
        return project != null && !project.isDefault && !project.isDisposed
    }

    private fun lifecycleOpenKeys(project: Project): Set<String> {
        return projectCandidatePaths(project)
            .mapNotNull { path -> runCatching { canonicalLifecycleOpenKey(Paths.get(path)) }.getOrNull() }
            .toSet()
    }

    private fun processLifecycleCloseRequest(
        parameters: Map<String, List<String>>,
        context: ChannelHandlerContext,
    ) {
        try {
            val result = closeLifecycleProject(parameters)
            sendJsonResponse(context, formatJsonManually(result.first), result.second)
        } catch (error: BadRequestException) {
            sendJsonResponse(context, formatBadRequest(error), HttpResponseStatus.BAD_REQUEST)
        } catch (error: Exception) {
            logger.warn("Failed to process lifecycle close request", error)
            sendJsonResponse(
                context,
                """{"error": "Internal server error"}""",
                HttpResponseStatus.INTERNAL_SERVER_ERROR,
            )
        }
    }

    private fun buildInspectionCancellationResponse(parameters: Map<String, List<String>>): String {
        val resolved = resolveInspectionRoute(parameters)
            ?: return formatJsonManually(buildMissingProjectResponse(routeProjectSelector(parameters)))
        val key = projectKey(resolved.project)
        val runState = inspectionRunStatesByProject[key]
        val rawExpectedRunId = firstParameter(parameters, "inspection_run_id")
            ?: throw BadRequestException("inspection_run_id", "Parameter 'inspection_run_id' is required.")
        val expectedRunId = rawExpectedRunId.toLongOrNull()?.takeIf { it > 0 }
            ?: throw BadRequestException("inspection_run_id", "Parameter 'inspection_run_id' must be a positive integer.")
        if (runState?.inProgress != true) {
            return formatJsonManually(
                mapOf(
                    "status" to "not_running",
                    "inspection_in_progress" to false,
                    "inspection_cancellation_requested" to false,
                    "project_key" to key,
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(resolved),
                )
            )
        }
        if (expectedRunId != runState.runId) {
            return formatJsonManually(
                mapOf(
                    "status" to "run_changed",
                    "inspection_in_progress" to true,
                    "inspection_cancellation_requested" to false,
                    "expected_inspection_run_id" to expectedRunId,
                    "inspection_run_id" to runState.runId,
                    "project_key" to key,
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(resolved),
                )
            )
        }

        val control = inspectionRunControlsByProject[key]
        if (control == null || control.runId != runState.runId) {
            return formatJsonManually(
                mapOf(
                    "status" to "cancellation_unavailable",
                    "inspection_in_progress" to true,
                    "inspection_cancellation_requested" to false,
                    "inspection_run_id" to runState.runId,
                    "project_key" to key,
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(resolved),
                )
            )
        }

        control.cancellationRequested.set(true)
        control.indicator.cancel()
        return formatJsonManually(
            mapOf(
                "status" to "cancel_requested",
                "inspection_in_progress" to true,
                "inspection_cancellation_requested" to true,
                "inspection_run_id" to runState.runId,
                "project_key" to key,
                "session_id" to InspectionIdeSession.sessionId,
                "route" to routeMetadata(resolved),
            )
        )
    }

    private fun closeLifecycleProject(parameters: Map<String, List<String>>): Pair<Map<String, Any?>, HttpResponseStatus> {
        val closeToken = firstParameter(parameters, "close_token")
            ?: throw BadRequestException("close_token", "Parameter 'close_token' is required.")
        val expectedProjectInstanceId = firstParameter(parameters, "project_instance_id")
            ?: throw BadRequestException("project_instance_id", "Parameter 'project_instance_id' is required.")
        val lease = leasesByProjectInstance[expectedProjectInstanceId]
            ?: return lifecycleCloseSkipped("not_claimed", "No helper lifecycle claim exists for this project instance.")
        if (lease.closeToken != closeToken) {
            return lifecycleCloseSkipped("token_mismatch", "Close token did not match the helper lifecycle claim.", HttpResponseStatus.FORBIDDEN)
        }
        val expectedLeaseId = firstParameter(parameters, "lease_id")
        if (expectedLeaseId != null && expectedLeaseId != lease.leaseId) {
            return lifecycleCloseSkipped("lease_mismatch", "Lease id did not match the helper lifecycle claim.", HttpResponseStatus.FORBIDDEN)
        }
        if (!leasesByProjectInstance.remove(expectedProjectInstanceId, lease)) {
            return lifecycleCloseSkipped("not_claimed", "No helper lifecycle claim exists for this project instance.")
        }
        if (lease.sessionId != InspectionIdeSession.sessionId) {
            return lifecycleCloseSkipped("session_drift", "IDE session changed before cleanup; leaving the project open.", HttpResponseStatus.CONFLICT)
        }
        val expectedSessionId = firstParameter(parameters, "session_id")
        if (expectedSessionId != null && expectedSessionId != InspectionIdeSession.sessionId) {
            return lifecycleCloseSkipped("session_drift", "IDE session changed before cleanup; leaving the project open.", HttpResponseStatus.CONFLICT)
        }

        val project = findOpenProjectByInstanceId(expectedProjectInstanceId)
            ?: run {
                lifecycleOpenOwnershipByProjectInstance.remove(expectedProjectInstanceId)
                return lifecycleCloseSkipped("route_missing", "Claimed project is no longer open; cleanup is already complete.")
            }
        if (projectKey(project) != lease.projectKey) {
            return lifecycleCloseSkipped("project_mismatch", "Claimed project key no longer matches the lifecycle claim.", HttpResponseStatus.CONFLICT)
        }

        val closeAttempts = closeClaimedProject(project, expectedProjectInstanceId)
        val closed = closeAttempts.any { attempt -> attempt.closedVerified }

        if (!closed) {
            return lifecycleCloseSkipped(
                "close_failed",
                "JetBrains IDE declined or failed to close the claimed project.",
                HttpResponseStatus.CONFLICT,
                closeAttempts,
            )
        }
        lifecycleOpenOwnershipByProjectInstance.remove(expectedProjectInstanceId)
        return mapOf(
            "status" to "closed",
            "project_instance_id" to expectedProjectInstanceId,
            "project_key" to lease.projectKey,
            "lease_id" to lease.leaseId,
            "session_id" to InspectionIdeSession.sessionId,
            "closed_at_ms" to System.currentTimeMillis(),
            "close_attempts" to closeAttemptPayload(closeAttempts),
        ) to HttpResponseStatus.OK
    }

    private fun closeClaimedProject(project: Project, projectInstanceId: String): List<LifecycleCloseAttempt> {
        val attempts = mutableListOf<LifecycleCloseAttempt>()
        val saveModes = listOf(true, false, false)
        for ((index, save) in saveModes.withIndex()) {
            val forceCloseReturned = closeProjectOnEdt(project, save)
            val verificationDeadline = closeVerificationNow() + closeVerificationTimeoutMs
            val closedVerified = waitForProjectClosed(project, projectInstanceId, verificationDeadline)
            attempts.add(
                LifecycleCloseAttempt(
                    attempt = index + 1,
                    save = save,
                    forceCloseReturned = forceCloseReturned,
                    closedVerified = closedVerified,
                    projectDisposed = project.isDisposed,
                )
            )
            if (closedVerified) {
                break
            }
        }
        return attempts
    }

    private fun closeProjectOnEdt(project: Project, save: Boolean): Boolean {
        return runCatching {
            val application = ApplicationManager.getApplication()
            if (application.isDispatchThread) {
                forceCloseProject(project, save)
            } else {
                val closeResult = AtomicReference<Boolean>()
                application.invokeAndWait {
                    closeResult.set(forceCloseProject(project, save))
                }
                closeResult.get() == true
            }
        }.getOrDefault(false)
    }

    private fun waitForProjectClosed(project: Project, projectInstanceId: String, deadline: Long): Boolean {
        if (ApplicationManager.getApplication().isDispatchThread) {
            return project.isDisposed || findOpenProjectByInstanceId(projectInstanceId) == null
        }
        do {
            if (project.isDisposed || findOpenProjectByInstanceId(projectInstanceId) == null) {
                return true
            }
            val remainingMs = deadline - closeVerificationNow()
            if (remainingMs > 0) {
                closeVerificationSleep(minOf(closeVerificationPollMs, remainingMs))
            }
        } while (closeVerificationNow() < deadline)
        return project.isDisposed || findOpenProjectByInstanceId(projectInstanceId) == null
    }

    private fun closeAttemptPayload(attempts: List<LifecycleCloseAttempt>): List<Map<String, Any?>> {
        return attempts.map { attempt ->
            mapOf(
                "attempt" to attempt.attempt,
                "save" to attempt.save,
                "force_close_returned" to attempt.forceCloseReturned,
                "closed_verified" to attempt.closedVerified,
                "project_disposed" to attempt.projectDisposed,
            )
        }
    }

    private fun lifecycleCloseSkipped(
        reason: String,
        message: String,
        status: HttpResponseStatus = HttpResponseStatus.OK,
        closeAttempts: List<LifecycleCloseAttempt> = emptyList(),
    ): Pair<Map<String, Any?>, HttpResponseStatus> {
        return mapOf(
            "status" to "skipped",
            "cleanup_skipped" to true,
            "reason" to reason,
            "message" to message,
            "session_id" to InspectionIdeSession.sessionId,
            "close_attempts" to closeAttemptPayload(closeAttempts).takeIf { it.isNotEmpty() },
        ) to status
    }

    private fun findOpenProjectByInstanceId(projectInstanceId: String): Project? {
        return ApplicationManager.getApplication().runReadAction<Project?, Exception> {
            ProjectManager.getInstance().openProjects.firstOrNull { project ->
                isUsableProject(project) && projectInstanceId(project) == projectInstanceId
            }
        }
    }

    private fun resolveInspectionRoute(parameters: Map<String, List<String>>): ResolvedInspectionRoute? {
        val expectedProjectInstanceId = firstParameter(parameters, "project_instance_id")?.trim()?.takeIf { it.isNotEmpty() }
        val selector = InspectionRouteSelector(
            projectKey = firstParameter(parameters, "project_key"),
            projectPath = firstParameter(parameters, "project_path"),
            worktreePath = firstParameter(parameters, "worktree_path"),
            cwd = firstParameter(parameters, "cwd"),
            project = firstParameter(parameters, "project"),
            ide = firstParameter(parameters, "ide"),
        )
        val identity = safeInspectionIdentity()
        val routeIdentity = identity.toRouteIdentity()
        val candidates = scoreInspectionRouteCandidates(listOf(routeIdentity), selector)
        val selected = candidates.firstOrNull() ?: return null
        val best = candidates.filter { candidate -> candidate.score == selected.score }
        val pathSelector = firstParameter(parameters, "project_path")
            ?: firstParameter(parameters, "worktree_path")
            ?: firstParameter(parameters, "cwd")
            ?: firstParameter(parameters, "project")
        val pathBasedSelection = normalizeProjectPath(pathSelector) != null
        val duplicateBest = if (pathBasedSelection) {
            val selectedPathScore = routePathMatchScore(selected.project, pathSelector)
            selectedPathScore != null &&
                best.count { candidate -> routePathMatchScore(candidate.project, pathSelector) == selectedPathScore } > 1
        } else {
            best.size > 1
        }
        if (duplicateBest) {
            throw BadRequestException(
                "project",
                "Multiple open projects matched this request. Retry with an exact project_path, worktree_path, or project_key; use cwd for a nested directory.",
            )
        }
        val project = resolveProjectFromRouteProject(selected.project) ?: return null
        val projectIdentity = openProjectIdentity(project)
        val actualProjectInstanceId = projectIdentity["project_instance_id"] as? String
            ?: projectInstanceId(project)
        if (expectedProjectInstanceId != null && expectedProjectInstanceId != actualProjectInstanceId) {
            throw BadRequestException(
                "project_instance_id",
                "Requested project instance does not match the resolved route. Resolve the route again before continuing.",
            )
        }
        return ResolvedInspectionRoute(project, identity, projectIdentity, selected.score)
    }

    private fun resolveProjectFromRouteProject(routeProject: InspectionRouteProject): Project? {
        routeProject.projectInstanceId?.let { selectedInstanceId ->
            ProjectManager.getInstance().openProjects.firstOrNull { project ->
                isUsableProject(project) && projectInstanceId(project) == selectedInstanceId
            }?.let { return it }
        }
        return routeProject.projectKey?.let(::getCurrentProject)
    }

    private fun routePathMatchScore(project: InspectionRouteProject, selectorPath: String?): Int? {
        return bestPathMatchScore(
            selectorPath,
            listOfNotNull(effectiveProjectRoot(project), project.projectFilePath),
        )
    }

    private fun routeMetadata(project: Project): Map<String, Any?> {
        val identity = safeInspectionIdentity()
        val projectIdentity = openProjectIdentity(project)
        return routeMetadata(ResolvedInspectionRoute(project, identity, projectIdentity))
    }

    private fun safeInspectionIdentity(): Map<String, Any?> {
        return runCatching { buildInspectionIdentity() }.getOrElse {
            mapOf(
                "session_id" to InspectionIdeSession.sessionId,
                "started_at_ms" to InspectionIdeSession.startedAtMs,
                "heartbeat_ms" to System.currentTimeMillis(),
                "pid" to ProcessHandle.current().pid(),
                "port" to runCatching { resolveIdePort() }.getOrNull(),
                "ide_name" to null,
                "ide_version" to null,
                "ide_product_code" to null,
                "plugin_version" to null,
                "plugin_build_fingerprint" to null,
                "plugin_build_commit" to null,
                "plugin_build_short_commit" to null,
                "plugin_build_dirty" to null,
                "plugin_build_time" to null,
                "open_projects" to runCatching { openProjectIdentities() }.getOrDefault(emptyList()),
            )
        }
    }

    private fun routeMetadata(resolved: ResolvedInspectionRoute): Map<String, Any?> {
        return mapOf(
            "port" to resolved.identity["port"],
            "base_url" to resolved.identity["port"]?.let { port -> routeBaseUrl(port) },
            "session_id" to resolved.identity["session_id"],
            "started_at_ms" to resolved.identity["started_at_ms"],
            "heartbeat_ms" to resolved.identity["heartbeat_ms"],
            "project_key" to resolved.projectIdentity["project_key"],
            "project_instance_id" to resolved.projectIdentity["project_instance_id"],
            "project_name" to resolved.projectIdentity["name"],
            "base_path" to routeBasePath(resolved.projectIdentity),
            "project_file_path" to resolved.projectIdentity["project_file_path"],
            "focused" to resolved.projectIdentity["focused"],
            "ide" to ideRouteMetadata(resolved.identity),
            "score" to resolved.score,
        )
    }

    private fun routeBaseUrl(port: Any): String {
        return listOf("http://127.0.0.1:", port.toString(), "/api/inspection").joinToString("")
    }

    private fun routeBasePath(projectIdentity: Map<String, Any?>): String? {
        return (projectIdentity["base_path"] as? String)
            ?: projectRootFromProjectFilePath(projectIdentity["project_file_path"] as? String)
    }

    private fun ideRouteMetadata(identity: Map<String, Any?>): Map<String, Any?> {
        return mapOf(
            "name" to identity["ide_name"],
            "version" to identity["ide_version"],
            "product_code" to identity["ide_product_code"],
            "pid" to identity["pid"],
            "plugin_version" to identity["plugin_version"],
            "plugin_build_fingerprint" to identity["plugin_build_fingerprint"],
        ).filterValues { value -> value != null }
    }

    private fun routeSelectorMetadata(parameters: Map<String, List<String>>): Map<String, Any?> {
        return mapOf(
            "project_key" to firstParameter(parameters, "project_key"),
            "project_path" to firstParameter(parameters, "project_path"),
            "worktree_path" to firstParameter(parameters, "worktree_path"),
            "cwd" to firstParameter(parameters, "cwd"),
            "project" to firstParameter(parameters, "project"),
            "ide" to firstParameter(parameters, "ide"),
        ).filterValues { value -> value != null }
    }

    private fun registryMetadata(): Map<String, Any?> {
        return mapOf(
            "instances_dir" to inspectionRegistryInstancesDir().toString(),
            "environment" to mapOf(
                "registry_dir" to "JETBRAINS_INSPECTION_REGISTRY_DIR",
                "ports" to "JETBRAINS_INSPECTION_PORTS",
            ),
            "ttl_ms" to 60000,
        )
    }

    private fun firstParameter(parameters: Map<String, List<String>>, name: String): String? {
        return parameters[name]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun routeProjectSelector(parameters: Map<String, List<String>>): String? {
        return firstParameter(parameters, "project_key")
            ?: firstParameter(parameters, "project_path")
            ?: firstParameter(parameters, "worktree_path")
            ?: firstParameter(parameters, "cwd")
            ?: firstParameter(parameters, "project")
    }

    private fun Map<String, Any?>.toRouteIdentity(): InspectionRouteIdentity {
        return InspectionRouteIdentity(
            sessionId = this["session_id"] as? String,
            startedAtMs = (this["started_at_ms"] as? Number)?.toLong(),
            heartbeatMs = (this["heartbeat_ms"] as? Number)?.toLong(),
            port = (this["port"] as? Number)?.toInt(),
            ideName = this["ide_name"] as? String,
            ideVersion = this["ide_version"] as? String,
            ideProductCode = this["ide_product_code"] as? String,
            pluginVersion = this["plugin_version"] as? String,
            pluginBuildFingerprint = this["plugin_build_fingerprint"] as? String,
            projects = ((this["open_projects"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()
                ?: openProjectIdentities()).map { identity ->
                InspectionRouteProject(
                    projectKey = identity["project_key"] as? String,
                    projectInstanceId = identity["project_instance_id"] as? String,
                    name = identity["name"] as? String,
                    basePath = identity["base_path"] as? String,
                    projectFilePath = identity["project_file_path"] as? String,
                    focused = identity["focused"] as? Boolean ?: false,
                )
            },
        )
    }
    
    private fun getInspectionProblemsInternal(
        project: Project,
        severity: String = "all",
        scope: String = "whole_project",
        problemType: String? = null,
        filePattern: String? = null,
        limit: Int = 100,
        offset: Int = 0,
        includeStale: Boolean = false,
        directoryParam: String? = null,
        files: List<String>? = null,
        includeUnversioned: Boolean = true,
        changedFilesMode: String? = null,
        maxFiles: Int? = null,
        validatedRequestScope: InspectionCaptureScope? = null,
    ): String {
        return run {
            val requestedScope = scope.trim().takeIf { it.isNotEmpty() } ?: "whole_project"
            val normalizedScope = normalizeProblemsScope(scope)
            val currentFilePath = resolveCurrentFilePath(project, normalizedScope)
            val requestedCaptureScope = validatedRequestScope ?: resolveCaptureScopeEvidence(
                project,
                InspectionCaptureScope(
                    scopeParam = requestedScope,
                    directoryParam = directoryParam,
                    files = files,
                    resolvedCurrentFile = currentFilePath,
                    includeUnversioned = includeUnversioned,
                    changedFilesMode = changedFilesMode,
                    maxFiles = maxFiles,
                ),
            )
            val key = projectKey(project)
            var snapshot = resultsStore.getSnapshot(key)
            val hasSnapshot = snapshot != null
            val runState = inspectionRunStatesByProject[key]
            var staleness = resolveSnapshotStaleness(project, snapshot)
            if (staleness.changeKind == "current_run_psi_churn") {
                val reconciliation = reconcileCurrentRunPsiChurn(project, snapshot)
                snapshot = reconciliation.snapshot
                staleness = if (reconciliation.reconciled) {
                    resolveSnapshotStaleness(project, snapshot)
                } else {
                    staleness.asUnverifiedCurrentRunPsiChurn()
                }
            }
            if (hasSnapshot && staleness.stale) {
                val staleSnapshot = snapshot ?: resultsStore.getSnapshot(key)
                val cachedProblems = staleSnapshot?.problems ?: emptyList()
                val scopeProblemMatcher = buildScopeProblemMatcher(
                    project = project,
                    scopeParam = requestedScope,
                    directoryParam = directoryParam,
                    files = files,
                    resolvedCurrentFile = currentFilePath,
                    includeUnversioned = includeUnversioned,
                    changedFilesMode = changedFilesMode,
                    maxFiles = maxFiles,
                    resolvedScope = requestedCaptureScope,
                )
                val scopedCachedProblems = filterProblemsForScope(cachedProblems, scopeProblemMatcher)
                val filteredCachedProblems = filterProblems(
                    problems = scopedCachedProblems,
                    severity = severity,
                    scope = normalizedScope,
                    currentFilePath = currentFilePath,
                    problemType = problemType,
                    filePattern = filePattern,
                )
                val page = paginateProblems(filteredCachedProblems, limit, offset)
                val staleBase = mutableMapOf(
                    "status" to "stale_results",
                    "project" to project.name,
                    "project_key" to key,
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(project),
                    "inspection_in_progress" to (runState?.inProgress == true),
                    "inspection_run_id" to runState?.runId,
                    "timestamp" to (resultsStore.getTimestamp(key) ?: System.currentTimeMillis()),
                    "message" to "Project files changed since the last inspection. Trigger a new inspection before trusting these results.",
                    "results_may_be_stale" to true,
                    "stale_reasons" to staleness.reasons,
                    "snapshot_change_kind" to staleness.changeKind,
                    "snapshot_outcome" to staleSnapshot?.outcome?.apiValue,
                    "results_source" to staleSnapshot?.source,
                    "results_timestamp_ms" to staleSnapshot?.timestamp,
                    "cached_total_problems" to filteredCachedProblems.size,
                    "cached_problems_shown" to if (includeStale) page.shown else 0,
                    "filters" to mapOf(
                        "severity" to severity,
                        "scope" to requestedScope,
                        "dir" to directoryParam,
                        "files_requested" to (files?.size ?: 0),
                        "problem_type" to (problemType ?: "all"),
                        "file_pattern" to (filePattern ?: "all")
                    )
                )
                addCaptureIncompleteReason(staleBase, staleSnapshot, staleness)
                if (includeStale) {
                    staleBase["message"] = "Project files changed since the last inspection. Returning cached findings because include_stale=true; trigger a new inspection before acting on them."
                    staleBase["include_stale"] = true
                    staleBase["problems"] = page.problems
                    staleBase["pagination"] = mapOf(
                        "limit" to limit,
                        "offset" to offset,
                        "has_more" to page.hasMore,
                        "next_offset" to page.nextOffset,
                    )
                } else {
                    staleBase["include_stale"] = false
                }
                staleSnapshot?.captureDiagnostic?.let { staleBase["capture_diagnostic"] = it }
                addInspectionVerdict(staleBase)
                return formatJsonManually(staleBase.filterValues { it != null })
            }
            if (snapshot != null) {
                val snapshotCaptureScope = snapshot.captureScope?.let { captureScope ->
                    if (captureScope.resolvedFiles != null || captureScope.resolvedDirectory != null) {
                        captureScope
                    } else {
                        resolveCaptureScopeEvidence(project, captureScope)
                    }
                }
                if (!inspectionCaptureScopeCoversRequest(snapshotCaptureScope, requestedCaptureScope)) {
                    val response = mutableMapOf<String, Any?>(
                        "status" to "scope_mismatch",
                        "project" to project.name,
                        "project_key" to key,
                        "session_id" to InspectionIdeSession.sessionId,
                        "route" to routeMetadata(project),
                        "inspection_in_progress" to (runState?.inProgress == true),
                        "inspection_run_id" to runState?.runId,
                        "snapshot_run_id" to snapshot.runId,
                        "timestamp" to snapshot.timestamp,
                        "message" to "The last inspection scope does not cover the requested problems scope. Trigger a new inspection for the requested scope.",
                        "total_problems" to 0,
                        "problems_shown" to 0,
                        "problems" to emptyList<Map<String, Any>>(),
                        "filters" to mapOf(
                            "severity" to severity,
                            "scope" to requestedScope,
                            "dir" to directoryParam,
                            "files_requested" to (files?.size ?: 0),
                            "problem_type" to (problemType ?: "all"),
                            "file_pattern" to (filePattern ?: "all"),
                        ),
                    )
                    addInspectionVerdict(response)
                    return formatJsonManually(response.filterValues { it != null })
                }
            }
            snapshot = reconcileSnapshotWithLiveProblems(project, snapshot)
            if (snapshot != null && snapshot.outcome == InspectionSnapshotOutcome.CAPTURE_INCOMPLETE) {
                val response = mutableMapOf<String, Any?>(
                    "status" to "capture_incomplete",
                    "project" to project.name,
                    "project_key" to key,
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(project),
                    "inspection_in_progress" to (runState?.inProgress == true),
                    "inspection_run_id" to runState?.runId,
                    "timestamp" to snapshot.timestamp,
                    "message" to (
                        snapshot.note
                            ?: "Inspection finished, but the plugin could not conclusively capture the IDE results. Re-run the inspection or open the Inspection Results tool window."
                        ),
                    "capture_incomplete" to true,
                    "results_may_be_incomplete" to true,
                    "total_problems" to 0,
                    "problems_shown" to 0,
                    "problems" to emptyList<Map<String, Any>>(),
                    "pagination" to mapOf(
                        "limit" to limit,
                        "offset" to offset,
                        "has_more" to false,
                        "next_offset" to null
                    ),
                    "filters" to mapOf(
                        "severity" to severity,
                        "scope" to requestedScope,
                        "problem_type" to (problemType ?: "all"),
                        "file_pattern" to (filePattern ?: "all")
                    ),
                    "method" to snapshot.source,
                    "snapshot_outcome" to snapshot.outcome.apiValue,
                )
                addCaptureIncompleteReason(response, snapshot, staleness)
                snapshot.captureDiagnostic?.let { response["capture_diagnostic"] = it }
                addInspectionVerdict(response)
                return formatJsonManually(response)
            }
            val problems = if (snapshot?.problems != null) {
                snapshot.problems
            } else {
                val extractor = enhancedTreeExtractorFactory()
                extractor.extractAllProblems(project)
            }
            
            if (problems.isNotEmpty() || hasSnapshot) {
                val scopeProblemMatcher = buildScopeProblemMatcher(
                    project = project,
                    scopeParam = requestedScope,
                    directoryParam = directoryParam,
                    files = files,
                    resolvedCurrentFile = currentFilePath,
                    includeUnversioned = includeUnversioned,
                    changedFilesMode = changedFilesMode,
                    maxFiles = maxFiles,
                    resolvedScope = requestedCaptureScope,
                )
                val scopedProblems = filterProblemsForScope(problems, scopeProblemMatcher)

                val filteredProblems = filterProblems(
                    problems = scopedProblems,
                    severity = severity,
                    scope = normalizedScope,
                    currentFilePath = currentFilePath,
                    problemType = problemType,
                    filePattern = filePattern
                )

                val page = paginateProblems(filteredProblems, limit, offset)
                
                val response = mutableMapOf<String, Any?>(
                    "status" to "results_available",
                    "project" to project.name,
                    "project_key" to key,
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(project),
                    "inspection_in_progress" to (runState?.inProgress == true),
                    "inspection_run_id" to runState?.runId,
                    "timestamp" to (resultsStore.getTimestamp(key) ?: System.currentTimeMillis()),
                    "total_problems" to page.total,
                    "problems_shown" to page.shown,
                    "problems" to page.problems,
                    "pagination" to mapOf(
                        "limit" to limit,
                        "offset" to offset,
                        "has_more" to page.hasMore,
                        "next_offset" to page.nextOffset
                    ),
                    "filters" to mapOf(
                        "severity" to severity,
                        "scope" to requestedScope,
                        "dir" to directoryParam,
                        "files_requested" to (files?.size ?: 0),
                        "problem_type" to (problemType ?: "all"),
                        "file_pattern" to (filePattern ?: "all")
                    ),
                    "method" to (snapshot?.source ?: "enhanced_tree")
                )
                snapshot?.runId?.let { response["snapshot_run_id"] = it }
                snapshot?.triggerTimeMs?.let { response["snapshot_trigger_time_ms"] = it }
                snapshot?.captureDiagnostic?.let { response["capture_diagnostic"] = it }
                response["snapshot_change_kind"] = resolveSnapshotStaleness(project, snapshot).changeKind
                addInspectionVerdict(response)
                
                formatJsonManually(response.filterValues { it != null })
            } else {
                val response = mutableMapOf<String, Any?>(
                    "status" to "no_results",
                    "project" to project.name,
                    "project_key" to key,
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(project),
                    "inspection_in_progress" to (runState?.inProgress == true),
                    "inspection_run_id" to runState?.runId,
                    "timestamp" to System.currentTimeMillis(),
                    "message" to "No trustworthy inspection result was captured. Trigger and wait for a fresh inspection, or open the Inspection Results view for the exact worktree.",
                    "total_problems" to 0,
                    "problems_shown" to 0,
                    "problems" to emptyList<Map<String, Any>>(),
                    "pagination" to mapOf(
                        "limit" to limit,
                        "offset" to offset,
                        "has_more" to false,
                        "next_offset" to null,
                    ),
                    "filters" to mapOf(
                        "severity" to severity,
                        "scope" to requestedScope,
                        "dir" to directoryParam,
                        "files_requested" to (files?.size ?: 0),
                        "problem_type" to (problemType ?: "all"),
                        "file_pattern" to (filePattern ?: "all"),
                    ),
                    "method" to "enhanced_tree",
                )
                addInspectionVerdict(response)
                formatJsonManually(response)
            }
        }
    }

    private fun getInspectionProblems(
        project: Project,
        severity: String = "all",
        scope: String = "whole_project",
        problemType: String? = null,
        filePattern: String? = null,
        limit: Int = 100,
        offset: Int = 0,
        includeStale: Boolean = false,
        directoryParam: String? = null,
        files: List<String>? = null,
        includeUnversioned: Boolean = true,
        changedFilesMode: String? = null,
        maxFiles: Int? = null,
    ): String {
        return getInspectionProblemsInternal(
            project = project,
            severity = severity,
            scope = scope,
            problemType = problemType,
            filePattern = filePattern,
            limit = limit,
            offset = offset,
            includeStale = includeStale,
            directoryParam = directoryParam,
            files = files,
            includeUnversioned = includeUnversioned,
            changedFilesMode = changedFilesMode,
            maxFiles = maxFiles,
        )
    }

    @Suppress("unused")
    private fun getInspectionProblems(
        project: Project,
        severity: String = "all",
        scope: String = "whole_project",
        problemType: String? = null,
        filePattern: String? = null,
        limit: Int = 100,
        offset: Int = 0,
        includeStale: Boolean = false,
    ): String {
        return getInspectionProblemsInternal(
            project = project,
            severity = severity,
            scope = scope,
            problemType = problemType,
            filePattern = filePattern,
            limit = limit,
            offset = offset,
            includeStale = includeStale,
            directoryParam = null,
            files = null,
            includeUnversioned = true,
            changedFilesMode = null,
            maxFiles = null,
        )
    }

    private fun withCurrentProject(
        context: ChannelHandlerContext,
        projectName: String?,
        refreshProjectState: Boolean = false,
        action: (Project) -> Unit,
    ) {
        val resolvedProjectName = normalizeProjectSelector(projectName)
        val project = ApplicationManager.getApplication().runReadAction<Project?, Exception> { getCurrentProject(resolvedProjectName) }
        if (project == null) {
            sendJsonResponse(
                context,
                formatJsonManually(buildMissingProjectResponse(resolvedProjectName)),
                HttpResponseStatus.NOT_FOUND,
            )
            return
        }
        if (refreshProjectState && !isInspectionInProgress(project)) {
            syncProjectState(project)
        }
        action(project)
    }

    private fun getInspectionStatus(project: Project): String {
        val status = buildInspectionStatus(project)
        return formatJsonManually(status)
    }

    private fun buildInspectionStatus(project: Project): MutableMap<String, Any> {
        val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val inspectionWindows = inspectionResultsToolWindowIds.mapNotNull { toolWindowId ->
            toolWindowManager.getToolWindow(toolWindowId)?.let { toolWindowId to it }
        }
        val problemsWindow = inspectionWindows.firstOrNull()?.second
        val inspectionResultsWindow = inspectionWindows.firstOrNull { (toolWindowId, _) ->
            toolWindowId == "Inspection Results"
        }?.second

        val status = mutableMapOf<String, Any>()
        status["project_name"] = project.name
        val key = projectKey(project)
        status["project_key"] = key
        status["session_id"] = InspectionIdeSession.sessionId
        status["route"] = routeMetadata(project)

        val currentTime = System.currentTimeMillis()
        val runState = inspectionRunStatesByProject[key]
        val lastInspectionTriggerTime = runState?.triggerTimeMs ?: 0L
        val timeSinceLastTrigger = currentTime - lastInspectionTriggerTime
        status["inspection_triggered"] = lastInspectionTriggerTime > 0L

        val dumbService = com.intellij.openapi.project.DumbService.getInstance(project)
        val isIndexing = dumbService.isDumb

        // Use the same extractor as the /problems endpoint so status matches real availability
        var snapshot = resultsStore.getSnapshot(key)
        val hasInspectionSnapshot = snapshot != null
        var staleness = resolveSnapshotStaleness(project, snapshot)
        if (staleness.changeKind == "current_run_psi_churn") {
            val reconciliation = reconcileCurrentRunPsiChurn(project, snapshot)
            snapshot = reconciliation.snapshot
            staleness = if (reconciliation.reconciled) {
                resolveSnapshotStaleness(project, snapshot)
            } else {
                staleness.asUnverifiedCurrentRunPsiChurn()
            }
        }
        if (!staleness.stale) {
            snapshot = reconcileSnapshotWithLiveProblems(project, snapshot)
        }
        val snapshotScopeHasProof = inspectionCaptureScopeHasProof(snapshot?.captureScope)
        val problemsSnapshot = snapshot?.problems ?: emptyList()
        val captureIncomplete = (
            snapshot?.outcome == InspectionSnapshotOutcome.CAPTURE_INCOMPLETE ||
                (snapshot != null && !snapshotScopeHasProof)
            ) && !staleness.stale
        val resultsAvailable = if (hasInspectionSnapshot) {
            snapshot?.outcome != InspectionSnapshotOutcome.CAPTURE_INCOMPLETE &&
                snapshotScopeHasProof &&
                !staleness.stale
        } else {
            false
        }
        if (hasInspectionSnapshot && staleness.stale) {
            status["cached_total_problems"] = problemsSnapshot.size
        } else {
            status["total_problems"] = problemsSnapshot.size
        }
        status["results_source"] = snapshot?.source ?: "tool_window"
        status["results_may_be_stale"] = hasInspectionSnapshot && staleness.stale
        if (snapshot != null) {
            status["results_timestamp_ms"] = snapshot.timestamp
            status["snapshot_outcome"] = snapshot.outcome.apiValue
            if (!snapshot.note.isNullOrBlank()) {
                status["snapshot_note"] = snapshot.note
            }
            snapshot.captureDiagnostic?.let { status["capture_diagnostic"] = it }
        }
        captureIncompleteReason(snapshot, staleness)?.let { reason ->
            status["capture_incomplete_reason"] = reason.apiValue
        }
        if (
            snapshot != null &&
            snapshot.outcome != InspectionSnapshotOutcome.CAPTURE_INCOMPLETE &&
            !snapshotScopeHasProof
        ) {
            status["capture_incomplete_reason"] = CaptureIncompleteReason.SCOPE_NOT_COVERED.apiValue
        }
        if (staleness.reasons.isNotEmpty()) {
            status["stale_reasons"] = staleness.reasons
        }
        status["snapshot_change_kind"] = staleness.changeKind

        // Window visibility hints (best-effort)
        val problemsVisible = problemsWindow?.isVisible ?: false
        val inspectionVisible = inspectionResultsWindow?.isVisible ?: false
        status["problems_window_visible"] = problemsVisible || inspectionVisible
        val activeInspectionWindow = inspectionWindows.firstOrNull { (_, toolWindow) ->
            toolWindow.isVisible
        }?.first
        if (activeInspectionWindow != null) {
            status["active_tool_window"] = activeInspectionWindow
        }

        val inspectionInProgress = runState?.inProgress == true
        val isLikelyStillRunning = inspectionInProgress

        status["is_scanning"] = isIndexing || isLikelyStillRunning
        status["has_inspection_results"] = resultsAvailable
        status["capture_incomplete"] = captureIncomplete
        status["inspection_in_progress"] = inspectionInProgress
        status["time_since_last_trigger_ms"] = timeSinceLastTrigger
        if (runState != null) {
            status["inspection_run_id"] = runState.runId
            val runControl = inspectionRunControlsByProject[key]
            status["inspection_cancellation_requested"] =
                runControl?.runId == runState.runId && runControl.cancellationRequested.get()
            if (runState.inProgress && timeSinceLastTrigger >= inspectionRunExpirationMs) {
                status["inspection_run_expired"] = true
            }
        }
        if (snapshot != null) {
            status["results_age_ms"] = (currentTime - snapshot.timestamp).coerceAtLeast(0L)
            snapshot.runId?.let { status["snapshot_run_id"] = it }
            snapshot.triggerTimeMs?.let { status["snapshot_trigger_time_ms"] = it }
        }
        status["indexing"] = isIndexing

        // Clear indicator for a clean inspection (finished, confirmed, and not stale)
        val cleanInspection = (
            !isIndexing &&
                !isLikelyStillRunning &&
                !inspectionInProgress &&
                snapshotScopeHasProof &&
                snapshot?.outcome == InspectionSnapshotOutcome.CLEAN_CONFIRMED &&
                !staleness.stale
            )
        status["clean_inspection"] = cleanInspection
        addStatusInspectionVerdict(status)

        return status
    }

    private fun reconcileSnapshotWithLiveProblems(
        project: Project,
        snapshot: InspectionResultsSnapshot?,
    ): InspectionResultsSnapshot? {
        val canReconcile = snapshot?.outcome == InspectionSnapshotOutcome.CLEAN_CONFIRMED ||
            snapshot?.outcome == InspectionSnapshotOutcome.CAPTURE_INCOMPLETE
        if (snapshot == null || !canReconcile || snapshot.problems.isNotEmpty()) {
            return snapshot
        }

        val liveExtraction = try {
            enhancedTreeExtractorFactory().extractAllProblemsWithStatus(project)
        } catch (_: Exception) {
            ProblemExtractionResult(emptyList(), succeeded = false)
        }
        if (liveExtraction.source == ProblemExtractionSource.PROBLEMS_FALLBACK) {
            return snapshot
        }
        val liveProblems = liveExtraction.problems

        val captureScopeMatcher = snapshot.captureScope?.let { captureScope ->
            buildScopeProblemMatcher(
                project = project,
                scopeParam = captureScope.scopeParam,
                directoryParam = captureScope.directoryParam,
                files = captureScope.files,
                resolvedCurrentFile = captureScope.resolvedCurrentFile,
                includeUnversioned = captureScope.includeUnversioned,
                changedFilesMode = captureScope.changedFilesMode,
                maxFiles = captureScope.maxFiles,
                resolvedScope = captureScope,
            )
        }
        val compatibleLiveProblems = filterProblemsForScope(liveProblems, captureScopeMatcher)

        if (compatibleLiveProblems.isEmpty()) {
            return snapshot
        }

        val reconciledSnapshot = snapshot.copy(
            problems = compatibleLiveProblems,
            outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
            source = "tool_window",
            note = null,
        )
        val key = projectKey(project)
        return if (resultsStore.replaceSnapshot(key, snapshot, reconciledSnapshot)) {
            reconciledSnapshot
        } else {
            resultsStore.getSnapshot(key)
        }
    }

    private fun reconcileCurrentRunPsiChurn(
        project: Project,
        snapshot: InspectionResultsSnapshot?,
    ): CurrentRunPsiChurnReconciliation {
        if (snapshot == null) {
            return CurrentRunPsiChurnReconciliation(null, false)
        }

        val liveExtraction = try {
            enhancedTreeExtractorFactory().extractAllProblemsWithStatus(project)
        } catch (_: Exception) {
            return CurrentRunPsiChurnReconciliation(snapshot, false)
        }
        if (liveExtraction.source == ProblemExtractionSource.PROBLEMS_FALLBACK) {
            return CurrentRunPsiChurnReconciliation(snapshot, false)
        }
        val liveProblems = liveExtraction.problems
        val captureScopeMatcher = snapshot.captureScope?.let { captureScope ->
            buildScopeProblemMatcher(
                project = project,
                scopeParam = captureScope.scopeParam,
                directoryParam = captureScope.directoryParam,
                files = captureScope.files,
                resolvedCurrentFile = captureScope.resolvedCurrentFile,
                includeUnversioned = captureScope.includeUnversioned,
                changedFilesMode = captureScope.changedFilesMode,
                maxFiles = captureScope.maxFiles,
                resolvedScope = captureScope,
            )
        }
        val compatibleLiveProblems = filterProblemsForScope(liveProblems, captureScopeMatcher)
        val compatibleSnapshotProblems = filterProblemsForScope(snapshot.problems, captureScopeMatcher)
        if (compatibleLiveProblems != compatibleSnapshotProblems) {
            return CurrentRunPsiChurnReconciliation(snapshot, false)
        }

        return CurrentRunPsiChurnReconciliation(snapshot, true)
    }

    private fun SnapshotStaleness.asUnverifiedCurrentRunPsiChurn(): SnapshotStaleness {
        val changeKind = when {
            reasons.contains("unsaved_documents") -> "unsaved_documents"
            reasons.contains("project_changed_since_inspection") -> "project_changed_since_inspection"
            else -> changeKind
        }
        return SnapshotStaleness(true, reasons, changeKind)
    }

    private fun resolveCurrentFilePath(project: Project, normalizedScope: String): String? {
        if (normalizedScope != "current_file") {
            return null
        }
        return resolveActiveEditorFile(project)?.path?.let(::normalizeFileSystemPath)
    }

    private fun waitForInspection(projectName: String?, timeoutMsRaw: Long?, pollMsRaw: Long?): String {
        val timeoutMs = (timeoutMsRaw ?: 180000L).coerceIn(1000L, 300000L)
        val pollMs = (pollMsRaw ?: 1000L).coerceIn(200L, 5000L).coerceAtMost(timeoutMs)
        val start = System.currentTimeMillis()
        val resolvedProjectName = normalizeProjectSelector(projectName)
        var project = ApplicationManager.getApplication().runReadAction<Project?, Exception> { getCurrentProject(resolvedProjectName) }
        while (project == null) {
            if (System.currentTimeMillis() - start >= timeoutMs) {
                return formatWaitError(
                    buildMissingProjectResponse(resolvedProjectName),
                    start,
                    timeoutMs,
                    pollMs,
                    "no_project"
                )
            }

            try {
                TimeUnit.MILLISECONDS.sleep(pollMs)
            } catch (_: Exception) {
                return formatWaitError(
                    mutableMapOf("error" to "Wait interrupted"),
                    start,
                    timeoutMs,
                    pollMs,
                    "interrupted"
                )
            }

            project = ApplicationManager.getApplication().runReadAction<Project?, Exception> { getCurrentProject(resolvedProjectName) }
        }

        val activeProject = project
        var lastStableCount: Int? = null
        var stableCountHits = 0
        var stableSince: Long? = null
        var cleanStableSince: Long? = null
        var noResultsStableSince: Long? = null
        var noResultsObservationCount = 0
        var status = ApplicationManager.getApplication().runReadAction<MutableMap<String, Any>, Exception> { buildInspectionStatus(activeProject) }

        while (true) {
            val hasResults = status["has_inspection_results"] as? Boolean ?: false
            val inspectionTriggered = status["inspection_triggered"] as? Boolean ?: false
            val cleanInspection = status["clean_inspection"] as? Boolean ?: false
            val captureIncomplete = status["capture_incomplete"] as? Boolean ?: false
            val isScanning = status["is_scanning"] as? Boolean ?: false
            val inProgress = status["inspection_in_progress"] as? Boolean ?: false
            val totalProblems = (status["total_problems"] as? Number)?.toInt()
            val resultsSource = status["results_source"] as? String
            val resultsTimestampMs = (status["results_timestamp_ms"] as? Number)?.toLong()
            val snapshotOutcome = status["snapshot_outcome"] as? String
            val timeSinceTrigger = (status["time_since_last_trigger_ms"] as? Number)?.toLong()
            val resultsMayBeStale = status["results_may_be_stale"] as? Boolean ?: false
            val minStableMs = 5000L
            val now = System.currentTimeMillis()

            if (resultsMayBeStale && !isScanning && !inProgress) {
                status["wait_note"] = "Cached inspection results are stale because the project changed after the last run. Trigger a new inspection before trusting these findings."
                return formatWaitResponse(status, start, timeoutMs, pollMs, true, "stale_results")
            }

            if (cleanInspection && !isScanning && !inProgress) {
                if (cleanStableSince == null && resultsTimestampMs == null) {
                    cleanStableSince = now
                }
                if (cleanWaitHasSettled(now, resultsTimestampMs, cleanStableSince, timeSinceTrigger, minStableMs)) {
                    return formatWaitResponse(status, start, timeoutMs, pollMs, true, "clean")
                }
            } else {
                cleanStableSince = null
            }

            if (captureIncomplete && !isScanning && !inProgress) {
                status["wait_note"] = (
                    status["snapshot_note"] as? String
                        ?: "Inspection finished, but the plugin could not conclusively capture the IDE results. Re-run the inspection or open the Inspection Results tool window."
                    )
                return formatWaitResponse(status, start, timeoutMs, pollMs, true, "capture_incomplete")
            }

            if (
                !hasResults &&
                !isScanning &&
                !inProgress &&
                !inspectionTriggered
            ) {
                status["wait_note"] = "No recent inspection - trigger inspection first."
                return formatWaitError(status, start, timeoutMs, pollMs, "no_recent_inspection")
            }

            val toolWindowNoResults = resultsSource == "tool_window" &&
                !hasResults &&
                !isScanning &&
                !inProgress &&
                inspectionTriggered
            if (toolWindowNoResults) {
                if (noResultsStableSince == null) {
                    noResultsStableSince = now
                    noResultsObservationCount = 0
                }
                noResultsObservationCount += 1
                if (noResultsWaitHasSettled(now, noResultsStableSince, timeSinceTrigger, minStableMs)) {
                    status["wait_note"] = "Inspection finished but no trustworthy result was captured. Treat this as UNKNOWN, not clean; rerun inspection or open the Inspection Results tool window for the exact worktree."
                    return formatWaitResponse(status, start, timeoutMs, pollMs, true, "no_results")
                }
            } else {
                noResultsStableSince = null
                noResultsObservationCount = 0
            }

            if (
                hasResults &&
                snapshotOutcome != InspectionSnapshotOutcome.CLEAN_CONFIRMED.apiValue &&
                !isScanning &&
                !inProgress
            ) {
                if (totalProblems != null) {
                    if (totalProblems == lastStableCount) {
                        if (stableCountHits == 0) {
                            stableSince = now
                        }
                        stableCountHits += 1
                    } else {
                        stableCountHits = 0
                        stableSince = null
                        lastStableCount = totalProblems
                    }

                    if (resultsWaitHasSettled(now, stableSince, timeSinceTrigger, minStableMs)) {
                        return formatWaitResponse(status, start, timeoutMs, pollMs, true, "results")
                    }
                } else {
                    return formatWaitResponse(status, start, timeoutMs, pollMs, true, "results")
                }
            }

            if (System.currentTimeMillis() - start >= timeoutMs) {
                if (
                    resultsSource == "tool_window" &&
                    !hasResults &&
                    !isScanning &&
                    !inProgress &&
                    inspectionTriggered &&
                    timeSinceTrigger != null &&
                    timeSinceTrigger >= 15000 &&
                    noResultsObservationCount >= 2
                ) {
                    status["wait_note"] = "Inspection finished but no trustworthy result was captured. Treat this as UNKNOWN, not clean; rerun inspection or open the Inspection Results tool window for the exact worktree."
                    return formatWaitResponse(status, start, timeoutMs, pollMs, true, "no_results")
                }
                return formatWaitResponse(status, start, timeoutMs, pollMs, false, "timeout")
            }

            try {
                TimeUnit.MILLISECONDS.sleep(pollMs)
            } catch (_: Exception) {
                return formatWaitResponse(status, start, timeoutMs, pollMs, false, "interrupted")
            }

            status = ApplicationManager.getApplication().runReadAction<MutableMap<String, Any>, Exception> { buildInspectionStatus(activeProject) }
        }
    }

    private fun formatWaitResponse(
        status: MutableMap<String, Any>,
        startMs: Long,
        timeoutMs: Long,
        pollMs: Long,
        completed: Boolean,
        reason: String
    ): String {
        val response = status.toMutableMap()
        if (reason == "stale_results") {
            (response["total_problems"] as? Number)?.let { total ->
                response.putIfAbsent("cached_total_problems", total.toInt())
            }
            response.remove("total_problems")
        }
        val elapsed = System.currentTimeMillis() - startMs
        response["wait_completed"] = completed
        response["timed_out"] = !completed && reason == "timeout"
        response["completion_reason"] = reason
        response["wait_ms"] = elapsed
        response["timeout_ms"] = timeoutMs
        response["poll_ms"] = pollMs
        addStatusInspectionVerdict(response)
        return formatJsonManually(response)
    }

    private fun formatWaitError(
        responseData: MutableMap<String, Any>,
        startMs: Long,
        timeoutMs: Long,
        pollMs: Long,
        reason: String
    ): String {
        val response = responseData.toMutableMap()
        response["wait_completed"] = false
        response["timed_out"] = reason == "timeout"
        response["session_id"] = InspectionIdeSession.sessionId
        if (reason == "no_project" && response["wait_note"] == null) {
            response["wait_note"] = "No project found - ensure the IDE has an open project, or pass the exact project name."
        }
        response["completion_reason"] = reason
        response["wait_ms"] = System.currentTimeMillis() - startMs
        response["timeout_ms"] = timeoutMs
        response["poll_ms"] = pollMs
        addStatusInspectionVerdict(response)
        return formatJsonManually(response)
    }

    private fun buildMissingProjectResponse(projectName: String?): MutableMap<String, Any> {
        val response = mutableMapOf<String, Any>()
        response["session_id"] = InspectionIdeSession.sessionId
        response["status"] = "no_project"
        if (projectName == null) {
            response["error"] = "No project found"
            addStatusInspectionVerdict(response)
            return response
        }

        val displayProjectName = displayProjectSelector(projectName)
        response["error"] = "Requested project '$displayProjectName' is not open in the IDE."

        val openProjectNames = ProjectManager.getInstance().openProjects
            .filter { project -> !project.isDefault && !project.isDisposed && project.isInitialized }
            .map { project -> project.name }
            .distinct()
            .sorted()

        if (openProjectNames.isNotEmpty()) {
            response["open_projects"] = openProjectNames.take(5)
            val suggestedOpenProjects = suggestOpenProjects(displayProjectName, openProjectNames)
            if (suggestedOpenProjects.isNotEmpty()) {
                response["suggested_open_projects"] = suggestedOpenProjects
            }
        }

        val suggestedRecentProjects = suggestRecentProjects(displayProjectName, openProjectNames)
        if (suggestedRecentProjects.isNotEmpty()) {
            response["suggested_recent_projects"] = suggestedRecentProjects.map { suggestion ->
                mapOf(
                    "name" to suggestion.name,
                    "path" to suggestion.path,
                )
            }
        }

        addStatusInspectionVerdict(response)
        return response
    }

    private fun suggestOpenProjects(projectName: String, openProjectNames: List<String>): List<String> {
        return openProjectNames
            .mapNotNull { candidateName ->
                val score = scoreProjectSelector(projectName, candidateName, null)
                if (score >= 70) candidateName to score else null
            }
            .sortedWith(compareByDescending<Pair<String, Int>> { (_, score) -> score }.thenBy { (name, _) -> name })
            .map { (name, _) -> name }
            .take(3)
    }

    private fun suggestRecentProjects(projectName: String, openProjectNames: List<String>): List<ProjectSuggestion> {
        return try {
            val recentProjectsManager = recentProjectsManagerProvider() ?: return emptyList()
            recentProjectsManager.getRecentPaths()
                .mapNotNull { recentPath: String ->
                    val normalizedPath = normalizeProjectPath(recentPath) ?: return@mapNotNull null
                    if (!Files.exists(Paths.get(normalizedPath))) {
                        return@mapNotNull null
                    }

                    val candidateName = recentProjectsManager.getProjectName(recentPath)
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?: recentProjectsManager.getDisplayName(recentPath)
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                        ?: Paths.get(normalizedPath).fileName?.toString()
                        ?: return@mapNotNull null

                    if (openProjectNames.any { openProjectName -> openProjectName.equals(candidateName, ignoreCase = true) }) {
                        return@mapNotNull null
                    }

                    val score = scoreProjectSelector(projectName, candidateName, normalizedPath)
                    if (score < 70) {
                        return@mapNotNull null
                    }

                    ProjectSuggestion(candidateName, normalizedPath, score)
                }
                .distinctBy { suggestion: ProjectSuggestion -> suggestion.path }
                .sortedWith(
                    compareByDescending<ProjectSuggestion> { suggestion -> suggestion.score }
                        .thenBy { suggestion -> suggestion.name }
                )
                .take(3)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun scoreProjectSelector(projectSelector: String, candidateName: String, candidatePath: String?): Int {
        val normalizedSelector = canonicalProjectToken(projectSelector)
        val normalizedCandidateName = canonicalProjectToken(candidateName)
        if (normalizedSelector.isEmpty() || normalizedCandidateName.isEmpty()) {
            return 0
        }

        val normalizedCandidatePath = canonicalProjectToken(candidatePath ?: "")
        val selectorPathHint = normalizeProjectPath(projectSelector)
        if (candidatePath != null && selectorPathHint != null && selectorPathHint == candidatePath) {
            return 100
        }
        if (normalizedSelector == normalizedCandidateName) {
            return 95
        }
        if (normalizedCandidateName.startsWith(normalizedSelector) || normalizedSelector.startsWith(normalizedCandidateName)) {
            return 85
        }
        if (normalizedCandidateName.contains(normalizedSelector) || normalizedSelector.contains(normalizedCandidateName)) {
            return 80
        }
        if (normalizedCandidatePath.isNotEmpty() && normalizedCandidatePath.contains(normalizedSelector)) {
            return 78
        }

        val editDistance = levenshteinDistance(normalizedSelector, normalizedCandidateName)
        return when {
            editDistance <= 1 -> 76
            editDistance == 2 -> 72
            else -> 0
        }
    }

    private fun canonicalProjectToken(value: String): String {
        return value.lowercase().filter { character -> character.isLetterOrDigit() }
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) {
            return 0
        }
        if (left.isEmpty()) {
            return right.length
        }
        if (right.isEmpty()) {
            return left.length
        }

        val previousRow = IntArray(right.length + 1) { index -> index }
        val currentRow = IntArray(right.length + 1)

        for (leftIndex in left.indices) {
            currentRow[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                currentRow[rightIndex + 1] = minOf(
                    currentRow[rightIndex] + 1,
                    previousRow[rightIndex + 1] + 1,
                    previousRow[rightIndex] + substitutionCost,
                )
            }
            currentRow.copyInto(previousRow)
        }

        return previousRow[right.length]
    }
    
    private fun triggerInspectionAsync(
        project: Project,
        runId: Long,
        captureScope: InspectionCaptureScope,
        profileName: String? = null,
        control: InspectionRunControl,
    ) {
        val key = projectKey(project)
        if (!isCurrentInspectionRun(key, runId)) {
            inspectionRunControlsByProject.remove(key, control)
            return
        }
        try {
            inspectionProcessRunner(
                Runnable {
                    runInspectionUnderProgress(project, runId, captureScope, profileName)
                },
                control.indicator,
            )
        } finally {
            if (inspectionRunStatesByProject[key]?.inProgress != true) {
                inspectionRunControlsByProject.remove(key, control)
            }
        }
    }

    private fun runInspectionUnderProgress(
        project: Project,
        runId: Long,
        captureScope: InspectionCaptureScope,
        profileName: String? = null,
    ) {
        val key = projectKey(project)
        var captureScheduled = false
        try {
            if (!isCurrentInspectionRun(key, runId)) {
                return
            }
            checkInspectionRunCancellation(key, runId)
            resultsStore.clear(key)

            clearPriorInspectionResults(project)
            
            syncProjectState(project)
            waitForSmartMode(project)
            checkInspectionRunCancellation(key, runId)
            val inspectionInputState = captureStableProjectState(project)
            val dumbAfterSync = DumbService.getInstance(project).isDumb

            val profileManager = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project)
            val requestedProfileName = profileName?.trim()?.takeIf { it.isNotEmpty() }
            val profileNames = try {
                profileManager.profiles.map { it.name }.sorted()
            } catch (_: Exception) {
                null
            }
            val requestedProfileMissing = requestedProfileName != null &&
                profileNames != null &&
                requestedProfileName !in profileNames
            val requestedProfileUnverified = requestedProfileName != null && profileNames == null
            if (requestedProfileMissing || requestedProfileUnverified) {
                val exitReason = if (requestedProfileMissing) "profile_missing" else "profile_list_unreadable"
                val note = if (requestedProfileMissing) {
                    "Requested inspection profile '$requestedProfileName' was not found."
                } else {
                    "Requested inspection profile '$requestedProfileName' could not be verified."
                }
                resultsStore.setSnapshot(
                    key,
                    InspectionResultsSnapshot(
                        problems = emptyList(),
                        timestamp = System.currentTimeMillis(),
                        projectState = inspectionInputState,
                        outcome = InspectionSnapshotOutcome.CAPTURE_INCOMPLETE,
                        source = "profile_resolution",
                        note = note,
                        captureScope = captureScope,
                        captureDiagnostic = mapOf(
                            "profile_requested" to requestedProfileName,
                            "profile_missing" to requestedProfileMissing,
                            "profile_unverified" to requestedProfileUnverified,
                            "profile_list_readable" to (profileNames != null),
                            "profile_available_names" to profileNames?.take(25),
                            "profile_source" to "request",
                            "exit_reason" to exitReason,
                        ).filterValues { it != null },
                        captureIncompleteReason = CaptureIncompleteReason.PROFILE_RESOLUTION_ERROR,
                        runId = runId,
                        triggerTimeMs = inspectionRunStatesByProject[key]?.triggerTimeMs,
                    )
                )
                return
            }

            val changedFilesScopeFiles = if (captureScope.scopeParam?.lowercase()?.trim() == "changed_files") {
                captureScope.resolvedFiles.orEmpty().map { path ->
                    LocalFileSystem.getInstance().findFileByPath(path)
                        ?: throw IllegalStateException("Validated changed file '$path' is no longer available.")
                }
            } else {
                null
            }
            val effectiveCaptureScope = resolveCaptureScopeEvidence(
                project = project,
                captureScope = captureScope,
                resolvedChangedFiles = changedFilesScopeFiles,
            )
            inspectionRunStatesByProject.computeIfPresent(key) { _, state ->
                if (state.runId == runId) state.copy(captureScope = effectiveCaptureScope) else state
            }
            if (changedFilesScopeFiles != null && changedFilesScopeFiles.isEmpty()) {
                resultsStore.setSnapshot(
                    key,
                    InspectionResultsSnapshot(
                        problems = emptyList(),
                        timestamp = System.currentTimeMillis(),
                        projectState = inspectionInputState,
                        outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                        source = "empty_changed_files",
                        note = "No changed files matched the requested inspection scope.",
                        captureScope = effectiveCaptureScope,
                        runId = runId,
                        triggerTimeMs = inspectionRunStatesByProject[key]?.triggerTimeMs,
                    )
                )
                return
            }

            val scope: AnalysisScope = buildAnalysisScope(
                project = project,
                scopeParam = effectiveCaptureScope.scopeParam,
                directoryParam = effectiveCaptureScope.directoryParam,
                files = effectiveCaptureScope.files,
                resolvedCurrentFile = effectiveCaptureScope.resolvedCurrentFile,
                includeUnversioned = effectiveCaptureScope.includeUnversioned,
                changedFilesMode = effectiveCaptureScope.changedFilesMode,
                maxFiles = effectiveCaptureScope.maxFiles,
                resolvedChangedFiles = changedFilesScopeFiles,
            )
            val scopeDiagnostics = inspectCaptureScopeFiles(
                project = project,
                scopeParam = captureScope.scopeParam,
                directoryParam = captureScope.directoryParam,
                files = captureScope.files,
                resolvedCurrentFile = captureScope.resolvedCurrentFile,
                resolvedChangedFiles = changedFilesScopeFiles,
            ) + mapOf(
                "dumb_after_sync" to dumbAfterSync,
                "dispatch_thread_before_inspection" to ApplicationManager.getApplication().isDispatchThread,
            )
            val scopeProblemMatcher = buildScopeProblemMatcher(
                project = project,
                scopeParam = captureScope.scopeParam,
                directoryParam = captureScope.directoryParam,
                files = captureScope.files,
                resolvedCurrentFile = captureScope.resolvedCurrentFile,
                includeUnversioned = captureScope.includeUnversioned,
                changedFilesMode = captureScope.changedFilesMode,
                maxFiles = captureScope.maxFiles,
                resolvedChangedFiles = changedFilesScopeFiles,
                resolvedScope = effectiveCaptureScope,
            )
            
            val profile = if (requestedProfileName != null) {
                profileManager.getProfile(requestedProfileName)
            } else {
                profileManager.currentProfile
            }
            val resolvedProfileName = try {
                profile.name
            } catch (_: Exception) {
                null
            }
            val profileDiagnostic = mapOf(
                "profile_requested" to requestedProfileName,
                "profile_resolved_name" to resolvedProfileName,
                "profile_missing" to false,
                "profile_unverified" to false,
                "profile_list_readable" to (profileNames != null),
                "profile_available_names" to profileNames?.take(25),
                "profile_source" to if (requestedProfileName != null) "request" else "current",
            ).filterValues { it != null }
            val ideProductCode = safeInspectionIdentity()["ide_product_code"]?.toString()
            val targetToolShortNames = expectedProofToolShortNames(
                ideProductCode = ideProductCode,
                requestedProfileName = requestedProfileName,
            )

            @Suppress("USELESS_CAST")
            val inspectionManager = InspectionManager.getInstance(project) as com.intellij.codeInspection.ex.InspectionManagerEx
            
            @Suppress("UnstableApiUsage", "USELESS_CAST")
            val globalContext = inspectionManager.createNewGlobalContext() as com.intellij.codeInspection.ex.GlobalInspectionContextImpl
            globalContext.setExternalProfile(profile)
            globalContext.currentScope = scope
            
            @Suppress("UnstableApiUsage")
            globalContext.performInspectionsWithProgress(scope, false, false)
            ProgressManager.checkCanceled()

            try {
                @Suppress("UnstableApiUsage")
                val viewReady = globalContext.initializeViewIfNeeded()
                val initialView = try {
                    @Suppress("UnstableApiUsage")
                    globalContext.view
                } catch (_: Exception) {
                    null
                }
                run {
                    try {
                        checkInspectionRunCancellation(key, runId)
                        val extractor = enhancedTreeExtractorFactory()
                        var extractedFromContextSucceeded = false
                        val contextExtraction = try {
                            val fallbackScopeFiles = scopedPsiFilesForInspectionEngine(
                                project,
                                effectiveCaptureScope.scopeParam,
                                effectiveCaptureScope.directoryParam,
                                effectiveCaptureScope.files,
                                effectiveCaptureScope.resolvedCurrentFile,
                                resolvedChangedFiles = changedFilesScopeFiles,
                                scopeDiagnostics["scope_file_diagnostics"] as? List<*>,
                                ideProductCode = ideProductCode,
                            )
                            extractProblemsFromContextSafe(
                                globalContext,
                                project,
                                targetToolShortNames,
                                fallbackScopeFiles,
                                cancellationCheck = { checkInspectionRunCancellation(key, runId) },
                            ).also {
                                extractedFromContextSucceeded = true
                            }
                        } catch (e: Exception) {
                            rethrowIfCanceled(e)
                            InspectionModelExtraction(
                                problems = emptyList(),
                                problemDescriptorCount = 0,
                                enabledToolCount = 0,
                                readableToolCount = 0,
                                unreadableToolCount = 1,
                                unreadableReasons = listOf(e.javaClass.simpleName.ifBlank { "context_extraction_exception" }),
                            )
                        }
                        val extractedFromContext = contextExtraction.problems
                        val scopedContextResults = filterProblemsForScope(extractedFromContext, scopeProblemMatcher)
                        val modelVerdict = contextExtraction.verdict
                        val modelExtractionClean = extractedFromContextSucceeded &&
                            modelVerdict == InspectionModelVerdict.CLEAN &&
                            scopedContextResults.isEmpty()

                        val captureStartMs = System.currentTimeMillis()
                        val deadlineMs = captureStartMs + 60000
                        var bestResults: List<Map<String, Any>> = scopedContextResults
                        var bestSource = if (scopedContextResults.isNotEmpty()) "global_context" else "inspection_view"
                        var lastSize = bestResults.size
                        var lastChangeMs = System.currentTimeMillis()
                        var observedInspectionView = false
                        var observedSettledEmptyInspectionView = false
                        var observedStableReadableEmptyInspectionView = false
                        var observedStableEmptyResultsWithoutInspectionView = false
                        var observedOpaqueSettledEmptyInspectionView = false
                        var observedModelCleanInspection = false
                        var observedNonEmptyInspectionTree = false
                        var observedStaleNonEmptyInspectionTree = false
                        var observedTransientEmptyInspectionViewEvidence = false
                        var inspectionViewUpdating = false
                        var lastViewObservation: InspectionViewObservation? = null
                        var readableEmptyInspectionViewStableSince: Long? = null
                        var inspectionViewObservationCount = 0
                        var readableEmptyInspectionViewObservationCount = 0
                        var transientUpdatingEmptyObservationCount = 0
                        var unreadableProblemStateObservationCount = 0
                        var nullRootChildObservationCount = 0
                        var toolWindowObservationCount = 0
                        var compatibleToolWindowObservationCount = 0
                        var extractionFailureCount = if (extractedFromContextSucceeded) 0 else 1
                        var successfulExtractionCount = if (extractedFromContextSucceeded) 1 else 0
                        var lastExtractionCycleSucceeded = extractedFromContextSucceeded
                        var lastToolExtractionSucceeded = false
                        var captureExitReason = "deadline"
                        var inspectionViewMarkedFinished = false

                        fun resetReadableEmptyInspectionViewEvidence() {
                            readableEmptyInspectionViewStableSince = null
                            readableEmptyInspectionViewObservationCount = 0
                        }

                        fun extractFromViewSafe(view: InspectionResultsView): ProblemExtractionResult {
                            val app = ApplicationManager.getApplication()
                            if (app.isDispatchThread) {
                                return extractor.extractAllProblemsFromInspectionViewWithStatus(view, project)
                            }
                            val holder = AtomicReference<ProblemExtractionResult>()
                            app.invokeAndWait {
                                holder.set(extractor.extractAllProblemsFromInspectionViewWithStatus(view, project))
                            }
                            return holder.get() ?: ProblemExtractionResult(emptyList(), succeeded = false)
                        }

                        fun inspectViewStateSafe(view: InspectionResultsView): InspectionViewObservation {
                            val app = ApplicationManager.getApplication()

                            fun inspectViewState(): InspectionViewObservation {
                                val rootChildCount = try {
                                    readInspectionRootChildCount(inspectionResultsTree(view)?.model?.root)
                                } catch (e: Exception) {
                                    rethrowIfCanceled(e)
                                    null
                                }
                                val (hasProblems, problemStateReadable) = try {
                                    view.hasProblems() to true
                                } catch (e: Exception) {
                                    rethrowIfCanceled(e)
                                    false to false
                                }
                                val (isUpdating, updateStateReadable) = try {
                                    view.isUpdating to true
                                } catch (e: Exception) {
                                    rethrowIfCanceled(e)
                                    false to false
                                }
                                return InspectionViewObservation(
                                    isUpdating = isUpdating,
                                    hasProblems = hasProblems,
                                    rootChildCount = rootChildCount,
                                    updateStateReadable = updateStateReadable,
                                    problemStateReadable = problemStateReadable,
                                )
                            }

                            if (app.isDispatchThread) {
                                return inspectViewState()
                            }

                            val holder = AtomicReference<InspectionViewObservation>()
                            app.invokeAndWait {
                                holder.set(inspectViewState())
                            }
                            return holder.get() ?: InspectionViewObservation(
                                isUpdating = false,
                                hasProblems = false,
                                rootChildCount = null,
                                updateStateReadable = false,
                                problemStateReadable = false,
                            )
                        }

                        fun markInspectionViewFinishedSafe(view: InspectionResultsView) {
                            val app = ApplicationManager.getApplication()
                            if (app.isDispatchThread) {
                                view.setUpdating(false)
                                return
                            }
                            app.invokeAndWait {
                                view.setUpdating(false)
                            }
                        }

                        var viewReadyOk = false

                        while (System.currentTimeMillis() < deadlineMs) {
                            checkInspectionRunCancellation(key, runId)
                            if (!isCurrentInspectionRun(key, runId)) {
                                captureExitReason = "superseded"
                                break
                            }
                            val loopNow = System.currentTimeMillis()
                            if (!viewReadyOk) {
                                viewReadyOk = try {
                                    viewReady.waitFor(1)
                                } catch (e: Exception) {
                                    rethrowIfCanceled(e)
                                    false
                                }
                            }
                            val view = try {
                                @Suppress("UnstableApiUsage")
                                globalContext.view
                            } catch (e: Exception) {
                                rethrowIfCanceled(e)
                                null
                            } ?: initialView
                            if (!viewReadyOk && view != null) {
                                viewReadyOk = true
                            }

                            if (
                                viewReadyOk &&
                                view != null &&
                                !inspectionViewMarkedFinished &&
                                (modelVerdict == InspectionModelVerdict.CLEAN || modelVerdict == InspectionModelVerdict.HAS_PROBLEMS)
                            ) {
                                try {
                                    markInspectionViewFinishedSafe(view)
                                } catch (e: Exception) {
                                    rethrowIfCanceled(e)
                                }
                                inspectionViewMarkedFinished = true
                            }

                            var viewExtractionSucceeded = false
                            if (viewReadyOk && view != null) {
                                observedInspectionView = true
                                val viewObservation = try {
                                    inspectViewStateSafe(view)
                                } catch (e: Exception) {
                                    rethrowIfCanceled(e)
                                    InspectionViewObservation(
                                        isUpdating = false,
                                        hasProblems = false,
                                        rootChildCount = null,
                                        updateStateReadable = false,
                                        problemStateReadable = false,
                                    )
                                }
                                inspectionViewObservationCount += 1
                                if (!viewObservation.problemStateReadable) {
                                    unreadableProblemStateObservationCount += 1
                                }
                                if (viewObservation.rootChildCount == null) {
                                    nullRootChildObservationCount += 1
                                }
                                if (isReadableEmptyInspectionView(viewObservation)) {
                                    readableEmptyInspectionViewObservationCount += 1
                                }
                                lastViewObservation = viewObservation
                                inspectionViewUpdating = viewObservation.isUpdating
                                when {
                                    hasInspectionViewProblems(viewObservation) -> {
                                        observedNonEmptyInspectionTree = true
                                        resetReadableEmptyInspectionViewEvidence()
                                        transientUpdatingEmptyObservationCount = 0
                                    }
                                    isSettledCleanInspectionView(viewObservation) -> {
                                        observedSettledEmptyInspectionView = true
                                        if (readableEmptyInspectionViewStableSince == null) {
                                            readableEmptyInspectionViewStableSince = loopNow
                                        }
                                    }
                                    isReadableEmptyInspectionView(viewObservation) -> {
                                        if (readableEmptyInspectionViewStableSince == null) {
                                            readableEmptyInspectionViewStableSince = loopNow
                                        }
                                    }
                                    isTransientUpdatingUnreadableEmptyCandidate(viewObservation) &&
                                        !observedNonEmptyInspectionTree -> {
                                        observedTransientEmptyInspectionViewEvidence = true
                                        transientUpdatingEmptyObservationCount += 1
                                        if (readableEmptyInspectionViewStableSince == null) {
                                            readableEmptyInspectionViewStableSince = loopNow
                                        }
                                    }
                                    isOpaqueSettledEmptyInspectionViewCandidate(viewObservation) -> {
                                        observedOpaqueSettledEmptyInspectionView = true
                                    }
                                    else -> {
                                        resetReadableEmptyInspectionViewEvidence()
                                        transientUpdatingEmptyObservationCount = 0
                                    }
                                }
                                val attempt = try {
                                    val extraction = extractFromViewSafe(view)
                                    viewExtractionSucceeded = extraction.succeeded
                                    if (!extraction.succeeded) {
                                        extractionFailureCount += 1
                                    }
                                    extraction.problems
                                } catch (e: Exception) {
                                    rethrowIfCanceled(e)
                                    extractionFailureCount += 1
                                    emptyList()
                                }
                                if (viewExtractionSucceeded) {
                                    successfulExtractionCount += 1
                                }
                                val scopedAttempt = filterProblemsForScope(attempt, scopeProblemMatcher)
                                if (scopedAttempt.size > bestResults.size) {
                                    bestResults = scopedAttempt
                                    bestSource = "inspection_view"
                                }
                            }

                            var toolExtractionSucceeded = false
                            var toolExtractionSource = ProblemExtractionSource.NONE
                            val toolResults = try {
                                val extraction = ApplicationManager.getApplication().runReadAction<ProblemExtractionResult, Exception> {
                                    extractor.extractAllProblemsWithStatus(project)
                                }
                                toolExtractionSucceeded = extraction.succeeded
                                toolExtractionSource = extraction.source
                                if (!extraction.succeeded) {
                                    extractionFailureCount += 1
                                }
                                extraction.problems
                            } catch (e: Exception) {
                                rethrowIfCanceled(e)
                                extractionFailureCount += 1
                                emptyList()
                            }
                            if (toolExtractionSucceeded) {
                                successfulExtractionCount += 1
                            }
                            lastToolExtractionSucceeded = toolExtractionSucceeded
                            lastExtractionCycleSucceeded = (!observedInspectionView || viewExtractionSucceeded) && toolExtractionSucceeded
                            toolWindowObservationCount += 1
                            val compatibleToolResults = filterProblemsForScope(toolResults, scopeProblemMatcher)
                            if (compatibleToolResults.isNotEmpty()) {
                                compatibleToolWindowObservationCount += 1
                            }
                            val trustedToolResults = selectTrustedToolResults(
                                toolResults = toolResults,
                                compatibleToolResults = compatibleToolResults,
                                observedInspectionView = observedInspectionView,
                                hasScopedMatcher = scopeProblemMatcher != null,
                            )
                            val toolResultsHaveCompatibleProvenance =
                                toolExtractionSource != ProblemExtractionSource.PROBLEMS_FALLBACK
                            if (toolResultsHaveCompatibleProvenance && trustedToolResults.size > bestResults.size) {
                                bestResults = trustedToolResults
                                bestSource = "tool_window"
                            }

                            if (bestResults.size != lastSize) {
                                lastSize = bestResults.size
                                lastChangeMs = loopNow
                            }

                            val stableForMs = loopNow - lastChangeMs
                            val pollingElapsedMs = loopNow - captureStartMs
                            if (
                                !observedStaleNonEmptyInspectionTree &&
                                shouldTreatNonEmptyInspectionTreeAsStaleCleanEvidence(
                                    observedNonEmptyInspectionTree = observedNonEmptyInspectionTree,
                                    modelExtractionClean = modelExtractionClean,
                                    modelProblemDescriptorCount = contextExtraction.problemDescriptorCount,
                                    bestResultsEmpty = bestResults.isEmpty(),
                                    extractionFailureCount = extractionFailureCount,
                                    lastExtractionCycleSucceeded = lastExtractionCycleSucceeded,
                                    lastToolExtractionSucceeded = lastToolExtractionSucceeded,
                                    inspectionViewUpdating = inspectionViewUpdating,
                                    stableForMs = stableForMs,
                                    pollingElapsedMs = pollingElapsedMs,
                                )
                            ) {
                                observedStaleNonEmptyInspectionTree = true
                            }
                            val effectiveObservedNonEmptyInspectionTree =
                                observedNonEmptyInspectionTree && !observedStaleNonEmptyInspectionTree
                            if (
                                !observedStableReadableEmptyInspectionView &&
                                shouldPromoteStableReadableEmptyInspectionView(
                                    readableEmptyInspectionViewStableSince = readableEmptyInspectionViewStableSince,
                                    readableEmptyInspectionViewObservationCount = readableEmptyInspectionViewObservationCount,
                                    transientUpdatingEmptyObservationCount = transientUpdatingEmptyObservationCount,
                                    inspectionViewUpdating = inspectionViewUpdating,
                                    now = loopNow,
                                    pollingElapsedMs = pollingElapsedMs,
                                )
                            ) {
                                observedStableReadableEmptyInspectionView = true
                            }
                            if (
                                !observedStableEmptyResultsWithoutInspectionView &&
                                shouldTrustStableScopedEmptyResults(
                                    viewReadyOk = viewReadyOk,
                                    hasScopedMatcher = scopeProblemMatcher != null,
                                    observedInspectionView = observedInspectionView,
                                    inspectionViewUpdating = inspectionViewUpdating,
                                    hasSettledInspectionViewEvidence = observedSettledEmptyInspectionView ||
                                        observedStableReadableEmptyInspectionView,
                                    hasOpaqueSettledEmptyInspectionViewEvidence = observedOpaqueSettledEmptyInspectionView,
                                    hasTransientEmptyInspectionViewEvidence = observedTransientEmptyInspectionViewEvidence,
                                    hasModelCleanEvidence = modelExtractionClean,
                                    extractionSucceeded = shouldTreatScopedEmptyExtractionAsSucceeded(
                                        lastExtractionCycleSucceeded = lastExtractionCycleSucceeded,
                                        observedTransientEmptyInspectionViewEvidence = observedTransientEmptyInspectionViewEvidence,
                                        lastToolExtractionSucceeded = lastToolExtractionSucceeded,
                                    ),
                                    scopedContextResultsEmpty = scopedContextResults.isEmpty(),
                                    bestResultsEmpty = bestResults.isEmpty(),
                                    observedNonEmptyInspectionTree = effectiveObservedNonEmptyInspectionTree,
                                    stableForMs = stableForMs,
                                    pollingElapsedMs = pollingElapsedMs,
                                )
                            ) {
                                observedStableEmptyResultsWithoutInspectionView = true
                            }
                            if (
                                !observedModelCleanInspection &&
                                modelExtractionClean &&
                                viewReadyOk &&
                                (lastViewObservation?.hasProblems != true || observedStaleNonEmptyInspectionTree) &&
                                bestResults.isEmpty() &&
                                !effectiveObservedNonEmptyInspectionTree &&
                                stableForMs >= 5000L &&
                                pollingElapsedMs >= 30000L
                            ) {
                                observedModelCleanInspection = true
                            }
                            if (
                                shouldStopCapturePolling(
                                    viewReadyOk = viewReadyOk,
                                    observedInspectionView = observedInspectionView,
                                    inspectionViewUpdating = inspectionViewUpdating,
                                    observedSettledEmptyInspectionView = observedSettledEmptyInspectionView,
                                    observedStableReadableEmptyInspectionView = observedStableReadableEmptyInspectionView,
                                    observedStableEmptyResultsWithoutInspectionView = observedStableEmptyResultsWithoutInspectionView,
                                    observedModelCleanInspection = observedModelCleanInspection,
                                    bestResultsCount = bestResults.size,
                                    stableForMs = stableForMs,
                                    pollingElapsedMs = pollingElapsedMs,
                                )
                            ) {
                                captureExitReason = "settled"
                                break
                            }

                            try {
                                Thread.sleep(1000)
                            } catch (e: Exception) {
                                rethrowIfCanceled(e)
                                captureExitReason = "sleep_interrupted"
                                break
                            }
                        }

                        if (
                            !observedStaleNonEmptyInspectionTree &&
                            shouldTreatNonEmptyInspectionTreeAsStaleCleanEvidence(
                                observedNonEmptyInspectionTree = observedNonEmptyInspectionTree,
                                modelExtractionClean = modelExtractionClean,
                                modelProblemDescriptorCount = contextExtraction.problemDescriptorCount,
                                bestResultsEmpty = bestResults.isEmpty(),
                                extractionFailureCount = extractionFailureCount,
                                lastExtractionCycleSucceeded = lastExtractionCycleSucceeded,
                                lastToolExtractionSucceeded = lastToolExtractionSucceeded,
                                inspectionViewUpdating = inspectionViewUpdating,
                                stableForMs = System.currentTimeMillis() - lastChangeMs,
                                pollingElapsedMs = System.currentTimeMillis() - captureStartMs,
                            )
                        ) {
                            observedStaleNonEmptyInspectionTree = true
                        }
                        val effectiveObservedNonEmptyInspectionTree =
                            observedNonEmptyInspectionTree && !observedStaleNonEmptyInspectionTree
                        if (
                            !observedStableEmptyResultsWithoutInspectionView &&
                            shouldTrustStableScopedEmptyResults(
                                viewReadyOk = viewReadyOk,
                                hasScopedMatcher = scopeProblemMatcher != null,
                                observedInspectionView = observedInspectionView,
                                inspectionViewUpdating = inspectionViewUpdating,
                                hasSettledInspectionViewEvidence = observedSettledEmptyInspectionView ||
                                    observedStableReadableEmptyInspectionView,
                                hasOpaqueSettledEmptyInspectionViewEvidence = observedOpaqueSettledEmptyInspectionView,
                                hasTransientEmptyInspectionViewEvidence = observedTransientEmptyInspectionViewEvidence,
                                hasModelCleanEvidence = modelExtractionClean,
                                extractionSucceeded = shouldTreatScopedEmptyExtractionAsSucceeded(
                                    lastExtractionCycleSucceeded = lastExtractionCycleSucceeded,
                                    observedTransientEmptyInspectionViewEvidence = observedTransientEmptyInspectionViewEvidence,
                                    lastToolExtractionSucceeded = lastToolExtractionSucceeded,
                                ),
                                scopedContextResultsEmpty = scopedContextResults.isEmpty(),
                                bestResultsEmpty = bestResults.isEmpty(),
                                observedNonEmptyInspectionTree = effectiveObservedNonEmptyInspectionTree,
                                stableForMs = System.currentTimeMillis() - lastChangeMs,
                                pollingElapsedMs = System.currentTimeMillis() - captureStartMs,
                            )
                        ) {
                            observedStableEmptyResultsWithoutInspectionView = true
                        }
                        val finalStableForMs = System.currentTimeMillis() - lastChangeMs
                        val finalPollingElapsedMs = System.currentTimeMillis() - captureStartMs
                        if (
                            !observedModelCleanInspection &&
                            modelExtractionClean &&
                            viewReadyOk &&
                            (lastViewObservation?.hasProblems != true || observedStaleNonEmptyInspectionTree) &&
                            bestResults.isEmpty() &&
                            !effectiveObservedNonEmptyInspectionTree &&
                            finalStableForMs >= 5000L &&
                            finalPollingElapsedMs >= 30000L
                        ) {
                            observedModelCleanInspection = true
                        }

                        val captureEndState = captureStableProjectState(project)
                        val projectStateChangedDuringCapture = captureEndState != inspectionInputState
                        val snapshotState = inspectionInputState
                        val ideProductCode = safeInspectionIdentity()["ide_product_code"] as? String
                        val suspiciousEmptyModelReason = suspiciousEmptyInspectionModelReason(
                            ideProductCode = ideProductCode,
                            requestedProfileName = requestedProfileName,
                            modelVerdict = modelVerdict,
                            problemDescriptorCount = contextExtraction.problemDescriptorCount,
                            bestResultsEmpty = bestResults.isEmpty(),
                            observedNonEmptyInspectionTree = effectiveObservedNonEmptyInspectionTree,
                        )
                        val (emptyOutcome, emptyNote) = classifyEmptyInspectionCapture(
                            viewReadyOk = viewReadyOk,
                            observedInspectionView = observedInspectionView,
                            observedSettledEmptyInspectionView = observedSettledEmptyInspectionView,
                            observedStableReadableEmptyInspectionView = observedStableReadableEmptyInspectionView,
                            observedStableEmptyResultsWithoutInspectionView = observedStableEmptyResultsWithoutInspectionView,
                            observedModelCleanInspection = observedModelCleanInspection,
                            observedNonEmptyInspectionTree = effectiveObservedNonEmptyInspectionTree,
                            suspiciousEmptyModelReason = suspiciousEmptyModelReason,
                        )
                        val captureEndMs = System.currentTimeMillis()
                        val stateDiagnostic = mapOf(
                            "project_state_changed_during_capture" to projectStateChangedDuringCapture,
                            "capture_start_psi_modification_count" to inspectionInputState.psiModificationCount,
                            "capture_end_psi_modification_count" to captureEndState.psiModificationCount,
                            "capture_end_unsaved_project_documents" to captureEndState.unsavedProjectDocuments,
                        )
                        val captureDiagnostic = if (bestResults.isEmpty()) {
                            val lastObservation = lastViewObservation
                            val stableForMs = captureEndMs - lastChangeMs
                            val readableStableForMs = readableEmptyInspectionViewStableSince?.let { captureEndMs - it } ?: 0L
                            mapOf(
                                "profile_requested" to requestedProfileName,
                                "profile_resolved_name" to resolvedProfileName,
                                "profile_missing" to false,
                                "profile_list_readable" to (profileNames != null),
                                "profile_available_names" to profileNames?.take(25),
                                "profile_source" to if (requestedProfileName != null) "request" else "current",
                                "exit_reason" to (suspiciousEmptyModelReason ?: captureExitReason),
                                "ide_product_code" to ideProductCode,
                                "suspicious_empty_model" to (suspiciousEmptyModelReason != null),
                                "polling_elapsed_ms" to (captureEndMs - captureStartMs),
                                "stable_for_ms" to stableForMs,
                                "has_scoped_matcher" to (scopeProblemMatcher != null),
                                "view_ready_ok" to viewReadyOk,
                                "observed_inspection_view" to observedInspectionView,
                                "observed_settled_empty_inspection_view" to observedSettledEmptyInspectionView,
                                "observed_stable_readable_empty_inspection_view" to observedStableReadableEmptyInspectionView,
                                "observed_stable_empty_results_without_inspection_view" to observedStableEmptyResultsWithoutInspectionView,
                                "observed_opaque_settled_empty_inspection_view" to observedOpaqueSettledEmptyInspectionView,
                                "observed_transient_empty_inspection_view_evidence" to observedTransientEmptyInspectionViewEvidence,
                                "observed_model_clean_inspection" to observedModelCleanInspection,
                                "model_verdict" to modelVerdict.apiValue,
                                "model_problem_descriptor_count" to contextExtraction.problemDescriptorCount,
                                "model_enabled_tool_count" to contextExtraction.enabledToolCount,
                                "model_readable_tool_count" to contextExtraction.readableToolCount,
                                "model_unreadable_tool_count" to contextExtraction.unreadableToolCount,
                                "model_unreadable_reasons" to contextExtraction.unreadableReasons,
                                "target_tool_states" to contextExtraction.targetToolStates,
                                "observed_non_empty_inspection_tree" to effectiveObservedNonEmptyInspectionTree,
                                "observed_raw_non_empty_inspection_tree" to observedNonEmptyInspectionTree,
                                "observed_stale_non_empty_inspection_tree" to observedStaleNonEmptyInspectionTree,
                                "inspection_view_updating" to inspectionViewUpdating,
                                "inspection_view_observation_count" to inspectionViewObservationCount,
                                "readable_empty_inspection_view_observation_count" to readableEmptyInspectionViewObservationCount,
                                "transient_updating_empty_observation_count" to transientUpdatingEmptyObservationCount,
                                "unreadable_problem_state_observation_count" to unreadableProblemStateObservationCount,
                                "null_root_child_observation_count" to nullRootChildObservationCount,
                                "tool_window_observation_count" to toolWindowObservationCount,
                                "compatible_tool_window_observation_count" to compatibleToolWindowObservationCount,
                                "successful_extraction_count" to successfulExtractionCount,
                                "extraction_failure_count" to extractionFailureCount,
                                "last_extraction_cycle_succeeded" to lastExtractionCycleSucceeded,
                                "last_tool_extraction_succeeded" to lastToolExtractionSucceeded,
                                "scoped_context_results_empty" to scopedContextResults.isEmpty(),
                                "readable_empty_inspection_view_stable_for_ms" to readableStableForMs,
                                "last_view_observation" to mapOf(
                                    "is_updating" to lastObservation?.isUpdating,
                                    "has_problems" to lastObservation?.hasProblems,
                                    "root_child_count" to lastObservation?.rootChildCount,
                                    "update_state_readable" to lastObservation?.updateStateReadable,
                                    "problem_state_readable" to lastObservation?.problemStateReadable,
                                ).filterValues { it != null },
                            ) + scopeDiagnostics + stateDiagnostic
                        } else {
                            stateDiagnostic.takeIf { projectStateChangedDuringCapture }
                        }
                        val snapshot = buildInspectionCaptureSnapshot(
                            InspectionCaptureSnapshotInput(
                                bestResults = bestResults,
                                bestSource = bestSource,
                                snapshotTimeMs = System.currentTimeMillis(),
                                projectState = snapshotState,
                                emptyOutcome = emptyOutcome,
                                emptyNote = emptyNote,
                                captureScope = effectiveCaptureScope,
                                captureDiagnostic = captureDiagnostic,
                                runId = runId,
                                triggerTimeMs = inspectionRunStatesByProject[key]?.triggerTimeMs,
                                viewReadyOk = viewReadyOk,
                            )
                        )
                        if (snapshot.outcome == InspectionSnapshotOutcome.CAPTURE_INCOMPLETE && bestResults.isEmpty()) {
                            logger.info(
                                "Inspection capture incomplete for ${project.name}: " +
                                    "viewReadyOk=$viewReadyOk, " +
                                    "observedInspectionView=$observedInspectionView, " +
                                    "observedSettledEmptyInspectionView=$observedSettledEmptyInspectionView, " +
                                    "observedStableReadableEmptyInspectionView=$observedStableReadableEmptyInspectionView, " +
                                    "observedStableEmptyResultsWithoutInspectionView=$observedStableEmptyResultsWithoutInspectionView, " +
                                    "observedModelCleanInspection=$observedModelCleanInspection, " +
                                    "observedNonEmptyInspectionTree=$observedNonEmptyInspectionTree, " +
                                    "observedTransientEmptyInspectionViewEvidence=$observedTransientEmptyInspectionViewEvidence, " +
                                    "modelVerdict=${modelVerdict.apiValue}, " +
                                    "modelProblemDescriptorCount=${contextExtraction.problemDescriptorCount}, " +
                                    "modelEnabledToolCount=${contextExtraction.enabledToolCount}, " +
                                    "modelReadableToolCount=${contextExtraction.readableToolCount}, " +
                                    "modelUnreadableToolCount=${contextExtraction.unreadableToolCount}, " +
                                    "modelUnreadableReasons=${contextExtraction.unreadableReasons}, " +
                                    "inspectionViewUpdating=$inspectionViewUpdating, " +
                                    "inspectionViewObservationCount=$inspectionViewObservationCount, " +
                                    "readableEmptyInspectionViewObservationCount=$readableEmptyInspectionViewObservationCount, " +
                                    "transientUpdatingEmptyObservationCount=$transientUpdatingEmptyObservationCount, " +
                                    "unreadableProblemStateObservationCount=$unreadableProblemStateObservationCount, " +
                                    "nullRootChildObservationCount=$nullRootChildObservationCount, " +
                                    "toolWindowObservationCount=$toolWindowObservationCount, " +
                                    "compatibleToolWindowObservationCount=$compatibleToolWindowObservationCount, " +
                                    "successfulExtractionCount=$successfulExtractionCount, " +
                                    "extractionFailureCount=$extractionFailureCount, " +
                                    "lastExtractionCycleSucceeded=$lastExtractionCycleSucceeded, " +
                                    "lastToolExtractionSucceeded=$lastToolExtractionSucceeded, " +
                                    "readableEmptyInspectionViewStableForMs=${readableEmptyInspectionViewStableSince?.let { snapshot.timestamp - it } ?: 0L}, " +
                                    "lastViewObservation=${lastViewObservation?.let { "isUpdating=${it.isUpdating}, hasProblems=${it.hasProblems}, rootChildCount=${it.rootChildCount}, updateStateReadable=${it.updateStateReadable}, problemStateReadable=${it.problemStateReadable}" } ?: "null"}"
                            )
                        }
                            if (isCurrentInspectionRun(key, runId)) {
                                resultsStore.setSnapshot(key, snapshot)
                            }
                        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                            throw e
                        } catch (error: Exception) {
                            logger.warn("Inspection result capture failed for ${project.name}", error)
                            if (isCurrentInspectionRun(key, runId)) {
                                resultsStore.setSnapshot(
                                    key,
                                    InspectionResultsSnapshot(
                                        problems = emptyList(),
                                        timestamp = System.currentTimeMillis(),
                                        projectState = inspectionInputState,
                                        outcome = InspectionSnapshotOutcome.CAPTURE_INCOMPLETE,
                                        source = "inspection_view",
                                        note = "Inspection failed before results could be captured.",
                                        captureScope = effectiveCaptureScope,
                                        captureDiagnostic = captureFailureDiagnostic(error),
                                        captureIncompleteReason = CaptureIncompleteReason.HELPER_PLUGIN_ERROR,
                                        runId = runId,
                                        triggerTimeMs = inspectionRunStatesByProject[key]?.triggerTimeMs,
                                    )
                                )
                            }
                        } finally {
                            finishInspectionRun(key, runId)
                        }
                }
                captureScheduled = true
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e
            } catch (error: Exception) {
                logger.warn("Inspection setup or capture scheduling failed for ${project.name}", error)
                publishCaptureFailureIfCurrent(key, runId, project, captureScope, error)
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (error: Exception) {
            logger.warn("Inspection execution failed for ${project.name}", error)
            publishCaptureFailureIfCurrent(key, runId, project, captureScope, error)
        } finally {
            if (!captureScheduled) {
                finishInspectionRun(key, runId)
            }
        }
    }

    private fun rethrowIfCanceled(e: Exception) {
        if (e is com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        }
    }

    private fun beginInspectionRun(project: Project, captureScope: InspectionCaptureScope): InspectionRunState {
        return beginInspectionRunInternal(project, captureScope)
    }

    private fun beginInspectionRunInternal(project: Project, captureScope: InspectionCaptureScope?): InspectionRunState {
        val key = projectKey(project)
        val now = System.currentTimeMillis()
        val runState = InspectionRunState(
            runId = runIdSequence.incrementAndGet(),
            triggerTimeMs = now,
            inProgress = true,
            captureScope = captureScope,
        )
        var activeRun: InspectionRunState? = null
        inspectionRunStatesByProject.compute(key) { _, existing ->
            val existingIsActive = existing?.inProgress == true
            if (existingIsActive) {
                activeRun = existing
                existing
            } else {
                runState
            }
        }
        activeRun?.let { throw InspectionRunConflictException(it.runId) }
        return runState
    }

    @Suppress("unused")
    private fun beginInspectionRun(project: Project): InspectionRunState {
        return beginInspectionRunInternal(project, null)
    }

    private fun isInspectionInProgress(project: Project): Boolean {
        return inspectionRunStatesByProject[projectKey(project)]?.inProgress == true
    }

    private fun isCurrentInspectionRun(key: String, runId: Long): Boolean {
        return inspectionRunStatesByProject[key]?.runId == runId
    }

    private fun checkInspectionRunCancellation(key: String, runId: Long) {
        ProgressManager.checkCanceled()
        val control = inspectionRunControlsByProject[key]
        if (control?.runId == runId && control.cancellationRequested.get()) {
            throw com.intellij.openapi.progress.ProcessCanceledException()
        }
    }

    private fun finishInspectionRun(key: String, runId: Long) {
        inspectionRunStatesByProject.computeIfPresent(key) { _, state ->
            if (state.runId == runId) {
                state.copy(inProgress = false)
            } else {
                state
            }
        }
        inspectionRunControlsByProject.computeIfPresent(key) { _, control ->
            if (control.runId == runId) null else control
        }
    }

    private fun publishCaptureFailureIfCurrent(
        key: String,
        runId: Long,
        project: Project,
        captureScope: InspectionCaptureScope,
        error: Exception? = null,
    ) {
        if (!isCurrentInspectionRun(key, runId)) {
            return
        }
        resultsStore.setSnapshot(
            key,
            InspectionResultsSnapshot(
                problems = emptyList(),
                timestamp = System.currentTimeMillis(),
                projectState = captureProjectState(project),
                outcome = InspectionSnapshotOutcome.CAPTURE_INCOMPLETE,
                source = "inspection_view",
                note = "Inspection failed before results could be captured.",
                captureScope = captureScope,
                captureDiagnostic = captureFailureDiagnostic(error),
                captureIncompleteReason = CaptureIncompleteReason.HELPER_PLUGIN_ERROR,
                runId = runId,
                triggerTimeMs = inspectionRunStatesByProject[key]?.triggerTimeMs,
            )
        )
    }

    private fun captureFailureDiagnostic(error: Exception?): Map<String, Any> {
        val diagnostic = mutableMapOf<String, Any>("exit_reason" to "helper_plugin_error")
        if (error != null) {
            diagnostic["exception_type"] = error.javaClass.name
            error.message?.takeIf { it.isNotBlank() }?.let { message ->
                diagnostic["exception_message"] = message.take(1000)
            }
        }
        return diagnostic
    }

    private fun syncProjectState(project: Project) {
        val application = ApplicationManager.getApplication()
        val refreshTask = Runnable {
            FileDocumentManager.getInstance().saveAllDocuments()
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            VirtualFileManager.getInstance().syncRefresh()
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        if (application.isDispatchThread) {
            refreshTask.run()
        } else {
            application.invokeAndWait(refreshTask)
        }
    }

    private fun waitForSmartMode(project: Project) {
        try {
            DumbService.getInstance(project).waitForSmartMode()
        } catch (e: Exception) {
            rethrowIfCanceled(e)
            logger.warn("Failed while waiting for smart mode before inspection", e)
        }
    }

    private fun clearPriorInspectionResults(project: Project) {
        val application = ApplicationManager.getApplication()
        val clearTask = Runnable {
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            inspectionResultsToolWindowIds.forEach { name ->
                val toolWindow = toolWindowManager.getToolWindow(name) ?: return@forEach
                for (i in toolWindow.contentManager.contentCount - 1 downTo 0) {
                    val content = toolWindow.contentManager.getContent(i) ?: continue
                    if (containsInspectionResultsView(content.component)) {
                        toolWindow.contentManager.removeContent(content, true)
                    }
                }
            }
        }

        if (application.isDispatchThread) {
            clearTask.run()
        } else {
            application.invokeAndWait(clearTask)
        }
    }

    private fun containsInspectionResultsView(component: Component): Boolean {
        if (component is InspectionResultsView || component.javaClass.name.contains("InspectionResultsView")) {
            return true
        }

        if (component is Container) {
            for (child in component.components) {
                if (containsInspectionResultsView(child)) {
                    return true
                }
            }
        }

        return false
    }

    private fun inspectionResultsTree(view: InspectionResultsView): JTree? {
        return try {
            view.javaClass.getMethod("getTree").invoke(view) as? JTree
        } catch (_: Exception) {
            null
        }
    }

    private fun captureProjectState(project: Project): InspectionProjectStateSnapshot {
        return InspectionProjectStateSnapshot(
            psiModificationCount = PsiModificationTracker.getInstance(project).modificationCount,
            unsavedProjectDocuments = countProjectUnsavedDocuments(project)
        )
    }

    private fun captureStableProjectState(project: Project): InspectionProjectStateSnapshot {
        var previous = captureProjectState(project)
        repeat(10) {
            Thread.sleep(75)
            val current = captureProjectState(project)
            if (current == previous) {
                return current
            }
            previous = current
        }
        return previous
    }

    private fun resolveSnapshotStaleness(project: Project, snapshot: InspectionResultsSnapshot?): SnapshotStaleness {
        val snapshotState = snapshot?.projectState ?: return SnapshotStaleness(false, emptyList())
        val currentState = captureProjectState(project)
        val reasons = mutableListOf<String>()
        if (currentState.unsavedProjectDocuments > 0) {
            reasons += "unsaved_documents"
        }
        if (currentState.psiModificationCount != snapshotState.psiModificationCount) {
            reasons += "project_changed_since_inspection"
        }
        if (reasons.isEmpty()) {
            return SnapshotStaleness(false, emptyList())
        }

        val currentRunState = inspectionRunStatesByProject[projectKey(project)]
        val currentRunId = currentRunState?.runId
        val sameRunSnapshot = currentRunState?.inProgress == true &&
            snapshot.runId != null && snapshot.runId == currentRunId
        val psiOnlyChange = reasons == listOf("project_changed_since_inspection")
        if (sameRunSnapshot && psiOnlyChange) {
            return SnapshotStaleness(false, reasons, "current_run_psi_churn")
        }

        val changeKind = when {
            reasons.contains("unsaved_documents") -> "unsaved_documents"
            snapshot.runId != null && currentRunId != null && snapshot.runId != currentRunId -> "snapshot_predates_current_trigger"
            snapshot.runId == null -> "unknown_snapshot_run"
            else -> "project_changed_since_inspection"
        }
        return SnapshotStaleness(true, reasons, changeKind)
    }

    private fun countProjectUnsavedDocuments(project: Project): Int {
        val fileDocumentManager = FileDocumentManager.getInstance()
        return fileDocumentManager.unsavedDocuments.count { document ->
            val file = fileDocumentManager.getFile(document) ?: return@count false
            file.belongsToProject(project)
        }
    }

    private fun VirtualFile.belongsToProject(project: Project): Boolean {
        val projectIndex = ProjectFileIndex.getInstance(project)
        if (projectIndex.isInContent(this)) {
            return true
        }
        val basePath = project.basePath ?: return false
        return path == basePath || path.startsWith("$basePath/")
    }

    private fun buildAnalysisScope(
        project: Project,
        scopeParam: String?,
        directoryParam: String?,
        files: List<String>?,
        resolvedCurrentFile: String?,
        includeUnversioned: Boolean,
        changedFilesMode: String?,
        maxFiles: Int?,
        resolvedChangedFiles: List<VirtualFile>? = null,
    ): AnalysisScope {
        val scopeLower = scopeParam?.lowercase()?.trim().orEmpty().ifBlank { "whole_project" }

        if (scopeLower == "files") {
            if (files.isNullOrEmpty()) {
                throw BadRequestException("files", "Parameter 'files' requires at least one file path when scope=files.")
            }
            val resolved = resolveRequestedFilesStrict(project, files).toSet()
            if (resolved.size == 1) {
                psiFileForScope(project, resolved.first())?.let { return AnalysisScope(it) }
            }
            return AnalysisScope(project, resolved)
        }

        if (scopeLower == "changed_files") {
            val limited = resolvedChangedFiles ?: resolveChangedFilesScopeFiles(
                project = project,
                includeUnversioned = includeUnversioned,
                changedFilesMode = changedFilesMode,
                maxFiles = maxFiles,
            )
            if (limited.isEmpty()) {
                throw BadRequestException("scope", "scope=changed_files resolved to no files.")
            }
            if (limited.size == 1) {
                psiFileForScope(project, limited.first())?.let { return AnalysisScope(it) }
            }
            return AnalysisScope(project, limited.toSet())
        }

        if (scopeLower == "current_file") {
            val vf = resolvedCurrentFile?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                ?: throw BadRequestException(
                    "scope",
                    "scope=current_file requires a valid selected project file in the active editor.",
                )
            val psiFile = PsiManager.getInstance(project).findFile(vf)
            if (psiFile != null) {
                return AnalysisScope(psiFile)
            }
            return AnalysisScope(project, setOf(vf))
        }

        if (scopeLower == "directory") {
            val vfs = resolveRequestedDirectoryStrict(project, directoryParam)
            val psiDir = PsiManager.getInstance(project).findDirectory(vfs)
            if (psiDir != null) {
                return AnalysisScope(psiDir)
            }
            return AnalysisScope(project, setOf(vfs))
        }

        if (scopeLower != "whole_project") {
            throw BadRequestException("scope", "Unsupported inspection scope '$scopeLower'.")
        }
        return AnalysisScope(project)
    }

    private fun psiFileForScope(project: Project, file: VirtualFile): com.intellij.psi.PsiFile? {
        val app = ApplicationManager.getApplication()
        return try {
            app.runReadAction<com.intellij.psi.PsiFile?, Exception> {
                PsiManager.getInstance(project).findFile(file)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun inspectCaptureScopeFiles(
        project: Project,
        scopeParam: String?,
        directoryParam: String?,
        files: List<String>?,
        resolvedCurrentFile: String?,
        resolvedChangedFiles: List<VirtualFile>?,
    ): Map<String, Any?> {
        val scopeLower = scopeParam?.lowercase()?.trim()
        val selectedFiles = when {
            scopeLower == "files" && !files.isNullOrEmpty() -> resolveRequestedFiles(project, files)
            scopeLower == "changed_files" -> resolvedChangedFiles.orEmpty()
            scopeLower == "current_file" && !resolvedCurrentFile.isNullOrBlank() ->
                listOfNotNull(LocalFileSystem.getInstance().findFileByPath(resolvedCurrentFile))
            else -> emptyList()
        }
        val resolutionStatus = when {
            scopeLower == "files" && !files.isNullOrEmpty() && selectedFiles.isEmpty() -> "files_unresolved_project_fallback"
            scopeLower == "files" && !files.isNullOrEmpty() -> "files_resolved"
            scopeLower == "changed_files" && selectedFiles.isEmpty() -> "changed_files_empty_project_fallback"
            scopeLower == "changed_files" -> "changed_files_resolved"
            scopeLower == "current_file" && selectedFiles.isEmpty() -> "current_file_unresolved_project_fallback"
            scopeLower == "current_file" -> "current_file_resolved"
            else -> "project_scope"
        }
        val inspectedFiles = selectedFiles.take(25).map { inspectVirtualFileForDiagnostics(project, it) }
        return mapOf(
            "scope_kind" to (scopeLower ?: "whole_project"),
            "scope_resolution_status" to resolutionStatus,
            "scope_directory_requested" to directoryParam,
            "scope_file_requested_count" to (files?.size ?: 0),
            "scope_file_resolved_count" to selectedFiles.size,
            "scope_file_diagnostics" to inspectedFiles,
        ).filterValues { it != null }
    }

    private fun resolveRequestedFiles(project: Project, files: List<String>): List<VirtualFile> {
        val base = project.basePath
        val localFileSystem = runCatching { LocalFileSystem.getInstance() }.getOrNull() ?: return emptyList()
        return files.mapNotNull { p ->
            val absolute = try {
                val path = Paths.get(p)
                if (path.isAbsolute) p else if (!base.isNullOrBlank()) Paths.get(base, p).normalize().toString() else p
            } catch (_: Exception) {
                p
            }
            localFileSystem.findFileByPath(absolute)
        }.distinct()
    }

    private fun validateInspectionScopeRequest(
        project: Project,
        scopeParam: String?,
        directoryParam: String?,
        files: List<String>,
        includeUnversioned: Boolean,
        changedFilesMode: String?,
        maxFiles: Int?,
        allowLegacyProblemsScope: Boolean,
    ): InspectionCaptureScope {
        val requestedScope = scopeParam?.trim()?.lowercase().orEmpty()
        val hasDirectoryTarget = !directoryParam.isNullOrBlank()
        val hasFileTargets = files.isNotEmpty()
        val conflictingTargets = when {
            hasDirectoryTarget && hasFileTargets -> true
            requestedScope in setOf("whole_project", "current_file", "changed_files") &&
                (hasDirectoryTarget || hasFileTargets) -> true
            requestedScope == "directory" && hasFileTargets -> true
            requestedScope == "files" && hasDirectoryTarget -> true
            else -> false
        }
        if (conflictingTargets) {
            throw BadRequestException(
                "scope",
                "Scope targeting parameters conflict. Use only the directory or files arguments required by the selected scope.",
            )
        }
        val effectiveScope = when {
            requestedScope.isBlank() && hasDirectoryTarget -> "directory"
            requestedScope.isBlank() && hasFileTargets -> "files"
            requestedScope.isBlank() -> "whole_project"
            else -> requestedScope
        }
        val supportedScopes = setOf("whole_project", "current_file", "directory", "files", "changed_files")
        if (effectiveScope !in supportedScopes) {
            if (allowLegacyProblemsScope) {
                return InspectionCaptureScope(scopeParam = scopeParam)
            }
            throw BadRequestException(
                "scope",
                "Parameter 'scope' must be one of: whole_project, current_file, directory, files, changed_files.",
            )
        }

        val baseScope = InspectionCaptureScope(
            scopeParam = effectiveScope,
            directoryParam = directoryParam,
            files = files.takeIf { it.isNotEmpty() },
            includeUnversioned = includeUnversioned,
            changedFilesMode = changedFilesMode,
            maxFiles = maxFiles,
        )
        return when (effectiveScope) {
            "files" -> {
                if (files.isEmpty()) {
                    throw BadRequestException("files", "Parameter 'files' requires at least one file path when scope=files.")
                }
                val resolvedFiles = resolveRequestedFilesStrict(project, files)
                baseScope.copy(resolvedFiles = resolvedFiles.mapNotNull { normalizeFileSystemPath(it.path) })
            }
            "directory" -> {
                val resolvedDirectory = resolveRequestedDirectoryStrict(project, directoryParam)
                baseScope.copy(resolvedDirectory = normalizeFileSystemPath(resolvedDirectory.path))
            }
            "current_file" -> {
                val currentFile = resolveActiveEditorFile(project)
                    ?: throw BadRequestException(
                        "scope",
                        "scope=current_file requires a valid selected project file in the active editor.",
                    )
                baseScope.copy(
                    resolvedCurrentFile = normalizeFileSystemPath(currentFile.path),
                    resolvedFiles = listOfNotNull(normalizeFileSystemPath(currentFile.path)),
                )
            }
            "changed_files" -> {
                val changedFiles = resolveChangedFilesScopeFiles(
                    project = project,
                    includeUnversioned = includeUnversioned,
                    changedFilesMode = changedFilesMode,
                    maxFiles = maxFiles,
                )
                baseScope.copy(resolvedFiles = changedFiles.mapNotNull { normalizeFileSystemPath(it.path) })
            }
            else -> baseScope
        }
    }

    private fun resolveRequestedFilesStrict(project: Project, files: List<String>): List<VirtualFile> {
        val localFileSystem = LocalFileSystem.getInstance()
        val basePath = project.basePath
        return files.map { rawPath ->
            val absolutePath = try {
                val path = Paths.get(rawPath)
                if (path.isAbsolute) {
                    path.normalize().toString()
                } else if (!basePath.isNullOrBlank()) {
                    Paths.get(basePath, rawPath).normalize().toString()
                } else {
                    throw BadRequestException("files", "Relative file '$rawPath' requires a project base path.")
                }
            } catch (error: BadRequestException) {
                throw error
            } catch (_: Exception) {
                throw BadRequestException("files", "File path '$rawPath' is invalid.")
            }
            val file = localFileSystem.findFileByPath(absolutePath)
                ?: throw BadRequestException("files", "File '$rawPath' does not exist or is not available to the IDE.")
            if (!file.isValid || file.isDirectory || !file.isInLocalFileSystem) {
                throw BadRequestException("files", "File '$rawPath' must resolve to a valid local file.")
            }
            file
        }.distinct()
    }

    private fun resolveRequestedDirectoryStrict(project: Project, directoryParam: String?): VirtualFile {
        val rawDirectory = directoryParam?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw BadRequestException("dir", "Parameter 'dir' is required when scope=directory.")
        val absolutePath = try {
            val path = Paths.get(rawDirectory)
            if (path.isAbsolute) {
                path.normalize().toString()
            } else {
                val basePath = project.basePath
                    ?: throw BadRequestException("dir", "Relative directory '$rawDirectory' requires a project base path.")
                Paths.get(basePath, rawDirectory).normalize().toString()
            }
        } catch (error: BadRequestException) {
            throw error
        } catch (_: Exception) {
            throw BadRequestException("dir", "Directory path '$rawDirectory' is invalid.")
        }
        val directory = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            ?: throw BadRequestException("dir", "Directory '$rawDirectory' does not exist or is not available to the IDE.")
        if (!directory.isValid || !directory.isDirectory || !directory.isInLocalFileSystem) {
            throw BadRequestException("dir", "Directory '$rawDirectory' must resolve to a valid local directory.")
        }
        return directory
    }

    private fun resolveCaptureScopeEvidence(
        project: Project,
        captureScope: InspectionCaptureScope,
        resolvedChangedFiles: List<VirtualFile>? = null,
    ): InspectionCaptureScope {
        val scopeKind = captureScope.scopeParam?.trim()?.lowercase().orEmpty().ifBlank { "whole_project" }
        val resolvedFiles = when (scopeKind) {
            "files" -> resolveRequestedFiles(project, captureScope.files.orEmpty()).map { it.path }
            "changed_files" -> (resolvedChangedFiles ?: resolveChangedFilesScopeFiles(
                project = project,
                includeUnversioned = captureScope.includeUnversioned,
                changedFilesMode = captureScope.changedFilesMode,
                maxFiles = captureScope.maxFiles,
            )).map { it.path }
            "current_file" -> listOfNotNull(captureScope.resolvedCurrentFile).filter { path ->
                runCatching { LocalFileSystem.getInstance().findFileByPath(path)?.isValid == true }.getOrDefault(false)
            }
            else -> null
        }?.mapNotNull(::normalizeFileSystemPath)?.distinct()

        val resolvedDirectory = if (scopeKind == "directory") {
            val rawDirectory = captureScope.directoryParam?.trim()
            val basePath = project.basePath
            when {
                rawDirectory.isNullOrBlank() -> null
                runCatching { Paths.get(rawDirectory).isAbsolute }.getOrDefault(false) -> rawDirectory
                !basePath.isNullOrBlank() -> Paths.get(basePath, rawDirectory).normalize().toString()
                else -> rawDirectory
            }?.let(::normalizeFileSystemPath)?.takeIf { directory ->
                runCatching { LocalFileSystem.getInstance().findFileByPath(directory)?.isDirectory == true }
                    .getOrDefault(false)
            }
        } else {
            null
        }

        return captureScope.copy(
            resolvedFiles = resolvedFiles,
            resolvedDirectory = resolvedDirectory,
        )
    }

    private fun inspectVirtualFileForDiagnostics(project: Project, file: VirtualFile): Map<String, Any?> {
        val app = ApplicationManager.getApplication()
        return try {
            app.runReadAction<Map<String, Any?>, Exception> {
                val projectIndex = ProjectFileIndex.getInstance(project)
                val psiFile = PsiManager.getInstance(project).findFile(file)
                val module = ModuleUtilCore.findModuleForFile(file, project)
                mapOf(
                    "path" to file.path,
                    "valid" to file.isValid,
                    "directory" to file.isDirectory,
                    "file_type" to file.fileType.name,
                    "psi_class" to psiFile?.javaClass?.name,
                    "psi_language" to psiFile?.language?.id,
                    "module" to module?.name,
                    "in_content" to projectIndex.isInContent(file),
                    "in_source" to projectIndex.isInSourceContent(file),
                ).filterValues { it != null }
            }
        } catch (e: Exception) {
            mapOf("path" to file.path, "diagnostic_error" to formatModelUnreadableReason("file_scope", e))
        }
    }

    private fun resolveChangedFilesScopeFiles(
        project: Project,
        includeUnversioned: Boolean,
        changedFilesMode: String?,
        maxFiles: Int?,
    ): List<VirtualFile> {
        val clm = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        val changeFiles = clm.allChanges.mapNotNull { change ->
            change.virtualFile ?: change.afterRevision?.file?.virtualFile ?: change.beforeRevision?.file?.virtualFile
        }.toMutableList()

        val mode = changedFilesMode?.lowercase()?.trim()
        if (!mode.isNullOrBlank() && mode != "all") {
            val gitSets = computeGitStagingSets(project)
                ?: throw BadRequestException(
                    "changed_files_mode",
                    "changed_files_mode=$mode requires a Git worktree with readable status data.",
                )
            val (stagedSet, unstagedSet) = gitSets
            changeFiles.retainAll { file ->
                val normalizedPath = normalizeFileSystemPath(file.path)
                when (mode) {
                    "staged" -> normalizedPath != null && stagedSet.contains(normalizedPath)
                    "unstaged" -> normalizedPath != null && unstagedSet.contains(normalizedPath)
                    else -> false
                }
            }
        }
        if (includeUnversioned) {
            try {
                val method = clm.javaClass.getMethod("getUnversionedFiles")
                @Suppress("UNCHECKED_CAST")
                val unversioned = method.invoke(clm) as? Collection<VirtualFile>
                if (unversioned != null) {
                    changeFiles.addAll(unversioned)
                }
            } catch (_: Exception) {
            }
        }
        val unique = changeFiles.distinct()
        return if (maxFiles != null && maxFiles > 0) unique.take(maxFiles) else unique
    }

    private fun buildScopeProblemMatcher(
        project: Project,
        scopeParam: String?,
        directoryParam: String?,
        files: List<String>?,
        resolvedCurrentFile: String?,
        includeUnversioned: Boolean,
        changedFilesMode: String?,
        maxFiles: Int?,
        resolvedChangedFiles: List<VirtualFile>? = null,
        resolvedScope: InspectionCaptureScope? = null,
    ): ((Map<String, Any>) -> Boolean)? {
        val scopeLower = resolvedScope?.scopeParam?.lowercase()?.trim()
            ?: scopeParam?.lowercase()?.trim()

        fun normalizeProblemPath(problem: Map<String, Any>): String? {
            return normalizeFileSystemPath(problem["file"] as? String)
        }

        fun exactFileMatcher(paths: Set<String>): (Map<String, Any>) -> Boolean {
            return { problem ->
                normalizeProblemPath(problem)?.let(paths::contains) == true
            }
        }

        fun directoryMatcher(dirPath: String): (Map<String, Any>) -> Boolean {
            val normalizedDir = normalizeFileSystemPath(dirPath) ?: return { false }
            val dirPrefix = if (normalizedDir.endsWith('/')) normalizedDir else "$normalizedDir/"
            return { problem ->
                val problemPath = normalizeProblemPath(problem)
                problemPath != null && (problemPath == normalizedDir || problemPath.startsWith(dirPrefix))
            }
        }

        if (scopeLower == "files") {
            val resolvedEvidence = resolvedScope?.resolvedFiles?.mapNotNull(::normalizeFileSystemPath)?.toSet()
            if (resolvedEvidence != null) {
                return exactFileMatcher(resolvedEvidence)
            }
            if (files.isNullOrEmpty()) {
                return exactFileMatcher(emptySet())
            }
            val base = project.basePath
            val resolved = files.mapNotNull { rawPath ->
                val absolute = try {
                    val path = Paths.get(rawPath)
                    if (path.isAbsolute) rawPath else if (!base.isNullOrBlank()) Paths.get(base, rawPath).normalize().toString() else rawPath
                } catch (_: Exception) {
                    rawPath
                }
                normalizeFileSystemPath(absolute)
            }.toSet()
            return resolved.takeIf { it.isNotEmpty() }?.let(::exactFileMatcher)
        }

        if (scopeLower == "changed_files") {
            resolvedScope?.resolvedFiles?.let { paths ->
                return exactFileMatcher(paths.mapNotNull(::normalizeFileSystemPath).toSet())
            }
            val limited = resolvedChangedFiles ?: resolveChangedFilesScopeFiles(
                project = project,
                includeUnversioned = includeUnversioned,
                changedFilesMode = changedFilesMode,
                maxFiles = maxFiles,
            )
            val resolved = limited.mapNotNull { normalizeFileSystemPath(it.path) }.toSet()
            return exactFileMatcher(resolved)
        }

        if (scopeLower == "current_file") {
            val currentFile = resolvedScope?.resolvedCurrentFile ?: resolvedCurrentFile
            return exactFileMatcher(listOfNotNull(currentFile).mapNotNull(::normalizeFileSystemPath).toSet())
        }

        val dirPath = resolvedScope?.resolvedDirectory ?: directoryParam?.trim()
        if (scopeLower == "directory" || !dirPath.isNullOrBlank()) {
            val base = project.basePath
            val absolute = when {
                dirPath.isNullOrBlank() -> null
                Paths.get(dirPath).isAbsolute -> dirPath
                !base.isNullOrBlank() -> Paths.get(base, dirPath).normalize().toString()
                else -> dirPath
            }
            return absolute?.let(::directoryMatcher)
        }

        return null
    }

    private fun computeGitStagingSets(project: Project): Pair<Set<String>, Set<String>>? {
        val basePath = project.basePath ?: return null
        val gitRoot = runBoundedGitCommand(basePath, listOf("rev-parse", "--show-toplevel"))
            .toString(Charsets.UTF_8)
            .trim()
            .let(::normalizeFileSystemPath)
            ?: throw BadRequestException(
                "changed_files_mode",
                "Git did not return a valid worktree root for changed_files_mode.",
            )
        val output = runBoundedGitCommand(gitRoot, listOf("status", "--porcelain", "-z"))
            .toString(Charsets.UTF_8)
        val (stagedRelative, unstagedRelative) = parseGitStatusPorcelainZ(output)
        fun absolutePaths(relativePaths: Set<String>): Set<String> {
            return relativePaths.mapNotNull { relativePath ->
                normalizeFileSystemPath(Paths.get(gitRoot, relativePath).normalize().toString())
            }.toSet()
        }
        return absolutePaths(stagedRelative) to absolutePaths(unstagedRelative)
    }

    private fun runBoundedGitCommand(directory: String, arguments: List<String>): ByteArray {
        val process = try {
            ProcessBuilder(listOf("git") + arguments)
                .directory(File(directory))
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            throw BadRequestException(
                "changed_files_mode",
                "Unable to start Git classification for changed_files_mode.",
            )
        }
        val result = try {
            collectBoundedProcessOutput(process, timeoutMs = 2000L, maxOutputBytes = 1_048_576)
        } catch (error: InterruptedException) {
            destroyProcessTree(process)
            throw error
        } catch (_: Exception) {
            destroyProcessTree(process)
            throw BadRequestException(
                "changed_files_mode",
                "Unable to read Git classification for changed_files_mode.",
            )
        }
        if (result.timedOut) {
            throw BadRequestException(
                "changed_files_mode",
                "Git classification timed out after 2000 ms.",
            )
        }
        if (result.outputLimitExceeded) {
            throw BadRequestException(
                "changed_files_mode",
                "Git classification exceeded the 1048576-byte output limit.",
            )
        }
        if (result.exitCode != 0) {
            throw BadRequestException(
                "changed_files_mode",
                "Git classification failed with exit code ${result.exitCode}.",
            )
        }
        return result.output
    }

    private fun resolveActiveEditorFile(project: Project): VirtualFile? {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val candidates = fileEditorManager.selectedFiles.asList()
        val projectFileIndex = ProjectFileIndex.getInstance(project)
        return candidates.firstOrNull { file ->
            file.isValid && file.isInLocalFileSystem && projectFileIndex.isInContent(file)
        }
    }
    
    private fun getCurrentProject(projectName: String? = null): Project? {
        val resolvedProjectName = normalizeProjectSelector(projectName)
        if (resolvedProjectName != null) {
            return resolveProjectSelector(resolvedProjectName)
        }

        val projectFromFrame = runCatching {
            IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        }.getOrNull()
        if (isUsableProject(projectFromFrame)) {
            return projectFromFrame
        }

        val dataContext = runCatching {
            DataManager.getInstance().dataContextFromFocusAsync.blockingGet(1000, TimeUnit.MILLISECONDS)
        }.getOrNull()
        val projectFromDataContext = runCatching {
            dataContext?.let { CommonDataKeys.PROJECT.getData(it) }
        }.getOrNull()
        if (isUsableProject(projectFromDataContext)) {
            return projectFromDataContext
        }

        val validProjects = runCatching {
            ProjectManager.getInstance().openProjects.filter(::isUsableProject)
        }.getOrElse { emptyList() }
        if (validProjects.isEmpty()) {
            return null
        }

        for (project in validProjects) {
            val isActiveWindow = runCatching {
                WindowManager.getInstance().suggestParentWindow(project)?.isActive == true
            }.getOrDefault(false)
            if (isActiveWindow) {
                return project
            }
        }

        return validProjects.firstOrNull()
    }

    private fun isUsableProject(project: Project?): Boolean {
        return project != null && !project.isDefault && !project.isDisposed && project.isInitialized
    }
    
    private fun resolveProjectSelector(projectName: String): Project? {
        val projectManager = ProjectManager.getInstance()
        val openProjects = projectManager.openProjects
        val trimmed = projectName.trim()
        if (trimmed.startsWith(EXACT_PROJECT_PATH_SELECTOR_PREFIX)) {
            return resolveExactPathSelector(
                openProjects,
                trimmed.removePrefix(EXACT_PROJECT_PATH_SELECTOR_PREFIX),
                includeProjectFilePath = true,
            )
        }
        if (trimmed.startsWith(EXACT_WORKTREE_PATH_SELECTOR_PREFIX)) {
            return resolveExactPathSelector(
                openProjects,
                trimmed.removePrefix(EXACT_WORKTREE_PATH_SELECTOR_PREFIX),
                includeProjectFilePath = false,
            )
        }
        if (trimmed.contains(':') && !looksLikePath(trimmed)) {
            openProjects.firstOrNull { project ->
                isUsableProject(project) && projectInstanceId(project) == trimmed
            }?.let { return it }
        }
        val pathHint = normalizeProjectPath(trimmed)

        val matches = openProjects.filter { project ->
            !project.isDefault &&
            !project.isDisposed &&
            project.isInitialized &&
            projectMatches(project, trimmed, pathHint)
        }
        if (matches.size <= 1) {
            return matches.firstOrNull()
        }

        if (pathHint != null) {
            val scoredMatches = matches.mapNotNull { project ->
                projectPathMatchScore(project, pathHint)?.let { score -> project to score }
            }
            val bestScore = scoredMatches.maxOfOrNull { (_, score) -> score }
            val bestMatches = scoredMatches.filter { (_, score) -> score == bestScore }
            if (bestMatches.size == 1) {
                return bestMatches.single().first
            }
            throw BadRequestException(
                "project",
                "Multiple open projects matched this request. Retry with project_path or project_key.",
            )
        }

        throw BadRequestException(
            "project",
            "Multiple open projects matched this request. Retry with project_path or project_key.",
        )
    }

    private fun resolveExactPathSelector(
        openProjects: Array<Project>,
        selectorPath: String,
        includeProjectFilePath: Boolean,
    ): Project? {
        val normalizedSelector = normalizeProjectPath(selectorPath) ?: return null
        val matches = openProjects.filter { project ->
            if (!isUsableProject(project)) {
                false
            } else {
                val projectRoot = normalizeProjectPath(project.basePath)
                    ?: projectRootFromProjectFilePath(project.projectFilePath)
                projectRoot == normalizedSelector ||
                    (includeProjectFilePath && normalizeProjectPath(project.projectFilePath) == normalizedSelector)
            }
        }
        if (matches.size > 1) {
            throw BadRequestException(
                "project",
                "Multiple open projects matched this exact path. Retry with project_key.",
            )
        }
        return matches.firstOrNull()
    }

    private fun projectMatches(project: Project, projectName: String, pathHint: String?): Boolean {
        if (project.name == projectName) return true
        if (projectKey(project) == projectName) return true
        if (pathHint == null) return false

        return projectCandidatePaths(project).any { candidatePath ->
            pathMatchesProject(pathHint, candidatePath)
        }
    }

    private fun projectPathMatchScore(project: Project, selectorPath: String?): Int? {
        return bestPathMatchScore(
            selectorPath,
            projectCandidatePaths(project),
        )
    }

    private fun projectCandidatePaths(project: Project): List<String> {
        return listOfNotNull(
            normalizeProjectPath(project.basePath)
                ?: projectRootFromProjectFilePath(project.projectFilePath),
            normalizeProjectPath(project.projectFilePath),
        )
    }

    private fun bestPathMatchScore(selectorPath: String?, projectPaths: List<String>): Int? {
        val normalizedSelector = normalizeProjectPath(selectorPath) ?: return null
        return projectPaths.maxOfOrNull { projectPath ->
            pathMatchScore(normalizedSelector, projectPath)
        }?.takeIf { it > 0 }
    }

    private fun pathMatchScore(selectorPath: String, projectPath: String): Int {
        val normalizedProjectPath = normalizeProjectPath(projectPath) ?: return 0
        return runCatching {
            val selector = Paths.get(selectorPath).normalize().toAbsolutePath()
            val project = Paths.get(normalizedProjectPath).normalize().toAbsolutePath()
            when {
                selector == project -> 2_000_000 + project.toString().length
                selector.startsWith(project) -> 1_000_000 + project.toString().length
                else -> 0
            }
        }.getOrDefault(0)
    }

    private fun pathMatchesProject(selectorPath: String, projectPath: String): Boolean {
        return runCatching {
            val selector = Paths.get(selectorPath).normalize().toAbsolutePath()
            val project = Paths.get(projectPath).normalize().toAbsolutePath()
            selector == project || selector.startsWith(project)
        }.getOrDefault(false)
    }

    private fun normalizeProjectPath(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!looksLikePath(raw)) return null
        return normalizeFileSystemPath(raw)
    }

    private fun normalizeProjectSelector(projectName: String?): String? {
        return projectName?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractProjectSelector(urlDecoder: QueryStringDecoder, request: FullHttpRequest): String? {
        val queryParameter = extractProjectQueryParameterEntry(urlDecoder, request) ?: return null
        val selector = normalizeProjectSelector(queryParameter.second) ?: return null
        return when (queryParameter.first) {
            "project_path" -> EXACT_PROJECT_PATH_SELECTOR_PREFIX + selector
            "worktree_path" -> EXACT_WORKTREE_PATH_SELECTOR_PREFIX + selector
            else -> selector
        }
    }

    private fun extractProjectQueryParameter(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
    ): String? {
        return extractProjectQueryParameterEntry(urlDecoder, request)?.second
    }

    private fun extractProjectQueryParameterEntry(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
    ): Pair<String, String>? {
        for (parameterName in listOf("project_instance_id", "project_key", "project_path", "worktree_path", "cwd", "project")) {
            val parameterValues = urlDecoder.parameters()[parameterName]
            if (parameterValues != null) {
                val firstNonBlankValue = parameterValues.firstOrNull { value -> value.trim().isNotEmpty() }
                if (firstNonBlankValue != null) {
                    return parameterName to firstNonBlankValue
                }
            }
        }

        val rawQuery = request.uri().substringAfter('?', missingDelimiterValue = "")
        if (rawQuery.isEmpty()) {
            return null
        }

        for (segment in rawQuery.split('&')) {
            if (segment.isEmpty()) {
                continue
            }
            val nameAndValue = segment.split('=', limit = 2)
            val decodedName = QueryStringDecoder.decodeComponent(nameAndValue[0])
            if (decodedName !in setOf("project", "project_instance_id", "project_key", "project_path", "worktree_path", "cwd")) {
                continue
            }
            val encodedValue = nameAndValue.getOrElse(1) { "" }
            val decodedValue = QueryStringDecoder.decodeComponent(encodedValue)
            if (decodedValue.trim().isNotEmpty()) {
                return decodedName to decodedValue
            }
        }

        return null
    }

    private fun displayProjectSelector(projectName: String): String {
        return projectName
            .removePrefix(EXACT_PROJECT_PATH_SELECTOR_PREFIX)
            .removePrefix(EXACT_WORKTREE_PATH_SELECTOR_PREFIX)
    }

    @Suppress("UnstableApiUsage")
    private fun extractProblemsFromContextSafe(
        globalContext: com.intellij.codeInspection.ex.GlobalInspectionContextImpl,
        project: Project,
        targetToolShortNames: Set<String> = emptySet(),
        fallbackScopeFiles: List<com.intellij.psi.PsiFile> = emptyList(),
        cancellationCheck: () -> Unit = { ProgressManager.checkCanceled() },
    ): InspectionModelExtraction {
        cancellationCheck()
        val app = ApplicationManager.getApplication()
        val presentationHolder = AtomicReference<InspectionModelExtraction>()
        val presentationTask = Runnable {
            cancellationCheck()
            presentationHolder.set(
                app.runReadAction<InspectionModelExtraction, Exception> {
                    extractProblemsFromContext(globalContext, project, targetToolShortNames, cancellationCheck)
                }
            )
        }
        if (app.isDispatchThread) {
            presentationTask.run()
        } else {
            app.invokeAndWait(presentationTask)
        }
        cancellationCheck()
        val presentationExtraction = presentationHolder.get() ?: return InspectionModelExtraction(
            problems = emptyList(),
            problemDescriptorCount = 0,
            enabledToolCount = 0,
            readableToolCount = 0,
            unreadableToolCount = 1,
            unreadableReasons = listOf("empty_edtholder"),
        )
        if (targetToolShortNames.isEmpty() || fallbackScopeFiles.isEmpty()) {
            return presentationExtraction
        }
        if (app.isDispatchThread) {
            return presentationExtraction.copy(
                targetToolStates = markInspectionEngineSkippedOnEdt(
                    presentationExtraction.targetToolStates,
                    targetToolShortNames,
                ),
            )
        }
        return mergeInspectionEngineFallback(
            base = presentationExtraction,
            globalContext = globalContext,
            project = project,
            targetToolShortNames = targetToolShortNames,
            fallbackScopeFiles = fallbackScopeFiles,
            cancellationCheck = cancellationCheck,
        )
    }

    @Suppress("UnstableApiUsage")
    private fun extractProblemsFromContext(
        globalContext: com.intellij.codeInspection.ex.GlobalInspectionContextImpl,
        project: Project,
        targetToolShortNames: Set<String> = emptySet(),
        cancellationCheck: () -> Unit = { ProgressManager.checkCanceled() },
    ): InspectionModelExtraction {
        cancellationCheck()
        val problems = mutableListOf<Map<String, Any>>()
        val seen = LinkedHashSet<String>()
        val targetToolStates = linkedMapOf<String, MutableMap<String, Any?>>()
        var problemDescriptorCount = 0
        var enabledToolCount = 0
        var readableToolCount = 0
        var unreadableToolCount = 0
        val unreadableReasons = linkedSetOf<String>()
        val tools = try {
            globalContext.tools.values
        } catch (e: Exception) {
            return InspectionModelExtraction(
                problems = emptyList(),
                problemDescriptorCount = 0,
                enabledToolCount = 0,
                readableToolCount = 0,
                unreadableToolCount = 1,
                unreadableReasons = listOf(formatModelUnreadableReason("tools", e)),
            )
        }

        for (toolGroup in tools) {
            cancellationCheck()
            val scopeStates = try {
                toolGroup.tools
            } catch (_: Exception) {
                emptyList()
            }
            for (state in scopeStates) {
                cancellationCheck()
                val stateEnabled = try {
                    state.isEnabled
                } catch (_: Exception) {
                    false
                }
                val wrapper = try {
                    state.tool
                } catch (e: Exception) {
                    unreadableReasons.add(formatModelUnreadableReason("tool", e))
                    null
                }
                if (wrapper == null) {
                    unreadableToolCount += 1
                    continue
                }
                val shortName = try {
                    wrapper.shortName
                } catch (_: Exception) {
                    null
                }
                val tracksTarget = shortName != null && shortName in targetToolShortNames
                if (tracksTarget) {
                    targetToolStates.getOrPut(shortName) { linkedMapOf("short_name" to shortName) }.apply {
                        this["wrapper_class"] = wrapper.javaClass.name
                        this["present"] = true
                        this["enabled"] = stateEnabled
                        this["level"] = try { state.level.name } catch (_: Exception) { null }
                        this["group"] = try { wrapper.groupDisplayName } catch (_: Exception) { null }
                        this["tool_class"] = try { wrapper.tool.javaClass.name } catch (_: Exception) { null }
                    }
                }
                if (!stateEnabled) continue
                enabledToolCount += 1

                val presentation = try {
                    globalContext.getPresentation(wrapper)
                } catch (e: Exception) {
                    unreadableReasons.add(formatModelUnreadableReason("presentation", e))
                    null
                }
                if (presentation == null) {
                    unreadableToolCount += 1
                    continue
                }

                cancellationCheck()
                try {
                    presentation.updateContent()
                } catch (e: Exception) {
                    unreadableReasons.add(formatModelUnreadableReason("update_content", e))
                    unreadableToolCount += 1
                    continue
                }

                val descriptors = try {
                    val elements = presentation.problemElements
                    val values = elements.getValues()
                    if (!values.isNullOrEmpty()) values else presentation.problemDescriptors
                } catch (e: Exception) {
                    unreadableReasons.add(formatModelUnreadableReason("descriptors", e))
                    unreadableToolCount += 1
                    continue
                }
                readableToolCount += 1
                problemDescriptorCount += descriptors.size
                if (tracksTarget) {
                    targetToolStates.getOrPut(shortName) { linkedMapOf("short_name" to shortName) }.apply {
                        this["descriptor_count"] = descriptors.size
                        this["presentation_readable"] = true
                    }
                }

                for (descriptor in descriptors) {
                    cancellationCheck()
                    val map = buildProblemMap(descriptor, wrapper, project) ?: continue
                    val key = listOf(
                        map["severity"],
                        map["inspectionType"],
                        map["file"],
                        map["line"],
                        map["column"],
                        map["description"],
                    ).joinToString("|")
                    if (seen.add(key)) {
                        problems.add(map)
                    }
                }
            }
        }

        for (shortName in targetToolShortNames) {
            cancellationCheck()
            targetToolStates.getOrPut(shortName) {
                linkedMapOf(
                    "short_name" to shortName,
                    "present" to false,
                    "enabled" to false,
                    "descriptor_count" to 0,
                )
            }
        }

        return InspectionModelExtraction(
            problems = problems,
            problemDescriptorCount = problemDescriptorCount,
            enabledToolCount = enabledToolCount,
            readableToolCount = readableToolCount,
            unreadableToolCount = unreadableToolCount,
            unreadableReasons = unreadableReasons.toList(),
            targetToolStates = targetToolStates.values.map { it.toMap() },
        )
    }

    private fun mergeInspectionEngineFallback(
        base: InspectionModelExtraction,
        globalContext: com.intellij.codeInspection.ex.GlobalInspectionContextImpl,
        project: Project,
        targetToolShortNames: Set<String>,
        fallbackScopeFiles: List<com.intellij.psi.PsiFile>,
        cancellationCheck: () -> Unit = { ProgressManager.checkCanceled() },
    ): InspectionModelExtraction {
        cancellationCheck()
        val targetStates = base.targetToolStates.associate { state ->
            val mutableState = state.toMutableMap()
            mutableState["short_name"]?.toString().orEmpty() to mutableState
        }.filterKeys { it.isNotEmpty() }.toMutableMap()
        val baseKeys = base.problems.mapTo(LinkedHashSet()) { problemKey(it) }
        val fallbackProblems = mutableListOf<Map<String, Any>>()
        var fallbackDescriptorCount = 0
        for (toolShortName in targetToolShortNames) {
            cancellationCheck()
            val state = targetStates.getOrPut(toolShortName) {
                linkedMapOf("short_name" to toolShortName, "present" to false, "enabled" to false)
            }
            val fallbackDescriptors = if (state["enabled"] == true && (state["descriptor_count"] as? Int ?: 0) == 0) {
                runTargetInspectionEngineFallback(
                    globalContext = globalContext,
                    project = project,
                    toolShortName = toolShortName,
                    fallbackScopeFiles = fallbackScopeFiles,
                    targetToolState = state,
                    cancellationCheck = cancellationCheck,
                )
            } else {
                emptyList()
            }
            state["inspection_engine_descriptor_count"] = fallbackDescriptors.size
            fallbackDescriptorCount += fallbackDescriptors.size
            for ((descriptor, wrapper) in fallbackDescriptors) {
                cancellationCheck()
                val map = buildProblemMap(descriptor, wrapper, project) ?: continue
                if (baseKeys.add(problemKey(map))) {
                    fallbackProblems += map
                }
            }
        }
        if (fallbackDescriptorCount == 0) {
            return base.copy(targetToolStates = targetStates.values.map { it.toMap() })
        }
        return base.copy(
            problems = base.problems + fallbackProblems,
            problemDescriptorCount = base.problemDescriptorCount + fallbackDescriptorCount,
            targetToolStates = targetStates.values.map { it.toMap() },
        )
    }

    private fun problemKey(map: Map<String, Any>): String {
        return listOf(
            map["severity"],
            map["inspectionType"],
            map["file"],
            map["line"],
            map["column"],
            map["description"],
        ).joinToString("|")
    }

    private fun markInspectionEngineSkippedOnEdt(
        states: List<Map<String, Any?>>,
        targetToolShortNames: Set<String>,
    ): List<Map<String, Any?>> {
        val reason = "inspection_engine_skipped_on_edt"
        val mutableStates = states.associate { state ->
            val mutableState = state.toMutableMap()
            mutableState["short_name"]?.toString().orEmpty() to mutableState
        }.filterKeys { it.isNotEmpty() }.toMutableMap()
        for (shortName in targetToolShortNames) {
            val state = mutableStates.getOrPut(shortName) { linkedMapOf("short_name" to shortName) }
            state["inspection_engine_skip_reason"] = reason
            state["inspection_engine_state"] = "skipped:$reason"
        }
        return mutableStates.values.map { it.toMap() }
    }

    private fun runTargetInspectionEngineFallback(
        globalContext: com.intellij.codeInspection.ex.GlobalInspectionContextImpl,
        project: Project,
        toolShortName: String,
        fallbackScopeFiles: List<com.intellij.psi.PsiFile>,
        targetToolState: MutableMap<String, Any?>,
        cancellationCheck: () -> Unit = { ProgressManager.checkCanceled() },
    ): List<Pair<com.intellij.codeInspection.ProblemDescriptor, com.intellij.codeInspection.ex.InspectionToolWrapper<*, *>>> {
        cancellationCheck()
        val descriptors = mutableListOf<Pair<com.intellij.codeInspection.ProblemDescriptor, com.intellij.codeInspection.ex.InspectionToolWrapper<*, *>>>()
        val errors = mutableListOf<String>()
        targetToolState["inspection_engine_file_count"] = fallbackScopeFiles.size
        ApplicationManager.getApplication().runReadAction<Unit, Exception> {
            for (psiFile in fallbackScopeFiles) {
                cancellationCheck()
                try {
                    val batchWrapper = com.intellij.codeInspection.ex.LocalInspectionToolWrapper.findTool2RunInBatch(
                        project,
                        psiFile,
                        toolShortName,
                    )
                    if (batchWrapper == null) {
                        errors += "inspection_engine:${psiFile.virtualFile?.path}:missing_batch_wrapper:$toolShortName"
                        continue
                    }
                    targetToolState["inspection_engine_wrapper_class"] = batchWrapper.javaClass.name
                    descriptors += InspectionEngine.runInspectionOnFile(psiFile, batchWrapper, globalContext).map { it to batchWrapper }
                    cancellationCheck()
                } catch (e: Exception) {
                    rethrowIfCanceled(e)
                    errors += formatModelUnreadableReason("inspection_engine:${psiFile.virtualFile?.path}", e)
                }
            }
        }
        if (errors.isNotEmpty()) {
            targetToolState["inspection_engine_errors"] = errors.take(5)
        }
        return descriptors
    }

    private fun scopedPsiFilesForInspectionEngine(
        project: Project,
        scopeParam: String?,
        directoryParam: String?,
        files: List<String>?,
        resolvedCurrentFile: String?,
        resolvedChangedFiles: List<VirtualFile>?,
        targetScopeFileDiagnostics: List<*>?,
        ideProductCode: String?,
    ): List<com.intellij.psi.PsiFile> {
        val scopeLower = scopeParam?.lowercase()?.trim()
        val paths = when {
            scopeLower == "files" && !files.isNullOrEmpty() -> resolveRequestedFiles(project, files).map { it.path }
            scopeLower == "changed_files" -> resolvedChangedFiles.orEmpty().map { it.path }
            scopeLower == "current_file" && !resolvedCurrentFile.isNullOrBlank() -> listOf(resolvedCurrentFile)
            else -> targetScopeFileDiagnostics
                ?.filterIsInstance<Map<*, *>>()
                ?.mapNotNull { it["path"] as? String }
                .orEmpty()
        }
        if (paths.isNotEmpty()) {
            return ApplicationManager.getApplication().runReadAction<List<com.intellij.psi.PsiFile>, Exception> {
                paths.mapNotNull { path ->
                    LocalFileSystem.getInstance().findFileByPath(path)?.let { file ->
                        PsiManager.getInstance(project).findFile(file)
                    }
                }
            }
        }

        if (scopeLower != null && scopeLower !in setOf("whole_project", "all")) {
            return emptyList()
        }
        val files = mutableListOf<com.intellij.psi.PsiFile>()
        val sourceRoot = directoryParam
            ?.takeIf { it.isNotBlank() }
            ?.let { directory ->
                val base = project.basePath
                val absolute = try {
                    val path = Paths.get(directory)
                    if (path.isAbsolute) directory else if (!base.isNullOrBlank()) Paths.get(base, directory).normalize().toString() else directory
                } catch (_: Exception) {
                    directory
                }
                LocalFileSystem.getInstance().findFileByPath(absolute)
            }
        ApplicationManager.getApplication().runReadAction<Unit, Exception> {
            val index = ProjectFileIndex.getInstance(project)
            val psiManager = PsiManager.getInstance(project)
            val collectFile: (VirtualFile) -> Unit = { file ->
                if (!file.isDirectory && index.isInSourceContent(file) && shouldRunRedLaneFallbackOnFile(file, ideProductCode)) {
                    psiManager.findFile(file)?.let { files += it }
                }
            }
            val root = sourceRoot ?: project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            if (root != null) {
                com.intellij.openapi.vfs.VfsUtilCore.visitChildrenRecursively(
                    root,
                    object : com.intellij.openapi.vfs.VirtualFileVisitor<Any>() {
                        override fun visitFile(file: VirtualFile): Boolean {
                            collectFile(file)
                            return file.isDirectory || !shouldRunRedLaneFallbackOnFile(file, ideProductCode)
                        }
                    },
                )
            } else {
                index.iterateContent { file ->
                    collectFile(file)
                    true
                }
            }
        }
        return files
    }

    private fun shouldRunRedLaneFallbackOnFile(file: VirtualFile, ideProductCode: String?): Boolean {
        val extension = file.extension?.lowercase() ?: return false
        return when {
            ideProductCode.equals("WS", ignoreCase = true) -> extension in setOf("js", "jsx", "ts", "tsx", "json", "json5")
            isPyCharmProductCode(ideProductCode) -> extension == "py"
            else -> false
        }
    }

    private fun expectedProofToolShortNames(
        ideProductCode: String?,
        requestedProfileName: String?,
    ): Set<String> {
        if (!requestedProfileName.equals("RedLane", ignoreCase = true)) return emptySet()
        return when {
            ideProductCode.equals("WS", ignoreCase = true) ->
                linkedSetOf("JSUnresolvedReference", "JsonDuplicatePropertyKeys", "JsonStandardCompliance")
            isPyCharmProductCode(ideProductCode) ->
                linkedSetOf("PyDictDuplicateKeysInspection")
            else -> emptySet()
        }
    }

    private fun formatModelUnreadableReason(stage: String, exception: Exception): String {
        val message = exception.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(160)
        return listOfNotNull(stage, exception.javaClass.simpleName, message)
            .joinToString(":")
    }

    private fun buildProblemMap(
        descriptor: com.intellij.codeInspection.CommonProblemDescriptor,
        wrapper: com.intellij.codeInspection.ex.InspectionToolWrapper<*, *>,
        project: Project,
    ): Map<String, Any>? {
        val descriptionTemplate = descriptor.descriptionTemplate.takeIf { it.isNotBlank() } ?: return null
        val description = normalizeProblemDescription(
            descriptionTemplate,
            (descriptor as? com.intellij.codeInspection.ProblemDescriptor)?.let(::problemDescriptorRefText),
        )
        val inspectionType = wrapper.shortName
        val category = wrapper.groupDisplayName

        var filePath = "unknown"
        var line = 0
        var column = 0
        var severity = "warning"

        if (descriptor is com.intellij.codeInspection.ProblemDescriptor) {
            val location = resolveProblemLocation(descriptor, project)
            if (location != null) {
                filePath = location.filePath
                line = location.line
                column = location.column
            }
            severity = severityFromHighlightType(descriptor.highlightType)
        }

        val typeLower = inspectionType.lowercase()
        severity = when {
            typeLower.contains("grazie") -> "grammar"
            typeLower.contains("spell") || typeLower.contains("aia") -> "typo"
            else -> severity
        }

        return mapOf(
            "description" to description,
            "file" to filePath,
            "line" to line,
            "column" to column,
            "severity" to severity,
            "category" to category,
            "inspectionType" to inspectionType,
            "source" to "inspection_context",
        )
    }
    
    private fun sendJsonResponse(
        context: ChannelHandlerContext, 
        jsonContent: String, 
        status: HttpResponseStatus = HttpResponseStatus.OK,
    ) {
        val content = Unpooled.copiedBuffer(jsonContent, Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
        response.headers()[HttpHeaderNames.CONTENT_TYPE] = "application/json"
        response.headers()[HttpHeaderNames.CONTENT_LENGTH] = content.readableBytes()
        response.headers()[HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN] = "*"
        context.writeAndFlush(response)
    }
}
