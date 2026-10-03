package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

internal class InspectionIdeMemoryTest {
    @Test
    fun `logged local OOM remains exhausted after later unrelated records`() {
        val now = System.currentTimeMillis()
        val state = InspectionIdeMemoryState { now + 1000 }
        val logger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(state)
        }
        try {
            logger.log(Level.SEVERE, "Project opening failed", OutOfMemoryError("Java heap space"))
            repeat(100) { logger.info("Subsequent log output") }
            val snapshot = state.snapshot()
            assertEquals("exhausted", snapshot["status"])
            assertTrue(snapshot["next_action"].toString().contains("Java heap space"))
            assertEquals("exhausted", state.snapshot()["status"])
        } finally {
            logger.removeHandler(state)
        }
    }

    @Test
    fun `new diagnostic state does not inherit a previous state's OOM`() {
        val previous = InspectionIdeMemoryState()
        previous.publish(LogRecord(Level.SEVERE, "OOM").apply {
            thrown = OutOfMemoryError("Java heap space")
        })
        assertEquals("exhausted", previous.snapshot()["status"])
        assertEquals("normal", InspectionIdeMemoryState().snapshot()["status"])
    }

    @Test
    fun `remote cause and OOM message text do not classify the IDE as exhausted`() {
        val state = InspectionIdeMemoryState()
        state.publish(LogRecord(Level.SEVERE, "Gradle failed: java.lang.OutOfMemoryError: Metaspace"))
        state.publish(LogRecord(Level.SEVERE, "Gradle sync failed").apply {
            thrown = RuntimeException("Remote build failure", OutOfMemoryError("Java heap space"))
        })
        assertEquals("normal", state.snapshot()["status"])
    }

    @Test
    fun `low memory signal recovers without being classified as OOM`() {
        var now = 1000L
        val state = InspectionIdeMemoryState { now }
        state.lowMemorySignal()
        assertEquals("low_memory", state.snapshot()["status"])
        now += 60_000
        assertEquals("normal", state.snapshot()["status"])
    }
}
