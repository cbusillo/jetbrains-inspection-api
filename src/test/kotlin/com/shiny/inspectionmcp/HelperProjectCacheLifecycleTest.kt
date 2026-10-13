package com.shiny.inspectionmcp

import com.intellij.openapi.project.getProjectCacheFileName
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class HelperProjectCacheLifecycleTest {
    @TempDir lateinit var directory: Path

    private fun record(registry: HelperProjectCacheRegistry, root: Path): Path {
        val entry = HelperProjectCacheRecord().apply {
            worktreePath = root.toString()
            projectPath = root.toString()
            cacheName = getProjectCacheFileName(root)
        }
        registry.state.records.add(entry)
        return directory.resolve("system/projects").resolve(entry.cacheName).also {
            Files.createDirectories(it)
            Files.writeString(it.resolve("index.data"), "project-only index fixture")
        }
    }

    private fun lifecycle(registry: HelperProjectCacheRegistry, busy: Boolean = false) =
        HelperProjectCacheLifecycle(registry, directory.resolve("system"), { busy }, { it() })

    @Suppress("UNCHECKED_CAST")
    private fun caches(result: Map<String, Any?>) = result["caches"] as List<Map<String, Any?>>

    @Test fun `preview and apply retire only recorded closed-worktree caches`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val foreign = Files.createDirectory(directory.resolve("foreign")).toRealPath()
        val registry = HelperProjectCacheRegistry()
        val ownedCache = record(registry, root)
        val foreignCache = record(registry, foreign)
        val shared = directory.resolve("system/index/shared.data")
        Files.createDirectories(shared.parent)
        Files.writeString(shared, "shared SDK index")
        val restored = HelperProjectCacheRegistry().apply {
            loadState(XmlSerializer.deserialize(XmlSerializer.serialize(registry.state), HelperProjectCacheState::class.java))
        }
        val engine = lifecycle(restored)
        val preview = engine.retire(root, true)
        assertEquals("would_remove", caches(preview).single()["status"])
        assertEquals(Files.size(ownedCache.resolve("index.data")), caches(preview).single()["bytes"])
        assertTrue(Files.exists(ownedCache))

        val applied = engine.retire(root, false)
        assertEquals("removed", caches(applied).single()["status"])
        assertFalse(Files.exists(ownedCache))
        assertEquals("project-only index fixture", Files.readString(foreignCache.resolve("index.data")))
        assertEquals("shared SDK index", Files.readString(shared))
        assertEquals(listOf(foreignCache), Files.list(ownedCache.parent).use { it.toList() })
        assertEquals(listOf(foreign.toString()), restored.state.records.map { it.worktreePath })
        assertTrue(caches(engine.retire(root, false)).isEmpty())
    }

    @Test fun `active worktree and unrecorded paths cannot retire caches`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val registry = HelperProjectCacheRegistry()
        val cache = record(registry, root)
        assertEquals("project_in_use", caches(lifecycle(registry, true).retire(root, false)).single()["reason"])
        assertTrue(Files.exists(cache))
        assertTrue(caches(lifecycle(HelperProjectCacheRegistry()).retire(root, false)).isEmpty())
        assertTrue(Files.exists(cache))
    }

    @Test fun `tampered cache names and symlinks preserve foreign contents`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val registry = HelperProjectCacheRegistry()
        val cache = record(registry, root)
        registry.state.records.single().cacheName = "../foreign"
        assertEquals("ownership_mismatch", caches(lifecycle(registry).retire(root, false)).single()["reason"])
        registry.state.records.single().cacheName = cache.fileName.toString()
        Files.delete(cache.resolve("index.data"))
        Files.delete(cache)
        val foreign = Files.createDirectory(directory.resolve("foreign"))
        Files.writeString(foreign.resolve("preserve"), "foreign data")
        Files.createSymbolicLink(cache, foreign)
        assertEquals("cache_path_symlink", caches(lifecycle(registry).retire(root, false)).single()["reason"])
        assertEquals("foreign data", Files.readString(foreign.resolve("preserve")))
    }

    @Test fun `retained quarantine is removable without deleting a recreated cache`() {
        val root = Files.createDirectory(directory.resolve("task")).toRealPath()
        val registry = HelperProjectCacheRegistry()
        val cache = record(registry, root)
        val quarantine = cache.resolveSibling(".inspection-retired-${UUID.randomUUID()}")
        Files.move(cache, quarantine)
        registry.state.records.single().quarantineName = quarantine.fileName.toString()
        Files.createDirectories(cache)
        Files.writeString(cache.resolve("new"), "new instance data")
        assertEquals("would_remove", caches(lifecycle(registry).retire(root, true)).single()["status"])
        assertTrue(Files.exists(quarantine))
        assertEquals("removed", caches(lifecycle(registry).retire(root, false)).single()["status"])
        assertFalse(Files.exists(quarantine))
        assertEquals("new instance data", Files.readString(cache.resolve("new")))
    }
}
