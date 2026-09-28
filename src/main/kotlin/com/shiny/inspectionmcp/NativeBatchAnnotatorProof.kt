package com.shiny.inspectionmcp

import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.Tools
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile

internal const val UNPROVEN_C_FAMILY_BATCH_ANNOTATOR_REASON = "native_cpp_batch_annotator_unproven"

private val C_FAMILY_SOURCE_EXTENSIONS = setOf(
    "c", "cc", "cp", "cpp", "cxx", "c++", "cppm", "ixx",
    "h", "hh", "hp", "hpp", "hxx", "h++", "inl", "ipp", "tpp", "tcc",
    "m", "mm", "cu", "cuh",
)

internal fun isCFamilySourcePath(path: String): Boolean {
    val name = path.substringAfterLast('/')
    return name.contains('.') && name.substringAfterLast('.').lowercase() in C_FAMILY_SOURCE_EXTENSIONS
}

/**
 * The batch runner calls [ExternalAnnotatorBatchInspection] tools through `checkFile` without publishing
 * `inspectionFinished`, so their completion on a C/C++ file can never be proven.
 */
internal fun hasUnprovenCFamilyBatchAnnotator(
    toolGroups: Collection<Tools>,
    files: List<PsiFile>,
    includeDoNotShow: Boolean,
): Boolean {
    val batchAnnotatorGroups = toolGroups.filter { group ->
        (group.tool as? LocalInspectionToolWrapper)?.tool is ExternalAnnotatorBatchInspection
    }
    if (batchAnnotatorGroups.isEmpty()) return false
    return files.any { file ->
        ProgressManager.checkCanceled()
        val path = file.virtualFile?.path ?: return@any false
        if (!isCFamilySourcePath(path)) return@any false
        val enabled = batchAnnotatorGroups.mapNotNull { group ->
            group.getEnabledTool(file, includeDoNotShow) as? LocalInspectionToolWrapper
        }
        val dialectIds = InspectionEngine.calcElementDialectIds(file.viewProvider.allFiles, emptyList())
        InspectionEngine.filterToolsApplicableByLanguage(enabled, dialectIds, dialectIds).isNotEmpty()
    }
}
