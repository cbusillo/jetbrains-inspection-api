package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.codeInspection.ui.InspectionTree
import com.intellij.codeInspection.ui.ProblemDescriptionNode
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeModel
import javax.swing.tree.TreeNode

class EnhancedTreeExtractorTest {
    
    private lateinit var extractor: EnhancedTreeExtractor
    
    @BeforeEach
    fun setup() {
        extractor = EnhancedTreeExtractor()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }
    
    @Test
    @DisplayName("Should test severity mapping logic for different inspection types")
    fun testSeverityMappingLogic() {
        val inspectionTypes = mapOf(
            "GrazieInspection" to "grammar",
            "SpellCheckingInspection" to "typo",
            "AiaStyle" to "typo",
            "RegularInspection" to "warning"
        )
        
        inspectionTypes.forEach { (inspectionType, expectedSeverity) ->
            val actualSeverity = when (inspectionType) {
                "GrazieInspection" -> "grammar"
                "SpellCheckingInspection" -> "typo"
                "AiaStyle" -> "typo"
                else -> "warning"
            }
            
            assertEquals(expectedSeverity, actualSeverity, 
                "Severity mapping failed for $inspectionType")
        }
    }
    
    @Test
    @DisplayName("Should test ProblemHighlightType mapping")
    fun testHighlightTypeMapping() {
        val highlightTypeMapping = mapOf(
            ProblemHighlightType.ERROR to "error",
            ProblemHighlightType.WARNING to "warning",
            ProblemHighlightType.WEAK_WARNING to "weak_warning",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING to "warning",
            ProblemHighlightType.INFORMATION to "info"
        )
        
        highlightTypeMapping.forEach { (highlightType, expectedSeverity) ->
            val actualSeverity = when (highlightType) {
                ProblemHighlightType.ERROR -> "error"
                ProblemHighlightType.WARNING -> "warning"
                ProblemHighlightType.WEAK_WARNING -> "weak_warning"
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "warning"
                ProblemHighlightType.INFORMATION -> "info"
                else -> "warning"
            }
            
            assertEquals(expectedSeverity, actualSeverity,
                "Highlight type mapping failed for $highlightType")
        }
    }
    
    @Test
    @DisplayName("Should test severity level extraction logic")
    fun testSeverityLevelExtraction() {
        val levelToSeverity = mapOf(
            "ERROR" to "error",
            "WARNING" to "warning",
            "WEAK_WARNING" to "weak_warning",
            "INFO" to "info",
            "INFORMATION" to "info"
        )
        
        levelToSeverity.forEach { (levelName, expectedSeverity) ->
            val actualSeverity = when {
                levelName.equals("ERROR", ignoreCase = true) -> "error"
                levelName.equals("WARNING", ignoreCase = true) -> "warning"
                levelName.equals("WEAK_WARNING", ignoreCase = true) -> "weak_warning"
                levelName.equals("INFO", ignoreCase = true) || 
                levelName.equals("INFORMATION", ignoreCase = true) -> "info"
                else -> "warning"
            }
            
            assertEquals(expectedSeverity, actualSeverity,
                "Level extraction failed for $levelName")
        }
    }
    
    @Test
    @DisplayName("Should handle enhanced extractor instantiation")
    fun testExtractorInstantiation() {
        assertNotNull(extractor)
    }

