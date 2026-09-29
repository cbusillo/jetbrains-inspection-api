package com.shiny.inspectionmcp

import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.Tools
import com.intellij.lang.Language
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.util.PairProcessor
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

internal class NativeInspectionToolExecution(
    val wrapper: InspectionToolWrapper<*, *>,
    val filePath: String?,
) {
    override fun equals(other: Any?): Boolean = other is NativeInspectionToolExecution &&
        wrapper === other.wrapper && filePath == other.filePath

    override fun hashCode(): Int = 31 * System.identityHashCode(wrapper) + (filePath?.hashCode() ?: 0)

    fun diagnostic(): Map<String, Any?> = mapOf("tool" to wrapper.shortName, "file" to filePath)
}

internal class NativeInspectionCompletionObservation {
    private val completed = linkedSetOf<NativeInspectionToolExecution>()
    private val candidates = linkedSetOf<NativeInspectionToolExecution>()
    private val exclusions = linkedMapOf<String, Int>()
    private val exclusionExamples = mutableListOf<Map<String, Any?>>()
    private var eventCount = 0
    private var negativeProblemCountEvents = 0
    private var enumerationCompleted = false
    private var unavailableReason: String? = null
    private val missingClassifications = linkedMapOf<NativeInspectionToolExecution, MissingCompletionClass>()
    private val applicableBatchExecutions = mutableSetOf<NativeInspectionToolExecution>()
    private var classificationCompleted = false
    private var classificationUnavailableReason: String? = null

    @Synchronized
    fun recordCompletion(problemCount: Int, wrapper: InspectionToolWrapper<*, *>, filePath: String?) {
        eventCount++
        if (problemCount < 0) {
            negativeProblemCountEvents++
        } else if (completed.size < MAX_EXECUTIONS) {
            completed += NativeInspectionToolExecution(wrapper, filePath)
        } else {
            unavailableReason = "completion_limit"
        }
    }

    @Synchronized
    fun observeCandidates(enumerate: (NativeInspectionCompletionObservation) -> Unit) {
        try {
            enumerate(this)
            enumerationCompleted = true
        } catch (error: Throwable) {
            unavailableReason = when (error) {
                is NativeInspectionObservationUnavailable -> error.message
                is ExactFileProofWritePreemptedException -> "observation_write_preempted"
                else -> error.javaClass.simpleName
            }
        }
    }

    @Synchronized
    fun classifyMissingCompletions(classify: (NativeInspectionCompletionObservation) -> Unit) {
        if (!enumerationCompleted || unavailableReason != null) return
        try {
            classify(this)
            classificationCompleted = true
        } catch (error: Throwable) {
            classificationUnavailableReason = when (error) {
                is NativeInspectionObservationUnavailable -> error.message
                is ExactFileProofWritePreemptedException -> "classification_write_preempted"
                else -> error.javaClass.simpleName
            }
        }
    }

    fun missingExecutions(): List<NativeInspectionToolExecution> = (candidates - completed).toList()

    fun classify(execution: NativeInspectionToolExecution, classification: MissingCompletionClass) {
        missingClassifications[execution] = classification
    }

    fun classifyApplicableBatchAnnotator(execution: NativeInspectionToolExecution) {
        classify(execution, MissingCompletionClass.EXTERNAL_ANNOTATOR_BATCH)
        applicableBatchExecutions += execution
    }

    /**
     * Clean proof needs every candidate tool/file pair to finish, or to be a proven silent skip: a tool whose
     * visitor is empty, or a batch annotator on a file it does not apply to.
     */
    @Synchronized
    fun unprovenCompletionReason(): String? {
        val explained = enumerationCompleted && unavailableReason == null && classificationCompleted &&
            candidates.isNotEmpty() &&
            (candidates - completed).all { execution ->
                when (missingClassifications[execution]) {
                    MissingCompletionClass.EMPTY_VISITOR -> true
                    MissingCompletionClass.EXTERNAL_ANNOTATOR_BATCH -> execution !in applicableBatchExecutions
                    else -> false
                }
            }
        return if (explained) null else UNPROVEN_TOOL_COMPLETION_REASON
    }

    fun candidate(wrapper: InspectionToolWrapper<*, *>, filePath: String?) {
        if (candidates.size >= MAX_EXECUTIONS) throw NativeInspectionObservationUnavailable("candidate_limit")
        candidates += NativeInspectionToolExecution(wrapper, filePath)
    }

    fun exclude(reason: String, wrapper: InspectionToolWrapper<*, *>, filePath: String?) {
        exclusions[reason] = (exclusions[reason] ?: 0) + 1
        if (exclusionExamples.size < EXAMPLE_LIMIT) {
            exclusionExamples += NativeInspectionToolExecution(wrapper, filePath).diagnostic() + ("reason" to reason)
        }
    }

