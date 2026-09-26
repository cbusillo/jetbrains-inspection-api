package com.shiny.inspectionmcp

import com.intellij.codeInspection.ex.InspectListener
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NativeInspectionCompletionObservationTest {
    private val path = "/project/app.py"
    private val project = mockk<Project>()
    private val file = mockk<PsiFile> {
        every { virtualFile } returns mockk<VirtualFile> {
            every { path } returns this@NativeInspectionCompletionObservationTest.path
        }
    }
    private fun tool(name: String = "Check") = mockk<InspectionToolWrapper<*, *>> {
        every { shortName } returns name
    }

    @Test
    fun `dropped completion changes comparison without changing current clean proof`() {
        val first = tool("First")
        val second = tool("Second")
        fun run(dropSecond: Boolean): Pair<NativeInspectionExecutionProofResult, Map<String, Any?>> {
            val collector = NativeInspectionExecutionProofCollector(project, setOf(path))
            collector.inspectionFinished(1, 1, 0, first, InspectListener.InspectionKind.LOCAL, file, project)
            if (!dropSecond) collector.inspectionFinished(1, 1, 0, second, InspectListener.InspectionKind.LOCAL, file, project)
            collector.recordExactFileAnalyzed(file, project)
            collector.markCompletedNormally()
            collector.completionObservation.observeCandidates { it.candidate(first, path); it.candidate(second, path) }
            return collector.result() to collector.completionObservation.diagnostic()
        }
        val complete = run(false)
        val dropped = run(true)
        assertThat(complete.first.proofClean).isTrue()
        assertThat(dropped.first.proofClean).isTrue()
        assertThat(complete.second["candidate_rule_would_block_clean"]).isEqualTo(false)
        assertThat(dropped.second["candidate_rule_would_block_clean"]).isEqualTo(true)
        assertThat(dropped.second["missing_examples"]).isEqualTo(listOf(mapOf("tool" to second.shortName, "file" to path)))
    }

    @Test
    fun `wrapper identity and physical file both distinguish candidates`() {
        val expected = tool()
        val otherWrapper = tool()
        val observation = NativeInspectionCompletionObservation()
        observation.recordCompletion(0, otherWrapper, path)
        observation.recordCompletion(0, expected, "/project/other.py")
        observation.recordCompletion(-1, expected, path)
        observation.observeCandidates { it.candidate(expected, path) }
        assertThat(observation.diagnostic()["missing_completion_count"]).isEqualTo(1)
        observation.recordCompletion(0, expected, path)
        assertThat(observation.diagnostic()["candidate_rule_would_block_clean"]).isEqualTo(false)
    }

    @Test
    fun `enumeration failure preserves native proof and disables comparison`() {
        val collector = NativeInspectionExecutionProofCollector(project, setOf(path))
        collector.recordExactFileAnalyzed(file, project)
        collector.recordExactInspectionFinished(0, tool(), InspectListener.InspectionKind.GLOBAL, project)
        collector.markCompletedNormally()
        val before = collector.result()
        collector.completionObservation.observeCandidates { error("metadata unavailable") }
        val diagnostic = collector.completionObservation.diagnostic()
        assertThat(collector.result()).isEqualTo(before)
        assertThat(before.proofClean).isTrue()
        assertThat(diagnostic["enumeration_complete"]).isEqualTo(false)
        assertThat(diagnostic["candidate_rule_would_block_clean"]).isNull()
        assertThat(diagnostic["unavailable_reason"]).isEqualTo(IllegalStateException::class.java.simpleName)
    }

    @Test
    fun `incomplete file traversal and write preemption have distinct diagnostic reasons`() {
        val collector = NativeInspectionExecutionProofCollector(project, setOf(path))
        collector.completionObservation.observeCandidates { collector.observedScopeFiles() }
        assertThat(collector.completionObservation.diagnostic()["unavailable_reason"]).isEqualTo("native_scope_traversal_incomplete")
        val observation = NativeInspectionCompletionObservation()
        observation.observeCandidates { throw ExactFileProofWritePreemptedException() }
        assertThat(observation.diagnostic()["unavailable_reason"]).isEqualTo("observation_write_preempted")
        assertThat(observation.diagnostic()["candidate_rule_would_block_clean"]).isNull()
    }
}
