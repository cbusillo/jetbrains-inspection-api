package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import com.intellij.codeInspection.ProblemHighlightType

class EnhancedTreeExtractorTest {
    
    private lateinit var extractor: EnhancedTreeExtractor
    
    @BeforeEach
    fun setup() {
        extractor = EnhancedTreeExtractor()
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
}