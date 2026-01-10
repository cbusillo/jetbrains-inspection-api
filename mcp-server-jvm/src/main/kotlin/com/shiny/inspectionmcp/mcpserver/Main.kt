@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.shiny.inspectionmcp.mcpserver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

private const val SERVER_NAME = "jetbrains-inspection-mcp"
private const val DEFAULT_PORT = "63341"
private const val DEFAULT_PROTOCOL_VERSION = "2024-11-05"

private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    explicitNulls = false
}

private object VersionAnchor

fun main() {
    val idePort = System.getenv("IDE_PORT")?.takeIf { it.isNotBlank() } ?: DEFAULT_PORT
    val baseUrl = "http://localhost:$idePort/api/inspection"
    val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    val toolExecutor = ToolExecutor(baseUrl, httpClient, idePort)

    System.err.println("[DEBUG] Starting Inspection MCP Server...")
    System.err.println("[DEBUG] Inspection MCP Server started successfully")

    val reader = BufferedReader(InputStreamReader(System.`in`))
    val writer = BufferedWriter(OutputStreamWriter(System.out))

    var line: String?
    while (true) {
        line = reader.readLine() ?: break
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue

        val response = try {
            handleIncomingMessage(trimmed, toolExecutor)
        } catch (error: Exception) {
            System.err.println("[ERROR] Failed to handle message: ${error.message}")
            null
        }

        if (response != null) {
            writer.write(json.encodeToString(JsonElement.serializer(), response))
            writer.newLine()
            writer.flush()
        }
    }
}

internal fun handleIncomingMessage(message: String, toolExecutor: ToolExecutor): JsonElement? {
    return when (val element = json.parseToJsonElement(message)) {
        is JsonArray -> {
            val responses = element.mapNotNull { handleRequest(it, toolExecutor) }
            if (responses.isEmpty()) null else JsonArray(responses)
        }
        else -> handleRequest(element, toolExecutor)
    }
}

private fun handleRequest(element: JsonElement, toolExecutor: ToolExecutor): JsonObject? {
    val obj = element as? JsonObject ?: return null
    val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return null
    val id = obj["id"]
    val params = obj["params"]

    return when (method) {
        "initialize" -> {
            if (id == null) return null
            val protocolVersion = (params as? JsonObject)?.string("protocolVersion") ?: DEFAULT_PROTOCOL_VERSION
            val result = buildJsonObject {
                put("protocolVersion", JsonPrimitive(protocolVersion))
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject {
                        put("listChanged", JsonPrimitive(false))
                    })
                })
                put("serverInfo", buildJsonObject {
                    put("name", JsonPrimitive(SERVER_NAME))
                    put("version", JsonPrimitive(serverVersion()))
                })
                put(
                    "instructions",
                    JsonPrimitive("Workflow: inspection_trigger -> inspection_wait (blocks; preferred) or poll inspection_get_status -> inspection_get_problems.")
                )
            }
            successResponse(id, result)
        }
        "notifications/initialized" -> null
        "tools/list" -> {
            if (id == null) return null
            val result = buildJsonObject {
                put("tools", toolExecutor.toolList())
            }
            successResponse(id, result)
        }
        "tools/call" -> {
            if (id == null) return null
            val result = toolExecutor.handleToolCall(params)
            successResponse(id, result)
        }
        "ping" -> {
            if (id == null) return null
            successResponse(id, buildJsonObject { })
        }
        else -> {
            if (id == null) return null
            methodNotFoundResponse(id, method)
        }
    }
}

private fun successResponse(id: JsonElement, result: JsonElement): JsonObject {
    return buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put("result", result)
    }
}

private fun methodNotFoundResponse(id: JsonElement, method: String): JsonObject {
    return buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put("error", buildJsonObject {
            put("code", JsonPrimitive(-32601))
            put("message", JsonPrimitive("Method not found: $method"))
        })
    }
}

private fun serverVersion(): String {
    return VersionAnchor::class.java.`package`.implementationVersion ?: "dev"
}

