package com.shiny.inspectionmcp

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProcessCancellationTest {
    private class CancellingGetter {
        fun getTextRange(): Any = throw cancellation

        companion object {
            val cancellation = ProcessCanceledException()
        }
    }

    @Test
    fun `cancellation inside a reflected getter is rethrown unwrapped`() {
        val thrown = assertThrows(ProcessCanceledException::class.java) {
            try {
                CancellingGetter::class.java.getMethod("getTextRange").invoke(CancellingGetter())
            } catch (e: Exception) {
                rethrowIfCanceled(e)
            }
        }

        assertSame(CancellingGetter.cancellation, thrown)
    }
}
