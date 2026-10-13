package com.shiny.inspectionmcp

import com.google.gson.Gson
import com.intellij.openapi.project.getProjectCacheFileName
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class HelperProjectCacheLifecycleTest {
    @TempDir lateinit var directory: Path

    private fun record(root: Path): Path {
        val cache = directory.resolve("system/projects").resolve(getProjectCacheFileName(root))
        Files.createDirectories(cache)
        Files.writeString(cache.resolve("index.data"), "project-only index fixture")
        writeMarker(cache, root)
        return cache
    }

    private fun writeMarker(cache: Path, root: Path, name: String = cache.fileName.toString()) {
        Files.writeString(cache.resolve(HELPER_PROJECT_CACHE_MARKER), Gson().toJson(mapOf(
            "worktree_path" to root.toString(), "project_path" to root.toString(),
            "cache_name" to name, "ownership_id" to UUID.randomUUID().toString(),
        )))
    }

    private fun lifecycle(registry: HelperProjectCacheRegistry = HelperProjectCacheRegistry(), busy: Boolean = false) =
        HelperProjectCacheLifecycle(registry, directory.resolve("system"), { busy }, { it() })

    @Suppress("UNCHECKED_CAST")
    private fun caches(result: Map<String, Any?>) = result["caches"] as List<Map<String, Any?>>

    @Test fun `preview and apply retire only durable recorded closed-worktree caches`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val foreign = Files.createDirectory(directory.resolve("foreign")).toRealPath()
        val ownedCache = record(root)
        val foreignCache = record(foreign)
        val shared = directory.resolve("system/index/shared.data")
        Files.createDirectories(shared.parent)
        Files.writeString(shared, "shared SDK index")
        val engine = lifecycle()
        val preview = engine.retire(root, true)
        assertEquals("would_remove", caches(preview).single()["status"])
        val actualBytes = Files.walk(ownedCache).use { files -> files.filter(Files::isRegularFile).mapToLong(Files::size).sum() }
        assertEquals(actualBytes, caches(preview).single()["bytes"])
        assertTrue(Files.exists(ownedCache))
        val applied = engine.retire(root, false)
        assertEquals("removed", caches(applied).single()["status"])
        assertFalse(Files.exists(ownedCache))
        assertEquals("project-only index fixture", Files.readString(foreignCache.resolve("index.data")))
        assertEquals("shared SDK index", Files.readString(shared))
        assertEquals(listOf(foreignCache), Files.list(ownedCache.parent).use { it.toList() })
        assertTrue(caches(lifecycle().retire(root, false)).isEmpty())
    }

    @Test fun `active worktree and unrecorded paths cannot retire caches`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val cache = record(root)
        assertEquals("project_in_use", caches(lifecycle(busy = true).retire(root, false)).single()["reason"])
        assertTrue(Files.exists(cache))
        Files.delete(cache.resolve(HELPER_PROJECT_CACHE_MARKER))
        assertTrue(caches(lifecycle().retire(root, false)).isEmpty())
        assertTrue(Files.exists(cache))
    }

    @Test fun `tampered cache names and symlinks preserve foreign contents`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val cache = record(root)
        writeMarker(cache, root, "../foreign")
        assertEquals("ownership_mismatch", caches(lifecycle().retire(root, false)).single()["reason"])
        writeMarker(cache, root)
        val foreign = directory.resolve("foreign")
        Files.move(cache, foreign)
        Files.writeString(foreign.resolve("preserve"), "foreign data")
        Files.createSymbolicLink(cache, foreign)
        assertTrue(caches(lifecycle().retire(root, false)).any { it["reason"] == "cache_path_symlink" })
        assertEquals("foreign data", Files.readString(foreign.resolve("preserve")))
    }

    @Test fun `failed deletion retains a durable marker for a new registry to recover`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val cache = record(root)
        assumeTrue(Files.getFileStore(cache).supportsFileAttributeView("posix"))
        val permissions = Files.getPosixFilePermissions(cache)
        var quarantine: Path? = null
        try {
            Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("r-x------"))
            val result = lifecycle().retire(root, false)
            val receipt = caches(result).single()
            assertEquals("cache_removal_failed", receipt["reason"])
            quarantine = Path.of(receipt["quarantine_path"] as String)
            assertTrue(Files.exists(quarantine.resolve(HELPER_PROJECT_CACHE_MARKER)))
            assertFalse(Files.exists(cache))
        } finally { Files.setPosixFilePermissions(quarantine ?: cache, permissions) }
        assertEquals("removed", caches(lifecycle().retire(root, false)).single()["status"])
    }

    @Test fun `restart recovers partially deleted quarantine without deleting recreated cache`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val cache = record(root)
        val quarantine = cache.resolveSibling(".inspection-retired-${UUID.randomUUID()}")
        Files.move(cache, quarantine)
        Files.delete(quarantine.resolve("index.data"))
        Files.createDirectories(cache)
        Files.writeString(cache.resolve("new"), "new instance data")
        assertEquals("would_remove", caches(lifecycle().retire(root, true)).single()["status"])
        assertTrue(Files.exists(quarantine))
        assertEquals("removed", caches(lifecycle().retire(root, false)).single()["status"])
        assertFalse(Files.exists(quarantine))
        assertEquals("new instance data", Files.readString(cache.resolve("new")))
    }

    @Test fun `EDT timeout and rename failure refuse retirement and preserve ownership`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val cache = record(root)
        for ((exception, reason) in listOf(TimeoutException() to "cache_retirement_busy",
            ExecutionException(IOException("rename failure")) to "cache_move_failed")) {
            val engine = HelperProjectCacheLifecycle(HelperProjectCacheRegistry(), directory.resolve("system"), { false }, { throw exception })
            val result = engine.retire(root, false)
            assertEquals("refused", result["status"])
            assertEquals(reason, result["reason"])
            assertTrue(Files.exists(cache.resolve(HELPER_PROJECT_CACHE_MARKER)))
        }
        assertEquals("removed", caches(lifecycle().retire(root, false)).single()["status"])
    }

    @Test fun `concurrent retirement reserves one worktree until its operation finishes`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val cache = record(root)
        val registry = HelperProjectCacheRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = HelperProjectCacheLifecycle(registry, directory.resolve("system"), { false }, { action ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            action()
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<Map<String, Any?>> { engine.retire(root, false) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val second = engine.retire(root, false)
            assertEquals("refused", second["status"])
            assertEquals("cache_retirement_busy", second["reason"])
            assertTrue(Files.exists(cache))
            release.countDown()
            assertEquals("removed", caches(first.get(10, TimeUnit.SECONDS)).single()["status"])
            assertFalse(Files.exists(cache))
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
