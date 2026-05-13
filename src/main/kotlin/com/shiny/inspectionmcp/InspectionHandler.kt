package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.ide.DataManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
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
import com.shiny.inspectionmcp.core.normalizeProblemsScope
import com.shiny.inspectionmcp.core.paginateProblems
import com.shiny.inspectionmcp.core.scoreInspectionRouteCandidates
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.ide.HttpRequestHandler
import java.awt.Component
import java.awt.Container
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.tree.TreeNode

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

internal data class InspectionProjectStateSnapshot(
    val psiModificationCount: Long,
    val unsavedProjectDocuments: Int,
)

internal data class InspectionCaptureScope(
    val scopeParam: String? = null,
    val directoryParam: String? = null,
    val files: List<String>? = null,
    val resolvedCurrentFile: String? = null,
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
)

internal data class InspectionViewObservation(
    val isUpdating: Boolean,
    val hasProblems: Boolean,
    val rootChildCount: Int?,
    val updateStateReadable: Boolean = true,
    val problemStateReadable: Boolean = true,
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

internal class BadRequestException(
    val parameter: String,
    override val message: String,
) : RuntimeException(message)

internal fun projectKey(project: Project): String {
    val basePath = runCatching { project.basePath }.getOrNull()
    normalizeFileSystemPath(basePath)?.let { return "path:$it" }

    val projectFilePath = runCatching { project.projectFilePath }.getOrNull()
    normalizeFileSystemPath(projectFilePath)?.let { return "file:$it" }

    return "name:${project.name}"
}

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

    fun setSnapshot(projectName: String, snapshot: InspectionResultsSnapshot) {
        snapshotsByProject[projectName] = snapshot
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
    observedNonEmptyInspectionTree: Boolean,
): Pair<InspectionSnapshotOutcome, String?> {
    if (
        viewReadyOk &&
            !observedNonEmptyInspectionTree &&
            (
                (observedInspectionView && (observedSettledEmptyInspectionView || observedStableReadableEmptyInspectionView)) ||
                    observedStableEmptyResultsWithoutInspectionView
                )
    ) {
        return InspectionSnapshotOutcome.CLEAN_CONFIRMED to null
    }

    return InspectionSnapshotOutcome.CAPTURE_INCOMPLETE to
        "Inspection finished, but the plugin could not conclusively confirm that the IDE results were empty. Re-run the inspection or open the Inspection Results/Problems tool window."
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

    if ((!viewReadyOk || !observedInspectionView) && stableForMs >= minStableMs && pollingElapsedMs >= maxFallbackWaitMs) {
        return true
    }

    return false
}

internal fun shouldTrustStableScopedEmptyResults(
    viewReadyOk: Boolean,
    hasScopedMatcher: Boolean,
    scopedContextResultsEmpty: Boolean,
    bestResultsEmpty: Boolean,
    observedNonEmptyInspectionTree: Boolean,
    stableForMs: Long,
    pollingElapsedMs: Long,
    minStableMs: Long = 5000L,
    minPollingMs: Long = 30000L,
): Boolean {
    return viewReadyOk &&
        hasScopedMatcher &&
        scopedContextResultsEmpty &&
        bestResultsEmpty &&
        !observedNonEmptyInspectionTree &&
        stableForMs >= minStableMs &&
        pollingElapsedMs >= minPollingMs
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
    
    private val runIdSequence = AtomicLong()
    private val inspectionRunStatesByProject = java.util.concurrent.ConcurrentHashMap<String, InspectionRunState>()

    private val resultsStore = InspectionResultsStore

    private data class SnapshotStaleness(
        val stale: Boolean,
        val reasons: List<String>,
    )
    
    override fun isSupported(request: FullHttpRequest): Boolean {
        return request.uri().startsWith("/api/inspection") && request.method() == HttpMethod.GET
    }

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
                "/api/inspection/problems" -> {
                    responseHasSessionDrift(parameters)?.let {
                        sendJsonResponse(context, it, HttpResponseStatus.CONFLICT)
                        return true
                    }
                    val severity = parameters["severity"]?.firstOrNull() ?: "all"
                    val scope = parameters["scope"]?.firstOrNull() ?: "whole_project"
                    val problemTypeRaw = parameters["problem_type"]?.firstOrNull()
                    val filePatternRaw = parameters["file_pattern"]?.firstOrNull()
                    val problemType = normalizeOptionalFilter(problemTypeRaw)
                    val filePattern = normalizeOptionalFilter(filePatternRaw)
                    val limit = parseIntParameter(parameters, "limit", defaultValue = 100, min = 1, max = 1000)
                    val offset = parseIntParameter(parameters, "offset", defaultValue = 0, min = 0)
                    val projectName = extractProjectSelector(urlDecoder, request)
                    withCurrentProject(context, projectName, refreshProjectState = true) { project ->
                        val result = ApplicationManager.getApplication().runReadAction<String, Exception> {
                            getInspectionProblems(project, severity, scope, problemType, filePattern, limit, offset)
                        }
                        sendJsonResponse(context, result)
                    }
                }
                "/api/inspection/trigger" -> {
                    responseHasSessionDrift(parameters)?.let {
                        sendJsonResponse(context, it, HttpResponseStatus.CONFLICT)
                        return true
                    }
                    val scope = parameters["scope"]?.firstOrNull()
                    // Accept either `dir`, `directory`, or `path` for directory scoping
                    val directory = parameters["dir"]?.firstOrNull()
                        ?: parameters["directory"]?.firstOrNull()
                        ?: parameters["path"]?.firstOrNull()
                    val filesList = mutableListOf<String>()
                    val repeatedFiles = parameters["file"] ?: emptyList()
                    if (repeatedFiles.isNotEmpty()) filesList.addAll(repeatedFiles)
                    val filesParam = parameters["files"]?.firstOrNull()
                    if (!filesParam.isNullOrBlank()) {
                        filesList.addAll(filesParam.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() })
                    }
                    val includeUnversioned = parameters["include_unversioned"]?.firstOrNull()?.equals("true", ignoreCase = true) ?: true
                    val changedFilesMode = parameters["changed_files_mode"]?.firstOrNull()?.lowercase()?.trim()
                    val maxFiles = parseOptionalIntParameter(parameters, "max_files", min = 1)
                    val profile = parameters["profile"]?.firstOrNull()
                    val projectName = extractProjectSelector(urlDecoder, request)
                    withCurrentProject(context, projectName) { project ->
                        val runState = beginInspectionRun(project)
                        try {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                triggerInspectionAsync(
                                    project = project,
                                    runId = runState.runId,
                                    scopeParam = scope,
                                    directoryParam = directory,
                                    files = if (filesList.isEmpty()) null else filesList,
                                    includeUnversioned = includeUnversioned,
                                    changedFilesMode = changedFilesMode,
                                    maxFiles = maxFiles,
                                    profileName = profile
                                )
                            }
                        } catch (e: Exception) {
                            finishInspectionRun(projectKey(project), runState.runId)
                            throw e
                        }
                        val details = mutableMapOf<String, Any>(
                            "status" to "triggered",
                            "message" to "Inspection triggered. Wait 10-15 seconds then check status"
                        )
                        details["run_id"] = runState.runId
                        details["session_id"] = InspectionIdeSession.sessionId
                        details["project_key"] = projectKey(project)
                        details["route"] = routeMetadata(project)
                        if (!scope.isNullOrBlank()) details["scope"] = scope
                        if (!directory.isNullOrBlank()) details["directory"] = directory
                        if (filesList.isNotEmpty()) details["files_requested"] = filesList.size
                        details["include_unversioned"] = includeUnversioned
                        if (!changedFilesMode.isNullOrBlank()) details["changed_files_mode"] = changedFilesMode
                        if (maxFiles != null) details["max_files"] = maxFiles
                        if (!profile.isNullOrBlank()) details["profile"] = profile
                        sendJsonResponse(context, formatJsonManually(details))
                    }
                }
                "/api/inspection/status" -> {
                    responseHasSessionDrift(parameters)?.let {
                        sendJsonResponse(context, it, HttpResponseStatus.CONFLICT)
                        return true
                    }
                    val projectName = extractProjectSelector(urlDecoder, request)
                    withCurrentProject(context, projectName, refreshProjectState = true) { project ->
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
                    val result = waitForInspection(projectName, timeoutMs, pollMs)
                    sendJsonResponse(context, result)
                }
                else -> {
                    sendJsonResponse(context, """{"error": "Unknown endpoint"}""", HttpResponseStatus.NOT_FOUND)
                }
            }
            return true
        } catch (error: BadRequestException) {
            sendJsonResponse(context, formatBadRequest(error), HttpResponseStatus.BAD_REQUEST)
            return true
        } catch (_: Exception) {
            sendJsonResponse(context, """{"error": "Internal server error"}""", HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return true
        }
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
        return formatJsonManually(
            mapOf(
                "error" to "IDE session changed",
                "session_drift" to true,
                "expected_session_id" to expectedSessionId,
                "session_id" to InspectionIdeSession.sessionId,
                "started_at_ms" to InspectionIdeSession.startedAtMs,
                "message" to "The JetBrains IDE session changed. Resolve the route again and re-trigger inspection before trusting cached results.",
            )
        )
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

    private fun resolveInspectionRoute(parameters: Map<String, List<String>>): ResolvedInspectionRoute? {
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
                "Multiple open projects matched this request. Retry with project_path or project_key.",
            )
        }
        val projectKey = selected.project.projectKey ?: return null
        val project = getCurrentProject(projectKey) ?: return null
        val projectIdentity = openProjectIdentity(project)
        return ResolvedInspectionRoute(project, identity, projectIdentity, selected.score)
    }

    private fun routePathMatchScore(project: InspectionRouteProject, selectorPath: String?): Int? {
        return bestPathMatchScore(
            selectorPath,
            listOfNotNull(project.basePath, project.projectFilePath),
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
                "open_projects" to runCatching { openProjectIdentities() }.getOrDefault(emptyList()),
            )
        }
    }

    private fun routeMetadata(resolved: ResolvedInspectionRoute): Map<String, Any?> {
        return mapOf(
            "port" to resolved.identity["port"],
            "base_url" to resolved.identity["port"]?.let { port -> "http://localhost:$port/api/inspection" },
            "session_id" to resolved.identity["session_id"],
            "started_at_ms" to resolved.identity["started_at_ms"],
            "heartbeat_ms" to resolved.identity["heartbeat_ms"],
            "project_key" to resolved.projectIdentity["project_key"],
            "project_name" to resolved.projectIdentity["name"],
            "base_path" to resolved.projectIdentity["base_path"],
            "project_file_path" to resolved.projectIdentity["project_file_path"],
            "focused" to resolved.projectIdentity["focused"],
            "ide" to ideRouteMetadata(resolved.identity),
            "score" to resolved.score,
        )
    }

    private fun ideRouteMetadata(identity: Map<String, Any?>): Map<String, Any?> {
        return mapOf(
            "name" to identity["ide_name"],
            "version" to identity["ide_version"],
            "product_code" to identity["ide_product_code"],
            "pid" to identity["pid"],
            "plugin_version" to identity["plugin_version"],
        )
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
            projects = ((this["open_projects"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()
                ?: openProjectIdentities()).map { identity ->
                InspectionRouteProject(
                    projectKey = identity["project_key"] as? String,
                    name = identity["name"] as? String,
                    basePath = identity["base_path"] as? String,
                    projectFilePath = identity["project_file_path"] as? String,
                    focused = identity["focused"] as? Boolean ?: false,
                )
            },
        )
    }
    
    private fun getInspectionProblems(
        project: Project,
        severity: String = "all",
        scope: String = "whole_project",
        problemType: String? = null,
        filePattern: String? = null,
        limit: Int = 100,
        offset: Int = 0
    ): String {
        return try {
            val normalizedScope = normalizeProblemsScope(scope)
            val key = projectKey(project)
            var snapshot = resultsStore.getSnapshot(key)
            val hasSnapshot = snapshot != null
            val staleness = getSnapshotStaleness(project)
            if (hasSnapshot && staleness.stale) {
                val response = mapOf(
                    "status" to "stale_results",
                    "project" to project.name,
                    "project_key" to key,
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(project),
                    "timestamp" to (resultsStore.getTimestamp(key) ?: System.currentTimeMillis()),
                    "message" to "Project files changed since the last inspection. Trigger a new inspection before trusting these results.",
                    "results_may_be_stale" to true,
                    "stale_reasons" to staleness.reasons,
                    "filters" to mapOf(
                        "severity" to severity,
                        "scope" to normalizedScope,
                        "problem_type" to (problemType ?: "all"),
                        "file_pattern" to (filePattern ?: "all")
                    )
                )
                return formatJsonManually(response)
            }
            snapshot = reconcileSnapshotWithLiveProblems(project, snapshot)
            if (snapshot != null && snapshot.outcome == InspectionSnapshotOutcome.CAPTURE_INCOMPLETE) {
                val response = mutableMapOf(
                    "status" to "capture_incomplete",
                    "project" to project.name,
                    "project_key" to key,
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(project),
                    "timestamp" to snapshot.timestamp,
                    "message" to (
                        snapshot.note
                            ?: "Inspection finished, but the plugin could not conclusively capture the IDE results. Re-run the inspection or open the Inspection Results tool window."
                        ),
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
                        "scope" to normalizedScope,
                        "problem_type" to (problemType ?: "all"),
                        "file_pattern" to (filePattern ?: "all")
                    ),
                    "method" to snapshot.source,
                    "snapshot_outcome" to snapshot.outcome.apiValue,
                )
                return formatJsonManually(response)
            }
            val problems = if (snapshot?.problems != null) {
                snapshot.problems
            } else {
                val extractor = enhancedTreeExtractorFactory()
                extractor.extractAllProblems(project)
            }
            
            if (problems.isNotEmpty() || hasSnapshot) {
                val currentFilePath = if (normalizedScope == "current_file") {
                    try {
                        FileEditorManager
                            .getInstance(project)
                            .selectedFiles
                            .firstOrNull()
                            ?.path
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                val filteredProblems = filterProblems(
                    problems = problems,
                    severity = severity,
                    scope = normalizedScope,
                    currentFilePath = currentFilePath,
                    problemType = problemType,
                    filePattern = filePattern
                )

                val page = paginateProblems(filteredProblems, limit, offset)
                
                val response = mapOf(
                    "status" to "results_available",
                    "project" to project.name,
                    "project_key" to key,
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(project),
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
                        "scope" to normalizedScope,
                        "problem_type" to (problemType ?: "all"),
                        "file_pattern" to (filePattern ?: "all")
                    ),
                    "method" to (snapshot?.source ?: "enhanced_tree")
                )
                
                formatJsonManually(response)
            } else {
                val response = mapOf(
                    "status" to "no_results",
                    "project" to project.name,
                    "project_key" to key,
                    "session_id" to InspectionIdeSession.sessionId,
                    "route" to routeMetadata(project),
                    "timestamp" to System.currentTimeMillis(),
                    "message" to "No inspection results found. Either run an inspection first, or the last inspection found no problems (100% pass).",
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
                        "scope" to normalizedScope,
                        "problem_type" to (problemType ?: "all"),
                        "file_pattern" to (filePattern ?: "all"),
                    ),
                    "method" to "enhanced_tree",
                )
                formatJsonManually(response)
            }
        } catch (_: Exception) {
            """{"error": "Failed to get inspection problems"}"""
        }
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
        return try {
            val status = buildInspectionStatus(project)
            formatJsonManually(status)
        } catch (_: Exception) {
            """{"error": "Failed to get status"}"""
        }
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
        val staleness = getSnapshotStaleness(project)
        if (!staleness.stale) {
            snapshot = reconcileSnapshotWithLiveProblems(project, snapshot)
        }
        val extractor = enhancedTreeExtractorFactory()
        val problemsSnapshot = if (hasInspectionSnapshot) {
            snapshot?.problems ?: emptyList()
        } else {
            try {
                extractor.extractAllProblems(project)
            } catch (_: Exception) { emptyList() }
        }
        val captureIncomplete = snapshot?.outcome == InspectionSnapshotOutcome.CAPTURE_INCOMPLETE && !staleness.stale
        val resultsAvailable = if (hasInspectionSnapshot) {
            snapshot?.outcome != InspectionSnapshotOutcome.CAPTURE_INCOMPLETE && !staleness.stale
        } else {
            problemsSnapshot.isNotEmpty()
        }
        status["total_problems"] = problemsSnapshot.size
        status["results_source"] = snapshot?.source ?: "tool_window"
        status["results_may_be_stale"] = hasInspectionSnapshot && staleness.stale
        if (snapshot != null) {
            status["results_timestamp_ms"] = snapshot.timestamp
            status["snapshot_outcome"] = snapshot.outcome.apiValue
            if (!snapshot.note.isNullOrBlank()) {
                status["snapshot_note"] = snapshot.note
            }
        }
        if (staleness.reasons.isNotEmpty()) {
            status["stale_reasons"] = staleness.reasons
        }

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
        val isLikelyStillRunning = inspectionInProgress && timeSinceLastTrigger < 300000

        status["is_scanning"] = isIndexing || isLikelyStillRunning
        status["has_inspection_results"] = resultsAvailable
        status["capture_incomplete"] = captureIncomplete
        status["inspection_in_progress"] = inspectionInProgress
        status["time_since_last_trigger_ms"] = timeSinceLastTrigger
        if (runState != null) {
            status["inspection_run_id"] = runState.runId
        }
        if (snapshot != null) {
            status["results_age_ms"] = (currentTime - snapshot.timestamp).coerceAtLeast(0L)
        }
        status["indexing"] = isIndexing

        // Clear indicator for a clean inspection (finished, confirmed, and not stale)
        val cleanInspection = (
            !isLikelyStillRunning &&
                !inspectionInProgress &&
                snapshot?.outcome == InspectionSnapshotOutcome.CLEAN_CONFIRMED &&
                !staleness.stale
            )
        status["clean_inspection"] = cleanInspection

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

        val liveProblems = try {
            enhancedTreeExtractorFactory().extractAllProblems(project)
        } catch (_: Exception) {
            emptyList()
        }

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
            )
        }
        val compatibleLiveProblems = filterProblemsForScope(liveProblems, captureScopeMatcher)

        if (compatibleLiveProblems.isEmpty()) {
            return snapshot
        }

        val reconciledSnapshot = snapshot.copy(
            problems = compatibleLiveProblems,
            timestamp = System.currentTimeMillis(),
            projectState = captureProjectState(project),
            outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
            source = "tool_window",
            note = null,
        )
        resultsStore.setSnapshot(projectKey(project), reconciledSnapshot)
        return reconciledSnapshot
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
                }
                if (noResultsWaitHasSettled(now, noResultsStableSince, timeSinceTrigger, minStableMs)) {
                    status["wait_note"] = "Inspection finished but no results were captured. This can happen for clean runs or when the Inspection Results view was unavailable. Re-run the inspection or open the Inspection Results tool window if findings were expected."
                    return formatWaitResponse(status, start, timeoutMs, pollMs, true, "no_results")
                }
            } else {
                noResultsStableSince = null
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
                    timeSinceTrigger >= 15000
                ) {
                    status["wait_note"] = "Inspection finished but no results were captured. This can happen for clean runs or when the Inspection Results view was unavailable. Re-run the inspection or open the Inspection Results tool window if findings were expected."
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
        val elapsed = System.currentTimeMillis() - startMs
        response["wait_completed"] = completed
        response["timed_out"] = !completed && reason == "timeout"
        response["completion_reason"] = reason
        response["wait_ms"] = elapsed
        response["timeout_ms"] = timeoutMs
        response["poll_ms"] = pollMs
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
        return formatJsonManually(response)
    }

    private fun buildMissingProjectResponse(projectName: String?): MutableMap<String, Any> {
        val response = mutableMapOf<String, Any>()
        response["session_id"] = InspectionIdeSession.sessionId
        if (projectName == null) {
            response["error"] = "No project found"
            return response
        }

        response["error"] = "Requested project '$projectName' is not open in the IDE."

        val openProjectNames = ProjectManager.getInstance().openProjects
            .filter { project -> !project.isDefault && !project.isDisposed && project.isInitialized }
            .map { project -> project.name }
            .distinct()
            .sorted()

        if (openProjectNames.isNotEmpty()) {
            response["open_projects"] = openProjectNames.take(5)
            val suggestedOpenProjects = suggestOpenProjects(projectName, openProjectNames)
            if (suggestedOpenProjects.isNotEmpty()) {
                response["suggested_open_projects"] = suggestedOpenProjects
            }
        }

        val suggestedRecentProjects = suggestRecentProjects(projectName, openProjectNames)
        if (suggestedRecentProjects.isNotEmpty()) {
            response["suggested_recent_projects"] = suggestedRecentProjects.map { suggestion ->
                mapOf(
                    "name" to suggestion.name,
                    "path" to suggestion.path,
                )
            }
        }

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
        scopeParam: String? = null,
        directoryParam: String? = null,
        files: List<String>? = null,
        includeUnversioned: Boolean = true,
        changedFilesMode: String? = null,
        maxFiles: Int? = null,
        profileName: String? = null,
    ) {
        val key = projectKey(project)
        val requestedCurrentFileScope = scopeParam?.lowercase()?.trim() == "current_file"
        val resolvedCurrentFile = if (requestedCurrentFileScope) {
            resolveActiveEditorFile(project)?.path?.let(::normalizeFileSystemPath)
        } else {
            null
        }
        val captureScopeParam = if (requestedCurrentFileScope && resolvedCurrentFile == null) {
            null
        } else {
            scopeParam
        }
        val captureScope = InspectionCaptureScope(
            scopeParam = captureScopeParam,
            directoryParam = directoryParam,
            files = files,
            resolvedCurrentFile = resolvedCurrentFile,
            includeUnversioned = includeUnversioned,
            changedFilesMode = changedFilesMode,
            maxFiles = maxFiles,
        )
        var captureScheduled = false
        try {
            if (!isCurrentInspectionRun(key, runId)) {
                return
            }
            resultsStore.clear(key)

            clearPriorInspectionResults(project)
            
            syncProjectState(project)

            val scope: AnalysisScope = buildAnalysisScope(
                project = project,
                scopeParam = scopeParam,
                directoryParam = directoryParam,
                files = files,
                resolvedCurrentFile = resolvedCurrentFile,
                includeUnversioned = includeUnversioned,
                changedFilesMode = changedFilesMode,
                maxFiles = maxFiles
            )
            val scopeProblemMatcher = buildScopeProblemMatcher(
                project = project,
                scopeParam = scopeParam,
                directoryParam = directoryParam,
                files = files,
                resolvedCurrentFile = resolvedCurrentFile,
                includeUnversioned = includeUnversioned,
                changedFilesMode = changedFilesMode,
                maxFiles = maxFiles,
            )
            
            @Suppress("USELESS_CAST")
            val inspectionManager = InspectionManager.getInstance(project) as com.intellij.codeInspection.ex.InspectionManagerEx
            val profileManager = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project)
            val profile = if (!profileName.isNullOrBlank()) {
                profileManager.getProfile(profileName)
            } else {
                profileManager.currentProfile
            }
            
            @Suppress("UnstableApiUsage", "USELESS_CAST")
            val globalContext = inspectionManager.createNewGlobalContext() as com.intellij.codeInspection.ex.GlobalInspectionContextImpl
            globalContext.setExternalProfile(profile)
            globalContext.currentScope = scope
            
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    @Suppress("UnstableApiUsage")
                    globalContext.doInspections(scope)
                },
                "Running Code Inspection",
                true,
                project
            )

            try {
                @Suppress("UnstableApiUsage")
                val viewReady = globalContext.initializeViewIfNeeded()
                val initialView = try {
                    @Suppress("UnstableApiUsage")
                    globalContext.view
                } catch (_: Exception) {
                    null
                }
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        val extractor = enhancedTreeExtractorFactory()
                        val extractedFromContext = try {
                            ApplicationManager.getApplication().runReadAction<List<Map<String, Any>>, Exception> {
                                extractProblemsFromContext(globalContext, project)
                            }
                        } catch (e: Exception) {
                            rethrowIfCanceled(e)
                            emptyList()
                        }
                        val scopedContextResults = filterProblemsForScope(extractedFromContext, scopeProblemMatcher)

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
                        var observedNonEmptyInspectionTree = false
                        var inspectionViewUpdating = false
                        var lastViewObservation: InspectionViewObservation? = null
                        var readableEmptyInspectionViewStableSince: Long? = null
                        var inspectionViewObservationCount = 0
                        var readableEmptyInspectionViewObservationCount = 0
                        var unreadableProblemStateObservationCount = 0
                        var nullRootChildObservationCount = 0
                        var toolWindowObservationCount = 0
                        var compatibleToolWindowObservationCount = 0

                        fun extractFromViewSafe(view: InspectionResultsView): List<Map<String, Any>> {
                            val app = ApplicationManager.getApplication()
                            if (app.isDispatchThread) {
                                return extractor.extractAllProblemsFromInspectionView(view, project)
                            }
                            val holder = AtomicReference<List<Map<String, Any>>>()
                            app.invokeAndWait {
                                holder.set(extractor.extractAllProblemsFromInspectionView(view, project))
                            }
                            return holder.get() ?: emptyList()
                        }

                        fun inspectViewStateSafe(view: InspectionResultsView): InspectionViewObservation {
                            val app = ApplicationManager.getApplication()

                            fun inspectViewState(): InspectionViewObservation {
                                val rootChildCount = try {
                                    readInspectionRootChildCount(view.tree.model.root)
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

                        var viewReadyOk = false

                        while (System.currentTimeMillis() < deadlineMs) {
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
                                        readableEmptyInspectionViewStableSince = null
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
                                    else -> {
                                        readableEmptyInspectionViewStableSince = null
                                    }
                                }
                                val attempt = try {
                                    extractFromViewSafe(view)
                                } catch (e: Exception) {
                                    rethrowIfCanceled(e)
                                    emptyList()
                                }
                                val scopedAttempt = filterProblemsForScope(attempt, scopeProblemMatcher)
                                if (scopedAttempt.size > bestResults.size) {
                                    bestResults = scopedAttempt
                                    bestSource = "inspection_view"
                                }
                            }

                            val toolResults = try {
                                ApplicationManager.getApplication().runReadAction<List<Map<String, Any>>, Exception> {
                                    extractor.extractAllProblems(project)
                                }
                            } catch (e: Exception) {
                                rethrowIfCanceled(e)
                                emptyList()
                            }
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
                            if (trustedToolResults.size > bestResults.size) {
                                bestResults = trustedToolResults
                                bestSource = "tool_window"
                            }

                            if (bestResults.size != lastSize) {
                                lastSize = bestResults.size
                                lastChangeMs = loopNow
                            }

                            val stableForMs = loopNow - lastChangeMs
                            val pollingElapsedMs = loopNow - captureStartMs
                            val hasUsableViewEvidence = hasUsableInspectionViewEvidence(
                                inspectionViewObservationCount = inspectionViewObservationCount,
                                nullRootChildObservationCount = nullRootChildObservationCount,
                                observedSettledEmptyInspectionView = observedSettledEmptyInspectionView,
                                observedStableReadableEmptyInspectionView = observedStableReadableEmptyInspectionView,
                                observedNonEmptyInspectionTree = observedNonEmptyInspectionTree,
                            )
                            if (
                                !observedStableReadableEmptyInspectionView &&
                                readableEmptyInspectionViewStableSince != null &&
                                loopNow - readableEmptyInspectionViewStableSince >= 5000L &&
                                pollingElapsedMs >= 30000L
                            ) {
                                observedStableReadableEmptyInspectionView = true
                            }
                            if (
                                !observedStableEmptyResultsWithoutInspectionView &&
                                shouldTrustStableScopedEmptyResults(
                                    viewReadyOk = viewReadyOk,
                                    hasScopedMatcher = scopeProblemMatcher != null,
                                    scopedContextResultsEmpty = scopedContextResults.isEmpty(),
                                    bestResultsEmpty = bestResults.isEmpty(),
                                    observedNonEmptyInspectionTree = observedNonEmptyInspectionTree,
                                    stableForMs = stableForMs,
                                    pollingElapsedMs = pollingElapsedMs,
                                )
                            ) {
                                observedStableEmptyResultsWithoutInspectionView = true
                            }
                            if (
                                shouldStopCapturePolling(
                                    viewReadyOk = viewReadyOk,
                                    observedInspectionView = observedInspectionView,
                                    inspectionViewUpdating = inspectionViewUpdating,
                                    observedSettledEmptyInspectionView = observedSettledEmptyInspectionView,
                                    observedStableReadableEmptyInspectionView = observedStableReadableEmptyInspectionView,
                                    observedStableEmptyResultsWithoutInspectionView = observedStableEmptyResultsWithoutInspectionView,
                                    bestResultsCount = bestResults.size,
                                    stableForMs = stableForMs,
                                    pollingElapsedMs = pollingElapsedMs,
                                )
                            ) {
                                break
                            }

                            try {
                                Thread.sleep(1000)
                            } catch (e: Exception) {
                                rethrowIfCanceled(e)
                                break
                            }
                        }

                        if (
                            !observedStableEmptyResultsWithoutInspectionView &&
                            shouldTrustStableScopedEmptyResults(
                                viewReadyOk = viewReadyOk,
                                hasScopedMatcher = scopeProblemMatcher != null,
                                scopedContextResultsEmpty = scopedContextResults.isEmpty(),
                                bestResultsEmpty = bestResults.isEmpty(),
                                observedNonEmptyInspectionTree = observedNonEmptyInspectionTree,
                                stableForMs = System.currentTimeMillis() - lastChangeMs,
                                pollingElapsedMs = System.currentTimeMillis() - captureStartMs,
                            )
                        ) {
                            observedStableEmptyResultsWithoutInspectionView = true
                        }

                        syncProjectState(project)
                        val snapshotState = captureProjectState(project)
                        val (emptyOutcome, emptyNote) = classifyEmptyInspectionCapture(
                            viewReadyOk = viewReadyOk,
                            observedInspectionView = observedInspectionView,
                            observedSettledEmptyInspectionView = observedSettledEmptyInspectionView,
                            observedStableReadableEmptyInspectionView = observedStableReadableEmptyInspectionView,
                            observedStableEmptyResultsWithoutInspectionView = observedStableEmptyResultsWithoutInspectionView,
                            observedNonEmptyInspectionTree = observedNonEmptyInspectionTree,
                        )
                        val snapshot = when {
                            bestResults.isNotEmpty() -> InspectionResultsSnapshot(
                                problems = bestResults,
                                timestamp = System.currentTimeMillis(),
                                projectState = snapshotState,
                                outcome = InspectionSnapshotOutcome.PROBLEMS_FOUND,
                                source = bestSource,
                                captureScope = captureScope,
                            )
                            emptyOutcome == InspectionSnapshotOutcome.CLEAN_CONFIRMED -> InspectionResultsSnapshot(
                                problems = emptyList(),
                                timestamp = System.currentTimeMillis(),
                                projectState = snapshotState,
                                outcome = InspectionSnapshotOutcome.CLEAN_CONFIRMED,
                                source = "inspection_view",
                                captureScope = captureScope,
                            )
                            else -> InspectionResultsSnapshot(
                                problems = emptyList(),
                                timestamp = System.currentTimeMillis(),
                                projectState = snapshotState,
                                outcome = InspectionSnapshotOutcome.CAPTURE_INCOMPLETE,
                                source = if (viewReadyOk) "inspection_view" else "tool_window",
                                note = emptyNote,
                                captureScope = captureScope,
                            )
                        }
                        if (snapshot.outcome == InspectionSnapshotOutcome.CAPTURE_INCOMPLETE && bestResults.isEmpty()) {
                            logger.info(
                                "Inspection capture incomplete for ${project.name}: " +
                                    "viewReadyOk=$viewReadyOk, " +
                                    "observedInspectionView=$observedInspectionView, " +
                                    "observedSettledEmptyInspectionView=$observedSettledEmptyInspectionView, " +
                                    "observedStableReadableEmptyInspectionView=$observedStableReadableEmptyInspectionView, " +
                                    "observedStableEmptyResultsWithoutInspectionView=$observedStableEmptyResultsWithoutInspectionView, " +
                                    "observedNonEmptyInspectionTree=$observedNonEmptyInspectionTree, " +
                                    "inspectionViewUpdating=$inspectionViewUpdating, " +
                                    "inspectionViewObservationCount=$inspectionViewObservationCount, " +
                                    "readableEmptyInspectionViewObservationCount=$readableEmptyInspectionViewObservationCount, " +
                                    "unreadableProblemStateObservationCount=$unreadableProblemStateObservationCount, " +
                                    "nullRootChildObservationCount=$nullRootChildObservationCount, " +
                                    "toolWindowObservationCount=$toolWindowObservationCount, " +
                                    "compatibleToolWindowObservationCount=$compatibleToolWindowObservationCount, " +
                                    "readableEmptyInspectionViewStableForMs=${readableEmptyInspectionViewStableSince?.let { snapshot.timestamp - it } ?: 0L}, " +
                                    "lastViewObservation=${lastViewObservation?.let { "isUpdating=${it.isUpdating}, hasProblems=${it.hasProblems}, rootChildCount=${it.rootChildCount}, updateStateReadable=${it.updateStateReadable}, problemStateReadable=${it.problemStateReadable}" } ?: "null"}"
                            )
                        }
                            if (isCurrentInspectionRun(key, runId)) {
                                resultsStore.setSnapshot(key, snapshot)
                            }
                        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                            throw e
                        } catch (_: Exception) {
                            if (isCurrentInspectionRun(key, runId)) {
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
            } catch (_: Exception) {
                publishCaptureFailureIfCurrent(key, runId, project, captureScope)
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            publishCaptureFailureIfCurrent(key, runId, project, captureScope)
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

    private fun beginInspectionRun(project: Project): InspectionRunState {
        val runState = InspectionRunState(
            runId = runIdSequence.incrementAndGet(),
            triggerTimeMs = System.currentTimeMillis(),
            inProgress = true,
        )
        inspectionRunStatesByProject[projectKey(project)] = runState
        return runState
    }

    private fun isInspectionInProgress(project: Project): Boolean {
        return inspectionRunStatesByProject[projectKey(project)]?.inProgress == true
    }

    private fun isCurrentInspectionRun(key: String, runId: Long): Boolean {
        return inspectionRunStatesByProject[key]?.runId == runId
    }

    private fun finishInspectionRun(key: String, runId: Long) {
        inspectionRunStatesByProject.computeIfPresent(key) { _, state ->
            if (state.runId == runId) {
                state.copy(inProgress = false)
            } else {
                state
            }
        }
    }

    private fun publishCaptureFailureIfCurrent(
        key: String,
        runId: Long,
        project: Project,
        captureScope: InspectionCaptureScope,
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
            )
        )
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

    private fun captureProjectState(project: Project): InspectionProjectStateSnapshot {
        return InspectionProjectStateSnapshot(
            psiModificationCount = PsiModificationTracker.getInstance(project).modificationCount,
            unsavedProjectDocuments = countProjectUnsavedDocuments(project)
        )
    }

    private fun getSnapshotStaleness(project: Project): SnapshotStaleness {
        val snapshotState = resultsStore.getProjectState(projectKey(project)) ?: return SnapshotStaleness(false, emptyList())
        val currentState = captureProjectState(project)
        val reasons = mutableListOf<String>()
        if (currentState.unsavedProjectDocuments > 0) {
            reasons += "unsaved_documents"
        }
        if (currentState.psiModificationCount != snapshotState.psiModificationCount) {
            reasons += "project_changed_since_inspection"
        }
        return SnapshotStaleness(reasons.isNotEmpty(), reasons)
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
        maxFiles: Int?
    ): AnalysisScope {
        return try {
            val scopeLower = scopeParam?.lowercase()?.trim()

            if (scopeLower == "files" && !files.isNullOrEmpty()) {
                val base = project.basePath
                val resolved = files.mapNotNull { p ->
                    val absolute = try {
                        val path = Paths.get(p)
                        if (path.isAbsolute) p else if (!base.isNullOrBlank()) Paths.get(base, p).normalize().toString() else p
                    } catch (_: Exception) { p }
                    LocalFileSystem.getInstance().findFileByPath(absolute)
                }.toSet()
                return if (resolved.isEmpty()) AnalysisScope(project) else AnalysisScope(project, resolved)
            }

            if (scopeLower == "changed_files") {
                val clm = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
                val baseChanges = clm.allChanges

                val changeFiles = baseChanges.mapNotNull { ch ->
                    ch.virtualFile ?: ch.afterRevision?.file?.virtualFile ?: ch.beforeRevision?.file?.virtualFile
                }.toMutableList()

                // Best-effort Git staging filter when requested
                val mode = changedFilesMode?.lowercase()?.trim()
                if (!mode.isNullOrBlank() && mode != "all") {
                    val gitSets = computeGitStagingSets(project)
                    if (gitSets != null) {
                        val (stagedSet, unstagedSet) = gitSets
                        val basePath = project.basePath
                        if (!basePath.isNullOrBlank()) {
                            fun rel(p: String): String {
                                val rel = try {
                                    Paths.get(basePath).relativize(Paths.get(p)).toString()
                                } catch (_: Exception) { p }
                                return rel.replace('\\', '/')
                            }
                            changeFiles.retainAll { vf ->
                                val rp = rel(vf.path)
                                when (mode) {
                                    "staged" -> stagedSet.contains(rp)
                                    "unstaged" -> unstagedSet.contains(rp)
                                    else -> true
                                }
                            }
                        }
                    }
                }
                if (includeUnversioned) {
                    try {
                        val method = clm.javaClass.getMethod("getUnversionedFiles")
                        @Suppress("UNCHECKED_CAST")
                        val unversioned = method.invoke(clm) as? Collection<VirtualFile>
                        if (unversioned != null) changeFiles.addAll(unversioned)
                    } catch (_: Exception) {
                    }
                }
                val unique = changeFiles.distinct()
                val limited = if (maxFiles != null && maxFiles > 0) unique.take(maxFiles) else unique
                return if (limited.isEmpty()) AnalysisScope(project) else AnalysisScope(project, limited.toSet())
            }

            // 1) Explicit current file
            if (scopeLower == "current_file") {
                val vf = resolvedCurrentFile?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                if (vf != null) {
                    val psiFile = PsiManager.getInstance(project).findFile(vf)
                    if (psiFile != null) return AnalysisScope(psiFile)
                    return AnalysisScope(project, setOf(vf))
                }
                // Fallback: no valid active editor file (e.g., TabPreviewDiffVirtualFile) → whole project
            }

            // 2) Directory scoping: `scope=directory` with `dir=...` or any non-empty directoryParam
            val dirPath = directoryParam?.trim()
            if ((scopeLower == "directory" || !dirPath.isNullOrBlank())) {
                val base = project.basePath
                val absolute = when {
                    dirPath.isNullOrBlank() -> null
                    Paths.get(dirPath).isAbsolute -> dirPath
                    !base.isNullOrBlank() -> Paths.get(base, dirPath).normalize().toString()
                    else -> dirPath
                }
                if (!absolute.isNullOrBlank()) {
                    val vfs = LocalFileSystem.getInstance().findFileByPath(absolute)
                    if (vfs != null && vfs.isDirectory) {
                        val psiDir = PsiManager.getInstance(project).findDirectory(vfs)
                        if (psiDir != null) return AnalysisScope(psiDir)
                        return AnalysisScope(project, setOf(vfs))
                    }
                }
                return AnalysisScope(project)
            }

            AnalysisScope(project)
        } catch (_: Exception) {
            AnalysisScope(project)
        }
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
    ): ((Map<String, Any>) -> Boolean)? {
        val scopeLower = scopeParam?.lowercase()?.trim()

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

        if (scopeLower == "files" && !files.isNullOrEmpty()) {
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
            val clm = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
            val changeFiles = clm.allChanges.mapNotNull { change ->
                change.virtualFile ?: change.afterRevision?.file?.virtualFile ?: change.beforeRevision?.file?.virtualFile
            }.toMutableList()

            val mode = changedFilesMode?.lowercase()?.trim()
            if (!mode.isNullOrBlank() && mode != "all") {
                val gitSets = computeGitStagingSets(project)
                if (gitSets != null) {
                    val (stagedSet, unstagedSet) = gitSets
                    val basePath = project.basePath
                    if (!basePath.isNullOrBlank()) {
                        fun relativePath(path: String): String {
                            val relative = try {
                                Paths.get(basePath).relativize(Paths.get(path)).toString()
                            } catch (_: Exception) {
                                path
                            }
                            return relative.replace('\\', '/')
                        }
                        changeFiles.retainAll { vf ->
                            when (mode) {
                                "staged" -> stagedSet.contains(relativePath(vf.path))
                                "unstaged" -> unstagedSet.contains(relativePath(vf.path))
                                else -> true
                            }
                        }
                    }
                }
            }
            if (includeUnversioned) {
                try {
                    val method = clm.javaClass.getMethod("getUnversionedFiles")
                    @Suppress("UNCHECKED_CAST")
                    val unversioned = method.invoke(clm) as? Collection<VirtualFile>
                    if (unversioned != null) changeFiles.addAll(unversioned)
                } catch (_: Exception) {
                }
            }
            val limited = changeFiles.distinct().let { unique ->
                if (maxFiles != null && maxFiles > 0) unique.take(maxFiles) else unique
            }
            val resolved = limited.mapNotNull { normalizeFileSystemPath(it.path) }.toSet()
            return resolved.takeIf { it.isNotEmpty() }?.let(::exactFileMatcher)
        }

        if (scopeLower == "current_file") {
            return resolvedCurrentFile?.let { exactFileMatcher(setOf(it)) }
        }

        val dirPath = directoryParam?.trim()
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
        val gitDir = Paths.get(basePath, ".git").toFile()
        if (!gitDir.exists()) return null
        return try {
            val pb = ProcessBuilder("git", "status", "--porcelain", "-z")
            pb.directory(File(basePath))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val bytes = proc.inputStream.readAllBytes()
            proc.waitFor(2, TimeUnit.SECONDS)
            val out = bytes.toString(Charsets.UTF_8)
            parseGitStatusPorcelainZ(out)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveActiveEditorFile(project: Project): VirtualFile? {
        return try {
            val fem = FileEditorManager.getInstance(project)
            val candidates = buildList {
                addAll(runCatching { fem.selectedFiles.asList() }.getOrNull() ?: emptyList())
                addAll(runCatching { fem.openFiles.asList() }.getOrNull() ?: emptyList())
            }
            val index = ProjectFileIndex.getInstance(project)
            candidates.firstOrNull { vf ->
                try {
                    vf.isValid && vf.isInLocalFileSystem && index.isInContent(vf)
                } catch (_: Exception) { false }
            }
        } catch (_: Exception) {
            null
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

    private fun getProjectByName(projectName: String): Project? {
        return resolveProjectSelector(projectName)
    }

    private fun projectMatches(project: Project, projectName: String, pathHint: String?): Boolean {
        if (project.name == projectName) return true
        if (projectKey(project) == projectName) return true
        if (pathHint == null) return false

        val basePath = normalizeProjectPath(project.basePath)
        if (basePath != null && pathMatchesProject(pathHint, basePath)) return true

        val projectFilePath = normalizeProjectPath(project.projectFilePath)
        return projectFilePath != null && pathMatchesProject(pathHint, projectFilePath)
    }

    private fun projectPathMatchScore(project: Project, selectorPath: String?): Int? {
        return bestPathMatchScore(
            selectorPath,
            listOfNotNull(project.basePath, project.projectFilePath),
        )
    }

    private fun bestPathMatchScore(selectorPath: String?, projectPaths: List<String>): Int? {
        val normalizedSelector = normalizeProjectPath(selectorPath) ?: return null
        return projectPaths.maxOfOrNull { projectPath ->
            pathMatchScore(normalizedSelector, projectPath) ?: 0
        }?.takeIf { it > 0 }
    }

    private fun pathMatchScore(selectorPath: String, projectPath: String): Int? {
        val normalizedProjectPath = normalizeProjectPath(projectPath) ?: return null
        return runCatching {
            val selector = Paths.get(selectorPath).normalize().toAbsolutePath()
            val project = Paths.get(normalizedProjectPath).normalize().toAbsolutePath()
            when {
                selector == project -> 2_000_000 + project.toString().length
                selector.startsWith(project) -> 1_000_000 + project.toString().length
                else -> null
            }
        }.getOrNull()
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
        val queryValue = extractProjectQueryParameter(urlDecoder, request)
        return normalizeProjectSelector(queryValue)
    }

    private fun extractProjectQueryParameter(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
    ): String? {
        for (parameterName in listOf("project_key", "project_path", "worktree_path", "cwd", "project")) {
            val parameterValues = urlDecoder.parameters()[parameterName]
            if (parameterValues != null) {
                val firstNonBlankValue = parameterValues.firstOrNull { value -> value.trim().isNotEmpty() }
                if (firstNonBlankValue != null) {
                    return firstNonBlankValue
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
            if (decodedName !in setOf("project", "project_key", "project_path", "worktree_path", "cwd")) {
                continue
            }
            val encodedValue = nameAndValue.getOrElse(1) { "" }
            val decodedValue = QueryStringDecoder.decodeComponent(encodedValue)
            if (decodedValue.trim().isNotEmpty()) {
                return decodedValue
            }
        }

        return null
    }

    @Suppress("UnstableApiUsage")
    private fun extractProblemsFromContext(
        globalContext: com.intellij.codeInspection.ex.GlobalInspectionContextImpl,
        project: Project,
    ): List<Map<String, Any>> {
        val problems = mutableListOf<Map<String, Any>>()
        val seen = LinkedHashSet<String>()
        val tools = globalContext.tools.values

        for (toolGroup in tools) {
            val scopeStates = try {
                toolGroup.tools
            } catch (_: Exception) {
                emptyList()
            }
            for (state in scopeStates) {
                if (!state.isEnabled) continue
                val wrapper = try {
                    state.tool
                } catch (_: Exception) {
                    null
                } ?: continue

                val presentation = try {
                    globalContext.getPresentation(wrapper)
                } catch (_: Exception) {
                    null
                } ?: continue

                try {
                    presentation.updateContent()
                } catch (_: Exception) {
                }

                val descriptors = try {
                    val elements = presentation.problemElements
                    val values = elements.getValues()
                    if (!values.isNullOrEmpty()) values else presentation.problemDescriptors
                } catch (_: Exception) {
                    emptyList()
                }

                for (descriptor in descriptors) {
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

        return problems
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
