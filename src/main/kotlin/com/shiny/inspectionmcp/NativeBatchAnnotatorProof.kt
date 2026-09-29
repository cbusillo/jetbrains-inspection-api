package com.shiny.inspectionmcp

import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.Tools
import com.intellij.lang.ExternalLanguageAnnotators
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile

internal const val UNPROVEN_C_FAMILY_BATCH_ANNOTATOR_REASON = "native_cpp_batch_annotator_unproven"
internal const val UNPROVEN_BATCH_ANNOTATOR_REASON = "native_batch_annotator_unproven"

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
 * `inspectionFinished`, so their completion can never be proven on a file they apply to. They apply to C/C++
 * files, and to any file with an external annotator paired with the tool, which is how the default `checkFile`
 * finds its work (for example ShellCheck on shell scripts).
 */
internal fun unprovenBatchAnnotatorReason(
    toolGroups: Collection<Tools>,
    files: List<PsiFile>,
    includeDoNotShow: Boolean,
    checkBudget: () -> Unit = {},
): String? {
    val batchAnnotatorGroups = toolGroups.filter { group ->
        checkBudget()
        (group.tool as? LocalInspectionToolWrapper)?.tool is ExternalAnnotatorBatchInspection
    }
    if (batchAnnotatorGroups.isEmpty()) return null
    var reason: String? = null
    for (file in files) {
        ProgressManager.checkCanceled()
        checkBudget()
        val cFamily = isCFamilyFile(file)
        if (!cFamily && reason != null) continue
        val enabled = batchAnnotatorGroups.mapNotNull { group ->
            group.getEnabledTool(file, includeDoNotShow) as? LocalInspectionToolWrapper
        }
        val dialectIds = InspectionEngine.calcElementDialectIds(file.viewProvider.allFiles, emptyList())
        val applicable = InspectionEngine.filterToolsApplicableByLanguage(enabled, dialectIds, dialectIds)
        if (cFamily && applicable.isNotEmpty()) return UNPROVEN_C_FAMILY_BATCH_ANNOTATOR_REASON
        if (applicable.any { hasPairedExternalAnnotator(it.tool as ExternalAnnotatorBatchInspection, file) }) {
            reason = UNPROVEN_BATCH_ANNOTATOR_REASON
        }
    }
    return reason
}

internal fun batchAnnotatorMayApply(tool: ExternalAnnotatorBatchInspection, file: PsiFile): Boolean =
    isCFamilyFile(file) || hasPairedExternalAnnotator(tool, file)

private fun hasPairedExternalAnnotator(tool: ExternalAnnotatorBatchInspection, file: PsiFile): Boolean =
    file.viewProvider.allFiles.any { root ->
        ExternalLanguageAnnotators.allForFile(root.language, root).any { annotator ->
            annotator.pairedBatchInspectionShortName == tool.shortName
        }
    }
