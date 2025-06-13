package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import org.jetbrains.ide.HttpRequestHandler

class InspectionHandler : HttpRequestHandler() {
    
    override fun isSupported(request: FullHttpRequest): Boolean {
        return request.uri().startsWith("/api/inspection") && request.method() == HttpMethod.GET
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean {
        try {
            when {
                urlDecoder.path() == "/api/inspection/trigger" -> {
                    val scope = urlDecoder.parameters()["scope"]?.firstOrNull() ?: "whole_project"
                    triggerInspection(scope)
                    sendJsonResponse(context, """{"status": "triggered", "message": "Inspection started", "scope": "$scope"}""")
                }
                urlDecoder.path() == "/api/inspection/problems" -> {
                    // Smart endpoint that handles both status and results
                    var resultsJson = """{"status": "pending"}"""
                    try {
                        ApplicationManager.getApplication().invokeAndWait {
                            resultsJson = getInspectionProblems()
                        }
                    } catch (e: ProcessCanceledException) {
                        throw e // Must rethrow ProcessCanceledException
                    }
                    sendJsonResponse(context, resultsJson)
                }
                urlDecoder.path() == "/api/inspection/inspections" -> {
                    var resultsJson = """{"status": "pending"}"""
                    try {
                        ApplicationManager.getApplication().invokeAndWait {
                            resultsJson = getInspectionCategories()
                        }
                    } catch (e: ProcessCanceledException) {
                        throw e // Must rethrow ProcessCanceledException
                    }
                    sendJsonResponse(context, resultsJson)
                }
                else -> {
                    sendJsonResponse(context, """{"error": "Unknown endpoint"}""", HttpResponseStatus.NOT_FOUND)
                }
            }
            return true
        } catch (e: Exception) {
            sendJsonResponse(context, """{"error": "${e.message}"}""", HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return true
        }
    }
    
    private fun triggerInspection(scopeType: String) {
        val project = getCurrentProject() ?: return
        
        ApplicationManager.getApplication().invokeLater {
            try {
                val inspectionManager = InspectionManagerEx.getInstance(project) as InspectionManagerEx
                val context = inspectionManager.createNewGlobalContext() as GlobalInspectionContextEx
                
                val analysisScope = when (scopeType) {
                    "whole_project" -> AnalysisScope(project)
                    "uncommitted" -> AnalysisScope(project)
                    "custom" -> AnalysisScope(project)
                    else -> AnalysisScope(project)
                }
                
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        context.doInspections(analysisScope)
                    } catch (e: Exception) { }
                }
                
            } catch (e: Exception) { }
        }
    }
    
    private fun findInspectionView(project: Project): InspectionResultsView? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Problems View")
        if (toolWindow?.isAvailable != true) return null
        
        val contentManager = toolWindow.contentManager ?: return null
        
