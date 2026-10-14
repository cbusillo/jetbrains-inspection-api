package com.shiny.inspectionmcp

import com.intellij.codeInspection.InspectionProfile
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile

internal const val MAX_BATCH_INSPECTION_READ_PREEMPTIONS = 80
internal const val BATCH_INSPECTION_WRITE_PREEMPTED_REASON = "write_action_preempted"

internal interface BatchInspectionPrerequisite {
    fun delegatedInspection(file: PsiFile, profile: InspectionProfile): String?

    companion object {
        val EP = ExtensionPointName<BatchInspectionPrerequisite>("com.shiny.inspection.api.batchInspectionPrerequisite")
    }
}

internal fun findBatchInspectionDelegations(
    profile: InspectionProfile,
    files: List<PsiFile>,
    cancellationCheck: () -> Unit = { ProgressManager.checkCanceled() },
): List<Map<String, String>> = buildList {
    for (file in files) {
        cancellationCheck()
        for (prerequisite in BatchInspectionPrerequisite.EP.getExtensionList(file.project)) {
            val tool = prerequisite.delegatedInspection(file, profile) ?: continue
            add(mapOf("tool" to tool, "file" to (file.virtualFile?.path ?: file.name)))
        }
    }
}
