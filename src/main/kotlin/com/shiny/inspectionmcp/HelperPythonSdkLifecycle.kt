package com.shiny.inspectionmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal const val HELPER_SDK_LIFECYCLE_VERSION = 1

class HelperSdkRecord {
    var sdkName: String = ""
    var interpreterHome: String = ""
    var worktreePath: String = ""
}

class HelperSdkOwnershipState {
    var records: MutableList<HelperSdkRecord> = mutableListOf()
}

@Service(Service.Level.APP)
@State(name = "InspectionHelperSdkOwnership", storages = [Storage("inspection-helper-sdks.xml", roamingType = RoamingType.DISABLED)])
class HelperSdkOwnershipRegistry : PersistentStateComponent<HelperSdkOwnershipState> {
    private var ownership = HelperSdkOwnershipState()
    override fun getState(): HelperSdkOwnershipState = ownership
    override fun loadState(state: HelperSdkOwnershipState) { ownership = state }

    fun recordAddedSdk(sdk: Sdk, worktree: Path) {
        ownership.records.add(HelperSdkRecord().apply {
            sdkName = sdk.name
            interpreterHome = requireNotNull(sdk.homePath)
            worktreePath = worktree.toAbsolutePath().normalize().toString()
        })
    }
}

internal fun definitelyMissingWorktree(root: Path): Boolean {
    // Inaccessible paths and unmounted artifact volumes do not prove removal.
    if (root.parent == null || !Files.isDirectory(root.parent)) return false
    return try {
        Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        false
    } catch (_: NoSuchFileException) {
        true
    } catch (_: java.io.IOException) {
        false
    }
}

internal fun helperSdkName(): String = "Inspection .venv [${UUID.randomUUID()}]"

internal class HelperPythonSdkLifecycle(
    private val registry: HelperSdkOwnershipRegistry = ApplicationManager.getApplication()
        .getService(HelperSdkOwnershipRegistry::class.java),
    private val sdkTable: () -> ProjectJdkTable = { ProjectJdkTable.getInstance() },
    private val inUse: (Sdk, Path) -> Boolean = { sdk, root ->
        ProjectManager.getInstance().openProjects.any { project ->
            project.basePath?.let { Path.of(it).toAbsolutePath().normalize() == root } == true ||
                ProjectRootManager.getInstance(project).projectSdk === sdk ||
                ModuleManager.getInstance(project).modules.any { ModuleRootManager.getInstance(it).sdk === sdk }
        }
    },
    private val write: ((() -> Unit) -> Unit) = { action ->
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction(action)
        }
    },
    private val persist: () -> Unit = { ApplicationManager.getApplication().saveSettings() },
) {
    fun unregister(worktree: Path?, dryRun: Boolean): Map<String, Any?> {
        val root = worktree?.toAbsolutePath()?.normalize()
        val entries = mutableListOf<Map<String, Any?>>()
        var before = 0
        var after = 0
        write {
            val table = sdkTable()
            before = table.allJdks.size
            val records = registry.state.records.toList().filter { record ->
                if (root != null) record.worktreePath == root.toString()
                else definitelyMissingWorktree(Path.of(record.worktreePath))
            }
            for (record in records) {
                val recordRoot = Path.of(record.worktreePath).toAbsolutePath().normalize()
                val expectedHome = recordRoot.resolve(if (SystemInfo.isWindows) ".venv/Scripts/python.exe" else ".venv/bin/python")
                val matches = table.allJdks.filter { it.name == record.sdkName }
                val sdk = matches.singleOrNull()
                val reason = when {
                    Path.of(record.interpreterHome).toAbsolutePath().normalize() != expectedHome -> "ownership_mismatch"
                    matches.isEmpty() -> "already_absent"
                    sdk == null -> "ambiguous_sdk"
                    sdk.sdkType.name != "Python SDK" || sdk.homePath?.let { Path.of(it).toAbsolutePath().normalize() } != expectedHome -> "ownership_mismatch"
                    inUse(sdk, recordRoot) -> "sdk_in_use"
                    else -> "helper_owned"
                }
                if (!dryRun && reason == "helper_owned") {
                    table.removeJdk(requireNotNull(sdk))
                    check(table.allJdks.none { it === sdk }) { "SDK removal readback failed" }
                    registry.state.records.remove(record)
                } else if (!dryRun && reason == "already_absent") {
                    registry.state.records.remove(record)
                }
                entries.add(mapOf(
                    "sdk_name" to record.sdkName, "interpreter_home" to record.interpreterHome,
                    "worktree_path" to record.worktreePath, "reason" to reason,
                    "status" to when {
                        reason == "helper_owned" -> if (dryRun) "would_remove" else "removed"
                        reason == "already_absent" -> "absent"
                        else -> "refused"
                    },
                ))
            }
            // A selector must never authorize an SDK merely because its interpreter is under that path.
            if (root != null) {
                val expected = root.resolve(if (SystemInfo.isWindows) ".venv/Scripts/python.exe" else ".venv/bin/python")
                table.allJdks.filter { sdk -> sdk.homePath?.let { Path.of(it).toAbsolutePath().normalize() } == expected &&
                    records.none { it.sdkName == sdk.name }
                }.forEach { sdk ->
                    entries.add(mapOf("sdk_name" to sdk.name, "interpreter_home" to expected.toString(),
                        "worktree_path" to root.toString(), "status" to "refused", "reason" to "not_helper_owned"))
                }
            }
            after = table.allJdks.size
        }
        if (!dryRun) persist()
        return mapOf("status" to if (entries.any { it["status"] == "refused" }) "refused" else "ok",
            "dry_run" to dryRun, "sdk_lifecycle_version" to HELPER_SDK_LIFECYCLE_VERSION,
            "sdk_count_before" to before, "sdk_count_after" to after, "sdks" to entries)
    }
}
