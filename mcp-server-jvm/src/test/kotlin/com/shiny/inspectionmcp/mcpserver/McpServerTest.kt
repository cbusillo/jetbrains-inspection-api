package com.shiny.inspectionmcp.mcpserver

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
}

private class MockIdeServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int = server.address.port
    val baseUrl: String = "http://localhost:$port/api/inspection"
    val lastQuery = AtomicReference<String?>()

    init {
        register("/api/inspection/status", """{"is_scanning":false,"has_inspection_results":true}""")
        register(
            "/api/inspection/problems",
            """{"status":"results_available","total_problems":2,"problems_shown":2,"problems":[]}"""
        )
        register("/api/inspection/trigger", """{"status":"triggered","message":"Inspection triggered"}""")
        register("/api/inspection/wait", """{"wait_completed":true,"completion_reason":"results"}""")
    }

    fun start() {
        server.start()
    }

    override fun close() {
        server.stop(0)
    }

    private fun register(path: String, responseBody: String) {
        server.createContext(path) { exchange ->
            lastQuery.set(exchange.requestURI.rawQuery)
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
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

private fun JsonObject.string(field: String): String? {
    return this[field]?.jsonPrimitive?.contentOrNull
}
