package com.shiny.inspectionmcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import io.mockk.*
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.psi.util.PsiModificationTracker
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise
import org.jetbrains.concurrency.rejectedPromise
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame

internal class InspectionHandlerLifecycleTest : InspectionHandlerTestSupport() {
    @Test
    fun `test python scope without sdk publishes capture incomplete before inspection`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        val inputFingerprint = projectInputsFingerprint(profileName = "TestProfile")
        handler.projectInputsFingerprintProvider = { _, _ -> inputFingerprint }
        handler.projectContentTrackerFactory = { _, _ -> FakeInspectionProjectContentTracker() }
        handler.projectAnalysisReadinessProvider = { _, _ ->
            InspectionProjectAnalysisReadiness(
                required = true,
                ready = false,
                reason = "python_sdk_missing",
                pythonFileCount = 2,
                missingSdkFileCount = 2,
            )
        }

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")
        val body = response.content().toString(Charsets.UTF_8)
        val status = buildInspectionStatus()
        val statusResponse = processGetRequest("/api/inspection/status")
        val statusBody = statusResponse.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"scope\": \"whole_project\""), body)
        assertEquals("capture_incomplete", status["snapshot_outcome"])
        assertEquals(true, status["capture_incomplete"])
        assertEquals("language_sdk_missing", status["capture_incomplete_reason"])
        assertEquals("project_analysis_readiness", status["results_source"])
        assertEquals("UNKNOWN", status["inspection_verdict"])
        assertEquals(0, status["total_problems"])
        assertTrue(statusBody.contains("\"classification\": \"configuration_blocked\""), statusBody)
        assertTrue(statusBody.contains("\"code\": \"language_sdk_missing\""), statusBody)
        @Suppress("UNCHECKED_CAST")
        val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
        assertEquals("inspection_preflight", diagnostic["readiness_stage"])
        assertEquals("whole_project", diagnostic["requested_scope"])
        assertEquals("whole_project", diagnostic["resolved_scope"])
        assertEquals(2, diagnostic["selected_python_file_count"])
        assertEquals("available", diagnostic["language_support_state"])
        assertEquals("missing", diagnostic["sdk_assignment_state"])
        assertEquals("python_sdk_missing", diagnostic["analysis_state"])
        assertEquals(false, diagnostic["inspection_started"])
        assertEquals("configuration", diagnostic["outcome_ownership"])
        verify(exactly = 0) { mockInspectionManager.createNewGlobalContext() }
    }

    @Test
    fun `test local Python SDK registration settles before stable input fingerprinting`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        val readinessSequence = ArrayDeque(
            listOf(
                pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
            ),
        )
        var currentReadiness = readinessSequence.removeFirst()
        var currentModificationCount = 10L
        var fingerprintCalls = 0
        every { PsiModificationTracker.getInstance(mockProject).modificationCount } answers {
            currentModificationCount
        }
        handler.pythonSdkSettleTimeoutMs = 100
        handler.pythonSdkSettlePollMs = 10
        handler.pythonSdkSettleNow = { 0L }
        handler.pythonSdkSettleSleep = {}
        handler.projectAnalysisReadinessProvider = { _, _ -> currentReadiness }
        handler.projectAnalysisReadinessRefreshProvider = { _, _, _, _ ->
            currentReadiness = readinessSequence.removeFirst()
            if (currentReadiness.ready) {
                currentModificationCount = 11L
            }
            currentReadiness
        }
        handler.projectInputsFingerprintProvider = { _, _ ->
            assertTrue(currentReadiness.ready, "SDK readiness must settle before fingerprinting.")
            fingerprintCalls += 1
            projectInputsFingerprint(profileName = "TestProfile")
        }

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(1, fingerprintCalls)
        assertEquals(
            11L,
            requireNotNull(InspectionResultsStore.getSnapshot(projectKey(mockProject))).projectState.psiModificationCount,
            "SDK registration changes must be excluded from capture churn validation.",
        )
    }

    @Test
    fun `test local Python SDK registration timeout remains language SDK missing`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"
        every { mockApplication.isDispatchThread } returns true
        every { mockProfileManager.profiles } returns listOf(mockProfile)
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        mockInspectionPrerequisites(mockProject)

        var refreshCalls = 0
        handler.pythonSdkSettleTimeoutMs = 25
        handler.pythonSdkSettlePollMs = 10
        handler.pythonSdkSettleNow = { 0L }
        handler.pythonSdkSettleSleep = {}
        handler.projectAnalysisReadinessProvider = { _, _ ->
            pythonSdkReadiness(ready = false, localInterpreterCandidate = true)
        }
        handler.projectAnalysisReadinessRefreshProvider = { _, _, _, _ ->
            refreshCalls += 1
            pythonSdkReadiness(ready = false, localInterpreterCandidate = true)
        }
        handler.projectInputsFingerprintProvider = { _, _ ->
            error("Terminal SDK absence must not fingerprint inspection inputs.")
        }

        val response = processTriggerRequest("/api/inspection/trigger?scope=whole_project")
        val status = buildInspectionStatus()

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(2, refreshCalls)
        assertEquals("language_sdk_missing", status["capture_incomplete_reason"])
        @Suppress("UNCHECKED_CAST")
        val diagnostic = status["capture_diagnostic"] as Map<String, Any?>
        assertEquals(true, diagnostic["python_sdk_settle_attempted"])
        assertEquals(true, diagnostic["python_sdk_settle_timed_out"])
        assertEquals(25L, diagnostic["python_sdk_settle_elapsed_ms"])
    }

    @Test
    fun `test Python SDK settle skips missing SDK without local interpreter candidate`() {
        var sleepCalls = 0
        var observationCalls = 0

        val result = settlePythonSdkReadiness(
            initialReadiness = pythonSdkReadiness(ready = false, localInterpreterCandidate = false),
            now = { 0L },
            sleep = { sleepCalls += 1 },
            observe = {
                observationCalls += 1
                error("No local interpreter candidate must not be retried.")
            },
            checkCanceled = {},
        )

        assertFalse(result.evidence.attempted)
        assertEquals(0, sleepCalls)
        assertEquals(0, observationCalls)
        assertEquals("python_sdk_missing", result.readiness.reason)
    }

    @Test
    fun `test Python SDK settle is zero cost for non Python scopes`() {
        var sleepCalls = 0
        var observationCalls = 0
        val initialReadiness = InspectionProjectAnalysisReadiness(
            required = false,
            ready = true,
            reason = "python_not_in_scope",
        )

        val result = settlePythonSdkReadiness(
            initialReadiness = initialReadiness,
            now = { 0L },
            sleep = { sleepCalls += 1 },
            observe = {
                observationCalls += 1
                error("Non-Python scopes must not be retried.")
            },
            checkCanceled = {},
        )

        assertFalse(result.evidence.attempted)
        assertEquals(0, sleepCalls)
        assertEquals(0, observationCalls)
        assertEquals(initialReadiness, result.readiness)
    }

    @Test
    fun `test Python SDK settle requires consecutive ready observations`() {
        val observations = ArrayDeque(
            listOf(
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
            ),
        )
        var sleepCalls = 0

        val result = settlePythonSdkReadiness(
            initialReadiness = pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
            now = { 0L },
            sleep = { sleepCalls += 1 },
            observe = { observations.removeFirst() },
            checkCanceled = {},
            timeoutMs = 100,
            pollMs = 10,
        )

        assertEquals(4, sleepCalls)
        assertEquals(5, result.evidence.observationCount)
        assertEquals(2, result.evidence.stableReadyObservations)
        assertTrue(result.readiness.ready)
        assertFalse(result.evidence.timedOut)
    }

    @Test
    fun `test Python SDK settle extends deadline for registration and assignment progress`() {
        val observations = ArrayDeque(
            listOf(
                pythonSdkReadiness(
                    ready = false,
                    localInterpreterCandidate = true,
                    reason = "python_sdk_assignment_pending",
                    registeredLocalPythonSdkCount = 1,
                ),
                pythonSdkReadiness(
                    ready = false,
                    localInterpreterCandidate = true,
                    reason = "python_sdk_updating",
                    registeredLocalPythonSdkCount = 1,
                    assignedLocalPythonSdkCount = 1,
                ),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
            ),
        )

        val result = settlePythonSdkReadiness(
            initialReadiness = pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
            now = { 0L },
            sleep = {},
            observe = { observations.removeFirst() },
            checkCanceled = {},
            timeoutMs = 20,
            pollMs = 10,
            progressGraceMs = 30,
            maxTimeoutMs = 60,
        )

        assertTrue(result.readiness.ready)
        assertEquals(2, result.evidence.stableReadyObservations)
        assertEquals(3, result.evidence.deadlineExtensionCount)
        assertEquals(60L, result.evidence.activeDeadlineMs)
        assertTrue(result.evidence.observedRegisteredLocalSdk)
        assertTrue(result.evidence.observedAssignedLocalSdk)
        assertFalse(result.evidence.timedOut)
    }

    @Test
    fun `test Python SDK settle does not observe after an overslept deadline`() {
        var currentTimeMs = 0L
        var observationCalls = 0

        val result = settlePythonSdkReadiness(
            initialReadiness = pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
            now = { currentTimeMs },
            sleep = { sleepMs -> currentTimeMs += sleepMs + 20 },
            observe = {
                observationCalls += 1
                pythonSdkReadiness(
                    ready = false,
                    localInterpreterCandidate = true,
                    reason = "python_sdk_assignment_pending",
                    registeredLocalPythonSdkCount = 1,
                )
            },
            checkCanceled = {},
            timeoutMs = 20,
            pollMs = 10,
            progressGraceMs = 20,
            maxTimeoutMs = 60,
        )

        assertTrue(result.evidence.timedOut)
        assertEquals(30L, result.evidence.elapsedMs)
        assertEquals(0, observationCalls)
        assertEquals(0, result.evidence.deadlineExtensionCount)
        assertFalse(result.evidence.observedRegisteredLocalSdk)
    }

    @Test
    fun `test Python SDK settle does not start another observation at the deadline`() {
        var currentTimeMs = 0L
        var observationCalls = 0

        val result = settlePythonSdkReadiness(
            initialReadiness = pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
            now = { currentTimeMs },
            sleep = { sleepMs -> currentTimeMs += sleepMs },
            observe = {
                observationCalls += 1
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true)
            },
            checkCanceled = {},
            timeoutMs = 20,
            pollMs = 10,
            progressGraceMs = 20,
            maxTimeoutMs = 20,
        )

        assertFalse(result.readiness.ready)
        assertEquals("python_sdk_missing", result.readiness.reason)
        assertEquals(1, result.evidence.stableReadyObservations)
        assertTrue(result.evidence.timedOut)
        assertEquals("python_sdk_missing", result.evidence.finalReason)
        assertEquals(1, observationCalls)
        assertEquals(20L, result.evidence.elapsedMs)
    }

    @Test
    fun `test Python SDK settle discards an observation that overruns the deadline`() {
        var currentTimeMs = 0L
        var observationCalls = 0

        val result = settlePythonSdkReadiness(
            initialReadiness = pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
            now = { currentTimeMs },
            sleep = { sleepMs -> currentTimeMs += sleepMs },
            observe = {
                observationCalls += 1
                currentTimeMs += 11
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true)
            },
            checkCanceled = {},
            timeoutMs = 20,
            pollMs = 10,
            progressGraceMs = 20,
            maxTimeoutMs = 20,
        )

        assertFalse(result.readiness.ready)
        assertEquals("python_sdk_missing", result.readiness.reason)
        assertEquals(0, result.evidence.stableReadyObservations)
        assertTrue(result.evidence.timedOut)
        assertEquals(21L, result.evidence.elapsedMs)
        assertEquals(1, observationCalls)
    }

    @Test
    fun `test Python SDK settle continues through assigned SDK update`() {
        val observations = ArrayDeque(
            listOf(
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
                pythonSdkReadiness(ready = true, localInterpreterCandidate = true),
            ),
        )

        val result = settlePythonSdkReadiness(
            initialReadiness = InspectionProjectAnalysisReadiness(
                required = true,
                ready = false,
                reason = "python_sdk_updating",
                pythonFileCount = 1,
                pythonSdkCount = 1,
                updatingSdkCount = 1,
                localPythonInterpreterCandidate = true,
            ),
            now = { 0L },
            sleep = {},
            observe = { observations.removeFirst() },
            checkCanceled = {},
            timeoutMs = 100,
            pollMs = 10,
        )

        assertTrue(result.readiness.ready)
        assertEquals(2, result.evidence.stableReadyObservations)
        assertFalse(result.evidence.timedOut)
    }

    @Test
    fun `test Python SDK settle honors cancellation`() {
        var sleepCalls = 0

        assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
            settlePythonSdkReadiness(
                initialReadiness = pythonSdkReadiness(ready = false, localInterpreterCandidate = true),
                now = { 0L },
                sleep = { sleepCalls += 1 },
                observe = { pythonSdkReadiness(ready = false, localInterpreterCandidate = true) },
                checkCanceled = { throw com.intellij.openapi.progress.ProcessCanceledException() },
            )
        }

        assertEquals(0, sleepCalls)
    }

    @Test
    fun `test python analysis requires two identical SDK-backed snapshots`() {
        val readiness = InspectionProjectAnalysisReadiness(
            required = true,
            ready = true,
            reason = "ready",
            pythonFileCount = 1,
            pythonSdkCount = 1,
        )
        val fingerprint = projectInputsFingerprint(profileName = "qualification-identical")
        val snapshot = changedFilesSnapshot(
            problems = listOf(changedFileProblem(file = "/tmp/TestProject/qualification-identical.py")),
            resolvedFiles = listOf("/tmp/TestProject/qualification-identical.py"),
        )

        val first = qualifyProjectAnalysisSnapshot(snapshot, readiness, fingerprint)
        val second = qualifyProjectAnalysisSnapshot(snapshot, readiness, fingerprint)

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, first.outcome)
        assertEquals(CaptureIncompleteReason.PROJECT_ANALYSIS_NOT_READY, first.captureIncompleteReason)
        assertTrue(first.problems.isEmpty())
        assertEquals(InspectionSnapshotOutcome.PROBLEMS_FOUND, second.outcome)
        assertEquals(1, second.problems.size)
    }

    @Test
    fun `test python analysis fingerprint change resets qualification`() {
        val readiness = InspectionProjectAnalysisReadiness(
            required = true,
            ready = true,
            reason = "ready",
            pythonFileCount = 1,
            pythonSdkCount = 1,
        )
        val snapshot = changedFilesSnapshot(
            problems = listOf(changedFileProblem(file = "/tmp/TestProject/qualification-reset.py")),
            resolvedFiles = listOf("/tmp/TestProject/qualification-reset.py"),
        )
        val firstFingerprint = projectInputsFingerprint(profileName = "qualification-reset")
        val changedFingerprint = firstFingerprint.copy(
            moduleSdkStates = listOf("TestProject\u0000Updated SDK\u0000Python SDK\u00003.14\u0000/tmp/python-3.14"),
        )

        qualifyProjectAnalysisSnapshot(snapshot, readiness, firstFingerprint)
        val result = qualifyProjectAnalysisSnapshot(snapshot, readiness, changedFingerprint)

        assertEquals(InspectionSnapshotOutcome.CAPTURE_INCOMPLETE, result.outcome)
        assertEquals(CaptureIncompleteReason.PROJECT_ANALYSIS_NOT_READY, result.captureIncompleteReason)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `test getCurrentProject prefers the focused data context project over open project order`() {
        val firstOpenProject = usableProject("FirstOpenProject")
        val dataContextProject = usableProject("DataContextProject")
        every { mockProjectManager.openProjects } returns arrayOf(firstOpenProject, dataContextProject)

        val mockIdeFocusManager = mockk<IdeFocusManager>()
        every { mockIdeFocusManager.lastFocusedFrame } returns null
        every { IdeFocusManager.getGlobalInstance() } returns mockIdeFocusManager

        val dataContext = mockk<DataContext>()
        val mockDataManager = mockk<DataManager>()
        every { mockDataManager.dataContextFromFocusAsync } returns resolvedPromise(dataContext)
        every { DataManager.getInstance() } returns mockDataManager
        every { CommonDataKeys.PROJECT.getData(dataContext) } returns dataContextProject

        assertSame(dataContextProject, currentProjectWithoutSelector())
    }

    @Test
    fun `test getCurrentProject never falls back to a disposed open project`() {
        val disposedProject = usableProject("DisposedProject")
        every { disposedProject.isDisposed } returns true
        every { mockProjectManager.openProjects } returns arrayOf(disposedProject)

        val mockIdeFocusManager = mockk<IdeFocusManager>()
        every { mockIdeFocusManager.lastFocusedFrame } returns null
        every { IdeFocusManager.getGlobalInstance() } returns mockIdeFocusManager

        val mockDataManager = mockk<DataManager>()
        val promise: Promise<DataContext> = rejectedPromise("No context")
        every { mockDataManager.dataContextFromFocusAsync } returns promise
        every { DataManager.getInstance() } returns mockDataManager

        assertNull(currentProjectWithoutSelector())
    }

    @Test
    fun `test getCurrentProject with multiple projects returns active one`() {
        val mockProject1 = mockk<Project>()
        val mockProject2 = mockk<Project>()
        val mockProject3 = mockk<Project>()
        
        every { mockProject1.isDefault } returns false
        every { mockProject1.isDisposed } returns false
        every { mockProject1.isInitialized } returns true
        every { mockProject1.name } returns "Project1"
        
        every { mockProject2.isDefault } returns false
        every { mockProject2.isDisposed } returns false
        every { mockProject2.isInitialized } returns true
        every { mockProject2.name } returns "ActiveProject"
        
        every { mockProject3.isDefault } returns false
        every { mockProject3.isDisposed } returns false
        every { mockProject3.isInitialized } returns true
        every { mockProject3.name } returns "Project3"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject1, mockProject2, mockProject3)
        
        val mockIdeFocusManager = mockk<IdeFocusManager>()
        val mockIdeFrame = mockk<IdeFrame>()
        every { mockIdeFrame.project } returns mockProject2
        every { mockIdeFocusManager.lastFocusedFrame } returns mockIdeFrame
        every { IdeFocusManager.getGlobalInstance() } returns mockIdeFocusManager
        
        val mockWindow1 = mockk<JFrame>()
        val mockWindow2 = mockk<JFrame>()
        val mockWindow3 = mockk<JFrame>()
        
        every { mockWindow1.isActive } returns false
        every { mockWindow2.isActive } returns true
        every { mockWindow3.isActive } returns false
        
        every { mockWindowManager.suggestParentWindow(mockProject1) } returns mockWindow1
        every { mockWindowManager.suggestParentWindow(mockProject2) } returns mockWindow2
        every { mockWindowManager.suggestParentWindow(mockProject3) } returns mockWindow3
        
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true
        
        val result = method.invoke(handler, null) as Project?
        
        assertNotNull(result)
        assertEquals("ActiveProject", result?.name)
    }

    @Test
    fun `test getCurrentProject with no active window returns first valid project`() {
        val mockProject1 = mockk<Project>()
        val mockProject2 = mockk<Project>()
        
        every { mockProject1.isDefault } returns false
        every { mockProject1.isDisposed } returns false
        every { mockProject1.isInitialized } returns true
        every { mockProject1.name } returns "FirstProject"
        
        every { mockProject2.isDefault } returns false
        every { mockProject2.isDisposed } returns false
        every { mockProject2.isInitialized } returns true
        every { mockProject2.name } returns "SecondProject"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject1, mockProject2)
        
        val mockIdeFocusManager = mockk<IdeFocusManager>()
        every { mockIdeFocusManager.lastFocusedFrame } returns null
        every { IdeFocusManager.getGlobalInstance() } returns mockIdeFocusManager
        
        val mockDataManager = mockk<DataManager>()
        val promise: Promise<DataContext> = rejectedPromise("No context")
        every { mockDataManager.dataContextFromFocusAsync } returns promise
        every { DataManager.getInstance() } returns mockDataManager
        
        val mockWindow1 = mockk<JFrame>()
        val mockWindow2 = mockk<JFrame>()
        
        every { mockWindow1.isActive } returns false
        every { mockWindow2.isActive } returns false
        
        every { mockWindowManager.suggestParentWindow(mockProject1) } returns mockWindow1
        every { mockWindowManager.suggestParentWindow(mockProject2) } returns mockWindow2
        
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true
        
        val result = method.invoke(handler, null) as Project?
        
        assertNotNull(result)
        assertEquals("FirstProject", result?.name)
    }

    @Test
    fun `test getCurrentProject prefers the open project whose window is active`() {
        val firstOpenProject = usableProject("FirstOpenProject")
        val activeWindowProject = usableProject("ActiveWindowProject")
        every { mockProjectManager.openProjects } returns arrayOf(firstOpenProject, activeWindowProject)

        val mockIdeFocusManager = mockk<IdeFocusManager>()
        every { mockIdeFocusManager.lastFocusedFrame } returns null
        every { IdeFocusManager.getGlobalInstance() } returns mockIdeFocusManager

        val mockDataManager = mockk<DataManager>()
        val promise: Promise<DataContext> = rejectedPromise("No context")
        every { mockDataManager.dataContextFromFocusAsync } returns promise
        every { DataManager.getInstance() } returns mockDataManager

        val inactiveWindow = mockk<JFrame>()
        val activeWindow = mockk<JFrame>()
        every { inactiveWindow.isActive } returns false
        every { activeWindow.isActive } returns true
        every { mockWindowManager.suggestParentWindow(firstOpenProject) } returns inactiveWindow
        every { mockWindowManager.suggestParentWindow(activeWindowProject) } returns activeWindow

        assertSame(activeWindowProject, currentProjectWithoutSelector())
    }

    @Test
    fun `test getCurrentProject with explicit project name`() {
        val mockProject1 = mockk<Project>()
        val mockProject2 = mockk<Project>()
        
        every { mockProject1.isDefault } returns false
        every { mockProject1.isDisposed } returns false
        every { mockProject1.isInitialized } returns true
        every { mockProject1.name } returns "ProjectOne"
        
        every { mockProject2.isDefault } returns false
        every { mockProject2.isDisposed } returns false
        every { mockProject2.isInitialized } returns true
        every { mockProject2.name } returns "ProjectTwo"
        
        every { mockProjectManager.openProjects } returns arrayOf(mockProject1, mockProject2)
        
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true
        
        val result1 = method.invoke(handler, "ProjectTwo") as Project?
        assertNotNull(result1)
        assertEquals("ProjectTwo", result1?.name)
        
        val result2 = method.invoke(handler, "ProjectOne") as Project?
        assertNotNull(result2)
        assertEquals("ProjectOne", result2?.name)
        
        val result3 = method.invoke(handler, "NonExistent") as Project?
        assertNull(result3)
    }

    @Test
    fun `test getCurrentProject treats blank project name as fallback selector`() {
        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true

        val result = method.invoke(handler, "   ") as Project?

        assertNotNull(result)
        assertEquals("TestProject", result?.name)
    }

    @Test
    fun `test route endpoint reports session drift as conflict`() {
        val response = processGetRequest(
            "/api/inspection/route?session_id=old-session&client_run_id=cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.CONFLICT, response.status())
        assertTrue(body.contains("\"session_drift\": true"))
        assertTrue(body.contains("\"inspection_verdict\": \"UNKNOWN\""))
        assertTrue(body.contains("\"inspection_verdict_reason\": \"session_drift\""))
        assertTrue(body.contains("\"expected_session_id\": \"old-session\""))
        assertTrue(body.contains("\"classification\": \"legitimate_fail_closed\""), body)
        assertTrue(body.contains("\"phase\": \"route\""), body)
        assertTrue(body.contains("\"http_status\": 409"), body)
        assertTrue(body.contains("\"client_run_id\": \"cccccccc-cccc-4ccc-8ccc-cccccccccccc\""), body)
    }

    @Test
    fun `test route endpoint selects the most specific nested project`() {
        val parentProject = mockProject(
            name = "Parent",
            basePath = "/repo",
            projectFilePath = "/repo/.idea/misc.xml",
        )
        val childProject = mockProject(
            name = "Child",
            basePath = "/repo/packages/app",
            projectFilePath = "/repo/packages/app/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(parentProject, childProject)

        val response = processGetRequest("/api/inspection/route?cwd=/repo/packages/app/src")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"Child\""))
        assertFalse(body.contains("Multiple open projects matched this request"))
    }

    @Test
    fun `test route endpoint requires exact worktree path`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"

        val response = processGetRequest("/api/inspection/route?worktree_path=/repo/app/src")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"no_project\""))
    }

    @Test
    fun `test route endpoint includes project instance id`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"

        val response = processGetRequest("/api/inspection/route?worktree_path=/repo/app")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_instance_id\""))
        assertTrue(body.contains("\"inspection_execution_proof_version\": $INSPECTION_EXECUTION_PROOF_VERSION"))
        assertTrue(body.contains("\"python_sdk_preparation_version\": $PYTHON_SDK_PREPARATION_VERSION"))
    }

    @Test
    fun `test Python SDK preparation endpoint supports only POST`() {
        val post = mockk<FullHttpRequest>()
        every { post.uri() } returns "/api/inspection/lifecycle/prepare-python-sdk"
        every { post.method() } returns HttpMethod.POST
        val get = mockk<FullHttpRequest>()
        every { get.uri() } returns "/api/inspection/lifecycle/prepare-python-sdk"
        every { get.method() } returns HttpMethod.GET

        assertTrue(handler.isSupported(post))
        assertFalse(handler.isSupported(get))

        val malformed = mockk<FullHttpRequest>()
        every { malformed.uri() } returns "/api/inspection/%ZZ"
        every { malformed.method() } returns HttpMethod.GET
        assertDoesNotThrow { handler.isSupported(malformed) }
    }

    @Test
    fun `test Python SDK preparation requires exact ownership pins and returns readback evidence`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = requireNotNull(Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1))
        val scheduledWorker = AtomicReference<Runnable?>()
        var timeoutCancelled = false
        handler.pythonSdkPreparationExecutor = { worker -> scheduledWorker.set(worker) }
        handler.schedulePythonSdkPreparationTimeout = { _, _ -> { timeoutCancelled = true } }
        handler.pythonSdkPreparationRunner = { request ->
            assertSame(mockProject, request.project)
            assertEquals(Paths.get("/repo/app").toAbsolutePath().normalize(), request.projectRoot)
            PythonSdkPreparationResult(
                prepared = true,
                reason = "python_sdk_preparation_prepared",
                operation = "created",
                interpreterHome = "/repo/app/.venv/bin/python",
                sdkName = "Inspection .venv",
                pythonModuleCount = 1,
                registeredLocalPythonSdkCount = 1,
                assignedLocalPythonSdkCount = 1,
                assignedPythonModuleCount = 1,
                projectSdkAssigned = true,
            )
        }
        val uri = pythonSdkPreparationUri(instanceId, token)

        val responses = processRequestResponses(uri, HttpMethod.POST)
        assertTrue(responses.isEmpty())
        requireNotNull(scheduledWorker.get()).run()

        assertEquals(1, responses.size)
        val response = responses.single()
        val body = response.content().toString(Charsets.UTF_8)
        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"prepared\""))
        assertTrue(body.contains("\"sdk_preparation_version\": $PYTHON_SDK_PREPARATION_VERSION"))
        assertTrue(body.contains("\"project_instance_id\": \"$instanceId\""))
        assertTrue(body.contains("\"project_key\": \"path:/repo/app\""))
        assertTrue(body.contains("\"session_id\": \"${InspectionIdeSession.sessionId}\""))
        assertTrue(body.contains("\"operation\": \"created\""))
        assertTrue(body.contains("\"registered_local_python_sdk_count\": 1"))
        assertTrue(body.contains("\"assigned_local_python_sdk_count\": 1"))
        assertTrue(body.contains("\"assigned_python_module_count\": 1"))
        assertTrue(body.contains("\"project_sdk_assigned\": true"))
        assertTrue(timeoutCancelled)

        handler.pythonSdkPreparationExecutor = { worker -> worker.run() }
        handler.pythonSdkPreparationRunner = {
            PythonSdkPreparationResult(
                prepared = false,
                reason = "python_sdk_preparation_existing_sdk_incomplete",
                interpreterHome = "/repo/app/.venv/bin/python",
                pythonModuleCount = 1,
                registeredLocalPythonSdkCount = 1,
                readbackObserved = true,
            )
        }
        val incomplete = processRequest(uri, HttpMethod.POST)
        val incompleteBody = incomplete.content().toString(Charsets.UTF_8)
        assertEquals(HttpResponseStatus.CONFLICT, incomplete.status())
        assertTrue(incompleteBody.contains("\"registered_local_python_sdk_count\": 1"), incompleteBody)
        assertTrue(incompleteBody.contains("\"classification\": \"configuration_blocked\""), incompleteBody)

        handler.pythonSdkPreparationRunner = {
            PythonSdkPreparationResult(
                prepared = false,
                reason = "python_sdk_preparation_existing_sdk_changed",
                interpreterHome = "/repo/app/.venv/bin/python",
                pythonModuleCount = 1,
                registeredLocalPythonSdkCount = 1,
                readbackObserved = true,
            )
        }
        val changed = processRequest(uri, HttpMethod.POST)
        val changedBody = changed.content().toString(Charsets.UTF_8)
        assertEquals(HttpResponseStatus.CONFLICT, changed.status())
        assertTrue(changedBody.contains("\"classification\": \"legitimate_fail_closed\""), changedBody)

        handler.pythonSdkPreparationRunner = {
            PythonSdkPreparationResult(
                prepared = false,
                reason = "python_sdk_preparation_persistence_failed",
                interpreterHome = "/repo/app/.venv/bin/python",
                pythonModuleCount = 1,
                registeredLocalPythonSdkCount = 1,
                assignedLocalPythonSdkCount = 1,
                assignedPythonModuleCount = 1,
                projectSdkAssigned = true,
                readbackObserved = true,
            )
        }
        val persistenceFailed = processRequest(uri, HttpMethod.POST)
        val persistenceFailedBody = persistenceFailed.content().toString(Charsets.UTF_8)
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, persistenceFailed.status())
        assertTrue(persistenceFailedBody.contains("\"classification\": \"tool_caused\""), persistenceFailedBody)
    }

    @Test
    fun `test Python SDK preparation refuses missing ownership and forbidden interpreter input`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        var runnerCalls = 0
        handler.pythonSdkPreparationRunner = {
            runnerCalls += 1
            error("must not run")
        }

        val notClaimed = processRequest(
            pythonSdkPreparationUri(instanceId, "missing-token"),
            HttpMethod.POST,
        )
        val forbiddenAliases = listOf("interpreter", "interpreter_path", "sdk_home").map { alias ->
            processRequest(
                pythonSdkPreparationUri(instanceId, "missing-token") + "&$alias=/tmp/python",
                HttpMethod.POST,
            )
        }
        val forbiddenBody = processRequest(
            pythonSdkPreparationUri(instanceId, "missing-token"),
            HttpMethod.POST,
            Unpooled.copiedBuffer("{}", Charsets.UTF_8),
        )

        assertEquals(HttpResponseStatus.CONFLICT, notClaimed.status())
        val notClaimedBody = notClaimed.content().toString(Charsets.UTF_8)
        assertTrue(notClaimedBody.contains("python_sdk_preparation_not_claimed"))
        assertTrue(notClaimedBody.contains("\"classification\": \"legitimate_fail_closed\""), notClaimedBody)
        forbiddenAliases.forEach { forbidden ->
            assertEquals(HttpResponseStatus.BAD_REQUEST, forbidden.status())
            assertTrue(forbidden.content().toString(Charsets.UTF_8).contains("python_sdk_preparation_interpreter_input_forbidden"))
        }
        assertEquals(HttpResponseStatus.BAD_REQUEST, forbiddenBody.status())
        assertTrue(forbiddenBody.content().toString(Charsets.UTF_8).contains("python_sdk_preparation_interpreter_input_forbidden"))
        assertEquals(0, runnerCalls)
    }

    @Test
    fun `test Python SDK preparation rejects every stale or mismatched ownership pin`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = requireNotNull(Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1))
        var runnerCalls = 0
        handler.pythonSdkPreparationRunner = {
            runnerCalls += 1
            error("must not run")
        }
        val valid = pythonSdkPreparationUri(instanceId, token)

        val wrongToken = processRequest(valid.replace("close_token=$token", "close_token=wrong"), HttpMethod.POST)
        val wrongLease = processRequest(valid.replace("lease_id=test-lease", "lease_id=other"), HttpMethod.POST)
        val staleSession = processRequest(
            valid.replace("session_id=${InspectionIdeSession.sessionId}", "session_id=old-session"),
            HttpMethod.POST,
        )
        val wrongProject = processRequest(valid.replace("project_key=path:/repo/app", "project_key=path:/repo/other"), HttpMethod.POST)
        val missingSession = processRequest(
            valid.replace("&session_id=${InspectionIdeSession.sessionId}", ""),
            HttpMethod.POST,
        )

        assertEquals(HttpResponseStatus.FORBIDDEN, wrongToken.status())
        val wrongTokenBody = wrongToken.content().toString(Charsets.UTF_8)
        assertTrue(wrongTokenBody.contains("python_sdk_preparation_token_mismatch"))
        assertTrue(wrongTokenBody.contains("\"classification\": \"legitimate_fail_closed\""), wrongTokenBody)
        assertEquals(HttpResponseStatus.FORBIDDEN, wrongLease.status())
        assertTrue(wrongLease.content().toString(Charsets.UTF_8).contains("python_sdk_preparation_lease_mismatch"))
        assertEquals(HttpResponseStatus.CONFLICT, staleSession.status())
        assertTrue(staleSession.content().toString(Charsets.UTF_8).contains("python_sdk_preparation_session_drift"))
        assertEquals(HttpResponseStatus.CONFLICT, wrongProject.status())
        assertTrue(wrongProject.content().toString(Charsets.UTF_8).contains("python_sdk_preparation_claim_mismatch"))
        assertEquals(HttpResponseStatus.BAD_REQUEST, missingSession.status())
        assertTrue(missingSession.content().toString(Charsets.UTF_8).contains("python_sdk_preparation_missing_session_id"))
        assertEquals(0, runnerCalls)
    }

    @Test
    fun `test Python SDK preparation serializes calls and close while worker is active`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = requireNotNull(Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1))
        val worker = AtomicReference<Runnable?>()
        val timeout = AtomicReference<Runnable?>()
        handler.pythonSdkPreparationExecutor = { task -> worker.set(task) }
        handler.schedulePythonSdkPreparationTimeout = { task, _ ->
            timeout.set(task)
            val cancel: () -> Unit = {}
            cancel
        }
        handler.lifecycleCloseExecutor = { task -> task.run() }
        val uri = pythonSdkPreparationUri(instanceId, token)
        val firstResponses = processRequestResponses(uri, HttpMethod.POST)

        val duplicate = processRequest(uri, HttpMethod.POST)
        val close = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token&lease_id=test-lease"
        )

        assertTrue(firstResponses.isEmpty())
        assertEquals(HttpResponseStatus.CONFLICT, duplicate.status())
        assertTrue(duplicate.content().toString(Charsets.UTF_8).contains("python_sdk_preparation_in_progress"))
        assertEquals(HttpResponseStatus.CONFLICT, close.status())
        assertTrue(close.content().toString(Charsets.UTF_8).contains("\"python_sdk_preparation_in_progress\": true"))
        assertTrue(lifecycleLeases().containsKey(instanceId))

        requireNotNull(timeout.get()).run()
        assertEquals(HttpResponseStatus.REQUEST_TIMEOUT, firstResponses.single().status())
        assertTrue(firstResponses.single().content().toString(Charsets.UTF_8).contains("python_sdk_preparation_timeout"))
        handler.pythonSdkPreparationRunner = { request ->
            assertTrue(request.indicator.isCanceled)
            PythonSdkPreparationResult(false, "python_sdk_preparation_cancelled")
        }
        requireNotNull(worker.get()).run()
        assertEquals(1, firstResponses.size)

        handler.pythonSdkPreparationRunner = {
            PythonSdkPreparationResult(true, "python_sdk_preparation_prepared", operation = "reused")
        }
        handler.pythonSdkPreparationExecutor = { task -> task.run() }
        handler.schedulePythonSdkPreparationTimeout = { _, _ -> {} }
        val retry = processRequest(uri, HttpMethod.POST)
        assertEquals(HttpResponseStatus.OK, retry.status())
    }

    @Test
    fun `test Python SDK preparation refuses an active inspection without cancelling it`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = requireNotNull(Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1))
        setInspectionRunState(
            projectKey(mockProject),
            InspectionRunState(
                runId = 17L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = true,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        val inspectionIndicator = mockk<ProgressIndicator>(relaxed = true)
        setInspectionRunControl(
            projectKey(mockProject),
            InspectionRunControl(runId = 17L, indicator = inspectionIndicator),
        )
        var runnerCalls = 0
        handler.pythonSdkPreparationRunner = {
            runnerCalls += 1
            error("must not run")
        }

        val response = processRequest(pythonSdkPreparationUri(instanceId, token), HttpMethod.POST)

        assertEquals(HttpResponseStatus.CONFLICT, response.status())
        val body = response.content().toString(Charsets.UTF_8)
        assertTrue(body.contains("python_sdk_preparation_inspection_in_progress"))
        assertTrue(body.contains("\"inspection_in_progress\": true"))
        assertTrue(body.contains("\"inspection_run_id\": 17"))
        assertEquals(0, runnerCalls)
        verify(exactly = 0) { inspectionIndicator.cancel() }
    }

    @Test
    fun `test Python SDK preparation worker exception releases serialization`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = requireNotNull(Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1))
        handler.pythonSdkPreparationExecutor = { task -> task.run() }
        handler.schedulePythonSdkPreparationTimeout = { _, _ -> {} }
        handler.pythonSdkPreparationRunner = { error("setup failed") }
        val uri = pythonSdkPreparationUri(instanceId, token)

        val failed = processRequest(uri, HttpMethod.POST)
        handler.pythonSdkPreparationRunner = {
            PythonSdkPreparationResult(true, "python_sdk_preparation_prepared", operation = "reused")
        }
        val retry = processRequest(uri, HttpMethod.POST)

        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, failed.status())
        val failedBody = failed.content().toString(Charsets.UTF_8)
        assertTrue(failedBody.contains("python_sdk_preparation_failed"))
        assertTrue(failedBody.contains("\"classification\": \"tool_caused\""), failedBody)
        assertFalse(failedBody.contains("registered_local_python_sdk_count"), failedBody)
        assertFalse(failedBody.contains("assigned_local_python_sdk_count"), failedBody)
        assertEquals(HttpResponseStatus.OK, retry.status())
    }

    @Test
    fun `test lifecycle claim returns close token for exact project instance`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)

        val response = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"claimed\""))
        assertTrue(body.contains("\"ownership_proven\": true"))
        assertTrue(body.contains("\"close_token\""))
        assertTrue(body.contains("\"lease_id\": \"test-lease\""))
        assertTrue(body.contains("\"lifecycle_ownership_protocol\": \"lease_bound_v1\""))
    }

    @Test
    fun `test lifecycle claim preserves cleanup authority while content roots are not ready`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        handler.lifecycleContentRootReadinessProvider = { _, targetKey ->
            InspectionHandler.LifecycleContentRootReadiness(
                ready = false,
                reason = "no_content_roots",
                targetKey = targetKey,
            )
        }
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)

        val response = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"claimed\""))
        assertTrue(body.contains("\"ownership_proven\": true"))
        assertTrue(body.contains("\"close_token\""))
        assertTrue(body.contains("\"lifecycle_readiness\""))
        assertTrue(body.contains("\"ready\": false"))
        assertTrue(body.contains("\"reason\": \"no_content_roots\""))
    }

    @Test
    fun `test lifecycle content root readiness uses the real project model`() {
        val tempDir = Files.createTempDirectory("inspection-content-root-readiness")
        every { mockProject.basePath } returns tempDir.toString()
        every { mockProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val rootManager = mockk<ProjectRootManager>()
        val moduleManager = mockk<ModuleManager>()
        var contentRoots = emptyArray<VirtualFile>()
        var modules = emptyArray<com.intellij.openapi.module.Module>()
        val moduleRootManagers = mutableMapOf<com.intellij.openapi.module.Module, ModuleRootManager>()
        every { rootManager.contentRoots } answers { contentRoots }
        every { rootManager.contentSourceRoots } returns emptyArray()
        every { moduleManager.modules } answers { modules }
        mockkStatic(ProjectRootManager::class)
        mockkStatic(ModuleManager::class)
        mockkStatic(ModuleRootManager::class)
        every { ProjectRootManager.getInstance(mockProject) } returns rootManager
        every { ModuleManager.getInstance(mockProject) } returns moduleManager
        every { ModuleRootManager.getInstance(any()) } answers {
            moduleRootManagers.getValue(firstArg())
        }
        val productionHandler = InspectionHandler()
        var analysisReadinessCalls = 0
        productionHandler.projectAnalysisReadinessProvider = { _, _ ->
            analysisReadinessCalls += 1
            error("Lifecycle structural readiness must not evaluate whole-project language readiness.")
        }
        val targetKey = tempDir.toRealPath().toString()

        try {
            val noRoots = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertFalse(noRoots.ready)
            assertEquals("no_content_roots", noRoots.reason)

            val childRootPath = Files.createDirectories(tempDir.resolve("module-a"))
            val childRoot = mockk<VirtualFile>()
            every { childRoot.path } returns childRootPath.toString()
            every { childRoot.isInLocalFileSystem } returns true
            contentRoots = arrayOf(childRoot)
            val childReady = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertTrue(childReady.ready, childReady.toString())
            assertFalse(childReady.targetInsideContent)
            assertTrue(childReady.contentRootInsideTarget)
            assertFalse(childReady.analysisRequired)
            assertTrue(childReady.analysisReady)
            assertEquals("deferred_to_inspection_scope", childReady.analysisReason)

            val fallbackModule = mockk<com.intellij.openapi.module.Module>()
            val fallbackRootManager = mockk<ModuleRootManager>()
            every { fallbackModule.name } returns "__jetbrains_inspection_api_lifecycle_fallback__test"
            every { fallbackRootManager.contentRoots } returns arrayOf(childRoot)
            moduleRootManagers[fallbackModule] = fallbackRootManager
            modules = arrayOf(fallbackModule)
            val fallbackReady = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertTrue(fallbackReady.ready, fallbackReady.toString())
            assertEquals(1, fallbackReady.fallbackModuleCount)

            val outsideRootPath = Files.createDirectories(tempDir.resolveSibling("outside-module"))
            val outsideRoot = mockk<VirtualFile>()
            every { outsideRoot.path } returns outsideRootPath.toString()
            every { outsideRoot.isInLocalFileSystem } returns true
            contentRoots = arrayOf(outsideRoot)
            modules = emptyArray()
            val outside = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertFalse(outside.ready)
            assertEquals("content_roots_outside_target", outside.reason)

            val targetModule = mockk<com.intellij.openapi.module.Module>()
            val targetModuleRootManager = mockk<ModuleRootManager>()
            every { targetModule.name } returns "target-module"
            every { targetModuleRootManager.contentRoots } returns arrayOf(childRoot)
            moduleRootManagers[targetModule] = targetModuleRootManager
            val siblingModule = mockk<com.intellij.openapi.module.Module>()
            val siblingModuleRootManager = mockk<ModuleRootManager>()
            every { siblingModule.name } returns "sibling-module"
            every { siblingModuleRootManager.contentRoots } returns arrayOf(outsideRoot)
            moduleRootManagers[siblingModule] = siblingModuleRootManager
            contentRoots = arrayOf(childRoot, outsideRoot)
            modules = arrayOf(targetModule, siblingModule)
            val mixedRoots = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertTrue(mixedRoots.ready, mixedRoots.toString())
            assertEquals("ready", mixedRoots.reason)

            modules = arrayOf(fallbackModule, siblingModule)
            val fallbackWithSibling = productionHandler.lifecycleContentRootReadinessProvider(mockProject, targetKey)
            assertFalse(fallbackWithSibling.ready)
            assertEquals("content_roots_outside_target", fallbackWithSibling.reason)
            assertEquals(0, analysisReadinessCalls)
        } finally {
            unmockkStatic(ProjectRootManager::class)
            unmockkStatic(ModuleManager::class)
            unmockkStatic(ModuleRootManager::class)
        }
    }

    @Test
    fun `test lifecycle fallback creator adds a non-persistent module content root`() {
        val moduleManager = mockk<ModuleManager>()
        val module = mockk<com.intellij.openapi.module.Module>()
        val targetRoot = mockk<VirtualFile>()
        val moduleName = slot<String>()
        every { mockProject.isDisposed } returns false
        every { mockApplication.runWriteAction(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
        }
        mockkStatic(ModuleManager::class)
        mockkStatic(ModuleRootModificationUtil::class)
        every { ModuleManager.getInstance(mockProject) } returns moduleManager
        every { moduleManager.findModuleByName(any()) } returns null
        every {
            moduleManager.newNonPersistentModule(capture(moduleName), EmptyModuleType.EMPTY_MODULE)
        } returns module
        every { moduleManager.disposeModule(any()) } just Runs
        every { ModuleRootModificationUtil.addContentRoot(module, targetRoot) } just Runs

        try {
            assertTrue(handler.lifecycleFallbackContentRootCreator(mockProject, targetRoot))
            assertTrue(
                moduleName.captured.startsWith("__jetbrains_inspection_api_lifecycle_fallback__"),
                moduleName.captured,
            )
            verify(exactly = 1) {
                moduleManager.newNonPersistentModule(moduleName.captured, EmptyModuleType.EMPTY_MODULE)
                ModuleRootModificationUtil.addContentRoot(module, targetRoot)
            }
            verify(exactly = 0) { moduleManager.disposeModule(any()) }
        } finally {
            unmockkStatic(ModuleRootModificationUtil::class)
            unmockkStatic(ModuleManager::class)
        }
    }

    @Test
    fun `test lifecycle claim does not authorize close for preexisting project`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)

        val response = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"not_owned\""))
        assertTrue(body.contains("\"ownership_proven\": false"))
        assertTrue(body.contains("\"reason\": \"project_preexisted\""))
        assertFalse(body.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle claim rejects ownership bound to another project object`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val otherProject = mockProject(
            name = "OtherProject",
            basePath = "/repo/app",
            projectFilePath = "/repo/app/.idea/misc.xml",
        )
        val field = InspectionHandler::class.java.getDeclaredField("lifecycleOpenOwnershipByProjectInstance")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val ownership = field.get(handler) as MutableMap<String, InspectionHandler.LifecycleOpenOwnership>
        ownership[projectInstanceId(mockProject)] = InspectionHandler.LifecycleOpenOwnership(
            leaseId = "test-lease",
            targetKey = Paths.get("/repo/app").normalize().toAbsolutePath().toString(),
            project = otherProject,
        )

        val response = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=${projectInstanceId(mockProject)}&lease_id=test-lease"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"not_owned\""))
        assertTrue(body.contains("\"reason\": \"project_instance_reused\""))
        assertFalse(body.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle claim rejects stale project instance id`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"

        val response = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=old-instance"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("does not match the resolved route"))
    }

    @Test
    fun `test lifecycle open rejects scheduling new project without session id`() {
        val tempDir = Files.createTempDirectory("inspection-open-missing-session")
        every { mockProjectManager.openProjects } returns emptyArray()

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("\"reason\": \"missing_session_id\""))
        verify(exactly = 0) { mockApplication.invokeLater(any()) }
    }

    @Test
    fun `test lifecycle open opens project path in running IDE`() {
        val tempDir = Files.createTempDirectory("inspection-open-test")
        val openedProject = mockProject(
            name = "Opened",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        runPooledTasksInline()
        var trustedPath: Path? = null
        var openedPath: Path? = null
        handler.trustProjectPath = { path: Path -> trustedPath = path }
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openProjects = arrayOf(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(tempDir))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opened\": false"))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertTrue(body.contains("\"ownership_registered\": true"))
        assertTrue(body.contains("\"lifecycle_open_diagnostic\""))
        assertTrue(body.contains("\"phase\": \"ready\""))
        assertTrue(body.contains("\"ownership_registered\": true"))
        assertTrue(body.contains("\"project_instance_id\""))
        assertTrue(body.contains("\"lifecycle_ownership_protocol\": \"lease_bound_v1\""))
        assertTrue(body.contains(tempDir.toString()))
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open binds fresh project to lease for claim`() {
        val tempDir = Files.createTempDirectory("inspection-open-owned")
        val openedProject = mockProject(
            name = "Owned",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(openedProject)
            openProjects = arrayOf(openedProject)
            openedProject
        }

        val openResponse = processGetRequest(lifecycleOpenUri(tempDir, "owned-lease"))
        val instanceId = projectInstanceId(openedProject)
        val claimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=owned-lease"
        )
        val claimBody = claimResponse.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, openResponse.status())
        assertEquals(HttpResponseStatus.OK, claimResponse.status())
        assertTrue(claimBody.contains("\"status\": \"claimed\""))
        assertTrue(claimBody.contains("\"ownership_proven\": true"))
        assertTrue(claimBody.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle route stays hidden until project ownership is registered`() {
        val tempDir = Files.createTempDirectory("inspection-open-claim-race")
        val openedProject = mockProject(
            name = "OwnedDuringOpen",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        val scheduled = AtomicReference<Runnable?>()
        val openedCallbackComplete = CountDownLatch(1)
        val allowOpenReturn = CountDownLatch(1)
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            scheduled.set(firstArg<Runnable>())
        }
        handler.openProjectPath = { _, onOpened ->
            onOpened(openedProject)
            openProjects = arrayOf(openedProject)
            openedCallbackComplete.countDown()
            assertTrue(allowOpenReturn.await(5, TimeUnit.SECONDS))
            openedProject
        }

        val openResponse = processGetRequest(lifecycleOpenUri(tempDir, "race-lease"))
        val openThread = Thread { scheduled.get()?.run() }
        openThread.start()
        assertTrue(openedCallbackComplete.await(5, TimeUnit.SECONDS))
        val instanceId = projectInstanceId(openedProject)
        val hiddenClaimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=race-lease"
        )
        val hiddenClaimBody = hiddenClaimResponse.content().toString(Charsets.UTF_8)
        val hiddenRouteResponse = processGetRequest(
            "/api/inspection/route?project=${java.net.URLEncoder.encode("OwnedDuringOpen", "UTF-8")}",
        )
        val hiddenRouteBody = hiddenRouteResponse.content().toString(Charsets.UTF_8)
        val hiddenIdentities = openProjectIdentities()
        val hiddenProjectVisible = LifecycleOpenRouteVisibility.isVisible(openedProject)
        allowOpenReturn.countDown()
        openThread.join(5_000)
        val claimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=race-lease"
        )
        val claimBody = claimResponse.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, openResponse.status())
        assertEquals(HttpResponseStatus.OK, hiddenClaimResponse.status())
        assertTrue(hiddenClaimBody.contains("\"status\": \"no_project\""))
        assertEquals(HttpResponseStatus.OK, hiddenRouteResponse.status())
        assertTrue(hiddenRouteBody.contains("\"status\": \"no_project\""), hiddenRouteBody)
        assertFalse(hiddenProjectVisible)
        assertTrue(hiddenIdentities.isEmpty(), hiddenIdentities.toString())
        assertTrue(openProjectIdentities().any { identity -> identity["project_instance_id"] == instanceId })
        assertEquals(HttpResponseStatus.OK, claimResponse.status())
        assertTrue(claimBody.contains("\"status\": \"claimed\""))
        assertTrue(claimBody.contains("\"ownership_proven\": true"))
        assertTrue(claimBody.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle open race with user project never grants ownership`() {
        val tempDir = Files.createTempDirectory("inspection-open-user-race")
        val userProject = mockProject(
            name = "UserOwned",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        val scheduled = mutableListOf<Runnable>()
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, _ -> error("scheduled open must not run after a user project appears") }

        val openResponse = processGetRequest(lifecycleOpenUri(tempDir, "raced-lease"))
        openProjects = arrayOf(userProject)
        scheduled.single().run()
        val instanceId = projectInstanceId(userProject)
        val claimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=raced-lease"
        )
        val claimBody = claimResponse.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, openResponse.status())
        assertTrue(openResponse.content().toString(Charsets.UTF_8).contains("\"ownership_registered\": true"))
        assertEquals(HttpResponseStatus.OK, claimResponse.status())
        assertTrue(claimBody.contains("\"status\": \"not_owned\""), claimBody)
        assertTrue(claimBody.contains("\"ownership_proven\": false"), claimBody)
        assertFalse(claimBody.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle open does not own a project that appeared before the open call`() {
        val tempDir = Files.createTempDirectory("inspection-open-late-user-race")
        val userProject = mockProject(
            name = "LateUserOwned",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var initialized = false
        var openProjects = emptyArray<Project>()
        val scheduled = mutableListOf<Runnable>()
        every { userProject.isInitialized } answers { initialized }
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, onOpened ->
            initialized = true
            onOpened(userProject)
            userProject
        }

        processGetRequest(lifecycleOpenUri(tempDir, "late-user-lease"))
        openProjects = arrayOf(userProject)
        scheduled.single().run()
        val instanceId = projectInstanceId(userProject)
        val claimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=late-user-lease",
        )
        val claimBody = claimResponse.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, claimResponse.status())
        assertTrue(claimBody.contains("\"status\": \"not_owned\""), claimBody)
        assertTrue(claimBody.contains("\"ownership_proven\": false"), claimBody)
        assertFalse(claimBody.contains("\"close_token\""))
    }

    @Test
    fun `test lifecycle open requires reported project identity to match returned project`() {
        val tempDir = Files.createTempDirectory("inspection-open-coalesced")
        val initializedProject = mockProject(
            name = "Initialized",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/initialized.xml").toString(),
        )
        val returnedProject = mockProject(
            name = "Returned",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(initializedProject)
            openProjects = arrayOf(returnedProject)
            returnedProject
        }

        processGetRequest(lifecycleOpenUri(tempDir, "coalesced-lease"))
        val instanceId = projectInstanceId(returnedProject)
        val claimResponse = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")}&project_instance_id=$instanceId&lease_id=coalesced-lease"
        )
        val claimBody = claimResponse.content().toString(Charsets.UTF_8)

        assertTrue(claimBody.contains("\"status\": \"not_owned\""))
        assertFalse(claimBody.contains("\"close_token\""))
        assertFalse(
            handler.lifecycleFallbackContentRootInstaller(returnedProject, tempDir.toRealPath().toString()),
            "A returned project that does not match the reported opened instance must not be repaired.",
        )
    }

    @Test
    fun `test lifecycle fallback installer requires a lease-bound open`() {
        val tempDir = Files.createTempDirectory("inspection-open-fallback-no-lease")
        val openedProject = mockProject(
            name = "NoLease",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(openedProject)
            openProjects = arrayOf(openedProject)
            openedProject
        }
        val encodedPath = java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")
        val encodedSession = java.net.URLEncoder.encode(InspectionIdeSession.sessionId, "UTF-8")

        processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=$encodedPath&session_id=$encodedSession"
        )

        assertFalse(
            handler.lifecycleFallbackContentRootInstaller(openedProject, tempDir.toRealPath().toString()),
            "Fallback repair requires the same lease used to register helper ownership.",
        )
    }

    @Test
    fun `test lifecycle fallback installer schedules exact owned root without blocking`() {
        val tempDir = Files.createTempDirectory("inspection-open-fallback-owned")
        val openedProject = mockProject(
            name = "OwnedFallback",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        var openProjects = emptyArray<Project>()
        val scheduled = mutableListOf<Runnable>()
        val pooled = mutableListOf<Runnable>()
        every { mockProjectManager.openProjects } answers { openProjects }
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            pooled += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(openedProject)
            openProjects = arrayOf(openedProject)
            openedProject
        }
        handler.lifecycleContentRootReadinessProvider = { _, targetKey ->
            InspectionHandler.LifecycleContentRootReadiness(
                ready = false,
                reason = "no_content_roots",
                targetKey = targetKey,
            )
        }
        val localFileSystem = mockk<LocalFileSystem>()
        val targetRoot = mockk<VirtualFile>()
        val targetKey = tempDir.toRealPath().toString()
        var createdRoot: VirtualFile? = null
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.refreshAndFindFileByNioFile(tempDir.toRealPath()) } returns targetRoot
        handler.lifecycleFallbackContentRootCreator = { project, root ->
            assertSame(openedProject, project)
            createdRoot = root
            true
        }

        try {
            processGetRequest(lifecycleOpenUri(tempDir, "owned-fallback-lease"))
            scheduled.single().run()

            assertEquals(1, pooled.size)
            assertTrue(handler.lifecycleFallbackContentRootInstaller(openedProject, targetKey))
            assertEquals(2, scheduled.size, "The installer must enqueue work instead of blocking on the EDT.")
            assertNull(createdRoot)

            scheduled.last().run()
            assertSame(targetRoot, createdRoot)
        } finally {
            unmockkStatic(LocalFileSystem::class)
        }
    }

    @Test
    fun `test lifecycle open trusts project path before opening`() {
        val tempDir = Files.createTempDirectory("inspection-open-trust-test")
        val openedProject = mockProject(
            name = "TrustedOpen",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        val events = mutableListOf<String>()
        var trustedPath: Path? = null
        var openedPath: Path? = null
        handler.trustProjectPath = { path: Path ->
            events += "trust"
            trustedPath = path
        }
        handler.openProjectPath = { path: Path, beforeInit ->
            events += "open"
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(tempDir))

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(listOf("trust", "open"), events)
        assertEquals(tempDir.toAbsolutePath().normalize(), trustedPath)
        assertEquals(trustedPath, openedPath)
    }

    @Test
    fun `test lifecycle open opens ipr project file path in running IDE`() {
        val tempDir = Files.createTempDirectory("inspection-open-ipr-file")
        val projectFilePath = tempDir.resolve("project.ipr")
        Files.writeString(projectFilePath, "<project />")
        val openedProject = mockProject(
            name = "OpenedIpr",
            basePath = null,
            projectFilePath = projectFilePath.toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var trustedPath: Path? = null
        var openedPath: Path? = null
        handler.trustProjectPath = { path: Path -> trustedPath = path }
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(projectFilePath))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(tempDir.toAbsolutePath().normalize(), trustedPath)
        assertEquals(projectFilePath.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open opens idea project file path in running IDE`() {
        val tempDir = Files.createTempDirectory("inspection-open-idea-file")
        val projectFilePath = tempDir.resolve(".idea/misc.xml")
        Files.createDirectories(projectFilePath.parent)
        Files.writeString(projectFilePath, "<project />")
        val openedProject = mockProject(
            name = "OpenedIdeaFile",
            basePath = tempDir.toString(),
            projectFilePath = projectFilePath.toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(projectFilePath))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open treats ipr-named idea metadata as project root`() {
        val tempDir = Files.createTempDirectory("inspection-open-idea-ipr-file")
        val metadataPath = tempDir.resolve(".idea/project.ipr")
        Files.createDirectories(metadataPath.parent)
        Files.writeString(metadataPath, "<project />")
        val openedProject = mockProject(
            name = "OpenedIdeaIprMetadata",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var trustedPath: Path? = null
        var openedPath: Path? = null
        handler.trustProjectPath = { path: Path -> trustedPath = path }
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(metadataPath))

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(tempDir.toAbsolutePath().normalize(), trustedPath)
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open rejects regular non-project file outside idea`() {
        val tempDir = Files.createTempDirectory("inspection-open-non-project-file")
        val regularFile = tempDir.resolve("notes.txt")
        Files.writeString(regularFile, "not a project")
        every { mockProjectManager.openProjects } returns emptyArray()

        val response = processGetRequest(lifecycleOpenUri(regularFile))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("\"parameter\": \"worktree_path\""))
        assertTrue(body.contains(".ipr project file, or file inside .idea"))
    }

    @Test
    fun `test lifecycle open resolves idea directory to project root`() {
        val tempDir = Files.createTempDirectory("inspection-open-idea-dir")
        val ideaDir = tempDir.resolve(".idea")
        Files.createDirectories(ideaDir)
        val openedProject = mockProject(
            name = "OpenedIdeaDir",
            basePath = tempDir.toString(),
            projectFilePath = ideaDir.resolve("misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(ideaDir))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open resolves nested idea directory to project root`() {
        val tempDir = Files.createTempDirectory("inspection-open-nested-idea-dir")
        val nestedIdeaDir = tempDir.resolve(".idea/runConfigurations")
        Files.createDirectories(nestedIdeaDir)
        val openedProject = mockProject(
            name = "OpenedNestedIdeaDir",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(nestedIdeaDir))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open resolves nested idea file to project root`() {
        val tempDir = Files.createTempDirectory("inspection-open-nested-idea-file")
        val nestedIdeaFile = tempDir.resolve(".idea/runConfigurations/app.xml")
        Files.createDirectories(nestedIdeaFile.parent)
        Files.writeString(nestedIdeaFile, "<component />")
        val openedProject = mockProject(
            name = "OpenedNestedIdeaFile",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(nestedIdeaFile))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"project_root\": \"$tempDir\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open reports already open exact project`() {
        val tempDir = Files.createTempDirectory("inspection-open-existing")
        every { mockProject.basePath } returns tempDir.toString()
        every { mockProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
    }

    @Test
    fun `test lifecycle open schedules nested worktree when containing project is open`() {
        val tempDir = Files.createTempDirectory("inspection-open-containing")
        val nestedPath = tempDir.resolve("packages/app")
        Files.createDirectories(nestedPath)
        every { mockProject.basePath } returns tempDir.toString()
        every { mockProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        var openedPath: Path? = null
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            beforeInit(mockProject)
            mockProject
        }

        val response = processGetRequest(lifecycleOpenUri(nestedPath))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opened\": false"))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertEquals(nestedPath.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open schedules linked worktree when main checkout is open`() {
        val tempDir = Files.createTempDirectory("inspection-open-linked-worktree")
        val mainCheckout = tempDir.resolve("main")
        val linkedWorktree = tempDir.resolve("worktrees/feature")
        Files.createDirectories(mainCheckout)
        Files.createDirectories(linkedWorktree)
        every { mockProject.basePath } returns mainCheckout.toString()
        every { mockProject.projectFilePath } returns mainCheckout.resolve(".idea/misc.xml").toString()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var openedPath: Path? = null
        handler.openProjectPath = { path: Path, beforeInit ->
            openedPath = path
            val openedProject = mockProject(
                name = "Feature",
                basePath = linkedWorktree.toString(),
                projectFilePath = linkedWorktree.resolve(".idea/misc.xml").toString(),
            )
            beforeInit(openedProject)
            openedProject
        }

        val response = processGetRequest(lifecycleOpenUri(linkedWorktree))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"opening\""))
        assertTrue(body.contains("\"opening_scheduled\": true"))
        assertFalse(body.contains("\"status\": \"already_open\""))
        assertEquals(linkedWorktree.toAbsolutePath().normalize(), openedPath)
    }

    @Test
    fun `test lifecycle open reports already open project file path`() {
        val tempDir = Files.createTempDirectory("inspection-open-file-existing")
        val projectFilePath = tempDir.resolve(".idea/misc.xml").toString()
        every { mockProject.basePath } returns null
        every { mockProject.projectFilePath } returns projectFilePath

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(projectFilePath, "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
    }

    @Test
    fun `test lifecycle open detects already open project root from project file path`() {
        val tempDir = Files.createTempDirectory("inspection-open-file-root-existing")
        val projectFilePath = tempDir.resolve(".idea/misc.xml").toString()
        every { mockProject.basePath } returns null
        every { mockProject.projectFilePath } returns projectFilePath
        var scheduled = false
        every { mockApplication.invokeLater(any()) } answers {
            scheduled = true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
        assertFalse(scheduled)
    }

    @Test
    fun `test lifecycle open detects already open ipr project root`() {
        val tempDir = Files.createTempDirectory("inspection-open-ipr-root-existing")
        val projectFilePath = tempDir.resolve("project.ipr").toString()
        every { mockProject.basePath } returns null
        every { mockProject.projectFilePath } returns projectFilePath
        var scheduled = false
        every { mockApplication.invokeLater(any()) } answers {
            scheduled = true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
        assertFalse(scheduled)
    }

    @Test
    fun `test lifecycle open coalesces duplicate concurrent opens`() {
        val tempDir = Files.createTempDirectory("inspection-open-duplicate")
        every { mockProjectManager.openProjects } returns emptyArray()
        val scheduled = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, _ -> null }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(secondBody.contains("\"reason\": \"already_opening\""))
        assertTrue(secondBody.contains("\"lifecycle_open_diagnostic\""))
        assertTrue(secondBody.contains("\"phase\": \"scheduled\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
        scheduled.single().run()
        val third = processGetRequest(lifecycleOpenUri(tempDir))
        assertEquals(2, scheduled.size)
        assertEquals(HttpResponseStatus.OK, third.status())
    }

    @Test
    fun `test lifecycle open records null project result`() {
        val tempDir = Files.createTempDirectory("inspection-open-null-result")
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        handler.openProjectPath = { _, _ -> null }

        val response = processGetRequest(lifecycleOpenUri(tempDir))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"lifecycle_open_diagnostic\""))
        assertTrue(body.contains("\"phase\": \"open_returned_null\""))
        assertTrue(body.contains("\"reason\": \"project_open_returned_null\""))
        assertTrue(body.contains("\"open_returned\": true"))

        val probeBody = processGetRequest(lifecycleOpenUri(tempDir, probe = true))
            .content()
            .toString(Charsets.UTF_8)

        assertTrue(probeBody.contains("\"status\": \"not_open\""))
        assertTrue(probeBody.contains("\"probe\": true"))
        assertTrue(probeBody.contains("\"phase\": \"open_returned_null\""))
        verify(exactly = 1) { mockApplication.invokeLater(any()) }
    }

    @Test
    fun `test lifecycle open probe never schedules a project open`() {
        val tempDir = Files.createTempDirectory("inspection-open-probe")
        every { mockProjectManager.openProjects } returns emptyArray()

        val response = processGetRequest(lifecycleOpenUri(tempDir, probe = true))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"not_open\""))
        assertTrue(body.contains("\"reason\": \"project_not_open\""))
        assertTrue(body.contains("\"probe\": true"))
        assertTrue(body.contains("\"opening_scheduled\": false"))
        verify(exactly = 0) { mockApplication.invokeLater(any()) }
    }

    @Test
    fun `test lifecycle open probe marks already open response without scheduling`() {
        every { mockProject.basePath } returns "/tmp/TestProject"
        every { mockProject.projectFilePath } returns "/tmp/TestProject/.idea/misc.xml"

        val response = processGetRequest(lifecycleOpenUri("/tmp/TestProject", probe = true))
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"probe\": true"))
        verify(exactly = 0) { mockApplication.invokeLater(any()) }
    }

    @Test
    fun `test lifecycle open probe reports active request while visible project awaits readiness`() {
        val tempDir = Files.createTempDirectory("inspection-open-probe-active-request")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns true
        every { initializingProject.name } returns "inspection-open-probe-active-request"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val probe = processGetRequest(lifecycleOpenUri(tempDir, probe = true))

        val firstBody = first.content().toString(Charsets.UTF_8)
        val probeBody = probe.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, probe.status())
        assertTrue(firstBody.contains("\"status\": \"opening\""))
        assertTrue(probeBody.contains("\"status\": \"opening\""))
        assertTrue(probeBody.contains("\"reason\": \"already_opening\""))
        assertTrue(probeBody.contains("\"probe\": true"))
        assertTrue(probeBody.contains("\"opening_scheduled\": false"))
        assertEquals(1, scheduled.size)
        assertEquals(1, guardPolls.size)
    }

    @Test
    fun `test lifecycle open resets diagnostic for a new attempt`() {
        val tempDir = Files.createTempDirectory("inspection-open-diagnostic-reset")
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var nowMs = 10L
        var openResult: Project? = null
        handler.lifecycleOpenDiagnosticNow = { nowMs }
        handler.openProjectPath = { _, callback ->
            openResult?.also(callback)
        }

        processGetRequest(lifecycleOpenUri(tempDir))
        nowMs = 100L
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertTrue(secondBody.contains("\"started_at_ms\": 100"))
        assertTrue(secondBody.contains("\"outcome_at_ms\": 100"))
    }

    @Test
    fun `test lifecycle open diagnostic expires after ttl`() {
        val tempDir = Files.createTempDirectory("inspection-open-diagnostic-ttl")
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var nowMs = 10L
        handler.lifecycleOpenDiagnosticNow = { nowMs }
        handler.lifecycleOpenDiagnosticTtlMs = 5L
        handler.openProjectPath = { _, _ -> null }

        processGetRequest(lifecycleOpenUri(tempDir))
        nowMs = 20L
        val probeBody = processGetRequest(lifecycleOpenUri(tempDir, probe = true))
            .content()
            .toString(Charsets.UTF_8)

        assertTrue(probeBody.contains("\"diagnostic_available\": false"))
        assertFalse(probeBody.contains("\"lifecycle_open_diagnostic\""))
    }

    @Test
    fun `test lifecycle open diagnostic evicts oldest entry above limit`() {
        val firstDir = Files.createTempDirectory("inspection-open-diagnostic-first")
        val secondDir = Files.createTempDirectory("inspection-open-diagnostic-second")
        every { mockProjectManager.openProjects } returns emptyArray()
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        var nowMs = 10L
        handler.lifecycleOpenDiagnosticNow = { nowMs }
        handler.maxLifecycleOpenDiagnostics = 1
        handler.openProjectPath = { _, _ -> null }

        processGetRequest(lifecycleOpenUri(firstDir))
        nowMs = 20L
        processGetRequest(lifecycleOpenUri(secondDir))

        val firstProbe = processGetRequest(lifecycleOpenUri(firstDir, probe = true))
            .content()
            .toString(Charsets.UTF_8)
        val secondProbe = processGetRequest(lifecycleOpenUri(secondDir, probe = true))
            .content()
            .toString(Charsets.UTF_8)

        assertTrue(firstProbe.contains("\"diagnostic_available\": false"))
        assertTrue(secondProbe.contains("\"diagnostic_available\": true"))
        assertTrue(secondProbe.contains("\"phase\": \"open_returned_null\""))
    }

    @Test
    fun `test lifecycle open keeps opening guard until returned project is initialized`() {
        val tempDir = Files.createTempDirectory("inspection-open-initializing")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-initializing"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)
        every { initializingProject.isInitialized } returns true
        val third = processGetRequest(lifecycleOpenUri(tempDir))
        val thirdBody = third.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(secondBody.contains("\"reason\": \"already_opening\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
        assertEquals(HttpResponseStatus.OK, third.status())
        assertTrue(thirdBody.contains("\"status\": \"already_open\""))
    }

    @Test
    fun `test lifecycle open repairs content root lost during project configuration`() {
        val tempDir = Files.createTempDirectory("inspection-open-root-repair")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns true
        every { initializingProject.name } returns "inspection-open-root-repair"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        var nowMs = 1_000L
        var fallbackInstalled = false
        var fallbackCreateCount = 0
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardPollMs = 200
        handler.lifecycleOpenGuardTimeoutMs = 2_000
        handler.lifecycleOpenRootStabilizationMs = 400
        handler.lifecycleFallbackRootStabilizationMs = 200
        handler.lifecycleFallbackFailureThreshold = 2
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis ->
            if (!fallbackInstalled && scheduled.size > 1) {
                scheduled.last().run()
            }
            nowMs += millis
        }
        handler.lifecycleProjectSmartProvider = { true }
        handler.lifecycleContentRootReadinessProvider = { _, targetKey ->
            when {
                fallbackInstalled -> InspectionHandler.LifecycleContentRootReadiness(
                    ready = true,
                    reason = "ready",
                    targetKey = targetKey,
                    contentRootCount = 1,
                    moduleCount = 1,
                    fallbackModuleCount = 1,
                    targetInsideContent = true,
                    contentRoots = listOf(targetKey),
                )
                nowMs < 1_200L -> InspectionHandler.LifecycleContentRootReadiness(
                    ready = true,
                    reason = "ready",
                    targetKey = targetKey,
                    contentRootCount = 1,
                    moduleCount = 1,
                    targetInsideContent = true,
                    contentRoots = listOf(targetKey),
                )
                else -> InspectionHandler.LifecycleContentRootReadiness(
                    ready = false,
                    reason = "no_content_roots",
                    targetKey = targetKey,
                )
            }
        }
        val localFileSystem = mockk<LocalFileSystem>()
        val targetRoot = mockk<VirtualFile>()
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem
        every { localFileSystem.refreshAndFindFileByNioFile(tempDir.toRealPath()) } returns targetRoot
        handler.lifecycleFallbackContentRootCreator = { project, root ->
            assertSame(initializingProject, project)
            assertSame(targetRoot, root)
            fallbackCreateCount += 1
            fallbackInstalled = true
            true
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        try {
            val first = processGetRequest(lifecycleOpenUri(tempDir))
            scheduled.single().run()
            guardPolls.single().run()
            val second = processGetRequest(lifecycleOpenUri(tempDir))
            val secondBody = second.content().toString(Charsets.UTF_8)

            assertEquals(HttpResponseStatus.OK, first.status())
            assertEquals(HttpResponseStatus.OK, second.status())
            assertEquals(1, fallbackCreateCount)
            assertTrue(fallbackInstalled)
            assertTrue(secondBody.contains("\"status\": \"already_open\""))
            assertTrue(secondBody.contains("\"fallback_module_count\": 1"))
        } finally {
            unmockkStatic(LocalFileSystem::class)
        }
    }

    @Test
    fun `test lifecycle open retries when project disappears before initialization`() {
        val tempDir = Files.createTempDirectory("inspection-open-disappears")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-disappears"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardSleep = {
            openProjects[0] = null
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        guardPolls.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(2, scheduled.size)
    }

    @Test
    fun `test lifecycle open keeps opening guard while returned project is never observed`() {
        val tempDir = Files.createTempDirectory("inspection-open-never-observed")
        every { mockProjectManager.openProjects } returns emptyArray()
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-never-observed"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        var nowMs = 1_000L
        var retryAfterOldNeverObservedTimeout = ""
        var scheduledAfterRetry = 0
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardTimeoutMs = 1_000
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis ->
            nowMs += millis
            if (nowMs >= 1_400L && retryAfterOldNeverObservedTimeout.isEmpty()) {
                retryAfterOldNeverObservedTimeout = processGetRequest(lifecycleOpenUri(tempDir)).content().toString(Charsets.UTF_8)
                scheduledAfterRetry = scheduled.size
            }
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val inFlight = processGetRequest(lifecycleOpenUri(tempDir)).content().toString(Charsets.UTF_8)
        guardPolls.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.CONFLICT, second.status())
        assertEquals(1, scheduledAfterRetry)
        assertEquals(1, scheduled.size)
        assertTrue(inFlight.contains("\"reason\": \"already_opening\""))
        assertTrue(retryAfterOldNeverObservedTimeout.contains("\"reason\": \"already_opening\""))
        assertTrue(retryAfterOldNeverObservedTimeout.contains("\"opening_scheduled\": false"))
        assertTrue(secondBody.contains("\"status\": \"failed\""))
        assertTrue(secondBody.contains("\"reason\": \"open_state_unknown\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
        assertTrue(secondBody.contains("\"lifecycle_open_diagnostic\""))
        assertTrue(secondBody.contains("\"phase\": \"unresolved\""))
        assertTrue(secondBody.contains("\"outcome_reason\": \"readiness_guard_timeout\""))
    }

    @Test
    fun `test lifecycle open remains unresolved when readiness misses stabilization deadline`() {
        val tempDir = Files.createTempDirectory("inspection-open-unstable-ready")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        val openedProject = mockProject(
            name = "UnstableReady",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        var nowMs = 1_000L
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardTimeoutMs = 400
        handler.lifecycleOpenRootStabilizationMs = 1_000
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis -> nowMs += millis }
        handler.lifecycleContentRootReadinessProvider = { _, targetKey ->
            InspectionHandler.LifecycleContentRootReadiness(
                ready = true,
                reason = "ready",
                targetKey = targetKey,
                contentRootCount = 1,
                sourceRootCount = 0,
                moduleCount = 1,
                targetInsideContent = true,
                contentRoots = listOf(targetKey),
            )
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = openedProject
            beforeInit(openedProject)
            openedProject
        }
        mockInspectionPrerequisites(openedProject)

        processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        guardPolls.single().run()
        val encodedPath = java.net.URLEncoder.encode(tempDir.toString(), "UTF-8")
        val status = processGetRequest("/api/inspection/status?worktree_path=$encodedPath")
        val statusBody = status.content().toString(Charsets.UTF_8)
        val instanceId = projectInstanceId(openedProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=$encodedPath&project_instance_id=$instanceId&lease_id=test-open-lease"
        )
        val claimBody = claim.content().toString(Charsets.UTF_8)
        val unresolved = processGetRequest(lifecycleOpenUri(tempDir))
        val unresolvedBody = unresolved.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, status.status())
        assertTrue(statusBody.contains("\"ready\": false"))
        assertTrue(statusBody.contains("\"reason\": \"project_configuration_unstable\""))
        assertEquals(HttpResponseStatus.OK, claim.status())
        assertTrue(claimBody.contains("\"ready\": false"))
        assertTrue(claimBody.contains("\"reason\": \"project_configuration_unstable\""))
        assertEquals(HttpResponseStatus.CONFLICT, unresolved.status())
        assertTrue(unresolvedBody.contains("\"reason\": \"open_state_unknown\""))
        assertTrue(unresolvedBody.contains("\"ready\": false"))
        assertTrue(unresolvedBody.contains("\"reason\": \"project_configuration_unstable\""))
        assertFalse(unresolvedBody.contains("\"status\": \"already_open\""))
    }

    @Test
    fun `test lifecycle open retries after unresolved returned project is disposed`() {
        val tempDir = Files.createTempDirectory("inspection-open-unresolved-disposed")
        every { mockProjectManager.openProjects } returns emptyArray()
        var disposed = false
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } answers { disposed }
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-unresolved-disposed"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        var nowMs = 1_000L
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardTimeoutMs = 400
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis -> nowMs += millis }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(initializingProject)
            initializingProject
        }

        processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        guardPolls.single().run()
        val unknown = processGetRequest(lifecycleOpenUri(tempDir))
        disposed = true
        val retry = processGetRequest(lifecycleOpenUri(tempDir))

        assertEquals(HttpResponseStatus.CONFLICT, unknown.status())
        assertEquals(HttpResponseStatus.OK, retry.status())
        assertEquals(2, scheduled.size)
        assertTrue(unknown.content().toString(Charsets.UTF_8).contains("\"reason\": \"open_state_unknown\""))
    }

    @Test
    fun `test lifecycle open keeps unresolved guard when poller submission fails`() {
        val tempDir = Files.createTempDirectory("inspection-open-poller-submit-fails")
        every { mockProjectManager.openProjects } returns emptyArray()
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns false
        every { initializingProject.name } returns "inspection-open-poller-submit-fails"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } throws RejectedExecutionException("pool stopped")
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.CONFLICT, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(secondBody.contains("\"reason\": \"open_state_unknown\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
    }

    @Test
    fun `test lifecycle open reports unknown state at hard timeout when observed project stays unusable`() {
        val tempDir = Files.createTempDirectory("inspection-open-observed-timeout")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        var initialized = false
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } answers { initialized }
        every { initializingProject.name } returns "inspection-open-observed-timeout"
        every { initializingProject.basePath } returns tempDir.toString()
        every { initializingProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        val scheduled = mutableListOf<Runnable>()
        var nowMs = 1_000L
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardTimeoutMs = 400
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis -> nowMs += millis }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.CONFLICT, second.status())
        assertEquals(1, scheduled.size)
        assertFalse(initialized)
        assertTrue(secondBody.contains("\"status\": \"failed\""))
        assertTrue(secondBody.contains("\"reason\": \"open_state_unknown\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
    }

    @Test
    fun `test lifecycle open keeps guard while observed project is not identifiable`() {
        val tempDir = Files.createTempDirectory("inspection-open-unidentifiable")
        val openProjects = arrayOfNulls<Project>(1)
        every { mockProjectManager.openProjects } answers { openProjects.filterNotNull().toTypedArray() }
        every { mockProject.basePath } returns null
        every { mockProject.projectFilePath } returns null
        val initializingProject = mockk<Project>()
        every { initializingProject.isDefault } returns false
        every { initializingProject.isDisposed } returns false
        every { initializingProject.isInitialized } returns true
        every { initializingProject.name } returns "inspection-open-unidentifiable"
        every { initializingProject.basePath } returns null
        every { initializingProject.projectFilePath } returns null
        val scheduled = mutableListOf<Runnable>()
        val guardPolls = mutableListOf<Runnable>()
        var nowMs = 1_000L
        var retryBeforeTimeoutBody = ""
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        every { mockApplication.executeOnPooledThread(any<Runnable>()) } answers {
            guardPolls += firstArg<Runnable>()
            mockk(relaxed = true)
        }
        handler.lifecycleOpenGuardTimeoutMs = 400
        handler.lifecycleOpenGuardNow = { nowMs }
        handler.lifecycleOpenGuardSleep = { millis ->
            nowMs += millis
            retryBeforeTimeoutBody = processGetRequest(lifecycleOpenUri(tempDir)).content().toString(Charsets.UTF_8)
            nowMs = 1_400L
        }
        handler.openProjectPath = { _, beforeInit ->
            openProjects[0] = initializingProject
            beforeInit(initializingProject)
            initializingProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(1, scheduled.size)
        scheduled.single().run()
        guardPolls.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.CONFLICT, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(retryBeforeTimeoutBody.contains("\"reason\": \"already_opening\""))
        assertTrue(retryBeforeTimeoutBody.contains("\"opening_scheduled\": false"))
        assertTrue(secondBody.contains("\"reason\": \"open_state_unknown\""))
        assertTrue(secondBody.contains("\"opening_scheduled\": false"))
    }

    @Test
    fun `test lifecycle open releases opening guard when the opened project is already disposed`() {
        val tempDir = Files.createTempDirectory("inspection-open-disposed")
        val disposedProject = mockk<Project>()
        every { disposedProject.isDefault } returns false
        every { disposedProject.isDisposed } returns true
        every { disposedProject.isInitialized } returns false
        every { disposedProject.name } returns "inspection-open-disposed"
        every { disposedProject.basePath } returns tempDir.toString()
        every { disposedProject.projectFilePath } returns tempDir.resolve(".idea/misc.xml").toString()
        every { mockProjectManager.openProjects } returns emptyArray()
        val scheduled = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(disposedProject)
            disposedProject
        }

        processGetRequest(lifecycleOpenUri(tempDir))
        scheduled.single().run()
        val second = processGetRequest(lifecycleOpenUri(tempDir))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, second.status(), secondBody)
        assertFalse(secondBody.contains("\"reason\": \"already_opening\""), secondBody)
        assertEquals(2, scheduled.size, secondBody)
    }

    @Test
    fun `test lifecycle open coalesces symlink aliases`() {
        val tempDir = Files.createTempDirectory("inspection-open-real")
        val symlink = tempDir.parent.resolve("inspection-open-link-${System.nanoTime()}")
        try {
            Files.createSymbolicLink(symlink, tempDir)
        } catch (_: UnsupportedOperationException) {
            return
        }
        every { mockProjectManager.openProjects } returns emptyArray()
        val scheduled = mutableListOf<Runnable>()
        every { mockApplication.invokeLater(any()) } answers {
            scheduled += firstArg<Runnable>()
        }
        handler.openProjectPath = { _, beforeInit ->
            beforeInit(mockProject)
            mockProject
        }

        val first = processGetRequest(lifecycleOpenUri(tempDir))
        val second = processGetRequest(lifecycleOpenUri(symlink))
        val secondBody = second.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals(HttpResponseStatus.OK, second.status())
        assertEquals(1, scheduled.size)
        assertTrue(secondBody.contains("\"reason\": \"already_opening\""))

        Files.deleteIfExists(symlink)
    }

    @Test
    fun `test lifecycle open detects already open symlink alias`() {
        val realPath = Files.createTempDirectory("inspection-open-real-existing")
        val symlink = realPath.parent.resolve("inspection-open-existing-link-${System.nanoTime()}")
        try {
            Files.createSymbolicLink(symlink, realPath)
        } catch (_: UnsupportedOperationException) {
            return
        }
        every { mockProject.basePath } returns realPath.toString()
        every { mockProject.projectFilePath } returns realPath.resolve(".idea/misc.xml").toString()
        var scheduled = false
        every { mockApplication.invokeLater(any()) } answers {
            scheduled = true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode(symlink.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
        assertFalse(scheduled)

        Files.deleteIfExists(symlink)
    }

    @Test
    fun `test lifecycle open normalizes home-relative path for already open project`() {
        val home = System.getProperty("user.home")
        val projectPath = Paths.get(home, "repo-open-existing").toString()
        every { mockProject.basePath } returns projectPath
        every { mockProject.projectFilePath } returns Paths.get(projectPath, ".idea/misc.xml").toString()

        val response = processGetRequest(
            "/api/inspection/lifecycle/open?worktree_path=${java.net.URLEncoder.encode("~/repo-open-existing", "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"already_open\""))
        assertTrue(body.contains("\"opened\": false"))
    }

    @Test
    fun `test route endpoint rejects missing project instance id without fallback`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"

        val response = processGetRequest(
            "/api/inspection/route?worktree_path=/repo/app&project_instance_id=old-instance"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("does not match the resolved route"))
        assertFalse(body.contains("\"status\": \"resolved\""))
    }

    @Test
    fun `test route endpoint rejects project instance id that conflicts with path selector`() {
        val mainProject = mockProject(
            name = "Main",
            basePath = "/repo/main",
            projectFilePath = "/repo/main/.idea/misc.xml",
        )
        val worktreeProject = mockProject(
            name = "Worktree",
            basePath = "/repo/worktree",
            projectFilePath = "/repo/worktree/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(mainProject, worktreeProject)

        val response = processGetRequest(
            "/api/inspection/route?worktree_path=/repo/main&project_instance_id=${projectInstanceId(worktreeProject)}"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("does not match the resolved route"))
        assertFalse(body.contains("\"project_key\": \"path:/repo/worktree\""))
    }

    @Test
    fun `test lifecycle close rejects mismatched close token`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns true
        var closeCalls = 0
        handler.forceCloseProject = { _, _ ->
            closeCalls++
            every { mockProjectManager.openProjects } returns emptyArray()
            true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=wrong"
        )
        val validResponse = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.FORBIDDEN, response.status())
        assertTrue(body.contains("\"status\": \"skipped\""))
        assertTrue(body.contains("\"reason\": \"token_mismatch\""))
        assertEquals(HttpResponseStatus.OK, validResponse.status())
        assertTrue(validResponse.content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
        assertEquals(1, closeCalls)
    }

    @Test
    fun `test lifecycle close refuses unsaved claimed project documents and retains claim`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = requireNotNull(Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1))
        every { mockApplication.isDispatchThread } returns true
        handler.lifecycleCloseUnsavedDocumentGuard = { _, _ ->
            InspectionHandler.LifecycleCloseGuardRefusal(
                reason = "unsaved_documents",
                unsavedDocumentCount = 2,
                matchingDocumentCount = 1,
                unresolvedDocumentCount = 1,
                matchingPaths = listOf("/repo/app/src/main.kt"),
            )
        }
        var closeCalls = 0
        handler.forceCloseProject = { _, _ -> closeCalls++; true }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.CONFLICT, response.status())
        assertTrue(body.contains("\"reason\": \"unsaved_documents\""), body)
        assertTrue(body.contains("\"matching_unsaved_document_count\": 1"), body)
        assertTrue(body.contains("/repo/app/src/main.kt"), body)
        assertEquals(0, closeCalls)
        assertTrue(lifecycleLeases().containsKey(instanceId))
        assertTrue(lifecycleOpenOwnership().containsKey(instanceId))
    }

    @Test
    fun `test lifecycle close reports guard failure without closing or losing claim`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = requireNotNull(Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1))
        every { mockApplication.isDispatchThread } returns true
        handler.lifecycleCloseUnsavedDocumentGuard = { _, _ -> error("unavailable") }
        var closeCalls = 0
        handler.forceCloseProject = { _, _ -> closeCalls++; true }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token"
        )

        assertEquals(HttpResponseStatus.CONFLICT, response.status())
        assertTrue(response.content().toString(Charsets.UTF_8).contains("unsaved_document_guard_unavailable"))
        assertEquals(0, closeCalls)
        assertTrue(lifecycleLeases().containsKey(instanceId))
        assertTrue(lifecycleOpenOwnership().containsKey(instanceId))
    }

    @Test
    fun `test lifecycle close dispatches guard and no-save close in the same EDT runnable`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = requireNotNull(Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1))
        every { mockApplication.isDispatchThread } returns false
        every { mockApplication.invokeAndWait(any()) } answers { firstArg<Runnable>().run() }
        val events = mutableListOf<String>()
        handler.lifecycleCloseUnsavedDocumentGuard = { _, _ -> events.add("guard"); null }
        handler.forceCloseProject = { _, save ->
            events.add("close:$save")
            every { mockProjectManager.openProjects } returns emptyArray()
            true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token"
        )

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(listOf("guard", "close:false"), events)
    }

    @Test
    fun `test lifecycle close dispatch failure refuses without retrying or losing claim`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = requireNotNull(Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1))
        every { mockApplication.isDispatchThread } returns false
        var dispatchCalls = 0
        every { mockApplication.invokeAndWait(any()) } answers {
            dispatchCalls++
            throw IllegalStateException("dispatch unavailable")
        }
        var closeCalls = 0
        handler.forceCloseProject = { _, _ -> closeCalls++; true }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.CONFLICT, response.status())
        assertTrue(body.contains("unsaved_document_guard_unavailable"), body)
        assertFalse(body.contains("unsaved_document_count"), body)
        assertEquals(1, dispatchCalls)
        assertEquals(0, closeCalls)
        assertTrue(lifecycleLeases().containsKey(instanceId))
    }

    @Test
    fun `test lifecycle close preserves completed EDT result when dispatcher throws afterward`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = requireNotNull(Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1))
        every { mockApplication.isDispatchThread } returns false
        every { mockApplication.invokeAndWait(any()) } answers {
            firstArg<Runnable>().run()
            throw IllegalStateException("dispatcher failed after execution")
        }
        handler.lifecycleCloseUnsavedDocumentGuard = { _, _ -> null }
        var closeCalls = 0
        handler.forceCloseProject = { _, save ->
            assertFalse(save)
            closeCalls++
            every { mockProjectManager.openProjects } returns emptyArray()
            true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token"
        )

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(1, closeCalls)
    }

    @Test
    fun `test lifecycle close retries with the same claim after unsaved document is saved`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = requireNotNull(Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1))
        every { mockApplication.isDispatchThread } returns true
        var unsaved = true
        handler.lifecycleCloseUnsavedDocumentGuard = { _, _ ->
            if (unsaved) {
                InspectionHandler.LifecycleCloseGuardRefusal(
                    reason = "unsaved_documents",
                    unsavedDocumentCount = 1,
                    matchingDocumentCount = 1,
                    unresolvedDocumentCount = 0,
                )
            } else {
                null
            }
        }
        var closed = false
        handler.forceCloseProject = { _, save ->
            assertFalse(save)
            closed = true
            true
        }
        every { mockProjectManager.openProjects } answers { if (closed) emptyArray() else arrayOf(mockProject) }

        val refused = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token"
        )
        unsaved = false
        val closedResponse = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token"
        )

        assertEquals(HttpResponseStatus.CONFLICT, refused.status())
        assertEquals(HttpResponseStatus.OK, closedResponse.status())
        assertTrue(closedResponse.content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
    }

    @Test
    fun `test lifecycle unsaved document guard covers external roots and lexical symlink files`() {
        val worktreeRoot = Files.createTempDirectory("inspection-close-worktree")
        every { mockProject.basePath } returns worktreeRoot.toString()
        val externalRoot = Files.createTempDirectory("inspection-close-external")
        val externalFile = Files.writeString(externalRoot.resolve("external.kt"), "external")
        val linkedFile = worktreeRoot.resolve("linked.kt")
        Files.createSymbolicLink(linkedFile, externalFile)
        val unrelatedRoot = Files.createTempDirectory("inspection-close-unrelated")
        val unrelatedFile = Files.writeString(unrelatedRoot.resolve("unrelated.kt"), "unrelated")
        val rootFile = mockk<VirtualFile>()
        every { rootFile.path } returns externalRoot.toString()
        every { rootFile.isInLocalFileSystem } returns true
        val rootManager = mockk<ProjectRootManager>()
        mockkStatic(ProjectRootManager::class)
        every { ProjectRootManager.getInstance(mockProject) } returns rootManager
        every { rootManager.contentRoots } returns arrayOf(rootFile)
        val linkedDocument = mockk<Document>()
        val externalDocument = mockk<Document>()
        val unrelatedDocument = mockk<Document>()
        val linkedVirtualFile = mockk<VirtualFile>()
        val externalVirtualFile = mockk<VirtualFile>()
        val unrelatedVirtualFile = mockk<VirtualFile>()
        every { linkedVirtualFile.path } returns linkedFile.toString()
        every { externalVirtualFile.path } returns externalFile.toString()
        every { unrelatedVirtualFile.path } returns unrelatedFile.toString()
        every { linkedVirtualFile.isInLocalFileSystem } returns true
        every { externalVirtualFile.isInLocalFileSystem } returns true
        every { unrelatedVirtualFile.isInLocalFileSystem } returns true
        val fileDocumentManager = mockk<FileDocumentManager>()
        mockkStatic(FileDocumentManager::class)
        every { FileDocumentManager.getInstance() } returns fileDocumentManager
        every { fileDocumentManager.unsavedDocuments } returns
            arrayOf(linkedDocument, externalDocument, unrelatedDocument)
        every { fileDocumentManager.getFile(linkedDocument) } returns linkedVirtualFile
        every { fileDocumentManager.getFile(externalDocument) } returns externalVirtualFile
        every { fileDocumentManager.getFile(unrelatedDocument) } returns unrelatedVirtualFile

        val refusal = requireNotNull(handler.inspectUnsavedDocumentsBeforeLifecycleClose(mockProject, worktreeRoot))

        assertEquals("unsaved_documents", refusal.reason)
        assertEquals(3, refusal.unsavedDocumentCount)
        assertEquals(2, refusal.matchingDocumentCount)
        assertEquals(0, refusal.unresolvedDocumentCount)
        assertTrue(refusal.matchingPaths.contains(linkedFile.toString()))
        assertTrue(refusal.matchingPaths.contains(externalFile.toString()))
    }

    @Test
    fun `test lifecycle unsaved document guard refuses unknown association and ignores proven unrelated local file`() {
        val worktreeRoot = Files.createTempDirectory("inspection-close-worktree")
        every { mockProject.basePath } returns worktreeRoot.toString()
        val unrelatedFile = Files.writeString(
            Files.createTempDirectory("inspection-close-unrelated").resolve("unrelated.kt"),
            "unrelated",
        )
        val rootManager = mockk<ProjectRootManager>()
        mockkStatic(ProjectRootManager::class)
        every { ProjectRootManager.getInstance(mockProject) } returns rootManager
        every { rootManager.contentRoots } returns emptyArray()
        val unknownDocument = mockk<Document>()
        val unrelatedDocument = mockk<Document>()
        val unrelatedVirtualFile = mockk<VirtualFile>()
        every { unrelatedVirtualFile.path } returns unrelatedFile.toString()
        every { unrelatedVirtualFile.isInLocalFileSystem } returns true
        val fileDocumentManager = mockk<FileDocumentManager>()
        mockkStatic(FileDocumentManager::class)
        every { FileDocumentManager.getInstance() } returns fileDocumentManager
        every { fileDocumentManager.unsavedDocuments } returns arrayOf(unknownDocument, unrelatedDocument)
        every { fileDocumentManager.getFile(unknownDocument) } returns null
        every { fileDocumentManager.getFile(unrelatedDocument) } returns unrelatedVirtualFile

        val refusal = requireNotNull(handler.inspectUnsavedDocumentsBeforeLifecycleClose(mockProject, worktreeRoot))

        assertEquals("unsaved_document_association_unknown", refusal.reason)
        assertEquals(0, refusal.matchingDocumentCount)
        assertEquals(1, refusal.unresolvedDocumentCount)
    }

    @Test
    fun `test lifecycle close releases the HTTP event loop before close work`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)

        val scheduled = AtomicReference<Runnable?>()
        handler.lifecycleCloseExecutor = { task -> scheduled.set(task) }
        every { mockApplication.isDispatchThread } returns true
        var closed = false
        handler.forceCloseProject = { _, _ ->
            closed = true
            true
        }
        every { mockProjectManager.openProjects } answers { if (closed) emptyArray() else arrayOf(mockProject) }
        val uri = "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        val urlDecoder = QueryStringDecoder(uri)
        val request = mockk<FullHttpRequest>()
        val context = mockk<ChannelHandlerContext>()
        val responses = mutableListOf<FullHttpResponse>()
        every { request.uri() } returns uri
        every { context.writeAndFlush(any()) } answers {
            responses.add(firstArg())
            mockk(relaxed = true)
        }

        assertTrue(handler.process(urlDecoder, request, context))
        assertTrue(responses.isEmpty())
        assertNotNull(scheduled.get())

        scheduled.get()?.run()

        assertEquals(1, responses.size)
        assertEquals(HttpResponseStatus.OK, responses.single().status())
        assertTrue(responses.single().content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
    }

    @Test
    fun `test lifecycle close preserves claim while inspection is running`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        setInspectionRunState(
            projectKey(mockProject),
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = true,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        val closeCalls = AtomicInteger(0)
        handler.forceCloseProject = { _, _ ->
            closeCalls.incrementAndGet()
            true
        }

        val blocked = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token&lease_id=test-lease"
        )

        assertEquals(HttpResponseStatus.CONFLICT, blocked.status())
        assertTrue(blocked.content().toString(Charsets.UTF_8).contains("\"reason\": \"inspection_in_progress\""))
        assertTrue(blocked.content().toString(Charsets.UTF_8).contains("\"inspection_run_id\": 7"))
        assertEquals(0, closeCalls.get())

        setInspectionRunState(
            projectKey(mockProject),
            InspectionRunState(
                runId = 7L,
                triggerTimeMs = System.currentTimeMillis(),
                inProgress = false,
                captureScope = InspectionCaptureScope(scopeParam = "whole_project"),
            ),
        )
        every { mockProjectManager.openProjects } answers {
            if (closeCalls.get() == 0) arrayOf(mockProject) else emptyArray()
        }
        every { mockApplication.isDispatchThread } returns true

        val closed = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token&lease_id=test-lease"
        )

        assertEquals(HttpResponseStatus.OK, closed.status())
        assertTrue(closed.content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun `test lifecycle close uses claimed project instance even when route selectors drift`() {
        val mainProject = mockProject(
            name = "Main",
            basePath = "/repo/main",
            projectFilePath = "/repo/main/.idea/misc.xml",
        )
        val worktreeProject = mockProject(
            name = "Worktree",
            basePath = "/repo/worktree",
            projectFilePath = "/repo/worktree/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(mainProject, worktreeProject)
        val instanceId = projectInstanceId(worktreeProject)
        registerLifecycleOpenOwnership(worktreeProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/worktree&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns true
        var closedProject: Project? = null
        handler.forceCloseProject = { project, _ ->
            closedProject = project
            every { mockProjectManager.openProjects } returns arrayOf(mainProject)
            true
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?project_key=${projectKey(mainProject)}&project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"closed\""))
        assertSame(worktreeProject, closedProject)
    }

    @Test
    fun `test lifecycle close consumes close token before close work under concurrency`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns true
        val enteredClose = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeCalls = AtomicInteger(0)
        handler.forceCloseProject = { _, _ ->
            closeCalls.incrementAndGet()
            enteredClose.countDown()
            if (releaseClose.await(5, TimeUnit.SECONDS)) {
                every { mockProjectManager.openProjects } returns emptyArray()
                true
            } else {
                false
            }
        }

        val firstResponse = AtomicReference<FullHttpResponse>()
        val firstFailure = AtomicReference<Throwable>()
        val firstThread = Thread {
            try {
                firstResponse.set(
                    processGetRequest(
                        "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token"
                    )
                )
            } catch (t: Throwable) {
                firstFailure.set(t)
            }
        }
        firstThread.start()
        assertTrue(enteredClose.await(5, TimeUnit.SECONDS))

        val second = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token"
        )
        releaseClose.countDown()
        firstThread.join(5_000)
        firstFailure.get()?.let { throw it }
        val first = firstResponse.get() ?: fail("first close response was not captured")

        assertEquals(HttpResponseStatus.OK, first.status())
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
        assertEquals(HttpResponseStatus.OK, second.status())
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"reason\": \"not_claimed\""))
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun `test lifecycle close consumes stale-session lease without closing project`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        lifecycleLeases()[instanceId] = InspectionProjectLease(
            closeToken = "stale-token",
            leaseId = "test-lease",
            projectKey = projectKey(mockProject),
            projectInstanceId = instanceId,
            basePath = "/repo/app",
            sessionId = "stale-session",
            claimedAtMs = 1L,
            project = mockProject,
        )
        val closeCalls = AtomicInteger(0)
        handler.forceCloseProject = { _, _ ->
            closeCalls.incrementAndGet()
            true
        }

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=stale-token"
        )
        val second = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=stale-token"
        )

        assertEquals(HttpResponseStatus.CONFLICT, first.status())
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"reason\": \"session_drift\""))
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"reason\": \"not_claimed\""))
        assertEquals(0, closeCalls.get())
    }

    @Test
    fun `test lifecycle close consumes valid token when request session id drifted`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        val closeCalls = AtomicInteger(0)
        handler.forceCloseProject = { _, _ ->
            closeCalls.incrementAndGet()
            true
        }

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token&session_id=stale-session"
        )
        val second = processGetRequest(
            "/api/inspection/lifecycle/close?project_instance_id=$instanceId&close_token=$token&session_id=stale-session"
        )

        assertEquals(HttpResponseStatus.CONFLICT, first.status())
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"reason\": \"session_drift\""))
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"reason\": \"not_claimed\""))
        assertEquals(0, closeCalls.get())
    }

    @Test
    fun `test lifecycle close evicts lease when claimed project is missing`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockProjectManager.openProjects } returns emptyArray()

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val second = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )

        assertEquals(HttpResponseStatus.OK, first.status())
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"reason\": \"route_missing\""))
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"reason\": \"not_claimed\""))
    }

    @Test
    fun `test lifecycle close verifies closed project after false close result`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns true
        handler.forceCloseProject = { _, _ ->
            every { mockProjectManager.openProjects } returns emptyArray()
            false
        }

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token&client_run_id=dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        )
        val second = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val body = first.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, first.status())
        assertTrue(body.contains("\"status\": \"closed\""))
        assertTrue(body.contains("\"force_close_returned\": false"))
        assertTrue(body.contains("\"closed_verified\": true"))
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"reason\": \"not_claimed\""))
    }

    @Test
    fun `test lifecycle close waits beyond short fixed window for slow verified close`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns false
        every { mockApplication.invokeAndWait(any()) } answers { firstArg<Runnable>().run() }
        handler.closeVerificationTimeoutMs = 2_000
        handler.closeVerificationPollMs = 100
        var nowMs = 0L
        handler.closeVerificationNow = { nowMs }
        handler.closeVerificationSleep = { millis -> nowMs += millis }
        handler.forceCloseProject = { _, _ -> true }
        every { mockProjectManager.openProjects } answers {
            if (nowMs >= 1_200) emptyArray() else arrayOf(mockProject)
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"status\": \"closed\""))
        assertTrue(body.contains("\"closed_verified\": true"))
        assertTrue(nowMs >= 1_200)
    }

    @Test
    fun `test lifecycle close gives retry without saving a fresh verification window`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns false
        every { mockApplication.invokeAndWait(any()) } answers { firstArg<Runnable>().run() }
        handler.closeVerificationTimeoutMs = 300
        handler.closeVerificationPollMs = 100
        var nowMs = 0L
        var noSaveStartMs: Long? = null
        handler.closeVerificationNow = { nowMs }
        handler.closeVerificationSleep = { millis -> nowMs += millis }
        val saveModes = mutableListOf<Boolean>()
        handler.forceCloseProject = { _, save ->
            saveModes.add(save)
            if (saveModes.size == 1) {
                false
            } else {
                noSaveStartMs = nowMs
                true
            }
        }
        every { mockProjectManager.openProjects } answers {
            val retryStart = noSaveStartMs
            if (retryStart != null && nowMs - retryStart >= 200) emptyArray() else arrayOf(mockProject)
        }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals(listOf(false, false), saveModes)
        assertTrue(body.contains("\"status\": \"closed\""))
        assertTrue(body.contains("\"attempt\": 2"))
        assertTrue(body.contains("\"closed_verified\": true"))
    }

    @Test
    fun `test lifecycle close does not poll for close verification on dispatch thread`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns true
        var sleepCount = 0
        handler.closeVerificationSleep = { sleepCount++ }
        handler.forceCloseProject = { _, _ -> false }

        val response = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.CONFLICT, response.status())
        assertTrue(body.contains("\"reason\": \"close_failed\""))
        assertEquals(0, sleepCount)
    }

    @Test
    fun `test lifecycle close preserves token when close fails after retries`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns false
        every { mockApplication.invokeAndWait(any()) } answers { firstArg<Runnable>().run() }
        var nowMs = 0L
        handler.closeVerificationTimeoutMs = 300
        handler.closeVerificationNow = { nowMs }
        handler.closeVerificationSleep = { millis -> nowMs += millis }
        val saveModes = mutableListOf<Boolean>()
        handler.forceCloseProject = { _, save ->
            saveModes.add(save)
            false
        }

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token&client_run_id=dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        )
        var closed = false
        handler.forceCloseProject = { _, save ->
            saveModes.add(save)
            closed = true
            true
        }
        every { mockProjectManager.openProjects } answers { if (closed) emptyArray() else arrayOf(mockProject) }
        val second = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )

        assertEquals(HttpResponseStatus.CONFLICT, first.status())
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"reason\": \"close_failed\""))
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"classification\": \"legitimate_fail_closed\""))
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"phase\": \"lifecycle_close\""))
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"client_run_id\": \"dddddddd-dddd-4ddd-8ddd-dddddddddddd\""))
        assertEquals(HttpResponseStatus.OK, second.status())
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
        assertEquals(listOf(false, false, false, false), saveModes)
        assertTrue(first.content().toString(Charsets.UTF_8).contains("\"closed_verified\": false"))
    }

    @Test
    fun `test lifecycle close preserves token when verification throws`() {
        every { mockProject.basePath } returns "/repo/app"
        every { mockProject.projectFilePath } returns "/repo/app/.idea/misc.xml"
        val instanceId = projectInstanceId(mockProject)
        registerLifecycleOpenOwnership(mockProject)
        val claim = processGetRequest(
            "/api/inspection/lifecycle/claim?worktree_path=/repo/app&project_instance_id=$instanceId&lease_id=test-lease"
        ).content().toString(Charsets.UTF_8)
        val token = Regex("\"close_token\": \"([^\"]+)\"").find(claim)?.groupValues?.get(1)
        assertNotNull(token)
        every { mockApplication.isDispatchThread } returns false
        every { mockApplication.invokeAndWait(any()) } answers { firstArg<Runnable>().run() }
        handler.forceCloseProject = { _, _ -> true }
        handler.closeVerificationSleep = { throw IllegalStateException("verification failed") }

        val first = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token&client_run_id=eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        )

        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, first.status())
        val firstBody = first.content().toString(Charsets.UTF_8)
        assertTrue(firstBody.contains("\"inspection_verdict_reason\": \"inspection_api_http_error\""), firstBody)
        assertTrue(firstBody.contains("\"phase\": \"lifecycle_close\""), firstBody)
        assertFalse(firstBody.contains("verification failed"), firstBody)
        assertFalse(firstBody.contains(requireNotNull(token)), firstBody)

        var closed = false
        handler.forceCloseProject = { _, _ ->
            closed = true
            true
        }
        handler.closeVerificationSleep = {}
        every { mockProjectManager.openProjects } answers { if (closed) emptyArray() else arrayOf(mockProject) }

        val second = processGetRequest(
            "/api/inspection/lifecycle/close?worktree_path=/repo/app&project_instance_id=$instanceId&close_token=$token"
        )

        assertEquals(HttpResponseStatus.OK, second.status())
        assertTrue(second.content().toString(Charsets.UTF_8).contains("\"status\": \"closed\""))
    }

    @Test
    fun `test route endpoint rejects ambiguous project names`() {
        val firstProject = mockProject(
            name = "Shared",
            basePath = "/repo/one",
            projectFilePath = "/repo/one/.idea/misc.xml",
        )
        val secondProject = mockProject(
            name = "Shared",
            basePath = "/repo/two",
            projectFilePath = "/repo/two/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(firstProject, secondProject)

        val response = processGetRequest("/api/inspection/route?project=Shared")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("Multiple open projects matched this request"))
    }

    @Test
    fun `test route endpoint rejects duplicate project names even when paths differ in specificity`() {
        val parentProject = mockProject(
            name = "Shared",
            basePath = "/repo",
            projectFilePath = "/repo/.idea/misc.xml",
        )
        val childProject = mockProject(
            name = "Shared",
            basePath = "/repo/packages/app",
            projectFilePath = "/repo/packages/app/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(parentProject, childProject)

        val response = processGetRequest("/api/inspection/route?project=Shared")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
        assertTrue(body.contains("Multiple open projects matched this request"))
    }

    @Test
    fun `test getCurrentProject prefers exact project file path over longer containing base path`() {
        val exactProjectFileMatch = mockProject(
            name = "ExactProjectFile",
            basePath = "/repo/app",
            projectFilePath = "/repo/app/.idea/misc.xml",
        )
        val longerContainingBasePath = mockProject(
            name = "ContainingBasePath",
            basePath = "/repo/app/.idea",
            projectFilePath = "/repo/app/.idea/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(exactProjectFileMatch, longerContainingBasePath)

        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true

        val result = method.invoke(handler, "/repo/app/.idea/misc.xml") as Project?

        assertNotNull(result)
        assertEquals("ExactProjectFile", result?.name)
    }

    @Test
    fun `test route endpoint prefers exact project file path over longer containing base path`() {
        val exactProjectFileMatch = mockProject(
            name = "ExactProjectFile",
            basePath = "/repo/app",
            projectFilePath = "/repo/app/.idea/misc.xml",
        )
        val longerContainingBasePath = mockProject(
            name = "ContainingBasePath",
            basePath = "/repo/app/.idea",
            projectFilePath = "/repo/app/.idea/.idea/misc.xml",
        )
        every { mockProjectManager.openProjects } returns arrayOf(exactProjectFileMatch, longerContainingBasePath)

        val response = processGetRequest("/api/inspection/route?project_path=/repo/app/.idea/misc.xml")
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"ExactProjectFile\""))
        assertFalse(body.contains("Multiple open projects matched this request"))
    }

    @Test
    fun `test route base url uses numeric loopback`() {
        val method = InspectionHandler::class.java.getDeclaredMethod("routeBaseUrl", Any::class.java)
        method.isAccessible = true

        val result = method.invoke(handler, 63342)

        val expected = "http://" + "127.0.0.1" + ":63342" + "/api/" + "inspection"
        assertEquals(expected, result)
    }

    @Test
    fun `test route endpoint exposes effective base path from project file when base path is missing`() {
        val tempDir = Files.createTempDirectory("inspection-route-file-root")
        every { mockProject.basePath } returns null
        every { mockProject.projectFilePath } returns tempDir.resolve(".idea/modules.xml").toString()

        val response = processGetRequest(
            "/api/inspection/route?worktree_path=${java.net.URLEncoder.encode(tempDir.toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"base_path\": \"$tempDir\""))
        assertTrue(body.contains("\"project_file_path\": \"${tempDir.resolve(".idea/modules.xml")}\""))
    }

    @Test
    fun `test route endpoint prefers nested project file root over containing parent`() {
        val tempDir = Files.createTempDirectory("inspection-route-file-root-nested")
        val nestedPath = tempDir.resolve("packages/app")
        val parentProject = mockProject(
            name = "Parent",
            basePath = tempDir.toString(),
            projectFilePath = tempDir.resolve(".idea/misc.xml").toString(),
        )
        val childProject = mockProject(
            name = "Child",
            basePath = null,
            projectFilePath = nestedPath.resolve(".idea/misc.xml").toString(),
        )
        every { mockProjectManager.openProjects } returns arrayOf(parentProject, childProject)

        val response = processGetRequest(
            "/api/inspection/route?cwd=${java.net.URLEncoder.encode(nestedPath.resolve("src/main").toString(), "UTF-8") }"
        )
        val body = response.content().toString(Charsets.UTF_8)

        assertEquals(HttpResponseStatus.OK, response.status())
        assertTrue(body.contains("\"project_name\": \"Child\""))
        assertTrue(body.contains("\"base_path\": \"$nestedPath\""))
        assertFalse(body.contains("Multiple open projects matched this request"))
    }

    @Test
    fun `test getCurrentProject still falls back when focus lookup throws`() {
        every { IdeFocusManager.getGlobalInstance() } throws IllegalStateException("focus unavailable")

        val mockDataManager = mockk<DataManager>()
        val promise: Promise<DataContext> = rejectedPromise("No context")
        every { mockDataManager.dataContextFromFocusAsync } returns promise
        every { DataManager.getInstance() } returns mockDataManager

        every { mockWindowManager.suggestParentWindow(mockProject) } returns null

        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod("getCurrentProject", String::class.java)
        method.isAccessible = true

        val result = method.invoke(handler, null) as Project?

        assertNotNull(result)
        assertEquals("TestProject", result?.name)
    }
}
