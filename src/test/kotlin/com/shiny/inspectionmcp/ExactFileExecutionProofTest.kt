package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExactFileExecutionProofTest {
    private data class Wrapper(
        val applicable: Boolean = true,
        val batchRunnable: Boolean = true,
    )

    private class CancellationSignal : RuntimeException()

    private class FakeAdapter(
        private val wrappers: Map<String, Wrapper>,
        private val descriptors: Map<String, List<String>> = emptyMap(),
        private val unmappedDescriptors: Set<String> = emptySet(),
        private val onDisplayKey: (String) -> Unit = {},
        private val onEnablement: (String) -> Unit = {},
        private val onWrapperResolution: (String) -> Unit = {},
        private val onExecution: (String) -> Unit = {},
        private val onMapping: (String) -> Unit = {},
        private val onDiagnostic: (String) -> Unit = {},
    ) : ExactFileProofAdapter<String, String, Wrapper, String> {
        override fun resolveDisplayKey(candidate: ExactFileProofCandidate<String>): String? {
            onDisplayKey(candidate.shortName)
            return candidate.shortName.takeUnless { it.startsWith("missing-key") }
        }

        override fun isProfileEnabled(candidate: ExactFileProofCandidate<String>, displayKey: String): Boolean {
            onEnablement(candidate.shortName)
            return !candidate.shortName.startsWith("disabled")
        }

        override fun resolveSourceWrapper(candidate: ExactFileProofCandidate<String>): Wrapper? {
            onWrapperResolution(candidate.shortName)
            return wrappers[candidate.shortName]
        }

        override fun isLanguageApplicable(candidate: ExactFileProofCandidate<String>, sourceWrapper: Wrapper): Boolean =
            sourceWrapper.applicable

        override fun resolveBatchWrapper(candidate: ExactFileProofCandidate<String>, sourceWrapper: Wrapper): Wrapper? =
            sourceWrapper.takeIf { it.batchRunnable }

        override fun execute(candidate: ExactFileProofCandidate<String>, batchWrapper: Wrapper): List<String> {
            onExecution(candidate.shortName)
            return descriptors[candidate.shortName].orEmpty()
        }

        override fun mapDescriptor(
            candidate: ExactFileProofCandidate<String>,
            batchWrapper: Wrapper,
            descriptor: String,
        ): Map<String, Any>? {
            onMapping(descriptor)
            return descriptor
                .takeUnless(unmappedDescriptors::contains)
                ?.let { mapOf("tool" to candidate.shortName, "description" to it) }
        }

        override fun diagnosticRow(
            candidate: ExactFileProofCandidate<String>,
            classification: ExactFileProofClassification,
            sourceWrapper: Wrapper?,
            batchWrapper: Wrapper?,
            detail: String?,
        ): Map<String, Any?> {
            onDiagnostic(candidate.shortName)
            return mapOf(
                "short_name" to candidate.shortName,
                "file" to candidate.filePath,
                "classification" to classification.diagnosticValue,
                "detail" to detail,
            ).filterValues { it != null }
        }
    }

    private fun runProof(
        names: List<String>,
        adapter: FakeAdapter,
        timeoutMs: Long = 1_000,
        clock: () -> Long = { 0L },
        cancellationCheck: () -> Unit = {},
    ): BoundedExecutionProofResult = runExactFileExecutionProof(
        enabledLocalToolCount = names.size,
        candidates = names.map { name -> ExactFileProofCandidate(name, "/repo/Test.kt", name) },
        enumerationErrorCount = 0,
        timeoutMs = timeoutMs,
        nowNanos = clock,
        cancellationCheck = cancellationCheck,
        rethrowCancellation = { error -> if (error is CancellationSignal) throw error },
        problemKey = { problem -> problem.toString() },
        adapter = adapter,
    )

    @Test
    fun `applicable batch-runnable obligations establish clean proof`() {
        val proof = runProof(
            names = listOf("KotlinA", "KotlinB"),
            adapter = FakeAdapter(mapOf("KotlinA" to Wrapper(), "KotlinB" to Wrapper())),
        )

        assertTrue(proof.proofEstablished)
        assertTrue(proof.proofClean)
        assertEquals(2, proof.languageApplicableObligationCount)
        assertEquals(2, proof.executedToolCount)
    }

    @Test
    fun `globally enabled non-applicable tools do not block proof`() {
        val proof = runProof(
            names = listOf("Kotlin", "XmlOnly"),
            adapter = FakeAdapter(
                mapOf(
                    "Kotlin" to Wrapper(),
                    "XmlOnly" to Wrapper(applicable = false),
                ),
            ),
        )

        assertTrue(proof.proofEstablished)
        assertEquals(1, proof.languageNonApplicableObligationCount)
        assertEquals(1, proof.executedToolCount)
    }

    @Test
    fun `applicable non-batch wrapper keeps proof unproven`() {
        val proof = runProof(
            names = listOf("Kotlin", "ApplicableUnfair"),
            adapter = FakeAdapter(
                mapOf(
                    "Kotlin" to Wrapper(),
                    "ApplicableUnfair" to Wrapper(batchRunnable = false),
                ),
            ),
        )

        assertFalse(proof.proofEstablished)
        assertEquals(1, proof.missingWrapperCount)
        assertEquals("applicable_missing_batch_wrapper", proof.proofBlockReason)
    }

    @Test
    fun `successful execution plus unclassified obligation remains unproven`() {
        val proof = runProof(
            names = listOf("Kotlin", "missing-key-Unknown"),
            adapter = FakeAdapter(mapOf("Kotlin" to Wrapper())),
        )

        assertFalse(proof.proofEstablished)
        assertEquals(1, proof.displayKeyMissingCount)
        assertEquals(1, proof.unclassifiedObligationCount)
    }

    @Test
    fun `timeout after partial execution records unvisited obligations`() {
        var now = 0L
        val proof = runProof(
            names = listOf("KotlinA", "KotlinB"),
            adapter = FakeAdapter(
                wrappers = mapOf("KotlinA" to Wrapper(), "KotlinB" to Wrapper()),
                onExecution = { name -> if (name == "KotlinA") now = 2_000_000L },
            ),
            timeoutMs = 1,
            clock = { now },
        )

        assertFalse(proof.proofEstablished)
        assertTrue(proof.hitTimeLimit)
        assertEquals(1, proof.executedToolCount)
        assertEquals(1, proof.unvisitedObligationCount)
        assertEquals(0, proof.unvisitedClassificationObligationCount)
        assertEquals(1, proof.unexecutedRunnableObligationCount)
    }

    @Test
    fun `timeout crossed by final descriptor mapping cannot establish proof`() {
        var now = 0L
        val proof = runProof(
            names = listOf("Kotlin"),
            adapter = FakeAdapter(
                wrappers = mapOf("Kotlin" to Wrapper()),
                descriptors = mapOf("Kotlin" to listOf("late")),
                onMapping = { now = 2_000_000L },
            ),
            timeoutMs = 1,
            clock = { now },
        )

        assertFalse(proof.proofEstablished)
        assertTrue(proof.hitTimeLimit)
        assertEquals(1, proof.executedToolCount)
        assertEquals(1, proof.unvisitedDescriptorCount)
        assertTrue(proof.proofProblems.isEmpty())
    }

    @Test
    fun `classification timeout separates unvisited candidates from runnable work`() {
        var now = 0L
        val proof = runProof(
            names = listOf("KotlinA", "KotlinB"),
            adapter = FakeAdapter(
                wrappers = mapOf("KotlinA" to Wrapper(), "KotlinB" to Wrapper()),
                onEnablement = { now = 2_000_000L },
            ),
            timeoutMs = 1,
            clock = { now },
        )

        assertTrue(proof.hitTimeLimit)
        assertEquals(2, proof.unvisitedClassificationObligationCount)
        assertEquals(0, proof.unexecutedRunnableObligationCount)
    }

    @Test
    fun `file limit accounting leaves all candidates unvisited`() {
        val proof = BoundedExecutionProofResult(
            proofProblems = emptyList(),
            enabledLocalToolCount = 4,
            candidateObligationCount = 100,
            unvisitedClassificationObligationCount = 100,
            hitFileLimit = true,
        )

        assertFalse(proof.proofEstablished)
        assertEquals(100, proof.unvisitedObligationCount)
        assertEquals("file_limit", proof.proofBlockReason)
    }

    @Test
    fun `cancellation during enablement propagates`() {
        assertThrows(CancellationSignal::class.java) {
            runProof(
                names = listOf("Kotlin"),
                adapter = FakeAdapter(
                    wrappers = mapOf("Kotlin" to Wrapper()),
                    onEnablement = { throw CancellationSignal() },
                ),
            )
        }
    }

    @Test
    fun `cancellation during wrapper resolution propagates`() {
        assertThrows(CancellationSignal::class.java) {
            runProof(
                names = listOf("Kotlin"),
                adapter = FakeAdapter(
                    wrappers = mapOf("Kotlin" to Wrapper()),
                    onWrapperResolution = { throw CancellationSignal() },
                ),
            )
        }
    }

    @Test
    fun `cancellation during execution propagates`() {
        assertThrows(CancellationSignal::class.java) {
            runProof(
                names = listOf("Kotlin"),
                adapter = FakeAdapter(
                    wrappers = mapOf("Kotlin" to Wrapper()),
                    onExecution = { throw CancellationSignal() },
                ),
            )
        }
    }

    @Test
    fun `cancellation during timeout diagnostics propagates`() {
        var clockReads = 0
        assertThrows(CancellationSignal::class.java) {
            runProof(
                names = listOf("Kotlin"),
                adapter = FakeAdapter(
                    wrappers = mapOf("Kotlin" to Wrapper()),
                    onDiagnostic = { throw CancellationSignal() },
                ),
                timeoutMs = 1,
                clock = { if (clockReads++ == 0) 0L else 2_000_000L },
            )
        }
    }

    @Test
    fun `unmapped descriptors block clean proof while mapped findings remain visible`() {
        val proof = runProof(
            names = listOf("Kotlin"),
            adapter = FakeAdapter(
                wrappers = mapOf("Kotlin" to Wrapper()),
                descriptors = mapOf("Kotlin" to listOf("mapped", "unmapped")),
                unmappedDescriptors = setOf("unmapped"),
            ),
        )

        assertFalse(proof.proofEstablished)
        assertEquals(1, proof.unmappedDescriptorCount)
        assertEquals(1, proof.proofProblems.size)
        assertEquals("descriptor_mapping_incomplete", proof.proofBlockReason)
    }

    @Test
    fun `blocking diagnostic examples are deterministically bounded`() {
        val names = (1..20).map { "ApplicableUnfair$it" }
        val proof = runProof(
            names = names,
            adapter = FakeAdapter(names.associateWith { Wrapper(batchRunnable = false) }),
        )

        assertEquals(20, proof.missingWrapperCount)
        assertEquals(MAX_EXACT_FILE_PROOF_EXAMPLES, proof.blockingExamples.size)
        assertEquals("ApplicableUnfair1", proof.blockingExamples.first()["short_name"])
        assertEquals("ApplicableUnfair12", proof.blockingExamples.last()["short_name"])
    }
}
