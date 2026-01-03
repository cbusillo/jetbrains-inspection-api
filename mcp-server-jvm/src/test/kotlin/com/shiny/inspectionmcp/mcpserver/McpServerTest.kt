package com.shiny.inspectionmcp.mcpserver

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class McpServerTest {
    @Test
    fun initializeResponseIncludesServerInfo() {
        val executor = ToolExecutor("http://localhost:1/api/inspection", HttpClient.newHttpClient(), "63341")
        val response = handleIncomingMessage(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""",
            executor
        ) as JsonObject

        assertEquals("2.0", response.string("jsonrpc"))
        val result = response["result"]?.jsonObject
        assertNotNull(result)
        assertEquals("2024-11-05", result?.string("protocolVersion"))
        assertEquals("jetbrains-inspection-mcp", result?.get("serverInfo")?.jsonObject?.string("name"))
    }

    @Test
    fun toolsListIncludesExpectedTools() {
        val executor = ToolExecutor("http://localhost:1/api/inspection", HttpClient.newHttpClient(), "63341")
        val response = handleIncomingMessage(
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            executor
        ) as JsonObject

        val tools = response["result"]?.jsonObject?.get("tools")?.jsonArray
        val names = tools?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }?.toSet()
        assertEquals(
            setOf("inspection_get_problems", "inspection_trigger", "inspection_get_status", "inspection_wait"),
            names
        )
    }

    @Test
    fun batchRequestsReturnJsonArray() {
        val executor = ToolExecutor("http://localhost:1/api/inspection", HttpClient.newHttpClient(), "63341")
        val response = handleIncomingMessage(
            """[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","id":2,"method":"tools/list"}]""",
            executor
        ) as JsonArray

        assertEquals(2, response.size)
    }

    @Test
    fun pingReturnsEmptyResult() {
        val executor = ToolExecutor("http://localhost:1/api/inspection", HttpClient.newHttpClient(), "63341")
        val response = handleIncomingMessage(
            """{"jsonrpc":"2.0","id":3,"method":"ping"}""",
            executor
        ) as JsonObject

        assertEquals("2.0", response.string("jsonrpc"))
        assertNotNull(response["result"]?.jsonObject)
        assertTrue(response["result"]?.jsonObject?.isEmpty() == true)
    }

    @Test
    fun methodNotFoundReturnsError() {
        val executor = ToolExecutor("http://localhost:1/api/inspection", HttpClient.newHttpClient(), "63341")
        val response = handleIncomingMessage(
            """{"jsonrpc":"2.0","id":4,"method":"missing"}""",
            executor
        ) as JsonObject

        val error = response["error"]?.jsonObject
        assertEquals(-32601, error?.get("code")?.jsonPrimitive?.intOrNull)
        assertTrue(error?.get("message")?.jsonPrimitive?.content?.contains("Method not found") == true)
    }

    @Test
    fun notificationsInitializedReturnsNull() {
        val executor = ToolExecutor("http://localhost:1/api/inspection", HttpClient.newHttpClient(), "63341")
        val response = handleIncomingMessage(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            executor
        )

        assertNull(response)
    }

    @Test
    fun toolsCallMissingNameReturnsError() {
        val executor = ToolExecutor("http://localhost:1/api/inspection", HttpClient.newHttpClient(), "63341")
        val params = buildJsonObject {
            put("arguments", buildJsonObject { })
        }

        val result = executor.handleToolCall(params)
        assertTrue(result.isError())
        assertTrue(result.firstText().contains("Missing tool name"))
    }

    @Test
    fun toolsCallUnknownToolReturnsError() {
        val executor = ToolExecutor("http://localhost:1/api/inspection", HttpClient.newHttpClient(), "63341")
        val params = buildJsonObject {
            put("name", JsonPrimitive("unknown_tool"))
            put("arguments", buildJsonObject { })
        }

        val result = executor.handleToolCall(params)
        assertTrue(result.isError())
        assertTrue(result.firstText().contains("Unknown tool: unknown_tool"))
    }

    @Test
    fun inspectionGetProblemsEncodesQueryAndAddsGuidance() {
        MockIdeServer().use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())
            val args = buildJsonObject {
                put("file_pattern", JsonPrimitive("my file.js"))
                put("limit", JsonPrimitive(10))
            }

            val response = executor.handleToolCall(buildToolCall("inspection_get_problems", args))
            val text = response.firstText()
            assertTrue(text.contains("\"total_problems\": 2"))
            assertTrue(text.contains("INFO: Found 2 problems total"))
            val query = server.lastQuery.get() ?: ""
            assertTrue(query.contains("file_pattern=my+file.js"))
            assertTrue(query.contains("limit=10"))
        }
    }

    @Test
    fun inspectionGetProblemsAddsPaginationGuidance() {
        val response = """{"status":"results_available","total_problems":5,"problems_shown":2,"pagination":{"has_more":true,"next_offset":2},"problems":[]}"""
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())
            val args = buildJsonObject {
                put("offset", JsonPrimitive(2))
                put("problem_type", JsonPrimitive("SpellCheckingInspection"))
            }

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", args))
            val text = result.firstText()
            assertTrue(text.contains("INFO: Found 5 problems total"))
            assertTrue(text.contains("NEXT: More results available"))
            val query = server.lastQuery.get() ?: ""
            assertTrue(query.contains("offset=2"))
            assertTrue(query.contains("problem_type=SpellCheckingInspection"))
        }
    }

    @Test
    fun inspectionGetProblemsHandlesNoResults() {
        val response = """{"status":"no_results"}"""
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))
            assertTrue(result.firstText().contains("No results found"))
        }
    }

    @Test
    fun inspectionGetProblemsHandlesZeroResults() {
        val response = """{"status":"results_available","total_problems":0,"problems_shown":0,"problems":[]}"""
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))
            assertTrue(result.firstText().contains("No problems found"))
        }
    }

    @Test
    fun inspectionTriggerIncludesFilesParams() {
        MockIdeServer().use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())
            val args = buildJsonObject {
                put("scope", JsonPrimitive("files"))
                put("files", JsonArray(listOf(JsonPrimitive("src/a.py"), JsonPrimitive("src/b.py"))))
            }

            val response = executor.handleToolCall(buildToolCall("inspection_trigger", args))
            val text = response.firstText()
            assertTrue(text.contains("\"status\": \"triggered\""))
            val query = server.lastQuery.get() ?: ""
            assertTrue(query.contains("file=src%2Fa.py"))
            assertTrue(query.contains("file=src%2Fb.py"))
        }
    }

    @Test
    fun inspectionTriggerSupportsAliasesAndOptions() {
        MockIdeServer().use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())
            val args = buildJsonObject {
                put("scope", JsonPrimitive("directory"))
                put("directory", JsonPrimitive("src/my dir"))
                put("files", JsonPrimitive("src/main.py"))
                put("include_unversioned", JsonPrimitive(true))
                put("changed_files_mode", JsonPrimitive("staged"))
                put("max_files", JsonPrimitive(5))
                put("profile", JsonPrimitive("LLM Fast Checks"))
            }

            val result = executor.handleToolCall(buildToolCall("inspection_trigger", args))
            val text = result.firstText()
            assertTrue(text.contains("inspection_wait"))
            val query = server.lastQuery.get() ?: ""
            assertTrue(query.contains("scope=directory"))
            assertTrue(query.contains("dir=src%2Fmy+dir"))
            @Suppress("SpellCheckingInspection")
            assertTrue(query.contains("file=src%2Fmain.py"))
            assertTrue(query.contains("include_unversioned=true"))
            assertTrue(query.contains("changed_files_mode=staged"))
            assertTrue(query.contains("max_files=5"))
            assertTrue(query.contains("profile=LLM+Fast+Checks"))
        }
    }

    @Test
    fun inspectionGetStatusAddsGuidance() {
        MockIdeServer().use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val response = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            val text = response.firstText()
            assertTrue(text.contains("\"has_inspection_results\": true"))
            assertTrue(text.contains("STATUS: Inspection complete"))
        }
    }

    @Test
    fun inspectionGetStatusHandlesScanning() {
        val response = """{"is_scanning":true}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            assertTrue(result.firstText().contains("still running"))
        }
    }

    @Test
    fun inspectionGetStatusHandlesCleanInspection() {
        val response = """{"clean_inspection":true}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            assertTrue(result.firstText().contains("codebase is clean"))
        }
    }

    @Test
    fun inspectionGetStatusHandlesNoRecentInspection() {
        val response = """{"is_scanning":false,"has_inspection_results":false,"time_since_last_trigger_ms":120000}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            assertTrue(result.firstText().contains("No recent inspection"))
        }
    }

    @Test
    fun inspectionGetStatusHandlesRecentNoResults() {
        val response = """{"is_scanning":false,"has_inspection_results":false,"time_since_last_trigger_ms":5000}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            assertTrue(result.firstText().contains("no results were captured"))
        }
    }

    @Test
    fun inspectionWaitUsesTimeoutParams() {
        MockIdeServer().use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())
            val args = buildJsonObject {
                put("timeout_ms", JsonPrimitive(180000))
                put("poll_ms", JsonPrimitive(500))
            }

            val response = executor.handleToolCall(buildToolCall("inspection_wait", args))
            val text = response.firstText()
            assertTrue(text.contains("\"wait_completed\": true"))
            val query = server.lastQuery.get() ?: ""
            assertTrue(query.contains("timeout_ms=180000"))
            assertTrue(query.contains("poll_ms=500"))
        }
    }

    @Test
    fun inspectionWaitOmitsDefaultParams() {
        MockIdeServer().use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            val query = server.lastQuery.get()
            assertTrue(query.isNullOrEmpty())
        }
    }

    @Test
    fun inspectionWaitHandlesNoProject() {
        val response = """{"wait_completed":true,"completion_reason":"no_project"}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            assertTrue(result.firstText().contains("No project found"))
        }
    }

    @Test
    fun inspectionWaitHandlesInterrupted() {
        val response = """{"wait_completed":false,"completion_reason":"interrupted"}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            assertTrue(result.firstText().contains("Wait interrupted"))
        }
    }

    @Test
    fun inspectionWaitHandlesTimeout() {
        val response = """{"wait_completed":false,"timed_out":true}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            assertTrue(result.firstText().contains("Wait timed out"))
        }
    }

    @Test
    fun inspectionWaitHandlesNoResults() {
        val response = """{"wait_completed":true,"completion_reason":"no_results"}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            assertTrue(result.firstText().contains("no results were captured"))
        }
    }

    @Test
    fun inspectionGetProblemsHandlesInvalidJson() {
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse("{"))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))
            assertTrue(result.isError())
            assertTrue(result.firstText().contains("Invalid JSON response"))
        }
    }

    @Test
    fun inspectionGetProblemsHandlesNon200Status() {
        val response = MockResponse("""{"error":"boom"}""", status = 500)
        MockIdeServer(mapOf("/api/inspection/problems" to response)).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))
            assertTrue(result.isError())
            assertTrue(result.firstText().contains("Unexpected HTTP status 500"))
        }
    }
}

