package com.shiny.inspectionmcp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import java.awt.datatransfer.StringSelection
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.asSequence

private const val MCP_JAR_NAME = "jetbrains-inspection-mcp.jar"
private val LOG = Logger.getInstance(CopyMcpCommandAction::class.java)

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

internal enum class McpJarStrategy {
    CODE_SOURCE,
    CLASS_RESOURCE,
    PATH_MANAGER_CLASS,
    PLUGIN_ROOT_SCAN
}

internal data class StrategyAttempt(
    val strategy: McpJarStrategy,
    val detail: String,
    val resolvedPath: Path?,
    val success: Boolean
)

internal data class McpJarDiscoveryResult(
    val jarPath: Path?,
    val attempts: List<StrategyAttempt>
) {
    val winningAttempt: StrategyAttempt?
        get() = attempts.firstOrNull { it.success }
}

internal fun copyMcpSetup(project: Project?) {
    val discoveryResult = resolveMcpJarResult()
    val jarPath = discoveryResult.jarPath
    if (jarPath == null) {
        val diagnostics = discoveryResult.attempts.joinToString("\n") { attempt ->
            "• [${attempt.strategy}] ${attempt.detail}"
        }
        LOG.warn("Unable to locate $MCP_JAR_NAME across all discovery strategies:\n$diagnostics")
        Messages.showErrorDialog(
            "Unable to locate $MCP_JAR_NAME in the installed plugin.\n\nDiagnostic attempts:\n$diagnostics\n\nReinstall the plugin and try again.",
            "MCP Setup"
        )
        return
    }

    discoveryResult.winningAttempt?.let { winning ->
        LOG.info("Resolved $MCP_JAR_NAME via ${winning.strategy}: ${winning.resolvedPath}")
    }

    val setups = buildMcpSetupOptions(jarPath) ?: run {
        Messages.showErrorDialog(
            "Unable to configure $MCP_JAR_NAME options.",
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

private fun buildMcpSetupOptions(jarPath: Path): List<McpSetupOption>? {
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

internal fun resolveMcpJarPath(): Path? {
    return resolveMcpJarResult().jarPath
}

internal fun resolveMcpJarResult(
    codeSourceLocationProvider: () -> URL? = {
        CopyMcpCommandAction::class.java.protectionDomain?.codeSource?.location
    },
    classResourceUrlProvider: () -> URL? = {
        CopyMcpCommandAction::class.java.getResource("CopyMcpCommandAction.class")
            ?: CopyMcpCommandAction::class.java.getResource("/com/shiny/inspectionmcp/CopyMcpCommandAction.class")
    },
    pathManagerJarPathProvider: () -> String? = {
        runCatching { PathManager.getJarPathForClass(CopyMcpCommandAction::class.java) }.getOrNull()
    },
    pluginsPathProvider: () -> Path? = {
        runCatching { Paths.get(PathManager.getPluginsPath()) }.getOrNull()
    },
    jarUrlConnectionOpener: (URL) -> URL? = { url ->
        runCatching { (url.openConnection() as? JarURLConnection)?.jarFileURL }.getOrNull()
    }
): McpJarDiscoveryResult {
    val attempts = mutableListOf<StrategyAttempt>()

    // 1. ProtectionDomain codeSource
    val codeSourceLocation = codeSourceLocationProvider()
    if (codeSourceLocation == null) {
        attempts.add(
            StrategyAttempt(
                McpJarStrategy.CODE_SOURCE,
                "ProtectionDomain codeSource location is null",
                null,
                false
            )
        )
    } else {
        val codeSourcePath = resolveCodeSourcePath(codeSourceLocation)
        if (codeSourcePath == null) {
            attempts.add(
                StrategyAttempt(
                    McpJarStrategy.CODE_SOURCE,
                    "Failed to resolve Path from codeSource location: $codeSourceLocation",
                    null,
                    false
                )
            )
        } else {
            val jarPath = resolveMcpJarPathFromCodeSource(codeSourcePath)
                ?: resolveMcpJarPathFromPluginPath(codeSourcePath)
            if (jarPath != null) {
                attempts.add(
                    StrategyAttempt(
                        McpJarStrategy.CODE_SOURCE,
                        "Found $MCP_JAR_NAME via codeSource location $codeSourceLocation",
                        jarPath,
                        true
                    )
                )
                return McpJarDiscoveryResult(jarPath, attempts)
            } else {
                attempts.add(
                    StrategyAttempt(
                        McpJarStrategy.CODE_SOURCE,
                        "codeSource path $codeSourcePath does not contain $MCP_JAR_NAME",
                        null,
                        false
                    )
                )
            }
        }
    }

    // 2. Action class resource URL
    val resourceUrl = classResourceUrlProvider()
    if (resourceUrl == null) {
        attempts.add(
            StrategyAttempt(
                McpJarStrategy.CLASS_RESOURCE,
                "Action class resource URL is null",
                null,
                false
            )
        )
    } else {
        val resourceBasePath = parseResourceUrlToPath(resourceUrl, jarUrlConnectionOpener)
        if (resourceBasePath == null) {
            attempts.add(
                StrategyAttempt(
                    McpJarStrategy.CLASS_RESOURCE,
                    "Failed to parse resource base Path from URL: $resourceUrl",
                    null,
                    false
                )
            )
        } else {
            val jarPath = resolveMcpJarPathFromCodeSource(resourceBasePath)
                ?: resolveMcpJarPathFromPluginPath(resourceBasePath)
            if (jarPath != null) {
                attempts.add(
                    StrategyAttempt(
                        McpJarStrategy.CLASS_RESOURCE,
                        "Found $MCP_JAR_NAME via class resource URL $resourceUrl",
                        jarPath,
                        true
                    )
                )
                return McpJarDiscoveryResult(jarPath, attempts)
            } else {
                attempts.add(
                    StrategyAttempt(
                        McpJarStrategy.CLASS_RESOURCE,
                        "Class resource base path $resourceBasePath does not contain $MCP_JAR_NAME",
                        null,
                        false
                    )
                )
            }
        }
    }

    // 3. PathManager getJarPathForClass
    val pathManagerJarPath = pathManagerJarPathProvider()
    if (pathManagerJarPath.isNullOrBlank()) {
        attempts.add(
            StrategyAttempt(
                McpJarStrategy.PATH_MANAGER_CLASS,
                "PathManager.getJarPathForClass returned null or blank",
                null,
                false
            )
        )
    } else {
        val pathManagerPath = runCatching { Paths.get(pathManagerJarPath) }.getOrNull()
        if (pathManagerPath == null) {
            attempts.add(
                StrategyAttempt(
                    McpJarStrategy.PATH_MANAGER_CLASS,
                    "Failed to convert PathManager jar path string to Path: $pathManagerJarPath",
                    null,
                    false
                )
            )
        } else {
            val jarPath = resolveMcpJarPathFromCodeSource(pathManagerPath)
                ?: resolveMcpJarPathFromPluginPath(pathManagerPath)
            if (jarPath != null) {
                attempts.add(
                    StrategyAttempt(
                        McpJarStrategy.PATH_MANAGER_CLASS,
                        "Found $MCP_JAR_NAME via PathManager class path: $pathManagerJarPath",
                        jarPath,
                        true
                    )
                )
                return McpJarDiscoveryResult(jarPath, attempts)
            } else {
                attempts.add(
                    StrategyAttempt(
                        McpJarStrategy.PATH_MANAGER_CLASS,
                        "PathManager path $pathManagerPath does not contain $MCP_JAR_NAME",
                        null,
                        false
                    )
                )
            }
        }
    }

    // 4. PathManager getPluginsPath & bounded plugin-root scan
    val pluginsPath = pluginsPathProvider()
    if (pluginsPath == null || !Files.isDirectory(pluginsPath)) {
        attempts.add(
            StrategyAttempt(
                McpJarStrategy.PLUGIN_ROOT_SCAN,
                "Plugins path is null or not a directory: $pluginsPath",
                null,
                false
            )
        )
    } else {
        val scannedJarPath = scanPluginsDirForMcpJar(pluginsPath)
        if (scannedJarPath != null) {
            attempts.add(
                StrategyAttempt(
                    McpJarStrategy.PLUGIN_ROOT_SCAN,
                    "Found $MCP_JAR_NAME during bounded plugin-root scan under $pluginsPath",
                    scannedJarPath,
                    true
                )
            )
            return McpJarDiscoveryResult(scannedJarPath, attempts)
        } else {
            attempts.add(
                StrategyAttempt(
                    McpJarStrategy.PLUGIN_ROOT_SCAN,
                    "Scanned subdirectories under $pluginsPath but did not find $MCP_JAR_NAME",
                    null,
                    false
                )
            )
        }
    }

    return McpJarDiscoveryResult(null, attempts)
}

internal fun parseResourceUrlToPath(
    url: URL,
    jarUrlConnectionOpener: (URL) -> URL? = { u ->
        runCatching { (u.openConnection() as? JarURLConnection)?.jarFileURL }.getOrNull()
    }
): Path? {
    val jarFileUrl = jarUrlConnectionOpener(url)
    if (jarFileUrl != null) {
        val path = resolveCodeSourcePath(jarFileUrl)
        if (path != null) return path
    }

    val urlString = url.toExternalForm()
    if (urlString.contains("!")) {
        val archivePart = urlString.substringBefore("!")
        val fileUriString = if (archivePart.contains("file:")) {
            "file:" + archivePart.substringAfter("file:")
        } else {
            archivePart
        }
        val path = parsePathFromUriOrString(fileUriString)
        if (path != null) return path
    }

    val cleanUrlString = if (urlString.contains("file:")) {
        "file:" + urlString.substringAfter("file:")
    } else {
        urlString
    }

    val parsedPath = parsePathFromUriOrString(cleanUrlString) ?: return null

    if (Files.isDirectory(parsedPath)) {
        return parsedPath
    }

    val pathStr = parsedPath.toString()
    val classSuffix = "com/shiny/inspectionmcp/CopyMcpCommandAction.class"
    val altClassSuffix = CopyMcpCommandAction::class.java.name.replace('.', '/') + ".class"

    return when {
        pathStr.endsWith(classSuffix) -> {
            val baseDirStr = pathStr.dropLast(classSuffix.length).trimEnd('/', '\\')
            runCatching { Paths.get(baseDirStr) }.getOrNull()
        }
        pathStr.endsWith(altClassSuffix) -> {
            val baseDirStr = pathStr.dropLast(altClassSuffix.length).trimEnd('/', '\\')
            runCatching { Paths.get(baseDirStr) }.getOrNull()
        }
        pathStr.endsWith(".class") -> parsedPath.parent
        else -> parsedPath
    }
}

internal fun parsePathFromUriOrString(uriOrPath: String): Path? {
    return runCatching {
        val uri = URI(uriOrPath)
        Paths.get(uri)
    }.getOrElse {
        runCatching {
            val rawPath = if (uriOrPath.startsWith("file:")) {
                runCatching { URI.create(uriOrPath).path }.getOrNull()
                    ?: uriOrPath.substringAfter("file:")
            } else {
                uriOrPath
            }
            val decodedPath = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
            Paths.get(decodedPath)
        }.getOrNull()
    }
}

internal fun scanPluginsDirForMcpJar(pluginsPath: Path): Path? {
    if (!Files.isDirectory(pluginsPath)) return null

    resolveMcpJarPathFromPluginPath(pluginsPath)?.let { return it }

    return runCatching {
        Files.list(pluginsPath).use { stream ->
            stream.asSequence()
                .filter { Files.isDirectory(it) }
                .mapNotNull { child -> resolveMcpJarPathFromPluginPath(child) }
                .firstOrNull()
        }
    }.getOrNull()
}

internal fun resolveCodeSourcePath(location: URL): Path? {
    val fileLocation = when (location.protocol.lowercase()) {
        "file" -> location
        "jar" -> runCatching {
            (location.openConnection() as? JarURLConnection)?.jarFileURL
        }.getOrNull()
        else -> null
    } ?: return null
    return runCatching { Paths.get(fileLocation.toURI()) }.getOrElse {
        runCatching { Paths.get(fileLocation.path) }.getOrNull()
    }
}

internal fun resolveMcpJarPathFromPluginPath(pluginPath: Path): Path? {
    val pluginRoot = if (Files.isDirectory(pluginPath)) pluginPath else pluginPath.parent ?: return null
    val candidates = listOf(
        pluginRoot.resolve("lib").resolve(MCP_JAR_NAME),
        pluginRoot.resolve(MCP_JAR_NAME),
    )
    return candidates.firstOrNull { Files.exists(it) }
}

internal fun resolveMcpJarPathFromCodeSource(codeSource: Path): Path? {
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

