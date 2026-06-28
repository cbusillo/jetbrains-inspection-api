package com.shiny.inspectionmcp

import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.codeInspection.ui.ProblemDescriptionNode
import com.intellij.codeInspection.ui.InspectionNode
import com.intellij.codeInspection.ui.InspectionTreeNode
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.ContentManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode
import java.awt.Component
import java.awt.Container
import java.io.File
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JTree

class EnhancedTreeExtractor {

    private companion object {
        private val noMethod = Any()
        private val zeroArgMethodCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Any>>()
    }

    private fun getZeroArgMethod(targetClass: Class<*>, name: String): Method? {
        val perClassCache = zeroArgMethodCache.computeIfAbsent(targetClass) { ConcurrentHashMap() }
        val cached = perClassCache[name]
        if (cached != null) {
            return if (cached === noMethod) null else cached as Method
        }

        val resolved = try {
            targetClass.getMethod(name)
        } catch (_: Exception) {
            null
        }
        perClassCache[name] = resolved ?: noMethod
        return resolved
    }

    fun extractAllProblemsFromInspectionView(view: InspectionResultsView, project: Project): List<Map<String, Any>> {
        val problems = mutableListOf<Map<String, Any>>()
        try {
            extractProblemsFromView(view, problems, project)
        } catch (_: Exception) {
        }
        return dedupeProblems(problems)
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
                if (problems.isNotEmpty()) {
                    return problems
                }
            }

