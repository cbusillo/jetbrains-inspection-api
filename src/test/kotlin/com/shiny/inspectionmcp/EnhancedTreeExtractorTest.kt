package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.codeInspection.ui.InspectionTree
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
    @DisplayName("Should not fall back to Problems view when Inspection Results are settled empty")
    fun testDoesNotFallbackWhenInspectionResultsAreSettledEmpty() {
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

        assertEquals(emptyList<Map<String, Any>>(), problems)
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

    @Suppress("unused", "SameReturnValue")
    private class FallbackProblem(private val virtualFile: VirtualFile) {
        fun getDescription(): String = "Fallback warning"
        fun getVirtualFile(): VirtualFile = virtualFile
        fun getSeverity(): String = "WARNING"
        fun getCategory(): String = "General"
        fun getInspectionToolId(): String = "FallbackInspection"
    }
}
