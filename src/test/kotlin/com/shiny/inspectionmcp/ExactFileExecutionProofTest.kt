package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExactFileExecutionProofTest {
    private data class Wrapper(
        val applicable: Boolean = true,
        val batchCapability: ExactFileProofBatchCapability = ExactFileProofBatchCapability.BATCH_RUNNABLE,
        val hasBatchWrapper: Boolean = true,
        val identity: String = "source",
    )

    private class CancellationSignal : RuntimeException()

    private class FakeAdapter(
        private val wrappers: Map<String, Wrapper>,
        private val pairedBatchWrappers: Map<String, Wrapper> = emptyMap(),
        private val descriptors: Map<String, List<String>> = emptyMap(),
        private val unmappedDescriptors: Set<String> = emptySet(),
        private val onDisplayKey: (String) -> Unit = {},
        private val onEnablement: (String) -> Unit = {},
        private val onWrapperResolution: (String) -> Unit = {},
        private val onBatchCapability: (String) -> Unit = {},
        private val onExecution: (String) -> Unit = {},
        private val onCopy: (String, Wrapper) -> Wrapper = { _, wrapper -> wrapper.copy(identity = "${wrapper.identity}:copy") },
        private val onInitialize: (String, Wrapper) -> Unit = { _, _ -> },
        private val onExecutionWrapper: (String, Wrapper) -> Unit = { _, _ -> },
        private val onCleanup: (String, Wrapper) -> Unit = { _, _ -> },
        private val onMapping: (String) -> Unit = {},
        private val onDiagnostic: (String) -> Unit = {},
        private val onResolveBatchWrapper: (String, Wrapper) -> Wrapper? = { name, wrapper ->
            pairedBatchWrappers[name] ?: wrapper.takeIf { it.hasBatchWrapper }
        },
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

        override fun resolveBatchCapability(
            candidate: ExactFileProofCandidate<String>,
            sourceWrapper: Wrapper,
        ): ExactFileProofBatchCapability {
            onBatchCapability(candidate.shortName)
            return sourceWrapper.batchCapability
        }

        override fun resolveBatchWrapper(candidate: ExactFileProofCandidate<String>, sourceWrapper: Wrapper): Wrapper? =
            onResolveBatchWrapper(candidate.shortName, sourceWrapper)

        override fun copyBatchWrapper(candidate: ExactFileProofCandidate<String>, batchWrapper: Wrapper): Wrapper =
            onCopy(candidate.shortName, batchWrapper)

        override fun initializeBatchWrapper(candidate: ExactFileProofCandidate<String>, batchWrapper: Wrapper) {
            onInitialize(candidate.shortName, batchWrapper)
        }

        override fun execute(candidate: ExactFileProofCandidate<String>, batchWrapper: Wrapper): List<String> {
            onExecution(candidate.shortName)
            onExecutionWrapper(candidate.shortName, batchWrapper)
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
                ?.let {
                    mapOf(
                        "tool" to candidate.shortName,
                        "description" to it,
                        "file" to candidate.filePath,
                    )
                }
        }

        override fun cleanupBatchWrapper(candidate: ExactFileProofCandidate<String>, batchWrapper: Wrapper) {
            onCleanup(candidate.shortName, batchWrapper)
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
        filePaths: Map<String, String> = emptyMap(),
        maxParallelFiles: Int = 4,
    ): BoundedExecutionProofResult = runExactFileExecutionProof(
        enabledLocalToolCount = names.size,
        candidates = names.map { name -> ExactFileProofCandidate(name, filePaths[name] ?: "/repo/Test.kt", name) },
        enumerationErrorCount = 0,
        timeoutMs = timeoutMs,
        nowNanos = clock,
        cancellationCheck = cancellationCheck,
        rethrowCancellation = { error -> if (error is CancellationSignal) throw error },
        problemKey = { problem -> problem.toString() },
        adapter = adapter,
        maxParallelFiles = maxParallelFiles,
    )

    @Test
    fun `applicable batch-runnable obligations establish clean proof`() {
        val proof = runProof(
            names = listOf("KotlinA", "KotlinB"),
            adapter = FakeAdapter(mapOf("KotlinA" to Wrapper(), "KotlinB" to Wrapper())),
            filePaths = mapOf("KotlinA" to "/repo/A.kt", "KotlinB" to "/repo/B.kt"),
        )

        assertTrue(proof.proofEstablished)
        assertTrue(proof.proofClean)
        assertEquals(2, proof.languageApplicableObligationCount)
        assertEquals(2, proof.executedToolCount)
        assertEquals(2, proof.candidateScopeFileCount)
        assertEquals(2, proof.applicableScopeFileCount)
        assertEquals(2, proof.executedScopeFileCount)
        assertEquals(2, proof.batchRunnableObligationCount)
    }

    @Test
    fun `different exact files execute concurrently with complete accounting`() {
        val bothFilesStarted = CountDownLatch(2)
        val activeWorkers = AtomicInteger()
        val maxActiveWorkers = AtomicInteger()
        val overlappingExecutions = AtomicInteger()
        val proof = runProof(
            names = listOf("KotlinA", "KotlinB"),
            adapter = FakeAdapter(
                wrappers = mapOf("KotlinA" to Wrapper(), "KotlinB" to Wrapper()),
                onExecutionWrapper = { _, _ ->
                    val active = activeWorkers.incrementAndGet()
                    maxActiveWorkers.updateAndGet { current -> maxOf(current, active) }
                    bothFilesStarted.countDown()
                    try {
                        if (bothFilesStarted.await(2, TimeUnit.SECONDS)) overlappingExecutions.incrementAndGet()
                    } finally {
                        activeWorkers.decrementAndGet()
                    }
                },
            ),
            filePaths = mapOf("KotlinA" to "/repo/A.kt", "KotlinB" to "/repo/B.kt"),
        )

        assertTrue(proof.proofEstablished)
        assertEquals(2, proof.executedToolCount)
        assertEquals(2, proof.executedScopeFileCount)
        assertEquals(2, maxActiveWorkers.get())
        assertEquals(2, overlappingExecutions.get())
        assertEquals(0, activeWorkers.get())
    }

    @Test
    fun `reproduced two-file workload executes all 227 runnable obligations`() {
        val toolNames = (0 until 114).map { index -> "Tool${index.toString().padStart(3, '0')}" }
        val candidates = buildList {
            toolNames.forEachIndexed { index, toolName ->
                add(ExactFileProofCandidate(toolName, "/repo/large_a.py", "$toolName:a"))
                if (index < 113) add(ExactFileProofCandidate(toolName, "/repo/large_b.py", "$toolName:b"))
            }
        }
        val proof = runExactFileExecutionProof(
            enabledLocalToolCount = toolNames.size,
            candidates = candidates,
            enumerationErrorCount = 0,
            timeoutMs = 5_000,
            nowNanos = System::nanoTime,
            cancellationCheck = {},
            rethrowCancellation = { error -> if (error is CancellationSignal) throw error },
            problemKey = { problem -> problem.toString() },
            adapter = FakeAdapter(toolNames.associateWith { Wrapper() }),
        )

        assertTrue(proof.proofEstablished)
        assertEquals(227, proof.candidateObligationCount)
        assertEquals(227, proof.batchRunnableObligationCount)
        assertEquals(227, proof.executedToolCount)
        assertEquals(2, proof.candidateScopeFileCount)
        assertEquals(2, proof.executedScopeFileCount)
        assertEquals(0, proof.unexecutedRunnableObligationCount)
        assertFalse(proof.hitTimeLimit)
    }

    @Test
    fun `timeout after execution marks every returned descriptor unvisited`() {
        val nowNanos = java.util.concurrent.atomic.AtomicLong(0L)
        val proof = runProof(
            names = listOf("Kotlin"),
            adapter = FakeAdapter(
                wrappers = mapOf("Kotlin" to Wrapper()),
                descriptors = mapOf("Kotlin" to listOf("first", "second")),
                onExecution = { nowNanos.set(2_000_000L) },
            ),
            timeoutMs = 1,
            clock = nowNanos::get,
        )

        assertTrue(proof.hitTimeLimit)
        assertEquals(1, proof.executedToolCount)
        assertEquals(2, proof.totalDescriptorCount)
        assertEquals(2, proof.unvisitedDescriptorCount)
        assertEquals("time_limit", proof.proofBlockReason)
    }

    @Test
    fun `copied wrapper is initialized executed and cleaned exactly once`() {
        val lifecycle = Collections.synchronizedList(mutableListOf<String>())
        val proof = runProof(
            names = listOf("Kotlin"),
            adapter = FakeAdapter(
                wrappers = mapOf("Kotlin" to Wrapper(identity = "source")),
                onCopy = { name, wrapper ->
                    lifecycle += "copy:$name:${wrapper.identity}"
                    wrapper.copy(identity = "copy")
                },
                onInitialize = { name, wrapper -> lifecycle += "initialize:$name:${wrapper.identity}" },
                onExecutionWrapper = { name, wrapper -> lifecycle += "execute:$name:${wrapper.identity}" },
                onCleanup = { name, wrapper -> lifecycle += "cleanup:$name:${wrapper.identity}" },
            ),
        )

        assertTrue(proof.proofEstablished)
        assertEquals(
            listOf("copy:Kotlin:source", "initialize:Kotlin:copy", "execute:Kotlin:copy", "cleanup:Kotlin:copy"),
            lifecycle,
        )
    }

    @Test
    fun `initialization failure still cleans copied wrapper and fails closed`() {
        val cleanupCount = AtomicInteger()
        val proof = runProof(
            names = listOf("Kotlin"),
            adapter = FakeAdapter(
                wrappers = mapOf("Kotlin" to Wrapper()),
                onInitialize = { _, _ -> throw IllegalStateException("initialize") },
                onCleanup = { _, _ -> cleanupCount.incrementAndGet() },
            ),
        )

        assertFalse(proof.proofEstablished)
        assertEquals(1, proof.failedObligationCount)
        assertEquals(0, proof.executedToolCount)
        assertEquals(1, cleanupCount.get())
        assertEquals("execution_failed", proof.proofBlockReason)
    }

    @Test
    fun `cleanup failure keeps completed execution unproven`() {
        val proof = runProof(
            names = listOf("Kotlin"),
            adapter = FakeAdapter(
                wrappers = mapOf("Kotlin" to Wrapper()),
                onCleanup = { _, _ -> throw IllegalStateException("cleanup") },
            ),
        )

        assertFalse(proof.proofEstablished)
        assertEquals(1, proof.executedToolCount)
        assertEquals(1, proof.failedObligationCount)
        assertEquals(1, proof.errorCount)
        assertEquals("execution_failed", proof.proofBlockReason)
    }

    @Test
    fun `paired batch wrapper resolves before isolated execution copy`() {
        val executedWrapperIdentities = mutableListOf<String>()
        val proof = runProof(
            names = listOf("ApplicableUnfair"),
            adapter = FakeAdapter(
                wrappers = mapOf(
                    "ApplicableUnfair" to Wrapper(
                        batchCapability = ExactFileProofBatchCapability.NON_BATCH_CAPABLE_EXCLUDED,
                        hasBatchWrapper = false,
                        identity = "unfair",
                    ),
                ),
                pairedBatchWrappers = mapOf("ApplicableUnfair" to Wrapper(identity = "paired")),
                onExecutionWrapper = { _, wrapper -> executedWrapperIdentities += wrapper.identity },
            ),
        )

        assertEquals(0, proof.missingWrapperCount)
        assertEquals(1, proof.nonBatchExcludedObligationCount)
        assertEquals(1, proof.nonBatchExamples.size)
        assertEquals("non_batch_capable_excluded", proof.nonBatchExamples.single()["classification"])
        assertTrue(executedWrapperIdentities.isEmpty())
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
    fun `files with no applicable obligations do not require execution coverage`() {
        val proof = runProof(
            names = listOf("Kotlin", "XmlOnly"),
            adapter = FakeAdapter(
                mapOf(
                    "Kotlin" to Wrapper(),
                    "XmlOnly" to Wrapper(applicable = false),
                ),
            ),
            filePaths = mapOf(
                "Kotlin" to "/repo/A.kt",
                "XmlOnly" to "/repo/B.kt",
            ),
        )

        assertTrue(proof.proofEstablished)
        assertEquals(2, proof.candidateScopeFileCount)
        assertEquals(1, proof.applicableScopeFileCount)
        assertEquals(1, proof.executedScopeFileCount)
        assertEquals(0, proof.missingScopeExecutionCoverageCount)
    }

    @Test
    fun `explicitly excluded unfair tools are not counted as runnable or executed`() {
        val proof = runProof(
            names = listOf("ApplicableUnfair"),
            adapter = FakeAdapter(
                mapOf(
                    "ApplicableUnfair" to Wrapper(
                        batchCapability = ExactFileProofBatchCapability.NON_BATCH_CAPABLE_EXCLUDED,
                        hasBatchWrapper = false,
                    ),
                ),
            ),
        )

        assertFalse(proof.proofEstablished)
        assertFalse(proof.proofClean)
        assertEquals(0, proof.executedToolCount)
        assertEquals(0, proof.batchRunnableObligationCount)
        assertEquals(1, proof.nonBatchExcludedObligationCount)
        assertEquals(1, proof.nonBatchExamples.size)
        assertEquals("no_applicable_batch_tools", proof.proofBlockReason)
    }

    @Test
    fun `fair missing wrapper remains blocking and excluded tools stay out of the denominator`() {
        val proof = runProof(
            names = listOf("Kotlin", "ApplicableUnfair", "MissingBatch"),
            adapter = FakeAdapter(
                mapOf(
                    "Kotlin" to Wrapper(),
                    "ApplicableUnfair" to Wrapper(
                        batchCapability = ExactFileProofBatchCapability.NON_BATCH_CAPABLE_EXCLUDED,
                        hasBatchWrapper = false,
                    ),
                    "MissingBatch" to Wrapper(hasBatchWrapper = false),
                ),
            ),
            filePaths = mapOf(
                "Kotlin" to "/repo/A.kt",
                "ApplicableUnfair" to "/repo/B.kt",
                "MissingBatch" to "/repo/C.kt",
            ),
        )

        assertFalse(proof.proofEstablished)
        assertEquals(1, proof.missingWrapperCount)
        assertEquals(1, proof.nonBatchExcludedObligationCount)
        assertEquals(1, proof.batchRunnableObligationCount)
        assertEquals(1, proof.executedToolCount)
        assertEquals(3, proof.candidateScopeFileCount)
        assertEquals(3, proof.applicableScopeFileCount)
        assertEquals(1, proof.executedScopeFileCount)
        assertEquals(2, proof.missingScopeExecutionCoverageCount)
        assertEquals("applicable_missing_batch_wrapper", proof.proofBlockReason)
    }

    @Test
    fun `paired unfair unavailable counterpart remains blocking`() {
        val proof = runProof(
            names = listOf("Kotlin", "PairedUnfair"),
            adapter = FakeAdapter(
                mapOf(
                    "Kotlin" to Wrapper(),
                    "PairedUnfair" to Wrapper(hasBatchWrapper = false, identity = "paired-unfair"),
                ),
                pairedBatchWrappers = emptyMap(),
            ),
        )

        assertFalse(proof.proofEstablished)
        assertEquals(1, proof.missingWrapperCount)
        assertEquals(1, proof.batchRunnableObligationCount)
        assertEquals(1, proof.executedToolCount)
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
        assertEquals(1, proof.executedScopeFileCount)
        assertEquals(1, proof.unvisitedObligationCount)
        assertEquals(0, proof.unvisitedClassificationObligationCount)
        assertEquals(1, proof.unexecutedRunnableObligationCount)
    }

    @Test
    fun `shared deadline cancels all file workers and waits for cleanup`() {
        val workersStarted = CountDownLatch(2)
        val activeWorkers = AtomicInteger()
        val cleanupCount = AtomicInteger()
        val proof = runProof(
            names = listOf("KotlinA", "KotlinB"),
            adapter = FakeAdapter(
                wrappers = mapOf("KotlinA" to Wrapper(), "KotlinB" to Wrapper()),
                onExecutionWrapper = { _, _ ->
                    activeWorkers.incrementAndGet()
                    workersStarted.countDown()
                    try {
                        workersStarted.await(2, TimeUnit.SECONDS)
                        Thread.sleep(5_000L)
                    } finally {
                        activeWorkers.decrementAndGet()
                    }
                },
                onCleanup = { _, _ -> cleanupCount.incrementAndGet() },
            ),
            timeoutMs = 100L,
            clock = System::nanoTime,
            filePaths = mapOf("KotlinA" to "/repo/A.kt", "KotlinB" to "/repo/B.kt"),
        )

        assertTrue(proof.hitTimeLimit)
        assertEquals(0, proof.executedToolCount)
        assertEquals(2, proof.unexecutedRunnableObligationCount)
        assertEquals(0, proof.executedScopeFileCount)
        assertEquals(2, cleanupCount.get())
        assertEquals(0, activeWorkers.get())
    }

    @Test
    fun `partial worker timeout preserves completed file counts`() {
        val fastFileCompleted = CountDownLatch(1)
        val cleanupCount = AtomicInteger()
        val proof = runProof(
            names = listOf("Fast", "SlowA", "SlowB"),
            adapter = FakeAdapter(
                wrappers = mapOf("Fast" to Wrapper(), "SlowA" to Wrapper(), "SlowB" to Wrapper()),
                onExecutionWrapper = { name, _ ->
                    if (name == "Fast") {
                        fastFileCompleted.countDown()
                    } else if (name == "SlowA") {
                        fastFileCompleted.await(2, TimeUnit.SECONDS)
                        Thread.sleep(5_000L)
                    }
                },
                onCleanup = { _, _ -> cleanupCount.incrementAndGet() },
            ),
            timeoutMs = 150L,
            clock = System::nanoTime,
            filePaths = mapOf(
                "Fast" to "/repo/Fast.kt",
                "SlowA" to "/repo/Slow.kt",
                "SlowB" to "/repo/Slow.kt",
            ),
        )

        assertTrue(proof.hitTimeLimit)
        assertEquals(1, proof.executedToolCount)
        assertEquals(1, proof.executedScopeFileCount)
        assertEquals(2, proof.unexecutedRunnableObligationCount)
        assertEquals(2, cleanupCount.get())
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
    fun `cancellation during batch capability classification propagates`() {
        assertThrows(CancellationSignal::class.java) {
            runProof(
                names = listOf("Kotlin"),
                adapter = FakeAdapter(
                    wrappers = mapOf("Kotlin" to Wrapper()),
                    onBatchCapability = { throw CancellationSignal() },
                ),
            )
        }
    }

    @Test
    fun `batch capability classification failure remains unproven`() {
        val proof = runProof(
            names = listOf("Kotlin"),
            adapter = FakeAdapter(
                wrappers = mapOf("Kotlin" to Wrapper()),
                onBatchCapability = { throw IllegalStateException("metadata failed") },
            ),
        )

        assertFalse(proof.proofEstablished)
        assertEquals(1, proof.unclassifiedObligationCount)
        assertEquals(1, proof.errorCount)
        assertEquals("unclassified_obligations", proof.proofBlockReason)
        assertEquals("classification_failed", proof.blockingExamples.single()["classification"])
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
    fun `cancellation stops parallel workers only after their cleanup completes`() {
        val workersStarted = CountDownLatch(2)
        val activeWorkers = AtomicInteger()
        val cleanupCount = AtomicInteger()

        assertThrows(CancellationSignal::class.java) {
            runProof(
                names = listOf("KotlinA", "KotlinB"),
                adapter = FakeAdapter(
                    wrappers = mapOf("KotlinA" to Wrapper(), "KotlinB" to Wrapper()),
                    onExecutionWrapper = { name, _ ->
                        activeWorkers.incrementAndGet()
                        workersStarted.countDown()
                        try {
                            workersStarted.await(2, TimeUnit.SECONDS)
                            if (name == "KotlinA") throw CancellationSignal()
                            Thread.sleep(5_000L)
                        } finally {
                            activeWorkers.decrementAndGet()
                        }
                    },
                    onCleanup = { _, _ -> cleanupCount.incrementAndGet() },
                ),
                clock = System::nanoTime,
                filePaths = mapOf("KotlinA" to "/repo/A.kt", "KotlinB" to "/repo/B.kt"),
            )
        }

        assertEquals(2, cleanupCount.get())
        assertEquals(0, activeWorkers.get())
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
    fun `descriptors outside the candidate file block clean proof`() {
        val adapter = object : ExactFileProofAdapter<String, String, Wrapper, String> by FakeAdapter(
            wrappers = mapOf("Kotlin" to Wrapper()),
            descriptors = mapOf("Kotlin" to listOf("outside")),
        ) {
            override fun mapDescriptor(
                candidate: ExactFileProofCandidate<String>,
                batchWrapper: Wrapper,
                descriptor: String,
            ): Map<String, Any> = mapOf(
                "tool" to candidate.shortName,
                "description" to descriptor,
                "file" to "/repo/Other.kt",
            )
        }

        val proof = runExactFileExecutionProof(
            enabledLocalToolCount = 1,
            candidates = listOf(ExactFileProofCandidate("Kotlin", "/repo/Test.kt", "Kotlin")),
            enumerationErrorCount = 0,
            timeoutMs = 1_000,
            nowNanos = { 0L },
            cancellationCheck = {},
            rethrowCancellation = {},
            problemKey = { problem -> problem.toString() },
            adapter = adapter,
        )

        assertFalse(proof.proofEstablished)
        assertEquals(1, proof.unmappedDescriptorCount)
        assertTrue(proof.proofProblems.isEmpty())
    }

    @Test
    fun `blocking diagnostic examples are deterministically bounded`() {
        val names = (1..20).map { "ApplicableUnfair$it" }
        val proof = runProof(
            names = names,
            adapter = FakeAdapter(
                names.associateWith {
                    Wrapper(
                        batchCapability = ExactFileProofBatchCapability.NON_BATCH_CAPABLE_EXCLUDED,
                        hasBatchWrapper = false,
                    )
                },
            ),
        )

        assertEquals(20, proof.nonBatchExcludedObligationCount)
        assertEquals(MAX_EXACT_FILE_PROOF_EXAMPLES, proof.nonBatchExamples.size)
        assertEquals("ApplicableUnfair1", proof.nonBatchExamples.first()["short_name"])
        assertEquals("ApplicableUnfair12", proof.nonBatchExamples.last()["short_name"])
    }

    @Test
    fun `candidate scope coverage requires one executed batch obligation per file`() {
        val proof = runProof(
            names = listOf("FileA", "FileB", "ExcludedOnly"),
            adapter = FakeAdapter(
                mapOf(
                    "FileA" to Wrapper(identity = "a"),
                    "FileB" to Wrapper(identity = "b"),
                    "ExcludedOnly" to Wrapper(
                        batchCapability = ExactFileProofBatchCapability.NON_BATCH_CAPABLE_EXCLUDED,
                        hasBatchWrapper = false,
                    ),
                ),
            ),
            filePaths = mapOf(
                "FileA" to "/repo/A.kt",
                "FileB" to "/repo/B.kt",
                "ExcludedOnly" to "/repo/C.kt",
            ),
        )

        assertFalse(proof.proofEstablished)
        assertEquals(2, proof.executedToolCount)
        assertEquals(2, proof.executedScopeFileCount)
        assertEquals(3, proof.candidateScopeFileCount)
        assertEquals(1, proof.nonBatchExcludedObligationCount)
        assertEquals(1, proof.missingScopeExecutionCoverageCount)
        assertEquals("candidate_file_execution_incomplete", proof.proofBlockReason)
    }
}
