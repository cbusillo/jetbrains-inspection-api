package com.shiny.inspectionmcp

import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal const val MAX_EXACT_FILE_PROOF_EXAMPLES = 12

internal class ExactFileProofTimeLimitExceededException : RuntimeException()

internal enum class ExactFileProofClassification(val diagnosticValue: String) {
    PROFILE_DISABLED("profile_disabled"),
    LANGUAGE_NOT_APPLICABLE("language_not_applicable"),
    BATCH_RUNNABLE("batch_runnable"),
    APPLICABLE_MISSING_BATCH_WRAPPER("applicable_missing_batch_wrapper"),
    DISPLAY_KEY_UNRESOLVED("display_key_unresolved"),
    SOURCE_WRAPPER_UNRESOLVED("source_wrapper_unresolved"),
    CLASSIFICATION_FAILED("classification_failed"),
    EXECUTION_FAILED("execution_failed"),
    DESCRIPTOR_UNMAPPED("descriptor_unmapped"),
    TIME_LIMIT("time_limit"),
}

internal data class ExactFileProofCandidate<T>(
    val shortName: String,
    val filePath: String,
    val value: T,
)

internal fun exactFileProofProblemMatchesCandidate(
    candidateFilePath: String,
    problem: Map<String, Any>,
): Boolean {
    val problemFilePath = (problem["file"] as? String)?.takeIf { it.isNotBlank() } ?: return false
    val normalizedCandidatePath = runCatching { Paths.get(candidateFilePath).normalize().toAbsolutePath() }.getOrNull()
        ?: return false
    val normalizedProblemPath = runCatching { Paths.get(problemFilePath).normalize().toAbsolutePath() }.getOrNull()
        ?: return false
    return normalizedProblemPath == normalizedCandidatePath
}

internal interface ExactFileProofAdapter<T, K, W, D> {
    fun resolveDisplayKey(candidate: ExactFileProofCandidate<T>): K?

    fun isProfileEnabled(candidate: ExactFileProofCandidate<T>, displayKey: K): Boolean

    fun resolveSourceWrapper(candidate: ExactFileProofCandidate<T>): W?

    fun isLanguageApplicable(candidate: ExactFileProofCandidate<T>, sourceWrapper: W): Boolean

    fun resolveBatchWrapper(candidate: ExactFileProofCandidate<T>, sourceWrapper: W): W?

    fun copyBatchWrapper(candidate: ExactFileProofCandidate<T>, batchWrapper: W): W = batchWrapper

    fun initializeBatchWrapper(candidate: ExactFileProofCandidate<T>, batchWrapper: W) = Unit

    fun execute(candidate: ExactFileProofCandidate<T>, batchWrapper: W): List<D>

    fun mapDescriptor(candidate: ExactFileProofCandidate<T>, batchWrapper: W, descriptor: D): Map<String, Any>?

    fun cleanupBatchWrapper(candidate: ExactFileProofCandidate<T>, batchWrapper: W) = Unit

    fun diagnosticRow(
        candidate: ExactFileProofCandidate<T>,
        classification: ExactFileProofClassification,
        sourceWrapper: W? = null,
        batchWrapper: W? = null,
        detail: String? = null,
    ): Map<String, Any?>
}

