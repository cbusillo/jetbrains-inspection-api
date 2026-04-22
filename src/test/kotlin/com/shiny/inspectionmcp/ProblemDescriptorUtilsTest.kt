package com.shiny.inspectionmcp

import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ProblemDescriptorUtilsTest {
    @Test
    @DisplayName("Problem descriptor offsets include the highlight range inside the PSI element")
    fun problemDescriptorStartOffsetUsesRangeInElement() {
        assertEquals(145, problemDescriptorStartOffset(120, TextRange(25, 36)))
    }

    @Test
    @DisplayName("Problem descriptor offsets fall back to the PSI element start")
    fun problemDescriptorStartOffsetFallsBackToElementStart() {
        assertEquals(120, problemDescriptorStartOffset(120, null))
    }
}
