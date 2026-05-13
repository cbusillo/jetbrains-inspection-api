package com.shiny.inspectionmcp.mcpserver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import com.shiny.inspectionmcp.core.InspectionRouteIdentity
import com.shiny.inspectionmcp.core.InspectionRouteProject
import com.shiny.inspectionmcp.core.InspectionRouteSelector
import com.shiny.inspectionmcp.core.scoreInspectionRouteCandidates
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlin.io.path.name

private const val REGISTRY_DIR_ENV = "JETBRAINS_INSPECTION_REGISTRY_DIR"
private const val PORTS_ENV = "JETBRAINS_INSPECTION_PORTS"
private const val REGISTRY_TTL_MS = 60_000L

private val routeJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal data class InspectionTarget(
    val baseUrl: String,
    val idePort: String,
    val identity: JsonObject? = null,
    val project: JsonObject? = null,
)

internal interface InspectionTargetResolver {
    val autoRouting: Boolean
    fun resolve(args: JsonObject, excludedSessionIds: Set<String> = emptySet()): InspectionTarget
    fun listProjects(): JsonElement
    fun invalidate(target: InspectionTarget) = Unit
}

internal class FixedTargetResolver(
    private val baseUrl: String,
    private val idePort: String,
) : InspectionTargetResolver {
    override val autoRouting: Boolean = false

    override fun resolve(args: JsonObject, excludedSessionIds: Set<String>): InspectionTarget {
        return InspectionTarget(baseUrl = baseUrl, idePort = idePort)
    }

    override fun listProjects(): JsonElement {
        return buildJsonObject {
            put("mode", JsonPrimitive("fixed"))
            put("port", JsonPrimitive(idePort))
            put("base_url", JsonPrimitive(baseUrl))
        }
    }

}

