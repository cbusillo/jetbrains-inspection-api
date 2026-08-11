package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.util.PairProcessor

internal fun canExecuteWithInspectEx(toolWrapper: InspectionToolWrapper<*, *>): Boolean =
    toolWrapper is LocalInspectionToolWrapper

internal data class SupportedInspectionFileResult(
    val filePath: String,
    val suppliedToolShortNames: Set<String>,
    val returnedDescriptorsByToolShortName: Map<String, List<ProblemDescriptor>>,
) {
    val problemDescriptorCount: Int
        get() = returnedDescriptorsByToolShortName.values.sumOf(List<ProblemDescriptor>::size)
}

internal data class SupportedInspectionExecutionResult(
    val fileResults: List<SupportedInspectionFileResult>,
) {
    val scopeFileCount: Int
        get() = fileResults.size

    val problemDescriptorCount: Int
        get() = fileResults.sumOf(SupportedInspectionFileResult::problemDescriptorCount)
}

internal class SupportedInspectionExecutor {
    fun execute(
        scope: AnalysisScope,
        toolWrappers: List<LocalInspectionToolWrapper>,
        indicator: ProgressIndicator,
        ignoreSuppressedElements: Boolean = true,
    ): SupportedInspectionExecutionResult {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val fileResults = collectScopeFiles(scope).map { psiFile ->
            indicator.checkCanceled()
            executePreparedFile(
                psiFile,
                toolWrappers,
                indicator,
                ignoreSuppressedElements,
            )
        }
        return SupportedInspectionExecutionResult(fileResults)
    }

    internal fun executePreparedFile(
        psiFile: PsiFile,
        toolWrappers: List<LocalInspectionToolWrapper>,
        indicator: ProgressIndicator,
        ignoreSuppressedElements: Boolean = true,
    ): SupportedInspectionFileResult {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        indicator.checkCanceled()
        return inspectFile(psiFile, toolWrappers, indicator, ignoreSuppressedElements)
    }

    internal fun collectScopeFiles(scope: AnalysisScope): List<PsiFile> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val filesByPath = linkedMapOf<String, PsiFile>()
        scope.accept(object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val path = file.virtualFile?.path ?: file.name
                filesByPath.putIfAbsent(path, file)
            }
        })
        return filesByPath.values.toList()
    }

    private fun inspectFile(
        psiFile: PsiFile,
        toolWrappers: List<LocalInspectionToolWrapper>,
        indicator: ProgressIndicator,
        ignoreSuppressedElements: Boolean,
    ): SupportedInspectionFileResult {
        val returnedDescriptors = InspectionEngine.inspectEx(
            toolWrappers,
            psiFile,
            psiFile.textRange,
            psiFile.textRange,
            false,
            false,
            ignoreSuppressedElements,
            indicator,
            PairProcessor.alwaysTrue(),
        )
        return SupportedInspectionFileResult(
            filePath = psiFile.virtualFile?.path ?: psiFile.name,
            suppliedToolShortNames = toolWrappers.mapTo(sortedSetOf()) { it.shortName },
            returnedDescriptorsByToolShortName = returnedDescriptors.entries
                .sortedBy { it.key.shortName }
                .associate { (wrapper, descriptors) -> wrapper.shortName to descriptors.toList() },
        )
    }
}
