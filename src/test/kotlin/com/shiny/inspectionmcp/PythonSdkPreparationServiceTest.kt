package com.shiny.inspectionmcp

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.util.SystemInfo
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap

class PythonSdkPreparationServiceTest {
    @TempDir
    lateinit var projectRoot: Path

    private lateinit var project: Project
    private lateinit var module: Module
    private lateinit var clock: TestClock
    private lateinit var platform: FakePythonSdkPreparationPlatform

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.isDisposed } returns false
        module = module("fixture")
        clock = TestClock()
        platform = FakePythonSdkPreparationPlatform(project, listOf(module))
    }

    @Test
    fun `creates configures registers and assigns a missing worktree SDK`() {
        val interpreter = createVenv()

        val result = prepare()

        assertTrue(result.prepared)
        assertEquals("python_sdk_preparation_prepared", result.reason)
        assertEquals("created", result.operation)
        assertEquals(interpreter.toString(), result.interpreterHome)
        assertEquals(1, result.registeredLocalPythonSdkCount)
        assertEquals(1, result.assignedLocalPythonSdkCount)
        assertEquals(1, result.assignedPythonModuleCount)
        assertTrue(result.projectSdkAssigned)
        assertEquals(listOf("create", "setup", "commit", "persist", "readback"), platform.mutationEvents)
        assertSame(platform.detachedSdk, platform.registered.single())
        assertSame(platform.detachedSdk, platform.committedDetachedSdk)
        assertEquals(interpreter.toString(), platform.createdHome)
    }

    @Test
    fun `reuses the one registered worktree SDK without creating or setting up another`() {
        val interpreter = createVenv()
        val existing = sdk("existing", interpreter.toString())
        platform.registered += existing

        val result = prepare()

        assertTrue(result.prepared)
        assertEquals("reused", result.operation)
        assertEquals(listOf("commit", "persist", "readback"), platform.mutationEvents)
        assertNull(platform.committedDetachedSdk)
        assertEquals(listOf(existing), platform.registered)
    }

    @Test
    fun `registration failure reports safe readback without assignment`() {
        createVenv()
        platform.forcedCommitResult = PythonSdkCommitResult(
            succeeded = false,
            reason = "python_sdk_preparation_registration_failed",
            detail = "registry unavailable",
        )
        platform.forcedReadback = PythonSdkReadback(
            registeredCount = 0,
            assignedLocalSdkCount = 0,
            assignedPythonModuleCount = 0,
            projectSdkAssigned = false,
            currentModuleModelMatches = true,
            sdkName = null,
        )

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_registration_failed", result.reason)
        assertEquals("registry unavailable", result.detail)
        assertEquals(0, result.registeredLocalPythonSdkCount)
        assertEquals(0, result.assignedLocalPythonSdkCount)
        assertEquals(listOf("create", "setup", "commit", "readback"), platform.mutationEvents)
        assertEquals(0, platform.assignmentMutationCount)
        assertTrue(platform.registered.isEmpty())
    }

    @Test
    fun `application persistence failure is reported after safe readback`() {
        createVenv()
        platform.persistenceResult = PythonSdkPersistenceResult(
            false,
            "python_sdk_preparation_persistence_failed",
            "settings store failed",
        )

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_persistence_failed", result.reason)
        assertEquals("settings store failed", result.detail)
        assertEquals(1, result.registeredLocalPythonSdkCount)
        assertEquals(1, result.assignedLocalPythonSdkCount)
        assertEquals(listOf("create", "setup", "commit", "persist", "readback"), platform.mutationEvents)
    }

    @Test
    fun `reuses one exact SDK that appears between lookup and commit`() {
        val interpreter = createVenv()
        val racingSdk = sdk("racing", interpreter.toString())
        platform.sdkAppearingAtCommit = racingSdk

        val result = prepare()

        assertTrue(result.prepared)
        assertEquals("reused", result.operation)
        assertEquals(listOf(racingSdk), platform.registered)
        assertSame(platform.detachedSdk, platform.committedDetachedSdk)
        assertEquals(1, platform.assignmentMutationCount)
        assertEquals(listOf("create", "setup", "commit", "persist", "readback"), platform.mutationEvents)
    }

    @Test
    fun `already assigned exact SDK performs no assignment mutation`() {
        val interpreter = createVenv()
        val existing = sdk("assigned", interpreter.toString())
        platform.registered += existing
        platform.snapshots = ArrayDeque(
            listOf(
                snapshot(listOf(module), projectSdk = existing, moduleSdks = mapOf(module to existing)),
                snapshot(listOf(module), projectSdk = existing, moduleSdks = mapOf(module to existing)),
            ),
        )
        platform.alreadyAssigned = true

        val result = prepare()

        assertTrue(result.prepared)
        assertEquals("already_assigned", result.operation)
        assertEquals(0, platform.assignmentMutationCount)
        assertEquals(listOf("commit", "persist", "readback"), platform.mutationEvents)
    }

    @Test
    fun `preserves the lexical venv interpreter instead of collapsing its symlink target`() {
        assumeFalse(SystemInfo.isWindows)
        val sharedInterpreter = projectRoot.resolve("shared-python")
        Files.writeString(sharedInterpreter, "#!/bin/sh\nexit 0\n")
        assertTrue(sharedInterpreter.toFile().setExecutable(true))
        val interpreter = createVenv(sharedInterpreter)

        val result = prepare()

        val lexicalHome = interpreter.normalize().toAbsolutePath().toString()
        assertTrue(result.prepared)
        assertEquals(lexicalHome, platform.createdHome)
        assertEquals(lexicalHome, result.interpreterHome)
        assertFalse(lexicalHome == interpreter.toRealPath().toString())
    }

    @Test
    fun `refuses a conflicting valid assignment before any SDK lifecycle write`() {
        createVenv()
        val other = sdk("other", projectRoot.resolve("other-python").toString())
        platform.validSdks[other] = true
        platform.snapshots = ArrayDeque(
            listOf(
                snapshot(listOf(module), projectSdk = other),
                snapshot(listOf(module), projectSdk = other),
            ),
        )

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_sdk_conflict", result.reason)
        assertTrue(result.detail.orEmpty().contains("other"))
        assertTrue(platform.mutationEvents.isEmpty())
        assertEquals(1, platform.pythonTypeQueries)
    }

    @Test
    fun `refuses ambiguous registrations at the same lexical home`() {
        val interpreter = createVenv()
        platform.registered += sdk("first", interpreter.toString())
        platform.registered += sdk("second", interpreter.toString())

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_ambiguous_registered_sdk", result.reason)
        assertEquals(2, result.registeredLocalPythonSdkCount)
        assertEquals(listOf("readback"), platform.mutationEvents)
        assertEquals(1, platform.pythonTypeQueries)
    }

    @Test
    fun `ignores a non Python SDK at the same home and creates the required Python SDK`() {
        val interpreter = createVenv()
        val otherType = sdk("other type", interpreter.toString(), typeName = "JavaSDK")
        platform.registered += otherType

        val result = prepare()

        assertTrue(result.prepared)
        assertEquals("created", result.operation)
        assertEquals(listOf(otherType, platform.detachedSdk), platform.registered)
        assertEquals(listOf("create", "setup", "commit", "persist", "readback"), platform.mutationEvents)
    }

    @Test
    fun `refuses a matching registered SDK whose setup is incomplete`() {
        val interpreter = createVenv()
        platform.registered += sdk("incomplete", interpreter.toString())
        platform.setupComplete = false
        platform.forcedReadback = PythonSdkReadback(
            registeredCount = 1,
            assignedLocalSdkCount = 0,
            assignedPythonModuleCount = 0,
            projectSdkAssigned = false,
            currentModuleModelMatches = true,
            sdkName = "incomplete",
        )

        val result = prepare(deadlineMs = 20_000)

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_existing_sdk_incomplete", result.reason)
        assertEquals(1, result.registeredLocalPythonSdkCount)
        assertEquals(0, result.assignedLocalPythonSdkCount)
        assertEquals(listOf("readback"), platform.mutationEvents)
        assertEquals(0, platform.commitQueries)
    }

    @Test
    fun `waits for an exact registered SDK whose platform setup is still completing`() {
        val interpreter = createVenv()
        val existing = sdk("starting", interpreter.toString())
        platform.registered += existing
        platform.setupCompleteResults = ArrayDeque(listOf(false, false, true))

        val result = prepare()

        assertTrue(result.prepared)
        assertEquals("reused", result.operation)
        assertEquals(3, platform.setupCompleteQueries)
        assertEquals(listOf("commit", "persist", "readback"), platform.mutationEvents)
        assertNull(platform.committedDetachedSdk)
    }

    @Test
    fun `initializes detached SDK additional data before Python path setup`() {
        val type = mockk<SdkType>()
        val sdk = mockk<Sdk>()
        val additionalData = mockk<SdkAdditionalData>()
        val modificator = mockk<SdkModificator>(relaxed = true)
        val assignedData = slot<SdkAdditionalData>()
        every { type.loadAdditionalData(sdk, match { element -> element.name == "additional" }) } returns additionalData
        every { sdk.sdkModificator } returns modificator
        every { sdk.sdkAdditionalData } returns additionalData
        every { modificator.sdkAdditionalData = capture(assignedData) } just Runs

        JetBrainsPythonSdkPreparationPlatform(runSdkWriteAction = { action -> action() })
            .initializeDetachedSdkAdditionalData(type, sdk)

        assertSame(additionalData, assignedData.captured)
    }

    @Test
    fun `cancels while waiting for an existing SDK setup without committing`() {
        val interpreter = createVenv()
        platform.registered += sdk("starting", interpreter.toString())
        platform.setupComplete = false

        val result = prepare(onCancellationCheck = { checks ->
            if (checks == 5) throw ProcessCanceledException()
        })

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_cancelled", result.reason)
        assertEquals(0, platform.commitQueries)
        assertTrue(platform.mutationEvents.isEmpty())
    }

    @Test
    fun `refuses when the exact registered SDK identity changes during setup wait`() {
        val interpreter = createVenv()
        val expected = sdk("starting", interpreter.toString())
        val replacement = sdk("replacement", interpreter.toString())
        platform.registered += expected
        platform.registeredSdkResults = ArrayDeque(listOf(listOf(expected), listOf(replacement)))
        platform.setupComplete = false

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_existing_sdk_changed", result.reason)
        assertEquals(0, platform.commitQueries)
        assertEquals(listOf("readback"), platform.mutationEvents)
    }

    @Test
    fun `refuses when another matching registration appears during setup wait`() {
        val interpreter = createVenv()
        val expected = sdk("starting", interpreter.toString())
        val duplicate = sdk("duplicate", interpreter.toString())
        platform.registered += expected
        platform.registeredSdkResults = ArrayDeque(listOf(listOf(expected), listOf(expected, duplicate)))
        platform.setupComplete = false

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_ambiguous_registered_sdk", result.reason)
        assertEquals(0, platform.commitQueries)
        assertEquals(listOf("readback"), platform.mutationEvents)
    }

    @Test
    fun `bounds an existing SDK setup wait by the request deadline`() {
        val interpreter = createVenv()
        platform.registered += sdk("starting", interpreter.toString())
        platform.setupComplete = false
        platform.forcedReadback = PythonSdkReadback(
            registeredCount = 1,
            assignedLocalSdkCount = 0,
            assignedPythonModuleCount = 0,
            projectSdkAssigned = false,
            currentModuleModelMatches = true,
            sdkName = "starting",
        )

        val result = prepare(deadlineMs = 600)

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_timeout", result.reason)
        assertEquals(600, clock.timeMs)
        assertFalse(result.readbackObserved)
        assertEquals(0, platform.commitQueries)
    }

    @Test
    fun `cancellation during failure readback remains cancellation`() {
        val interpreter = createVenv()
        platform.registered += sdk("first", interpreter.toString())
        platform.registered += sdk("second", interpreter.toString())
        platform.readbackFailure = ProcessCanceledException()

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_cancelled", result.reason)
        assertFalse(result.readbackObserved)
        assertEquals(listOf("readback"), platform.mutationEvents)
        assertEquals(0, platform.commitQueries)
    }

    @Test
    fun `requires pyvenv configuration before consulting project state`() {
        val interpreter = interpreterPath()
        Files.createDirectories(interpreter.parent)
        Files.writeString(interpreter, "python")
        interpreter.toFile().setExecutable(true)

        val result = prepare()

        assertEquals("python_sdk_preparation_venv_configuration_missing", result.reason)
        assertEquals(0, platform.trustQueries)
        assertTrue(platform.mutationEvents.isEmpty())
    }

    @Test
    fun `requires an executable interpreter before consulting project state`() {
        Files.createDirectories(projectRoot.resolve(".venv"))
        Files.writeString(projectRoot.resolve(".venv/pyvenv.cfg"), "home = fixture\n")

        val result = prepare()

        assertEquals("python_sdk_preparation_interpreter_missing", result.reason)
        assertEquals(0, platform.trustQueries)
        assertTrue(platform.mutationEvents.isEmpty())
    }

    @Test
    fun `refuses an untrusted project before reading its module model`() {
        createVenv()
        platform.trusted = false

        val result = prepare()

        assertEquals("python_sdk_preparation_project_untrusted", result.reason)
        assertEquals(1, platform.trustQueries)
        assertEquals(0, platform.snapshotQueries)
        assertTrue(platform.mutationEvents.isEmpty())
    }

    @Test
    fun `times out when no Python module becomes visible`() {
        createVenv()
        platform.snapshots = ArrayDeque(listOf(snapshot(emptyList())))

        val result = prepare(deadlineMs = 600)

        assertEquals("python_sdk_preparation_timeout", result.reason)
        assertTrue(platform.snapshotQueries >= 2)
        assertEquals(600, clock.timeMs)
        assertTrue(platform.mutationEvents.isEmpty())
    }

    @Test
    fun `fails closed when the exact Python SDK type is unavailable`() {
        createVenv()
        platform.pythonType = null

        val result = prepare()

        assertEquals("python_sdk_preparation_unsupported", result.reason)
        assertTrue(platform.mutationEvents.isEmpty())
        assertEquals(1, platform.pythonTypeQueries)
    }

    @Test
    fun `cancellation during SDK setup leaves the registry untouched`() {
        createVenv()
        platform.setupFailure = ProcessCanceledException()

        val result = prepare()

        assertEquals("python_sdk_preparation_cancelled", result.reason)
        assertEquals(listOf("create", "setup"), platform.mutationEvents)
        assertTrue(platform.registered.isEmpty())
        assertEquals(0, platform.commitQueries)
    }

    @Test
    fun `deadline after detached setup prevents registration and assignment`() {
        createVenv()
        val deadline = 5_000L

        val result = prepare(deadlineMs = deadline) { checkCount ->
            if (checkCount == 6) clock.timeMs = deadline
        }

        assertEquals("python_sdk_preparation_timeout", result.reason)
        assertEquals(listOf("create", "setup"), platform.mutationEvents)
        assertTrue(platform.registered.isEmpty())
        assertEquals(0, platform.commitQueries)
    }

    @Test
    fun `module identity churn is rejected by the commit boundary`() {
        createVenv()
        platform.modulesAtCommit = listOf(module("replacement"))

        val result = prepare()

        assertEquals("python_sdk_preparation_module_model_changed", result.reason)
        assertEquals(listOf("create", "setup", "commit", "readback"), platform.mutationEvents)
        assertTrue(platform.registered.isEmpty())
        assertEquals(1, platform.readbackQueries)
    }

    @Test
    fun `incomplete setup never reaches registration`() {
        createVenv()
        platform.setupComplete = false

        val result = prepare()

        assertEquals("python_sdk_preparation_setup_incomplete", result.reason)
        assertEquals(listOf("create", "setup"), platform.mutationEvents)
        assertTrue(platform.registered.isEmpty())
        assertEquals(0, platform.commitQueries)
    }

    @Test
    fun `setup errors never reach registration`() {
        createVenv()
        platform.setupFailure = IllegalStateException("roots unavailable")

        val result = prepare()

        assertEquals("python_sdk_preparation_failed", result.reason)
        assertEquals("roots unavailable", result.detail)
        assertEquals(listOf("create", "setup"), platform.mutationEvents)
        assertTrue(platform.registered.isEmpty())
        assertEquals(0, platform.commitQueries)
    }

    @Test
    fun `failed readback reports observed counts after a successful commit`() {
        createVenv()
        platform.forcedReadback = PythonSdkReadback(
            registeredCount = 0,
            assignedLocalSdkCount = 1,
            assignedPythonModuleCount = 1,
            projectSdkAssigned = true,
            currentModuleModelMatches = true,
            sdkName = null,
        )

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_readback_failed", result.reason)
        assertEquals(0, result.registeredLocalPythonSdkCount)
        assertEquals(1, result.assignedLocalPythonSdkCount)
        assertEquals(listOf("create", "setup", "commit", "persist", "readback"), platform.mutationEvents)
    }

    @Test
    fun `partial module assignment cannot pass readback`() {
        createVenv()
        platform.forcedReadback = PythonSdkReadback(
            registeredCount = 1,
            assignedLocalSdkCount = 1,
            assignedPythonModuleCount = 0,
            projectSdkAssigned = true,
            currentModuleModelMatches = true,
            sdkName = "Inspection .venv",
        )

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_readback_failed", result.reason)
        assertEquals(0, result.assignedPythonModuleCount)
        assertTrue(result.projectSdkAssigned)
    }

    @Test
    fun `project assignment is required even when every module is assigned`() {
        createVenv()
        platform.forcedReadback = PythonSdkReadback(
            registeredCount = 1,
            assignedLocalSdkCount = 1,
            assignedPythonModuleCount = 1,
            projectSdkAssigned = false,
            currentModuleModelMatches = true,
            sdkName = "Inspection .venv",
        )

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_readback_failed", result.reason)
        assertFalse(result.projectSdkAssigned)
    }

    @Test
    fun `split assignments across SDK objects cannot pass readback`() {
        createVenv()
        platform.forcedReadback = PythonSdkReadback(
            registeredCount = 1,
            assignedLocalSdkCount = 2,
            assignedPythonModuleCount = 1,
            projectSdkAssigned = true,
            currentModuleModelMatches = true,
            sdkName = "Inspection .venv",
        )

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_readback_failed", result.reason)
        assertEquals(2, result.assignedLocalPythonSdkCount)
    }

    @Test
    fun `module churn after commit cannot pass readback`() {
        createVenv()
        platform.forcedReadback = PythonSdkReadback(
            registeredCount = 1,
            assignedLocalSdkCount = 1,
            assignedPythonModuleCount = 1,
            projectSdkAssigned = true,
            currentModuleModelMatches = false,
            sdkName = "Inspection .venv",
        )

        val result = prepare()

        assertFalse(result.prepared)
        assertEquals("python_sdk_preparation_readback_failed", result.reason)
        assertEquals(listOf("create", "setup", "commit", "persist", "readback"), platform.mutationEvents)
    }

    private fun prepare(
        deadlineMs: Long = 5_000,
        onCancellationCheck: (Int) -> Unit = {},
    ): PythonSdkPreparationResult {
        var checks = 0
        val indicator = mockk<ProgressIndicator>(relaxed = true)
        every { indicator.checkCanceled() } answers {
            checks += 1
            onCancellationCheck(checks)
        }
        val service = PythonSdkPreparationService(
            platform = platform,
            now = clock::now,
            sleep = clock::sleep,
        )
        return service.prepare(
            PythonSdkPreparationRequest(
                project = project,
                projectRoot = projectRoot,
                deadlineMs = deadlineMs,
                indicator = indicator,
                ownershipIsCurrent = { true },
            ),
        )
    }

    private fun createVenv(symlinkTarget: Path? = null): Path {
        val interpreter = interpreterPath()
        Files.createDirectories(interpreter.parent)
        Files.writeString(projectRoot.resolve(".venv/pyvenv.cfg"), "home = fixture\n")
        if (symlinkTarget == null) {
            Files.writeString(interpreter, "#!/bin/sh\nexit 0\n")
            interpreter.toFile().setExecutable(true)
        } else {
            Files.createSymbolicLink(interpreter, symlinkTarget)
        }
        return interpreter.normalize().toAbsolutePath()
    }

    private fun interpreterPath(): Path = projectRoot.resolve(".venv").resolve(
        if (SystemInfo.isWindows) "Scripts/python.exe" else "bin/python",
    )

    private fun module(name: String): Module = mockk<Module>().also { module ->
        every { module.name } returns name
    }

    private fun snapshot(
        modules: List<Module>,
        projectSdk: Sdk? = null,
        moduleSdks: Map<Module, Sdk?> = modules.associateWith { null },
    ) = PythonSdkModelSnapshot(modules, projectSdk, moduleSdks)

    private fun sdk(name: String, home: String, typeName: String = "Python SDK"): Sdk = mockk<Sdk>().also { sdk ->
        every { sdk.name } returns name
        platform.homes[sdk] = home
        platform.typeNames[sdk] = typeName
    }

    private class TestClock {
        var timeMs: Long = 0

        fun now(): Long = timeMs

        fun sleep(millis: Long) {
            timeMs += millis
        }
    }

    private class FakePythonSdkPreparationPlatform(
        private val project: Project,
        initialModules: List<Module>,
    ) : PythonSdkPreparationPlatform {
        var trusted = true
        var pythonType: SdkType? = mockk<SdkType>().also { type ->
            every { type.name } returns "Python SDK"
        }
        var snapshots = ArrayDeque(
            listOf(
                PythonSdkModelSnapshot(initialModules, null, initialModules.associateWith { null }),
                PythonSdkModelSnapshot(initialModules, null, initialModules.associateWith { null }),
            ),
        )
        var modulesAtCommit: List<Module> = initialModules
        val registered = mutableListOf<Sdk>()
        val homes = IdentityHashMap<Sdk, String?>()
        val typeNames = IdentityHashMap<Sdk, String>()
        val validSdks = IdentityHashMap<Sdk, Boolean>()
        val mutationEvents = mutableListOf<String>()
        val detachedSdk: Sdk = mockk<Sdk>().also { sdk ->
            every { sdk.name } returns "Inspection .venv"
            typeNames[sdk] = "Python SDK"
        }
        var createdHome: String? = null
        var setupComplete = true
        var setupCompleteResults: ArrayDeque<Boolean>? = null
        var registeredSdkResults: ArrayDeque<List<Sdk>>? = null
        var setupFailure: Throwable? = null
        var readbackFailure: Throwable? = null
        var forcedReadback: PythonSdkReadback? = null
        var forcedCommitResult: PythonSdkCommitResult? = null
        var persistenceResult = PythonSdkPersistenceResult(true, "python_sdk_preparation_persisted")
        var sdkAppearingAtCommit: Sdk? = null
        var alreadyAssigned = false
        var committedDetachedSdk: Sdk? = null
        var assignmentMutationCount = 0
        var trustQueries = 0
        var snapshotQueries = 0
        var pythonTypeQueries = 0
        var commitQueries = 0
        var readbackQueries = 0
        var setupCompleteQueries = 0

        override fun isProjectTrusted(project: Project): Boolean {
            assertSame(this.project, project)
            trustQueries += 1
            return trusted
        }

        override fun snapshot(project: Project): PythonSdkModelSnapshot {
            assertSame(this.project, project)
            snapshotQueries += 1
            return if (snapshots.size > 1) snapshots.removeFirst() else snapshots.first()
        }

        override fun registeredSdks(): List<Sdk> {
            val results = registeredSdkResults ?: return registered.toList()
            return if (results.size > 1) results.removeFirst() else results.first()
        }

        override fun pythonSdkType(): SdkType? {
            pythonTypeQueries += 1
            return pythonType
        }

        override fun sdkHome(sdk: Sdk): String? = homes[sdk]

        override fun sdkTypeName(sdk: Sdk): String = typeNames[sdk].orEmpty()

        override fun isValidSdk(sdk: Sdk): Boolean = validSdks[sdk] == true

        override fun createDetachedSdk(
            existingSdks: Collection<Sdk>,
            interpreterHome: String,
            type: SdkType,
        ): Sdk {
            assertEquals(registered, existingSdks.toList())
            assertSame(pythonType, type)
            mutationEvents += "create"
            createdHome = interpreterHome
            homes[detachedSdk] = interpreterHome
            return detachedSdk
        }

        override fun setupSdkPaths(type: SdkType, sdk: Sdk, indicator: ProgressIndicator) {
            assertSame(pythonType, type)
            assertSame(detachedSdk, sdk)
            mutationEvents += "setup"
            setupFailure?.let { throw it }
        }

        override fun isSetupComplete(sdk: Sdk, interpreterHome: String): Boolean {
            assertEquals(interpreterHome, homes[sdk])
            setupCompleteQueries += 1
            val results = setupCompleteResults ?: return setupComplete
            return if (results.size > 1) results.removeFirst() else results.first()
        }

        override fun commit(
            project: Project,
            expectedModules: List<Module>,
            interpreterHome: String,
            detachedSdk: Sdk?,
            ownershipIsCurrent: () -> Boolean,
            indicator: ProgressIndicator,
        ): PythonSdkCommitResult {
            assertSame(this.project, project)
            commitQueries += 1
            mutationEvents += "commit"
            committedDetachedSdk = detachedSdk
            forcedCommitResult?.let { return it }
            if (!sameIdentity(expectedModules, modulesAtCommit)) {
                return PythonSdkCommitResult(false, "python_sdk_preparation_module_model_changed")
            }
            sdkAppearingAtCommit?.let { sdk ->
                registered += sdk
                sdkAppearingAtCommit = null
            }
            val matching = registered.filter { sdk ->
                homes[sdk] == interpreterHome && typeNames[sdk] == "Python SDK"
            }
            val selected = matching.singleOrNull() ?: detachedSdk
                ?: return PythonSdkCommitResult(false, "python_sdk_preparation_candidate_missing")
            if (matching.isEmpty()) registered += selected
            if (!alreadyAssigned) assignmentMutationCount += 1
            return PythonSdkCommitResult(
                succeeded = true,
                reason = "python_sdk_preparation_prepared",
                operation = when {
                    alreadyAssigned -> "already_assigned"
                    matching.isNotEmpty() -> "reused"
                    else -> "created"
                },
                sdk = selected,
            )
        }

        override fun persistSdkRegistry(): PythonSdkPersistenceResult {
            mutationEvents += "persist"
            return persistenceResult
        }

        override fun readback(
            project: Project,
            expectedModules: List<Module>,
            interpreterHome: String,
        ): PythonSdkReadback {
            assertSame(this.project, project)
            readbackQueries += 1
            mutationEvents += "readback"
            readbackFailure?.let { throw it }
            return forcedReadback ?: PythonSdkReadback(
                registeredCount = registered.count { sdk ->
                    homes[sdk] == interpreterHome && typeNames[sdk] == "Python SDK"
                },
                assignedLocalSdkCount = 1,
                assignedPythonModuleCount = expectedModules.size,
                projectSdkAssigned = true,
                currentModuleModelMatches = sameIdentity(expectedModules, modulesAtCommit),
                sdkName = registered.singleOrNull { sdk ->
                    homes[sdk] == interpreterHome && typeNames[sdk] == "Python SDK"
                }?.name,
            )
        }

        private fun sameIdentity(left: List<Module>, right: List<Module>): Boolean {
            return left.size == right.size && left.indices.all { index -> left[index] === right[index] }
        }
    }
}
