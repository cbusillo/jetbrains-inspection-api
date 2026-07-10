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
            selector = InspectionRouteSelector(worktreePath = "/tmp/worktree"),
            defaultCwd = null,
        )

        assertEquals("path:/tmp/worktree", candidates.first().project.projectKey)
        assertEquals(950, candidates.first().score)
    }

    @Test
    fun explicitProjectAndWorktreePathsDoNotMatchNestedDirectories() {
        val identity = identity(project("path:/tmp/worktree", "repo", "/tmp/worktree"))

        val projectPathCandidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(projectPath = "/tmp/worktree/module/src"),
            defaultCwd = null,
        )
        val worktreePathCandidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(worktreePath = "/tmp/worktree/module/src"),
            defaultCwd = null,
        )

        assertTrue(projectPathCandidates.isEmpty())
        assertTrue(worktreePathCandidates.isEmpty())
    }

    @Test
    fun invalidExplicitPathsDoNotMatchPathlessProjects() {
        val identity = identity(
            project(
                projectKey = "name:pathless",
                name = "pathless",
                basePath = null,
                projectFilePath = null,
            )
        )

        val projectPathCandidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(projectPath = "not-a-path"),
            defaultCwd = null,
        )
        val worktreePathCandidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(worktreePath = "not-a-path"),
            defaultCwd = null,
        )

        assertTrue(projectPathCandidates.isEmpty())
        assertTrue(worktreePathCandidates.isEmpty())
    }

    @Test
    fun projectPathMatchesReportedProjectFilePath() {
        val identity = identity(
            project(
                projectKey = "path:/tmp/worktree",
                name = "repo",
                basePath = "/tmp/worktree",
                projectFilePath = "/tmp/worktree/.idea/misc.xml",
            )
        )

        val candidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(projectPath = "/tmp/worktree/.idea/misc.xml"),
            defaultCwd = null,
        )

        assertEquals("path:/tmp/worktree", candidates.first().project.projectKey)
        assertEquals(950, candidates.first().score)
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
    fun worktreePathMatchesProjectFileRootWhenBasePathIsMissing() {
        val project = project(
            projectKey = "file:/tmp/worktree/.idea/modules.xml",
            name = "repo",
            basePath = null,
            projectFilePath = "/tmp/worktree/.idea/modules.xml",
        )
        val identity = identity(project)

        val candidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(worktreePath = "/tmp/worktree"),
            defaultCwd = null,
        )

        assertEquals("file:/tmp/worktree/.idea/modules.xml", candidates.first().project.projectKey)
        assertEquals(950, candidates.first().score)
    }

    @Test
    fun worktreePathMatchesNestedIdeaProjectFileRootWhenBasePathIsMissing() {
        val project = project(
            projectKey = "file:/tmp/worktree/.idea/runConfigurations/app.xml",
            name = "repo",
            basePath = null,
            projectFilePath = "/tmp/worktree/.idea/runConfigurations/app.xml",
        )
        val identity = identity(project)

        val candidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(worktreePath = "/tmp/worktree"),
            defaultCwd = null,
        )

        assertEquals("file:/tmp/worktree/.idea/runConfigurations/app.xml", candidates.first().project.projectKey)
        assertEquals(950, candidates.first().score)
    }

    @Test
    fun nestedCwdMatchesProjectFileRootWhenBasePathIsMissing() {
        val project = project(
            projectKey = "file:/tmp/worktree/.idea/misc.xml",
            name = "repo",
            basePath = null,
            projectFilePath = "/tmp/worktree/.idea/misc.xml",
        )
        val identity = identity(project)

        val candidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(cwd = "/tmp/worktree/src/main"),
            defaultCwd = null,
        )

        assertEquals("file:/tmp/worktree/.idea/misc.xml", candidates.first().project.projectKey)
        assertTrue(candidates.first().score >= 700)
    }

    @Test
    fun nestedCwdPrefersDerivedProjectFileRootOverContainingBasePath() {
        val parent = project(
            projectKey = "path:/tmp/repo",
            name = "repo",
            basePath = "/tmp/repo",
            projectFilePath = "/tmp/repo/.idea/misc.xml",
        )
        val child = project(
            projectKey = "file:/tmp/repo/packages/app/.idea/misc.xml",
            name = "app",
            basePath = null,
            projectFilePath = "/tmp/repo/packages/app/.idea/misc.xml",
        )
        val identity = identity(parent, child)

        val candidates = scoreInspectionRouteCandidates(
            identities = listOf(identity),
            selector = InspectionRouteSelector(cwd = "/tmp/repo/packages/app/src/main"),
            defaultCwd = null,
        )

        assertEquals("file:/tmp/repo/packages/app/.idea/misc.xml", candidates.first().project.projectKey)
        assertTrue(candidates.first().score >= 700)
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

    @Test
    fun explicitCwdPrecedesProjectNameButDefaultCwdDoesNot() {
        val cwdProject = identity(project("path:/tmp/cwd", "cwd-project", "/tmp/cwd"))
        val namedProject = identity(project("path:/tmp/named", "named-project", "/tmp/named"))

        val explicitCwdCandidates = scoreInspectionRouteCandidates(
            identities = listOf(cwdProject, namedProject),
            selector = InspectionRouteSelector(project = "named-project", cwd = "/tmp/cwd/src"),
            defaultCwd = null,
        )
        val defaultCwdCandidates = scoreInspectionRouteCandidates(
            identities = listOf(cwdProject, namedProject),
            selector = InspectionRouteSelector(project = "named-project"),
            defaultCwd = "/tmp/cwd/src",
        )

        assertEquals("path:/tmp/cwd", explicitCwdCandidates.first().project.projectKey)
        assertEquals("path:/tmp/named", defaultCwdCandidates.first().project.projectKey)
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
        basePath: String?,
        projectFilePath: String? = null,
        focused: Boolean = false,
    ): InspectionRouteProject {
        return InspectionRouteProject(
            projectKey = projectKey,
            name = name,
            basePath = basePath,
            projectFilePath = projectFilePath,
            focused = focused,
            projectInstanceId = "instance:$projectKey",
        )
    }
}
