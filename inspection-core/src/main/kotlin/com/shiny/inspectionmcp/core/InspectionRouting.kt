package com.shiny.inspectionmcp.core

import java.nio.file.Path
import java.nio.file.Paths

data class InspectionRouteProject(
    val projectKey: String?,
    val name: String?,
    val basePath: String?,
    val projectFilePath: String?,
    val focused: Boolean,
    val projectInstanceId: String? = null,
)

data class InspectionRouteIdentity(
    val sessionId: String?,
    val startedAtMs: Long?,
    val heartbeatMs: Long?,
    val port: Int?,
    val ideName: String?,
    val ideVersion: String?,
    val ideProductCode: String?,
    val pluginVersion: String?,
    val projects: List<InspectionRouteProject>,
    val pluginBuildFingerprint: String? = null,
)

data class InspectionRouteSelector(
    val projectKey: String? = null,
    val projectPath: String? = null,
    val worktreePath: String? = null,
    val cwd: String? = null,
    val project: String? = null,
    val ide: String? = null,
)

data class InspectionRouteCandidate(
    val identity: InspectionRouteIdentity,
    val project: InspectionRouteProject,
    val score: Int,
)

fun scoreInspectionRouteCandidates(
    identities: List<InspectionRouteIdentity>,
    selector: InspectionRouteSelector,
    defaultCwd: String? = System.getProperty("user.dir"),
): List<InspectionRouteCandidate> {
    val explicitProjectKey = selector.projectKey?.trim()?.takeIf { it.isNotEmpty() }
    val hasExplicitProjectPath = !selector.projectPath.isNullOrBlank()
    val explicitProjectPath = normalizeRoutePath(selector.projectPath)
    val hasExplicitWorktreePath = !selector.worktreePath.isNullOrBlank()
    val explicitWorktreePath = normalizeRoutePath(selector.worktreePath)
    val projectSelector = selector.project?.trim()?.takeIf { it.isNotEmpty() }
    val projectSelectorPath = normalizeRoutePath(projectSelector)
    val hasExplicitCwd = !selector.cwd.isNullOrBlank()
    val explicitCwd = normalizeRoutePath(selector.cwd)
    val defaultRouteCwd = normalizeRoutePath(defaultCwd)
    val ideSelector = selector.ide?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val totalProjects = identities.sumOf { it.projects.size }

    val candidates = mutableListOf<InspectionRouteCandidate>()
    for (identity in identities) {
        if (ideSelector != null && !identityMatchesIde(identity, ideSelector)) {
            continue
        }
        for (project in identity.projects) {
            val score = scoreProject(
                project = project,
                explicitProjectKey = explicitProjectKey,
                hasExplicitProjectPath = hasExplicitProjectPath,
                explicitProjectPath = explicitProjectPath,
                hasExplicitWorktreePath = hasExplicitWorktreePath,
                explicitWorktreePath = explicitWorktreePath,
                hasExplicitCwd = hasExplicitCwd,
                explicitCwd = explicitCwd,
                projectSelector = projectSelector,
                projectSelectorPath = projectSelectorPath,
                defaultCwd = defaultRouteCwd,
                onlyProject = totalProjects == 1,
            )
            if (score > 0) {
                candidates += InspectionRouteCandidate(identity, project, score)
            }
        }
    }

    return candidates.sortedWith(
        compareByDescending<InspectionRouteCandidate> { it.score }
            .thenByDescending { normalizedRoutePathLength(effectiveProjectRoot(it.project)) }
            .thenBy { it.project.name ?: "" }
    )
}

fun normalizeRoutePath(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val withoutKeyPrefix = value.removePrefix("path:").removePrefix("file:")
    if (!looksLikeRoutePath(withoutKeyPrefix)) return null
    val expanded = if (withoutKeyPrefix.startsWith("~")) {
        System.getProperty("user.home") + withoutKeyPrefix.removePrefix("~")
    } else {
        withoutKeyPrefix
    }
    return runCatching { Paths.get(expanded).normalize().toAbsolutePath().toString() }.getOrNull()
}

