package com.shiny.inspectionmcp

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.shiny.inspectionmcp.core.formatJsonManually
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val BUILD_INFO_RESOURCE = "/com/shiny/inspectionmcp/inspection-build.properties"
private const val REGISTRY_DIR_ENV = "JETBRAINS_INSPECTION_REGISTRY_DIR"
private const val REGISTRY_HEARTBEAT_SECONDS = 10L

internal object InspectionIdeSession {
    val sessionId: String = UUID.randomUUID().toString()
    val startedAtMs: Long = System.currentTimeMillis()
}

internal data class InspectionPluginBuildInfo(
    val version: String?,
    val commit: String?,
    val shortCommit: String?,
    val dirty: Boolean?,
    val time: String?,
    val fingerprint: String?,
)

internal fun loadInspectionPluginBuildInfo(): InspectionPluginBuildInfo {
    val properties = Properties()
    InspectionIdeSession::class.java.getResourceAsStream(BUILD_INFO_RESOURCE)?.use { stream ->
        properties.load(stream)
    }
    fun value(key: String): String? = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    return InspectionPluginBuildInfo(
        version = value("plugin.version"),
        commit = value("plugin.build.commit"),
        shortCommit = value("plugin.build.short_commit"),
        dirty = value("plugin.build.dirty")?.toBooleanStrictOrNull(),
        time = value("plugin.build.time"),
        fingerprint = value("plugin.build.fingerprint"),
    )
}

private val pluginBuildInfo: InspectionPluginBuildInfo by lazy { loadInspectionPluginBuildInfo() }

internal fun inspectionPluginVersion(): String? = pluginBuildInfo.version

internal fun buildInspectionIdentity(): Map<String, Any?> {
    val appInfo = ApplicationInfo.getInstance()
    val buildInfo = pluginBuildInfo
    return mapOf(
        "session_id" to InspectionIdeSession.sessionId,
        "started_at_ms" to InspectionIdeSession.startedAtMs,
        "heartbeat_ms" to System.currentTimeMillis(),
        "pid" to ProcessHandle.current().pid(),
        "port" to resolveIdePort(),
        "ide_name" to appInfo.fullApplicationName,
        "ide_version" to appInfo.fullVersion,
        "ide_product_code" to resolveIdeProductCode(appInfo),
        "plugin_version" to buildInfo.version,
        "plugin_build_fingerprint" to buildInfo.fingerprint,
        "plugin_build_commit" to buildInfo.commit,
        "plugin_build_short_commit" to buildInfo.shortCommit,
        "plugin_build_dirty" to buildInfo.dirty,
        "plugin_build_time" to buildInfo.time,
        "lifecycle_ownership_protocol" to LIFECYCLE_OWNERSHIP_PROTOCOL,
        "open_projects" to openProjectIdentities(),
    )
}

internal fun resolveIdePort(): Int? {
    val managerPort = resolvePortFromClasses(
        listOf(
            "com.intellij.ide.BuiltInServerManager",
            "org.jetbrains.ide.BuiltInServerManager"
        ),
        listOf("getPort")
    )
    if (managerPort != null && managerPort > 0) return managerPort

    val optionsPort = resolvePortFromClasses(
        listOf(
            "com.intellij.ide.BuiltInServerOptions",
            "org.jetbrains.ide.BuiltInServerOptions"
        ),
        listOf("getPort", "getBuiltInServerPort")
    )
    return optionsPort?.takeIf { it > 0 }
}

internal fun inspectionRegistryInstancesDir(): Path {
    System.getenv(REGISTRY_DIR_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let { return Paths.get(it) }

    val base = when {
        SystemInfo.isWindows -> System.getenv("LOCALAPPDATA")?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), "AppData", "Local")
        SystemInfo.isMac -> Paths.get(System.getProperty("user.home"), "Library", "Caches")
        else -> System.getenv("XDG_CACHE_HOME")?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".cache")
    }
    return base.resolve("jetbrains-inspection-api").resolve("instances")
}

