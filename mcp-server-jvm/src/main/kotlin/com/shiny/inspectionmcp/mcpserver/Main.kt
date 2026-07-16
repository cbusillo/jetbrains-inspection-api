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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
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
import java.util.UUID

private const val SERVER_NAME = "jetbrains-inspection-mcp"
private const val DEFAULT_PROTOCOL_VERSION = "2024-11-05"
private const val DEFAULT_WAIT_TIMEOUT_MS = 180_000
private const val MIN_WAIT_TIMEOUT_MS = 1_000
private const val MAX_WAIT_TIMEOUT_MS = 300_000

private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    explicitNulls = false
}

private data class PinnedInspectionTarget(
    val projectKey: String,
    val sessionId: String,
)

private object VersionAnchor

fun main() {
    val fixedIdePort = System.getenv("IDE_PORT")?.takeIf { it.isNotBlank() }
    val toolExecutor = if (fixedIdePort != null) {
        ToolExecutor("http://localhost:$fixedIdePort/api/inspection", buildHttpClient(), fixedIdePort)
    } else {
        ToolExecutor.auto()
    }

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

private fun buildHttpClient(): HttpClient {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
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
    private var httpClient: HttpClient,
    private val idePort: String,
    private val targetResolver: InspectionTargetResolver = FixedTargetResolver(baseUrl, idePort),
    private val httpClientFactory: () -> HttpClient = ::buildHttpClient,
) {
    private val pinnedSessionsByProject = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var latestTriggeredTarget: PinnedInspectionTarget? = null

    companion object {
        fun auto(httpClientFactory: () -> HttpClient = ::buildHttpClient): ToolExecutor {
            return ToolExecutor(
                baseUrl = "http://localhost:0/api/inspection",
                httpClient = httpClientFactory(),
                idePort = "auto",
                targetResolver = AutoTargetResolver(httpClientFactory),
                httpClientFactory = httpClientFactory,
            )
        }
    }

    fun toolList(): JsonArray {
        return JsonArray(
            listOf(
                buildJsonObject {
                    put("name", JsonPrimitive("inspection_list_projects"))
                    put(
                        "description",
                        JsonPrimitive("List live JetBrains IDE projects discovered by the MCP router, including project keys and paths for disambiguation.")
                    )
                    put("inputSchema", emptySchema())
                },
                buildJsonObject {
                    put("name", JsonPrimitive("inspection_get_problems"))
                    put(
                        "description",
                        JsonPrimitive(
                            "Fetch problems after inspection completes. Typical flow: inspection_trigger -> inspection_wait -> inspection_get_problems. In auto mode, pass project_path or project_key when available; selector-less calls follow the last triggered project when possible. capture_incomplete means do not treat as clean; retry once, preferably with a narrower scope. stale_results withholds cached findings by default; pass include_stale only when explicitly diagnosing cached data. snapshot_change_kind explains whether stale data predates the trigger or fresh results saw reconciled IDE PSI churn."
                        )
                    )
                    put("inputSchema", getProblemsSchema())
                },
                buildJsonObject {
                    put("name", JsonPrimitive("inspection_trigger"))
                    put(
                        "description",
                        JsonPrimitive("Start an inspection run (async). Typical flow: call inspection_wait next, then inspection_get_problems. In auto mode, prefer project_path or project_key so the router can select the intended IDE project. Use targeted scopes such as files, directory, changed_files, or current_file when possible.")
                    )
                    put("inputSchema", triggerSchema())
                },
                buildJsonObject {
                    put("name", JsonPrimitive("inspection_get_status"))
                    put(
                        "description",
                        JsonPrimitive(
                            "Check inspection status. In auto mode, pass project_path or project_key when available; selector-less calls follow the last triggered project when possible. If status says no recent inspection, trigger first. If you expected findings but see no_results, retry once with a narrower scope."
                        )
                    )
                    put("inputSchema", statusSchema())
                },
                buildJsonObject {
                    put("name", JsonPrimitive("inspection_wait"))
                    put(
                        "description",
                        JsonPrimitive(
                            "Block until inspection completes or timeout. Preferred after inspection_trigger; call inspection_get_problems after results or clean. In auto mode, pass project_path or project_key when available. capture_incomplete means do not treat as clean; retry once, preferably with a narrower scope."
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
            "inspection_list_projects" -> handleListProjects()
            else -> toolError("Unknown tool: $name")
        }
    }

    private fun handleListProjects(): JsonObject {
        return try {
            toolText(prettyJson.encodeToString(JsonElement.serializer(), targetResolver.listProjects()))
        } catch (error: Exception) {
            toolError("Error listing JetBrains projects: ${error.message}")
        }
    }

    private fun handleGetProblems(args: JsonObject): JsonObject {
        return try {
            val params = mutableListOf<Pair<String, String>>()
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
            if (args["include_stale"]?.jsonPrimitive?.booleanOrNull == true) {
                params += "include_stale" to "true"
            }

            val (result, target) = routeAndGet(autoPinnedArgs(args), "problems", params)
            ensurePinnedSessionStillValid("inspection_get_problems", target, result)

            val guidance = buildProblemsGuidance(result)
            val text = renderInspectionResult(result, guidance, buildRouteGuidance(target))
            toolText(text)
        } catch (error: Exception) {
            toolError("Error getting problems: ${error.message}")
        }
    }

    private fun handleTrigger(args: JsonObject): JsonObject {
        return try {
            val params = mutableListOf<Pair<String, String>>()
            val scope = args.string("scope")
            val normalizedScope = scope?.trim()?.lowercase()
            scope?.let { params += "scope" to it }
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
            args.int("max_files")
                ?.takeIf { normalizedScope == "changed_files" && it > 0 }
                ?.let { params += "max_files" to it.toString() }
            args.string("profile")?.let { params += "profile" to it }

            val (result, target) = routeAndGet(autoPinnedArgs(args), "trigger", params)
            pinTriggeredSession(target, result)

            val text = prettyJson.encodeToString(JsonElement.serializer(), result) +
                "\n\nUse inspection_wait (preferred) or poll inspection_get_status before fetching problems." +
                buildRouteGuidance(target)
            toolText(text)
        } catch (error: Exception) {
            toolError("Error triggering inspection: ${error.message}")
        }
    }

    private fun handleGetStatus(args: JsonObject): JsonObject {
        return try {
            val params = mutableListOf<Pair<String, String>>()

            val (result, target) = routeAndGet(autoPinnedArgs(args), "status", params)
            ensurePinnedSessionStillValid("inspection_get_status", target, result)

            val guidance = buildStatusGuidance(result)
            val text = renderInspectionResult(result, guidance, buildRouteGuidance(target))
            toolText(text)
        } catch (error: Exception) {
            toolError("Error getting status: ${error.message}")
        }
    }

    private fun handleWait(args: JsonObject): JsonObject {
        return try {
            val params = mutableListOf<Pair<String, String>>()
            val timeoutProvided = args["timeout_ms"] != null
            val pollProvided = args["poll_ms"] != null
            val timeoutMs = (args.int("timeout_ms") ?: DEFAULT_WAIT_TIMEOUT_MS)
                .coerceIn(MIN_WAIT_TIMEOUT_MS, MAX_WAIT_TIMEOUT_MS)
            val pollMs = args.int("poll_ms") ?: 1000
            if (timeoutProvided || timeoutMs != DEFAULT_WAIT_TIMEOUT_MS) params += "timeout_ms" to timeoutMs.toString()
            if (pollProvided || pollMs != 1000) params += "poll_ms" to pollMs.toString()

            val requestTimeoutSeconds = ((timeoutMs + 5000) / 1000).toLong().coerceAtLeast(15L)
            val (result, target) = routeAndGet(autoPinnedArgs(args), "wait", params, requestTimeoutSeconds)
            ensurePinnedSessionStillValid("inspection_wait", target, result)

            val guidance = buildWaitGuidance(result)
            val text = renderInspectionResult(result, guidance, buildRouteGuidance(target))
            toolText(text)
        } catch (error: Exception) {
            toolError("Error waiting for inspection: ${error.message}")
        }
    }

    private fun routeAndGet(
        args: JsonObject,
        endpoint: String,
        params: MutableList<Pair<String, String>>,
        timeoutSeconds: Long = 10,
    ): Pair<JsonElement, InspectionTarget> {
        var target = targetResolver.resolve(args)
        val clientRunId = UUID.randomUUID().toString()
        val requestParams = params.withRoutingSelector(args, target) + ("client_run_id" to clientRunId)
        try {
            return httpGet(buildUrl("${target.baseUrl}/$endpoint", requestParams), timeoutSeconds, target.idePort) to target
        } catch (error: RuntimeException) {
            if (!targetResolver.autoRouting || !isRetryableRouteFailure(error)) {
                throw error
            }
            val excludedSessionIds = target.identity?.get("session_id")?.jsonPrimitive?.contentOrNull?.let(::setOf)
                ?: emptySet()
            target = targetResolver.resolve(args, excludedSessionIds = excludedSessionIds)
            val retryParams = params.withRoutingSelector(args, target) + ("client_run_id" to clientRunId)
            return httpGet(buildUrl("${target.baseUrl}/$endpoint", retryParams), timeoutSeconds, target.idePort) to target
        }
    }

    private fun List<Pair<String, String>>.withRoutingSelector(args: JsonObject, target: InspectionTarget): List<Pair<String, String>> {
        val routedParams = args.string("session_id")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { sessionId -> this + ("session_id" to sessionId) }
            ?: this
        val routedProjectKey = target.project?.get("project_key")?.jsonPrimitive?.contentOrNull
        if (!routedProjectKey.isNullOrBlank()) {
            return routedParams + ("project_key" to routedProjectKey)
        }
        args.routingSelector()?.let { (name, value) -> return routedParams + (name to value) }
        return routedParams
    }

    private fun pinTriggeredSession(target: InspectionTarget, result: JsonElement) {
        val resultObject = result as? JsonObject
        val projectKey = target.project?.get("project_key")?.jsonPrimitive?.contentOrNull
            ?: resultObject?.get("project_key")?.jsonPrimitive?.contentOrNull
            ?: return
        val sessionId = target.identity?.get("session_id")?.jsonPrimitive?.contentOrNull
            ?: resultObject?.get("session_id")?.jsonPrimitive?.contentOrNull
            ?: return
        pinnedSessionsByProject[projectKey] = sessionId
        latestTriggeredTarget = PinnedInspectionTarget(projectKey, sessionId)
    }

    private fun ensurePinnedSessionStillValid(toolName: String, target: InspectionTarget, result: JsonElement) {
        val resultObject = result as? JsonObject
        val projectKey = target.project?.get("project_key")?.jsonPrimitive?.contentOrNull
            ?: resultObject?.get("project_key")?.jsonPrimitive?.contentOrNull
            ?: return
        val sessionId = target.identity?.get("session_id")?.jsonPrimitive?.contentOrNull
            ?: resultObject?.get("session_id")?.jsonPrimitive?.contentOrNull
            ?: return
        val pinnedSessionId = pinnedSessionsByProject[projectKey] ?: return
        if (pinnedSessionId != sessionId) {
            pinnedSessionsByProject.remove(projectKey)
            if (latestTriggeredTarget?.projectKey == projectKey) {
                latestTriggeredTarget = null
            }
            throw RuntimeException(
                "The JetBrains IDE session for project $projectKey restarted before $toolName completed. Re-trigger inspection for this project."
            )
        }
    }

    private fun autoPinnedArgs(args: JsonObject): JsonObject {
        if (args.routingSelector() != null) {
            return args
        }
        val pinnedTarget = latestTriggeredTarget ?: return args
        return buildJsonObject {
            args.forEach { (key, value) -> put(key, value) }
            put("project_key", JsonPrimitive(pinnedTarget.projectKey))
            put("session_id", JsonPrimitive(pinnedTarget.sessionId))
        }
    }

    private fun buildRouteGuidance(target: InspectionTarget): String {
        if (!targetResolver.autoRouting) return ""
        val project = target.project ?: return ""
        val name = project["name"]?.jsonPrimitive?.contentOrNull ?: return ""
        val projectKey = project["project_key"]?.jsonPrimitive?.contentOrNull
        val ideName = target.identity?.get("ide_name")?.jsonPrimitive?.contentOrNull ?: "JetBrains IDE"
        return "\n\nROUTE: $ideName on port ${target.idePort}, project=$name" +
            if (projectKey.isNullOrBlank()) "" else ", project_key=$projectKey"
    }

    private fun isRetryableRouteFailure(error: Throwable): Boolean {
        val message = error.message ?: return false
        return listOf(
            "connection refused",
            "request timeout",
            "timed out",
            "connection reset",
            "broken pipe",
            "eof",
        ).any { token -> message.contains(token, ignoreCase = true) }
    }

    private fun buildProblemsGuidance(result: JsonElement): String {
        val obj = result as? JsonObject ?: return ""
        val status = obj["status"]?.jsonPrimitive?.contentOrNull
        val captureReason = obj["capture_incomplete_reason"]?.jsonPrimitive?.contentOrNull
        val total = obj["total_problems"]?.jsonPrimitive?.intOrNull
        val shown = obj["problems_shown"]?.jsonPrimitive?.intOrNull
        val pagination = obj["pagination"] as? JsonObject
        val hasMore = pagination?.get("has_more")?.jsonPrimitive?.booleanOrNull == true
        val nextOffset = pagination?.get("next_offset")?.jsonPrimitive?.intOrNull

        val guidance = inspectionVerdictGuidance(
            obj,
            when {
                status == "stale_results" -> McpInspectionVerdict(
                    "UNKNOWN",
                    "stale_results",
                    "Cached inspection results are stale because the project changed after the last run.",
                    "Trigger inspection again before trusting cached results. Pass include_stale=true only for explicit cached-result diagnostics.",
                )
                status == "capture_incomplete" -> unknownCaptureVerdict(captureReason)
                status == "no_results" -> McpInspectionVerdict(
                    "UNKNOWN",
                    "no_results",
                    "No trustworthy inspection result was captured.",
                    "Trigger and wait for a fresh inspection, or open the Inspection Results view for the exact worktree before treating the result as clean.",
                )
                total != null && total > 0 -> McpInspectionVerdict(
                    "RED",
                    "actionable_findings",
                    "Inspection worked and returned actionable findings. Found $total problems total, showing ${shown ?: total}.",
                    "Fix the reported findings, then rerun inspection.",
                )
                total == 0 -> McpInspectionVerdict(
                    "GREEN",
                    "no_matching_findings",
                    "Inspection worked and found no actionable findings for the selected scope/filter.",
                    "No inspection action required for this scope/filter.",
                )
                else -> null
            },
        ) ?: when {
            status == "stale_results" ->
                "\n\nWARN: Cached inspection results are stale because the project changed after the last run. Trigger a new inspection before trusting these findings. Pass include_stale=true only for explicit cached-result diagnostics."
            status == "capture_incomplete" ->
                captureIncompleteGuidance("WARN", captureReason)
            status == "no_results" ->
                "\n\nWARN: No trustworthy inspection result was captured. Trigger and wait for a fresh inspection before treating this as clean."
            total == 0 ->
                "\n\nVERDICT: GREEN reason=no_matching_findings\nMESSAGE: Inspection worked and found no actionable findings for the selected scope/filter."
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
        val stale = obj["results_may_be_stale"]?.jsonPrimitive?.booleanOrNull == true
        val captureIncomplete = obj["capture_incomplete"]?.jsonPrimitive?.booleanOrNull == true
        val captureReason = obj["capture_incomplete_reason"]?.jsonPrimitive?.contentOrNull

        val timeSince = obj["time_since_last_trigger_ms"]?.jsonPrimitive?.longOrNull

        return inspectionVerdictGuidance(
            obj,
            when {
                isScanning -> McpInspectionVerdict(
                    "UNKNOWN",
                    "inspection_still_running",
                    "Inspection is still running.",
                    "Wait before getting problems.",
                )
                stale -> McpInspectionVerdict(
                    "UNKNOWN",
                    "stale_results",
                    "Project changed after the last inspection.",
                    "Trigger inspection again before trusting cached results.",
                )
                captureIncomplete -> unknownCaptureVerdict(captureReason)
                clean -> McpInspectionVerdict(
                    "GREEN",
                    "clean_confirmed",
                    "Inspection complete and no actionable findings were found.",
                    "No inspection action required for this scope/filter.",
                )
                hasResults -> McpInspectionVerdict(
                    "UNKNOWN",
                    "results_available_without_findings",
                    "Inspection status says results are available, but this status response did not include actionable findings.",
                    "Call inspection_get_problems and use its verdict before reporting GREEN or RED.",
                )
                timeSince != null && timeSince < 60000 -> McpInspectionVerdict(
                    "UNKNOWN",
                    "no_results",
                    "Inspection finished but no trustworthy result was captured yet.",
                    "Retry once, preferably with a narrower scope, before treating this as clean.",
                )
                else -> McpInspectionVerdict(
                    "UNKNOWN",
                    "no_recent_inspection",
                    "No recent inspection result is available.",
                    "Trigger inspection first.",
                )
            },
        ) ?: when {
            isScanning -> "\n\nSTATUS: Inspection still running - wait before getting problems."
            stale -> "\n\nSTATUS: Project changed after the last inspection - trigger inspection again before trusting cached results."
            captureIncomplete -> captureIncompleteGuidance("STATUS", captureReason)
            clean -> "\n\nSTATUS: Inspection complete - codebase is clean."
            hasResults -> "\n\nSTATUS: Inspection complete - problems found, ready to retrieve."
            timeSince != null && timeSince < 60000 ->
                "\n\nSTATUS: Inspection finished but no results were captured yet. Retry once, preferably with a narrower scope, before treating this as clean."
            else -> "\n\nSTATUS: No recent inspection - trigger inspection first."
        }
    }

    private fun buildWaitGuidance(result: JsonElement): String {
        val obj = result as? JsonObject ?: return ""
        val completed = obj["wait_completed"]?.jsonPrimitive?.booleanOrNull == true
        val timedOut = obj["timed_out"]?.jsonPrimitive?.booleanOrNull == true
        val reason = obj["completion_reason"]?.jsonPrimitive?.contentOrNull
        val captureReason = obj["capture_incomplete_reason"]?.jsonPrimitive?.contentOrNull

        return inspectionVerdictGuidance(
            obj,
            when {
                completed && reason == "clean" -> McpInspectionVerdict(
                    "GREEN",
                    "clean_confirmed",
                    "Inspection complete and no actionable findings were found.",
                    "No inspection action required for this scope/filter.",
                )
                completed && reason == "results" -> McpInspectionVerdict(
                    "RED",
                    "actionable_findings",
                    "Inspection complete and problems are ready to retrieve.",
                    "Call inspection_get_problems, fix the reported findings, then rerun inspection.",
                )
                completed && reason == "capture_incomplete" -> unknownCaptureVerdict(captureReason)
                completed && reason == "stale_results" -> McpInspectionVerdict(
                    "UNKNOWN",
                    "stale_results",
                    "Cached inspection results are stale.",
                    "Trigger inspection again before trusting findings.",
                )
                completed && reason == "no_results" -> McpInspectionVerdict(
                    "UNKNOWN",
                    "no_results",
                    "Inspection finished with no trustworthy captured result.",
                    "Retry once with a narrower scope before treating this as clean.",
                )
                reason == "no_recent_inspection" -> McpInspectionVerdict(
                    "UNKNOWN",
                    "no_recent_inspection",
                    "No recent inspection result is available.",
                    "Trigger inspection first.",
                )
                reason == "no_project" -> McpInspectionVerdict(
                    "UNKNOWN",
                    "no_project",
                    "No project was found.",
                    "Ensure the IDE has an open project, or pass the exact project name.",
                )
                reason == "interrupted" -> McpInspectionVerdict(
                    "UNKNOWN",
                    "interrupted",
                    "Inspection wait was interrupted.",
                    "Try again.",
                )
                timedOut -> McpInspectionVerdict(
                    "UNKNOWN",
                    "timeout",
                    "Inspection wait timed out.",
                    "Try inspection_get_status or increase timeout_ms.",
                )
                else -> null
            },
        ) ?: when {
            completed && reason == "clean" -> "\n\nSTATUS: Inspection complete - codebase is clean."
            completed && reason == "results" -> "\n\nSTATUS: Inspection complete - problems found."
            completed && reason == "capture_incomplete" ->
                captureIncompleteGuidance("STATUS", captureReason)
            completed && reason == "stale_results" ->
                "\n\nSTATUS: Cached inspection results are stale - trigger inspection again before trusting findings."
            completed && reason == "no_results" ->
                "\n\nSTATUS: Inspection finished with no trustworthy captured result. Retry once with a narrower scope before treating this as clean."
            reason == "no_recent_inspection" -> "\n\nSTATUS: No recent inspection - trigger inspection first."
            reason == "no_project" -> "\n\nSTATUS: No project found - ensure the IDE has an open project, or pass the exact project name."
            reason == "interrupted" -> "\n\nSTATUS: Wait interrupted - try again."
            timedOut -> "\n\nSTATUS: Wait timed out - try inspection_get_status or increase timeout_ms."
            else -> ""
        }
    }

    private fun captureIncompleteGuidance(prefix: String, reason: String?): String {
        val reasonText = if (reason.isNullOrBlank()) "" else " reason=$reason."
        return "\n\n$prefix: capture_incomplete$reasonText do not treat as clean. Retry once, preferably with a narrower scope; if it repeats, report the reason and capture_diagnostic."
    }

    private fun renderInspectionResult(result: JsonElement, guidance: String, routeGuidance: String): String {
        val payload = sanitizeInspectionPayloadForAgent(result)
        val jsonText = prettyJson.encodeToString(JsonElement.serializer(), payload)
        val leadingGuidance = guidance.trim()
        val leading = if (leadingGuidance.isEmpty()) "" else "$leadingGuidance\n\n"
        return leading + jsonText + routeGuidance
    }

    private fun sanitizeInspectionPayloadForAgent(result: JsonElement): JsonElement {
        val obj = result as? JsonObject ?: return result
        val verdict = blockerVerdict(obj)?.verdict
            ?: obj["inspection_verdict"]?.jsonPrimitive?.contentOrNull
        val keep = obj.toMutableMap()
        keep.remove("capture_diagnostic")
        val includeStale = obj["include_stale"]?.jsonPrimitive?.booleanOrNull == true
        if (verdict == "UNKNOWN" && !includeStale) {
            keep.remove("total_problems")
            keep.remove("problems_shown")
            keep.remove("problems")
            keep.remove("pagination")
            keep.remove("inspection_verdict")
            keep.remove("inspection_verdict_reason")
            keep.remove("inspection_verdict_message")
            keep.remove("inspection_verdict_next_action")
        }
        return JsonObject(keep)
    }

    private data class McpInspectionVerdict(
        val verdict: String,
        val reason: String,
        val message: String,
        val nextAction: String,
    )

    private fun inspectionVerdictGuidance(obj: JsonObject, fallback: McpInspectionVerdict?): String? {
        val blockerVerdict = blockerVerdict(obj)
        val pluginVerdict = obj["inspection_verdict"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it == "GREEN" || it == "RED" || it == "UNKNOWN" }
            ?.let { verdict ->
                McpInspectionVerdict(
                    verdict = verdict,
                    reason = obj["inspection_verdict_reason"]?.jsonPrimitive?.contentOrNull ?: "plugin_verdict",
                    message = obj["inspection_verdict_message"]?.jsonPrimitive?.contentOrNull
                        ?: "Inspection plugin provided the verdict.",
                    nextAction = obj["inspection_verdict_next_action"]?.jsonPrimitive?.contentOrNull
                        ?: "Follow the inspection plugin verdict.",
                )
            }

        val verdict = blockerVerdict ?: pluginVerdict ?: fallback ?: return null
        return "\n\nVERDICT: ${verdict.verdict} reason=${verdict.reason}" +
            "\nMESSAGE: ${verdict.message}" +
            "\nNEXT_ACTION: ${verdict.nextAction}"
    }

    private fun blockerVerdict(obj: JsonObject): McpInspectionVerdict? {
        val status = obj["status"]?.jsonPrimitive?.contentOrNull
        val completionReason = obj["completion_reason"]?.jsonPrimitive?.contentOrNull
        val captureReason = obj["capture_incomplete_reason"]?.jsonPrimitive?.contentOrNull
        return when {
            obj["session_drift"]?.jsonPrimitive?.booleanOrNull == true -> McpInspectionVerdict(
                "UNKNOWN",
                "session_drift",
                "The IDE/plugin session changed before inspection could be trusted.",
                "Resolve the route again and rerun inspection.",
            )
            obj["ambiguous"]?.jsonPrimitive?.booleanOrNull == true -> McpInspectionVerdict(
                "UNKNOWN",
                "ambiguous_route",
                "The inspection route is ambiguous.",
                "Pass project_key, project_path, or worktree_path so the exact project is inspected.",
            )
            obj["unavailable"]?.jsonPrimitive?.booleanOrNull == true -> McpInspectionVerdict(
                "UNKNOWN",
                "inspection_api_unavailable",
                "The inspection API is unavailable.",
                "Open the exact worktree in the configured JetBrains IDE with the inspection plugin installed.",
            )
            obj["results_may_be_stale"]?.jsonPrimitive?.booleanOrNull == true || status == "stale_results" || completionReason == "stale_results" ->
                McpInspectionVerdict(
                    "UNKNOWN",
                    "stale_results",
                    "Cached inspection results are stale because the project changed after the last run.",
                    "Trigger inspection again before trusting cached results. Pass include_stale=true only for explicit cached-result diagnostics.",
                )
            obj["capture_incomplete"]?.jsonPrimitive?.booleanOrNull == true || status == "capture_incomplete" || completionReason == "capture_incomplete" ->
                unknownCaptureVerdict(captureReason)
            obj["timed_out"]?.jsonPrimitive?.booleanOrNull == true || completionReason == "timeout" -> McpInspectionVerdict(
                "UNKNOWN",
                "timeout",
                "Inspection wait timed out.",
                "Try inspection_get_status or increase timeout_ms.",
            )
            obj["indexing"]?.jsonPrimitive?.booleanOrNull == true ||
                obj["is_scanning"]?.jsonPrimitive?.booleanOrNull == true ||
                obj["inspection_in_progress"]?.jsonPrimitive?.booleanOrNull == true ||
                status == "indexing" ||
                status == "running" -> McpInspectionVerdict(
                    "UNKNOWN",
                    "inspection_still_running",
                    "Inspection is still running.",
                    "Wait before getting problems.",
                )
            status == "no_results" || completionReason == "no_results" -> McpInspectionVerdict(
                "UNKNOWN",
                "no_results",
                "Inspection finished with no trustworthy captured result.",
                "Retry once with a narrower scope before treating this as clean.",
            )
            status == "no_project" || completionReason == "no_project" -> McpInspectionVerdict(
                "UNKNOWN",
                "no_project",
                "No project was found.",
                "Ensure the IDE has an open project, or pass the exact project name.",
            )
            status == "no_recent_inspection" || completionReason == "no_recent_inspection" -> McpInspectionVerdict(
                "UNKNOWN",
                "no_recent_inspection",
                "No recent inspection result is available.",
                "Trigger inspection first.",
            )
            completionReason == "interrupted" -> McpInspectionVerdict(
                "UNKNOWN",
                "interrupted",
                "Inspection wait was interrupted.",
                "Try again.",
            )
            proofFailures(obj).isNotEmpty() -> {
                val proofFailures = proofFailures(obj)
                McpInspectionVerdict(
                    "UNKNOWN",
                    "inspection_proof_failed",
                    "Inspection returned contradictory proof and did not establish a trustworthy GREEN or RED result.",
                    "Resolve the proof failure (${proofFailures.joinToString(", ")}), then trigger and wait for a fresh inspection before reporting GREEN or RED.",
                )
            }
            else -> null
        }
    }

    private fun proofFailures(obj: JsonObject): List<String> {
        val topLevel = (obj["proof_failures"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        val nested = ((obj["inspection_proof"] as? JsonObject)?.get("proof_failures") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        return (topLevel + nested).distinct()
    }

    private fun unknownCaptureVerdict(reason: String?): McpInspectionVerdict {
        val safeReason = reason?.takeIf { it.isNotBlank() } ?: "capture_incomplete"
        val nextAction = when (safeReason) {
            "non_empty_unmapped_tree", "extractor_failure", "helper_plugin_error" ->
                "Treat this as a plugin/helper bug: capture the diagnostic payload, update the inspection plugin or helper skill, and rerun."
            "view_not_ready", "view_updating_unreadable", "unreadable_tree", "no_results" ->
                "Open the IDE Inspection Results or Problems view for the exact worktree, then rerun inspection."
            "current_run_psi_churn" ->
                "Save documents and rerun inspection after the IDE finishes updating PSI state."
            "timeout" ->
                "Wait for indexing/scanning to settle or rerun with a larger timeout."
            "profile_resolution_error" ->
                "Verify the requested inspection profile exists and is loaded in the target project, then rerun inspection."
            else ->
                "Retry once, preferably with a narrower scope; if it repeats, report the reason and capture_diagnostic."
        }
        return McpInspectionVerdict(
            "UNKNOWN",
            safeReason,
            "Inspection finished, but the plugin could not conclusively capture IDE results.",
            nextAction,
        )
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

    private fun httpGet(url: String, timeoutSeconds: Long = 10, targetPort: String = idePort): JsonElement {
        val request = HttpRequest.newBuilder(URI(url))
            .GET()
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build()

        return executeRequest(request, retryOnTransportFailure = true, targetPort = targetPort)
    }

    private fun executeRequest(
        request: HttpRequest,
        retryOnTransportFailure: Boolean,
        targetPort: String,
    ): JsonElement {
        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            val body = response.body()
            if (status == HttpURLConnection.HTTP_CONFLICT) {
                val conflict = runCatching { json.parseToJsonElement(body) }.getOrNull()
                val conflictStatus = (conflict as? JsonObject)
                    ?.get("status")
                    ?.jsonPrimitive
                    ?.contentOrNull
                if (conflictStatus == "inspection_in_progress") {
                    return conflict
                }
            }
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_ACCEPTED) {
                val responseSummary = summarizeErrorBody(body)
                val detailSuffix = if (responseSummary != null) {
                    ": $responseSummary"
                } else {
                    ""
                }
                throw RuntimeException("Unexpected HTTP status $status$detailSuffix")
            }
            return try {
                json.parseToJsonElement(body)
            } catch (parseError: Exception) {
                throw RuntimeException("Invalid JSON response: ${parseError.message}")
            }
        } catch (error: HttpTimeoutException) {
            if (retryOnTransportFailure) {
                refreshHttpClient()
                return executeRequest(request, retryOnTransportFailure = false, targetPort = targetPort)
            }
            throw RuntimeException("Request timeout | Ensure IDE on port $targetPort is reachable")
        } catch (error: Exception) {
            if (error is RuntimeException && error.message?.startsWith("Invalid JSON response") == true) {
                throw error
            }
            if (retryOnTransportFailure && isRetryableTransportFailure(error)) {
                refreshHttpClient()
                return executeRequest(request, retryOnTransportFailure = false, targetPort = targetPort)
            }
            val hint = if (isConnectionRefused(error)) {
                "Ensure JetBrains IDE is running, plugin installed, and built-in server enabled on port $targetPort (Allow unsigned requests)."
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

    private fun refreshHttpClient() {
        httpClient = httpClientFactory()
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

    private fun isRetryableTransportFailure(error: Throwable): Boolean {
        if (error is InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        if (error is ConnectException || error is IOException) {
            return true
        }
        val message = error.message ?: return false
        return listOf(
            "connection refused",
            "connection reset",
            "broken pipe",
            "eof",
            "timed out"
        ).any { token -> message.contains(token, ignoreCase = true) }
    }

    private fun getProblemsSchema(): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                routingProperties()
                put(
                    "project",
                    stringProp("Project name. In auto mode, prefer project_path or project_key; blank/omitted falls back to the last triggered project, cwd, or focused project when unambiguous.")
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
                    stringProp("Path filter (literal string, glob, or regex)")
                )
                put(
                    "limit",
                    intProp("Max problems to return", defaultValue = 100, minimum = 1, maximum = 1000)
                )
                put(
                    "offset",
                    intProp("Pagination offset", defaultValue = 0, minimum = 0)
                )
                put("include_stale", buildJsonObject {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("Return cached stale findings for diagnostics. Default false; stale findings must not be treated as current."))
                })
            })
        }
    }

    private fun triggerSchema(): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                routingProperties()
                put(
                    "project",
                    stringProp("Project name. In auto mode, prefer project_path or project_key; blank/omitted follows the last triggered project, then cwd or the focused project when unambiguous.")
                )
                put(
                    "scope",
                    enumProp(
                        "Scope. Prefer files, directory, changed_files, or current_file for fast targeted inspections; use whole_project when you need full coverage. After triggering, call inspection_wait before fetching problems.",
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
                put("max_files", intProp("Positive cap for scope=changed_files only; ignored for other scopes", minimum = 1))
                put(
                    "profile",
                    stringProp(
                        "Exact inspection profile name. Omit to use the project's current profile; after the asynchronous trigger, a missing or unverifiable profile is reported as UNKNOWN with reason=profile_resolution_error by status, wait, or problems."
                    )
                )
            })
        }
    }

    private fun statusSchema(): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                routingProperties()
                put(
                    "project",
                    stringProp("Project name. In auto mode, prefer project_path or project_key; blank/omitted falls back to the last triggered project, cwd, or focused project when unambiguous.")
                )
            })
        }
    }

    private fun waitSchema(): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                routingProperties()
                put(
                    "project",
                    stringProp("Project name. In auto mode, prefer project_path or project_key; blank/omitted falls back to the last triggered project, cwd, or focused project when unambiguous.")
                )
                put(
                    "timeout_ms",
                    intProp(
                        "Wait timeout in milliseconds",
                        defaultValue = DEFAULT_WAIT_TIMEOUT_MS,
                        minimum = MIN_WAIT_TIMEOUT_MS,
                        maximum = MAX_WAIT_TIMEOUT_MS,
                    )
                )
                put(
                    "poll_ms",
                    intProp("Poll interval ms", defaultValue = 1000)
                )
            })
        }
    }

    private fun emptySchema(): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { })
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.routingProperties() {
        put("project_key", stringProp("Exact project key from inspection_list_projects; preferred when disambiguating"))
        put("project_path", stringProp("Exact project root or reported project_file_path from inspection_list_projects"))
        put("worktree_path", stringProp("Exact project/worktree root from inspection_list_projects"))
        put("cwd", stringProp("Working directory nested within the target project; the deepest containing open project wins"))
        put("ide", stringProp("Optional IDE name/product hint for rare ambiguity cases"))
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

    private fun intProp(
        description: String,
        defaultValue: Int? = null,
        minimum: Int? = null,
        maximum: Int? = null,
    ): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("integer"))
            put("description", JsonPrimitive(description))
            if (defaultValue != null) {
                put("default", JsonPrimitive(defaultValue))
            }
            if (minimum != null) {
                put("minimum", JsonPrimitive(minimum))
            }
            if (maximum != null) {
                put("maximum", JsonPrimitive(maximum))
            }
        }
    }

}

