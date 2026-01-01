package com.shiny.inspectionmcp

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.ide.CopyPasteManager

private const val MCP_ONBOARDING_KEY = "inspection.api.mcp.onboarding.version"
private const val MCP_NOTIFICATION_GROUP = "inspection.api"
private const val PLUGIN_ID = "com.shiny.inspection.api"

class McpOnboardingStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val productName = InspectionApiBundle.message("notification.group.name")
        ApplicationManager.getApplication().invokeLater {
            val props = PropertiesComponent.getInstance()
            val currentVersion = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version
            val lastShownVersion = props.getValue(MCP_ONBOARDING_KEY)
            if (currentVersion != null && lastShownVersion == currentVersion) return@invokeLater
            props.setValue(MCP_ONBOARDING_KEY, currentVersion ?: "true")

            val group = NotificationGroupManager.getInstance().getNotificationGroup(MCP_NOTIFICATION_GROUP)
            val notification = group.createNotification(
                "$productName: MCP setup",
                "This plugin bundles an MCP server. Use Tools → Copy MCP Setup Command to connect your MCP client.",
                NotificationType.INFORMATION
            )

            notification.addAction(
                NotificationAction.createSimple("Copy MCP setup command") {
                    val command = buildMcpCommand()
                    if (command == null) {
                        Messages.showErrorDialog(
                            "Unable to locate jetbrains-inspection-mcp.jar. Reinstall the plugin and try again.",
                            "MCP Setup"
                        )
                    } else {
                        CopyPasteManager.getInstance().setContents(StringSelection(command))
                        Messages.showInfoMessage(
                            "MCP setup command copied to clipboard:\n\n$command",
                            "MCP Setup"
                        )
                    }
                }
            )

            notification.notify(project)
        }
    }
}