internal object InspectionIdeRegistry {
    private val started = AtomicBoolean(false)
    private var heartbeat: ScheduledFuture<*>? = null

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        AppExecutorUtil.getAppExecutorService().execute {
            waitForBuiltInServerStart()
            writeRegistryFile()
            heartbeat = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                { writeRegistryFile() },
                REGISTRY_HEARTBEAT_SECONDS,
                REGISTRY_HEARTBEAT_SECONDS,
                TimeUnit.SECONDS,
            )
        }
        Runtime.getRuntime().addShutdownHook(Thread { deleteRegistryFile() })
    }

    private fun waitForBuiltInServerStart() {
        for (className in listOf("com.intellij.ide.BuiltInServerManager", "org.jetbrains.ide.BuiltInServerManager")) {
            try {
                val clazz = Class.forName(className)
                val instance = singletonInstance(clazz)
                val waitForStart = clazz.methods.firstOrNull { it.name == "waitForStart" && it.parameterCount == 0 }
                if (waitForStart != null) {
                    waitForStart.invoke(instance)
                    return
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun writeRegistryFile() {
        try {
            val dir = inspectionRegistryInstancesDir()
            Files.createDirectories(dir)
            val target = registryFile(dir)
            val temp = dir.resolve("${target.fileName}.tmp")
            val body = formatJsonManually(buildInspectionIdentity())
            Files.writeString(temp, body)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
        }
    }

    private fun deleteRegistryFile() {
        try {
            Files.deleteIfExists(registryFile(inspectionRegistryInstancesDir()))
        } catch (_: Exception) {
        }
    }

    private fun registryFile(dir: Path): Path {
        return dir.resolve("${InspectionIdeSession.sessionId}.json")
    }
}

internal fun openProjectIdentities(): List<Map<String, Any?>> {
    return ApplicationManager.getApplication().runReadAction<List<Map<String, Any?>>, Exception> {
        ProjectManager.getInstance().openProjects
            .filter { project -> !project.isDefault && !project.isDisposed && project.isInitialized }
            .map(::openProjectIdentity)
    }
}

internal fun openProjectIdentity(project: Project): Map<String, Any?> {
    return mapOf(
        "project_key" to projectKey(project),
        "project_instance_id" to projectInstanceId(project),
        "name" to runCatching { project.name }.getOrDefault(""),
        "base_path" to runCatching { project.basePath }.getOrNull(),
        "project_file_path" to runCatching { project.projectFilePath }.getOrNull(),
        "focused" to isFocusedProject(project),
    )
}

internal fun projectInstanceId(project: Project): String {
    return "${InspectionIdeSession.sessionId}:${System.identityHashCode(project)}"
}

private fun isFocusedProject(project: Project): Boolean {
    val focusedProject = runCatching { IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project }.getOrNull()
    if (focusedProject == project) {
        return true
    }
    return runCatching { WindowManager.getInstance().suggestParentWindow(project)?.isActive == true }
        .getOrDefault(false)
}

private fun resolveIdeProductCode(appInfo: ApplicationInfo): String? {
    return runCatching {
        val build = appInfo.build
        val getter = build.javaClass.methods.firstOrNull { method ->
            method.name == "getProductCode" && method.parameterCount == 0
        }
        getter?.invoke(build) as? String
    }.getOrNull()
}

private fun resolvePortFromClasses(classNames: List<String>, methodNames: List<String>): Int? {
    for (className in classNames) {
        val port = resolvePortFromClass(className, methodNames)
        if (port != null && port > 0) return port
    }
    return null
}

private fun resolvePortFromClass(className: String, methodNames: List<String>): Int? {
    return try {
        val clazz = Class.forName(className)
        val instance = singletonInstance(clazz)
        for (methodName in methodNames) {
            val getter = clazz.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: continue
            val port = getter.invoke(instance) as? Int
            if (port != null && port > 0) return port
        }
        null
    } catch (_: Throwable) {
        null
    }
}

private fun singletonInstance(clazz: Class<*>): Any? {
    return clazz.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }
        ?.invoke(null)
        ?: clazz.fields.firstOrNull { it.name == "INSTANCE" }?.get(null)
}