internal data class BoundedExecutionProofResult(
    val proofProblems: List<Map<String, Any>>,
    val enabledLocalToolCount: Int,
    val executedToolCount: Int = 0,
    val totalDescriptorCount: Int = 0,
    val skippedReason: String? = null,
    val candidateObligationCount: Int = 0,
    val profileEnabledObligationCount: Int = 0,
    val profileDisabledObligationCount: Int = 0,
    val displayKeyMissingCount: Int = 0,
    val sourceWrapperMissingCount: Int = 0,
    val languageApplicableObligationCount: Int = 0,
    val languageNonApplicableObligationCount: Int = 0,
    val batchRunnableObligationCount: Int = 0,
    val unmappedDescriptorCount: Int = 0,
    val missingWrapperCount: Int = 0,
    val failedObligationCount: Int = 0,
    val unclassifiedObligationCount: Int = 0,
    val unvisitedClassificationObligationCount: Int = 0,
    val unexecutedRunnableObligationCount: Int = 0,
    val unvisitedDescriptorCount: Int = 0,
    val enumerationErrorCount: Int = 0,
    val errorCount: Int = 0,
    val hitFileLimit: Boolean = false,
    val hitTimeLimit: Boolean = false,
    val elapsedMs: Long = 0,
    val blockingExamples: List<Map<String, Any?>> = emptyList(),
    val nonApplicableExamples: List<Map<String, Any?>> = emptyList(),
) {
    val proofEstablished: Boolean
        get() = skippedReason == null &&
            !hitFileLimit &&
            !hitTimeLimit &&
            enumerationErrorCount == 0 &&
            displayKeyMissingCount == 0 &&
            sourceWrapperMissingCount == 0 &&
            missingWrapperCount == 0 &&
            failedObligationCount == 0 &&
            unclassifiedObligationCount == 0 &&
            unvisitedClassificationObligationCount == 0 &&
            unexecutedRunnableObligationCount == 0 &&
            unvisitedDescriptorCount == 0 &&
            unmappedDescriptorCount == 0 &&
            errorCount == 0 &&
            batchRunnableObligationCount > 0 &&
            executedToolCount == batchRunnableObligationCount

    val proofClean: Boolean
        get() = proofEstablished && proofProblems.isEmpty()

    val proofBlockReason: String?
        get() = when {
            skippedReason != null -> skippedReason
            hitFileLimit -> "file_limit"
            hitTimeLimit -> "time_limit"
            enumerationErrorCount > 0 -> "enabled_tool_enumeration_incomplete"
            displayKeyMissingCount > 0 -> "display_key_unresolved"
            sourceWrapperMissingCount > 0 -> "source_wrapper_unresolved"
            missingWrapperCount > 0 -> "applicable_missing_batch_wrapper"
            failedObligationCount > 0 -> "execution_failed"
            unclassifiedObligationCount > 0 -> "unclassified_obligations"
            unvisitedClassificationObligationCount > 0 -> "unvisited_classification_obligations"
            unexecutedRunnableObligationCount > 0 -> "unexecuted_runnable_obligations"
            unvisitedDescriptorCount > 0 -> "unvisited_descriptors"
            unmappedDescriptorCount > 0 -> "descriptor_mapping_incomplete"
            errorCount > 0 -> "proof_errors"
            batchRunnableObligationCount == 0 -> "no_applicable_batch_tools"
            executedToolCount != batchRunnableObligationCount -> "applicable_execution_incomplete"
            else -> null
        }

    val unvisitedObligationCount: Int
        get() = unvisitedClassificationObligationCount + unexecutedRunnableObligationCount
}

private data class RunnableExactFileProofObligation<T, W>(
    val candidate: ExactFileProofCandidate<T>,
    val sourceWrapper: W,
    val batchWrapper: W,
)

private class ExactFileProofExecutionState<T, W, D>(
    val obligation: RunnableExactFileProofObligation<T, W>,
) {
    var executionWrapper: W? = null
    var executed = false
    var completed = false
    var descriptorCount = 0
    var unvisitedDescriptorCount = 0
    var mappingErrorCount = 0
    val mappedProblems = mutableListOf<Map<String, Any>>()
    val unmappedDetails = mutableListOf<String>()
    var failureDetail: String? = null
    var timeoutStage: String? = null
}

