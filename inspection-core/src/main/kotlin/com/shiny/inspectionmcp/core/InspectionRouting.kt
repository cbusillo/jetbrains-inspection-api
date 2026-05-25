package com.shiny.inspectionmcp.core

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
    val explicitProjectPath = normalizeRoutePath(selector.projectPath)
        ?: normalizeRoutePath(selector.worktreePath)
    val projectSelector = selector.project?.trim()?.takeIf { it.isNotEmpty() }
    val projectSelectorPath = normalizeRoutePath(projectSelector)
    val cwd = normalizeRoutePath(selector.cwd) ?: normalizeRoutePath(defaultCwd)
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
                explicitProjectPath = explicitProjectPath,
                projectSelector = projectSelector,
                projectSelectorPath = projectSelectorPath,
                cwd = cwd,
                onlyProject = totalProjects == 1,
            )
            if (score > 0) {
                candidates += InspectionRouteCandidate(identity, project, score)
            }
        }
    }

    return candidates.sortedWith(
        compareByDescending<InspectionRouteCandidate> { it.score }
            .thenByDescending { normalizedRoutePathLength(it.project.basePath) }
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
    explicitProjectPath: String?,
    projectSelector: String?,
    projectSelectorPath: String?,
    cwd: String?,
    onlyProject: Boolean,
): Int {
    val projectKey = project.projectKey
    val projectName = project.name
    val basePath = normalizeRoutePath(project.basePath)
        ?: projectRootFromProjectFilePath(project.projectFilePath)
    val projectFilePath = normalizeRoutePath(project.projectFilePath)

    if (explicitProjectKey != null) {
        return if (projectKey == explicitProjectKey) 1000 else 0
    }
    if (explicitProjectPath != null) {
        return when {
            explicitProjectPath == basePath || explicitProjectPath == projectFilePath -> 950
            basePath != null && routePathContains(explicitProjectPath, basePath) -> 930
            else -> 0
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
    if (cwd != null && basePath != null && routePathContains(cwd, basePath)) {
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

fun projectRootFromProjectFilePath(projectFilePath: String?): String? {
    val normalizedProjectFilePath = normalizeRoutePath(projectFilePath) ?: return null
    val path = runCatching { Paths.get(normalizedProjectFilePath) }.getOrNull() ?: return null
    return when {
        path.parent?.fileName?.toString() == ".idea" -> path.parent?.parent?.toString()
        path.fileName?.toString()?.endsWith(".ipr") == true -> path.parent?.toString()
        else -> null
    }
}
