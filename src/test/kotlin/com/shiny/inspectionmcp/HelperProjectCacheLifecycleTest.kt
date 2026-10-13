package com.shiny.inspectionmcp

import com.google.gson.JsonParser
import com.intellij.openapi.project.getProjectCacheFileName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class HelperProjectCacheLifecycleTest {
    @TempDir lateinit var directory: Path

    private fun record(registry: HelperProjectCacheRegistry, root: Path): Path {
        val projects = directory.resolve("system/projects")
        val original = projects.resolve(getProjectCacheFileName(root))
        Files.createDirectories(original)
        Files.writeString(original.resolve("index.data"), "project-only index fixture")
        registry.sealClosedCache(original, root, root.toString())
        return registry.records(projects, root).single().directory
    }

    private fun lifecycle(registry: HelperProjectCacheRegistry = HelperProjectCacheRegistry()) =
        HelperProjectCacheLifecycle(registry, directory.resolve("system"))

    @Suppress("UNCHECKED_CAST")
    private fun caches(result: Map<String, Any?>) = result["caches"] as List<Map<String, Any?>>

    @Test fun `preview and apply retire only durable sealed closed-worktree caches`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val foreign = Files.createDirectory(directory.resolve("foreign")).toRealPath()
        val registry = HelperProjectCacheRegistry()
        val ownedCache = record(registry, root)
        val foreignCache = record(registry, foreign)
        val shared = directory.resolve("system/index/shared.data")
        Files.createDirectories(shared.parent)
        Files.writeString(shared, "shared SDK index")
        val engine = lifecycle()
        val preview = engine.retire(root, true)
        assertEquals("would_remove", caches(preview).single()["status"])
        val actualBytes = Files.walk(ownedCache).use { files -> files.filter(Files::isRegularFile).mapToLong(Files::size).sum() }
        assertEquals(actualBytes, caches(preview).single()["bytes"])
        assertTrue(Files.exists(ownedCache))
        assertEquals("removed", caches(engine.retire(root, false)).single()["status"])
        assertFalse(Files.exists(ownedCache))
        assertEquals("project-only index fixture", Files.readString(foreignCache.resolve("index.data")))
        assertEquals("shared SDK index", Files.readString(shared))
        assertEquals(listOf(foreignCache), Files.list(ownedCache.parent).use { it.toList() })
        assertTrue(caches(lifecycle().retire(root, false)).isEmpty())
    }

    @Test fun `unsealed and unrecorded caches are preserved even with an ownership marker`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val registry = HelperProjectCacheRegistry()
        val sealed = record(registry, root)
        val original = sealed.resolveSibling(getProjectCacheFileName(root))
        Files.move(sealed, original)
        assertTrue(caches(lifecycle().retire(root, false)).isEmpty())
        assertEquals("project-only index fixture", Files.readString(original.resolve("index.data")))
        Files.delete(original.resolve(HELPER_PROJECT_CACHE_MARKER))
        assertTrue(caches(lifecycle().retire(root, false)).isEmpty())
        assertTrue(Files.exists(original))
    }

    @Test fun `tampered cache names and symlinks preserve foreign contents`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val cache = record(HelperProjectCacheRegistry(), root)
        val marker = cache.resolve(HELPER_PROJECT_CACHE_MARKER)
        val originalMarker = Files.readString(marker)
        val json = JsonParser.parseString(originalMarker).asJsonObject
        json.addProperty("cache_name", "../foreign")
        Files.writeString(marker, json.toString())
        assertEquals("ownership_mismatch", caches(lifecycle().retire(root, false)).single()["reason"])
        Files.writeString(marker, originalMarker)
        val foreign = directory.resolve("foreign")
        Files.move(cache, foreign)
        Files.writeString(foreign.resolve("preserve"), "foreign data")
        Files.createSymbolicLink(cache, foreign)
        assertEquals("cache_path_symlink", caches(lifecycle().retire(root, false)).single()["reason"])
        assertEquals("foreign data", Files.readString(foreign.resolve("preserve")))
    }

    @Test fun `failed deletion retains a durable marker for a new registry to recover`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val cache = record(HelperProjectCacheRegistry(), root)
        assumeTrue(Files.getFileStore(cache).supportsFileAttributeView("posix"))
        val blockedDirectory = Files.createDirectory(cache.resolve("locked"))
        Files.move(cache.resolve("index.data"), blockedDirectory.resolve("index.data"))
        val permissions = Files.getPosixFilePermissions(blockedDirectory)
        try {
            Files.setPosixFilePermissions(blockedDirectory, PosixFilePermissions.fromString("r-x------"))
            val receipt = caches(lifecycle().retire(root, false)).single()
            assertEquals("cache_removal_failed", receipt["reason"])
            assertTrue(Files.exists(cache.resolve(HELPER_PROJECT_CACHE_MARKER)))
        } finally { Files.setPosixFilePermissions(blockedDirectory, permissions) }
        assertEquals("removed", caches(lifecycle().retire(root, false)).single()["status"])
    }

    @Test fun `restart recovers partial deletion without deleting a recreated cache`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val cache = record(HelperProjectCacheRegistry(), root)
        Files.delete(cache.resolve("index.data"))
        val reopened = cache.resolveSibling(getProjectCacheFileName(root))
        Files.createDirectories(reopened)
        Files.writeString(reopened.resolve("new"), "new instance data")
        assertEquals("would_remove", caches(lifecycle().retire(root, true)).single()["status"])
        assertEquals("removed", caches(lifecycle().retire(root, false)).single()["status"])
        assertFalse(Files.exists(cache))
        assertEquals("new instance data", Files.readString(reopened.resolve("new")))
    }

    @Test fun `non-writable parent and restart during finalization preserve recoverable ownership`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val registry = HelperProjectCacheRegistry()
        val cache = record(registry, root)
        assumeTrue(Files.getFileStore(cache).supportsFileAttributeView("posix"))
        val projects = cache.parent
        val permissions = Files.getPosixFilePermissions(projects)
        try {
            Files.setPosixFilePermissions(projects, PosixFilePermissions.fromString("r-x------"))
            assertEquals("cache_removal_failed", caches(lifecycle().retire(root, false)).single()["reason"])
            assertTrue(Files.exists(cache.resolve(HELPER_PROJECT_CACHE_MARKER)))
        } finally { Files.setPosixFilePermissions(projects, permissions) }
        val proof = registry.records(projects, root).single()
        Files.move(proof.marker, proof.finalizationMarker)
        assertEquals("would_remove", caches(lifecycle().retire(root, true)).single()["status"])
        assertEquals("removed", caches(lifecycle().retire(root, false)).single()["status"])
        assertFalse(Files.exists(cache))
        assertFalse(Files.exists(proof.finalizationMarker))
    }

    @Test fun `an in-flight retirement reservation refuses another request and releases for recovery`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val registry = HelperProjectCacheRegistry()
        val cache = record(registry, root)
        assertTrue(registry.begin(root))
        try {
            val result = lifecycle(registry).retire(root, false)
            assertEquals("refused", result["status"])
            assertEquals("cache_retirement_busy", result["reason"])
            assertTrue(Files.exists(cache))
        } finally { registry.end(root) }
        assertEquals("removed", caches(lifecycle(registry).retire(root, false)).single()["status"])
    }
}
