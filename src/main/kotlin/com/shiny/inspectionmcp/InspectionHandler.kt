package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiManager
import com.shiny.inspectionmcp.core.filterProblems
import com.shiny.inspectionmcp.core.formatJsonManually
import com.shiny.inspectionmcp.core.normalizeProblemsScope
import com.shiny.inspectionmcp.core.paginateProblems
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import org.jetbrains.ide.HttpRequestHandler
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

class InspectionHandler : HttpRequestHandler() {
    
    @Volatile
    private var lastInspectionTriggerTime: Long = 0
    @Volatile
    private var inspectionInProgress: Boolean = false

    private val resultsStore = InspectionResultsStore
    
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
            when (path) {
                "/api/inspection/problems" -> {
                    val projectName = urlDecoder.parameters()["project"]?.firstOrNull()
                    val severity = urlDecoder.parameters()["severity"]?.firstOrNull() ?: "all"
                    val scope = urlDecoder.parameters()["scope"]?.firstOrNull() ?: "whole_project"
                    val problemTypeRaw = urlDecoder.parameters()["problem_type"]?.firstOrNull()
                    val filePatternRaw = urlDecoder.parameters()["file_pattern"]?.firstOrNull()
                    val problemType = normalizeOptionalFilter(problemTypeRaw)
                    val filePattern = normalizeOptionalFilter(filePatternRaw)
                    val limit = urlDecoder.parameters()["limit"]?.firstOrNull()?.toIntOrNull() ?: 100
                    val offset = urlDecoder.parameters()["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
                    val result = ReadAction.compute<String, Exception> {
                        getInspectionProblems(projectName, severity, scope, problemType, filePattern, limit, offset)
                    }
                    sendJsonResponse(context, result)
                }
                "/api/inspection/trigger" -> {
                    val projectName = urlDecoder.parameters()["project"]?.firstOrNull()
                    val scope = urlDecoder.parameters()["scope"]?.firstOrNull()
                    // Accept either `dir`, `directory`, or `path` for directory scoping
                    val directory = urlDecoder.parameters()["dir"]?.firstOrNull()
                        ?: urlDecoder.parameters()["directory"]?.firstOrNull()
                        ?: urlDecoder.parameters()["path"]?.firstOrNull()
                    val filesList = mutableListOf<String>()
                    val repeatedFiles = urlDecoder.parameters()["file"] ?: emptyList()
                    if (repeatedFiles.isNotEmpty()) filesList.addAll(repeatedFiles)
                    val filesParam = urlDecoder.parameters()["files"]?.firstOrNull()
                    if (!filesParam.isNullOrBlank()) {
                        filesList.addAll(filesParam.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() })
                    }
                    val includeUnversioned = urlDecoder.parameters()["include_unversioned"]?.firstOrNull()?.equals("true", ignoreCase = true) ?: true
                    val changedFilesMode = urlDecoder.parameters()["changed_files_mode"]?.firstOrNull()?.lowercase()?.trim()
                    val maxFiles = urlDecoder.parameters()["max_files"]?.firstOrNull()?.toIntOrNull()
                    val profile = urlDecoder.parameters()["profile"]?.firstOrNull()
                    val project = ReadAction.compute<Project?, Exception> { getCurrentProject(projectName) }
                    if (project == null) {
                        sendJsonResponse(context, """{"error": "No project found"}""", HttpResponseStatus.NOT_FOUND)
                        return true
                    }
                    lastInspectionTriggerTime = System.currentTimeMillis()
                    inspectionInProgress = true
                    ApplicationManager.getApplication().invokeLater {
                        triggerInspectionAsync(
                            project = project,
                            scopeParam = scope,
                            directoryParam = directory,
                            files = if (filesList.isEmpty()) null else filesList,
                            includeUnversioned = includeUnversioned,
                            changedFilesMode = changedFilesMode,
                            maxFiles = maxFiles,
                            profileName = profile
                        )
                    }
                    val details = mutableMapOf<String, Any>(
                        "status" to "triggered",
                        "message" to "Inspection triggered. Wait 10-15 seconds then check status"
                    )
                    if (!scope.isNullOrBlank()) details["scope"] = scope
                    if (!directory.isNullOrBlank()) details["directory"] = directory
                    if (filesList.isNotEmpty()) details["files_requested"] = filesList.size
                    details["include_unversioned"] = includeUnversioned
                    if (!changedFilesMode.isNullOrBlank()) details["changed_files_mode"] = changedFilesMode
                    if (maxFiles != null) details["max_files"] = maxFiles
                    if (!profile.isNullOrBlank()) details["profile"] = profile
                    sendJsonResponse(context, formatJsonManually(details))
                }
                "/api/inspection/status" -> {
                    val projectName = urlDecoder.parameters()["project"]?.firstOrNull()
                    val result = ReadAction.compute<String, Exception> {
                        val project = getCurrentProject(projectName)
                        if (project != null) {
                            getInspectionStatus(project)
                        } else {
                            """{"error": "No project found"}"""
                        }
                    }
                    sendJsonResponse(context, result)
                }
                "/api/inspection/wait" -> {
                    val projectName = urlDecoder.parameters()["project"]?.firstOrNull()
                    val timeoutMs = urlDecoder.parameters()["timeout_ms"]?.firstOrNull()?.toLongOrNull()
                    val pollMs = urlDecoder.parameters()["poll_ms"]?.firstOrNull()?.toLongOrNull()
                    val result = waitForInspection(projectName, timeoutMs, pollMs)
                    sendJsonResponse(context, result)
                }
                else -> {
                    sendJsonResponse(context, """{"error": "Unknown endpoint"}""", HttpResponseStatus.NOT_FOUND)
                }
            }
            return true
        } catch (_: Exception) {
            sendJsonResponse(context, """{"error": "Internal server error"}""", HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return true
        }
    }
    
    private fun getInspectionProblems(
        projectName: String? = null,
        severity: String = "all", 
        scope: String = "whole_project",
        problemType: String? = null,
        filePattern: String? = null,
        limit: Int = 100,
        offset: Int = 0
    ): String {
        val project = getCurrentProject(projectName)
            ?: return """{"error": "No project found"}"""

        return try {
            val normalizedScope = normalizeProblemsScope(scope)
            val cachedProblems = resultsStore.getProblems(project.name)
            val hasSnapshot = resultsStore.hasSnapshot(project.name)
            val problems = when {
                cachedProblems != null -> cachedProblems
                hasSnapshot -> emptyList()
                else -> {
                    val extractor = EnhancedTreeExtractor()
                    extractor.extractAllProblems(project)
                }
            }
            
            if (problems.isNotEmpty() || hasSnapshot) {
                val currentFilePath = if (normalizedScope == "current_file") {
                    try {
                        com.intellij.openapi.fileEditor.FileEditorManager
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
                    "timestamp" to (resultsStore.getTimestamp(project.name) ?: System.currentTimeMillis()),
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
                    "method" to if (hasSnapshot) "inspection_view" else "enhanced_tree"
                )
                
                formatJsonManually(response)
            } else {
                """{"status": "no_results", "message": "No inspection results found. Either run an inspection first, or the last inspection found no problems (100% pass)."}"""
            }
        } catch (_: Exception) {
            """{"error": "Failed to get inspection problems"}"""
        }
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
        val problemsWindows = listOf("Problems View", "Problems", "Inspection Results")
            .mapNotNull { toolWindowManager.getToolWindow(it) }
        val problemsWindow = problemsWindows.firstOrNull()
        val inspectionResultsWindow = toolWindowManager.getToolWindow("Inspection Results")

        val status = mutableMapOf<String, Any>()
        status["project_name"] = project.name

        val currentTime = System.currentTimeMillis()
        val timeSinceLastTrigger = currentTime - lastInspectionTriggerTime

        val dumbService = com.intellij.openapi.project.DumbService.getInstance(project)
        val isIndexing = dumbService.isDumb

        // Use the same extractor as the /problems endpoint so status matches real availability
        val cachedProblems = resultsStore.getProblems(project.name)
        val cachedTimestamp = resultsStore.getTimestamp(project.name)
        val hasInspectionSnapshot = cachedTimestamp != null
        val extractor = EnhancedTreeExtractor()
        val problemsSnapshot = if (hasInspectionSnapshot) {
            cachedProblems ?: emptyList()
        } else {
            try {
                extractor.extractAllProblems(project)
            } catch (_: Exception) { emptyList() }
        }
        val problemsAvailable = problemsSnapshot.isNotEmpty()
        status["total_problems"] = problemsSnapshot.size
        status["results_source"] = if (hasInspectionSnapshot) "inspection_view" else "tool_window"

        // Window visibility hints (best-effort)
        val problemsVisible = problemsWindow?.isVisible ?: false
        val inspectionVisible = inspectionResultsWindow?.isVisible ?: false
        status["problems_window_visible"] = problemsVisible || inspectionVisible
        if (inspectionResultsWindow != null && inspectionResultsWindow.isVisible) {
            status["active_tool_window"] = "Inspection Results"
        } else if (problemsWindow != null && problemsWindow.isVisible) {
            status["active_tool_window"] = "Problems View"
        }

        val isLikelyStillRunning = inspectionInProgress && timeSinceLastTrigger < 300000

        if (problemsAvailable && inspectionInProgress && timeSinceLastTrigger > 5000) {
            inspectionInProgress = false
        }

        status["is_scanning"] = isIndexing || isLikelyStillRunning
        status["has_inspection_results"] = hasInspectionSnapshot || problemsAvailable
        status["inspection_in_progress"] = inspectionInProgress
        status["time_since_last_trigger_ms"] = timeSinceLastTrigger
        status["indexing"] = isIndexing

        // Clear indicator for a clean inspection (recent, finished, and no problems)
        val recentlyCompleted = timeSinceLastTrigger < 60000
        val cleanInspection = recentlyCompleted && !isLikelyStillRunning && !problemsAvailable && hasInspectionSnapshot
        status["clean_inspection"] = cleanInspection

        return status
    }

    private fun waitForInspection(projectName: String?, timeoutMsRaw: Long?, pollMsRaw: Long?): String {
        val timeoutMs = (timeoutMsRaw ?: 180000L).coerceIn(1000L, 300000L)
        val pollMs = (pollMsRaw ?: 1000L).coerceIn(200L, 5000L).coerceAtMost(timeoutMs)
        val start = System.currentTimeMillis()
        var project = ReadAction.compute<Project?, Exception> { getCurrentProject(projectName) }
        while (project == null) {
            if (System.currentTimeMillis() - start >= timeoutMs) {
                return formatWaitError("No project found", start, timeoutMs, pollMs, "no_project")
            }

            try {
                TimeUnit.MILLISECONDS.sleep(pollMs)
            } catch (_: Exception) {
                return formatWaitError("Wait interrupted", start, timeoutMs, pollMs, "interrupted")
            }

            project = ReadAction.compute<Project?, Exception> { getCurrentProject(projectName) }
        }

        val activeProject = project
            ?: return formatWaitError("No project found", start, timeoutMs, pollMs, "no_project")
        var lastStableCount: Int? = null
        var stableCountHits = 0
        var stableSince: Long? = null
        var status = ReadAction.compute<MutableMap<String, Any>, Exception> { buildInspectionStatus(activeProject) }

        while (true) {
        val hasResults = status["has_inspection_results"] as? Boolean ?: false
        val cleanInspection = status["clean_inspection"] as? Boolean ?: false
        val isScanning = status["is_scanning"] as? Boolean ?: false
        val inProgress = status["inspection_in_progress"] as? Boolean ?: false
        val totalProblems = (status["total_problems"] as? Number)?.toInt()
        val resultsSource = status["results_source"] as? String
        val minStableMs = 5000L
        val now = System.currentTimeMillis()

        if (cleanInspection && hasResults && !isScanning && !inProgress) {
            return formatWaitResponse(status, start, timeoutMs, pollMs, true, "clean")
        }

        val timeSinceTrigger = (status["time_since_last_trigger_ms"] as? Number)?.toLong()
        if (
            resultsSource == "tool_window" &&
            !hasResults &&
            !isScanning &&
            !inProgress &&
            timeSinceTrigger != null &&
            timeSinceTrigger < 60000
        ) {
            status["wait_note"] = "Inspection finished but no results were captured. This can happen for clean runs or when the Inspection Results view was unavailable. Re-run the inspection or open the Inspection Results tool window."
            return formatWaitResponse(status, start, timeoutMs, pollMs, true, "no_results")
        }

        if (hasResults && !isScanning && !inProgress) {
            if (resultsSource == "inspection_view") {
                return formatWaitResponse(status, start, timeoutMs, pollMs, true, "results")
            }

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

                if (stableSince != null && now - stableSince >= minStableMs) {
                    return formatWaitResponse(status, start, timeoutMs, pollMs, true, "results")
                }
            } else {
                return formatWaitResponse(status, start, timeoutMs, pollMs, true, "results")
            }
        }

            if (System.currentTimeMillis() - start >= timeoutMs) {
                return formatWaitResponse(status, start, timeoutMs, pollMs, false, "timeout")
            }

            try {
                TimeUnit.MILLISECONDS.sleep(pollMs)
            } catch (_: Exception) {
                return formatWaitResponse(status, start, timeoutMs, pollMs, false, "interrupted")
            }

            status = ReadAction.compute<MutableMap<String, Any>, Exception> { buildInspectionStatus(activeProject) }
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
        message: String,
        startMs: Long,
        timeoutMs: Long,
        pollMs: Long,
        reason: String
    ): String {
        val response = mutableMapOf<String, Any>()
        response["error"] = message
        response["wait_completed"] = false
        response["timed_out"] = reason == "no_project" || reason == "timeout"
        response["completion_reason"] = reason
        response["wait_ms"] = System.currentTimeMillis() - startMs
        response["timeout_ms"] = timeoutMs
        response["poll_ms"] = pollMs
        return formatJsonManually(response)
    }
    
    private fun triggerInspectionAsync(
        project: Project,
        scopeParam: String? = null,
        directoryParam: String? = null,
        files: List<String>? = null,
        includeUnversioned: Boolean = true,
        changedFilesMode: String? = null,
        maxFiles: Int? = null,
        profileName: String? = null,
    ) {
        try {
            inspectionInProgress = true
            resultsStore.clear(project.name)
            
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            // Clear prior results from both known locations to avoid stale reads
            listOf("Problems View", "Problems", "Inspection Results").forEach { name ->
                val tw = toolWindowManager.getToolWindow(name)
                if (tw != null) {
                    for (i in 0 until tw.contentManager.contentCount) {
                        val content = tw.contentManager.getContent(i)
                        if (content != null && content.component.javaClass.name.contains("InspectionResultsView")) {
                            tw.contentManager.removeContent(content, true)
                            try { Thread.sleep(200) } catch (_: Exception) {}
                            break
                        }
                    }
                }
            }
            
            FileDocumentManager.getInstance().saveAllDocuments()

            val scope: AnalysisScope = buildAnalysisScope(
                project = project,
                scopeParam = scopeParam,
                directoryParam = directoryParam,
                files = files,
                includeUnversioned = includeUnversioned,
                changedFilesMode = changedFilesMode,
                maxFiles = maxFiles
            )
            
            @Suppress("USELESS_CAST")
            val inspectionManager = InspectionManager.getInstance(project) as com.intellij.codeInspection.ex.InspectionManagerEx
            val profileManager = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project)
            val profile = if (!profileName.isNullOrBlank()) {
                profileManager.getProfile(profileName) ?: profileManager.currentProfile
            } else profileManager.currentProfile
            
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

                val projectName = project.name
                ApplicationManager.getApplication().executeOnPooledThread {
                    val extractor = EnhancedTreeExtractor()
                    val extractedFromContext = try {
                        ReadAction.compute<List<Map<String, Any>>, Exception> {
                            extractProblemsFromContext(globalContext, project)
                        }
                    } catch (e: Exception) {
                        rethrowIfCanceled(e)
                        emptyList()
                    }

                    val deadlineMs = System.currentTimeMillis() + 60000
                    var bestResults: List<Map<String, Any>> = extractedFromContext
                    var lastSize = bestResults.size
                    var lastChangeMs = System.currentTimeMillis()

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

                    val viewReadyOk = try {
                        viewReady.waitFor(60000)
                    } catch (e: Exception) {
                        rethrowIfCanceled(e)
                        false
                    }

                    while (System.currentTimeMillis() < deadlineMs) {
                        val view = try {
                            @Suppress("UnstableApiUsage")
                            globalContext.view
                        } catch (e: Exception) {
                            rethrowIfCanceled(e)
                            null
                        } ?: initialView

                        if (viewReadyOk && view != null) {
                            val attempt = try {
                                extractFromViewSafe(view)
                            } catch (e: Exception) {
                                rethrowIfCanceled(e)
                                emptyList()
                            }
                            if (attempt.size > bestResults.size) {
                                bestResults = attempt
                            }
                        }

                        val toolResults = try {
                            ReadAction.compute<List<Map<String, Any>>, Exception> {
                                extractor.extractAllProblems(project)
                            }
                        } catch (e: Exception) {
                            rethrowIfCanceled(e)
                            emptyList()
                        }
                        if (toolResults.size > bestResults.size) {
                            bestResults = toolResults
                        }

                        if (bestResults.size != lastSize) {
                            lastSize = bestResults.size
                            lastChangeMs = System.currentTimeMillis()
                        }

                        if (viewReadyOk && System.currentTimeMillis() - lastChangeMs >= 5000) {
                            break
                        }

                        try {
                            Thread.sleep(1000)
                        } catch (e: Exception) {
                            rethrowIfCanceled(e)
                            break
                        }
                    }

                    val extracted = bestResults

                    resultsStore.setProblems(projectName, extracted)
                    inspectionInProgress = false
                }
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                resultsStore.setProblems(project.name, emptyList())
                inspectionInProgress = false
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            inspectionInProgress = false
        }
    }

    private fun rethrowIfCanceled(e: Exception) {
        if (e is com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        }
    }

    private object InspectionResultsStore {
        private val problemsByProject = java.util.concurrent.ConcurrentHashMap<String, List<Map<String, Any>>>()
        private val timestampByProject = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun getProblems(projectName: String): List<Map<String, Any>>? {
            return problemsByProject[projectName]
        }

        fun getTimestamp(projectName: String): Long? {
            return timestampByProject[projectName]
        }

        fun hasSnapshot(projectName: String): Boolean {
            return timestampByProject.containsKey(projectName)
        }

        fun setProblems(projectName: String, problems: List<Map<String, Any>>) {
            problemsByProject[projectName] = problems
            timestampByProject[projectName] = System.currentTimeMillis()
        }

        fun clear(projectName: String) {
            problemsByProject.remove(projectName)
            timestampByProject.remove(projectName)
        }
    }

    private fun buildAnalysisScope(
        project: Project,
        scopeParam: String?,
        directoryParam: String?,
        files: List<String>?,
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
                        val unversioned = method.invoke(clm) as? Collection<com.intellij.openapi.vfs.VirtualFile>
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
                val vf = resolveActiveEditorFile(project)
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

    private fun computeGitStagingSets(project: Project): Pair<Set<String>, Set<String>>? {
        val basePath = project.basePath ?: return null
        val gitDir = Paths.get(basePath, ".git").toFile()
        if (!gitDir.exists()) return null
        return try {
            val pb = ProcessBuilder("git", "status", "--porcelain", "-z")
            pb.directory(java.io.File(basePath))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val bytes = proc.inputStream.readAllBytes()
            proc.waitFor(2, TimeUnit.SECONDS)
            val out = bytes.toString(Charsets.UTF_8)
            val staged = mutableSetOf<String>()
            val unstaged = mutableSetOf<String>()
            var i = 0
            while (i < out.length) {
                val zero = out.indexOf('\u0000', i)
                if (zero == -1) break
                val entry = out.substring(i, zero)
                if (entry.length >= 3) {
                    val x = entry[0]
                    val y = entry[1]
                    // Entry format: XY<space>path or for renames: R<score><space>old<null>new
                    val spaceIdx = entry.indexOf(' ', 2)
                    if (spaceIdx >= 2) {
                        // Path may start after XY and one separating space; trim leading spaces
                        val pathPart = entry.substring(spaceIdx + 1).trimStart()
                        val normalized = pathPart.replace('\\', '/')
                        if (x != ' ') staged.add(normalized)
                        if (y != ' ') unstaged.add(normalized)
                    }
                }
                i = zero + 1
            }
            Pair(staged, unstaged)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveActiveEditorFile(project: Project): com.intellij.openapi.vfs.VirtualFile? {
        return try {
            val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            val candidates = buildList {
                addAll(runCatching { fem.selectedFiles.asList() }.getOrNull() ?: emptyList())
                addAll(runCatching { fem.openFiles.asList() }.getOrNull() ?: emptyList())
            }
            val index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
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
        return try {
            if (projectName != null) {
                val projectByName = getProjectByName(projectName)
                if (projectByName != null) {
                    return projectByName
                }
            }
            
            val lastFocusedFrame = IdeFocusManager.getGlobalInstance().lastFocusedFrame
            val projectFromFrame = lastFocusedFrame?.project
            if (projectFromFrame != null && !projectFromFrame.isDefault && !projectFromFrame.isDisposed && projectFromFrame.isInitialized) {
                return projectFromFrame
            }
            
            val dataContextFuture = DataManager.getInstance().dataContextFromFocusAsync
            val dataContext = try {
                dataContextFuture.blockingGet(1000, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                null
            }
            
            val projectFromDataContext = dataContext?.let { CommonDataKeys.PROJECT.getData(it) }
            if (projectFromDataContext != null && !projectFromDataContext.isDefault && !projectFromDataContext.isDisposed && projectFromDataContext.isInitialized) {
                return projectFromDataContext
            }
            
            val projectManager = ProjectManager.getInstance()
            val openProjects = projectManager.openProjects
            
            val validProjects = openProjects.filter { project ->
                !project.isDefault && !project.isDisposed && project.isInitialized
            }
            
            if (validProjects.isEmpty()) {
                return null
            }
            
            for (project in validProjects) {
                val window = WindowManager.getInstance().suggestParentWindow(project)
                if (window != null && window.isActive) {
                    return project
                }
            }
            
            validProjects.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
    
    private fun getProjectByName(projectName: String): Project? {
        val projectManager = ProjectManager.getInstance()
        val openProjects = projectManager.openProjects
        val trimmed = projectName.trim()
        val pathHint = normalizeProjectPath(trimmed)

        return openProjects.firstOrNull { project ->
            !project.isDefault &&
            !project.isDisposed &&
            project.isInitialized &&
            projectMatches(project, trimmed, pathHint)
        }
    }

    private fun projectMatches(project: Project, projectName: String, pathHint: String?): Boolean {
        if (project.name == projectName) return true
        if (pathHint == null) return false

        val basePath = normalizeProjectPath(project.basePath)
        if (basePath != null && basePath == pathHint) return true

        val projectFilePath = normalizeProjectPath(project.projectFilePath)
        return projectFilePath == pathHint
    }

    private fun normalizeProjectPath(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!looksLikePath(raw)) return null
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
        val description = descriptor.descriptionTemplate?.takeIf { it.isNotBlank() } ?: return null
        val inspectionType = wrapper.shortName ?: wrapper.id
        val category = wrapper.groupDisplayName ?: wrapper.displayName

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
