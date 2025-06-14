package com.shiny.inspectionmcp

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
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
            when (urlDecoder.path()) {
                "/api/inspection/problems" -> {
                    val scope = urlDecoder.parameters()["scope"]?.firstOrNull() ?: "whole_project"
                    val severity = urlDecoder.parameters()["severity"]?.firstOrNull() ?: "all"
                    val result = ReadAction.compute<String, Exception> {
                        getInspectionProblems(scope, severity)
                    }
                    sendJsonResponse(context, result)
                }
                "/api/inspection/inspections" -> {
                    val result = ReadAction.compute<String, Exception> {
                        getInspectionCategories()
                    }
                    sendJsonResponse(context, result)
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
    
    private fun processFile(
        virtualFile: VirtualFile,
        psiManager: PsiManager,
        documentManager: PsiDocumentManager,
        problems: MutableList<Map<String, Any>>,
        allowedSeverities: Set<HighlightSeverity>
    ) {
        try {
            val psiFile = psiManager.findFile(virtualFile)
            if (psiFile != null) {
                val document = documentManager.getDocument(psiFile)
                if (document != null) {
                    val highlightingProblems = extractHighlightingProblems(psiFile, document, allowedSeverities)
                    problems.addAll(highlightingProblems)
                }
            }
        } catch (_: Exception) {
        }
    }
    
    private fun getInspectionProblems(scope: String = "whole_project", severity: String = "all"): String {
        val project = getCurrentProject()
            ?: return """{"error": "No project found"}"""
        
        return try {
            val problems = mutableListOf<Map<String, Any>>()
            
            // Parse severity filter
            val allowedSeverities = when (severity.lowercase()) {
                "error" -> setOf(HighlightSeverity.ERROR)
                "warning" -> setOf(HighlightSeverity.ERROR, HighlightSeverity.WARNING)
                "weak_warning" -> setOf(HighlightSeverity.ERROR, HighlightSeverity.WARNING, HighlightSeverity.WEAK_WARNING)
                "info" -> setOf(HighlightSeverity.INFORMATION)
                "all" -> setOf(HighlightSeverity.ERROR, HighlightSeverity.WARNING, HighlightSeverity.WEAK_WARNING, HighlightSeverity.INFORMATION)
                else -> setOf(HighlightSeverity.ERROR, HighlightSeverity.WARNING, HighlightSeverity.WEAK_WARNING, HighlightSeverity.INFORMATION)
            }
            
            val psiManager = PsiManager.getInstance(project)
            val documentManager = PsiDocumentManager.getInstance(project)
            
            if (scope == "current_file") {
                val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                val selectedFiles = fileEditorManager.selectedFiles
                for (virtualFile in selectedFiles) {
                    if (virtualFile.isValid && !virtualFile.isDirectory) {
                        processFile(virtualFile, psiManager, documentManager, problems, allowedSeverities)
                    }
                }
            } else {
                val projectFileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
                projectFileIndex.iterateContent { virtualFile ->
                    if (virtualFile.isValid && !virtualFile.isDirectory) {
                        processFile(virtualFile, psiManager, documentManager, problems, allowedSeverities)
                    }
                    true
                }
            }
            
            val response = mapOf(
                "status" to "results_available",
                "project" to project.name,
                "timestamp" to System.currentTimeMillis(),
                "total_problems" to problems.size,
                "problems_shown" to problems.size,
                "problems" to problems.take(100),
                "scope" to scope,
                "severity_filter" to severity,
                "method" to "highlighting_api"
            )
            
            formatJsonManually(response)
        } catch (e: Exception) {
            """{"error": "Failed to get problems: ${e.message}"}"""
        }
    }
    
    private fun extractHighlightingProblems(psiFile: PsiFile, document: Document, allowedSeverities: Set<HighlightSeverity>): List<Map<String, Any>> {
        val problems = mutableListOf<Map<String, Any>>()
        
        try {
            @Suppress("UnstableApiUsage")
            val highlightInfos = com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl.getHighlights(
                document, HighlightSeverity.INFORMATION, psiFile.project
            )
            
            for (info in highlightInfos) {
                if (allowedSeverities.contains(info.severity)) {
                    
                    val startOffset = info.startOffset
                    val lineNumber = document.getLineNumber(startOffset) + 1
                    val columnNumber = startOffset - document.getLineStartOffset(document.getLineNumber(startOffset))
                    
                    val problem = mapOf(
                        "description" to (info.description ?: "Unknown issue").replace("\"", "\\\"").replace("\n", "\\n"),
                        "file" to psiFile.virtualFile.path,
                        "line" to lineNumber,
                        "column" to columnNumber,
                        "severity" to when (info.severity) {
                            HighlightSeverity.ERROR -> "error"
                            HighlightSeverity.WARNING -> "warning"
                            HighlightSeverity.WEAK_WARNING -> "weak_warning"
                            HighlightSeverity.INFORMATION -> "info"
                            else -> "info"
                        },
                        "category" to (info.inspectionToolId ?: "General"),
                        "source" to "highlighting_api"
                    )
                    problems.add(problem)
                }
            }
        } catch (_: Exception) {
        }
        
        return problems
    }
    
    private fun getInspectionCategories(): String {
        val project = getCurrentProject()
            ?: return """{"error": "No project found"}"""
        
        return try {
            val problemsResult = getInspectionProblems()
            
            val categoryMap = mutableMapOf<String, Int>()
            
            val categoryRegex = """"category":\s*"([^"]+)"""".toRegex()
            val matches = categoryRegex.findAll(problemsResult)
            
            for (match in matches) {
                val category = match.groupValues[1]
                categoryMap[category] = categoryMap.getOrDefault(category, 0) + 1
            }
            
            val categories = categoryMap.map { (name, count) ->
                mapOf("name" to name, "problem_count" to count)
            }
            
            val response = mapOf(
                "status" to "results_available",
                "project" to project.name,
                "timestamp" to System.currentTimeMillis(),
                "categories" to categories
            )
            
            formatJsonManually(response)
        } catch (e: Exception) {
            """{"error": "Failed to get categories: ${e.message}"}"""
        }
    }
    
    private fun getCurrentProject(): Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull { !it.isDefault }
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
        status: HttpResponseStatus = HttpResponseStatus.OK
    ) {
        val content = Unpooled.copiedBuffer(jsonContent, Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        context.writeAndFlush(response)
    }
}