package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId

internal class InspectionIdeMemoryTest {
    @TempDir
    lateinit var directory: Path

    private val startedAt = LocalDateTime.of(2026, 10, 3, 12, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `current process OOM remains exhausted after log rotation`() {
        val log = directory.resolve("idea.log")
        Files.writeString(log, "2026-10-03 12:01:00,000 ERROR - Index storage failed\njava.lang.OutOfMemoryError: Java heap space\n")
        val state = InspectionIdeMemoryState(log, startedAt) { startedAt + 120_000 }
        val snapshot = state.snapshot()
        assertEquals("exhausted", snapshot["status"])
        assertEquals(startedAt + 60_000, snapshot["out_of_memory_at_ms"])
        assertTrue(snapshot["next_action"].toString().contains("Java heap space"))
        Files.writeString(log, "2026-10-03 12:02:00,000 INFO - rotating log\n")
        assertEquals("exhausted", state.snapshot()["status"])
    }

    @Test
    fun `previous IDE process OOM does not contaminate restarted process`() {
        val log = directory.resolve("idea.log")
        Files.writeString(log, "2026-10-03 11:59:00,000 ERROR - java.lang.OutOfMemoryError: Java heap space\n2026-10-03 12:01:00,000 INFO - started\n")
        val state = InspectionIdeMemoryState(log, startedAt) { startedAt + 120_000 }
        assertEquals("normal", state.snapshot()["status"])
    }

    @Test
    fun `low memory signal recovers without being classified as OOM`() {
        var now = startedAt
        val state = InspectionIdeMemoryState(directory.resolve("missing.log"), startedAt) { now }
        state.lowMemorySignal()
        assertEquals("low_memory", state.snapshot()["status"])
        now += 60_000
        assertEquals("normal", state.snapshot()["status"])
    }
}