    @Synchronized
    fun diagnostic(): Map<String, Any?> = try {
        val missing = candidates - completed
        val complete = enumerationCompleted && unavailableReason == null
        val classified = complete && classificationCompleted
        val unexplained = missing.filter { missingClassifications[it] != MissingCompletionClass.EMPTY_VISITOR }
        val classificationCounts = MissingCompletionClass.entries.associate { kind ->
            kind.diagnosticName to missing.count { missingClassifications[it] == kind }
        }
        mapOf(
            "schema_version" to 1,
            "mode" to "report_only",
            "enumeration_complete" to complete,
            "unavailable_reason" to unavailableReason,
            "candidate_execution_count" to candidates.size,
            "completion_event_count" to eventCount,
            "distinct_completion_count" to completed.size,
            "matched_candidate_count" to (candidates.size - missing.size),
            "missing_completion_count" to missing.size,
            "unmatched_completion_count" to (completed - candidates).size,
            "negative_problem_count_events" to negativeProblemCountEvents,
            "candidate_rule_would_block_clean" to if (complete) missing.isNotEmpty() || candidates.isEmpty() else null,
            "missing_examples" to missing.take(EXAMPLE_LIMIT).map { it.diagnostic() },
            "missing_classification_complete" to classified,
            "missing_classification_unavailable_reason" to classificationUnavailableReason,
            "missing_classification_counts" to if (classified) classificationCounts else null,
            "unexplained_missing_completion_count" to if (classified) unexplained.size else null,
            "silent_skip_rule_would_block_clean" to if (classified) unexplained.isNotEmpty() || candidates.isEmpty() else null,
            "unexplained_missing_examples" to if (classified) unexplained.take(EXAMPLE_LIMIT).map { it.diagnostic() } else null,
            "completed_examples" to completed.take(EXAMPLE_LIMIT).map { it.diagnostic() },
            "exclusions" to exclusions.toMap(),
            "exclusion_examples" to exclusionExamples.toList(),
            "examples_limit" to EXAMPLE_LIMIT,
            "limitations" to listOf(
                "candidates_from_post_run_profile_and_psi_not_scheduling_evidence",
                "empty_visitors_and_other_silent_skips_unobservable",
                "local_events_project_wide_concurrent_runs_may_overlap",
                "injected_files_external_annotators_and_aggregate_hooks_not_modeled",
                "missing_completion_is_not_inspection_failure",
                "matching_completions_do_not_establish_additional_execution_proof",
                "empty_visitor_classification_rebuilds_visitors_after_run",
            ),
        )
    } catch (error: Throwable) {
        mapOf("schema_version" to 1, "mode" to "report_only", "enumeration_complete" to false,
            "unavailable_reason" to error.javaClass.simpleName)
    }

    companion object {
        private const val MAX_EXECUTIONS = 100_000
        private const val EXAMPLE_LIMIT = 25
    }
}

internal const val UNPROVEN_TOOL_COMPLETION_REASON = "native_tool_completion_unproven"

internal enum class MissingCompletionClass(val diagnosticName: String) {
    EMPTY_VISITOR("empty_visitor"),
    NON_EMPTY_VISITOR("non_empty_visitor"),
    EXTERNAL_ANNOTATOR_BATCH("external_annotator_batch"),
    NOT_PROBED("not_probed"),
}

internal class NativeInspectionObservationUnavailable(reason: String) : RuntimeException(reason)

