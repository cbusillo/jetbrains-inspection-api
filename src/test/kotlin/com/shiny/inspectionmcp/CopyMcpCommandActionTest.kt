package com.shiny.inspectionmcp

import java.nio.file.Files
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
}