fun routePathContains(path: String, basePath: String): Boolean {
    return runCatching {
        val normalizedPath = Paths.get(path).normalize().toAbsolutePath()
        val normalizedBase = Paths.get(basePath).normalize().toAbsolutePath()
        normalizedPath == normalizedBase || normalizedPath.startsWith(normalizedBase)
    }.getOrDefault(false)
}

private fun scoreProject(
    project: InspectionRouteProject,
    explicitProjectKey: String?,
    hasExplicitProjectPath: Boolean,
    explicitProjectPath: String?,
    hasExplicitWorktreePath: Boolean,
    explicitWorktreePath: String?,
    hasExplicitCwd: Boolean,
    explicitCwd: String?,
    projectSelector: String?,
    projectSelectorPath: String?,
    defaultCwd: String?,
    onlyProject: Boolean,
): Int {
    val projectKey = project.projectKey
    val projectName = project.name
    val basePath = effectiveProjectRoot(project)
    val projectFilePath = normalizeRoutePath(project.projectFilePath)

    if (explicitProjectKey != null) {
        return if (projectKey == explicitProjectKey) 1000 else 0
    }
    if (hasExplicitProjectPath) {
        return if (explicitProjectPath != null &&
            (explicitProjectPath == basePath || explicitProjectPath == projectFilePath)
        ) 950 else 0
    }
    if (hasExplicitWorktreePath) {
        return if (explicitWorktreePath != null && explicitWorktreePath == basePath) 950 else 0
    }
    if (hasExplicitCwd) {
        return if (explicitCwd != null && basePath != null && routePathContains(explicitCwd, basePath)) {
            700 + normalizedRoutePathLength(basePath).coerceAtMost(100)
        } else {
            0
        }
    }
    if (projectSelectorPath != null) {
        return when {
            projectSelectorPath == basePath || projectSelectorPath == projectFilePath -> 900
            basePath != null && routePathContains(projectSelectorPath, basePath) -> 880
            else -> 0
        }
    }
    if (!projectSelector.isNullOrBlank()) {
        if (projectName == projectSelector) return 800
        if (projectName.equals(projectSelector, ignoreCase = true)) return 790
        return 0
    }
    if (defaultCwd != null && basePath != null && routePathContains(defaultCwd, basePath)) {
        return 700 + normalizedRoutePathLength(basePath).coerceAtMost(100)
    }
    if (project.focused) {
        return 200
    }
    return if (onlyProject) 100 else 0
}

private fun identityMatchesIde(identity: InspectionRouteIdentity, selector: String): Boolean {
    return listOfNotNull(
        identity.ideName,
        identity.ideProductCode,
    ).any { value -> value.lowercase().contains(selector) }
}

private fun looksLikeRoutePath(value: String): Boolean {
    return value.contains('/') || value.contains('\\') || value.startsWith("~") || value.startsWith(".")
}

private fun normalizedRoutePathLength(path: String?): Int {
    return normalizeRoutePath(path)?.length ?: 0
}

fun effectiveProjectRoot(project: InspectionRouteProject): String? {
    return normalizeRoutePath(project.basePath)
        ?: projectRootFromProjectFilePath(project.projectFilePath)
}

fun projectRootFromProjectFilePath(projectFilePath: String?): String? {
    val normalizedProjectFilePath = normalizeRoutePath(projectFilePath) ?: return null
    val path = runCatching { Paths.get(normalizedProjectFilePath) }.getOrNull() ?: return null
    projectRootFromIdeaMetadataPath(path)?.let { return it.toString() }
    return when {
        path.fileName?.toString()?.endsWith(".ipr") == true -> path.parent?.toString()
        else -> null
    }
}

fun projectRootFromIdeaMetadataPath(path: Path): Path? {
    var cursor: Path? = path
    while (cursor != null) {
        if (cursor.fileName?.toString() == ".idea") {
            return cursor.parent
        }
        cursor = cursor.parent
    }
    return null
}