internal fun observeNativeInspectionCandidates(
    observation: NativeInspectionCompletionObservation,
    toolGroups: Collection<Tools>,
    files: List<PsiFile>,
    project: Project,
    includeDoNotShow: Boolean,
) {
    val deadline = System.nanoTime() + 2_000_000_000L
    var elementCount = 0
    fun checkBudget() {
        ProgressManager.checkCanceled()
        if (System.nanoTime() >= deadline) throw NativeInspectionObservationUnavailable("enumeration_time_limit")
        if (elementCount > 200_000) throw NativeInspectionObservationUnavailable("psi_element_limit")
    }
    val perFileGroups = toolGroups.filter { group ->
        checkBudget()
        when (val wrapper = group.tool) {
            is LocalInspectionToolWrapper -> true
            is GlobalInspectionToolWrapper -> if (wrapper.tool.isGlobalSimpleInspectionTool) {
                true
            } else {
                group.tools.forEach { state ->
                    if (state.isEnabled && state.getScope(project) != null) observation.candidate(state.tool, null)
                    else observation.exclude("disabled_or_unresolved_scope", state.tool, null)
                }
                false
            }
            else -> throw NativeInspectionObservationUnavailable("unsupported_tool_kind")
        }
    }
    for (file in files) {
        checkBudget()
        val filePath = file.virtualFile?.path ?: throw NativeInspectionObservationUnavailable("scope_file_unavailable")
        val languages = linkedMapOf<Language, PsiElement>()
        file.viewProvider.allFiles.forEach { root ->
            root.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    elementCount++
                    checkBudget()
                    languages.putIfAbsent(element.language, element)
                    super.visitElement(element)
                }
            })
        }
        val dialectIds = InspectionEngine.calcElementDialectIds(languages.values.toList(), emptyList())
        val wrappers = perFileGroups.mapNotNull { group ->
            checkBudget()
            group.getEnabledTool(file, includeDoNotShow).also {
                if (it == null) observation.exclude("disabled_for_file", group.tool, filePath)
            }
        }
        val locals = wrappers.filterIsInstance<LocalInspectionToolWrapper>()
        val applicable = InspectionEngine.filterToolsApplicableByLanguage(locals, dialectIds, dialectIds).toSet()
        wrappers.forEach { wrapper ->
            if (wrapper is LocalInspectionToolWrapper && wrapper !in applicable) {
                observation.exclude("language_not_applicable", wrapper, filePath)
            } else {
                observation.candidate(wrapper, filePath)
            }
        }
    }
}

internal fun classifyMissingNativeCompletions(
    observation: NativeInspectionCompletionObservation,
    files: List<PsiFile>,
    context: GlobalInspectionContext,
    project: Project,
    indicator: ProgressIndicator,
) {
    val deadline = System.nanoTime() + CLASSIFICATION_BUDGET_NANOS
    val deadlineCancellation = AppExecutorUtil.getAppScheduledExecutorService()
        .schedule({ indicator.cancel() }, CLASSIFICATION_BUDGET_NANOS, TimeUnit.NANOSECONDS)
    fun checkBudget() {
        if (System.nanoTime() >= deadline) throw NativeInspectionObservationUnavailable("classification_time_limit")
        indicator.checkCanceled()
    }
    try {
        val filesByPath = files.associateBy { it.virtualFile?.path }
        for (execution in observation.missingExecutions()) {
            checkBudget()
            val file = filesByPath[execution.filePath]
            val wrapper = execution.wrapper as? LocalInspectionToolWrapper
            if (file == null || wrapper == null) {
                observation.classify(execution, MissingCompletionClass.NOT_PROBED)
                continue
            }
            if (wrapper.tool is ExternalAnnotatorBatchInspection) {
                if (batchAnnotatorMayApply(wrapper, file)) {
                    observation.classifyApplicableBatchAnnotator(execution)
                } else {
                    observation.classify(execution, MissingCompletionClass.EXTERNAL_ANNOTATOR_BATCH)
                }
                continue
            }
            var visitorWasEmpty: Boolean? = null
            val copy = InspectionProfileImpl.copyToolSettings(wrapper) as LocalInspectionToolWrapper
            try {
                copy.initialize(context)
                val probe = LocalInspectionToolWrapper(VisitorProbeInspection(copy.tool) { visitorWasEmpty = it })
                InspectionEngine.inspectEx(
                    listOf(probe), file, file.textRange, file.textRange, false, false, false, indicator, PairProcessor.alwaysTrue(),
                )
            } finally {
                runCatching { copy.cleanup(project) }
            }
            checkBudget()
            observation.classify(
                execution,
                when (visitorWasEmpty) {
                    true -> MissingCompletionClass.EMPTY_VISITOR
                    false -> MissingCompletionClass.NON_EMPTY_VISITOR
                    null -> MissingCompletionClass.NOT_PROBED
                },
            )
        }
    } catch (cancelled: ProcessCanceledException) {
        if (System.nanoTime() >= deadline) throw NativeInspectionObservationUnavailable("classification_time_limit")
        throw cancelled
    } finally {
        deadlineCancellation.cancel(false)
    }
}

private const val CLASSIFICATION_BUDGET_NANOS = 2_000_000_000L

private class VisitorProbeInspection(
    private val target: LocalInspectionTool,
    private val onVisitorBuilt: (Boolean) -> Unit,
) : LocalInspectionTool() {
    override fun getShortName(): String = "InspectionApiVisitorProbe." + target.shortName

    override fun getDisplayName(): String = shortName

    override fun getGroupDisplayName(): String = "Inspection API"

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
        session: LocalInspectionToolSession,
    ): PsiElementVisitor {
        val visitor = InspectionEngine.createVisitor(target, holder, isOnTheFly, session)
        onVisitorBuilt(visitor === PsiElementVisitor.EMPTY_VISITOR)
        return PsiElementVisitor.EMPTY_VISITOR
    }
}
