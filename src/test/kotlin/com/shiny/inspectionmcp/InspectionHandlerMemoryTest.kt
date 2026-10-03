package com.shiny.inspectionmcp

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.netty.handler.codec.http.HttpResponseStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class InspectionHandlerMemoryTest : InspectionHandlerTestSupport() {
    @Test
    fun `startup observer publishes real service diagnostics and disposal removes the observer`() {
        val root = java.util.logging.Logger.getLogger("")
        val previous = root.handlers.toSet()
        val service = InspectionIdeMemory()
        val observer = root.handlers.first { it !in previous && it is InspectionIdeMemoryState }
        try {
            every { mockApplication.getService(InspectionIdeMemory::class.java) } returns service
            InspectionIdeMemoryStartupListener().appFrameCreated(emptyList())
            observer.publish(java.util.logging.LogRecord(java.util.logging.Level.SEVERE, "OOM").apply {
                thrown = OutOfMemoryError("Java heap space")
            })
            val response = processGetRequest("/api/inspection/memory")
            val body = response.content().toString(Charsets.UTF_8)
            assertEquals(HttpResponseStatus.OK, response.status())
            assertTrue(body.contains("\"status\": \"exhausted\""), body)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(service)
        }
        assertTrue(root.handlers.none { it === observer })
    }

    @Test
    fun `memory endpoint returns current session OOM diagnostic without a project selector`() {
        mockkObject(InspectionIdeMemory.Companion)
        try {
            every { InspectionIdeMemory.snapshot() } returns mapOf(
                "status" to "exhausted",
                "out_of_memory_at_ms" to 1234L,
            )
            val response = processGetRequest("/api/inspection/memory")
            val body = response.content().toString(Charsets.UTF_8)
            assertEquals(HttpResponseStatus.OK, response.status())
            assertTrue(body.contains("\"session_id\": \"${InspectionIdeSession.sessionId}\""), body)
            assertTrue(body.contains("\"status\": \"exhausted\""), body)
            assertTrue(body.contains("\"out_of_memory_at_ms\": 1234"), body)
        } finally {
            unmockkObject(InspectionIdeMemory.Companion)
        }
    }
}
