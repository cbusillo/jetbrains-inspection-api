package com.shiny.inspectionmcp

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

private const val PYTHON_MODULE_TYPE_ID = "PYTHON_MODULE"
private const val PYTHON_SDK_TYPE_NAME = "Python SDK"
private const val PYTHON_MODULE_SETTLE_TIMEOUT_MS = 30_000L
private const val PYTHON_MODULE_SETTLE_POLL_MS = 200L
private const val REQUIRED_STABLE_MODULE_OBSERVATIONS = 2

internal data class PythonSdkPreparationRequest(
    val project: Project,
    val projectRoot: Path,
    val deadlineMs: Long,
    val indicator: ProgressIndicator,
    val ownershipIsCurrent: () -> Boolean,
)

internal data class PythonSdkPreparationResult(
    val prepared: Boolean,
    val reason: String,
    val operation: String? = null,
    val interpreterHome: String? = null,
    val sdkName: String? = null,
    val pythonModuleCount: Int = 0,
    val registeredLocalPythonSdkCount: Int = 0,
    val assignedLocalPythonSdkCount: Int = 0,
    val assignedPythonModuleCount: Int = 0,
    val projectSdkAssigned: Boolean = false,
    val detail: String? = null,
)

internal data class PythonSdkModelSnapshot(
    val modules: List<Module>,
    val projectSdk: Sdk?,
    val moduleSdks: Map<Module, Sdk?>,
) {
    val signature: List<String> = modules
        .map { module -> "${module.name}:${System.identityHashCode(module)}" }
        .sorted()
}

internal data class PythonSdkCommitResult(
    val succeeded: Boolean,
    val reason: String,
    val operation: String? = null,
    val sdk: Sdk? = null,
    val detail: String? = null,
)

internal data class PythonSdkReadback(
    val registeredCount: Int,
    val assignedLocalSdkCount: Int,
    val assignedPythonModuleCount: Int,
    val projectSdkAssigned: Boolean,
    val currentModuleModelMatches: Boolean,
    val sdkName: String?,
)

internal interface PythonSdkPreparationPlatform {
    fun isProjectTrusted(project: Project): Boolean
    fun snapshot(project: Project): PythonSdkModelSnapshot
    fun registeredSdks(): List<Sdk>
    fun pythonSdkType(): SdkType?
    fun sdkHome(sdk: Sdk): String?
    fun sdkTypeName(sdk: Sdk): String
    fun isValidSdk(sdk: Sdk): Boolean
    fun createDetachedSdk(existingSdks: Collection<Sdk>, interpreterHome: String, type: SdkType): Sdk
    fun setupSdkPaths(type: SdkType, sdk: Sdk, indicator: ProgressIndicator)
    fun isSetupComplete(sdk: Sdk, interpreterHome: String): Boolean
    fun commit(
        project: Project,
        expectedModules: List<Module>,
        interpreterHome: String,
        detachedSdk: Sdk?,
        ownershipIsCurrent: () -> Boolean,
        indicator: ProgressIndicator,
    ): PythonSdkCommitResult
    fun readback(project: Project, expectedModules: List<Module>, interpreterHome: String): PythonSdkReadback
}

