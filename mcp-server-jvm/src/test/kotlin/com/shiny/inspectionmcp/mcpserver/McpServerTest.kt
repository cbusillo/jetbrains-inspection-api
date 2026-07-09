package com.shiny.inspectionmcp.mcpserver

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.Authenticator
import java.net.ConnectException
import java.net.CookieHandler
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

class McpServerTest {
    @TempDir
    lateinit var tempDir: Path

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
            setOf("inspection_list_projects", "inspection_get_problems", "inspection_trigger", "inspection_get_status", "inspection_wait"),
            names
        )
        val trigger = tools?.first { it.jsonObject["name"]?.jsonPrimitive?.content == "inspection_trigger" }?.jsonObject
        assertTrue(trigger?.string("description")?.contains("inspection_wait") == true)
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
                put("include_stale", JsonPrimitive(true))
            }

            val response = executor.handleToolCall(buildToolCall("inspection_get_problems", args))
            val text = response.firstText()
            assertTrue(text.contains("\"total_problems\": 2"))
            assertTrue(text.contains("VERDICT: RED"))
            assertTrue(text.contains("Found 2 problems total"))
            val query = server.lastQuery.get() ?: ""
            assertTrue(query.contains("file_pattern=my+file.js"))
            assertTrue(query.contains("limit=10"))
            assertTrue(query.contains("include_stale=true"))
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
            assertTrue(text.contains("VERDICT: RED"))
            assertTrue(text.contains("Found 5 problems total"))
            assertTrue(text.contains("NEXT: More results available"))
            val query = server.lastQuery.get() ?: ""
            assertTrue(query.contains("offset=2"))
            assertTrue(query.contains("problem_type=SpellCheckingInspection"))
        }
    }

    @Test
    fun inspectionGetProblemsHandlesNoResults() {
        val response = """{"status":"no_results","total_problems":0,"problems_shown":0,"problems":[],"pagination":{"limit":100,"offset":0,"has_more":false,"next_offset":null},"filters":{"severity":"all","scope":"whole_project","problem_type":"all","file_pattern":"all"}}"""
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=no_results"))
            assertFalse(text.contains("clean runs"))
        }
    }

    @Test
    fun inspectionGetProblemsReportsInvalidParameterDetails() {
        val response = """{"error":"Invalid request parameter","parameter":"limit","message":"Parameter 'limit' must be at least 1."}"""
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse(response, 400))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(
                buildToolCall(
                    "inspection_get_problems",
                    buildJsonObject { put("limit", JsonPrimitive(0)) },
                )
            )

            val text = result.firstText()
            assertTrue(result.isError())
            assertTrue(text.contains("Unexpected HTTP status 400"))
            assertTrue(text.contains("Invalid request parameter"))
            assertTrue(text.contains("Parameter: limit"))
            assertTrue(text.contains("must be at least 1"))
        }
    }

    @Test
    fun inspectionGetProblemsWarnsWhenCaptureIncomplete() {
        val response = """{"status":"capture_incomplete","capture_incomplete_reason":"extractor_failure","proof_failures":["extractor_failure"],"capture_diagnostic":{"exit_reason":"deadline"},"total_problems":0,"problems_shown":0,"problems":[]}"""
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("NEXT_ACTION"))
            assertTrue(text.contains("reason=extractor_failure"))
            assertTrue(text.contains("capture_incomplete_reason"))
            assertFalse(text.contains("capture_diagnostic"))
            assertFalse(text.contains("OK: No problems found matching filters"))
        }
    }

    @Test
    fun inspectionGetProblemsKeepsExplicitStaleDiagnostics() {
        val response = """{"status":"stale_results","results_may_be_stale":true,"include_stale":true,"cached_total_problems":1,"cached_problems_shown":1,"problems":[{"description":"Cached warning"}],"pagination":{"limit":100,"offset":0,"has_more":false},"inspection_verdict":"UNKNOWN","inspection_verdict_reason":"stale_results"}"""
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(
                buildToolCall("inspection_get_problems", buildJsonObject { put("include_stale", JsonPrimitive(true)) })
            )
            val text = result.firstText()
            assertTrue(text.startsWith("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=stale_results"))
            assertTrue(text.contains("Cached warning"))
            assertTrue(text.contains("\"problems\""))
        }
    }

    @Test
    fun inspectionGetProblemsWarnsWhenResultsAreStale() {
        val response = """{"status":"stale_results","message":"Project files changed since the last inspection."}"""
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=stale_results"))
            assertTrue(text.contains("results are stale"))
            assertTrue(text.contains("include_stale=true"))
            assertTrue(text.contains("explicit cached-result diagnostics"))
        }
    }

    @Test
    fun inspectionGetProblemsBlockerOverridesPluginRedVerdict() {
        val response = """{"status":"stale_results","results_may_be_stale":true,"inspection_verdict":"RED","inspection_verdict_reason":"actionable_findings","total_problems":5,"problems":[]}"""
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=stale_results"))
            assertFalse(text.contains("VERDICT: RED"))
        }
    }

    @Test
    fun inspectionGetProblemsProofFailureOverridesPluginRedVerdict() {
        val response = """{"status":"results_available","total_problems":1,"problems_shown":1,"problems":[{"description":"Wrong scope warning"}],"proof_failures":["route_mismatch"],"inspection_verdict":"RED","inspection_verdict_reason":"actionable_findings"}"""
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=inspection_proof_failed"))
            assertFalse(text.contains("VERDICT: RED"))
            assertFalse(text.contains("Wrong scope warning"))
            assertFalse(text.contains("\"problems\""))
        }
    }

    @Test
    fun mcpRenderedVerdictsMatchSharedContractFixtures() {
        listOf(
            contractCase("problems-red-findings"),
            contractCase("problems-unknown-capture-incomplete"),
            contractCase("problems-unknown-stale-default"),
            contractCase("status-green-clean"),
            contractCase("status-unknown-proof-failed"),
        ).forEach { contract ->
            val path = "/api/inspection/${contract.endpoint}"
            MockIdeServer(mapOf(path to MockResponse(contract.payloadText))).use { server ->
                server.start()
                val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())
                val tool = when (contract.endpoint) {
                    "problems" -> "inspection_get_problems"
                    "status" -> "inspection_get_status"
                    else -> error("Unsupported contract endpoint: ${contract.endpoint}")
                }

                val result = executor.handleToolCall(buildToolCall(tool, buildJsonObject { }))
                val text = result.firstText()

                assertFalse(result.isError(), contract.name)
                assertTrue(text.contains("VERDICT: ${contract.expected.string("verdict")} reason=${contract.expected.string("reason")}"), contract.name)
                contract.expected.stringArray("mcp_contains").forEach { expectedText ->
                    assertTrue(text.contains(expectedText), "${contract.name} missing $expectedText in:\n$text")
                }
                contract.expected.stringArray("mcp_not_contains").forEach { forbiddenText ->
                    assertFalse(text.contains(forbiddenText), "${contract.name} leaked $forbiddenText in:\n$text")
                }
                assertFixtureSatisfiesHelperContract(contract)
            }
        }
    }

    @Test
    fun inspectionGetProblemsHandlesZeroResults() {
        val response = """{"status":"results_available","total_problems":0,"problems_shown":0,"problems":[]}"""
        MockIdeServer(mapOf("/api/inspection/problems" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: GREEN"))
            assertTrue(text.contains("reason=no_matching_findings"))
            assertFalse(text.contains("No problems found"))
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
    fun inspectionTriggerReturnsExistingRunForConflict() {
        val response = """{"error":"inspection_in_progress","status":"inspection_in_progress","inspection_in_progress":true,"inspection_run_id":42}"""
        MockIdeServer(mapOf("/api/inspection/trigger" to MockResponse(response, 409))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_trigger", buildJsonObject { }))
            val text = result.firstText()

            assertFalse(result.isError())
            assertTrue(text.contains("\"status\": \"inspection_in_progress\""))
            assertTrue(text.contains("\"inspection_run_id\": 42"))
            assertTrue(text.contains("inspection_wait"))
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
            assertTrue(query.contains("file=src%2Fmain.py"))
            assertTrue(query.contains("include_unversioned=true"))
            assertTrue(query.contains("changed_files_mode=staged"))
            assertFalse(query.contains("max_files=5"))
            assertTrue(query.contains("profile=LLM+Fast+Checks"))
        }
    }

    @Test
    fun inspectionTriggerForwardsPositiveMaxFilesOnlyForChangedFiles() {
        MockIdeServer().use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())
            val args = buildJsonObject {
                put("scope", JsonPrimitive(" Changed_Files "))
                put("max_files", JsonPrimitive(5))
            }

            executor.handleToolCall(buildToolCall("inspection_trigger", args))

            val query = server.lastQuery.get() ?: ""
            assertTrue(query.contains("scope=+Changed_Files+"))
            assertTrue(query.contains("max_files=5"))
        }
    }

    @Test
    fun inspectionTriggerIgnoresNonPositiveMaxFilesFromToolWrappers() {
        MockIdeServer().use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())
            val args = buildJsonObject {
                put("scope", JsonPrimitive("files"))
                put("files", JsonArray(listOf(JsonPrimitive("src/a.py"))))
                put("max_files", JsonPrimitive(0))
            }

            executor.handleToolCall(buildToolCall("inspection_trigger", args))

            val query = server.lastQuery.get() ?: ""
            assertTrue(query.contains("scope=files"))
            assertFalse(query.contains("max_files=0"))
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
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=results_available_without_findings"))
        }
    }

    @Test
    fun inspectionGetStatusOmitsBlankProjectParam() {
        MockIdeServer().use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            executor.handleToolCall(
                buildToolCall(
                    "inspection_get_status",
                    buildJsonObject {
                        put("project", JsonPrimitive("   "))
                    },
                ),
            )

            val query = server.lastQuery.get()
            assertTrue(query.isNullOrEmpty())
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
    fun inspectionGetStatusBlockerOverridesPluginGreenVerdict() {
        val response = """{"is_scanning":true,"inspection_in_progress":true,"inspection_verdict":"GREEN","inspection_verdict_reason":"clean_confirmed","clean_inspection":true}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=inspection_still_running"))
            assertFalse(text.contains("VERDICT: GREEN"))
        }
    }

    @Test
    fun inspectionGetStatusProofFailureOverridesPluginGreenVerdict() {
        val response = """{"status":"results_available","clean_inspection":true,"total_problems":0,"problems_shown":0,"problems":[],"proof_failures":["profile_mismatch"],"inspection_proof":{"status":"failed","proof_failures":["profile_mismatch"]},"inspection_verdict":"GREEN","inspection_verdict_reason":"clean_confirmed"}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.startsWith("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=inspection_proof_failed"))
            assertFalse(text.contains("VERDICT: GREEN"))
            assertFalse(text.contains("\"total_problems\": 0"))
            assertFalse(text.contains("\"problems\""))
        }
    }

    @Test
    fun inspectionGetStatusHandlesCleanInspection() {
        val response = """{"clean_inspection":true}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: GREEN"))
            assertTrue(text.contains("reason=clean_confirmed"))
        }
    }

    @Test
    fun inspectionGetStatusHandlesStaleResults() {
        val response = """{"results_may_be_stale":true}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=stale_results"))
            assertTrue(text.contains("Trigger inspection again"))
        }
    }

    @Test
    fun inspectionGetStatusHandlesCaptureIncomplete() {
        val response = """{"capture_incomplete":true,"capture_incomplete_reason":"view_not_ready","has_inspection_results":false,"clean_inspection":false}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("NEXT_ACTION"))
            assertTrue(text.contains("reason=view_not_ready"))
            assertFalse(text.contains("codebase is clean"))
        }
    }

    @Test
    fun inspectionGetStatusHandlesNoRecentInspection() {
        val response = """{"is_scanning":false,"has_inspection_results":false,"time_since_last_trigger_ms":120000}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=no_recent_inspection"))
        }
    }

    @Test
    fun inspectionGetStatusHandlesRecentNoResults() {
        val response = """{"is_scanning":false,"has_inspection_results":false,"time_since_last_trigger_ms":5000}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=no_results"))
        }
    }

    @Test
    fun inspectionGetStatusDoesNotReportProblemsForCleanSnapshot() {
        val response = """{"is_scanning":false,"has_inspection_results":false,"clean_inspection":true,"total_problems":0,"time_since_last_trigger_ms":5000}"""
        MockIdeServer(mapOf("/api/inspection/status" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: GREEN"))
            assertTrue(text.contains("reason=clean_confirmed"))
            assertFalse(text.contains("STATUS: Inspection complete - problems found"))
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
    fun inspectionWaitOmitsBlankProjectParam() {
        MockIdeServer().use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            executor.handleToolCall(
                buildToolCall(
                    "inspection_wait",
                    buildJsonObject {
                        put("project", JsonPrimitive(""))
                    },
                ),
            )

            val query = server.lastQuery.get()
            assertTrue(query.isNullOrEmpty())
        }
    }

    @Test
    fun inspectionWaitHandlesNoProject() {
        val response = """{"wait_completed":false,"timed_out":false,"completion_reason":"no_project"}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=no_project"))
            assertTrue(text.contains("exact project name"))
        }
    }

    @Test
    fun inspectionWaitHandlesInterrupted() {
        val response = """{"wait_completed":false,"completion_reason":"interrupted"}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=interrupted"))
        }
    }

    @Test
    fun inspectionWaitHandlesTimeout() {
        val response = """{"wait_completed":false,"timed_out":true}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=timeout"))
        }
    }

    @Test
    fun inspectionWaitBlockerOverridesPluginGreenVerdict() {
        val response = """{"wait_completed":false,"timed_out":true,"inspection_verdict":"GREEN","inspection_verdict_reason":"clean_confirmed"}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=timeout"))
            assertFalse(text.contains("VERDICT: GREEN"))
        }
    }

    @Test
    fun inspectionWaitProofFailureOverridesPluginGreenVerdict() {
        val response = """{"wait_completed":true,"completion_reason":"clean","total_problems":0,"problems_shown":0,"problems":[],"proof_failures":["run_mismatch"],"inspection_verdict":"GREEN","inspection_verdict_reason":"clean_confirmed"}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.startsWith("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=inspection_proof_failed"))
            assertFalse(text.contains("VERDICT: GREEN"))
            assertFalse(text.contains("\"total_problems\": 0"))
            assertFalse(text.contains("\"problems\""))
        }
    }

    @Test
    fun inspectionWaitHandlesNoResults() {
        val response = """{"wait_completed":true,"completion_reason":"no_results"}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=no_results"))
            assertTrue(text.contains("Retry once"))
        }
    }

    @Test
    fun inspectionWaitHandlesNoRecentInspection() {
        val response = """{"wait_completed":false,"completion_reason":"no_recent_inspection"}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=no_recent_inspection"))
        }
    }

    @Test
    fun inspectionWaitHandlesStaleResults() {
        val response = """{"wait_completed":true,"completion_reason":"stale_results","results_may_be_stale":true}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("reason=stale_results"))
            assertTrue(text.contains("Trigger inspection again"))
        }
    }

    @Test
    fun inspectionWaitHandlesCaptureIncomplete() {
        val response = """{"wait_completed":true,"completion_reason":"capture_incomplete","capture_incomplete_reason":"timeout"}"""
        MockIdeServer(mapOf("/api/inspection/wait" to MockResponse(response))).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
            val text = result.firstText()
            assertTrue(text.contains("VERDICT: UNKNOWN"))
            assertTrue(text.contains("NEXT_ACTION"))
            assertTrue(text.contains("reason=timeout"))
            assertFalse(text.contains("codebase is clean"))
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
        val response = MockResponse(
            """{"error":"Requested project 'missing' is not open in the IDE.","suggested_open_projects":["odoo-ai"],"suggested_recent_projects":[{"name":"odoo-api","path":"/tmp/odoo-api"}]}""",
            status = 404,
        )
        MockIdeServer(mapOf("/api/inspection/problems" to response)).use { server ->
            server.start()
            val executor = ToolExecutor(server.baseUrl, HttpClient.newHttpClient(), server.port.toString())

            val result = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))
            assertTrue(result.isError())
            assertTrue(result.firstText().contains("Unexpected HTTP status 404"))
            assertTrue(result.firstText().contains("Requested project 'missing' is not open in the IDE."))
            assertTrue(result.firstText().contains("Open: odoo-ai"))
            assertTrue(result.firstText().contains("Recent: odoo-api (/tmp/odoo-api)"))
        }
    }

    @Test
    fun inspectionGetStatusRetriesWithFreshClientAfterTransportFailure() {
        val replacementClientFactoryCalls = AtomicInteger(0)
        val replacementClient = StaticHttpClient(
            StaticHttpResponse(
                statusCode = 200,
                request = HttpRequest.newBuilder(URI("http://localhost:63343/api/inspection/status")).build(),
                body = """{"is_scanning":false,"clean_inspection":true}"""
            )
        )
        val executor = ToolExecutor(
            "http://localhost:63343/api/inspection",
            ThrowingHttpClient(ConnectException("Connection refused")),
            "63343"
        ) {
            replacementClientFactoryCalls.incrementAndGet()
            replacementClient
        }

        val result = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))

        assertFalse(result.isError())
        assertTrue(result.firstText().contains("VERDICT: GREEN"))
        assertEquals(1, replacementClientFactoryCalls.get())
    }

    @Test
    fun inspectionListProjectsReturnsAutoDiscoveredProjects() {
        MockIdeServer().use { server ->
            server.start()
            val executor = autoExecutor(server)

            val result = executor.handleToolCall(buildToolCall("inspection_list_projects", buildJsonObject { }))
            val text = result.firstText()

            assertFalse(result.isError())
            assertTrue(text.contains("\"mode\": \"auto\""))
            assertTrue(text.contains("jetbrains-inspection-api"))
            assertTrue(text.contains("project_key"))
        }
    }

    @Test
    fun autoRoutingUsesProjectPathAndForwardsProjectKey() {
        MockIdeServer().use { server ->
            server.start()
            val executor = autoExecutor(server)

            val result = executor.handleToolCall(
                buildToolCall(
                    "inspection_trigger",
                    buildJsonObject {
                        put("project_path", JsonPrimitive("/tmp/jetbrains-inspection-api"))
                    },
                )
            )

            assertFalse(result.isError())
            assertTrue(result.firstText().contains("ROUTE:"))
            val query = server.lastQuery.get() ?: ""
            assertTrue(query.contains("project_key=path%3A%2Ftmp%2Fjetbrains-inspection-api"))
        }
    }

    @Test
    fun autoRoutingUsesCwdSelectorAndForwardsProjectKey() {
        MockIdeServer(identityProjectName = "cwd", identityBasePath = "/tmp/cwd-project").use { server ->
            server.start()
            val executor = autoExecutor(server)

            val result = executor.handleToolCall(
                buildToolCall(
                    "inspection_get_status",
                    buildJsonObject {
                        put("cwd", JsonPrimitive("/tmp/cwd-project/src"))
                    },
                )
            )

            assertFalse(result.isError())
            val query = server.lastQuery.get() ?: ""
            assertTrue(query.contains("project_key=path%3A%2Ftmp%2Fcwd-project"))
        }
    }

    @Test
    fun autoRoutingReportsAmbiguousProjectNames() {
        MockIdeServer(identityProjectName = "shared", identityBasePath = "/tmp/one", identitySessionId = "one").use { first ->
            MockIdeServer(identityProjectName = "shared", identityBasePath = "/tmp/two", identitySessionId = "two").use { second ->
                first.start()
                second.start()
                val executor = autoExecutor(first, second)

                val result = executor.handleToolCall(
                    buildToolCall(
                        "inspection_get_status",
                        buildJsonObject { put("project", JsonPrimitive("shared")) },
                    )
                )

                assertTrue(result.isError())
                assertTrue(result.firstText().contains("Multiple JetBrains projects matched"))
                assertTrue(result.firstText().contains("project_key=path:/tmp/one"))
                assertTrue(result.firstText().contains("project_key=path:/tmp/two"))
            }
        }
    }

    @Test
    fun autoRoutingKeepsSelectorFreeFollowUpsOnLastTriggeredProject() {
        MockIdeServer(identityProjectName = "first", identityBasePath = "/tmp/first", identitySessionId = "first").use { first ->
            MockIdeServer(
                identityProjectName = "cwd-project",
                identityBasePath = "/Users/cbusillo/Developer/jetbrains-inspection-api",
                identitySessionId = "cwd",
            ).use { second ->
                first.start()
                second.start()
                val executor = autoExecutor(first, second)

                val trigger = executor.handleToolCall(
                    buildToolCall(
                        "inspection_trigger",
                        buildJsonObject { put("project_path", JsonPrimitive("/tmp/first")) },
                    )
                )
                val wait = executor.handleToolCall(buildToolCall("inspection_wait", buildJsonObject { }))
                val problems = executor.handleToolCall(buildToolCall("inspection_get_problems", buildJsonObject { }))

                assertFalse(trigger.isError())
                assertFalse(wait.isError())
                assertFalse(problems.isError())
                assertTrue(wait.firstText().contains("project_key=path:/tmp/first"))
                assertTrue(problems.firstText().contains("project_key=path:/tmp/first"))
                assertFalse(wait.firstText().contains("cwd-project"))
                assertFalse(problems.firstText().contains("cwd-project"))
            }
        }
    }

    @Test
    fun autoRoutingRejectsStatusWhenPinnedSessionDrifts() {
        MockIdeServer(identityProjectName = "project", identityBasePath = "/tmp/project", identitySessionId = "old").use { oldSession ->
            MockIdeServer(identityProjectName = "project", identityBasePath = "/tmp/project", identitySessionId = "new").use { newSession ->
                oldSession.start()
                val executor = autoExecutor(oldSession, newSession)

                val trigger = executor.handleToolCall(
                    buildToolCall(
                        "inspection_trigger",
                        buildJsonObject { put("project_path", JsonPrimitive("/tmp/project")) },
                    )
                )
                oldSession.close()
                newSession.start()
                val status = executor.handleToolCall(buildToolCall("inspection_get_status", buildJsonObject { }))

                assertFalse(trigger.isError())
                assertTrue(status.isError())
                assertTrue(status.firstText().contains("restarted before inspection_get_status completed"))
                assertTrue(status.firstText().contains("Re-trigger inspection"))
            }
        }
    }

    @Test
    fun excludedSessionsDoNotPoisonLaterAutoDiscovery() {
        MockIdeServer().use { server ->
            server.start()
            val resolver = AutoTargetResolver(
                httpClientFactory = { HttpClient.newHttpClient() },
                registryDir = tempDir,
                scanPorts = listOf(server.port),
            )
            val args = buildJsonObject {
                put("project_path", JsonPrimitive("/tmp/jetbrains-inspection-api"))
            }

            val first = resolver.resolve(args)
            val sessionId = first.identity?.get("session_id")?.jsonPrimitive?.contentOrNull
            assertNotNull(sessionId)

            val excluded = runCatching { resolver.resolve(args, excludedSessionIds = setOf(sessionId ?: "")) }
            assertTrue(excluded.isFailure)

            val recovered = resolver.resolve(args)
            assertEquals(sessionId, recovered.identity?.get("session_id")?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun inspectionListProjectsReportsFixedTargetMode() {
        val executor = ToolExecutor("http://localhost:63343/api/inspection", HttpClient.newHttpClient(), "63343")

        val result = executor.handleToolCall(buildToolCall("inspection_list_projects", buildJsonObject { }))
        val text = result.firstText()

        assertFalse(result.isError())
        assertTrue(text.contains("\"mode\": \"fixed\""))
        assertTrue(text.contains("\"port\": \"63343\""))
        assertTrue(text.contains("http://localhost:63343/api/inspection"))
    }

    @Test
    fun autoRoutingUsesFreshRegistryIdentityBeforePortScan() {
        MockIdeServer(identityProjectName = "registry-project", identityBasePath = "/tmp/registry-project").use { server ->
            server.start()
            Files.writeString(tempDir.resolve("registry-project.json"), registryIdentityBody(server.port, "/tmp/registry-project"))
            val resolver = AutoTargetResolver(
                httpClientFactory = { HttpClient.newHttpClient() },
                registryDir = tempDir,
                scanPorts = emptyList(),
            )

            val target = resolver.resolve(
                buildJsonObject { put("project_path", JsonPrimitive("/tmp/registry-project")) }
            )

            assertEquals(server.port.toString(), target.idePort)
            assertEquals("registry-project", target.project?.get("name")?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun autoRoutingIgnoresStaleRegistryIdentityAndFallsBackToPortScan() {
        MockIdeServer().use { server ->
            server.start()
            Files.writeString(
                tempDir.resolve("stale.json"),
                registryIdentityBody(server.port, "/tmp/stale-project", heartbeatMs = 1),
            )
            val resolver = AutoTargetResolver(
                httpClientFactory = { HttpClient.newHttpClient() },
                registryDir = tempDir,
                scanPorts = listOf(server.port),
                nowMs = { 1_000_000 },
            )

            val target = resolver.resolve(
                buildJsonObject { put("project_path", JsonPrimitive("/tmp/jetbrains-inspection-api")) }
            )

            assertEquals(server.port.toString(), target.idePort)
            assertEquals("jetbrains-inspection-api", target.project?.get("name")?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun autoRoutingReportsNoDiscoveredIdeInstances() {
        val resolver = AutoTargetResolver(
            httpClientFactory = { HttpClient.newHttpClient() },
            registryDir = tempDir,
            scanPorts = emptyList(),
        )

        val result = runCatching { resolver.resolve(buildJsonObject { }) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No JetBrains IDE inspection plugin instances") == true)
    }

    @Test
    fun autoRoutingReportsDiscoveredProjectsWhenNoProjectMatches() {
        MockIdeServer().use { server ->
            server.start()
            val resolver = AutoTargetResolver(
                httpClientFactory = { HttpClient.newHttpClient() },
                registryDir = tempDir,
                scanPorts = listOf(server.port),
            )

            val result = runCatching {
                resolver.resolve(buildJsonObject { put("project_path", JsonPrimitive("/tmp/not-open")) })
            }

            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message ?: ""
            assertTrue(message.contains("No open JetBrains project matched this request"))
            assertTrue(message.contains("Mock IDEA / jetbrains-inspection-api / /tmp/jetbrains-inspection-api"))
        }
    }

    @Test
    fun autoRoutingCanSelectByIdeNameAndProjectKey() {
        MockIdeServer().use { server ->
            server.start()
            val resolver = AutoTargetResolver(
                httpClientFactory = { HttpClient.newHttpClient() },
                registryDir = tempDir,
                scanPorts = listOf(server.port),
            )

            val target = resolver.resolve(
                buildJsonObject {
                    put("ide", JsonPrimitive("idea"))
                    put("project_key", JsonPrimitive("path:/tmp/jetbrains-inspection-api"))
                },
            )

            assertEquals(server.port.toString(), target.idePort)
            assertEquals("path:/tmp/jetbrains-inspection-api", target.project?.get("project_key")?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun autoRoutingMatchesProjectNameCaseInsensitively() {
        MockIdeServer().use { server ->
            server.start()
            val resolver = AutoTargetResolver(
                httpClientFactory = { HttpClient.newHttpClient() },
                registryDir = tempDir,
                scanPorts = listOf(server.port),
            )

            val target = resolver.resolve(
                buildJsonObject { put("project", JsonPrimitive("JETBRAINS-INSPECTION-API")) },
            )

            assertEquals(server.port.toString(), target.idePort)
            assertEquals("jetbrains-inspection-api", target.project?.get("name")?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun autoRoutingMatchesNestedProjectPath() {
        MockIdeServer().use { server ->
            server.start()
            val resolver = AutoTargetResolver(
                httpClientFactory = { HttpClient.newHttpClient() },
                registryDir = tempDir,
                scanPorts = listOf(server.port),
            )

            val target = resolver.resolve(
                buildJsonObject { put("project_path", JsonPrimitive("/tmp/jetbrains-inspection-api/src/main")) },
            )

            assertEquals(server.port.toString(), target.idePort)
        }
    }

    @Test
    fun autoRoutingPrefersNestedProjectFileRootOverContainingParent() {
        MockIdeServer(identityProjectName = "Parent", identityBasePath = "/tmp/repo", identitySessionId = "parent").use { parent ->
            MockIdeServer(
                identityProjectName = "Child",
                identityBasePath = null,
                identityProjectFilePath = "/tmp/repo/packages/app/.idea/misc.xml",
                identitySessionId = "child",
            ).use { child ->
                parent.start()
                child.start()
                val resolver = AutoTargetResolver(
                    httpClientFactory = { HttpClient.newHttpClient() },
                    registryDir = tempDir,
                    scanPorts = listOf(parent.port, child.port),
                )

                val target = resolver.resolve(
                    buildJsonObject { put("worktree_path", JsonPrimitive("/tmp/repo/packages/app/src/main")) },
                )

                assertEquals(child.port.toString(), target.idePort)
                assertEquals("Child", target.project?.get("name")?.jsonPrimitive?.contentOrNull)
            }
        }
    }

    @Test
    fun defaultAutoRoutingSettingsUseRegistryDirAndInspectionPortRange() {
        val registryDir = defaultRegistryDir().toString()
        val ports = defaultScanPorts()

        assertTrue(registryDir.endsWith("jetbrains-inspection-api/instances"))
        assertEquals((63340..63349).toList(), ports)
    }

    @Test
    fun targetResolverDefaultInvalidateIsNoOp() {
        val resolver: InspectionTargetResolver = FixedTargetResolver("http://localhost:63343/api/inspection", "63343")
        val target = resolver.resolve(buildJsonObject { })

        resolver.invalidate(target)

        assertEquals("63343", target.idePort)
    }

    private fun registryIdentityBody(
        port: Int,
        basePath: String,
        heartbeatMs: Long = System.currentTimeMillis(),
    ): String {
        return """
            {
              "session_id":"registry-$port",
              "started_at_ms":1,
              "heartbeat_ms":$heartbeatMs,
              "pid":${ProcessHandle.current().pid()},
              "port":$port,
              "ide_name":"Mock IDEA",
              "ide_product_code":"IU",
              "plugin_version":"test",
              "open_projects":[{
                "project_key":"path:$basePath",
                "name":"${basePath.substringAfterLast('/')}",
                "base_path":"$basePath",
                "focused":true
              }]
            }
        """.trimIndent()
    }
}

private data class MockResponse(val body: String, val status: Int = 200)

private abstract class BaseHttpClient : HttpClient() {
    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(5))

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>
    ): CompletableFuture<HttpResponse<T>> {
        throw UnsupportedOperationException("sendAsync not used in tests")
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>
    ): CompletableFuture<HttpResponse<T>> {
        throw UnsupportedOperationException("sendAsync not used in tests")
    }
}

private class ThrowingHttpClient(private val error: IOException) : BaseHttpClient() {
    override fun <T> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>
    ): HttpResponse<T> {
        throw error
    }
}

private class StaticHttpClient(private val response: HttpResponse<String>) : BaseHttpClient() {
    @Suppress("UNCHECKED_CAST")
    override fun <T> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>
    ): HttpResponse<T> {
        return response as HttpResponse<T>
    }
}

private data class StaticHttpResponse(
    private val statusCode: Int,
    private val request: HttpRequest,
    private val body: String
) : HttpResponse<String> {
    override fun statusCode(): Int = statusCode

    override fun request(): HttpRequest = request

    override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

    override fun body(): String = body

    override fun sslSession(): Optional<SSLSession> = Optional.empty()

    override fun uri(): URI = request.uri()

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}

private class MockIdeServer(
    overrides: Map<String, MockResponse> = emptyMap(),
    private val identityProjectName: String = "jetbrains-inspection-api",
    private val identityBasePath: String? = "/tmp/jetbrains-inspection-api",
    private val identityProjectFilePath: String? = null,
    private val identitySessionId: String = "session",
) : AutoCloseable {
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
            "/api/inspection/wait" to MockResponse("""{"wait_completed":true,"completion_reason":"results"}"""),
            "/api/inspection/identity" to MockResponse(identityBody()),
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

    private fun identityBody(): String {
        val projectKey = identityBasePath?.let { "path:$it" } ?: "file:$identityProjectFilePath"
        val basePathJson = identityBasePath?.let { "\"$it\"" } ?: "null"
        val projectFilePathJson = identityProjectFilePath?.let { "\"$it\"" } ?: "null"
        return """
            {
              "session_id":"$identitySessionId-${port}",
              "started_at_ms":1,
              "heartbeat_ms":9999999999999,
              "pid":${ProcessHandle.current().pid()},
              "port":$port,
              "ide_name":"Mock IDEA",
              "ide_product_code":"IU",
              "plugin_version":"test",
              "open_projects":[{
                "project_key":"$projectKey",
                "name":"$identityProjectName",
                "base_path":$basePathJson,
                "project_file_path":$projectFilePathJson,
                "focused":true
              }]
            }
        """.trimIndent()
    }
}

private fun McpServerTest.autoExecutor(vararg servers: MockIdeServer): ToolExecutor {
    return ToolExecutor(
        baseUrl = "http://localhost:0/api/inspection",
        httpClient = HttpClient.newHttpClient(),
        idePort = "auto",
        targetResolver = AutoTargetResolver(
            httpClientFactory = { HttpClient.newHttpClient() },
            registryDir = tempDir,
            scanPorts = servers.map { it.port },
        ),
    )
}

private fun buildToolCall(name: String, args: JsonObject): JsonObject {
    return buildJsonObject {
        put("name", JsonPrimitive(name))
        put("arguments", args)
    }
}

private data class ContractCase(
    val name: String,
    val endpoint: String,
    val payload: JsonObject,
    val payloadText: String,
    val expected: JsonObject,
)

private fun contractCase(name: String): ContractCase {
    val path = contractFixturePath(name)
    val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
    val payload = root["payload"]?.jsonObject ?: error("Missing payload in $name")
    return ContractCase(
        name = root.string("name") ?: name,
        endpoint = root.string("endpoint") ?: error("Missing endpoint in $name"),
        payload = payload,
        payloadText = Json.encodeToString(JsonElement.serializer(), payload),
        expected = root["expected"]?.jsonObject ?: error("Missing expected in $name"),
    )
}

private fun contractFixturePath(name: String): Path {
    val relative = Path.of("test-fixtures/contract-verdicts/$name.json")
    return generateSequence(Path.of("").toAbsolutePath()) { current -> current.parent }
        .map { root -> root.resolve(relative) }
        .firstOrNull { Files.exists(it) }
        ?: error("Missing contract fixture: $relative")
}

private fun assertFixtureSatisfiesHelperContract(contract: ContractCase) {
    val helper = contract.expected["helper_agent_result"]?.jsonObject
        ?: error("${contract.name} missing helper_agent_result")
    val retryPolicy = helper["retry_policy"]?.jsonObject
        ?: error("${contract.name} missing helper retry_policy")

    val helperContract = expectedHelperContract(contract)
    assertEquals(helperContract.verdict, helper.string("verdict"), "${contract.name} helper verdict")
    assertEquals(helperContract.bucket, helper.string("bucket"), "${contract.name} helper bucket")
    assertEquals(helperContract.retry, retryPolicy["retry"]?.jsonPrimitive?.booleanOrNull, "${contract.name} helper retry")
    assertEquals(helperContract.maxAttempts, retryPolicy["max_attempts"]?.jsonPrimitive?.intOrNull, "${contract.name} helper max_attempts")
    assertEquals(helperContract.waitMs, retryPolicy["wait_ms"]?.jsonPrimitive?.intOrNull, "${contract.name} helper wait_ms")
    assertEquals(helperContract.nextAction, helper.string("next_action"), "${contract.name} helper next_action")
    assertEquals(helperContract.agentReport, helper.string("agent_report"), "${contract.name} helper agent_report")
    listOf(
        "inspection_verdict",
        "inspection_verdict_reason",
        "inspection_verdict_message",
        "inspection_verdict_next_action",
    ).forEach { field ->
        assertTrue(!contract.payload.string(field).isNullOrBlank(), "${contract.name} missing $field")
    }
}

private data class HelperContract(
    val verdict: String,
    val bucket: String,
    val retry: Boolean,
    val maxAttempts: Int,
    val waitMs: Int,
    val nextAction: String,
    val agentReport: String,
)

private fun expectedHelperContract(contract: ContractCase): HelperContract {
    val verdict = contract.expected.string("verdict") ?: "UNKNOWN"
    val reason = contract.expected.string("reason") ?: "unknown"
    val bucket = when {
        verdict == "GREEN" -> "clean"
        verdict == "RED" -> "actionable_findings"
        reason == "stale_results" -> "stale_results"
        reason in setOf("extractor_failure", "inspection_proof_failed") -> "tool_bug"
        else -> "unknown"
    }
    val retry = verdict == "UNKNOWN" && bucket == "stale_results"
    val nextAction = when {
        verdict == "GREEN" -> "No inspection action required for this scope/filter."
        verdict == "RED" -> "Fix the reported findings, then rerun inspection."
        reason == "extractor_failure" -> "Treat this as a plugin/helper bug: capture the diagnostic payload, update the inspection plugin or helper skill, and rerun."
        reason == "stale_results" -> "Rerun inspection; stale cached findings must not be treated as current."
        reason == "inspection_proof_failed" -> "Do not report GREEN or RED. Rerun inspection and include helper diagnostics if it remains UNKNOWN."
        else -> "Do not report GREEN or RED. Rerun inspection and include helper diagnostics if it remains UNKNOWN."
    }
    val agentReport = when (verdict) {
        "GREEN" -> "JetBrains inspection passed for the selected scope."
        "RED" -> "JetBrains inspection found ${contract.payload["total_problems"]?.jsonPrimitive?.intOrNull ?: 0} actionable finding(s)."
        else -> "JetBrains inspection was inconclusive ($bucket: $reason). $nextAction"
    }
    return HelperContract(
        verdict = verdict,
        bucket = bucket,
        retry = retry,
        maxAttempts = if (retry) 1 else 0,
        waitMs = if (retry) 30000 else 0,
        nextAction = nextAction,
        agentReport = agentReport,
    )
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

private fun JsonObject.stringArray(field: String): List<String> {
    return this[field]?.jsonArray?.mapNotNull { item -> item.jsonPrimitive.contentOrNull } ?: emptyList()
}
