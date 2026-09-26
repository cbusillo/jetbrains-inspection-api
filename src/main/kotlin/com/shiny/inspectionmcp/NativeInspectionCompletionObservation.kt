package com.shiny.inspectionmcp

import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.Tools
import com.intellij.lang.Language
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

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
