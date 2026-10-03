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
    fun `memory endpoint returns current session OOM diagnostic without a project selector`() {
        mockkObject(InspectionIdeMemory)
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
            unmockkObject(InspectionIdeMemory)
        }
    }
}
