package com.shiny.inspectionmcp

import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.codeInspection.ui.InspectionTree
import com.intellij.codeInspection.ui.ProblemDescriptionNode
import com.intellij.codeInspection.ui.InspectionNode
import com.intellij.codeInspection.ui.InspectionTreeNode
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.injected.editor.DocumentWindow
import com.intellij.injected.editor.VirtualFileWindow
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode
import java.awt.Component
import java.awt.Container
import javax.swing.JTree

class EnhancedTreeExtractor {

    fun extractAllProblemsFromInspectionView(view: InspectionResultsView, project: Project): List<Map<String, Any>> {
        val problems = mutableListOf<Map<String, Any>>()
        try {
            extractProblemsFromView(view, problems, project)
        } catch (_: Exception) {
        }
        return problems
    }
    
    fun extractAllProblems(project: Project): List<Map<String, Any>> {
        val problems = mutableListOf<Map<String, Any>>()
        
        try {
            val toolWindowManager = ToolWindowManager.getInstance(project)

            val seen = LinkedHashSet<String>()

            val inspectionWindows = findToolWindowsContainingInspectionResults(toolWindowManager)
            if (inspectionWindows.isNotEmpty()) {
                for (tw in inspectionWindows) {
                    extractFromToolWindow(tw, problems, project, seen)
                }
                return problems
            }

            // Fallback: when no Inspect Code results exist, scrape the Problems tool window.
            // JetBrains 2025.3 renamed "Problems View" to "Problems" in some IDEs; include both.
            val fallbackNames = listOf("Problems View", "Problems", "Inspections")
            val fallback = fallbackNames.firstNotNullOfOrNull { name -> toolWindowManager.getToolWindow(name) }
                ?: return problems
            extractFromToolWindow(fallback, problems, project, seen)
            
        } catch (e: Exception) {
        }
        
        return problems
    }

    private fun findToolWindowsContainingInspectionResults(toolWindowManager: ToolWindowManager): List<ToolWindow> {
        val ids = try {
            toolWindowManager.toolWindowIds.toList()
        } catch (_: Exception) {
            emptyList()
        }
        if (ids.isEmpty()) {
            val direct = toolWindowManager.getToolWindow("Inspection Results")
            return if (direct != null) listOf(direct) else emptyList()
        }

        val matches = mutableListOf<ToolWindow>()
        for (id in ids) {
            val toolWindow = toolWindowManager.getToolWindow(id) ?: continue
            if (toolWindowHasInspectionResults(toolWindow)) {
                matches.add(toolWindow)
            }
        }
        return matches
    }

    private fun toolWindowHasInspectionResults(toolWindow: ToolWindow): Boolean {
        try {
            for (i in 0 until toolWindow.contentManager.contentCount) {
                val content = toolWindow.contentManager.getContent(i) ?: continue
                val component = content.component
                if (findInspectionResultsView(component) != null) {
                    return true
                }
            }
        } catch (_: Exception) {
            return false
        }
        return false
    }

    private fun extractFromToolWindow(
        toolWindow: ToolWindow,
        problems: MutableList<Map<String, Any>>,
        project: Project,
        seen: MutableSet<String>,
    ) {
        for (i in 0 until toolWindow.contentManager.contentCount) {
            val content = toolWindow.contentManager.getContent(i) ?: continue
            val component = content.component

            val before = problems.size
            val inspectionView = findInspectionResultsView(component)
            if (inspectionView != null) {
                extractProblemsFromView(inspectionView, problems, project)
            } else {
                val tree = findInspectionTree(component)
                if (tree != null) {
                    extractProblemsFromTree(tree, problems, project)
                }
            }

            if (problems.size != before) {
                val newSlice = problems.subList(before, problems.size)
                val deduped = newSlice.filter { p ->
                    val key = listOf(
                        p["severity"],
                        p["inspectionType"],
                        p["file"],
                        p["line"],
                        p["column"],
                        p["description"],
                    ).joinToString("|")
                    seen.add(key)
                }
                newSlice.clear()
                problems.addAll(deduped)
            }
        }
    }
    
    private fun findInspectionResultsView(component: Component): InspectionResultsView? {
        if (component is InspectionResultsView) {
            return component
        }
        
        if (component is Container) {
            for (child in component.components) {
                val result = findInspectionResultsView(child)
                if (result != null) {
                    return result
                }
            }
        }
        
        return null
    }
    
