package com.shiny.inspectionmcp

import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.Tools
import com.intellij.lang.ExternalLanguageAnnotators
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile

internal const val UNPROVEN_C_FAMILY_BATCH_ANNOTATOR_REASON = "native_cpp_batch_annotator_unproven"
internal const val UNPROVEN_BATCH_ANNOTATOR_REASON = "native_batch_annotator_unproven"

/**
 * External annotators give up silently: ShellCheck's stops the process after ten seconds and returns no result,
 * which the platform's `checkFile` reports exactly like a clean file. A run this long is not proof of a clean result.
 */
internal const val EXTERNAL_ANNOTATOR_TIMEOUT_SIGNATURE_MS = 9_000L

private val C_FAMILY_FILE_TYPE_NAMES = setOf("C/C++", "C/C++ Header", "C++", "ObjectiveC")
private val C_FAMILY_LANGUAGE_IDS = setOf("C++", "C", "ObjectiveC")
private val C_FAMILY_SOURCE_EXTENSIONS = setOf(
    "c", "cc", "cp", "cpp", "cxx", "c++", "cppm", "ixx", "cxxm", "c++m", "ccm", "mxx",
    "h", "hh", "hp", "hpp", "hxx", "h++", "icc", "inl", "ipp", "tpp", "tcc",
    "m", "mm", "cu", "cuh",
)

internal fun isCFamilySourcePath(path: String): Boolean {
    val name = path.substringAfterLast('/')
    return name.contains('.') && name.substringAfterLast('.').lowercase() in C_FAMILY_SOURCE_EXTENSIONS
}

private fun isCFamilyFile(file: PsiFile): Boolean =
    file.fileType.name in C_FAMILY_FILE_TYPE_NAMES ||
        file.viewProvider.languages.any { it.id in C_FAMILY_LANGUAGE_IDS } ||
        file.virtualFile?.path?.let(::isCFamilySourcePath) == true

/**
 * The batch runner calls [ExternalAnnotatorBatchInspection] tools through `checkFile` without publishing
 * `inspectionFinished`, so their completion can never be proven on a file they apply to. A language-applicable
 * tool applies to C/C++ files, to files of the language it declares (for example ESLint on JavaScript), and to
 * files with an external annotator paired with it, which is how the default `checkFile` finds its work.
 * Tools that declare no language and override `checkFile` (such as clion-radler) are taken to apply to C/C++ only.
 * [provenRuns] lists tool/file pairs whose `checkFile` an exact-scope proof ran to completion itself.
 * The result names up to [MAX_EXACT_FILE_PROOF_EXAMPLES] blocking tool/file pairs so a caller can inspect around them.
 */
internal fun unprovenBatchAnnotators(
    toolGroups: Collection<Tools>,
    files: List<PsiFile>,
    includeDoNotShow: Boolean,
    provenRuns: Set<Pair<String, String>> = emptySet(),
    checkBudget: () -> Unit = {},
): UnprovenBatchAnnotators? {
    val batchAnnotatorGroups = toolGroups.filter { group ->
        checkBudget()
        (group.tool as? LocalInspectionToolWrapper)?.tool is ExternalAnnotatorBatchInspection
    }
    if (batchAnnotatorGroups.isEmpty()) return null
    var blocked = false
    var cFamilyBlocked = false
    val examples = mutableListOf<Map<String, String>>()
    for (file in files) {
        ProgressManager.checkCanceled()
        try {
            checkBudget()
        } catch (exhausted: NativeInspectionObservationUnavailable) {
            if (!blocked) throw exhausted
            break
        }
        val cFamily = isCFamilyFile(file)
        if (blocked && examples.size >= MAX_EXACT_FILE_PROOF_EXAMPLES && (cFamilyBlocked || !cFamily)) continue
        val enabled = batchAnnotatorGroups.mapNotNull { group ->
            group.getEnabledTool(file, includeDoNotShow) as? LocalInspectionToolWrapper
        }
        val dialectIds = InspectionEngine.calcElementDialectIds(file.viewProvider.allFiles, emptyList())
        val filePath = file.virtualFile?.path
        val blocking = InspectionEngine.filterToolsApplicableByLanguage(enabled, dialectIds, dialectIds)
            .filterNot { (it.shortName to filePath) in provenRuns }
            .filter { cFamily || batchAnnotatorMayApply(it, file) }
        if (blocking.isEmpty()) continue
        blocked = true
        cFamilyBlocked = cFamilyBlocked || cFamily
        blocking.take(MAX_EXACT_FILE_PROOF_EXAMPLES - examples.size).mapTo(examples) { wrapper ->
            mapOf("tool" to wrapper.shortName, "file" to (filePath ?: file.name))
        }
    }
    if (!blocked) return null
    return UnprovenBatchAnnotators(
        if (cFamilyBlocked) UNPROVEN_C_FAMILY_BATCH_ANNOTATOR_REASON else UNPROVEN_BATCH_ANNOTATOR_REASON,
        examples,
    )
}

internal data class UnprovenBatchAnnotators(
    val reason: String,
    val examples: List<Map<String, String>>,
)

internal fun unprovenBatchAnnotatorNextAction(examples: List<*>?): String {
    val pairs = examples.orEmpty().mapNotNull { example ->
        val entry = example as? Map<*, *> ?: return@mapNotNull null
        val tool = entry["tool"] as? String ?: return@mapNotNull null
        val file = entry["file"] as? String ?: return@mapNotNull null
        "$tool on $file"
    }
    val blocking = if (pairs.isEmpty()) {
        "batch-annotator inspections that could not prove they ran"
    } else {
        "batch-annotator inspections that could not prove they ran (${pairs.joinToString("; ")})"
    }
    return "Do not report GREEN and do not retry: this scope has $blocking. " +
        "Inspect the scope without those files, or report UNKNOWN."
}

/** Assumes [wrapper] already passed the platform's language filter for [file]. */
internal fun batchAnnotatorMayApply(wrapper: LocalInspectionToolWrapper, file: PsiFile): Boolean =
    isCFamilyFile(file) ||
        wrapper.language.let { !it.isNullOrBlank() && it != "any" } ||
        hasPairedExternalAnnotator(wrapper.tool as ExternalAnnotatorBatchInspection, file)

private fun hasPairedExternalAnnotator(tool: ExternalAnnotatorBatchInspection, file: PsiFile): Boolean =
    file.viewProvider.allFiles.any { root ->
        ExternalLanguageAnnotators.allForFile(root.language, root).any { annotator ->
            annotator.pairedBatchInspectionShortName == tool.shortName
        }
    }

/**
 * The platform's own `checkFile` runs the paired external annotator, so a normal return shows the tool ran. A tool
 * that replaces it (clion-radler, ESLint) can return nothing without having analysed the file: a live IntelliJ run of
 * clion-radler returned no descriptors for C++ with a missing return and an unused variable.
 */
internal fun usesPlatformCheckFile(tool: ExternalAnnotatorBatchInspection): Boolean = runCatching {
    tool.javaClass.getMethod("checkFile", PsiFile::class.java, GlobalInspectionContext::class.java, InspectionManager::class.java)
        .declaringClass == ExternalAnnotatorBatchInspection::class.java
}.getOrDefault(false)
