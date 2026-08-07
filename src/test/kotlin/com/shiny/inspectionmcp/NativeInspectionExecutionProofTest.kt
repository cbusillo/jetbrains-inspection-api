package com.shiny.inspectionmcp

import com.intellij.codeInspection.ex.InspectListener
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeInspectionExecutionProofTest {
    private val filePath = "/tmp/TestProject/src/App.kt"
    private val project = mockk<Project>()
    private val file = mockk<PsiFile> {
        every { virtualFile } returns mockk<VirtualFile> {
            every { path } returns filePath
        }
    }
    private val tool = mockk<InspectionToolWrapper<*, *>> {
        every { shortName } returns "UnusedSymbol"
    }

    @Test
    fun `normal local inspection events establish clean native proof`() {
        val collector = NativeInspectionExecutionProofCollector(project, setOf(filePath))

        collector.inspectionFinished(
            10L,
            1L,
            0,
            tool,
            InspectListener.InspectionKind.LOCAL,
            file,
            project,
        )
        collector.recordExactFileAnalyzed(file, project)
        collector.markCompletedNormally()

        val result = collector.result()
        assertTrue(result.proofEstablished)
        assertTrue(result.proofClean)
        assertEquals(1, result.fileAnalyzedCount)
        assertEquals(1, result.completedToolCount)
        assertNull(result.proofBlockReason)
        assertNull(nativeInspectionProofNotEstablishedReason(result))
    }

    @Test
    fun `missing native proof remains fail closed`() {
        assertEquals(
            "native_attestation_missing",
            nativeInspectionProofNotEstablishedReason(null),
        )
    }

    @Test
    fun `global inspection completion without file traversal remains unproven`() {
        val collector = NativeInspectionExecutionProofCollector(project, setOf(filePath))

        collector.recordExactInspectionFinished(0, tool, InspectListener.InspectionKind.GLOBAL, project)
        collector.markCompletedNormally()

        val result = collector.result()
        assertFalse(result.proofEstablished)
        assertFalse(result.proofClean)
        assertEquals(1, result.globalInspectionFinishedCount)
        assertEquals("native_inspection_scope_incomplete", result.proofBlockReason)
    }

    @Test
    fun `global simple inspection with file traversal establishes clean native proof`() {
        val collector = NativeInspectionExecutionProofCollector(project, setOf(filePath))

        collector.recordExactFileAnalyzed(file, project)
        collector.recordExactInspectionFinished(0, tool, InspectListener.InspectionKind.GLOBAL_SIMPLE, project)
        collector.markCompletedNormally()

        val result = collector.result()
        assertTrue(result.proofEstablished)
        assertTrue(result.proofClean)
        assertEquals(1, result.globalSimpleInspectionFinishedCount)
    }

    @Test
    fun `reported native problems establish execution but cannot prove clean`() {
        val collector = NativeInspectionExecutionProofCollector(project, setOf(filePath))

        collector.inspectionFinished(
            10L,
            1L,
            2,
            tool,
            InspectListener.InspectionKind.LOCAL,
            file,
            project,
        )
        collector.recordExactFileAnalyzed(file, project)
        collector.markCompletedNormally()

        val result = collector.result()
        assertTrue(result.proofEstablished)
        assertFalse(result.proofClean)
        assertNull(result.proofBlockReason)
        assertNull(nativeInspectionProofNotEstablishedReason(result))
    }

    @Test
    fun `inspection failures keep native proof fail closed`() {
        val collector = NativeInspectionExecutionProofCollector(project, setOf(filePath))

        collector.inspectionFinished(
            10L,
            1L,
            0,
            tool,
            InspectListener.InspectionKind.LOCAL,
            file,
            project,
        )
        collector.recordExactFileAnalyzed(file, project)
        collector.inspectionFailed("UnusedSymbol", IllegalStateException("boom"), file, project)
        collector.markCompletedNormally()

        val result = collector.result()
        assertFalse(result.proofEstablished)
        assertFalse(result.proofClean)
        assertEquals(1, result.inspectionFailureCount)
        assertEquals("native_inspection_failures", result.proofBlockReason)
    }

    @Test
    fun `normal return without execution events remains unproven`() {
        val collector = NativeInspectionExecutionProofCollector(project, setOf(filePath))

        collector.markCompletedNormally()

        val result = collector.result()
        assertFalse(result.proofEstablished)
        assertEquals("native_inspection_scope_incomplete", result.proofBlockReason)
    }

    @Test
    fun `events from another project do not establish proof`() {
        val collector = NativeInspectionExecutionProofCollector(project, setOf(filePath))
        val otherProject = mockk<Project>()

        collector.recordExactFileAnalyzed(file, otherProject)
        collector.inspectionFinished(
            10L,
            1L,
            0,
            tool,
            InspectListener.InspectionKind.GLOBAL,
            null,
            otherProject,
        )
        collector.markCompletedNormally()

        val result = collector.result()
        assertFalse(result.proofEstablished)
        assertEquals(0, result.fileAnalyzedCount)
        assertEquals(0, result.inspectionFinishedCount)
    }

    @Test
    fun `invalid PSI and tool metadata cannot break native event collection`() {
        val invalidFile = mockk<PsiFile> {
            every { virtualFile } throws IllegalStateException("invalid PSI")
        }
        val invalidTool = mockk<InspectionToolWrapper<*, *>> {
            every { shortName } throws IllegalStateException("disposed tool")
        }
        val collector = NativeInspectionExecutionProofCollector(project, setOf(filePath))

        collector.recordExactFileAnalyzed(invalidFile, project)
        collector.recordExactInspectionFinished(0, invalidTool, InspectListener.InspectionKind.GLOBAL, project)
        collector.markCompletedNormally()

        val result = collector.result()
        assertFalse(result.proofEstablished)
        assertEquals("native_inspection_scope_incomplete", result.proofBlockReason)
    }

    @Test
    fun `partial file traversal remains fail closed`() {
        val secondPath = "/tmp/TestProject/src/Other.kt"
        val collector = NativeInspectionExecutionProofCollector(project, setOf(filePath, secondPath))

        collector.recordExactFileAnalyzed(file, project)
        collector.markCompletedNormally()

        val result = collector.result()
        assertFalse(result.proofEstablished)
        assertEquals(1, result.missingExpectedFileCount)
        assertEquals("native_inspection_scope_incomplete", result.proofBlockReason)
    }

    @Test
    fun `full traversal without tool completion remains fail closed`() {
        val collector = NativeInspectionExecutionProofCollector(project, setOf(filePath))

        collector.recordExactFileAnalyzed(file, project)
        collector.markCompletedNormally()

        val result = collector.result()
        assertFalse(result.proofEstablished)
        assertEquals("native_inspection_no_tools_completed", result.proofBlockReason)
    }

}
