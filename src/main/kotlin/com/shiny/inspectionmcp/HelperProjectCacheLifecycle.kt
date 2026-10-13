package com.shiny.inspectionmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.getProjectCacheFileName
import com.intellij.openapi.project.getProjectDataPath
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal const val HELPER_PROJECT_CACHE_LIFECYCLE_VERSION = 1

class HelperProjectCacheRecord {
    var worktreePath: String = ""
    var projectPath: String = ""
    var cacheName: String = ""
    var quarantineName: String = ""
}

class HelperProjectCacheState {
    var records: MutableList<HelperProjectCacheRecord> = mutableListOf()
}

@Service(Service.Level.APP)
@State(name = "InspectionHelperProjectCaches", storages = [Storage("inspection-helper-project-caches.xml", roamingType = RoamingType.DISABLED)])
class HelperProjectCacheRegistry : PersistentStateComponent<HelperProjectCacheState> {
    private var ownership = HelperProjectCacheState()
    override fun getState(): HelperProjectCacheState = ownership
    override fun loadState(state: HelperProjectCacheState) { ownership = state }

    fun recordClosedProject(project: Project, worktree: Path) {
        check(project.isDisposed) { "Cache ownership requires verified project disposal" }
        val path = requireNotNull(project.presentableUrl)
        val cacheName = project.getProjectDataPath("inspection-retirement").parent.fileName.toString()
        synchronized(this) {
            ownership.records.removeIf { it.projectPath == path && it.quarantineName.isEmpty() }
            ownership.records.add(HelperProjectCacheRecord().apply {
                worktreePath = canonicalWorktree(worktree).toString()
                projectPath = path
                this.cacheName = cacheName
            })
        }
    }
}

internal class HelperProjectCacheLifecycle(
    private val registry: HelperProjectCacheRegistry = ApplicationManager.getApplication()
        .getService(HelperProjectCacheRegistry::class.java),
    private val systemDirectory: Path = Path.of(PathManager.getSystemPath()),
    private val active: (HelperProjectCacheRecord) -> Boolean = { record ->
        val root = canonicalWorktree(Path.of(record.worktreePath))
        ProjectManager.getInstance().openProjects.any { project ->
            project.getProjectCacheFileName() == record.cacheName || project.basePath?.let { base ->
                val path = canonicalWorktree(Path.of(base))
                path.startsWith(root) || root.startsWith(path)
            } != false
        }
    },
    private val onEdt: ((() -> Unit) -> Unit) = { action ->
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action()
        else {
            val task = FutureTask<Unit> { action() }
            application.invokeLater(task)
            try {
                task.get(10, TimeUnit.SECONDS)
            } finally {
                task.cancel(false)
            }
        }
    },
) {
    fun retire(worktree: Path, dryRun: Boolean): Map<String, Any?> {
        val root = canonicalWorktree(worktree)
        val projects = systemDirectory.toAbsolutePath().normalize().resolve("projects")
        val records = synchronized(registry) { registry.state.records.filter { it.worktreePath == root.toString() } }
        val results = mutableListOf<Map<String, Any?>>()
        for (record in records) {
            val path = projects.resolve(record.cacheName).normalize()
            var reason: String? = null
            var quarantine: Path? = null
            onEdt {
                val quarantined = record.quarantineName.takeIf { it.isNotEmpty() }?.let(projects::resolve)
                reason = when {
                    !canonicalWorktree(Path.of(record.projectPath)).startsWith(root) ||
                        record.cacheName != getProjectCacheFileName(Path.of(record.projectPath)) ||
                        path.parent != projects -> "ownership_mismatch"
                    record.quarantineName.isNotEmpty() &&
                        !isRetiredCacheName(record.quarantineName) -> "ownership_mismatch"
                    Files.isSymbolicLink(projects) || Files.isSymbolicLink(path) ||
                        (quarantined != null && Files.isSymbolicLink(quarantined)) -> "cache_path_symlink"
                    active(record) -> "project_in_use"
                    else -> null
                }
                if (reason == null) {
                    quarantine = quarantined
                    if (!dryRun && quarantine == null && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        val retired = projects.resolve(".inspection-retired-${UUID.randomUUID()}")
                        // Only the rename runs on EDT, so a later open gets a new cache directory.
                        Files.move(path, retired, StandardCopyOption.ATOMIC_MOVE)
                        record.quarantineName = retired.fileName.toString()
                        quarantine = retired
                    }
                }
            }
            val target = quarantine ?: path
            val bytes = if (reason == null && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.walk(target).use { files ->
                    files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                        .mapToLong { Files.size(it) }.sum()
                }
            } else 0L
            if (quarantine != null && !dryRun && reason == null && Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.walk(quarantine).use { files ->
                        files.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                    }
                } catch (_: java.io.IOException) {
                    reason = "cache_removal_failed"
                } catch (_: java.io.UncheckedIOException) {
                    reason = "cache_removal_failed"
                }
            }
            if (reason == null && !dryRun) synchronized(registry) { registry.state.records.remove(record) }
            results.add(mapOf(
                "project_path" to record.projectPath,
                "worktree_path" to root.toString(),
                "cache_path" to path.toString(),
                "quarantine_path" to quarantine?.toString(),
                "bytes" to bytes,
                "status" to when {
                    reason != null -> "refused"
                    dryRun && Files.exists(target, LinkOption.NOFOLLOW_LINKS) -> "would_remove"
                    dryRun -> "absent"
                    quarantine != null -> "removed"
                    else -> "absent"
                },
                "reason" to (reason ?: "helper_owned_closed_project"),
            ))
        }
        return mapOf(
            "status" to if (results.any { it["status"] == "refused" }) "refused" else "ok",
            "dry_run" to dryRun,
            "project_cache_lifecycle_version" to HELPER_PROJECT_CACHE_LIFECYCLE_VERSION,
            "worktree_path" to root.toString(),
            "caches" to results,
        )
    }

    private fun isRetiredCacheName(name: String): Boolean = name.startsWith(".inspection-retired-") &&
        runCatching { UUID.fromString(name.removePrefix(".inspection-retired-")) }.isSuccess
}