private data class MockResponse(val body: String, val status: Int = 200)

private class MockIdeServer(overrides: Map<String, MockResponse> = emptyMap()) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int = server.address.port
    val baseUrl: String = "http://localhost:$port/api/inspection"
    val lastQuery = AtomicReference<String?>()

    init {
        val defaults = mapOf(
            "/api/inspection/status" to MockResponse("""{"is_scanning":false,"has_inspection_results":true}"""),
            "/api/inspection/problems" to MockResponse(
                """{"status":"results_available","total_problems":2,"problems_shown":2,"problems":[]}"""
            ),
            "/api/inspection/trigger" to MockResponse("""{"status":"triggered","message":"Inspection triggered"}"""),
            "/api/inspection/wait" to MockResponse("""{"wait_completed":true,"completion_reason":"results"}""")
        )

        val responses = defaults.toMutableMap()
        responses.putAll(overrides)
        responses.forEach { (path, response) ->
            register(path, response)
        }
    }

    fun start() {
        server.start()
    }

    override fun close() {
        server.stop(0)
    }

    private fun register(path: String, response: MockResponse) {
        server.createContext(path) { exchange ->
            lastQuery.set(exchange.requestURI.rawQuery)
            val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}

private fun buildToolCall(name: String, args: JsonObject): JsonObject {
    return buildJsonObject {
        put("name", JsonPrimitive(name))
        put("arguments", args)
    }
}

private fun JsonObject.firstText(): String {
    val content = this["content"]?.jsonArray?.firstOrNull()?.jsonObject ?: return ""
    return content["text"]?.jsonPrimitive?.content ?: ""
}

private fun JsonObject.isError(): Boolean {
    return this["isError"]?.jsonPrimitive?.booleanOrNull == true
}

private fun JsonObject.string(field: String): String? {
    return this[field]?.jsonPrimitive?.contentOrNull
}