internal class AutoTargetResolver(
    private val httpClientFactory: () -> HttpClient,
    private val registryDir: Path = defaultRegistryDir(),
    private val scanPorts: List<Int> = defaultScanPorts(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : InspectionTargetResolver {
    override val autoRouting: Boolean = true

    override fun resolve(args: JsonObject, excludedSessionIds: Set<String>): InspectionTarget {
        var identities = discoverIdentities(includePortScan = false)
        if (identities.isEmpty()) {
            identities = discoverIdentities(includePortScan = true)
        }
        identities = identities.filterNotExcluded(excludedSessionIds)
        if (identities.isEmpty()) {
            throw RuntimeException(
                "No JetBrains IDE inspection plugin instances were discovered. Open an IDE with the plugin installed, or set IDE_PORT for fixed-port mode."
            )
        }

        var candidates = scoreCandidates(args, identities)
        if (candidates.isEmpty()) {
            identities = discoverIdentities(includePortScan = true)
                .filterNotExcluded(excludedSessionIds)
            candidates = scoreCandidates(args, identities)
        }
        if (candidates.isEmpty()) {
            throw RuntimeException("No open JetBrains project matched this request.\n\n${formatCandidates(identities)}")
        }

        val bestScore = candidates.first().score
        val best = candidates.filter { it.score == bestScore }
        if (best.size > 1) {
            throw RuntimeException(
                "Multiple JetBrains projects matched this request. Retry with project_path or project_key.\n\n${formatCandidateProjects(best)}"
            )
        }

        return best.first().target
    }

    override fun listProjects(): JsonElement {
        val identities = discoverIdentities(includePortScan = true)
        return buildJsonObject {
            put("mode", JsonPrimitive("auto"))
            put("registry_dir", JsonPrimitive(registryDir.toString()))
            put("projects", JsonArray(identities.flatMap { identity -> projectsForIdentity(identity).map { project ->
                buildJsonObject {
                    put("session_id", identity.string("session_id")?.let(::JsonPrimitive) ?: JsonPrimitive(""))
                    put("port", JsonPrimitive(identity.port() ?: 0))
                    put("ide_name", identity.string("ide_name")?.let(::JsonPrimitive) ?: JsonPrimitive(""))
                    identity.string("ide_product_code")?.let { put("ide_product_code", JsonPrimitive(it)) }
                    put("project_key", project.string("project_key")?.let(::JsonPrimitive) ?: JsonPrimitive(""))
                    put("name", project.string("name")?.let(::JsonPrimitive) ?: JsonPrimitive(""))
                    project.string("base_path")?.let { put("base_path", JsonPrimitive(it)) }
                    project.string("project_file_path")?.let { put("project_file_path", JsonPrimitive(it)) }
                    put("focused", JsonPrimitive(project.isFocusedProject()))
                }
            }}))
        }
    }

    private fun discoverIdentities(includePortScan: Boolean): List<JsonObject> {
        val registryIdentities = readRegistryIdentities().mapNotNull(::verifyIdentity)
        val scannedIdentities = if (includePortScan || registryIdentities.isEmpty()) scanIdentities() else emptyList()
        return (registryIdentities + scannedIdentities)
            .distinctBy { identity -> identity.string("session_id") ?: "port:${identity.port()}" }
    }

    private fun List<JsonObject>.filterNotExcluded(excludedSessionIds: Set<String>): List<JsonObject> {
        if (excludedSessionIds.isEmpty()) return this
        return filter { identity -> identity.string("session_id") !in excludedSessionIds }
    }

    private fun readRegistryIdentities(): List<JsonObject> {
        return try {
            if (!Files.isDirectory(registryDir)) return emptyList()
            Files.list(registryDir).use { stream ->
                stream
                    .filter { path -> path.name.endsWith(".json") }
                    .toList()
                    .mapNotNull { path -> readRegistryIdentity(path) }
                    .filter { identity -> isFresh(identity) && processLooksAlive(identity) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readRegistryIdentity(path: Path): JsonObject? {
        return try {
            routeJson.parseToJsonElement(Files.readString(path)) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun isFresh(identity: JsonObject): Boolean {
        val heartbeat = identity.longString("heartbeat_ms")?.toLongOrNull() ?: return false
        return nowMs() - heartbeat <= REGISTRY_TTL_MS
    }

    private fun processLooksAlive(identity: JsonObject): Boolean {
        val pid = identity.longString("pid")?.toLongOrNull() ?: return true
        return runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrDefault(true)
    }

    private fun verifyIdentity(candidate: JsonObject): JsonObject? {
        val port = candidate.port() ?: return null
        return fetchIdentity(port)
    }

    private fun scanIdentities(): List<JsonObject> {
        return scanPorts.mapNotNull(::fetchIdentity)
    }

    private fun fetchIdentity(port: Int): JsonObject? {
        return try {
            val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/inspection/identity"))
                .GET()
                .timeout(Duration.ofMillis(800))
                .build()
            val response = httpClientFactory().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != HttpURLConnection.HTTP_OK) return null
            val identity = routeJson.parseToJsonElement(response.body()) as? JsonObject ?: return null
            val reportedPort = identity.port()
            if (reportedPort == null || reportedPort <= 0) {
                buildJsonObject {
                    identity.forEach { (key, value) -> put(key, value) }
                    put("port", JsonPrimitive(port))
                }
            } else {
                identity
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scoreCandidates(args: JsonObject, identities: List<JsonObject>): List<RouteCandidate> {
        val identityBySession = identities.associateBy { identity -> identity.string("session_id") ?: "port:${identity.port()}" }
        val projectsByRouteKey = identities.associate { identity ->
            val sessionKey = identity.string("session_id") ?: "port:${identity.port()}"
            sessionKey to projectsForIdentity(identity).associateBy { project ->
                routeProjectKey(project.string("project_key"), project.string("name"), project.string("base_path"))
            }
        }

        return scoreInspectionRouteCandidates(
            identities = identities.map(::toRouteIdentity),
            selector = InspectionRouteSelector(
                projectKey = args.string("project_key"),
                projectPath = args.string("project_path"),
                worktreePath = args.string("worktree_path"),
                cwd = args.string("cwd"),
                project = args.string("project"),
                ide = args.string("ide"),
            ),
        ).mapNotNull { candidate ->
            val sessionKey = candidate.identity.sessionId ?: "port:${candidate.identity.port}"
            val identity = identityBySession[sessionKey] ?: return@mapNotNull null
            val port = identity.port() ?: return@mapNotNull null
            val projectRouteKey = routeProjectKey(candidate.project.projectKey, candidate.project.name, candidate.project.basePath)
            val project = projectsByRouteKey[sessionKey]?.get(projectRouteKey) ?: return@mapNotNull null
            RouteCandidate(
                InspectionTarget(
                    baseUrl = "http://localhost:$port/api/inspection",
                    idePort = port.toString(),
                    identity = identity,
                    project = project,
                ),
                candidate.score,
            )
        }
    }

    private fun projectsForIdentity(identity: JsonObject): List<JsonObject> {
        return identity["open_projects"]?.jsonArray?.filterIsInstance<JsonObject>() ?: emptyList()
    }

    private fun formatCandidates(identities: List<JsonObject>): String {
        val projects = identities.flatMap { identity -> projectsForIdentity(identity).map { identity to it } }
        if (projects.isEmpty()) {
            return "No open projects were reported by discovered IDEs."
        }
        return "Candidates:\n" + projects.joinToString("\n") { (identity, project) ->
            "- ${identity.string("ide_name") ?: "JetBrains IDE"} / ${project.string("name") ?: "<unnamed>"} / ${project.string("base_path") ?: project.string("project_key") ?: "<no path>"}"
        }
    }

    private fun formatCandidateProjects(candidates: List<RouteCandidate>): String {
        return "Candidates:\n" + candidates.joinToString("\n") { candidate ->
            val identity = candidate.target.identity
            val project = candidate.target.project
            "- ${identity?.string("ide_name") ?: "JetBrains IDE"} / ${project?.string("name") ?: "<unnamed>"} / ${project?.string("base_path") ?: project?.string("project_key") ?: "<no path>"} / project_key=${project?.string("project_key") ?: ""}"
        }
    }
}

private fun toRouteIdentity(identity: JsonObject): InspectionRouteIdentity {
    return InspectionRouteIdentity(
        sessionId = identity.string("session_id"),
        startedAtMs = identity.longString("started_at_ms")?.toLongOrNull(),
        heartbeatMs = identity.longString("heartbeat_ms")?.toLongOrNull(),
        port = identity.port(),
        ideName = identity.string("ide_name"),
        ideVersion = identity.string("ide_version"),
        ideProductCode = identity.string("ide_product_code"),
        pluginVersion = identity.string("plugin_version"),
        projects = identity["open_projects"]?.jsonArray?.filterIsInstance<JsonObject>()?.map(::toRouteProject) ?: emptyList(),
    )
}

private fun toRouteProject(project: JsonObject): InspectionRouteProject {
    return InspectionRouteProject(
        projectKey = project.string("project_key"),
        name = project.string("name"),
        basePath = project.string("base_path"),
        projectFilePath = project.string("project_file_path"),
        focused = project.isFocusedProject(),
    )
}

private fun routeProjectKey(projectKey: String?, name: String?, basePath: String?): String {
    return projectKey ?: "name:$name:base:$basePath"
}

private data class RouteCandidate(
    val target: InspectionTarget,
    val score: Int,
)

internal fun defaultRegistryDir(): Path {
    System.getenv(REGISTRY_DIR_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let { return Paths.get(it) }
    val userHome = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val base = when {
        os.contains("win") -> System.getenv("LOCALAPPDATA")?.let { Paths.get(it) }
            ?: Paths.get(userHome, "AppData", "Local")
        os.contains("mac") -> Paths.get(userHome, "Library", "Caches")
        else -> System.getenv("XDG_CACHE_HOME")?.let { Paths.get(it) }
            ?: Paths.get(userHome, ".cache")
    }
    return base.resolve("jetbrains-inspection-api").resolve("instances")
}

internal fun defaultScanPorts(): List<Int> {
    val configured = System.getenv(PORTS_ENV)?.trim()?.takeIf { it.isNotEmpty() }
    if (configured != null) {
        return configured.split(',').flatMap { token ->
            val trimmed = token.trim()
            val rangeParts = trimmed.split('-', limit = 2)
            if (rangeParts.size == 2) {
                val start = rangeParts[0].toIntOrNull()
                val end = rangeParts[1].toIntOrNull()
                if (start != null && end != null && start <= end) (start..end).toList() else emptyList()
            } else {
                trimmed.toIntOrNull()?.let(::listOf) ?: emptyList()
            }
        }.distinct()
    }
    return (63340..63349).toList()
}

private fun JsonObject.string(name: String): String? {
    return this[name]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.port(): Int? {
    return this["port"]?.jsonPrimitive?.intOrNull
}

private fun JsonObject.longString(name: String): String? {
    return this[name]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.isFocusedProject(): Boolean {
    return this["focused"]?.jsonPrimitive?.contentOrNull == "true"
}
