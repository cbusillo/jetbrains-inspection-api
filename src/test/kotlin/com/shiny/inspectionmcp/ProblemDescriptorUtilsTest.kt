package com.shiny.inspectionmcp

import com.intellij.codeInspection.ProblemDescriptor
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
