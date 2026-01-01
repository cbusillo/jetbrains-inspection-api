package com.shiny.inspectionmcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val PLUGIN_ID = "com.shiny.inspection.api"
private const val MCP_JAR_NAME = "jetbrains-inspection-mcp.jar"
private const val DEFAULT_PORT = "63341"

class CopyMcpCommandAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(event: AnActionEvent) {
        val command = buildMcpCommand()
        if (command == null) {
            Messages.showErrorDialog(
                "Unable to locate $MCP_JAR_NAME in the installed plugin. Reinstall the plugin and try again.",
                "MCP Setup"
            )
            return
        }

        CopyPasteManager.getInstance().setContents(StringSelection(command))
        Messages.showInfoMessage(
            "MCP setup command copied to clipboard:\n\n$command",
            "MCP Setup"
        )
    }
}

internal fun buildMcpCommand(): String? {
    val jarPath = resolveMcpJarPath() ?: return null
    val javaBin = resolveJavaBinary()
    val port = resolveIdePort()?.toString() ?: DEFAULT_PORT
    val name = defaultMcpName()
    return "code mcp add --env IDE_PORT=$port $name ${quote(javaBin)} -jar ${quote(jarPath.toString())}"
}

private fun resolveMcpJarPath(): Path? {
    val descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID)) ?: return null
    val pluginPath = descriptor.pluginPath
    val basePath = if (Files.isDirectory(pluginPath)) pluginPath else pluginPath.parent
    val candidate = basePath.resolve("lib").resolve(MCP_JAR_NAME)
    return if (Files.exists(candidate)) candidate else null
}

private fun resolveJavaBinary(): String {
    val javaHome = System.getProperty("java.home") ?: return "java"
    val binName = if (SystemInfo.isWindows) "java.exe" else "java"
    val candidate = Paths.get(javaHome, "bin", binName)
    return if (Files.exists(candidate)) candidate.toString() else "java"
}

private fun resolveIdePort(): Int? {
    val managerPort = resolvePortFromClasses(
        listOf(
            "com.intellij.ide.BuiltInServerManager",
            "org.jetbrains.ide.BuiltInServerManager"
        ),
        listOf("getPort")
    )
    if (managerPort != null && managerPort > 0) return managerPort

    val optionsPort = resolvePortFromClasses(
        listOf(
            "com.intellij.ide.BuiltInServerOptions",
            "org.jetbrains.ide.BuiltInServerOptions"
        ),
        listOf("getPort", "getBuiltInServerPort")
    )
    return optionsPort?.takeIf { it > 0 }
}

private fun resolvePortFromClasses(classNames: List<String>, methodNames: List<String>): Int? {
    for (className in classNames) {
        val port = resolvePortFromClass(className, methodNames)
        if (port != null && port > 0) return port
    }
    return null
}

private fun resolvePortFromClass(className: String, methodNames: List<String>): Int? {
    return try {
        val clazz = Class.forName(className)
        val instance = clazz.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }
            ?.invoke(null)
            ?: clazz.fields.firstOrNull { it.name == "INSTANCE" }?.get(null)
        for (methodName in methodNames) {
            val getter = clazz.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: continue
            val port = getter.invoke(instance) as? Int
            if (port != null && port > 0) return port
        }
        null
    } catch (_: Throwable) {
        null
    }
}

private fun defaultMcpName(): String {
    val product = ApplicationNamesInfo.getInstance().productName
    val normalized = product.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim { it == '-' }
    return if (normalized.isNotEmpty()) "inspection-$normalized" else "inspection-ide"
}

private fun quote(value: String): String {
    return if (value.any { it.isWhitespace() }) {
        "\"${value.replace("\"", "\\\"")}\""
    } else {
        value
    }
}
