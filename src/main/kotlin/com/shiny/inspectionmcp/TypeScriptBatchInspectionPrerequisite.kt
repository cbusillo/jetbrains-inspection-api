package com.shiny.inspectionmcp

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.InspectionProfile
import com.intellij.lang.javascript.ecmascript6.TypeScriptAnnotatorCheckerProvider
import com.intellij.lang.typescript.inspections.TypeScriptUnresolvedReferenceInspection
import com.intellij.psi.PsiFile

internal class TypeScriptBatchInspectionPrerequisite : BatchInspectionPrerequisite {
    override fun delegatedInspection(file: PsiFile, profile: InspectionProfile): String? {
        if (file.language.id != "TypeScript") return null
        val shortName = TypeScriptUnresolvedReferenceInspection().shortName
        val key = HighlightDisplayKey.find(shortName) ?: return null
        if (!profile.isToolEnabled(key, file)) return null
        return shortName.takeIf { TypeScriptAnnotatorCheckerProvider.getCheckerProvider(file).skipErrors(file) }
    }
}