private fun JsonObject.string(name: String): String? {
    return (this[name] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.optionalProject(): String? {
    return string("project")?.trim()?.takeIf { it.isNotEmpty() }
}

private fun JsonObject.routingSelector(): Pair<String, String>? {
    string("project_key")?.trim()?.takeIf { it.isNotEmpty() }?.let { return "project_key" to it }
    string("project_path")?.trim()?.takeIf { it.isNotEmpty() }?.let { return "project_path" to it }
    string("worktree_path")?.trim()?.takeIf { it.isNotEmpty() }?.let { return "worktree_path" to it }
    string("cwd")?.trim()?.takeIf { it.isNotEmpty() }?.let { return "cwd" to it }
    optionalProject()?.let { return "project" to it }
    return null
}

private fun summarizeErrorBody(body: String): String? {
    return try {
        val parsedBody = json.parseToJsonElement(body) as? JsonObject ?: return body.trim().takeIf { it.isNotEmpty() }
        val parts = mutableListOf<String>()
        parsedBody["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { parts += it }
        parsedBody["parameter"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { parts += "Parameter: $it" }
        parsedBody["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { parts += it }
        val verdict = parsedBody["inspection_verdict"]?.jsonPrimitive?.contentOrNull
        val verdictReason = parsedBody["inspection_verdict_reason"]?.jsonPrimitive?.contentOrNull
        val verdictMessage = parsedBody["inspection_verdict_message"]?.jsonPrimitive?.contentOrNull
        val verdictNextAction = parsedBody["inspection_verdict_next_action"]?.jsonPrimitive?.contentOrNull
        if (!verdict.isNullOrBlank()) {
            parts += buildString {
                append("VERDICT: ")
                append(verdict)
                if (!verdictReason.isNullOrBlank()) {
                    append(" reason=")
                    append(verdictReason)
                }
            }
            if (!verdictMessage.isNullOrBlank()) {
                parts += "MESSAGE: $verdictMessage"
            }
            if (!verdictNextAction.isNullOrBlank()) {
                parts += "NEXT_ACTION: $verdictNextAction"
            }
        }
        val attribution = parsedBody["inspection_attribution"] as? JsonObject
        if (attribution != null) {
            val fields = listOf(
                "classification",
                "code",
                "phase",
                "endpoint",
                "http_status",
                "request_id",
                "client_run_id",
            ).mapNotNull { name ->
                attribution[name]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { value -> value.isNotBlank() }
                    ?.let { value -> "$name=$value" }
            }
            if (fields.isNotEmpty()) {
                parts += "ATTRIBUTION: ${fields.joinToString(" ")}"
            }
        }
        parsedBody["suggested_open_projects"]?.jsonArray
            ?.mapNotNull { element -> element.jsonPrimitive.contentOrNull }
            ?.takeIf { suggestions -> suggestions.isNotEmpty() }
            ?.let { suggestions -> parts += "Open: ${suggestions.joinToString(", ")}" }
        parsedBody["suggested_recent_projects"]?.jsonArray
            ?.mapNotNull { element ->
                val suggestion = element as? JsonObject ?: return@mapNotNull null
                val name = suggestion["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val path = suggestion["path"]?.jsonPrimitive?.contentOrNull
                if (path.isNullOrBlank()) name else "$name ($path)"
            }
            ?.takeIf { suggestions -> suggestions.isNotEmpty() }
            ?.let { suggestions -> parts += "Recent: ${suggestions.joinToString(", ")}" }
        if (parts.isEmpty()) body.trim().takeIf { it.isNotEmpty() } else parts.joinToString(" | ")
    } catch (_: Exception) {
        body.trim().takeIf { it.isNotEmpty() }
    }
}

private fun JsonObject.int(name: String): Int? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.doubleOrNull?.toInt()
}
