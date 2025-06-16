package com.shiny.inspectionmcp

import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.codeInspection.ui.InspectionTree
import com.intellij.codeInspection.ui.ProblemDescriptionNode
import com.intellij.codeInspection.ui.InspectionNode
import com.intellij.codeInspection.ui.InspectionTreeNode
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode
import java.awt.Component
import java.awt.Container
import javax.swing.JTree

class EnhancedTreeExtractor {
    
    fun extractAllProblems(project: Project): List<Map<String, Any>> {
        val problems = mutableListOf<Map<String, Any>>()
        
        try {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val windowNames = listOf("Inspection Results", "Problems View", "Inspections")
            val toolWindow = windowNames.firstNotNullOfOrNull { windowName ->
                toolWindowManager.getToolWindow(windowName)
            }
            
            if (toolWindow == null) {
                return problems
            }
            
            
            for (i in 0 until toolWindow.contentManager.contentCount) {
                val content = toolWindow.contentManager.getContent(i)
                if (content != null) {
                    val component = content.component
                    
                    
                    val inspectionView = findInspectionResultsView(component)
                    if (inspectionView != null) {
                        extractProblemsFromView(inspectionView, problems, project)
                    } else {
                        val tree = findInspectionTree(component)
                        if (tree != null) {
                            extractProblemsFromTree(tree, problems, project)
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
        }
        
        return problems
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
                            file = virtualFile.path
                            
                            val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
                            if (document != null) {
                                val textRange = element.textRange
                                line = document.getLineNumber(textRange.startOffset) + 1
                                column = textRange.startOffset - document.getLineStartOffset(line - 1)
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