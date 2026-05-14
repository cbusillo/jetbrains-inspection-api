package com.shiny.inspectionmcp.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectionRoutingTest {
    @Test
    fun projectKeyBeatsCwdAndFocus() {
        val first = identity(project("path:/tmp/first", "first", "/tmp/first", focused = true))
        val second = identity(project("path:/tmp/second", "second", "/tmp/second"))

        val candidates = scoreInspectionRouteCandidates(
            identities = listOf(first, second),
            selector = InspectionRouteSelector(projectKey = "path:/tmp/second", cwd = "/tmp/first/src"),
            defaultCwd = null,
        )

        assertEquals("path:/tmp/second", candidates.first().project.projectKey)
        assertEquals(1000, candidates.first().score)
    }

    @Test
    fun worktreePathMatchesProjectBasePath() {
        val identity = identity(project("path:/tmp/worktree", "repo", "/tmp/worktree"))

        val candidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(worktreePath = "/tmp/worktree/module/src"),
            defaultCwd = null,
        )

        assertEquals("path:/tmp/worktree", candidates.first().project.projectKey)
        assertEquals(930, candidates.first().score)
    }

    @Test
    fun legacyProjectPathSelectorMatchesProjectFilePathBeforeContainingBasePath() {
        val exactProjectFile = project(
            projectKey = "path:/tmp/repo/app",
            name = "app",
            basePath = "/tmp/repo/app",
            projectFilePath = "/tmp/repo/app/.idea/misc.xml",
        )
        val containingBasePath = project(
            projectKey = "path:/tmp/repo/app/.idea",
            name = "idea-dir",
            basePath = "/tmp/repo/app/.idea",
            projectFilePath = "/tmp/repo/app/.idea/.idea/misc.xml",
        )
        val identity = identity(exactProjectFile, containingBasePath)

        val candidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(project = "/tmp/repo/app/.idea/misc.xml"),
            defaultCwd = null,
        )

        assertEquals("path:/tmp/repo/app", candidates.first().project.projectKey)
        assertEquals(900, candidates.first().score)
    }

    @Test
    fun pathSelectorsDoNotMatchSiblingPrefixes() {
        val identity = identity(project("path:/tmp/repo/app", "app", "/tmp/repo/app"))

        val candidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(worktreePath = "/tmp/repo/application/src"),
            defaultCwd = null,
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun duplicateProjectNamesRemainAmbiguous() {
        val first = identity(project("path:/tmp/one", "shared", "/tmp/one"))
        val second = identity(project("path:/tmp/two", "shared", "/tmp/two"))

        val candidates = scoreInspectionRouteCandidates(
            identities = listOf(first, second),
            selector = InspectionRouteSelector(project = "shared"),
            defaultCwd = null,
        )

        assertEquals(2, candidates.size)
        assertTrue(candidates.all { candidate -> candidate.score == 800 })
    }

    @Test
    fun cwdPrefersDeepestContainingProject() {
        val root = identity(project("path:/tmp/repo", "repo", "/tmp/repo"))
        val nested = identity(project("path:/tmp/repo/packages/app", "app", "/tmp/repo/packages/app"))

        val candidates = scoreInspectionRouteCandidates(
            identities = listOf(root, nested),
            selector = InspectionRouteSelector(cwd = "/tmp/repo/packages/app/src"),
            defaultCwd = null,
        )

        assertEquals("path:/tmp/repo/packages/app", candidates.first().project.projectKey)
    }

    private fun identity(vararg projects: InspectionRouteProject): InspectionRouteIdentity {
        return InspectionRouteIdentity(
            sessionId = "session-${projects.first().name}",
            startedAtMs = 1,
            heartbeatMs = 2,
            port = 63340,
            ideName = "Mock IDEA",
            ideVersion = "2025.1",
            ideProductCode = "IU",
            pluginVersion = "test",
            projects = projects.toList(),
        )
    }

    private fun project(
        projectKey: String,
        name: String,
        basePath: String,
        projectFilePath: String? = null,
        focused: Boolean = false,
    ): InspectionRouteProject {
        return InspectionRouteProject(
            projectKey = projectKey,
            name = name,
            basePath = basePath,
            projectFilePath = projectFilePath,
            focused = focused,
        )
    }
}
