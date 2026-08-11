package com.shiny.inspectionmcp

import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.InspectionProfile
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ProblemDescriptorUtilsTest {
    @Test
    @DisplayName("Problem descriptor offsets include the highlight range inside the PSI element")
    fun problemDescriptorStartOffsetUsesRangeInElement() {
        assertEquals(
            145,
            problemDescriptorStartOffset(
                elementStartOffset = 120,
                textRangeInElement = TextRange(25, 36),
                descriptorTextRange = null,
            )
        )
    }

    @Test
    @DisplayName("Problem descriptor offsets prefer the concrete descriptor text range")
    fun problemDescriptorStartOffsetUsesDescriptorTextRange() {
        assertEquals(
            240,
            problemDescriptorStartOffset(
                elementStartOffset = 120,
                textRangeInElement = TextRange(25, 36),
                descriptorTextRange = TextRange(240, 251),
            )
        )
    }

    @Test
    @DisplayName("Problem descriptor offsets fall back to the PSI element start")
    fun problemDescriptorStartOffsetFallsBackToElementStart() {
        assertEquals(
            120,
            problemDescriptorStartOffset(
                elementStartOffset = 120,
                textRangeInElement = null,
                descriptorTextRange = null,
            )
        )
    }

    @Test
    @DisplayName("Problem descriptions are normalized for machine clients")
    fun normalizeProblemDescriptionRemovesIdeMarkupAndRefPlaceholder() {
        assertEquals(
            "Value of parameter name is always '\"port\"'",
            normalizeProblemDescription(
                "<HTML><BODY><P>Value of parameter <CODE>#ref</CODE> is always '&quot;port&quot;'</P></BODY></HTML>",
                "name",
            ),
        )
    }

    @Test
    @DisplayName("Problem descriptions preserve literal angle-bracket text")
    fun normalizeProblemDescriptionPreservesAngleBracketText() {
        assertEquals(
            "Use Map<String> with <init>.",
            normalizeProblemDescription("Use Map<String> with <code>#ref</code>.", "<init>"),
        )
    }

    @Test
    @DisplayName("Problem descriptions preserve refs that look like stripped tags")
    fun normalizeProblemDescriptionPreservesRefTextThatLooksLikeHtmlTag() {
        assertEquals(
            "Unexpected element <body>.",
            normalizeProblemDescription("Unexpected element <code>#ref</code>.", "<body>"),
        )
    }

    @Test
    @DisplayName("Problem descriptions strip encoded IDE markup")
    fun normalizeProblemDescriptionStripsEncodedMarkup() {
        assertEquals(
            "Value of parameter name is always '\"port\"'",
            normalizeProblemDescription(
                "&lt;html&gt;&lt;body&gt;Value of parameter &lt;code&gt;#ref&lt;/code&gt; is always '&quot;port&quot;'&lt;/body&gt;&lt;/html&gt;",
                "name",
            ),
        )
    }

    @Test
    @DisplayName("Problem descriptor ref text uses highlighted range")
    fun problemDescriptorRefTextUsesHighlightedRange() {
        val descriptor = mockProblemDescriptor("val port = config.port", TextRange(4, 8))

        assertEquals("port", problemDescriptorRefText(descriptor))
    }

    @Test
    @DisplayName("Problem descriptor ref text skips overly large PSI text")
    fun problemDescriptorRefTextSkipsLargePsiText() {
        val descriptor = mockProblemDescriptor("fun example() {\n    val port = config.port\n}", null)

        assertEquals(null, problemDescriptorRefText(descriptor))
    }

    @Test
    @DisplayName("Problem descriptor ref text ignores invalid PSI elements")
    fun problemDescriptorRefTextIgnoresInvalidPsiElement() {
        val descriptor = mockProblemDescriptor("port", TextRange(0, 4), isValid = false)

        assertEquals(null, problemDescriptorRefText(descriptor))
    }

    @Test
    @DisplayName("Profile severity can raise a warning descriptor to error")
    fun liftSeverityWithProfileRaisesWarningToError() {
        val profile = mockk<InspectionProfile>()
        val displayKey = mockk<HighlightDisplayKey>()
        val element = mockk<PsiElement>()
        every { profile.getErrorLevel(displayKey, element) } returns HighlightDisplayLevel.ERROR

        assertEquals(
            "error",
            liftSeverityWithProfile(
                baseSeverity = severityFromHighlightType(ProblemHighlightType.WARNING),
                selectedProfile = profile,
                displayKey = displayKey,
                psiElement = element,
            ),
        )
    }

    @Test
    @DisplayName("Profile severity does not lower a descriptor-derived error")
    fun liftSeverityWithProfileDoesNotLowerErrorSeverity() {
        val profile = mockk<InspectionProfile>()
        val displayKey = mockk<HighlightDisplayKey>()
        val element = mockk<PsiElement>()
        every { profile.getErrorLevel(displayKey, element) } returns HighlightDisplayLevel.WARNING

        assertEquals(
            "error",
            liftSeverityWithProfile(
                baseSeverity = severityFromHighlightType(ProblemHighlightType.ERROR),
                selectedProfile = profile,
                displayKey = displayKey,
                psiElement = element,
            ),
        )
    }

    @Test
    @DisplayName("Weak warning display levels use the API severity vocabulary")
    fun severityFromHighlightDisplayLevelNormalizesWeakWarning() {
        assertEquals(
            "weak_warning",
            severityFromHighlightDisplayLevel(HighlightDisplayLevel.WEAK_WARNING),
        )
    }

    @Test
    @DisplayName("Missing profile data falls back to the highlight-type severity")
    fun liftSeverityWithProfileFallsBackSafely() {
        val profile = mockk<InspectionProfile>()
        val displayKey = mockk<HighlightDisplayKey>()
        val element = mockk<PsiElement>()
        every { profile.getErrorLevel(displayKey, element) } throws IllegalStateException("missing")

        assertEquals(
            "warning",
            liftSeverityWithProfile(
                baseSeverity = severityFromHighlightType(ProblemHighlightType.GENERIC_ERROR_OR_WARNING),
                selectedProfile = profile,
                displayKey = displayKey,
                psiElement = element,
            ),
        )
        assertEquals(
            "warning",
            liftSeverityWithProfile(
                baseSeverity = severityFromHighlightType(ProblemHighlightType.GENERIC_ERROR_OR_WARNING),
                selectedProfile = null,
                displayKey = displayKey,
                psiElement = element,
            ),
        )
    }

    private fun mockProblemDescriptor(
        elementText: String,
        textRangeInElement: TextRange?,
        isValid: Boolean = true,
    ): ProblemDescriptor {
        val element = mockk<PsiElement>()
        every { element.isValid } returns isValid
        every { element.text } returns elementText
        val descriptor = mockk<ProblemDescriptor>()
        every { descriptor.psiElement } returns element
        every { descriptor.textRangeInElement } returns textRangeInElement
        return descriptor
    }
}
