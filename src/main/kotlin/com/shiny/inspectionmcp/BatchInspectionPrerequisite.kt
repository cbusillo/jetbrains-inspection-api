package com.shiny.inspectionmcp

import com.intellij.codeInspection.InspectionProfile
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile

internal interface BatchInspectionPrerequisite {
    fun delegatedInspection(file: PsiFile, profile: InspectionProfile): String?

    companion object {
        val EP = ExtensionPointName<BatchInspectionPrerequisite>("com.shiny.inspection.api.batchInspectionPrerequisite")
    }
}

internal fun findBatchInspectionDelegations(
    profile: InspectionProfile,
    files: List<PsiFile>,
): List<Map<String, String>> = buildList {
    for (file in files) {
        ProgressManager.checkCanceled()
        for (prerequisite in BatchInspectionPrerequisite.EP.getExtensionList(file.project)) {
            val tool = prerequisite.delegatedInspection(file, profile) ?: continue
            add(mapOf("tool" to tool, "file" to (file.virtualFile?.path ?: file.name)))
        }
    }
}
