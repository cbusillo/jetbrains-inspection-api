package com.shiny.inspectionmcp

import java.net.URI
import java.net.URL
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CopyMcpCommandActionTest {
    @Test
    fun `resolves the MCP jar from an installed plugin root`() {
        val pluginRoot = createTempDirectory("inspection-plugin")
        val jarPath = Files.createDirectories(pluginRoot.resolve("lib"))
            .resolve("jetbrains-inspection-mcp.jar")
        Files.createFile(jarPath)

        assertEquals(jarPath, resolveMcpJarPathFromPluginPath(pluginRoot))
    }

    @Test
    fun `resolves the MCP jar from a plugin archive path`() {
        val pluginRoot = createTempDirectory("inspection-plugin")
        val archivePath = Files.createDirectories(pluginRoot.resolve("lib"))
            .resolve("inspection-api.jar")
        Files.createFile(archivePath)
        val jarPath = archivePath.parent.resolve("jetbrains-inspection-mcp.jar")
        Files.createFile(jarPath)

        assertEquals(jarPath, resolveMcpJarPathFromPluginPath(archivePath))
    }

    @Test
    fun `returns null when the MCP jar is not installed`() {
        assertNull(resolveMcpJarPathFromPluginPath(createTempDirectory("inspection-plugin")))
    }

    @Test
    fun `resolves file and jar code-source URLs`() {
        val pluginJar = Files.createFile(createTempDirectory("inspection-plugin").resolve("inspection-api.jar"))
        val fileUrl = pluginJar.toUri().toURL()
        val jarUrl = URI.create("jar:${fileUrl}!/").toURL()

        assertEquals(pluginJar, resolveCodeSourcePath(fileUrl))
        assertEquals(pluginJar, resolveCodeSourcePath(jarUrl))
    }

    @Test
    fun `rejects non-file code-source URLs`() {
        assertNull(resolveCodeSourcePath(URI.create("https://example.com/inspection-api.jar").toURL()))
    }

    @Test
    fun `resolves via CODE_SOURCE strategy when codeSource is available`() {
        val pluginRoot = createTempDirectory("inspection-plugin")
        val libDir = Files.createDirectories(pluginRoot.resolve("lib"))
        val mcpJarPath = Files.createFile(libDir.resolve("jetbrains-inspection-mcp.jar"))
        val archivePath = Files.createFile(libDir.resolve("inspection-api.jar"))

        val result = resolveMcpJarResult(
            codeSourceLocationProvider = { archivePath.toUri().toURL() }
        )

        assertEquals(mcpJarPath, result.jarPath)
        assertNotNull(result.winningAttempt)
        assertEquals(McpJarStrategy.CODE_SOURCE, result.winningAttempt?.strategy)
        assertTrue(result.winningAttempt?.success == true)
        assertEquals(1, result.attempts.size)
    }

    @Test
    fun `falls back to CLASS_RESOURCE when codeSource is null`() {
        val pluginRoot = createTempDirectory("inspection-plugin")
        val libDir = Files.createDirectories(pluginRoot.resolve("lib"))
        val mcpJarPath = Files.createFile(libDir.resolve("jetbrains-inspection-mcp.jar"))
        val archivePath = Files.createFile(libDir.resolve("inspection-api.jar"))
        val resourceUrl = URI.create("jar:${archivePath.toUri()}!/com/shiny/inspectionmcp/CopyMcpCommandAction.class").toURL()

        val result = resolveMcpJarResult(
            codeSourceLocationProvider = { null },
            classResourceUrlProvider = { resourceUrl }
        )

        assertEquals(mcpJarPath, result.jarPath)
        assertNotNull(result.winningAttempt)
        assertEquals(McpJarStrategy.CLASS_RESOURCE, result.winningAttempt?.strategy)
        assertEquals(2, result.attempts.size)
        assertEquals(McpJarStrategy.CODE_SOURCE, result.attempts[0].strategy)
        assertFalse(result.attempts[0].success)
        assertEquals(McpJarStrategy.CLASS_RESOURCE, result.attempts[1].strategy)
        assertTrue(result.attempts[1].success)
    }

    @Test
    fun `resolves via CLASS_RESOURCE with custom jar handler using textual parsing when JarURLConnection is unavailable`() {
        val pluginRoot = createTempDirectory("inspection-plugin")
        val libDir = Files.createDirectories(pluginRoot.resolve("lib"))
        val mcpJarPath = Files.createFile(libDir.resolve("jetbrains-inspection-mcp.jar"))
        val archivePath = Files.createFile(libDir.resolve("inspection-api.jar"))
        val resourceUrl = URI.create("jar:${archivePath.toUri()}!/com/shiny/inspectionmcp/CopyMcpCommandAction.class").toURL()

        val result = resolveMcpJarResult(
            codeSourceLocationProvider = { null },
            classResourceUrlProvider = { resourceUrl },
            jarUrlConnectionOpener = { null }
        )

        assertEquals(mcpJarPath, result.jarPath)
        assertEquals(McpJarStrategy.CLASS_RESOURCE, result.winningAttempt?.strategy)
    }

    @Test
    fun `resolves via CLASS_RESOURCE when classes are in an exploded directory layout`() {
        val pluginRoot = createTempDirectory("inspection-plugin")
        val classesDir = Files.createDirectories(pluginRoot.resolve("classes").resolve("com").resolve("shiny").resolve("inspectionmcp"))
        val classFile = Files.createFile(classesDir.resolve("CopyMcpCommandAction.class"))
        val mcpJarPath = Files.createFile(Files.createDirectories(pluginRoot.resolve("lib")).resolve("jetbrains-inspection-mcp.jar"))

        val result = resolveMcpJarResult(
            codeSourceLocationProvider = { null },
            classResourceUrlProvider = { classFile.toUri().toURL() }
        )

        assertEquals(mcpJarPath, result.jarPath)
        assertEquals(McpJarStrategy.CLASS_RESOURCE, result.winningAttempt?.strategy)
    }

    @Test
    fun `falls back to PATH_MANAGER_CLASS when codeSource and resource URL fail`() {
        val pluginRoot = createTempDirectory("inspection-plugin")
        val libDir = Files.createDirectories(pluginRoot.resolve("lib"))
        val mcpJarPath = Files.createFile(libDir.resolve("jetbrains-inspection-mcp.jar"))
        val archivePath = Files.createFile(libDir.resolve("inspection-api.jar"))

        val result = resolveMcpJarResult(
            codeSourceLocationProvider = { null },
            classResourceUrlProvider = { null },
            pathManagerJarPathProvider = { archivePath.toString() }
        )

        assertEquals(mcpJarPath, result.jarPath)
        assertEquals(McpJarStrategy.PATH_MANAGER_CLASS, result.winningAttempt?.strategy)
        assertEquals(3, result.attempts.size)
    }

    @Test
    fun `falls back to PLUGIN_ROOT_SCAN when prior strategies fail`() {
        val systemPluginsDir = createTempDirectory("plugins")
        val pluginDir = Files.createDirectories(systemPluginsDir.resolve("jetbrains-inspection-api"))
        val mcpJarPath = Files.createFile(Files.createDirectories(pluginDir.resolve("lib")).resolve("jetbrains-inspection-mcp.jar"))

        val result = resolveMcpJarResult(
            codeSourceLocationProvider = { null },
            classResourceUrlProvider = { null },
            pathManagerJarPathProvider = { null },
            pluginsPathProvider = { systemPluginsDir }
        )

        assertEquals(mcpJarPath, result.jarPath)
        assertEquals(McpJarStrategy.PLUGIN_ROOT_SCAN, result.winningAttempt?.strategy)
        assertEquals(4, result.attempts.size)
    }

    @Test
    fun `records total failure when all strategies fail`() {
        val result = resolveMcpJarResult(
            codeSourceLocationProvider = { null },
            classResourceUrlProvider = { null },
            pathManagerJarPathProvider = { null },
            pluginsPathProvider = { null }
        )

        assertNull(result.jarPath)
        assertNull(result.winningAttempt)
        assertEquals(4, result.attempts.size)
        assertTrue(result.attempts.all { !it.success })

        assertEquals(McpJarStrategy.CODE_SOURCE, result.attempts[0].strategy)
        assertEquals(McpJarStrategy.CLASS_RESOURCE, result.attempts[1].strategy)
        assertEquals(McpJarStrategy.PATH_MANAGER_CLASS, result.attempts[2].strategy)
        assertEquals(McpJarStrategy.PLUGIN_ROOT_SCAN, result.attempts[3].strategy)
    }
}

