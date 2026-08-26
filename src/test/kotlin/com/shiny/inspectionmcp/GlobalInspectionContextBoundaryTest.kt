package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.openapi.progress.ProcessCanceledException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class GlobalInspectionContextBoundaryTest {
    @Test
    fun `creates the standard GlobalInspectionContextEx from InspectionManager`() {
        val inspectionManager = mockk<InspectionManager>()
        val context = mockk<GlobalInspectionContextEx>()
        every { inspectionManager.createNewGlobalContext() } returns context

        val boundary = GlobalInspectionContextBoundary.createStandard(inspectionManager)

        assertThat(boundary.publicContext()).isSameAs(context)
        verify(exactly = 1) { inspectionManager.createNewGlobalContext() }
    }

    @Test
    fun `executes through export results and removes the run export directory`() {
        val inspectionManager = mockk<InspectionManager>()
        val context = mockk<GlobalInspectionContextEx>()
        val scope = mockk<AnalysisScope>()
        var exportDirectory: Path? = null
        every { inspectionManager.createNewGlobalContext() } returns context
        every {
            context.performInspectionsWithProgressAndExportResults(
                scope,
                false,
                false,
                any(),
                any(),
            )
        } answers {
            exportDirectory = arg<Path>(3)
            val exportedPaths = arg<MutableList<Path>>(4)
            exportedPaths.add(requireNotNull(exportDirectory).resolve("result.xml"))
        }

        val result = GlobalInspectionContextBoundary.createStandard(inspectionManager)
            .performInspectionsWithProgressAndExportResults(scope)

        assertThat(result.exportedResultPathCount).isEqualTo(1)
        assertThat(result.exportedResultPathCountTruncated).isFalse()
        assertThat(result.exportDirectoryDeleted).isTrue()
        assertThat(Files.exists(requireNotNull(exportDirectory))).isFalse()
    }

    @Test
    fun `cancellation propagates and still removes the run export directory`() {
        val inspectionManager = mockk<InspectionManager>()
        val context = mockk<GlobalInspectionContextEx>()
        val scope = mockk<AnalysisScope>()
        var exportDirectory: Path? = null
        every { inspectionManager.createNewGlobalContext() } returns context
        every {
            context.performInspectionsWithProgressAndExportResults(
                scope,
                false,
                false,
                any(),
                any(),
            )
        } answers {
            exportDirectory = arg<Path>(3)
            throw ProcessCanceledException()
        }

        assertThatThrownBy {
            GlobalInspectionContextBoundary.createStandard(inspectionManager)
                .performInspectionsWithProgressAndExportResults(scope)
        }.isInstanceOf(ProcessCanceledException::class.java)

        assertThat(Files.exists(requireNotNull(exportDirectory))).isFalse()
    }
}