        for (i in 0 until contentManager.contentCount) {
            val content = contentManager.getContent(i)
            if (content?.component is InspectionResultsView) {
                return content.component as InspectionResultsView
            }
        }
        return null
    }
    
    private fun getInspectionProblems(): String {
        val project = getCurrentProject() ?: return """{"error": "No project found"}"""
        
        try {
            val inspectionView = findInspectionView(project)
            if (inspectionView == null) {
                return """
                {
                    "status": "no_inspection_run",
                    "project": "${project.name}",
                    "timestamp": "${System.currentTimeMillis()}",
                    "message": "No inspection has been run yet. Use /api/inspection/trigger first."
                }
                """.trimIndent()
            }
            
            if (inspectionView == null) {
                return """
                {
                    "status": "no_results_yet",
                    "project": "${project.name}",
                    "timestamp": "${System.currentTimeMillis()}",
                    "message": "Inspection may be running or no results available yet. Try again in a few seconds."
                }
                """.trimIndent()
            }
            val tree = inspectionView.tree
            val treeModel = tree?.model
            val rootNode = treeModel?.root
            val childCount = treeModel?.getChildCount(rootNode) ?: 0
            
            if (childCount == 0) {
                return """
                {
                    "status": "no_results_yet",
                    "project": "${project.name}",
                    "timestamp": "${System.currentTimeMillis()}",
                    "message": "Inspection running or no problems found yet. Try again in a few seconds."
                }
                """.trimIndent()
            }
            
            // Extract problems from the tree
            val problems = mutableListOf<Map<String, Any>>()
            var totalProblems = 0
            
            if (tree != null && treeModel != null && rootNode != null) {
                fun extractProblems(node: Any?, category: String = "", depth: Int = 0) {
                    if (node == null || depth > 10 || totalProblems >= 5000) return
                    
                    val nodeStr = node.toString()
                    val nodeChildCount = treeModel.getChildCount(node)
                    val nodeClass = node.javaClass.simpleName
                    
                    
                    // If this is an InspectionNode (actual problem), extract it
                    if (nodeClass == "InspectionNode" && nodeChildCount == 0) {
                        totalProblems++
                        if (problems.size < 100) { // Limit to 100 for response size
                            problems.add(mapOf(
                                "description" to nodeStr,
                                "category" to category,
                                "severity" to "warning" // Default severity
                            ))
                        }
                    } else if (nodeChildCount == 0 && depth > 2) {
                        // Try to capture leaf nodes that might be problems
                        totalProblems++
                        if (problems.size < 100) {
                            problems.add(mapOf(
                                "description" to nodeStr,
                                "category" to category,
                                "severity" to "warning"
                            ))
                        }
                    }
                    
                    // Recurse through children
                    val currentCategory = if (nodeClass == "InspectionGroupNode" || depth <= 1) nodeStr else category
                    for (i in 0 until nodeChildCount) {
                        try {
                            val child = treeModel.getChild(node, i)
                            extractProblems(child, currentCategory, depth + 1)
                        } catch (e: Exception) {
                            // Skip problematic nodes
                        }
                    }
                }
                
                // Extract problems from all root children
                for (i in 0 until childCount) {
                    try {
                        val child = treeModel.getChild(rootNode, i)
                        extractProblems(child, "", 1)
                    } catch (e: Exception) {
                        // Skip problematic nodes
                    }
                }
            }
            
            return """
            {
                "status": "results_available",
                "project": "${project.name}",
                "timestamp": "${System.currentTimeMillis()}",
                "total_problems": $totalProblems,
                "problems_shown": ${problems.size},
                "problems": ${formatJsonValue(problems)}
            }
            """.trimIndent()
            
        } catch (e: Exception) {
            return """{"error": "Failed to get problems: ${e.message}"}"""
        }
    }
    
    private fun getInspectionCategories(): String {
        val project = getCurrentProject() ?: return """{"error": "No project found"}"""
        
        try {
            val inspectionView = findInspectionView(project)
            if (inspectionView == null) {
                return """
                {
                    "status": "no_inspection_run",
                    "project": "${project.name}",
                    "timestamp": "${System.currentTimeMillis()}",
                    "message": "No inspection has been run yet."
                }
                """.trimIndent()
            }
            
            if (inspectionView != null) {
                val tree = inspectionView.tree
                val treeModel = tree?.model
                val rootNode = treeModel?.root
                val childCount = treeModel?.getChildCount(rootNode) ?: 0
                
                val categories = mutableListOf<Map<String, Any>>()
                
                // Get top-level categories
                for (i in 0 until childCount) {
                    try {
                        val child = treeModel?.getChild(rootNode, i)
                        val childCount2 = treeModel?.getChildCount(child) ?: 0
                        categories.add(mapOf(
                            "name" to child.toString(),
                            "problem_count" to childCount2
                        ))
                    } catch (e: Exception) {
                        // Skip problematic nodes
                    }
                }
                
                return """
                {
                    "status": "results_available",
                    "project": "${project.name}",
                    "timestamp": "${System.currentTimeMillis()}",
                    "categories": ${formatJsonValue(categories)}
                }
                """.trimIndent()
            } else {
                return """
                {
                    "status": "no_results_yet",
                    "project": "${project.name}",
                    "timestamp": "${System.currentTimeMillis()}",
                    "message": "Inspection results not ready yet."
                }
                """.trimIndent()
            }
            
        } catch (e: Exception) {
            return """{"error": "Failed to get categories: ${e.message}"}"""
        }
    }
    
    private fun getCurrentProject(): Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull { !it.isDefault }
    }
    
    private fun formatJsonValue(value: Any?): String {
        return when (value) {
            is String -> "\"$value\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            is List<*> -> value.joinToString(",", "[", "]") { formatJsonValue(it) }
            is Map<*, *> -> value.entries.joinToString(",\n      ", "{\n      ", "\n    }") { (k, v) ->
                """"$k": ${formatJsonValue(v)}"""
            }
            null -> "null"
            else -> "\"$value\""
        }
    }
    
    private fun sendJsonResponse(context: ChannelHandlerContext, json: String, status: HttpResponseStatus = HttpResponseStatus.OK) {
        val content = Unpooled.copiedBuffer(json, Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
        response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "application/json")
        response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, content.readableBytes())
        context.writeAndFlush(response)
    }
}