internal class ToolExecutor(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val idePort: String
) {
    fun toolList(): JsonArray {
        return JsonArray(
            listOf(
                buildJsonObject {
                    put("name", JsonPrimitive("inspection_get_problems"))
                    put(
                        "description",
                        JsonPrimitive(
                            "Fetch problems after inspection completes. Results mirror the IDE Problems/Inspection Results view; hidden or filtered views can hide warnings."
                        )
                    )
                    put("inputSchema", getProblemsSchema())
                },
                buildJsonObject {
                    put("name", JsonPrimitive("inspection_trigger"))
                    put(
                        "description",
                        JsonPrimitive("Start an inspection run (async).")
                    )
                    put("inputSchema", triggerSchema())
                },
                buildJsonObject {
                    put("name", JsonPrimitive("inspection_get_status"))
                    put(
                        "description",
                        JsonPrimitive(
                            "Check inspection status. If you expect warnings but see clean/no_results, re-run or open the Problems/Inspection Results view."
                        )
                    )
                    put("inputSchema", statusSchema())
                },
                buildJsonObject {
                    put("name", JsonPrimitive("inspection_wait"))
                    put(
                        "description",
                        JsonPrimitive(
                            "Block until inspection completes or timeout. May report no_results if the IDE view is unavailable or filtered."
                        )
                    )
                    put("inputSchema", waitSchema())
                }
            )
        )
    }

    fun handleToolCall(params: JsonElement?): JsonObject {
        val paramsObj = params as? JsonObject
        val name = paramsObj?.string("name")
        val args = paramsObj?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
        if (name == null) {
            return toolError("Missing tool name")
        }
        return when (name) {
            "inspection_get_problems" -> handleGetProblems(args)
            "inspection_trigger" -> handleTrigger(args)
            "inspection_get_status" -> handleGetStatus(args)
            "inspection_wait" -> handleWait(args)
            else -> toolError("Unknown tool: $name")
        }
    }

    private fun handleGetProblems(args: JsonObject): JsonObject {
        return try {
            val params = mutableListOf<Pair<String, String>>()
            args.string("project")?.let { params += "project" to it }
            val scope = args.string("scope") ?: "whole_project"
            val severity = args.string("severity") ?: "all"
            params += "scope" to scope
            params += "severity" to severity
            args.string("problem_type")?.let { params += "problem_type" to it }
            args.string("file_pattern")?.let { params += "file_pattern" to it }
            val limit = args.int("limit") ?: 100
            val offset = args.int("offset") ?: 0
            if (limit != 100) params += "limit" to limit.toString()
            if (offset != 0) params += "offset" to offset.toString()

            val url = buildUrl("$baseUrl/problems", params)
            val result = httpGet(url)

            val guidance = buildProblemsGuidance(result)
            val text = prettyJson.encodeToString(JsonElement.serializer(), result) + guidance
            toolText(text)
        } catch (error: Exception) {
            toolError("Error getting problems: ${error.message}")
        }
    }

    private fun handleTrigger(args: JsonObject): JsonObject {
        return try {
            val params = mutableListOf<Pair<String, String>>()
            args.string("project")?.let { params += "project" to it }
            args.string("scope")?.let { params += "scope" to it }
            val dir = args.string("dir") ?: args.string("directory") ?: args.string("path")
            dir?.let { params += "dir" to it }

            val files = args["files"]?.let { element ->
                when (element) {
                    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    is JsonPrimitive -> element.contentOrNull?.let { listOf(it) }
                    else -> null
                }
            }
            if (!files.isNullOrEmpty()) {
                files.forEach { params += "file" to it }
            }

            val includeUnversioned = (args["include_unversioned"] as? JsonPrimitive)?.booleanOrNull
            includeUnversioned?.let { params += "include_unversioned" to it.toString() }
            args.string("changed_files_mode")?.let { params += "changed_files_mode" to it }
            args.int("max_files")?.let { params += "max_files" to it.toString() }
            args.string("profile")?.let { params += "profile" to it }

            val url = buildUrl("$baseUrl/trigger", params)
            val result = httpGet(url)

            val text = prettyJson.encodeToString(JsonElement.serializer(), result) +
                "\n\nUse inspection_wait (preferred) or poll inspection_get_status before fetching problems."
            toolText(text)
        } catch (error: Exception) {
            toolError("Error triggering inspection: ${error.message}")
        }
    }

    private fun handleGetStatus(args: JsonObject): JsonObject {
        return try {
            val params = mutableListOf<Pair<String, String>>()
            args.string("project")?.let { params += "project" to it }

            val url = buildUrl("$baseUrl/status", params)
            val result = httpGet(url)

            val text = prettyJson.encodeToString(JsonElement.serializer(), result) + buildStatusGuidance(result)
            toolText(text)
        } catch (error: Exception) {
            toolError("Error getting status: ${error.message}")
        }
    }

    private fun handleWait(args: JsonObject): JsonObject {
        return try {
            val params = mutableListOf<Pair<String, String>>()
            args.string("project")?.let { params += "project" to it }
            val timeoutProvided = args["timeout_ms"] != null
            val pollProvided = args["poll_ms"] != null
            val timeoutMs = args.int("timeout_ms") ?: 180000
            val pollMs = args.int("poll_ms") ?: 1000
            if (timeoutProvided || timeoutMs != 180000) params += "timeout_ms" to timeoutMs.toString()
            if (pollProvided || pollMs != 1000) params += "poll_ms" to pollMs.toString()

            val url = buildUrl("$baseUrl/wait", params)
            val requestTimeoutSeconds = ((timeoutMs + 5000) / 1000).toLong().coerceAtLeast(15L)
            val result = httpGet(url, requestTimeoutSeconds)

            val text = prettyJson.encodeToString(JsonElement.serializer(), result) + buildWaitGuidance(result)
            toolText(text)
        } catch (error: Exception) {
            toolError("Error waiting for inspection: ${error.message}")
        }
    }

    private fun buildProblemsGuidance(result: JsonElement): String {
        val obj = result as? JsonObject ?: return ""
        val status = obj["status"]?.jsonPrimitive?.contentOrNull
        val total = obj["total_problems"]?.jsonPrimitive?.intOrNull
        val shown = obj["problems_shown"]?.jsonPrimitive?.intOrNull
        val pagination = obj["pagination"] as? JsonObject
        val hasMore = pagination?.get("has_more")?.jsonPrimitive?.booleanOrNull == true
        val nextOffset = pagination?.get("next_offset")?.jsonPrimitive?.intOrNull

        val guidance = when {
            status == "no_results" ->
                "\n\nWARN: No results found. Trigger an inspection first, or the codebase is clean."
            total == 0 ->
                "\n\nOK: No problems found matching filters."
            total != null ->
                "\n\nINFO: Found $total problems total, showing ${shown ?: total}."
            else -> ""
        }

        return if (hasMore && nextOffset != null) {
            "$guidance\n\nNEXT: More results available. Use offset=$nextOffset to get the next page."
        } else {
            guidance
        }
    }

    private fun buildStatusGuidance(result: JsonElement): String {
        val obj = result as? JsonObject ?: return ""
        val isScanning = obj["is_scanning"]?.jsonPrimitive?.booleanOrNull == true
        val clean = obj["clean_inspection"]?.jsonPrimitive?.booleanOrNull == true
        val hasResults = obj["has_inspection_results"]?.jsonPrimitive?.booleanOrNull == true

        val timeSince = obj["time_since_last_trigger_ms"]?.jsonPrimitive?.longOrNull

        return when {
            isScanning -> "\n\nSTATUS: Inspection still running - wait before getting problems."
            clean -> "\n\nSTATUS: Inspection complete - codebase is clean (no problems found)."
            hasResults -> "\n\nSTATUS: Inspection complete - problems found, ready to retrieve."
            timeSince != null && timeSince < 60000 ->
                "\n\nSTATUS: Inspection finished but no results were captured. This can happen for clean runs or when the Inspection Results view was unavailable. Re-run inspection or open the Inspection Results tool window."
            else -> "\n\nSTATUS: No recent inspection - trigger inspection first."
        }
    }

    private fun buildWaitGuidance(result: JsonElement): String {
        val obj = result as? JsonObject ?: return ""
        val completed = obj["wait_completed"]?.jsonPrimitive?.booleanOrNull == true
        val timedOut = obj["timed_out"]?.jsonPrimitive?.booleanOrNull == true
        val reason = obj["completion_reason"]?.jsonPrimitive?.contentOrNull

        return when {
            completed && reason == "clean" -> "\n\nSTATUS: Inspection complete - codebase is clean."
            completed && reason == "results" -> "\n\nSTATUS: Inspection complete - problems found."
            completed && reason == "no_results" ->
                "\n\nSTATUS: Inspection finished but no results were captured. Re-run inspection or open the Inspection Results tool window."
            reason == "no_project" -> "\n\nSTATUS: No project found yet - ensure the IDE has an open project."
            reason == "interrupted" -> "\n\nSTATUS: Wait interrupted - try again."
            timedOut -> "\n\nSTATUS: Wait timed out - try inspection_get_status or increase timeout_ms."
            else -> ""
        }
    }

    private fun toolText(text: String, isError: Boolean = false): JsonObject {
        return buildJsonObject {
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(text))
                        }
                    )
                )
            )
            if (isError) {
                put("isError", JsonPrimitive(true))
            }
        }
    }

    private fun toolError(message: String): JsonObject {
        return toolText(message, isError = true)
    }

    private fun httpGet(url: String, timeoutSeconds: Long = 10): JsonElement {
        val request = HttpRequest.newBuilder(URI(url))
            .GET()
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            val body = response.body()
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_ACCEPTED) {
                throw RuntimeException("Unexpected HTTP status $status")
            }
            return try {
                json.parseToJsonElement(body)
            } catch (parseError: Exception) {
                throw RuntimeException("Invalid JSON response: ${parseError.message}")
            }
        } catch (error: HttpTimeoutException) {
            throw RuntimeException("Request timeout | Ensure IDE on port $idePort is reachable")
        } catch (error: Exception) {
            if (error is RuntimeException && error.message?.startsWith("Invalid JSON response") == true) {
                throw error
            }
            val hint = if (isConnectionRefused(error)) {
                "Ensure JetBrains IDE is running, plugin installed, and built-in server enabled on port $idePort (Allow unsigned requests)."
            } else {
                ""
            }
            val msg = buildString {
                append("HTTP request failed: ")
                append(error.message ?: "unknown error")
                if (hint.isNotEmpty()) {
                    append(" | ")
                    append(hint)
                }
            }
            throw RuntimeException(msg)
        }
    }

    private fun buildUrl(base: String, params: List<Pair<String, String>>): String {
        if (params.isEmpty()) return base
        val query = params.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "$base?$query"
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    private fun isConnectionRefused(error: Throwable): Boolean {
        return error is ConnectException || error.cause is ConnectException ||
            (error.message?.contains("Connection refused", ignoreCase = true) == true)
    }

    private fun getProblemsSchema(): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put(
                    "project",
                    stringProp("Project name (optional; defaults to focused project)")
                )
                put(
                    "scope",
                    stringProp(
                        "Scope: whole_project | current_file | <path substring>",
                        defaultValue = "whole_project"
                    )
                )
                put(
                    "severity",
                    stringProp(
                        "error | warning | weak_warning | info | grammar | typo | all",
                        defaultValue = "all"
                    )
                )
                put(
                    "problem_type",
                    stringProp("Filter by inspection type/category")
                )
                put(
                    "file_pattern",
                    stringProp("Path filter (string or regex)")
                )
                put(
                    "limit",
                    intProp("Max problems to return", defaultValue = 100)
                )
                put(
                    "offset",
                    intProp("Pagination offset", defaultValue = 0)
                )
            })
        }
    }

    private fun triggerSchema(): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("project", stringProp("Project name (optional)"))
                put(
                    "scope",
                    enumProp(
                        "Scope (directory->dir, files->files)",
                        listOf("whole_project", "current_file", "directory", "changed_files", "files")
                    )
                )
                put("dir", stringProp("Directory for scope=directory (required)"))
                put("directory", stringProp("Alias for dir"))
                put("path", stringProp("Alias for dir"))
                put("files", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("File paths for scope=files (required)"))
                    put("items", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                    })
                })
                put("include_unversioned", buildJsonObject {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("changed_files only; default true"))
                })
                put(
                    "changed_files_mode",
                    enumProp("changed_files only: all | staged | unstaged", listOf("all", "staged", "unstaged"))
                )
                put("max_files", intProp("Max files (changed_files only)"))
                put("profile", stringProp("Inspection profile name"))
            })
        }
    }

    private fun statusSchema(): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put(
                    "project",
                    stringProp("Project name (optional; defaults to focused project)")
                )
            })
        }
    }

    private fun waitSchema(): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put(
                    "project",
                    stringProp("Project name (optional)")
                )
                put(
                    "timeout_ms",
                    intProp("Max wait ms", defaultValue = 180000)
                )
                put(
                    "poll_ms",
                    intProp("Poll interval ms", defaultValue = 1000)
                )
            })
        }
    }

    private fun stringProp(description: String, defaultValue: String? = null): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive(description))
            if (defaultValue != null) {
                put("default", JsonPrimitive(defaultValue))
            }
        }
    }

    private fun enumProp(description: String, values: List<String>): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive(description))
            put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        }
    }

    private fun intProp(description: String, defaultValue: Int? = null): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("integer"))
            put("description", JsonPrimitive(description))
            if (defaultValue != null) {
                put("default", JsonPrimitive(defaultValue))
            }
        }
    }

}

private fun JsonObject.string(name: String): String? {
    return (this[name] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.int(name: String): Int? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.doubleOrNull?.toInt()
}
