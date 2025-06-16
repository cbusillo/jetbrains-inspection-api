package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import org.jetbrains.ide.HttpRequestHandler

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
                    val severity = urlDecoder.parameters()["severity"]?.firstOrNull() ?: "all"
                    val result = ReadAction.compute<String, Exception> {
                        getInspectionProblems(severity)
                    }
                    sendJsonResponse(context, result)
                }
                "/api/inspection/trigger" -> {
                    lastInspectionTriggerTime = System.currentTimeMillis()
                    inspectionInProgress = true
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        triggerInspectionAsync()
                    }
                    sendJsonResponse(context, """{"status": "triggered", "message": "Inspection triggered. Wait 10-15 seconds then call /api/inspection/problems"}""")
                }
                "/api/inspection/status" -> {
                    val result = ReadAction.compute<String, Exception> {
                        val project = getCurrentProject()
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
        } catch (e: Exception) {
            sendJsonResponse(context, """{"error": "Internal server error: ${e.message}"}""", HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return true
        }
    }
    
    private fun getInspectionProblems(severity: String = "all"): String {
        val project = getCurrentProject()
            ?: return """{"error": "No project found"}"""
        
        return try {
            val extractor = EnhancedTreeExtractor()
            val problems = extractor.extractAllProblems(project)
            
            if (problems.isNotEmpty()) {
                val filteredProblems = if (severity == "all") {
                    problems
                } else {
                    problems.filter { 
                        val problemSeverity = it["severity"] as String
                        severity == problemSeverity || 
                        (severity == "warning" && (problemSeverity == "grammar" || problemSeverity == "typo"))
                    }
                }
                
                val response = mapOf(
                    "status" to "results_available",
                    "project" to project.name,
                    "timestamp" to System.currentTimeMillis(),
                    "total_problems" to filteredProblems.size,
                    "problems_shown" to filteredProblems.size,
                    "problems" to filteredProblems,
                    "severity_filter" to severity,
                    "method" to "enhanced_tree"
                )
                
                formatJsonManually(response)
            } else {
                """{"status": "no_results", "message": "No inspection results found. Run 'Analyze → Inspect Code' first."}"""
            }
        } catch (e: Exception) {
            """{"error": "Failed to get inspection problems: ${e.message}"}"""
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
            
            formatJsonManually(status)
        } catch (e: Exception) {
            """{"error": "Failed to get status: ${e.message}"}"""
        }
    }
    
    private fun triggerInspectionAsync() {
        val project = getCurrentProject() ?: return
        
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
            
            val scope = AnalysisScope(project)
            
            val inspectionManager = InspectionManager.getInstance(project) as com.intellij.codeInspection.ex.InspectionManagerEx
            val profileManager = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project)
            val profile = profileManager.currentProfile
            
            @Suppress("UnstableApiUsage")
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
        } catch (e: Exception) {
            inspectionInProgress = false
        }
    }
    
    private fun getCurrentProject(): Project? {
        return try {
            val projectManager = ProjectManager.getInstance()
            val openProjects = projectManager.openProjects
            openProjects.firstOrNull { project ->
                !project.isDefault && !project.isDisposed && project.isInitialized
            }
        } catch (e: Exception) {
            null
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