    @Test
    @DisplayName("Should report unsuccessful status when inspection tree traversal fails")
    fun testExtractionStatusReportsTreeTraversalFailure() {
        val project = mockk<Project>(relaxed = true)
        val inspectionView = mockk<InspectionResultsView>()
        val tree = mockk<InspectionTree>()
        val model = mockk<TreeModel>()

        every { inspectionView.tree } returns tree
        every { tree.model } returns model
        every { model.root } throws IllegalStateException("tree traversal failed")

        val result = extractor.extractAllProblemsFromInspectionViewWithStatus(inspectionView, project)

        assertFalse(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should report unsuccessful status when inspection view has no readable tree")
    fun testExtractionStatusReportsMissingInspectionTree() {
        val project = mockk<Project>(relaxed = true)
        val inspectionView = mockk<InspectionResultsView>()

        every { inspectionView.tree } throws IllegalStateException("tree unavailable")
        every { inspectionView.components } returns emptyArray()

        val result = extractor.extractAllProblemsFromInspectionViewWithStatus(inspectionView, project)

        assertFalse(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should report unsuccessful status when tool window content manager is unavailable")
    fun testExtractionStatusReportsUnavailableContentManager() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val inspectionWindow = mockk<ToolWindow>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns false

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Inspection Results")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns inspectionWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns null
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns null
        every { inspectionWindow.contentManager } throws IllegalStateException("content manager unavailable")

        val result = extractor.extractAllProblemsWithStatus(project)

        assertFalse(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should report unsuccessful status when tool window IDs cannot be enumerated")
    fun testExtractionStatusReportsToolWindowEnumerationFailure() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns false

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } throws IllegalStateException("tool windows unavailable")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns null
        every { toolWindowManager.getToolWindow("Problems View") } returns null
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns null

        val result = extractor.extractAllProblemsWithStatus(project)

        assertFalse(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should not treat a direct empty inspection tool window shell as successful extraction")
    fun testExtractionStatusFailsWhenDirectInspectionWindowIsEmptyShell() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val emptyWindow = mockk<ToolWindow>()
        val emptyContentManager = mockk<ContentManager>()
        val emptyContent = mockk<Content>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns emptyArray()
        every { toolWindowManager.getToolWindow("Inspection Results") } returns emptyWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns null
        every { toolWindowManager.getToolWindow("Problems") } returns null

        every { emptyWindow.contentManager } returns emptyContentManager
        every { emptyContentManager.contentCount } returns 1
        every { emptyContentManager.getContent(0) } returns emptyContent
        every { emptyContent.component } returns JPanel()

        val result = extractor.extractAllProblemsWithStatus(project)

        assertFalse(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should preserve successful status when direct inspection fallback works after ID enumeration fails")
    fun testExtractionStatusPreservesSuccessWhenDirectFallbackWorksAfterEnumerationFailure() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val inspectionWindow = mockk<ToolWindow>()
        val inspectionContentManager = mockk<ContentManager>()
        val inspectionContent = mockk<Content>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } throws IllegalStateException("tool windows unavailable")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns inspectionWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns null
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns null

        every { inspectionWindow.contentManager } returns inspectionContentManager
        every { inspectionContentManager.contentCount } returns 1
        every { inspectionContentManager.getContent(0) } returns inspectionContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { inspectionContent.component } returns panel

        val result = extractor.extractAllProblemsWithStatus(project)

        assertTrue(result.succeeded)
        assertEquals(1, result.problems.size)
        assertEquals("Fallback warning", result.problems[0]["description"])
    }

    @Test
    @DisplayName("Should probe all known inspection windows when ID enumeration fails")
    fun testExtractionStatusProbesInspectionsDirectFallbackAfterEnumerationFailure() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val inspectionsWindow = mockk<ToolWindow>()
        val inspectionsContentManager = mockk<ContentManager>()
        val inspectionsContent = mockk<Content>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } throws IllegalStateException("tool windows unavailable")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns null
        every { toolWindowManager.getToolWindow("Problems View") } returns null
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns inspectionsWindow

        every { inspectionsWindow.contentManager } returns inspectionsContentManager
        every { inspectionsContentManager.contentCount } returns 1
        every { inspectionsContentManager.getContent(0) } returns inspectionsContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { inspectionsContent.component } returns panel

        val result = extractor.extractAllProblemsWithStatus(project)

        assertTrue(result.succeeded)
        assertEquals(1, result.problems.size)
        assertEquals("Fallback warning", result.problems[0]["description"])
    }

    @Test
    @DisplayName("Should skip blank direct fallback windows when a later inspection window is extractable")
    fun testExtractionStatusSkipsBlankDirectFallbackAfterEnumerationFailure() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val blankWindow = mockk<ToolWindow>()
        val blankContentManager = mockk<ContentManager>()
        val blankContent = mockk<Content>()
        val inspectionsWindow = mockk<ToolWindow>()
        val inspectionsContentManager = mockk<ContentManager>()
        val inspectionsContent = mockk<Content>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } throws IllegalStateException("tool windows unavailable")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns blankWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns null
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns inspectionsWindow

        every { blankWindow.contentManager } returns blankContentManager
        every { blankContentManager.contentCount } returns 1
        every { blankContentManager.getContent(0) } returns blankContent
        every { blankContent.component } returns JPanel()

        every { inspectionsWindow.contentManager } returns inspectionsContentManager
        every { inspectionsContentManager.contentCount } returns 1
        every { inspectionsContentManager.getContent(0) } returns inspectionsContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { inspectionsContent.component } returns panel

        val result = extractor.extractAllProblemsWithStatus(project)

        assertTrue(result.succeeded)
        assertEquals(1, result.problems.size)
        assertEquals("Fallback warning", result.problems[0]["description"])
    }

