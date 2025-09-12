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
                    lastInspectionTriggerTime = System.currentTimeMillis()
                    inspectionInProgress = true
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        triggerInspectionAsync(projectName, scope, directory)
                    }
                    val details = mutableMapOf<String, Any>(
                        "status" to "triggered",
                        "message" to "Inspection triggered. Wait 10-15 seconds then check status"
                    )
                    if (!scope.isNullOrBlank()) details["scope"] = scope
                    if (!directory.isNullOrBlank()) details["directory"] = directory
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
            
            val status = mutableMapOf<String, Any>()
            status["project_name"] = project.name
            
            val currentTime = System.currentTimeMillis()
            val timeSinceLastTrigger = currentTime - lastInspectionTriggerTime
            
            val dumbService = com.intellij.openapi.project.DumbService.getInstance(project)
            val isIndexing = dumbService.isDumb
            
            var hasInspectionResults = false
            if (problemsWindow != null) {
                for (i in 0 until problemsWindow.contentManager.contentCount) {
                    val content = problemsWindow.contentManager.getContent(i)
                    if (content != null && content.component.javaClass.name.contains("InspectionResultsView")) {
                        hasInspectionResults = true
                        break
                    }
                }
                status["problems_window_visible"] = problemsWindow.isVisible
            } else {
                status["problems_window_visible"] = false
            }
            
            val isLikelyStillRunning = inspectionInProgress && timeSinceLastTrigger < 30000
            
            if (hasInspectionResults && inspectionInProgress && timeSinceLastTrigger > 5000) {
                inspectionInProgress = false
            }
            
            status["is_scanning"] = isIndexing || isLikelyStillRunning
            status["has_inspection_results"] = hasInspectionResults
            status["inspection_in_progress"] = inspectionInProgress
            status["time_since_last_trigger_ms"] = timeSinceLastTrigger
            status["indexing"] = isIndexing
            
            // Add clear status for clean inspection
            val recentlyCompleted = timeSinceLastTrigger < 60000
            val cleanInspection = recentlyCompleted && !isLikelyStillRunning && !hasInspectionResults
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
    ) {
        val project = getCurrentProject(projectName) ?: return
        
        try {
            inspectionInProgress = true
            
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            val problemsWindow = toolWindowManager.getToolWindow("Problems View")
            
            if (problemsWindow != null) {
                for (i in 0 until problemsWindow.contentManager.contentCount) {
                    val content = problemsWindow.contentManager.getContent(i)
                    if (content != null && content.component.javaClass.name.contains("InspectionResultsView")) {
                        problemsWindow.contentManager.removeContent(content, true)
                        Thread.sleep(500)
                        break
                    }
                }
            }
            
            FileDocumentManager.getInstance().saveAllDocuments()

            // Build an AnalysisScope based on request parameters
            val scope: AnalysisScope = buildAnalysisScope(project, scopeParam, directoryParam)
            
            @Suppress("USELESS_CAST")
            val inspectionManager = InspectionManager.getInstance(project) as com.intellij.codeInspection.ex.InspectionManagerEx
            val profileManager = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project)
            val profile = profileManager.currentProfile
            
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
        directoryParam: String?
    ): AnalysisScope {
        return try {
            val scopeLower = scopeParam?.lowercase()?.trim()

            // 1) Explicit current file
            if (scopeLower == "current_file") {
                val selected = try {
                    com.intellij.openapi.fileEditor.FileEditorManager
                        .getInstance(project)
                        .selectedFiles
                        .firstOrNull()
                } catch (_: Exception) { null }
                if (selected != null) {
                    val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(selected)
                    if (psiFile != null) return AnalysisScope(psiFile)
                    return AnalysisScope(project, listOf(selected).toSet())
                }
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
            }

            // 3) Fallback to whole project
            AnalysisScope(project)
        } catch (_: Exception) {
            AnalysisScope(project)
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
