package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BoundedProcessOutputTest {

    @Test
    fun `collects successful process output`() {
        val process = ProcessBuilder("sh", "-c", "printf 'M  src/App.kt'").start()

        val result = collectBoundedProcessOutput(process, timeoutMs = 1000L, maxOutputBytes = 1024)

        assertEquals(0, result.exitCode)
        assertEquals("M  src/App.kt", result.output.toString(Charsets.UTF_8))
        assertFalse(result.timedOut)
        assertFalse(result.outputLimitExceeded)
    }

    @Test
    fun `reports nonzero process exit`() {
        val process = ProcessBuilder("sh", "-c", "printf failure; exit 7").start()

        val result = collectBoundedProcessOutput(process, timeoutMs = 1000L, maxOutputBytes = 1024)

        assertEquals(7, result.exitCode)
        assertEquals("failure", result.output.toString(Charsets.UTF_8))
        assertFalse(result.timedOut)
    }

    @Test
    fun `kills a process that exceeds its timeout`() {
        val process = ProcessBuilder("sh", "-c", "sleep 5").start()

        val result = collectBoundedProcessOutput(process, timeoutMs = 50L, maxOutputBytes = 1024)

        assertTrue(result.timedOut)
        assertFalse(process.isAlive)
    }

    @Test
    fun `reports output that exceeds the byte limit`() {
        val process = ProcessBuilder("sh", "-c", "printf 123456789").start()

        val result = collectBoundedProcessOutput(process, timeoutMs = 1000L, maxOutputBytes = 4)

        assertTrue(result.outputLimitExceeded)
        assertEquals("1234", result.output.toString(Charsets.UTF_8))
    }

    @Test
    fun `preserves interruption while cleaning up the process`() {
        val process = ProcessBuilder("sh", "-c", "sleep 5").start()
        Thread.currentThread().interrupt()

        try {
            assertThrows(InterruptedException::class.java) {
                collectBoundedProcessOutput(process, timeoutMs = 1000L, maxOutputBytes = 1024)
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(process.isAlive)
        } finally {
            Thread.interrupted()
        }
    }
}
