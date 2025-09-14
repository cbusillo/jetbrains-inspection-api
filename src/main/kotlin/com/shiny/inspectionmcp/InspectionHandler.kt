package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import org.jetbrains.ide.HttpRequestHandler
import java.util.concurrent.TimeUnit

class InspectionHandler : HttpRequestHandler() {
    
    @Volatile
    private var lastInspectionTriggerTime: Long = 0
    @Volatile
    private var inspectionInProgress: Boolean = false
    
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
                    val problemType = urlDecoder.parameters()["problem_type"]?.firstOrNull()
                    val filePattern = urlDecoder.parameters()["file_pattern"]?.firstOrNull()
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
                    lastInspectionTriggerTime = System.currentTimeMillis()
                    inspectionInProgress = true
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        triggerInspectionAsync(
                            projectName = projectName,
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
            val extractor = EnhancedTreeExtractor()
            val problems = extractor.extractAllProblems(project)
            
            if (problems.isNotEmpty()) {
                val severityFilteredProblems = if (severity == "all") {
                    problems
                } else {
                    problems.filter { 
                        @Suppress("USELESS_CAST")
                        val problemSeverity = it["severity"] as String
                        severity == problemSeverity || 
                        (severity == "warning" && (problemSeverity == "grammar" || problemSeverity == "typo"))
                    }
                }
                
                val scopeFilteredProblems = if (scope == "whole_project") {
                    severityFilteredProblems
                } else {
                    val filtered = severityFilteredProblems.filter { problem ->
                        val filePath = problem["file"] as? String ?: ""
                        when (scope) {
                            // Limit to the currently selected editor file when available
                            "current_file" -> {
                                val selected = try {
                                    com.intellij.openapi.fileEditor.FileEditorManager
                                        .getInstance(project)
                                        .selectedFiles
                                        .firstOrNull()
                                        ?.path
                                } catch (_: Exception) { null }

                                if (selected.isNullOrBlank()) {
                                    // Fallback: no active file; do not match anything to avoid
                                    // returning whole-project results under a current_file scope
                                    false
                                } else {
                                    // Compare absolute paths; also allow endsWith as a light fallback
                                    filePath == selected || filePath.endsWith(selected)
                                }
                            }
                            else -> {
                                filePath.contains(scope, ignoreCase = true)
                            }
                        }
                    }
                    filtered
                }
                
                val problemTypeFilteredProblems = if (problemType != null) {
                    scopeFilteredProblems.filter { problem ->
                        val inspectionType = problem["inspectionType"] as? String ?: ""
                        val category = problem["category"] as? String ?: ""
                        inspectionType.contains(problemType, ignoreCase = true) || 
                        category.contains(problemType, ignoreCase = true)
                    }
                } else {
                    scopeFilteredProblems
                }
                
                val filePatternFilteredProblems = if (filePattern != null) {
                    val regex = try { 
                        Regex(filePattern, RegexOption.IGNORE_CASE) 
                    } catch (_: Exception) {
                        null
                    }
                    
                    if (regex != null) {
                        problemTypeFilteredProblems.filter { problem ->
                            val filePath = problem["file"] as? String ?: ""
                            regex.containsMatchIn(filePath)
                        }
                    } else {
                        problemTypeFilteredProblems.filter { problem ->
                            val filePath = problem["file"] as? String ?: ""
                            filePath.contains(filePattern, ignoreCase = true)
                        }
                    }
                } else {
                    problemTypeFilteredProblems
                }
                
                val totalFilteredProblems = filePatternFilteredProblems.size
                
                val paginatedProblems = filePatternFilteredProblems
                    .drop(offset)
                    .take(limit)
                
                val hasMore = offset + paginatedProblems.size < totalFilteredProblems
                val nextOffset = if (hasMore) offset + limit else null
                
                val response = mapOf(
                    "status" to "results_available",
                    "project" to project.name,
                    "timestamp" to System.currentTimeMillis(),
                    "total_problems" to totalFilteredProblems,
                    "problems_shown" to paginatedProblems.size,
                    "problems" to paginatedProblems,
                    "pagination" to mapOf(
                        "limit" to limit,
                        "offset" to offset,
                        "has_more" to hasMore,
                        "next_offset" to nextOffset
                    ),
                    "filters" to mapOf(
                        "severity" to severity,
                        "scope" to scope,
                        "problem_type" to (problemType ?: "all"),
                        "file_pattern" to (filePattern ?: "all")
                    ),
                    "method" to "enhanced_tree"
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
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            val problemsWindow = toolWindowManager.getToolWindow("Problems View")
            val inspectionResultsWindow = toolWindowManager.getToolWindow("Inspection Results")

            val status = mutableMapOf<String, Any>()
            status["project_name"] = project.name

            val currentTime = System.currentTimeMillis()
            val timeSinceLastTrigger = currentTime - lastInspectionTriggerTime

            val dumbService = com.intellij.openapi.project.DumbService.getInstance(project)
            val isIndexing = dumbService.isDumb

            // Use the same extractor as the /problems endpoint so status matches real availability
            val extractor = EnhancedTreeExtractor()
            val problemsAvailable = try {
                extractor.extractAllProblems(project).isNotEmpty()
            } catch (_: Exception) { false }

            // Window visibility hints (best-effort)
            val problemsVisible = problemsWindow?.isVisible ?: false
            val inspectionVisible = inspectionResultsWindow?.isVisible ?: false
            status["problems_window_visible"] = problemsVisible || inspectionVisible
            if (inspectionResultsWindow != null && inspectionResultsWindow.isVisible) {
                status["active_tool_window"] = "Inspection Results"
            } else if (problemsWindow != null && problemsWindow.isVisible) {
                status["active_tool_window"] = "Problems View"
            }

            val isLikelyStillRunning = inspectionInProgress && timeSinceLastTrigger < 30000

            if (problemsAvailable && inspectionInProgress && timeSinceLastTrigger > 5000) {
                inspectionInProgress = false
            }

            status["is_scanning"] = isIndexing || isLikelyStillRunning
            status["has_inspection_results"] = problemsAvailable
            status["inspection_in_progress"] = inspectionInProgress
            status["time_since_last_trigger_ms"] = timeSinceLastTrigger
            status["indexing"] = isIndexing

            // Clear indicator for a clean inspection (recent, finished, and no problems)
            val recentlyCompleted = timeSinceLastTrigger < 60000
            val cleanInspection = recentlyCompleted && !isLikelyStillRunning && !problemsAvailable
            status["clean_inspection"] = cleanInspection

            formatJsonManually(status)
        } catch (_: Exception) {
            """{"error": "Failed to get status"}"""
        }
    }
    
    private fun triggerInspectionAsync(
        projectName: String? = null,
        scopeParam: String? = null,
        directoryParam: String? = null,
        files: List<String>? = null,
        includeUnversioned: Boolean = true,
        changedFilesMode: String? = null,
        maxFiles: Int? = null,
        profileName: String? = null,
    ) {
        val project = getCurrentProject(projectName) ?: return
        
        try {
            inspectionInProgress = true
            
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            // Clear prior results from both known locations to avoid stale reads
            listOf("Problems View", "Inspection Results").forEach { name ->
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
            
            inspectionInProgress = false
        } catch (_: Exception) {
            inspectionInProgress = false
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
                        val path = java.nio.file.Paths.get(p)
                        if (path.isAbsolute) p else if (!base.isNullOrBlank()) java.nio.file.Paths.get(base, p).normalize().toString() else p
                    } catch (_: Exception) { p }
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(absolute)
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
                                    java.nio.file.Paths.get(basePath).relativize(java.nio.file.Paths.get(p)).toString()
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
                    val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf)
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
                    java.nio.file.Paths.get(dirPath).isAbsolute -> dirPath
                    !base.isNullOrBlank() -> java.nio.file.Paths.get(base, dirPath).normalize().toString()
                    else -> dirPath
                }
                if (!absolute.isNullOrBlank()) {
                    val vfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(absolute)
                    if (vfs != null && vfs.isDirectory) {
                        val psiDir = com.intellij.psi.PsiManager.getInstance(project).findDirectory(vfs)
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
        val gitDir = java.nio.file.Paths.get(basePath, ".git").toFile()
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
        
        return openProjects.firstOrNull { project ->
            !project.isDefault && 
            !project.isDisposed && 
            project.isInitialized && 
            project.name == projectName
        }
    }
    
    private fun formatJsonManually(data: Any?): String {
        return when (data) {
            is String -> "\"${data.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
            is Number -> data.toString()
            is Boolean -> data.toString()
            is List<*> -> data.joinToString(",", "[", "]") { formatJsonManually(it) }
            is Map<*, *> -> data.entries.joinToString(",\n  ", "{\n  ", "\n}") { (k, v) ->
                "\"$k\": ${formatJsonManually(v)}"
            }
            null -> "null"
            else -> "\"$data\""
        }
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