private class ExactFileProofAccumulator(
    private val enabledLocalToolCount: Int,
    private val candidateObligationCount: Int,
    private val enumerationErrorCount: Int,
) {
    val problems = mutableListOf<Map<String, Any>>()
    val seenProblems = linkedSetOf<String>()
    val blockingExamples = mutableListOf<Map<String, Any?>>()
    val nonApplicableExamples = mutableListOf<Map<String, Any?>>()
    var profileEnabledObligationCount = 0
    var profileDisabledObligationCount = 0
    var displayKeyMissingCount = 0
    var sourceWrapperMissingCount = 0
    var languageApplicableObligationCount = 0
    var languageNonApplicableObligationCount = 0
    var batchRunnableObligationCount = 0
    var executedToolCount = 0
    var totalDescriptorCount = 0
    var unmappedDescriptorCount = 0
    var missingWrapperCount = 0
    var failedObligationCount = 0
    var unclassifiedObligationCount = 0
    var unvisitedClassificationObligationCount = 0
    var unexecutedRunnableObligationCount = 0
    var unvisitedDescriptorCount = 0
    var errorCount = 0
    var hitTimeLimit = false

    fun addBlockingExample(example: Map<String, Any?>) {
        if (blockingExamples.size < MAX_EXACT_FILE_PROOF_EXAMPLES) blockingExamples += example
    }

    fun addNonApplicableExample(example: Map<String, Any?>) {
        if (nonApplicableExamples.size < MAX_EXACT_FILE_PROOF_EXAMPLES) nonApplicableExamples += example
    }

    fun result(startNanos: Long, nowNanos: () -> Long): BoundedExecutionProofResult =
        BoundedExecutionProofResult(
            proofProblems = problems,
            enabledLocalToolCount = enabledLocalToolCount,
            candidateObligationCount = candidateObligationCount,
            profileEnabledObligationCount = profileEnabledObligationCount,
            profileDisabledObligationCount = profileDisabledObligationCount,
            displayKeyMissingCount = displayKeyMissingCount,
            sourceWrapperMissingCount = sourceWrapperMissingCount,
            languageApplicableObligationCount = languageApplicableObligationCount,
            languageNonApplicableObligationCount = languageNonApplicableObligationCount,
            batchRunnableObligationCount = batchRunnableObligationCount,
            executedToolCount = executedToolCount,
            totalDescriptorCount = totalDescriptorCount,
            unmappedDescriptorCount = unmappedDescriptorCount,
            missingWrapperCount = missingWrapperCount,
            failedObligationCount = failedObligationCount,
            unclassifiedObligationCount = unclassifiedObligationCount,
            unvisitedClassificationObligationCount = unvisitedClassificationObligationCount,
            unexecutedRunnableObligationCount = unexecutedRunnableObligationCount,
            unvisitedDescriptorCount = unvisitedDescriptorCount,
            enumerationErrorCount = enumerationErrorCount,
            errorCount = errorCount,
            skippedReason = null,
            hitTimeLimit = hitTimeLimit,
            elapsedMs = ((nowNanos() - startNanos).coerceAtLeast(0L) / 1_000_000L),
            blockingExamples = blockingExamples,
            nonApplicableExamples = nonApplicableExamples,
        )
}

