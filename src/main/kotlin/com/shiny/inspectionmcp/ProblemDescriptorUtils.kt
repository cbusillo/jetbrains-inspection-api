package com.shiny.inspectionmcp

import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.injected.editor.DocumentWindow
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager

data class ProblemLocation(
    val filePath: String,
    val line: Int,
    val column: Int,
)

fun resolveProblemLocation(descriptor: ProblemDescriptor, project: Project): ProblemLocation? {
    val element = descriptor.psiElement
    if (element == null || !element.isValid) return null
    val containingFile = element.containingFile ?: return null
    val virtualFile = containingFile.virtualFile ?: return null

    val documentManager = PsiDocumentManager.getInstance(project)
    val document = documentManager.getDocument(containingFile)
    val textRange = element.textRange
    val startOffset = problemDescriptorStartOffset(
        elementStartOffset = textRange.startOffset,
        textRangeInElement = descriptor.textRangeInElement,
        descriptorTextRange = descriptorTextRange(descriptor),
    )

    var filePath = virtualFile.path
    var line = 0
    var column = 0

    if (virtualFile is VirtualFileWindow && document is DocumentWindow) {
        val hostFile = virtualFile.delegate
        val hostPsi = InjectedLanguageManager.getInstance(project).getTopLevelFile(containingFile)
        val hostDocument = hostPsi?.let { psi -> documentManager.getDocument(psi) }
        val hostOffset = document.injectedToHost(startOffset)
        filePath = hostFile.path
        if (hostDocument != null) {
            line = hostDocument.getLineNumber(hostOffset) + 1
            column = hostOffset - hostDocument.getLineStartOffset(line - 1)
        } else {
            line = document.getLineNumber(startOffset) + 1
            column = startOffset - document.getLineStartOffset(line - 1)
        }
    } else if (document != null) {
        line = document.getLineNumber(startOffset) + 1
        column = startOffset - document.getLineStartOffset(line - 1)
    }

    return ProblemLocation(filePath, line, column)
}

internal fun problemDescriptorStartOffset(
    elementStartOffset: Int,
    textRangeInElement: TextRange?,
    descriptorTextRange: TextRange?,
): Int {
    return descriptorTextRange?.startOffset ?: elementStartOffset + (textRangeInElement?.startOffset ?: 0)
}

private fun descriptorTextRange(descriptor: ProblemDescriptor): TextRange? {
    return try {
        descriptor.javaClass.getMethod("getTextRange").invoke(descriptor) as? TextRange
    } catch (_: Exception) {
        null
    }
}

fun severityFromHighlightType(highlightType: ProblemHighlightType): String {
    return when (highlightType) {
        ProblemHighlightType.ERROR -> "error"
        ProblemHighlightType.WARNING -> "warning"
        ProblemHighlightType.WEAK_WARNING -> "weak_warning"
        ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "warning"
        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL -> "error"
        ProblemHighlightType.LIKE_DEPRECATED -> "weak_warning"
        ProblemHighlightType.LIKE_UNUSED_SYMBOL -> "weak_warning"
        ProblemHighlightType.GENERIC_ERROR -> "error"
        else -> "info"
    }
}
