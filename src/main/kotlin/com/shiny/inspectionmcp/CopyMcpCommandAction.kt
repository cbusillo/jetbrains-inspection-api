package com.shiny.inspectionmcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val MCP_JAR_NAME = "jetbrains-inspection-mcp.jar"

class CopyMcpCommandAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(event: AnActionEvent) {
        copyMcpSetup(event.project)
    }
}

private enum class McpSetupKind {
    SINGLE,
    MULTI
}

private data class McpSetupOption(
    val label: String,
    val command: String,
    val kind: McpSetupKind = McpSetupKind.SINGLE
)

internal fun copyMcpSetup(project: Project?) {
    val setups = buildMcpSetupOptions() ?: run {
        Messages.showErrorDialog(
            "Unable to locate $MCP_JAR_NAME in the installed plugin. Reinstall the plugin and try again.",
            "MCP Setup"
        )
        return
    }

    val chosen = chooseMcpSetup(project, setups) ?: return
    CopyPasteManager.getInstance().setContents(StringSelection(chosen.command))
    val prefix = if (chosen.kind == McpSetupKind.MULTI) {
        "MCP setup commands copied to clipboard:"
    } else {
        "MCP setup command copied to clipboard:"
    }
    Messages.showInfoMessage(
        "$prefix\n\n${chosen.command}",
        "MCP Setup"
    )
}

private fun chooseMcpSetup(project: Project?, setups: List<McpSetupOption>): McpSetupOption? {
    val labels = setups.map { it.label }.toTypedArray()
    val selection = Messages.showDialog(
        project,
        "Select your MCP client",
        "MCP Setup",
        labels,
        0,
        null
    )
    if (selection < 0) return null
    return setups.getOrNull(selection)
}

private fun buildMcpSetupOptions(): List<McpSetupOption>? {
    val jarPath = resolveMcpJarPath() ?: return null
    val javaBin = resolveJavaBinary()
    val port = resolveIdePort()?.toString()
    val name = "inspection-jetbrains"

    val codeCommand = "code mcp add $name ${quote(javaBin)} -jar ${quote(jarPath.toString())}"
    val codexCommand = "codex mcp add $name -- ${quote(javaBin)} -jar ${quote(jarPath.toString())}"
    val claudeCommand = "claude mcp add --transport stdio $name --scope user -- ${quote(javaBin)} -jar ${quote(jarPath.toString())}"
    val geminiCommand = "gemini mcp add -s user $name ${quote(javaBin)} -jar ${quote(jarPath.toString())}"
    val fixedPortNote = port?.let { "\n\nFixed-port fallback: add --env IDE_PORT=$it to target only this IDE." } ?: ""
    val allCommands = buildString {
        appendLine("Every Code")
        appendLine(codeCommand)
        appendLine()
        appendLine("Codex CLI")
        appendLine(codexCommand)
        appendLine()
        appendLine("Claude Code")
        appendLine(claudeCommand)
        appendLine()
        appendLine("Gemini CLI")
        appendLine(geminiCommand)
        append(fixedPortNote)
    }.trim()

    return listOf(
        McpSetupOption("Every Code", codeCommand),
        McpSetupOption("Codex CLI", codexCommand),
        McpSetupOption("Claude Code", claudeCommand),
        McpSetupOption("Gemini CLI", geminiCommand),
        McpSetupOption("Copy all commands", allCommands, McpSetupKind.MULTI)
    )
}

private fun resolveMcpJarPath(): Path? {
    val codeSource = runCatching {
        CopyMcpCommandAction::class.java.protectionDomain?.codeSource?.location?.toURI()?.let(Paths::get)
    }.getOrNull() ?: return null
    val candidates = buildList {
        if (Files.isDirectory(codeSource)) {
            add(codeSource.resolve(MCP_JAR_NAME))
            codeSource.parent?.let { parent -> add(parent.resolve("lib").resolve(MCP_JAR_NAME)) }
        } else {
            codeSource.parent?.let { lib ->
                add(lib.resolve(MCP_JAR_NAME))
                lib.parent?.let { pluginRoot -> add(pluginRoot.resolve("lib").resolve(MCP_JAR_NAME)) }
            }
        }
    }
    return candidates.firstOrNull { Files.exists(it) }
}

private fun resolveJavaBinary(): String {
    val javaHome = System.getProperty("java.home") ?: return "java"
    val binName = if (SystemInfo.isWindows) "java.exe" else "java"
    val candidate = Paths.get(javaHome, "bin", binName)
    return if (Files.exists(candidate)) candidate.toString() else "java"
}

private fun quote(value: String): String {
    return if (value.any { it.isWhitespace() }) {
        "\"${value.replace("\"", "\\\"")}\""
    } else {
        value
    }
}