internal class PythonSdkPreparationService(
    private val platform: PythonSdkPreparationPlatform = JetBrainsPythonSdkPreparationPlatform(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) {
    fun prepare(request: PythonSdkPreparationRequest): PythonSdkPreparationResult {
        val interpreter = request.projectRoot.resolve(".venv").resolve(
            if (SystemInfo.isWindows) "Scripts/python.exe" else "bin/python",
        ).normalize().toAbsolutePath()
        val configuration = request.projectRoot.resolve(".venv/pyvenv.cfg").normalize().toAbsolutePath()
        val interpreterHome = interpreter.toString()
        if (!Files.isRegularFile(configuration)) {
            return failure("python_sdk_preparation_venv_configuration_missing", interpreterHome)
        }
        if (!Files.isRegularFile(interpreter) || (!SystemInfo.isWindows && !Files.isExecutable(interpreter))) {
            return failure("python_sdk_preparation_interpreter_missing", interpreterHome)
        }
        if (!platform.isProjectTrusted(request.project)) {
            return failure("python_sdk_preparation_project_untrusted", interpreterHome)
        }
        val type = platform.pythonSdkType()
        if (type == null || type.name != PYTHON_SDK_TYPE_NAME) {
            return failure(
                "python_sdk_preparation_unsupported",
                interpreterHome,
                detail = "The exact Python SDK type is unavailable.",
            )
        }

        return try {
            val snapshot = awaitStablePythonModules(request)
                ?: return failure("python_sdk_preparation_no_python_modules", interpreterHome)
            request.checkCurrent()
            conflict(snapshot, interpreterHome)?.let { sdk ->
                return failure(
                    reason = "python_sdk_preparation_sdk_conflict",
                    interpreterHome = interpreterHome,
                    modules = snapshot.modules.size,
                    detail = "A different valid SDK is assigned (${sdk.name}).",
                )
            }

            val matches = matchingSdks(interpreterHome)
            if (matches.size > 1) {
                return failure(
                    "python_sdk_preparation_ambiguous_registered_sdk",
                    interpreterHome,
                    snapshot.modules.size,
                )
            }
            val existing = matches.singleOrNull()
            if (existing != null && !platform.isSetupComplete(existing, interpreterHome)) {
                return failure(
                    "python_sdk_preparation_existing_sdk_incomplete",
                    interpreterHome,
                    snapshot.modules.size,
                )
            }
            val detached = if (existing == null) {
                request.checkCurrent()
                val candidate = platform.createDetachedSdk(platform.registeredSdks(), interpreterHome, type)
                request.checkCurrent()
                platform.setupSdkPaths(type, candidate, request.indicator)
                request.checkCurrent()
                if (!platform.isSetupComplete(candidate, interpreterHome)) {
                    return failure(
                        "python_sdk_preparation_setup_incomplete",
                        interpreterHome,
                        snapshot.modules.size,
                    )
                }
                candidate
            } else {
                null
            }

            val commit = platform.commit(
                project = request.project,
                expectedModules = snapshot.modules,
                interpreterHome = interpreterHome,
                detachedSdk = detached,
                ownershipIsCurrent = request.ownershipIsCurrent,
                indicator = request.indicator,
            )
            if (!commit.succeeded) {
                val observed = runCatching {
                    platform.readback(request.project, snapshot.modules, interpreterHome)
                }.getOrNull()
                return failure(commit.reason, interpreterHome, snapshot.modules.size, commit.detail).copy(
                    registeredLocalPythonSdkCount = observed?.registeredCount ?: 0,
                    assignedLocalPythonSdkCount = observed?.assignedLocalSdkCount ?: 0,
                    assignedPythonModuleCount = observed?.assignedPythonModuleCount ?: 0,
                    projectSdkAssigned = observed?.projectSdkAssigned ?: false,
                )
            }
            if (commit.operation !in setOf("created", "reused", "already_assigned")) {
                return failure(
                    "python_sdk_preparation_commit_failed",
                    interpreterHome,
                    snapshot.modules.size,
                    "The commit did not report a supported operation.",
                )
            }
            request.checkCurrent()
            val readback = platform.readback(request.project, snapshot.modules, interpreterHome)
            val completeReadback = readback.registeredCount == 1 &&
                readback.assignedLocalSdkCount == 1 &&
                readback.projectSdkAssigned &&
                readback.currentModuleModelMatches &&
                readback.assignedPythonModuleCount == snapshot.modules.size
            if (!completeReadback) {
                return failure(
                    "python_sdk_preparation_readback_failed",
                    interpreterHome,
                    snapshot.modules.size,
                    "Expected the one registered SDK object to be assigned to the project and every stable Python module.",
                ).copy(
                    registeredLocalPythonSdkCount = readback.registeredCount,
                    assignedLocalPythonSdkCount = readback.assignedLocalSdkCount,
                    assignedPythonModuleCount = readback.assignedPythonModuleCount,
                    projectSdkAssigned = readback.projectSdkAssigned,
                )
            }
            PythonSdkPreparationResult(
                prepared = true,
                reason = "python_sdk_preparation_prepared",
                operation = commit.operation,
                interpreterHome = interpreterHome,
                sdkName = readback.sdkName,
                pythonModuleCount = snapshot.modules.size,
                registeredLocalPythonSdkCount = readback.registeredCount,
                assignedLocalPythonSdkCount = readback.assignedLocalSdkCount,
                assignedPythonModuleCount = readback.assignedPythonModuleCount,
                projectSdkAssigned = readback.projectSdkAssigned,
            )
        } catch (_: ProcessCanceledException) {
            failure("python_sdk_preparation_cancelled", interpreterHome)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failure("python_sdk_preparation_cancelled", interpreterHome)
        } catch (error: Throwable) {
            failure(
                "python_sdk_preparation_failed",
                interpreterHome,
                detail = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun awaitStablePythonModules(request: PythonSdkPreparationRequest): PythonSdkModelSnapshot? {
        val settleDeadline = minOf(request.deadlineMs, now() + PYTHON_MODULE_SETTLE_TIMEOUT_MS)
        var previousSignature: List<String>? = null
        var stableObservations = 0
        while (now() < settleDeadline) {
            request.checkCurrent()
            val snapshot = platform.snapshot(request.project)
            if (snapshot.modules.isEmpty()) {
                previousSignature = null
                stableObservations = 0
            } else {
                stableObservations = if (snapshot.signature == previousSignature) stableObservations + 1 else 1
                previousSignature = snapshot.signature
                if (stableObservations >= REQUIRED_STABLE_MODULE_OBSERVATIONS) {
                    return snapshot
                }
            }
            sleep(minOf(PYTHON_MODULE_SETTLE_POLL_MS, (settleDeadline - now()).coerceAtLeast(1)))
        }
        return null
    }

    private fun conflict(snapshot: PythonSdkModelSnapshot, interpreterHome: String): Sdk? {
        return (listOf(snapshot.projectSdk) + snapshot.moduleSdks.values)
            .filterNotNull()
            .firstOrNull { sdk ->
                platform.isValidSdk(sdk) && normalize(platform.sdkHome(sdk)) != interpreterHome
            }
    }

    private fun matchingSdks(interpreterHome: String): List<Sdk> = platform.registeredSdks().filter { sdk ->
        platform.sdkTypeName(sdk) == PYTHON_SDK_TYPE_NAME && normalize(platform.sdkHome(sdk)) == interpreterHome
    }

    private fun normalize(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)?.let { raw ->
        runCatching { Paths.get(raw).normalize().toAbsolutePath().toString() }.getOrNull()
    }

    private fun PythonSdkPreparationRequest.checkCurrent() {
        indicator.checkCanceled()
        if (now() >= deadlineMs) {
            throw ProcessCanceledException()
        }
        if (project.isDisposed || !ownershipIsCurrent()) {
            throw ProcessCanceledException()
        }
    }

    private fun failure(
        reason: String,
        interpreterHome: String,
        modules: Int = 0,
        detail: String? = null,
    ) = PythonSdkPreparationResult(
        prepared = false,
        reason = reason,
        interpreterHome = interpreterHome,
        pythonModuleCount = modules,
        detail = detail,
    )
}

internal class JetBrainsPythonSdkPreparationPlatform : PythonSdkPreparationPlatform {
    override fun isProjectTrusted(project: Project): Boolean = TrustedProjects.isProjectTrusted(project)

    override fun snapshot(project: Project): PythonSdkModelSnapshot =
        ApplicationManager.getApplication().runReadAction<PythonSdkModelSnapshot> {
            val modules = ModuleManager.getInstance(project).modules
                .filter { module -> ModuleType.get(module).id == PYTHON_MODULE_TYPE_ID }
            PythonSdkModelSnapshot(
                modules = modules,
                projectSdk = ProjectRootManager.getInstance(project).projectSdk,
                moduleSdks = modules.associateWith { module -> ModuleRootManager.getInstance(module).sdk },
            )
        }

    override fun registeredSdks(): List<Sdk> = ProjectJdkTable.getInstance().allJdks.toList()

    override fun pythonSdkType(): SdkType? = SdkType.findByName(PYTHON_SDK_TYPE_NAME)

    override fun sdkHome(sdk: Sdk): String? = sdk.homePath

    override fun sdkTypeName(sdk: Sdk): String = sdk.sdkType.name

    override fun isValidSdk(sdk: Sdk): Boolean = sdk.homePath?.let { path ->
        runCatching { Files.exists(Paths.get(path)) }.getOrDefault(false)
    } ?: false

    override fun createDetachedSdk(existingSdks: Collection<Sdk>, interpreterHome: String, type: SdkType): Sdk =
        SdkConfigurationUtil.createSdk(existingSdks, interpreterHome, type, null, "Inspection .venv")

    override fun setupSdkPaths(type: SdkType, sdk: Sdk, indicator: ProgressIndicator) {
        ProgressManager.getInstance().runProcess({ type.setupSdkPaths(sdk) }, indicator)
    }

    override fun isSetupComplete(sdk: Sdk, interpreterHome: String): Boolean {
        return normalizedHome(sdk) == interpreterHome &&
            !sdk.versionString.isNullOrBlank() &&
            sdk.rootProvider.getFiles(OrderRootType.CLASSES).isNotEmpty()
    }

    override fun commit(
        project: Project,
        expectedModules: List<Module>,
        interpreterHome: String,
        detachedSdk: Sdk?,
        ownershipIsCurrent: () -> Boolean,
        indicator: ProgressIndicator,
    ): PythonSdkCommitResult {
        val result = AtomicReference<PythonSdkCommitResult>()
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                result.set(commitUnderWriteAction(
                    project,
                    expectedModules,
                    interpreterHome,
                    detachedSdk,
                    ownershipIsCurrent,
                    indicator,
                ))
            }
        }
        return result.get() ?: PythonSdkCommitResult(false, "python_sdk_preparation_commit_failed")
    }

    private fun commitUnderWriteAction(
        project: Project,
        expectedModules: List<Module>,
        interpreterHome: String,
        detachedSdk: Sdk?,
        ownershipIsCurrent: () -> Boolean,
        indicator: ProgressIndicator,
    ): PythonSdkCommitResult {
        indicator.checkCanceled()
        if (project.isDisposed || !ownershipIsCurrent()) {
            return PythonSdkCommitResult(false, "python_sdk_preparation_ownership_changed")
        }
        val currentModules = ModuleManager.getInstance(project).modules
            .filter { module -> ModuleType.get(module).id == PYTHON_MODULE_TYPE_ID }
        if (currentModules.size != expectedModules.size || !currentModules.containsAll(expectedModules)) {
            return PythonSdkCommitResult(false, "python_sdk_preparation_module_model_changed")
        }
        val currentAssignments = listOf(ProjectRootManager.getInstance(project).projectSdk) +
            currentModules.map { module -> ModuleRootManager.getInstance(module).sdk }
        val conflict = currentAssignments.filterNotNull().firstOrNull { sdk ->
            isValidSdk(sdk) && normalizedHome(sdk) != interpreterHome
        }
        if (conflict != null) {
            return PythonSdkCommitResult(
                false,
                "python_sdk_preparation_sdk_conflict",
                detail = "A different valid SDK is assigned (${conflict.name}).",
            )
        }

        val matching = ProjectJdkTable.getInstance().allJdks.filter { sdk ->
            sdk.sdkType.name == PYTHON_SDK_TYPE_NAME && normalizedHome(sdk) == interpreterHome
        }
        if (matching.size > 1) {
            return PythonSdkCommitResult(false, "python_sdk_preparation_ambiguous_registered_sdk")
        }
        val sdk = matching.singleOrNull() ?: detachedSdk
            ?: return PythonSdkCommitResult(false, "python_sdk_preparation_candidate_missing")
        if (matching.isNotEmpty() && !isSetupComplete(sdk, interpreterHome)) {
            return PythonSdkCommitResult(false, "python_sdk_preparation_existing_sdk_incomplete")
        }
        val alreadyAssigned = matching.singleOrNull() != null &&
            currentAssignments.filterNotNull().all { assigned -> assigned === sdk } &&
            currentAssignments.none { assigned -> assigned == null }
        val operation = when {
            alreadyAssigned -> "already_assigned"
            matching.isNotEmpty() -> "reused"
            else -> "created"
        }
        if (alreadyAssigned) {
            return PythonSdkCommitResult(true, "python_sdk_preparation_prepared", operation, sdk)
        }

        if (matching.isEmpty()) {
            try {
                val uniqueName = SdkConfigurationUtil.createUniqueSdkName(
                    sdk.name,
                    ProjectJdkTable.getInstance().allJdks.toList(),
                )
                if (uniqueName != sdk.name) {
                    sdk.sdkModificator.apply {
                        name = uniqueName
                        commitChanges()
                    }
                }
            } catch (error: Throwable) {
                return PythonSdkCommitResult(
                    false,
                    "python_sdk_preparation_registration_failed",
                    detail = error.message ?: error::class.java.simpleName,
                )
            }
        }

        val models = mutableListOf<com.intellij.openapi.roots.ModifiableRootModel>()
        try {
            currentModules.forEach { module ->
                val model = ModuleRootManager.getInstance(module).modifiableModel
                models += model
                model.sdk = sdk
            }
        } catch (error: Throwable) {
            models.forEach { model -> runCatching { model.dispose() } }
            return PythonSdkCommitResult(
                false,
                "python_sdk_preparation_assignment_failed",
                detail = error.message ?: error::class.java.simpleName,
            )
        }

        if (matching.isEmpty()) {
            try {
                ProjectJdkTable.getInstance().addJdk(sdk)
            } catch (error: Throwable) {
                models.forEach { model -> runCatching { model.dispose() } }
                return PythonSdkCommitResult(
                    false,
                    "python_sdk_preparation_registration_failed",
                    detail = error.message ?: error::class.java.simpleName,
                )
            }
        }
        try {
            models.toList().forEach { model ->
                model.commit()
                models.remove(model)
            }
            ProjectRootManager.getInstance(project).projectSdk = sdk
        } catch (error: Throwable) {
            models.forEach { model -> runCatching { model.dispose() } }
            return PythonSdkCommitResult(
                false,
                "python_sdk_preparation_assignment_failed",
                detail = error.message ?: error::class.java.simpleName,
            )
        }
        return PythonSdkCommitResult(true, "python_sdk_preparation_prepared", operation, sdk)
    }

    override fun readback(project: Project, expectedModules: List<Module>, interpreterHome: String): PythonSdkReadback =
        ApplicationManager.getApplication().runReadAction<PythonSdkReadback> {
            val registered = ProjectJdkTable.getInstance().allJdks.filter { sdk ->
                sdk.sdkType.name == PYTHON_SDK_TYPE_NAME && normalizedHome(sdk) == interpreterHome
            }
            val sdk = registered.singleOrNull()
            val currentModules = ModuleManager.getInstance(project).modules
                .filter { module -> ModuleType.get(module).id == PYTHON_MODULE_TYPE_ID }
            val currentModelMatches = currentModules.size == expectedModules.size &&
                currentModules.containsAll(expectedModules)
            val projectAssigned = sdk != null && ProjectRootManager.getInstance(project).projectSdk === sdk
            val assignments = listOf(ProjectRootManager.getInstance(project).projectSdk) +
                currentModules.map { module -> ModuleRootManager.getInstance(module).sdk }
            val assignedModules = if (sdk == null) 0 else currentModules.count { module ->
                ModuleRootManager.getInstance(module).sdk === sdk
            }
            val assignedSdkIdentities = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Sdk, Boolean>(),
            )
            assignments.filterNotNull().filterTo(assignedSdkIdentities) { assignedSdk ->
                assignedSdk.sdkType.name == PYTHON_SDK_TYPE_NAME &&
                    normalizedHome(assignedSdk) == interpreterHome
            }
            PythonSdkReadback(
                registeredCount = registered.size,
                assignedLocalSdkCount = assignedSdkIdentities.size,
                assignedPythonModuleCount = assignedModules,
                projectSdkAssigned = projectAssigned,
                currentModuleModelMatches = currentModelMatches,
                sdkName = sdk?.name,
            )
        }

    private fun normalizedHome(sdk: Sdk): String? = sdk.homePath?.let { raw ->
        runCatching { Paths.get(raw).normalize().toAbsolutePath().toString() }.getOrNull()
    }
}