internal fun <T, K, W, D> runExactFileExecutionProof(
    enabledLocalToolCount: Int,
    candidates: List<ExactFileProofCandidate<T>>,
    enumerationErrorCount: Int,
    timeoutMs: Long,
    nowNanos: () -> Long,
    startNanos: Long = nowNanos(),
    cancellationCheck: () -> Unit,
    rethrowCancellation: (Exception) -> Unit,
    problemKey: (Map<String, Any>) -> String,
    adapter: ExactFileProofAdapter<T, K, W, D>,
    maxParallelFiles: Int = 4,
): BoundedExecutionProofResult {
    val timeoutNanos = timeoutMs.coerceAtLeast(0L) * 1_000_000L
    val accumulator = ExactFileProofAccumulator(enabledLocalToolCount, candidates.size, enumerationErrorCount)
    val runnable = mutableListOf<RunnableExactFileProofObligation<T, W>>()

    fun deadlineExceeded(): Boolean = nowNanos() - startNanos > timeoutNanos

    fun safeDiagnostic(
        candidate: ExactFileProofCandidate<T>,
        classification: ExactFileProofClassification,
        sourceWrapper: W? = null,
        batchWrapper: W? = null,
        detail: String? = null,
    ): Map<String, Any?> = try {
        adapter.diagnosticRow(candidate, classification, sourceWrapper, batchWrapper, detail)
    } catch (error: Exception) {
        rethrowCancellation(error)
        mapOf(
            "short_name" to candidate.shortName,
            "file" to candidate.filePath,
            "classification" to classification.diagnosticValue,
            "detail" to detail,
        ).filterValues { it != null }
    }

    fun timeoutResult(currentIndex: Int, current: ExactFileProofCandidate<T>): BoundedExecutionProofResult {
        accumulator.hitTimeLimit = true
        accumulator.unvisitedClassificationObligationCount += candidates.size - currentIndex
        accumulator.addBlockingExample(safeDiagnostic(current, ExactFileProofClassification.TIME_LIMIT, detail = "classification"))
        candidates.drop(currentIndex + 1).take(MAX_EXACT_FILE_PROOF_EXAMPLES - accumulator.blockingExamples.size).forEach { candidate ->
            accumulator.addBlockingExample(safeDiagnostic(candidate, ExactFileProofClassification.TIME_LIMIT, detail = "unvisited"))
        }
        accumulator.unexecutedRunnableObligationCount += runnable.size
        return accumulator.result(startNanos, nowNanos)
    }

    for ((index, candidate) in candidates.withIndex()) {
        cancellationCheck()
        if (deadlineExceeded()) return timeoutResult(index, candidate)

        val displayKey = try {
            adapter.resolveDisplayKey(candidate)
        } catch (error: Exception) {
            if (error is ExactFileProofTimeLimitExceededException) return timeoutResult(index, candidate)
            rethrowCancellation(error)
            accumulator.unclassifiedObligationCount++
            accumulator.errorCount++
            accumulator.addBlockingExample(
                safeDiagnostic(candidate, ExactFileProofClassification.CLASSIFICATION_FAILED, detail = "display_key:${error.javaClass.simpleName}"),
            )
            continue
        }
        cancellationCheck()
        if (deadlineExceeded()) return timeoutResult(index, candidate)
        if (displayKey == null) {
            accumulator.displayKeyMissingCount++
            accumulator.unclassifiedObligationCount++
            accumulator.addBlockingExample(safeDiagnostic(candidate, ExactFileProofClassification.DISPLAY_KEY_UNRESOLVED))
            continue
        }

        val profileEnabled = try {
            adapter.isProfileEnabled(candidate, displayKey)
        } catch (error: Exception) {
            if (error is ExactFileProofTimeLimitExceededException) return timeoutResult(index, candidate)
            rethrowCancellation(error)
            accumulator.unclassifiedObligationCount++
            accumulator.errorCount++
            accumulator.addBlockingExample(
                safeDiagnostic(candidate, ExactFileProofClassification.CLASSIFICATION_FAILED, detail = "profile_enablement:${error.javaClass.simpleName}"),
            )
            continue
        }
        cancellationCheck()
        if (deadlineExceeded()) return timeoutResult(index, candidate)
        if (!profileEnabled) {
            accumulator.profileDisabledObligationCount++
            continue
        }
        accumulator.profileEnabledObligationCount++

        val sourceWrapper = try {
            adapter.resolveSourceWrapper(candidate)
        } catch (error: Exception) {
            if (error is ExactFileProofTimeLimitExceededException) return timeoutResult(index, candidate)
            rethrowCancellation(error)
            accumulator.unclassifiedObligationCount++
            accumulator.errorCount++
            accumulator.addBlockingExample(
                safeDiagnostic(candidate, ExactFileProofClassification.CLASSIFICATION_FAILED, detail = "source_wrapper:${error.javaClass.simpleName}"),
            )
            continue
        }
        cancellationCheck()
        if (deadlineExceeded()) return timeoutResult(index, candidate)
        if (sourceWrapper == null) {
            accumulator.sourceWrapperMissingCount++
            accumulator.unclassifiedObligationCount++
            accumulator.addBlockingExample(safeDiagnostic(candidate, ExactFileProofClassification.SOURCE_WRAPPER_UNRESOLVED))
            continue
        }

        val languageApplicable = try {
            adapter.isLanguageApplicable(candidate, sourceWrapper)
        } catch (error: Exception) {
            if (error is ExactFileProofTimeLimitExceededException) return timeoutResult(index, candidate)
            rethrowCancellation(error)
            accumulator.unclassifiedObligationCount++
            accumulator.errorCount++
            accumulator.addBlockingExample(
                safeDiagnostic(
                    candidate,
                    ExactFileProofClassification.CLASSIFICATION_FAILED,
                    sourceWrapper = sourceWrapper,
                    detail = "language_applicability:${error.javaClass.simpleName}",
                ),
            )
            continue
        }
        cancellationCheck()
        if (deadlineExceeded()) return timeoutResult(index, candidate)
        if (!languageApplicable) {
            accumulator.languageNonApplicableObligationCount++
            accumulator.addNonApplicableExample(
                safeDiagnostic(candidate, ExactFileProofClassification.LANGUAGE_NOT_APPLICABLE, sourceWrapper = sourceWrapper),
            )
            continue
        }
        accumulator.languageApplicableObligationCount++

        val batchWrapper = try {
            adapter.resolveBatchWrapper(candidate, sourceWrapper)
        } catch (error: Exception) {
            if (error is ExactFileProofTimeLimitExceededException) return timeoutResult(index, candidate)
            rethrowCancellation(error)
            accumulator.unclassifiedObligationCount++
            accumulator.errorCount++
            accumulator.addBlockingExample(
                safeDiagnostic(
                    candidate,
                    ExactFileProofClassification.CLASSIFICATION_FAILED,
                    sourceWrapper = sourceWrapper,
                    detail = "batch_wrapper:${error.javaClass.simpleName}",
                ),
            )
            continue
        }
        cancellationCheck()
        if (deadlineExceeded()) return timeoutResult(index, candidate)
        if (batchWrapper == null) {
            accumulator.missingWrapperCount++
            accumulator.addBlockingExample(
                safeDiagnostic(
                    candidate,
                    ExactFileProofClassification.APPLICABLE_MISSING_BATCH_WRAPPER,
                    sourceWrapper = sourceWrapper,
                ),
            )
            continue
        }
        accumulator.batchRunnableObligationCount++
        runnable += RunnableExactFileProofObligation(candidate, sourceWrapper, batchWrapper)
    }

    val executionStates = runnable.map { obligation -> ExactFileProofExecutionState<T, W, D>(obligation) }
    val deadlineTriggered = AtomicBoolean(false)
    val externalCancellation = AtomicReference<RuntimeException?>()

    fun requestExternalCancellation(error: RuntimeException) {
        externalCancellation.compareAndSet(null, error)
    }

    fun checkExecutionBudget() {
        val cancellation = externalCancellation.get()
        if (cancellation != null) throw cancellation
        try {
            cancellationCheck()
        } catch (error: RuntimeException) {
            requestExternalCancellation(error)
            throw error
        }
        if (deadlineTriggered.get() || deadlineExceeded()) {
            deadlineTriggered.set(true)
            throw ExactFileProofTimeLimitExceededException()
        }
    }

    fun classifyExecutionError(error: Exception): RuntimeException? {
        if (error is ExactFileProofTimeLimitExceededException) {
            deadlineTriggered.set(true)
            return null
        }
        val cancellation = try {
            rethrowCancellation(error)
            null
        } catch (cancellation: RuntimeException) {
            requestExternalCancellation(cancellation)
            cancellation
        }
        if (cancellation != null) return cancellation
        if (deadlineTriggered.get() || deadlineExceeded()) {
            deadlineTriggered.set(true)
        }
        return null
    }

    fun executeState(state: ExactFileProofExecutionState<T, W, D>): Boolean {
        val obligation = state.obligation
        var stage = "wrapper_copy"
        var executionWrapper: W? = null
        var stopFileWorker = false
        try {
            checkExecutionBudget()
            executionWrapper = adapter.copyBatchWrapper(obligation.candidate, obligation.batchWrapper)
            state.executionWrapper = executionWrapper
            stage = "wrapper_initialize"
            adapter.initializeBatchWrapper(obligation.candidate, executionWrapper)
            checkExecutionBudget()
            stage = "execution"
            val descriptors = adapter.execute(obligation.candidate, executionWrapper)
            state.executed = true
            state.descriptorCount = descriptors.size
            try {
                checkExecutionBudget()
            } catch (error: ExactFileProofTimeLimitExceededException) {
                state.unvisitedDescriptorCount = descriptors.size
                throw error
            }
            stage = "descriptor_mapping"
            for ((descriptorIndex, descriptor) in descriptors.withIndex()) {
                try {
                    checkExecutionBudget()
                } catch (error: ExactFileProofTimeLimitExceededException) {
                    state.unvisitedDescriptorCount = descriptors.size - descriptorIndex
                    throw error
                }
                val mapped = try {
                    adapter.mapDescriptor(obligation.candidate, executionWrapper, descriptor)
                } catch (error: Exception) {
                    val cancellation = classifyExecutionError(error)
                    if (cancellation != null) throw cancellation
                    if (deadlineTriggered.get()) {
                        state.unvisitedDescriptorCount = descriptors.size - descriptorIndex
                        throw ExactFileProofTimeLimitExceededException()
                    }
                    state.mappingErrorCount++
                    null
                }
                try {
                    checkExecutionBudget()
                } catch (error: ExactFileProofTimeLimitExceededException) {
                    state.unvisitedDescriptorCount = descriptors.size - descriptorIndex
                    throw error
                }
                if (mapped == null || !exactFileProofProblemMatchesCandidate(obligation.candidate.filePath, mapped)) {
                    state.unmappedDetails += if (mapped == null) {
                        "mapping_returned_null"
                    } else {
                        "descriptor_outside_candidate_file"
                    }
                } else {
                    state.mappedProblems += mapped
                }
            }
        } catch (error: Exception) {
            val cancellation = classifyExecutionError(error)
            when {
                cancellation != null -> stopFileWorker = true
                deadlineTriggered.get() -> {
                    state.timeoutStage = stage
                    stopFileWorker = true
                }
                else -> state.failureDetail = "$stage:${error.javaClass.simpleName}"
            }
        } finally {
            if (executionWrapper != null) {
                try {
                    adapter.cleanupBatchWrapper(obligation.candidate, executionWrapper)
                } catch (error: Exception) {
                    val cancellation = classifyExecutionError(error)
                    when {
                        cancellation != null -> stopFileWorker = true
                        deadlineTriggered.get() -> {
                            state.timeoutStage = state.timeoutStage ?: "wrapper_cleanup"
                            stopFileWorker = true
                        }
                        state.failureDetail == null -> state.failureDetail = "wrapper_cleanup:${error.javaClass.simpleName}"
                    }
                }
            }
        }
        state.completed = state.executed &&
            state.unvisitedDescriptorCount == 0 &&
            state.failureDetail == null &&
            state.timeoutStage == null
        return !stopFileWorker && externalCancellation.get() == null && !deadlineTriggered.get()
    }

    fun executeFile(states: List<ExactFileProofExecutionState<T, W, D>>) {
        for (state in states) {
            if (!executeState(state)) break
        }
    }

    val statesByFile = executionStates.groupBy { state ->
        runCatching { Paths.get(state.obligation.candidate.filePath).normalize().toAbsolutePath().toString() }
            .getOrDefault(state.obligation.candidate.filePath)
    }
    if (statesByFile.size <= 1 || maxParallelFiles <= 1) {
        statesByFile.values.forEach(::executeFile)
    } else {
        val threadNumber = AtomicInteger()
        val executor = Executors.newFixedThreadPool(
            minOf(statesByFile.size, maxParallelFiles.coerceAtLeast(1)),
        ) { task ->
            val thread = Thread(task, "exact-file-proof-${threadNumber.incrementAndGet()}")
            thread.isDaemon = true
            thread
        }
        statesByFile.values.forEach { states -> executor.execute { executeFile(states) } }
        executor.shutdown()
        var stopRequested = false
        var waitInterrupted = false
        while (true) {
            val terminated = try {
                executor.awaitTermination(25L, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                waitInterrupted = true
                requestExternalCancellation(RuntimeException(error))
                false
            }
            if (terminated) break
            if (externalCancellation.get() != null) {
                stopRequested = true
            } else {
                try {
                    cancellationCheck()
                } catch (error: RuntimeException) {
                    requestExternalCancellation(error)
                    stopRequested = true
                }
                if (deadlineExceeded()) {
                    deadlineTriggered.set(true)
                    stopRequested = true
                }
            }
            if (stopRequested) executor.shutdownNow()
        }
        if (waitInterrupted) Thread.currentThread().interrupt()
    }

    val cancellation = externalCancellation.get()
    if (cancellation != null) throw cancellation

    accumulator.hitTimeLimit = deadlineTriggered.get()
    for (state in executionStates) {
        cancellationCheck()
        if (deadlineExceeded()) accumulator.hitTimeLimit = true
        val obligation = state.obligation
        val diagnosticWrapper = state.executionWrapper ?: obligation.batchWrapper
        if (state.executed) {
            accumulator.executedToolCount++
            accumulator.totalDescriptorCount += state.descriptorCount
        } else if (state.failureDetail == null) {
            accumulator.unexecutedRunnableObligationCount++
        }
        accumulator.unvisitedDescriptorCount += state.unvisitedDescriptorCount
        accumulator.errorCount += state.mappingErrorCount
        if (state.failureDetail != null) {
            accumulator.failedObligationCount++
            accumulator.errorCount++
            accumulator.addBlockingExample(
                safeDiagnostic(
                    obligation.candidate,
                    ExactFileProofClassification.EXECUTION_FAILED,
                    obligation.sourceWrapper,
                    diagnosticWrapper,
                    state.failureDetail,
                ),
            )
        }
        if (state.timeoutStage != null) {
            accumulator.addBlockingExample(
                safeDiagnostic(
                    obligation.candidate,
                    ExactFileProofClassification.TIME_LIMIT,
                    obligation.sourceWrapper,
                    diagnosticWrapper,
                    state.timeoutStage,
                ),
            )
        }
        for (detail in state.unmappedDetails) {
            accumulator.unmappedDescriptorCount++
            accumulator.addBlockingExample(
                safeDiagnostic(
                    obligation.candidate,
                    ExactFileProofClassification.DESCRIPTOR_UNMAPPED,
                    obligation.sourceWrapper,
                    diagnosticWrapper,
                    detail,
                ),
            )
        }
        for (mapped in state.mappedProblems) {
            cancellationCheck()
            if (deadlineExceeded()) accumulator.hitTimeLimit = true
            val mappedProblemKey = problemKey(mapped)
            if (accumulator.seenProblems.add(mappedProblemKey)) accumulator.problems += mapped
        }
    }
    if (deadlineExceeded()) accumulator.hitTimeLimit = true
    if (accumulator.hitTimeLimit && executionStates.none { it.timeoutStage != null }) {
        val timeoutState = executionStates.firstOrNull { !it.completed && it.failureDetail == null }
            ?: executionStates.lastOrNull()
        if (timeoutState != null) {
            val obligation = timeoutState.obligation
            accumulator.addBlockingExample(
                safeDiagnostic(
                    obligation.candidate,
                    ExactFileProofClassification.TIME_LIMIT,
                    obligation.sourceWrapper,
                    timeoutState.executionWrapper ?: obligation.batchWrapper,
                    when {
                        timeoutState.completed -> "aggregation"
                        timeoutState.executed -> "descriptor_mapping"
                        else -> "execution"
                    },
                ),
            )
        }
    }

    return accumulator.result(startNanos, nowNanos)
}
