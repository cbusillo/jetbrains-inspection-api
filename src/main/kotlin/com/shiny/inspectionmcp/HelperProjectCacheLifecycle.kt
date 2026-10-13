package com.shiny.inspectionmcp

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.project.getProjectCacheFileName
import com.intellij.openapi.project.getProjectDataPath
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val HELPER_PROJECT_CACHE_LIFECYCLE_VERSION = 1
internal const val HELPER_PROJECT_CACHE_MARKER = ".inspection-helper-owned.json"

internal data class HelperProjectCacheRecord(
    val directory: Path,
    val worktreePath: String,
    val projectPath: String,
    val cacheName: String,
    val ownershipId: String,
)

@Service(Service.Level.APP)
class HelperProjectCacheRegistry : Disposable {
    private val retiring = ConcurrentHashMap.newKeySet<Path>()
    private var observing = false

    @Synchronized fun ensureObserving() {
        if (observing) return
        val manager = ProjectManager.getInstance()
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ProjectManager.TOPIC,
            object : ProjectManagerListener {
                override fun projectClosing(project: Project) { forgetProjectOwnership(project) }
            },
        )
        manager.openProjects.forEach(::forgetProjectOwnership)
        observing = true
    }

    fun forgetProjectOwnership(project: Project) {
        Files.deleteIfExists(project.getProjectDataPath("inspection-retirement").parent.resolve(HELPER_PROJECT_CACHE_MARKER))
    }

    fun recordClosedProject(project: Project, worktree: Path) {
        check(project.isDisposed) { "Cache ownership requires verified project disposal" }
        val path = requireNotNull(project.presentableUrl)
        val directory = project.getProjectDataPath("inspection-retirement").parent
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
        check(!Files.isSymbolicLink(directory.parent)) { "Cache parent must not be a symlink" }
        val record = HelperProjectCacheRecord(directory, canonicalWorktree(worktree).toString(), path,
            directory.fileName.toString(), UUID.randomUUID().toString())
        val temporary = Files.createTempFile(directory, ".inspection-ownership-", ".tmp")
        try {
            Files.writeString(temporary, Gson().toJson(mapOf(
                "worktree_path" to record.worktreePath, "project_path" to record.projectPath,
                "cache_name" to record.cacheName, "ownership_id" to record.ownershipId,
            )))
            Files.move(temporary, directory.resolve(HELPER_PROJECT_CACHE_MARKER),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    internal fun records(projects: Path, root: Path): List<HelperProjectCacheRecord> {
        if (!Files.isDirectory(projects, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.list(projects).use { paths ->
            paths.map(::readRecord).filter { it != null && it.worktreePath == root.toString() }
                .map { requireNotNull(it) }.toList()
        }
    }

    internal fun readRecord(directory: Path): HelperProjectCacheRecord? {
        val marker = directory.resolve(HELPER_PROJECT_CACHE_MARKER)
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return null
        val content = Files.readString(marker)
        return runCatching {
            val json = JsonParser.parseString(content).asJsonObject
            HelperProjectCacheRecord(directory, json.get("worktree_path").asString,
                json.get("project_path").asString, json.get("cache_name").asString,
                UUID.fromString(json.get("ownership_id").asString).toString())
        }.getOrNull()
    }

    internal fun begin(root: Path) = retiring.add(root)
    internal fun end(root: Path) { retiring.remove(root) }
    override fun dispose() = Unit
}

internal fun helperProjectCacheInUse(project: Project, record: HelperProjectCacheRecord): Boolean {
    if (project.getProjectCacheFileName() == record.cacheName) return true
    val root = canonicalWorktree(Path.of(record.worktreePath))
    return (project.basePath ?: project.presentableUrl)?.let { base ->
        val path = canonicalWorktree(Path.of(base))
        path.startsWith(root) || root.startsWith(path)
    } == true
}

internal class HelperProjectCacheLifecycle(
    private val registry: HelperProjectCacheRegistry = ApplicationManager.getApplication()
        .getService(HelperProjectCacheRegistry::class.java),
    private val systemDirectory: Path = Path.of(PathManager.getSystemPath()),
    private val active: (HelperProjectCacheRecord) -> Boolean = { record ->
        ProjectManager.getInstance().openProjects.any { helperProjectCacheInUse(it, record) }
    },
    private val onEdt: ((() -> Unit) -> Unit) = { action ->
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action()
        else {
            val task = FutureTask<Unit> { action() }
            application.invokeLater(task)
            try { task.get(10, TimeUnit.SECONDS) } finally { task.cancel(false) }
        }
    },
) {
    fun retire(worktree: Path, dryRun: Boolean): Map<String, Any?> {
        val root = canonicalWorktree(worktree)
        if (!registry.begin(root)) return response(root, dryRun, emptyList(), "cache_retirement_busy")
        val results = mutableListOf<Map<String, Any?>>()
        var reason: String? = null
        try {
            val projects = systemDirectory.toAbsolutePath().normalize().resolve("projects")
            if (Files.isSymbolicLink(projects)) reason = "cache_path_symlink"
            else registry.records(projects, root).forEach { results.add(retireRecord(it, root, projects, dryRun)) }
        } catch (_: TimeoutException) { reason = "cache_retirement_busy"
        } catch (error: ExecutionException) {
            if (error.cause !is IOException) throw error
            reason = "cache_move_failed"
        } catch (_: IOException) { reason = "cache_removal_failed"
        } catch (_: UncheckedIOException) { reason = "cache_removal_failed"
        } finally { registry.end(root) }
        return response(root, dryRun, results, reason)
    }

    private fun retireRecord(record: HelperProjectCacheRecord, root: Path, projects: Path,
                             dryRun: Boolean): Map<String, Any?> {
        val original = projects.resolve(record.cacheName).normalize()
        var target = record.directory
        var reason: String? = null
        onEdt {
            reason = when {
                !canonicalWorktree(Path.of(record.projectPath)).startsWith(root) ||
                    record.cacheName != getProjectCacheFileName(Path.of(record.projectPath)) ||
                    original.parent != projects || target.parent != projects ||
                    (target.fileName.toString() != record.cacheName && !isRetiredCacheName(target.fileName.toString())) -> "ownership_mismatch"
                Files.isSymbolicLink(target) -> "cache_path_symlink"
                registry.readRecord(target) != record -> "ownership_changed"
                active(record) -> "project_in_use"
                else -> null
            }
            if (reason == null && !dryRun && target == original) {
                val quarantine = projects.resolve(".inspection-retired-${UUID.randomUUID()}")
                Files.move(target, quarantine, StandardCopyOption.ATOMIC_MOVE)
                target = quarantine
            }
        }
        val bytes = if (reason == null) Files.walk(target).use { files ->
            files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
        } else 0L
        if (reason == null && !dryRun) {
            try {
                val marker = target.resolve(HELPER_PROJECT_CACHE_MARKER)
                Files.walk(target).use { files ->
                    files.filter { it != target && it != marker }.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                }
                Files.delete(marker)
                Files.delete(target)
            } catch (_: IOException) { reason = "cache_removal_failed"
            } catch (_: UncheckedIOException) { reason = "cache_removal_failed" }
        }
        return mapOf("project_path" to record.projectPath, "worktree_path" to root.toString(),
            "cache_path" to original.toString(), "quarantine_path" to target.takeIf { it != original }?.toString(),
            "bytes" to bytes, "status" to when {
                reason != null -> "refused"
                dryRun -> "would_remove"
                else -> "removed"
            }, "reason" to (reason ?: "helper_owned_closed_project"))
    }

    private fun response(root: Path, dryRun: Boolean, results: List<Map<String, Any?>>, reason: String?) = mapOf(
        "status" to if (reason != null || results.any { it["status"] == "refused" }) "refused" else "ok",
        "reason" to reason, "dry_run" to dryRun,
        "project_cache_lifecycle_version" to HELPER_PROJECT_CACHE_LIFECYCLE_VERSION,
        "worktree_path" to root.toString(), "caches" to results,
    )

    private fun isRetiredCacheName(name: String) = name.startsWith(".inspection-retired-") &&
        runCatching { UUID.fromString(name.removePrefix(".inspection-retired-")) }.isSuccess
}