    private fun findInspectionTree(component: Component): JTree? {
        if (component is InspectionTree) {
            return component
        }
        
        if (component is JTree) {
            return component
        }
        
        if (component is Container) {
            for (child in component.components) {
                val result = findInspectionTree(child)
                if (result != null) {
                    return result
                }
            }
        }
        
        return null
    }
    
    private fun extractProblemsFromView(view: InspectionResultsView, problems: MutableList<Map<String, Any>>, project: Project) {
        try {
            val tree = view.tree
            extractProblemsFromTree(tree, problems, project)
        } catch (e: Exception) {
        }
    }
    
    private fun extractProblemsFromTree(tree: JTree, problems: MutableList<Map<String, Any>>, project: Project) {
        try {
            val model = tree.model
            val root = model.root
            
            
            walkNode(root, problems, project, 0)
            
        } catch (e: Exception) {
        }
    }
    
    private fun walkNode(node: Any?, problems: MutableList<Map<String, Any>>, project: Project, depth: Int) {
        if (node == null) return
        
        try {
            when (node) {
                is ProblemDescriptionNode -> {
                    extractProblemFromNode(node, problems, project)
                }
                
                is InspectionTreeNode -> {
                    
                    for (i in 0 until node.childCount) {
                        val child = node.getChildAt(i)
                        walkNode(child, problems, project, depth + 1)
                    }
                }
                
                is DefaultMutableTreeNode -> {
                    extractProblemFromUserObject(node, problems)
                    for (i in 0 until node.childCount) {
                        val child = node.getChildAt(i)
                        walkNode(child, problems, project, depth + 1)
                    }
                }
                
                is TreeNode -> {
                    for (i in 0 until node.childCount) {
                        val child = node.getChildAt(i)
                        walkNode(child, problems, project, depth + 1)
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun extractProblemFromUserObject(
        node: DefaultMutableTreeNode,
        problems: MutableList<Map<String, Any>>,
    ) {
        val userObject = node.userObject ?: return
        if (userObject is ProblemDescriptionNode || userObject is InspectionNode || userObject is InspectionTreeNode) {
            return
        }

        val candidate = tryCall(userObject, "getProblem") ?: userObject
        val description = callString(
            candidate,
            listOf("getDescription", "getText", "getMessage", "getTitle")
        ) ?: return

        val location = tryCall(candidate, "getLocation") ?: tryCall(candidate, "getProblemLocation")
        val fileObj = tryCall(location, "getVirtualFile")
            ?: tryCall(location, "getFile")
            ?: tryCall(location, "getFilePath")
            ?: tryCall(candidate, "getVirtualFile")
            ?: tryCall(candidate, "getFile")
            ?: tryCall(candidate, "getFilePath")

        val virtualFile = resolveVirtualFile(fileObj)
        val filePath = when (fileObj) {
            is VirtualFile -> fileObj.path
            is java.nio.file.Path -> fileObj.toString()
            is String -> fileObj
            else -> virtualFile?.path
        } ?: return

        val rangeObj = tryCall(location, "getTextRange")
            ?: tryCall(location, "getRange")
            ?: tryCall(candidate, "getTextRange")
            ?: tryCall(candidate, "getRange")

        val (line, column) = resolveLineColumn(virtualFile, rangeObj)
        val severity = normalizeSeverity(tryCall(candidate, "getSeverity") ?: tryCall(candidate, "getHighlightSeverity"))
        val category = callString(candidate, listOf("getCategory", "getGroup", "getCategoryName")) ?: "General"
        val inspectionType = callString(candidate, listOf("getInspectionToolId", "getShortName", "getName"))
            ?: candidate.javaClass.simpleName

        problems.add(
            mapOf(
                "description" to description,
                "file" to filePath,
                "line" to line,
                "column" to column,
                "severity" to severity,
                "category" to category,
                "inspectionType" to inspectionType,
                "source" to "problems_view",
            )
        )
    }

    private fun resolveVirtualFile(fileObj: Any?): VirtualFile? {
        return when (fileObj) {
            is VirtualFile -> fileObj
            is java.nio.file.Path -> LocalFileSystem.getInstance().findFileByPath(fileObj.toString())
            is String -> LocalFileSystem.getInstance().findFileByPath(fileObj)
            else -> null
        }
    }

    private fun resolveLineColumn(
        virtualFile: VirtualFile?,
        rangeObj: Any?
    ): Pair<Int, Int> {
        if (virtualFile == null || rangeObj == null) {
            return 0 to 0
        }
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return 0 to 0
        val startOffset = when (rangeObj) {
            is TextRange -> rangeObj.startOffset
            else -> when (val raw = tryCall(rangeObj, "getStartOffset")) {
                is Int -> raw
                is Number -> raw.toInt()
                else -> null
            }
        } ?: return 0 to 0
        val line = document.getLineNumber(startOffset) + 1
        val column = startOffset - document.getLineStartOffset(line - 1)
        return line to column
    }

    private fun normalizeSeverity(raw: Any?): String {
        val severity = raw?.toString()?.lowercase() ?: return "warning"
        return when {
            severity.contains("error") -> "error"
            severity.contains("weak") -> "weak_warning"
            severity.contains("warning") -> "warning"
            severity.contains("info") -> "info"
            else -> "warning"
        }
    }

    private fun tryCall(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            method?.invoke(target)
        } catch (_: Exception) {
            null
        }
    }

    private fun callString(target: Any?, names: List<String>): String? {
        for (name in names) {
            val value = tryCall(target, name)
            if (value != null) {
                val text = value.toString().trim()
                if (text.isNotBlank()) {
                    return text
                }
            }
        }
        return null
    }
    
    private fun extractProblemFromNode(node: ProblemDescriptionNode, problems: MutableList<Map<String, Any>>, project: Project) {
        try {
            val descriptor = node.descriptor
            
            if (descriptor != null) {
                val description = descriptor.descriptionTemplate
                
                var file = "unknown"
                var line = 0
                var column = 0
                var severity = "warning"
                var category: String
                var inspectionType: String
                
                if (descriptor is ProblemDescriptor) {
                    val element = descriptor.psiElement
                    if (element != null && element.isValid) {
                        val containingFile = element.containingFile
                        val virtualFile = containingFile?.virtualFile

                        if (virtualFile != null) {
                            val documentManager = PsiDocumentManager.getInstance(project)
                            val document = documentManager.getDocument(containingFile)
                            val textRange = element.textRange
                            if (virtualFile is VirtualFileWindow && document is DocumentWindow) {
                                val hostFile = virtualFile.delegate
                                val hostPsi = InjectedLanguageManager.getInstance(project).getTopLevelFile(containingFile)
                                val hostDocument = hostPsi?.let { psi -> documentManager.getDocument(psi) }
                                val hostOffset = document.injectedToHost(textRange.startOffset)
                                file = hostFile.path
                                if (hostDocument != null) {
                                    line = hostDocument.getLineNumber(hostOffset) + 1
                                    column = hostOffset - hostDocument.getLineStartOffset(line - 1)
                                } else {
                                    line = document.getLineNumber(textRange.startOffset) + 1
                                    column = textRange.startOffset - document.getLineStartOffset(line - 1)
                                }
                            } else {
                                file = virtualFile.path
                                if (document != null) {
                                    line = document.getLineNumber(textRange.startOffset) + 1
                                    column = textRange.startOffset - document.getLineStartOffset(line - 1)
                                }
                            }
                        }
                        
                        severity = when (descriptor.highlightType) {
                            ProblemHighlightType.ERROR -> "error"
                            ProblemHighlightType.WARNING -> "warning"
                            ProblemHighlightType.WEAK_WARNING -> "weak_warning"
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "warning"
                            ProblemHighlightType.LIKE_UNKNOWN_SYMBOL -> "error"
                            ProblemHighlightType.LIKE_DEPRECATED -> "weak_warning"
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL -> "weak_warning"
                            ProblemHighlightType.GENERIC_ERROR -> "error"
                            else -> "info"
                        }
                    }
                }
                
                val parent = node.parent
                var inspectionNode: InspectionNode? = null
                
                var currentNode: Any? = parent
                while (currentNode != null && inspectionNode == null) {
                    if (currentNode is InspectionNode) {
                        inspectionNode = currentNode
                    } else {
                        currentNode = try {
                            currentNode.javaClass.getMethod("getParent").invoke(currentNode)
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                
                if (inspectionNode != null) {
                    val parentStr = inspectionNode.toString()
                    
                    when {
                        parentStr.contains("GrazieInspection", ignoreCase = true) -> {
                            category = "Grammar"
                            inspectionType = "GrazieInspection"
                        }
                        parentStr.contains("Grazie", ignoreCase = true) -> {
                            category = "Grammar"
                            inspectionType = "GrazieInspection"
                        }
                        parentStr.contains("Grammar", ignoreCase = true) -> {
                            category = "Grammar"
                            inspectionType = "GrazieInspection"
                        }
                        parentStr.contains("SpellCheckingInspection", ignoreCase = true) -> {
                            category = "Typo"
                            inspectionType = "SpellCheckingInspection"
                        }
                        parentStr.contains("SpellCheck", ignoreCase = true) -> {
                            category = "Typo"
                            inspectionType = "SpellCheckingInspection"
                        }
                        parentStr.contains("Typo", ignoreCase = true) -> {
                            category = "Typo"
                            inspectionType = "SpellCheckingInspection"
                        }
                        parentStr.contains("ShellCheck", ignoreCase = true) -> {
                            category = "Shell Script"
                            inspectionType = "ShellCheck"
                        }
                        parentStr.contains("DuplicatedCode", ignoreCase = true) -> {
                            category = "General"
                            inspectionType = "DuplicatedCode"
                        }
                        else -> {
                            try {
                                val toolWrapperField = inspectionNode.javaClass.getDeclaredField("myToolWrapper")
                                toolWrapperField.isAccessible = true
                                val toolWrapper = toolWrapperField.get(inspectionNode)
                                
                                if (toolWrapper != null) {
                                    val toolWrapperClass = toolWrapper.javaClass
                                    
                                    val displayNameMethod = toolWrapperClass.getMethod("getDisplayName")
                                    val groupNameMethod = toolWrapperClass.getMethod("getGroupDisplayName")
                                    val shortNameMethod = toolWrapperClass.getMethod("getShortName")
                                    
                                    val displayName = displayNameMethod.invoke(toolWrapper)?.toString() ?: ""
                                    val groupName = groupNameMethod.invoke(toolWrapper)?.toString() ?: ""
                                    val toolId = shortNameMethod.invoke(toolWrapper)?.toString() ?: ""
                                    
                                    category = when {
                                        groupName.isNotEmpty() -> groupName
                                        displayName.isNotEmpty() -> displayName
                                        else -> "General"
                                    }
                                    
                                    inspectionType = toolId.ifEmpty {
                                        val match = Regex("(\\w+Inspection|\\w+Check)").find(parentStr)
                                        match?.value ?: "unknown"
                                    }
                                } else {
                                    category = "General"
                                    val match = Regex("(\\w+Inspection|\\w+Check)").find(parentStr)
                                    inspectionType = match?.value ?: "unknown"
                                }
                            } catch (e: Exception) {
                                category = "General"
                                val match = Regex("(\\w+Inspection|\\w+Check)").find(parentStr)
                                inspectionType = match?.value ?: "unknown"
                            }
                        }
                    }
                } else {
                    category = "General"
                    inspectionType = "unknown"
                }
                
                try {
                        val levelField = node.javaClass.getDeclaredField("myLevel")
                        levelField.isAccessible = true
                        val levelObj = levelField.get(node)
                        
                        if (levelObj != null) {
                            val levelName = levelObj.toString()
                            
                            severity = when (inspectionType) {
                                "GrazieInspection" -> "grammar"
                                "SpellCheckingInspection" -> "typo"
                                "AiaStyle" -> "typo"
                                else -> when {
                                levelName.equals("ERROR", ignoreCase = true) -> "error"
                                levelName.equals("WARNING", ignoreCase = true) -> "warning"
                                levelName.equals("WEAK WARNING", ignoreCase = true) -> "weak_warning"
                                levelName.equals("WEAK_WARNING", ignoreCase = true) -> "weak_warning"
                                levelName.equals("INFO", ignoreCase = true) -> "info"
                                levelName.equals("INFORMATION", ignoreCase = true) -> "info"
                                else -> {
                                    try {
                                        val nameMethod = levelObj.javaClass.getMethod("getName")
                                        val name = nameMethod.invoke(levelObj)?.toString() ?: ""
                                        when {
                                            name.equals("ERROR", ignoreCase = true) -> "error"
                                            name.equals("WARNING", ignoreCase = true) -> "warning"
                                            name.contains("WEAK", ignoreCase = true) -> "weak_warning"
                                            name.equals("INFORMATION", ignoreCase = true) -> "info"
                                            name.equals("INFO", ignoreCase = true) -> "info"
                                            else -> severity
                                        }
                                    } catch (e: Exception) {
                                        severity
                                    }
                                }
                            }
                            }
                        }
                } catch (e: Exception) {
                }
                
                val problemMap = mapOf(
                    "description" to description,
                    "file" to file,
                    "line" to line,
                    "column" to column,
                    "severity" to severity,
                    "category" to category,
                    "inspectionType" to inspectionType,
                    "source" to "enhanced_tree_extractor"
                )
                
                problems.add(problemMap)
            }
        } catch (e: Exception) {
        }
    }
}
