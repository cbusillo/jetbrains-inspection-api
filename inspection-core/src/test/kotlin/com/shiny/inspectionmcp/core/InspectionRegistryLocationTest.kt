package com.shiny.inspectionmcp.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class InspectionRegistryLocationTest {
    @Test
    fun `explicit registry directory wins over every platform default`() {
        val resolved = registryDir(
            environment = mapOf(INSPECTION_REGISTRY_DIR_ENV to "  /custom/registry  ", "XDG_CACHE_HOME" to "/xdg"),
            osName = "Linux",
        )

        assertEquals(Paths.get("/custom/registry"), resolved)
    }

    @Test
    fun `blank registry directory override is ignored`() {
        val resolved = registryDir(environment = mapOf(INSPECTION_REGISTRY_DIR_ENV to "   "), osName = "Mac OS X")

        assertTrue(resolved.startsWith(Paths.get(USER_HOME, "Library", "Caches")), resolved.toString())
    }

    @Test
    fun `each platform resolves under its own cache root`() {
        val cacheRoots = listOf(
            registryDir(osName = "Mac OS X") to Paths.get(USER_HOME, "Library", "Caches"),
            registryDir(osName = "Windows 11", environment = mapOf("LOCALAPPDATA" to "/local-app-data")) to
                Paths.get("/local-app-data"),
            registryDir(osName = "Windows 11") to Paths.get(USER_HOME, "AppData", "Local"),
            registryDir(osName = "Linux", environment = mapOf("XDG_CACHE_HOME" to "/xdg")) to Paths.get("/xdg"),
            registryDir(osName = "Linux") to Paths.get(USER_HOME, ".cache"),
        )

        cacheRoots.forEach { (resolved, cacheRoot) ->
            assertTrue(resolved.startsWith(cacheRoot), "$resolved is not under $cacheRoot")
        }
        val locationsBelowCacheRoot = cacheRoots.map { (resolved, cacheRoot) -> cacheRoot.relativize(resolved) }.toSet()
        assertEquals(1, locationsBelowCacheRoot.size, locationsBelowCacheRoot.toString())
    }

    private fun registryDir(environment: Map<String, String> = emptyMap(), osName: String): Path {
        val properties = mapOf("user.home" to USER_HOME, "os.name" to osName)
        return inspectionRegistryInstancesDir(environment::get, properties::get)
    }

    private companion object {
        const val USER_HOME = "/home/tester"
    }
}
