package com.shiny.inspectionmcp

import java.nio.file.Files
import java.net.URI
import java.net.URL
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