    @Test
    @DisplayName("Should skip unreadable direct fallback windows when a later inspection window is extractable")
    fun testExtractionStatusSkipsUnreadableDirectFallbackAfterEnumerationFailure() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val unreadableWindow = mockk<ToolWindow>()
        val inspectionsWindow = mockk<ToolWindow>()
        val inspectionsContentManager = mockk<ContentManager>()
        val inspectionsContent = mockk<Content>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } throws IllegalStateException("tool windows unavailable")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns unreadableWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns null
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns inspectionsWindow

        every { unreadableWindow.contentManager } throws IllegalStateException("content manager unavailable")
        every { inspectionsWindow.contentManager } returns inspectionsContentManager
        every { inspectionsContentManager.contentCount } returns 1
        every { inspectionsContentManager.getContent(0) } returns inspectionsContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { inspectionsContent.component } returns panel

        val result = extractor.extractAllProblemsWithStatus(project)

        assertTrue(result.succeeded)
        assertEquals(1, result.problems.size)
        assertEquals("Fallback warning", result.problems[0]["description"])
    }

    @Test
    @DisplayName("Should fail status when ID enumeration fails and direct inspection window is empty")
    fun testExtractionStatusFailsWhenDirectFallbackAfterEnumerationFailureIsEmpty() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val inspectionWindow = mockk<ToolWindow>()
        val inspectionContentManager = mockk<ContentManager>()
        val inspectionContent = mockk<Content>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } throws IllegalStateException("tool windows unavailable")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns inspectionWindow

        every { inspectionWindow.contentManager } returns inspectionContentManager
        every { inspectionContentManager.contentCount } returns 1
        every { inspectionContentManager.getContent(0) } returns inspectionContent
        every { inspectionContent.component } returns JPanel()
        every { toolWindowManager.getToolWindow("Problems View") } returns null
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns null

        val result = extractor.extractAllProblemsWithStatus(project)

        assertFalse(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should preserve successful status when Problems fallback extracts findings after ID enumeration fails")
    fun testExtractionStatusPreservesSuccessWhenProblemsFallbackWorksAfterEnumerationFailure() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val problemsWindow = mockk<ToolWindow>()
        val problemsContentManager = mockk<ContentManager>()
        val problemsContent = mockk<Content>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } throws IllegalStateException("tool windows unavailable")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns null
        every { toolWindowManager.getToolWindow("Problems View") } returns problemsWindow
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns null

        every { problemsWindow.contentManager } returns problemsContentManager
        every { problemsContentManager.contentCount } returns 1
        every { problemsContentManager.getContent(0) } returns problemsContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { problemsContent.component } returns panel

        val result = extractor.extractAllProblemsWithStatus(project)

        assertTrue(result.succeeded)
        assertEquals(1, result.problems.size)
        assertEquals("Fallback warning", result.problems[0]["description"])
        assertEquals("problems_view", result.problems[0]["source"])
        assertEquals(ProblemExtractionSource.PROBLEMS_FALLBACK, result.source)
    }

    @Test
    @DisplayName("Should keep failed status when Problems fallback is empty after ID enumeration fails")
    fun testExtractionStatusKeepsFailureWhenProblemsFallbackAfterEnumerationFailureIsEmpty() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val problemsWindow = mockk<ToolWindow>()
        val problemsContentManager = mockk<ContentManager>()
        val problemsContent = mockk<Content>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } throws IllegalStateException("tool windows unavailable")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns null
        every { toolWindowManager.getToolWindow("Problems View") } returns problemsWindow

        every { problemsWindow.contentManager } returns problemsContentManager
        every { problemsContentManager.contentCount } returns 1
        every { problemsContentManager.getContent(0) } returns problemsContent
        every { problemsContent.component } returns JPanel()

        val result = extractor.extractAllProblemsWithStatus(project)

        assertFalse(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should not report successful fallback extraction for blank Problems shell")
    fun testExtractionStatusFailsWhenFallbackProblemsShellIsBlank() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val problemsWindow = mockk<ToolWindow>()
        val problemsContentManager = mockk<ContentManager>()
        val problemsContent = mockk<Content>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Problems View")
        every { toolWindowManager.getToolWindow("Problems View") } returns problemsWindow
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns null

        every { problemsWindow.contentManager } returns problemsContentManager
        every { problemsContentManager.contentCount } returns 1
        every { problemsContentManager.getContent(0) } returns problemsContent
        every { problemsContent.component } returns JPanel()

        val result = extractor.extractAllProblemsWithStatus(project)

        assertFalse(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should keep clean primary inspection result successful when fallback shell is blank")
    fun testExtractionStatusCleanPrimaryResultIsNotPoisonedByBlankFallback() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val inspectionWindow = mockk<ToolWindow>()
        val problemsWindow = mockk<ToolWindow>()
        val inspectionContentManager = mockk<ContentManager>()
        val problemsContentManager = mockk<ContentManager>()
        val inspectionContent = mockk<Content>()
        val problemsContent = mockk<Content>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Inspection Results")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns inspectionWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns problemsWindow
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns null

        every { inspectionWindow.contentManager } returns inspectionContentManager
        every { inspectionContentManager.contentCount } returns 1
        every { inspectionContentManager.getContent(0) } returns inspectionContent
        val cleanRoot = DefaultMutableTreeNode("Inspection Results")
        cleanRoot.add(DefaultMutableTreeNode("empty"))
        val cleanPanel = JPanel()
        cleanPanel.add(JTree(cleanRoot))
        every { inspectionContent.component } returns cleanPanel

        every { problemsWindow.contentManager } returns problemsContentManager
        every { problemsContentManager.contentCount } returns 1
        every { problemsContentManager.getContent(0) } returns problemsContent
        every { problemsContent.component } returns JPanel()

        val result = extractor.extractAllProblemsWithStatus(project)

        assertTrue(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should keep probing Problems fallback when primary inspection result is empty")
    fun testExtractionStatusChecksProblemsFallbackAfterEmptyPrimaryInspectionResult() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val inspectionWindow = mockk<ToolWindow>()
        val problemsWindow = mockk<ToolWindow>()
        val inspectionContentManager = mockk<ContentManager>()
        val problemsContentManager = mockk<ContentManager>()
        val inspectionContent = mockk<Content>()
        val problemsContent = mockk<Content>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Inspection Results")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns inspectionWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns problemsWindow
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns null

        every { inspectionWindow.contentManager } returns inspectionContentManager
        every { inspectionContentManager.contentCount } returns 1
        every { inspectionContentManager.getContent(0) } returns inspectionContent
        val cleanRoot = DefaultMutableTreeNode("Inspection Results")
        cleanRoot.add(DefaultMutableTreeNode("empty"))
        val cleanPanel = JPanel()
        cleanPanel.add(JTree(cleanRoot))
        every { inspectionContent.component } returns cleanPanel

        every { problemsWindow.contentManager } returns problemsContentManager
        every { problemsContentManager.contentCount } returns 1
        every { problemsContentManager.getContent(0) } returns problemsContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { problemsContent.component } returns panel

        val result = extractor.extractAllProblemsWithStatus(project)

        assertTrue(result.succeeded)
        assertEquals(1, result.problems.size)
        assertEquals("Fallback warning", result.problems[0]["description"])
        assertEquals("problems_view", result.problems[0]["source"])
    }

    @Test
    @DisplayName("Should not report successful fallback extraction for empty Problems tree")
    fun testExtractionStatusFailsWhenFallbackProblemsTreeIsEmpty() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val problemsWindow = mockk<ToolWindow>()
        val problemsContentManager = mockk<ContentManager>()
        val problemsContent = mockk<Content>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Problems View")
        every { toolWindowManager.getToolWindow("Problems View") } returns problemsWindow
        every { toolWindowManager.getToolWindow("Problems") } returns null
        every { toolWindowManager.getToolWindow("Inspections") } returns null

        every { problemsWindow.contentManager } returns problemsContentManager
        every { problemsContentManager.contentCount } returns 1
        every { problemsContentManager.getContent(0) } returns problemsContent
        val emptyPanel = JPanel()
        emptyPanel.add(JTree(DefaultMutableTreeNode("root")))
        every { problemsContent.component } returns emptyPanel

        val result = extractor.extractAllProblemsWithStatus(project)

        assertFalse(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should not fail status when another inspection window extracts valid results")
    fun testExtractionStatusIgnoresUnreadableStaleWindowWhenReadableInspectionWindowHasResults() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val unreadableWindow = mockk<ToolWindow>()
        val readableWindow = mockk<ToolWindow>()
        val readableContentManager = mockk<ContentManager>()
        val readableContent = mockk<Content>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Inspection Results", "Inspections")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns unreadableWindow
        every { toolWindowManager.getToolWindow("Inspections") } returns readableWindow

        every { unreadableWindow.contentManager } throws IllegalStateException("stale content manager")
        every { readableWindow.contentManager } returns readableContentManager
        every { readableContentManager.contentCount } returns 1
        every { readableContentManager.getContent(0) } returns readableContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { readableContent.component } returns panel

        val result = extractor.extractAllProblemsWithStatus(project)

        assertTrue(result.succeeded)
        assertEquals(1, result.problems.size)
        assertEquals("Fallback warning", result.problems[0]["description"])
    }

    @Test
    @DisplayName("Should not fail status when one candidate fails extraction after another extracts results")
    fun testExtractionStatusIgnoresCandidateThatFailsExtractionWhenAnotherCandidateHasResults() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val staleWindow = mockk<ToolWindow>()
        val staleSearchContentManager = mockk<ContentManager>()
        val staleSearchContent = mockk<Content>()
        val liveWindow = mockk<ToolWindow>()
        val liveContentManager = mockk<ContentManager>()
        val liveContent = mockk<Content>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Inspection Results", "Inspections")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns staleWindow
        every { toolWindowManager.getToolWindow("Inspections") } returns liveWindow

        every { staleWindow.contentManager } returns staleSearchContentManager andThenThrows IllegalStateException("stale extraction")
        every { staleSearchContentManager.contentCount } returns 1
        every { staleSearchContentManager.getContent(0) } returns staleSearchContent
        val staleSearchPanel = JPanel()
        staleSearchPanel.add(JTree(DefaultMutableTreeNode("root")))
        every { staleSearchContent.component } returns staleSearchPanel

        every { liveWindow.contentManager } returns liveContentManager
        every { liveContentManager.contentCount } returns 1
        every { liveContentManager.getContent(0) } returns liveContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { liveContent.component } returns panel

        val result = extractor.extractAllProblemsWithStatus(project)

        assertTrue(result.succeeded)
        assertEquals(1, result.problems.size)
        assertEquals("Fallback warning", result.problems[0]["description"])
    }

    @Test
    @DisplayName("Should fail status when unreadable inspection window only has an empty candidate beside it")
    fun testExtractionStatusFailsWhenUnreadableWindowOnlyHasEmptyCandidate() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val unreadableWindow = mockk<ToolWindow>()
        val emptyWindow = mockk<ToolWindow>()
        val emptyContentManager = mockk<ContentManager>()
        val emptyContent = mockk<Content>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Inspection Results", "Inspections")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns unreadableWindow
        every { toolWindowManager.getToolWindow("Inspections") } returns emptyWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns null
        every { toolWindowManager.getToolWindow("Problems") } returns null

        every { unreadableWindow.contentManager } throws IllegalStateException("stale content manager")
        every { emptyWindow.contentManager } returns emptyContentManager
        every { emptyContentManager.contentCount } returns 1
        every { emptyContentManager.getContent(0) } returns emptyContent
        every { emptyContent.component } returns JPanel()

        val result = extractor.extractAllProblemsWithStatus(project)

        assertFalse(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should not treat an empty inspection tool window shell as successful extraction")
    fun testExtractionStatusFailsWhenKnownInspectionWindowIsEmptyShell() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val emptyWindow = mockk<ToolWindow>()
        val emptyContentManager = mockk<ContentManager>()
        val emptyContent = mockk<Content>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Inspection Results")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns emptyWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns null
        every { toolWindowManager.getToolWindow("Problems") } returns null

        every { emptyWindow.contentManager } returns emptyContentManager
        every { emptyContentManager.contentCount } returns 1
        every { emptyContentManager.getContent(0) } returns emptyContent
        every { emptyContent.component } returns JPanel()

        val result = extractor.extractAllProblemsWithStatus(project)

        assertFalse(result.succeeded)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    @DisplayName("Should keep tree-only inspection result windows in status extraction")
    fun testExtractionStatusKeepsTreeOnlyInspectionWindow() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val inspectionWindow = mockk<ToolWindow>()
        val inspectionContentManager = mockk<ContentManager>()
        val inspectionContent = mockk<Content>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Inspection Results")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns inspectionWindow

        every { inspectionWindow.contentManager } returns inspectionContentManager
        every { inspectionContentManager.contentCount } returns 1
        every { inspectionContentManager.getContent(0) } returns inspectionContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { inspectionContent.component } returns panel

        val result = extractor.extractAllProblemsWithStatus(project)

        assertTrue(result.succeeded)
        assertEquals(1, result.problems.size)
        assertEquals("Fallback warning", result.problems[0]["description"])
        assertEquals("FallbackInspection", result.problems[0]["inspectionType"])
        assertEquals(ProblemExtractionSource.INSPECTION_RESULTS, result.source)
    }

    @Test
    @DisplayName("Should keep tree-only Inspections tool windows in status extraction")
    fun testExtractionStatusKeepsTreeOnlyInspectionsWindow() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val inspectionsWindow = mockk<ToolWindow>()
        val inspectionsContentManager = mockk<ContentManager>()
        val inspectionsContent = mockk<Content>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Inspections")
        every { toolWindowManager.getToolWindow("Inspections") } returns inspectionsWindow

        every { inspectionsWindow.contentManager } returns inspectionsContentManager
        every { inspectionsContentManager.contentCount } returns 1
        every { inspectionsContentManager.getContent(0) } returns inspectionsContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { inspectionsContent.component } returns panel

        val result = extractor.extractAllProblemsWithStatus(project)

        assertTrue(result.succeeded)
        assertEquals(1, result.problems.size)
        assertEquals("Fallback warning", result.problems[0]["description"])
        assertEquals("FallbackInspection", result.problems[0]["inspectionType"])
        assertEquals(ProblemExtractionSource.INSPECTION_RESULTS, result.source)
    }

    @Test
    @DisplayName("Should fall back to Problems view when Inspection Results are empty")
    fun testFallsBackToProblemsViewWhenInspectionResultsAreEmpty() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val inspectionWindow = mockk<ToolWindow>()
        val problemsWindow = mockk<ToolWindow>()
        val inspectionContentManager = mockk<ContentManager>()
        val problemsContentManager = mockk<ContentManager>()
        val inspectionContent = mockk<Content>()
        val problemsContent = mockk<Content>()
        val inspectionView = mockk<InspectionResultsView>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Inspection Results", "Problems View")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns inspectionWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns problemsWindow

        every { inspectionWindow.contentManager } returns inspectionContentManager
        every { inspectionContentManager.contentCount } returns 1
        every { inspectionContentManager.getContent(0) } returns inspectionContent
        every { inspectionContent.component } returns inspectionView
        every { inspectionView.tree } throws IllegalStateException("empty inspection view")

        every { problemsWindow.contentManager } returns problemsContentManager
        every { problemsContentManager.contentCount } returns 1
        every { problemsContentManager.getContent(0) } returns problemsContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { problemsContent.component } returns panel

        val problems = extractor.extractAllProblems(project)

        assertEquals(1, problems.size)
        assertEquals("Fallback warning", problems[0]["description"])
        assertEquals("FallbackInspection", problems[0]["inspectionType"])
        assertEquals("problems_view", problems[0]["source"])
    }

    @Test
    @DisplayName("Should fall back to Problems view when Inspection Results are settled empty")
    fun testFallsBackWhenInspectionResultsAreSettledEmpty() {
        val app = mockk<Application>()
        val project = mockk<Project>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>()
        val inspectionWindow = mockk<ToolWindow>()
        val problemsWindow = mockk<ToolWindow>()
        val inspectionContentManager = mockk<ContentManager>()
        val problemsContentManager = mockk<ContentManager>()
        val inspectionContent = mockk<Content>()
        val problemsContent = mockk<Content>()
        val inspectionView = mockk<InspectionResultsView>()
        val inspectionTree = mockk<InspectionTree>()
        val virtualFile = mockk<VirtualFile>()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true

        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns toolWindowManager
        every { toolWindowManager.toolWindowIds } returns arrayOf("Inspection Results", "Problems View")
        every { toolWindowManager.getToolWindow("Inspection Results") } returns inspectionWindow
        every { toolWindowManager.getToolWindow("Problems View") } returns problemsWindow

        every { inspectionWindow.contentManager } returns inspectionContentManager
        every { inspectionContentManager.contentCount } returns 1
        every { inspectionContentManager.getContent(0) } returns inspectionContent
        every { inspectionContent.component } returns inspectionView
        every { inspectionView.tree } returns inspectionTree
        every { inspectionView.isUpdating } returns false
        every { inspectionView.hasProblems() } returns false
        every { inspectionTree.model.root } returns DefaultMutableTreeNode("empty")

        every { problemsWindow.contentManager } returns problemsContentManager
        every { problemsContentManager.contentCount } returns 1
        every { problemsContentManager.getContent(0) } returns problemsContent
        every { virtualFile.path } returns "/tmp/project/App.kt"
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode(FallbackProblem(virtualFile)))
        val panel = JPanel()
        panel.add(JTree(root))
        every { problemsContent.component } returns panel

        val problems = extractor.extractAllProblems(project)

        assertEquals(1, problems.size)
        assertEquals("Fallback warning", problems[0]["description"])
        assertEquals("FallbackInspection", problems[0]["inspectionType"])
        assertEquals("problems_view", problems[0]["source"])
    }

    @Test
    @DisplayName("Should emit fallback finding for unmapped non-empty inspection tree")
    fun testUnmappedInspectionTreeProducesFallbackFinding() {
        val project = mockk<Project>(relaxed = true)
        val inspectionView = mockk<InspectionResultsView>()
        val inspectionTree = mockk<InspectionTree>()
        val root = DefaultMutableTreeNode("Inspection Results")
        val inspection = DefaultMutableTreeNode("CompilerInspection")
        val fileGroup = DefaultMutableTreeNode("InspectionRedFixture.java")
        fileGroup.add(DefaultMutableTreeNode("Cannot resolve symbol definitelyMissingInspectionSymbol"))
        inspection.add(fileGroup)
        root.add(inspection)
        val tree = JTree(root)

        every { inspectionView.tree } returns inspectionTree
        every { inspectionTree.model } returns tree.model

        val problems = extractor.extractAllProblemsFromInspectionView(inspectionView, project)

        assertEquals(1, problems.size)
        assertEquals("Cannot resolve symbol definitelyMissingInspectionSymbol", problems[0]["description"])
        assertTrue((problems[0]["file"] as String).endsWith("InspectionRedFixture.java"))
        assertEquals(false, problems[0]["locationKnown"])
        assertEquals(0, problems[0]["line"])
        assertEquals("CompilerInspection", problems[0]["inspectionType"])
        assertEquals("inspection_tree_fallback", problems[0]["source"])
    }

    @Test
    @DisplayName("Should emit fallback finding for descriptorless problem node")
    fun testDescriptorlessProblemNodeProducesFallbackFinding() {
        val project = mockk<Project>(relaxed = true)
        val inspectionView = mockk<InspectionResultsView>()
        val inspectionTree = mockk<InspectionTree>()
        val treeModel = mockk<TreeModel>()
        val problemNode = mockk<ProblemDescriptionNode>(relaxed = true)
        every { problemNode.descriptor } returns null
        every { problemNode.toString() } returns "Cannot resolve symbol MissingType"
        every { problemNode.parent } returns null

        every { inspectionView.tree } returns inspectionTree
        every { inspectionTree.model } returns treeModel
        every { treeModel.root } returns problemNode

        val problems = extractor.extractAllProblemsFromInspectionView(inspectionView, project)

        assertEquals(1, problems.size)
        assertEquals("Cannot resolve symbol MissingType", problems[0]["description"])
        assertEquals("UnresolvedReference", problems[0]["inspectionType"])
        assertEquals("inspection_node_fallback", problems[0]["source"])
        assertEquals(false, problems[0]["locationKnown"])
    }

    @Test
    @DisplayName("Should emit fallback finding for generic non-mutable tree node")
    fun testGenericTreeNodeProducesFallbackFinding() {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/project"
        val inspectionView = mockk<InspectionResultsView>()
        val inspectionTree = mockk<InspectionTree>()
        val treeModel = mockk<TreeModel>()
        val root = SimpleTreeNode("Inspection Results")
        val inspection = SimpleTreeNode("CompilerInspection", root)
        val fileGroup = SimpleTreeNode("src/InspectionRedFixture.java", inspection)
        val problem = SimpleTreeNode("Cannot resolve symbol definitelyMissingInspectionSymbol", fileGroup)
        root.children.add(inspection)
        inspection.children.add(fileGroup)
        fileGroup.children.add(problem)

        every { inspectionView.tree } returns inspectionTree
        every { inspectionTree.model } returns treeModel
        every { treeModel.root } returns root

        val problems = extractor.extractAllProblemsFromInspectionView(inspectionView, project)

        assertEquals(1, problems.size)
        assertEquals("/tmp/project/src/InspectionRedFixture.java", problems[0]["file"])
        assertEquals("CompilerInspection", problems[0]["inspectionType"])
        assertEquals("inspection_tree_fallback", problems[0]["source"])
    }

    @Test
    @DisplayName("Should not emit fallback finding for generic empty inspection tree")
    fun testGenericEmptyInspectionTreeDoesNotProduceFallbackFinding() {
        val project = mockk<Project>(relaxed = true)
        val inspectionView = mockk<InspectionResultsView>()
        val inspectionTree = mockk<InspectionTree>()
        val root = DefaultMutableTreeNode("Inspection Results")
        root.add(DefaultMutableTreeNode("empty"))
        val tree = JTree(root)

        every { inspectionView.tree } returns inspectionTree
        every { inspectionTree.model } returns tree.model

        val problems = extractor.extractAllProblemsFromInspectionView(inspectionView, project)

        assertTrue(problems.isEmpty())
    }

    @Test
    @DisplayName("Should not emit fallback finding for clean keyword-like grouping text")
    fun testCleanKeywordLikeInspectionTreeDoesNotProduceFallbackFinding() {
        val project = mockk<Project>(relaxed = true)
        val inspectionView = mockk<InspectionResultsView>()
        val inspectionTree = mockk<InspectionTree>()
        val root = DefaultMutableTreeNode("Inspection Results")
        val profileGroup = DefaultMutableTreeNode("Inspection profile: Default")
        profileGroup.add(DefaultMutableTreeNode("Error handling notes"))
        profileGroup.add(DefaultMutableTreeNode("Warning summary"))
        root.add(profileGroup)
        val tree = JTree(root)

        every { inspectionView.tree } returns inspectionTree
        every { inspectionTree.model } returns tree.model

        val problems = extractor.extractAllProblemsFromInspectionView(inspectionView, project)

        assertTrue(problems.isEmpty())
    }

    @Test
    @DisplayName("Should not emit fallback finding for benign leaf under inspection category")
    fun testBenignLeafUnderInspectionCategoryDoesNotProduceFallbackFinding() {
        val project = mockk<Project>(relaxed = true)
        val inspectionView = mockk<InspectionResultsView>()
        val inspectionTree = mockk<InspectionTree>()
        val root = DefaultMutableTreeNode("Inspection Results")
        val inspection = DefaultMutableTreeNode("CompilerInspection")
        inspection.add(DefaultMutableTreeNode("Generated sources summary"))
        root.add(inspection)
        val tree = JTree(root)

        every { inspectionView.tree } returns inspectionTree
        every { inspectionTree.model } returns tree.model

        val problems = extractor.extractAllProblemsFromInspectionView(inspectionView, project)

        assertTrue(problems.isEmpty())
    }

    @Test
    @DisplayName("Should dedupe repeated problem maps from inspection view snapshots")
    fun testDedupeProblems() {
        val repeatedProblem = mapOf<String, Any>(
            "severity" to "warning",
            "inspectionType" to "KotlinUnreachableCode",
            "file" to "/tmp/example.kt",
            "line" to 42,
            "column" to 1,
            "description" to "Unreachable code",
        )
        val uniqueProblem = mapOf<String, Any>(
            "severity" to "warning",
            "inspectionType" to "UnusedSymbol",
            "file" to "/tmp/example.kt",
            "line" to 43,
            "column" to 1,
            "description" to "Parameter is never used",
        )

        val dedupedProblems = extractor.dedupeProblems(
            listOf(repeatedProblem, repeatedProblem.toMap(), uniqueProblem)
        )

        assertEquals(2, dedupedProblems.size)
        assertEquals(repeatedProblem, dedupedProblems[0])
        assertEquals(uniqueProblem, dedupedProblems[1])
    }

    @Test
    @DisplayName("Should clamp stale document offsets")
    fun testClampDocumentOffset() {
        assertEquals(0, extractor.clampDocumentOffset(-10, 25))
        assertEquals(12, extractor.clampDocumentOffset(12, 25))
        assertEquals(25, extractor.clampDocumentOffset(100, 25))
        assertEquals(0, extractor.clampDocumentOffset(5, -1))
    }

    @Test
    @DisplayName("Should locate typo occurrences inside YAML block scalars")
    fun testYamlBlockScalarTypoLocations() {
        val lines = listOf(
            "      - name: Request preview refresh",
            "        env:",
            "          SERVICE_URL: https://example.com",
            "        run: |",
            "          node scripts/ops/request-preview-refresh.mjs \\",
            "            --service-url SERVICE_URL \\",
            "            --audience SERVICE_AUDIENCE \\",
            "        shell: bash",
        )

        val locations = extractor.yamlBlockScalarTypoLocations(lines, 4, "SERVICE")

        assertEquals(listOf(6 to 26, 7 to 23), locations)
    }

    @Test
    @DisplayName("Should ignore non block-scalar YAML lines")
    fun testYamlBlockScalarTypoLocationsRequiresScalarHeader() {
        val lines = listOf(
            "        env:",
            "          SERVICE_URL: https://example.com",
        )

        val locations = extractor.yamlBlockScalarTypoLocations(lines, 2, "SERVICE")

        assertTrue(locations.isEmpty())
    }

    @Suppress("unused", "SameReturnValue")
    private class FallbackProblem(private val virtualFile: VirtualFile) {
        fun getDescription(): String = "Fallback warning"
        fun getVirtualFile(): VirtualFile = virtualFile
        fun getSeverity(): String = "WARNING"
        fun getCategory(): String = "General"
        fun getInspectionToolId(): String = "FallbackInspection"
    }

    private class SimpleTreeNode(
        private val label: String,
        private val parentNode: TreeNode? = null,
    ) : TreeNode {
        val children = mutableListOf<TreeNode>()

        override fun getChildAt(childIndex: Int): TreeNode = children[childIndex]
        override fun getChildCount(): Int = children.size
        override fun getParent(): TreeNode? = parentNode
        override fun getIndex(node: TreeNode?): Int = children.indexOf(node)
        override fun getAllowsChildren(): Boolean = true
        override fun isLeaf(): Boolean = children.isEmpty()
        override fun children(): java.util.Enumeration<out TreeNode> = java.util.Collections.enumeration(children)
        override fun toString(): String = label
    }
}
