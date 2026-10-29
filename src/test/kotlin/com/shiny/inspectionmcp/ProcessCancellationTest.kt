package com.shiny.inspectionmcp

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProcessCancellationTest {
    @Test
    fun `result fallback preserves successes and ordinary failures but rethrows cancellation`() {
        assertEquals("value", runCatchingCancellable { "value" }.getOrNull())
        val failure = IllegalStateException("unavailable")
        assertSame(failure, runCatchingCancellable { throw failure }.exceptionOrNull())

        for (cancellation in listOf(ProcessCanceledException(), CancellationException())) {
            for (error in listOf(cancellation, InvocationTargetException(cancellation))) {
                val thrown = assertThrows(cancellation.javaClass) {
                    runCatchingCancellable { throw error }.getOrDefault("fallback")
                }
                assertSame(cancellation, thrown)
            }
        }
    }

    @Test
    fun `cleanup attempts every action before rethrowing cancellation even after an ordinary failure`() {
        val cancellation = ProcessCanceledException()
        val completed = mutableListOf<String>()

        val thrown = assertThrows(ProcessCanceledException::class.java) {
            runAllCleanupActions(
                { completed += "disconnect"; throw IllegalStateException("disconnect failed") },
                { completed += "dispose"; throw cancellation },
                { completed += "finish" },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf("disconnect", "dispose", "finish"), completed)
    }

    @Test
    fun `cleanup preserves an ordinary failure after completing remaining actions`() {
        val failure = IllegalStateException("disconnect failed")
        var finished = false

        val thrown = assertThrows(IllegalStateException::class.java) {
            runAllCleanupActions({ throw failure }, { finished = true })
        }

        assertSame(failure, thrown)
        assertEquals(true, finished)
    }

    @Test
    fun `cancelled project lookup does not return a fallback identity`() {
        val cancellation = ProcessCanceledException()
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } throws cancellation

        val thrown = assertThrows(ProcessCanceledException::class.java) { projectKey(project) }

        assertSame(cancellation, thrown)
    }

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