            // Fallback: when no Inspect Code results exist, scrape the Problems tool window.
            val fallback = inspectionFallbackToolWindowIds.firstNotNullOfOrNull { name ->
                toolWindowManager.getToolWindow(name)
            }
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
            val direct = toolWindowManager.getToolWindow(inspectionResultsToolWindowIds.first())
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
            val contentManager = getContentManager(toolWindow) ?: return false
            for (i in 0 until contentManager.contentCount) {
                val content = contentManager.getContent(i) ?: continue
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
        val contentManager = getContentManager(toolWindow) ?: return
        for (i in 0 until contentManager.contentCount) {
            val content = contentManager.getContent(i) ?: continue
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
                val deduped = newSlice.filter { p -> seen.add(problemDedupKey(p)) }
                newSlice.clear()
                problems.addAll(deduped)
            }
        }
    }

    internal fun dedupeProblems(problems: List<Map<String, Any>>): List<Map<String, Any>> {
        val seen = LinkedHashSet<String>()
        return problems.filter { problem -> seen.add(problemDedupKey(problem)) }
    }

    private fun problemDedupKey(problem: Map<String, Any>): String {
        return listOf(
            problem["severity"],
            problem["inspectionType"],
            problem["file"],
            problem["line"],
            problem["column"],
            problem["description"],
        ).joinToString("|")
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

    private fun getContentManager(toolWindow: ToolWindow): ContentManager? {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            return try {
                toolWindow.contentManager
            } catch (_: Exception) {
                null
            }
        }
        return getContentManagerIfCreated(toolWindow)
    }

    private fun getContentManagerIfCreated(toolWindow: ToolWindow): ContentManager? {
        return try {
            val method = getZeroArgMethod(toolWindow.javaClass, "getContentManagerIfCreated") ?: return null
            method.invoke(toolWindow) as? ContentManager
        } catch (_: Exception) {
            null
        }
    }
    
    private fun findInspectionTree(component: Component): JTree? {
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
            val tree = inspectionResultsTree(view) ?: return
            extractProblemsFromTree(tree, problems, project)
        } catch (e: Exception) {
        }
    }

    private fun inspectionResultsTree(view: InspectionResultsView): JTree? {
        return try {
            getZeroArgMethod(view.javaClass, "getTree")?.invoke(view) as? JTree
        } catch (_: Exception) {
            null
        } ?: findInspectionTree(view)
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
                    extractFallbackProblemFromTreeNode(node, problems, project)
                }
                
                is DefaultMutableTreeNode -> {
                    extractProblemFromUserObject(node, problems)
                    for (i in 0 until node.childCount) {
                        val child = node.getChildAt(i)
                        walkNode(child, problems, project, depth + 1)
                    }
                    extractFallbackProblemFromTreeNode(node, problems, project)
                }
                
                is TreeNode -> {
                    for (i in 0 until node.childCount) {
                        val child = node.getChildAt(i)
                        walkNode(child, problems, project, depth + 1)
                    }
                    extractFallbackProblemFromTreeNode(node, problems, project)
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
        val description = normalizeProblemDescription(callString(
            candidate,
            listOf("getDescription", "getText", "getMessage", "getTitle")
        ) ?: return)

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

    private fun extractFallbackProblemFromTreeNode(
        node: TreeNode,
        problems: MutableList<Map<String, Any>>,
        project: Project,
    ) {
        if (!shouldAddFallbackProblem(node)) {
            return
        }

        val description = normalizeProblemDescription(treeNodeText(node))
        if (description.isBlank() || isGenericInspectionTreeText(description)) {
            return
        }

        val inspectionType = nearestInspectionType(node)
        val filePath = nearestTreeFilePath(node, project)
        val severity = severityFromTreeText(description)
        problems.add(
            mapOf(
                "description" to description,
                "file" to filePath,
                "line" to 0,
                "column" to 0,
                "severity" to severity,
                "category" to nearestInspectionCategory(node, inspectionType),
                "inspectionType" to inspectionType,
                "source" to "inspection_tree_fallback",
                "locationKnown" to false,
                "locationNote" to if (filePath != "unknown") {
                    "Inspection result line unavailable from IDE tree; open Inspection Results for the exact line."
                } else {
                    "Inspection result location unavailable from IDE tree; open Inspection Results for the exact file and line."
                },
            )
        )
    }

    private fun shouldAddFallbackProblem(node: TreeNode): Boolean {
        if (node.childCount > 0) {
            return false
        }
        val userObject = (node as? DefaultMutableTreeNode)?.userObject
        if (userObject is ProblemDescriptionNode || userObject is InspectionNode || userObject is InspectionTreeNode) {
            return false
        }
        return hasInspectionAncestor(node) && looksLikeInspectionProblemText(treeNodeText(node))
    }

    private fun hasInspectionAncestor(node: TreeNode): Boolean {
        var current = node.parent
        while (current != null) {
            if (current is InspectionNode || current is InspectionTreeNode) {
                return true
            }
            val text = current.toString()
            if (looksLikeInspectionCategoryText(text)) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun treeNodeText(node: TreeNode): String {
        return ((node as? DefaultMutableTreeNode)?.userObject ?: node).toString()
    }

    private fun treePath(node: TreeNode): List<TreeNode> {
        val path = ArrayDeque<TreeNode>()
        var current: TreeNode? = node
        while (current != null) {
            path.addFirst(current)
            current = current.parent
        }
        return path.toList()
    }

    private fun nearestInspectionType(node: TreeNode): String {
        var current: TreeNode? = node.parent
        while (current != null) {
            val text = current.toString().trim()
            inspectionTypeNameFromText(text)?.let { return it }
            current = current.parent
        }
        inspectionTypeFromText(node.toString().trim())?.let { return it }
        return "InspectionTreeFallback"
    }

    private fun nearestInspectionCategory(node: TreeNode, inspectionType: String): String {
        var current: TreeNode? = node.parent
        while (current != null) {
            val text = current.toString().trim()
            if (text.isNotBlank() && !isGenericInspectionTreeText(text) && inspectionTypeNameFromText(text) == null) {
                return text.take(80)
            }
            current = current.parent
        }
        return if (inspectionType == "InspectionTreeFallback") "General" else inspectionType
    }

    private fun nearestTreeFilePath(node: TreeNode, project: Project): String {
        var current: TreeNode? = node
        while (current != null) {
            filePathFromTreeText(current.toString(), project)?.let { return it }
            current = current.parent
        }
        return "unknown"
    }

    private fun filePathFromTreeText(text: String, project: Project): String? {
        val normalized = text.trim()
        if (normalized.isBlank()) return null
        val fileMatch = Regex("(?:^|\\s)([^\\s:]+\\.(?:kt|kts|java|py|js|jsx|ts|tsx|swift|go|rs|rb|php|xml|json|ya?ml|sh|bash|zsh))(?:[:\\s]|$)")
            .find(normalized)
        val fileText = fileMatch?.groupValues?.getOrNull(1)?.trim('"', '\'', ',', ';', ')', ']') ?: return null
        return resolveTreeFilePath(fileText, project)
    }

    private fun resolveTreeFilePath(fileText: String, project: Project): String {
        val rawPath = runCatching { Paths.get(fileText) }.getOrNull()
        if (rawPath != null && rawPath.isAbsolute) {
            return rawPath.normalize().toString()
        }
        val basePath = project.basePath ?: return fileText
        if (rawPath != null && fileText.contains('/')) {
            return Paths.get(basePath, fileText).normalize().toString()
        }
        return findProjectFileByName(basePath, fileText) ?: fileText
    }

    private fun findProjectFileByName(basePath: String, fileName: String): String? {
        val base = runCatching { Paths.get(basePath) }.getOrNull() ?: return null
        return try {
            Files.walk(base).use { stream ->
                stream
                    .filter { path -> Files.isRegularFile(path) && path.fileName?.toString() == fileName }
                    .findFirst()
                    .map(Path::toString)
                    .orElse(null)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun inspectionTypeFromText(text: String): String? {
        inspectionTypeNameFromText(text)?.let { return it }
        if (text.isBlank()) return null
        return when {
            text.contains("Unresolved", ignoreCase = true) -> "UnresolvedReference"
            text.contains("Cannot resolve", ignoreCase = true) -> "UnresolvedReference"
            text.contains("Symbol", ignoreCase = true) -> "UnresolvedReference"
            text.contains("Typo", ignoreCase = true) -> "SpellCheckingInspection"
            text.contains("Grammar", ignoreCase = true) -> "GrazieInspection"
            else -> null
        }
    }

    private fun inspectionTypeNameFromText(text: String): String? {
        if (text.isBlank()) return null
        return Regex("\\b([A-Za-z][A-Za-z0-9_]*(?:Inspection|Check))\\b").find(text)?.groupValues?.getOrNull(1)
    }

    private fun looksLikeInspectionProblemText(text: String): Boolean {
        val normalized = text.trim()
        return normalized.contains("Cannot resolve", ignoreCase = true) ||
            normalized.contains("Unresolved", ignoreCase = true) ||
            normalized.contains("never used", ignoreCase = true) ||
            normalized.contains("deprecated", ignoreCase = true) ||
            normalized.contains("is missing", ignoreCase = true) ||
            normalized.contains("Missing ", ignoreCase = false) ||
            normalized.contains("Unknown ", ignoreCase = false)
    }

    private fun looksLikeInspectionCategoryText(text: String): Boolean {
        val normalized = text.trim()
        return inspectionTypeFromText(normalized) != null ||
            inspectionTypeNameFromText(normalized) != null ||
            normalized.contains("probable bugs", ignoreCase = true) ||
            normalized.contains("compiler", ignoreCase = true) ||
            normalized.contains("declaration redundancy", ignoreCase = true)
    }

    private fun isGenericInspectionTreeText(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.isBlank() ||
            normalized == "root" ||
            normalized == "inspection results" ||
            normalized == "problems" ||
            normalized == "empty"
    }

    private fun severityFromTreeText(text: String): String {
        return when {
            text.contains("error", ignoreCase = true) -> "error"
            text.contains("warning", ignoreCase = true) -> "warning"
            text.contains("weak", ignoreCase = true) -> "weak_warning"
            text.contains("info", ignoreCase = true) -> "info"
            else -> "warning"
        }
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
        val safeOffset = clampDocumentOffset(startOffset, document.textLength)
        val line = document.getLineNumber(safeOffset) + 1
        val column = safeOffset - document.getLineStartOffset(line - 1)
        return line to column
    }

    internal fun clampDocumentOffset(offset: Int, textLength: Int): Int {
        return offset.coerceIn(0, textLength.coerceAtLeast(0))
    }

    internal fun yamlBlockScalarTypoLocations(
        lines: List<String>,
        startLine: Int,
        word: String,
    ): List<Pair<Int, Int>> {
        val headerIndex = startLine - 1
        if (headerIndex !in lines.indices || word.isEmpty()) return emptyList()

        val header = lines[headerIndex]
        val headerIndent = header.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return emptyList()
        if (!Regex(":\\s*[|>][-+]?\\s*(?:#.*)?$").containsMatchIn(header)) {
            return emptyList()
        }

        val locations = mutableListOf<Pair<Int, Int>>()
        for (index in (headerIndex + 1) until lines.size) {
            val line = lines[index]
            if (line.isNotBlank()) {
                val indent = line.indexOfFirst { !it.isWhitespace() }
                if (indent <= headerIndent) break
            }

            var searchFrom = 0
            while (searchFrom <= line.length) {
                val column = line.indexOf(word, searchFrom)
                if (column < 0) break
                locations.add((index + 1) to column)
                searchFrom = column + word.length
            }
        }
        return locations
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
            val method = getZeroArgMethod(target.javaClass, name) ?: return null
            method.invoke(target)
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

    private fun relocateYamlBlockScalarTypo(
        problems: List<Map<String, Any>>,
        file: String,
        line: Int,
        description: String,
    ): Pair<Int, Int>? {
        if (line <= 0 || file == "unknown" || !(file.endsWith(".yml") || file.endsWith(".yaml"))) {
            return null
        }

        val word = typoWord(description) ?: return null
        val lines = try {
            File(file).readLines()
        } catch (_: Exception) {
            return null
        }
        val locations = yamlBlockScalarTypoLocations(lines, line, word)
        if (locations.isEmpty()) return null

        val locationSet = locations.toSet()
        val duplicateOrdinal = problems.count { problem ->
            problem["file"] == file &&
                problem["description"] == description &&
                (problem["line"] == line || locationSet.contains(problem["line"] to problem["column"]))
        }
        return locations.getOrNull(duplicateOrdinal)
    }

    private fun typoWord(description: String): String? {
        return Regex("(?i)\\bword '([^']+)'").find(description)?.groupValues?.getOrNull(1)
    }
    
    private fun extractProblemFromNode(node: ProblemDescriptionNode, problems: MutableList<Map<String, Any>>, project: Project) {
        try {
            val descriptor = node.descriptor
            
            if (descriptor != null) {
                val description = normalizeProblemDescription(
                    descriptor.descriptionTemplate,
                    (descriptor as? ProblemDescriptor)?.let(::problemDescriptorRefText),
                )
                
                var file = "unknown"
                var line = 0
                var column = 0
                var severity = "warning"
                var category: String
                var inspectionType: String
                
                if (descriptor is ProblemDescriptor) {
                    val location = resolveProblemLocation(descriptor, project)
                    if (location != null) {
                        file = location.filePath
                        line = location.line
                        column = location.column
                    }
                    severity = severityFromHighlightType(descriptor.highlightType)
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

                if (inspectionType == "GrazieInspection") {
                    relocateYamlBlockScalarTypo(problems, file, line, description)?.let { adjusted ->
                        line = adjusted.first
                        column = adjusted.second
                    }
                }
                
                val locationKnown = file != "unknown" && line > 0
                val problemMap = mutableMapOf<String, Any>(
                    "description" to description,
                    "file" to file,
                    "line" to line,
                    "column" to column,
                    "severity" to severity,
                    "category" to category,
                    "inspectionType" to inspectionType,
                    "source" to "enhanced_tree_extractor",
                    "locationKnown" to locationKnown
                )
                if (!locationKnown) {
                    problemMap["locationNote"] = "Location unavailable from IDE; entry may be stale. Re-run inspection to refresh."
                }
                
                problems.add(problemMap)
            } else {
                extractFallbackProblemFromInspectionNode(node, problems)
            }
        } catch (e: Exception) {
        }
    }

    private fun extractFallbackProblemFromInspectionNode(
        node: ProblemDescriptionNode,
        problems: MutableList<Map<String, Any>>,
    ) {
        val description = normalizeProblemDescription(node.toString())
        if (description.isBlank() || isGenericInspectionTreeText(description)) {
            return
        }

        val inspectionType = nearestInspectionType(node)
        val severity = severityFromTreeText(description)
        problems.add(
            mapOf(
                "description" to description,
                "file" to "unknown",
                "line" to 0,
                "column" to 0,
                "severity" to severity,
                "category" to if (inspectionType == "InspectionTreeFallback") "General" else inspectionType,
                "inspectionType" to inspectionType,
                "source" to "inspection_node_fallback",
                "locationKnown" to false,
                "locationNote" to "Inspection result descriptor unavailable from IDE; open Inspection Results for the exact file and line.",
            )
        )
    }
}
