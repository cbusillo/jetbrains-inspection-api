package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.InspectionProblemConsumer
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.progress.ProcessCanceledException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
    fun `launches offline with a problem consumer and removes the run export directory`() {
        val inspectionManager = mockk<InspectionManager>()
        val context = mockk<GlobalInspectionContextEx>()
        val scope = mockk<AnalysisScope>()
        val descriptor = mockk<CommonProblemDescriptor>()
        val toolWrapper = mockk<InspectionToolWrapper<*, *>>()
        val consumerSlot = slot<InspectionProblemConsumer>()
        val consumedProblems = mutableListOf<Pair<CommonProblemDescriptor, InspectionToolWrapper<*, *>>>()
        var exportDirectory: Path? = null
        every { inspectionManager.createNewGlobalContext() } returns context
        every { context.setProblemConsumer(capture(consumerSlot)) } returns Unit
        every {
            context.launchInspectionsOffline(
                scope,
                any(),
                false,
                any(),
            )
        } answers {
            exportDirectory = arg<Path>(1)
            val exportedPaths = arg<MutableList<Path>>(3)
            exportedPaths.add(requireNotNull(exportDirectory).resolve("result.xml"))
            consumerSlot.captured.consume(mockk(), descriptor, toolWrapper)
        }

        val result = GlobalInspectionContextBoundary.createStandard(inspectionManager)
            .launchInspectionsOffline(scope) { problemDescriptor, wrapper ->
                consumedProblems += problemDescriptor to wrapper
            }

        assertThat(result.exportedResultPathCount).isEqualTo(1)
        assertThat(result.exportedResultPathCountTruncated).isFalse()
        assertThat(result.exportDirectoryDeleted).isTrue()
        assertThat(result.consumedProblemCount).isEqualTo(1)
        assertThat(consumedProblems).containsExactly(descriptor to toolWrapper)
        assertThat(Files.exists(requireNotNull(exportDirectory))).isFalse()
    }

    @Test
    fun `cancellation propagates and still removes the run export directory`() {
        val inspectionManager = mockk<InspectionManager>()
        val context = mockk<GlobalInspectionContextEx>()
        val scope = mockk<AnalysisScope>()
        var exportDirectory: Path? = null
        every { inspectionManager.createNewGlobalContext() } returns context
        every { context.setProblemConsumer(any()) } returns Unit
        every {
            context.launchInspectionsOffline(
                scope,
                any(),
                false,
                any(),
            )
        } answers {
            exportDirectory = arg<Path>(1)
            throw ProcessCanceledException()
        }

        assertThatThrownBy {
            GlobalInspectionContextBoundary.createStandard(inspectionManager)
                .launchInspectionsOffline(scope) { _, _ -> }
        }.isInstanceOf(ProcessCanceledException::class.java)

        assertThat(Files.exists(requireNotNull(exportDirectory))).isFalse()
        verify(exactly = 1) { context.setProblemConsumer(any()) }
    }
}
