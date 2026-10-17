package com.shiny.inspectionmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.roots.OrderRootType
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.TestApplicationManager

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class PythonUvSdkRecoveryTest {
    @Test
    fun realUvSetup(): Unit = runBlocking {
        TestApplicationManager.getInstance()
        val parent = Path.of(System.getProperty("jia.control.root"))
        Files.createDirectories(parent)
        val root = Files.createTempDirectory(parent,"uv-control-")
        val process = ProcessBuilder("uv", "venv", root.resolve(".venv").toString()).inheritIO().start()
        check(process.waitFor() == 0)
        val home = root.resolve(".venv/bin/python").toString()
        val type = requireNotNull(SdkType.findByName("Python SDK"))
        println("CONTROL type=${type.javaClass.name}")
        val platform = JetBrainsPythonSdkPreparationPlatform()
        val helper = platform.createDetachedSdk(ProjectJdkTable.getInstance().allJdks.toList(), home, type)
        val started = System.nanoTime()
        platform.setupSdkPaths(type, helper, EmptyProgressIndicator())
        println("CONTROL helper home=${helper.homePath} version=${helper.versionString} roots=${helper.rootProvider.getFiles(OrderRootType.CLASSES).size} setup_ms=${(System.nanoTime()-started)/1000000} detail=${platform.setupIncompleteDetail(helper,home)}")
        ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction { ProjectJdkTable.getInstance().addJdk(helper) } }
        try {
            val clazz = type.javaClass.classLoader.loadClass("com.jetbrains.python.sdk.uv.UvExtKt")
            val method = clazz.methods.single { it.name == "setupExistingEnvAndSdk" && it.parameterTypes.first() == Path::class.java }
            val uvPath = Path.of(ProcessBuilder("which", "uv").start().inputStream.bufferedReader().readText().trim())
            suspend fun createUv(): Sdk {
                val outcome = suspendCancellableCoroutine<Any> { continuation ->
                    val direct = method.invoke(null, root.resolve(".venv/bin/python"), root, uvPath, false, continuation)
                    if (direct !== COROUTINE_SUSPENDED) continuation.resume(direct)
                }
                return outcome.javaClass.getMethod("getResult").invoke(outcome) as Sdk
            }
            val events = mutableListOf<String>()
            val connection = ApplicationManager.getApplication().messageBus.connect()
            connection.subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, object : ProjectJdkTable.Listener {
                override fun jdkAdded(jdk: Sdk) {
                    if (jdk.homePath == home) events += "roots=${jdk.rootProvider.getFiles(OrderRootType.CLASSES).size} version=${jdk.versionString} data=${jdk.sdkAdditionalData?.javaClass?.name}"
                }
            })
            val uvStarted = System.nanoTime()
            val uvSdk = createUv()
            check(uvSdk === helper)
            check(ProjectJdkTable.getInstance().allJdks.count { it.homePath == home } == 1)
            println("CONTROL uv home=${uvSdk.homePath} version=${uvSdk.versionString} roots=${uvSdk.rootProvider.getFiles(OrderRootType.CLASSES).size} setup_ms=${(System.nanoTime()-uvStarted)/1000000} registered=${ProjectJdkTable.getInstance().allJdks.filter { it.homePath == home }.size} detail=${platform.setupIncompleteDetail(uvSdk, home)}")
            ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction { if (ProjectJdkTable.getInstance().allJdks.none { it === uvSdk }) ProjectJdkTable.getInstance().addJdk(uvSdk) } }
            connection.disconnect()
            println("CONTROL uv_registration events=$events")
            println("CONTROL after_uv_register matches=${ProjectJdkTable.getInstance().allJdks.filter { it.homePath == home }.map { it.name }}")
            val repeated = createUv()
            check(repeated === uvSdk)
            println("CONTROL repeated_uv registered=${ProjectJdkTable.getInstance().allJdks.count { it.homePath == home }}")
            ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction {
                uvSdk.sdkModificator.apply { removeRoots(OrderRootType.CLASSES); commitChanges() }
            } }
            val incompleteStarted = System.nanoTime()
            val incomplete = createUv()
            check(incomplete === uvSdk)
            check(platform.setupIncompleteDetail(incomplete, home) != null)
            println("CONTROL incomplete_uv roots=${incomplete.rootProvider.getFiles(OrderRootType.CLASSES).size} reuse_ms=${(System.nanoTime()-incompleteStarted)/1000000} detail=${platform.setupIncompleteDetail(incomplete, home)}")
            ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction { ProjectJdkTable.getInstance().removeJdk(uvSdk) } }
        } finally {
            ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction { ProjectJdkTable.getInstance().allJdks.filter { it.homePath == home }.forEach { ProjectJdkTable.getInstance().removeJdk(it) } } }
        }
    }
    @Test
    fun realUvConfigurator(): Unit = runBlocking {
        TestApplicationManager.getInstance()
        val parent = Path.of(System.getProperty("jia.control.root"))
        Files.createDirectories(parent)
        val root = Files.createTempDirectory(parent,"configurator-")
        Files.writeString(root.resolve("pyproject.toml"), "[project]\nname = \"jia-uv-control\"\nversion = \"0.0.0\"\n")
        check(ProcessBuilder("uv", "sync", "--directory", root.toString()).inheritIO().start().waitFor() == 0)
        val home = root.resolve(".venv/bin/python").toString()
        val type = requireNotNull(SdkType.findByName("Python SDK"))
        val manager = ProjectManagerEx.getInstanceEx()
        val project = manager.javaClass.getMethod("newProject", String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(manager,"uv-configurator-control",root.toString(),true,false) as com.intellij.openapi.project.Project
        val module = runInEdtAndGet {
            ApplicationManager.getApplication().runWriteAction<com.intellij.openapi.module.Module> {
                ModuleManager.getInstance(project).newModule(root.resolve("control.iml"), "PYTHON_MODULE").also { created ->
                    ModuleRootManager.getInstance(created).modifiableModel.apply {
                        addContentEntry(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)))
                        commit()
                    }
                }
            }
        }
        val platform = JetBrainsPythonSdkPreparationPlatform()
        val helper = platform.createDetachedSdk(ProjectJdkTable.getInstance().allJdks.toList(),home,type)
        platform.setupSdkPaths(type,helper,EmptyProgressIndicator())
        val helperCommit = platform.commit(project,listOf(module),home,helper,{true},EmptyProgressIndicator())
        check(helperCommit.succeeded)
        try {
            val configuration = type.javaClass.classLoader.loadClass("com.jetbrains.python.uv.sdk.configuration.PyUvSdkConfiguration").getConstructor().newInstance()
            val checker = configuration.javaClass.methods.single { it.name == "checkEnvironmentAndPrepareSdkCreator" }
            val info = suspendCancellableCoroutine<Any?> { continuation ->
                val direct = checker.invoke(configuration,module,listOf(root.resolve(".venv/bin/python")),continuation)
                if (direct !== COROUTINE_SUSPENDED) continuation.resume(direct)
            }
            check(info != null)
            val create = info.javaClass.methods.single { it.name == "createSdk" }
            val toolId = configuration.javaClass.methods.single { it.name.startsWith("getToolId-") }.invoke(configuration)
            val result = suspendCancellableCoroutine<Any> { continuation ->
                val direct = create.invoke(info,toolId,continuation)
                if (direct !== COROUTINE_SUSPENDED) continuation.resume(direct)
            }
            println("CONTROL configurator_result=$result")
            val uvSdk = result.javaClass.getMethod("getResult").invoke(result) as Sdk
            check(uvSdk === helper)
            val readback = platform.readback(project,listOf(module),home)
            println("CONTROL configurator info=${info.javaClass.name} registered=${readback.registeredCount} assigned=${readback.assignedLocalSdkCount} project_assigned=${readback.projectSdkAssigned} modules_assigned=${readback.assignedPythonModuleCount} uv_roots=${uvSdk.rootProvider.getFiles(OrderRootType.CLASSES).size}")
            val withToolType = type.javaClass.classLoader.loadClass("com.jetbrains.python.sdk.configuration.CreateSdkInfoWithTool")
            val withTool = withToolType.constructors.single { it.parameterCount == 3 }.newInstance(info,toolId,null)
            val sdkConfigurationType = type.javaClass.classLoader.loadClass("com.jetbrains.python.sdk.configuration.PyProjectSdkConfiguration")
            val sdkConfiguration = sdkConfigurationType.getField("INSTANCE").get(null)
            val setSdk = sdkConfigurationType.methods.single { it.name == "setSdkUsingCreateSdkInfo" }
            val assigned = suspendCancellableCoroutine<Any> { continuation ->
                val direct = setSdk.invoke(sdkConfiguration,module,withTool,continuation)
                if (direct !== COROUTINE_SUSPENDED) continuation.resume(direct)
            }
            println("CONTROL configurator_assignment returned=$assigned module_is_uv=${ModuleRootManager.getInstance(module).sdk === uvSdk} project_is_helper=${ProjectRootManager.getInstance(project).projectSdk === helper} project_is_uv=${ProjectRootManager.getInstance(project).projectSdk === uvSdk} registered=${ProjectJdkTable.getInstance().allJdks.count { it.homePath == home }}")
            val refused = platform.commit(project,listOf(module),home,null,{true},EmptyProgressIndicator())
            check(refused.succeeded)
            check(platform.persistSdkRegistry().succeeded)
            check(ModuleRootManager.getInstance(module).sdk === helper)
            check(ProjectRootManager.getInstance(project).projectSdk === helper)
            check(ProjectJdkTable.getInstance().allJdks.single { it.homePath == home } === helper)
            println("CONTROL configurator_commit reason=${refused.reason}")
        } finally {
            ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction {
                Disposer.dispose(project)
                ProjectJdkTable.getInstance().allJdks.filter { it.homePath == home }.forEach { ProjectJdkTable.getInstance().removeJdk(it) }
            } }
        }
    }

    @Test
    fun realUvInterpreterPathFailure(): Unit = runBlocking {
        TestApplicationManager.getInstance()
        val parent = Path.of(System.getProperty("jia.control.root"))
        Files.createDirectories(parent)
        val root = Files.createTempDirectory(parent,"interpreter-failure-")
        check(ProcessBuilder("uv", "venv", root.resolve(".venv").toString()).inheritIO().start().waitFor() == 0)
        val interpreter = root.resolve(".venv/bin/python")
        val real = interpreter.resolveSibling("python-real")
        Files.move(interpreter,real)
        Files.writeString(interpreter, $$"""#!/bin/sh
case "$1" in -V|--version) exec '$$real' "$@";; *) exit 42;; esac
""")
        interpreter.toFile().setExecutable(true)
        val home = interpreter.toString()
        val type = requireNotNull(SdkType.findByName("Python SDK"))
        val clazz = type.javaClass.classLoader.loadClass("com.jetbrains.python.sdk.uv.UvExtKt")
        val method = clazz.methods.single { it.name == "setupExistingEnvAndSdk" && it.parameterTypes.first() == Path::class.java }
        val uvPath = Path.of(ProcessBuilder("which", "uv").start().inputStream.bufferedReader().readText().trim())
        suspend fun createUv(): Any = suspendCancellableCoroutine { continuation ->
            val direct = method.invoke(null,interpreter,root,uvPath,false,continuation)
            if (direct !== COROUTINE_SUSPENDED) continuation.resume(direct)
        }
        val dialogs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val previousDialog = TestDialogManager.setTestDialog { message -> dialogs += message; 0 }
        try {
            val platform = JetBrainsPythonSdkPreparationPlatform()
            val detached = platform.createDetachedSdk(ProjectJdkTable.getInstance().allJdks.toList(), home, type)
            platform.setupSdkPaths(type, detached, EmptyProgressIndicator())
            check(platform.setupIncompleteDetail(detached, home) != null)
            check(ProjectJdkTable.getInstance().allJdks.none { it === detached || it.homePath == home })
            val result = createUv()
            println("CONTROL interpreter_failure outcome=$result registered=${ProjectJdkTable.getInstance().allJdks.count { it.homePath == home }}")
            val sdk = result.javaClass.getMethod("getResult").invoke(result) as Sdk
            val nativeData = org.jdom.Element("additional")
            val helperData = org.jdom.Element("additional")
            type.saveAdditionalData(requireNotNull(sdk.sdkAdditionalData), nativeData)
            type.saveAdditionalData(requireNotNull(detached.sdkAdditionalData), helperData)
            check(nativeData.getAttributeValue("UV_VENV_PATH") == helperData.getAttributeValue("UV_VENV_PATH"))
            check(sdk.rootProvider.getFiles(OrderRootType.CLASSES).isEmpty())
            println("CONTROL interpreter_failure roots=${sdk.rootProvider.getFiles(OrderRootType.CLASSES).size} version=${sdk.versionString} detail=${JetBrainsPythonSdkPreparationPlatform().setupIncompleteDetail(sdk,home)}")
            Files.delete(interpreter)
            Files.move(real,interpreter)
            platform.setupSdkPaths(type, detached, EmptyProgressIndicator())
            check(platform.setupIncompleteDetail(detached, home) == null)
            check(ProjectJdkTable.getInstance().allJdks.none { it === detached })
            val repeated = createUv()
            val reused = repeated.javaClass.getMethod("getResult").invoke(repeated) as Sdk
            println("CONTROL interpreter_restored same_object=${reused === sdk} roots=${reused.rootProvider.getFiles(OrderRootType.CLASSES).size}")
            check(reused === sdk)
            check(reused.rootProvider.getFiles(OrderRootType.CLASSES).isEmpty())
            val previousData = sdk.sdkAdditionalData
            val project = io.mockk.mockk<com.intellij.openapi.project.Project>(relaxed = true)
            val request = PythonSdkPreparationRequest(project, root, System.currentTimeMillis() + 30_000,
                EmptyProgressIndicator(), { true }, PythonSdkPreparationProgress { System.currentTimeMillis() })
            check(!platform.recoverIncompleteSdk(request, sdk, type))
            check(sdk.rootProvider.getFiles(OrderRootType.CLASSES).isEmpty())
            val registry = ApplicationManager.getApplication().getService(HelperSdkOwnershipRegistry::class.java)
            registry.recordAddedSdk(sdk, helperWorktreeRoot(root))
            try {
                var writes = 0
                val ownerLost = JetBrainsPythonSdkPreparationPlatform { action ->
                    ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction {
                        if (++writes == 2) registry.state.records.removeIf { it.sdkName == sdk.name && it.interpreterHome == home }
                        action()
                    } }
                }
                check(!ownerLost.recoverIncompleteSdk(request, sdk, type))
                check(sdk.rootProvider.getFiles(OrderRootType.CLASSES).isEmpty())
                registry.recordAddedSdk(sdk, helperWorktreeRoot(root))
                val originalVersion = sdk.versionString
                val changedVersion = "concurrent update"
                writes = 0
                val changedSdk = JetBrainsPythonSdkPreparationPlatform { action ->
                    ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction {
                        if (++writes == 2) sdk.sdkModificator.apply { versionString = changedVersion; commitChanges() }
                        action()
                    } }
                }
                check(!changedSdk.recoverIncompleteSdk(request, sdk, type))
                check(sdk.versionString == changedVersion)
                check(sdk.rootProvider.getFiles(OrderRootType.CLASSES).isEmpty())
                ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction {
                    sdk.sdkModificator.apply { versionString = originalVersion; commitChanges() }
                } }
                val duplicate = platform.createDetachedSdk(ProjectJdkTable.getInstance().allJdks.toList(), home, type)
                ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction {
                    ProjectJdkTable.getInstance().addJdk(duplicate)
                } }
                try {
                    check(!platform.recoverIncompleteSdk(request, sdk, type))
                    check(sdk.rootProvider.getFiles(OrderRootType.CLASSES).isEmpty())
                } finally {
                    ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction {
                        ProjectJdkTable.getInstance().removeJdk(duplicate)
                    } }
                }
                check(platform.recoverIncompleteSdk(request, sdk, type))
            } finally {
                registry.state.records.removeIf { it.sdkName == sdk.name && it.interpreterHome == home }
            }
            check(platform.setupIncompleteDetail(sdk, home) == null)
            check(sdk.sdkAdditionalData?.javaClass == previousData?.javaClass)
            val recoveredData = org.jdom.Element("additional")
            type.saveAdditionalData(requireNotNull(sdk.sdkAdditionalData), recoveredData)
            check(com.intellij.openapi.util.JDOMUtil.areElementsEqual(nativeData, recoveredData))
            check(ProjectJdkTable.getInstance().allJdks.single { it.homePath == home } === sdk)
            val afterSetupResult = createUv()
            val afterSetup = afterSetupResult.javaClass.getMethod("getResult").invoke(afterSetupResult) as Sdk
            check(afterSetup === sdk)
            println("CONTROL public_path_setup same_data_class=${sdk.sdkAdditionalData?.javaClass == previousData?.javaClass} registered=${ProjectJdkTable.getInstance().allJdks.count { it.homePath == home }} roots=${sdk.rootProvider.getFiles(OrderRootType.CLASSES).size}")
            ApplicationManager.getApplication().invokeAndWait { }
            check(dialogs.isNotEmpty())
            println("CONTROL interpreter_failure error_dialogs=${dialogs.size}")
        } finally {
            ApplicationManager.getApplication().invokeAndWait { ApplicationManager.getApplication().runWriteAction {
                ProjectJdkTable.getInstance().allJdks.filter { it.homePath == home }.forEach { ProjectJdkTable.getInstance().removeJdk(it) }
            } }
            ApplicationManager.getApplication().invokeAndWait { }
            TestDialogManager.setTestDialog(previousDialog)
        }
    }

    @Test
    fun realPythonSetupEdtWait() {
        TestApplicationManager.getInstance()
        val parent = Path.of(System.getProperty("jia.control.root"))
        Files.createDirectories(parent)
        val root = Files.createTempDirectory(parent,"edt-wait-")
        check(ProcessBuilder("uv", "venv", root.resolve(".venv").toString()).inheritIO().start().waitFor() == 0)
        val type = requireNotNull(SdkType.findByName("Python SDK"))
        val platform = JetBrainsPythonSdkPreparationPlatform()
        val sdk = platform.createDetachedSdk(ProjectJdkTable.getInstance().allJdks.toList(),root.resolve(".venv/bin/python").toString(),type)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val progress = PythonSdkPreparationProgress { System.currentTimeMillis() }
        val indicator = EmptyProgressIndicator()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker = Thread({
            progress.startWorker()
            progress.enterStage("candidate_setup")
            try { platform.setupSdkPaths(type,sdk,indicator) }
            catch (error: Throwable) { failure.set(error) }
            finally { progress.finishWorker() }
        }, "jia-real-python-sdk-control")
        ApplicationManager.getApplication().invokeLater { entered.countDown(); release.await(5,java.util.concurrent.TimeUnit.SECONDS) }
        check(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
        try {
            worker.start()
            val deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (worker.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING) && System.nanoTime()<deadline) Thread.sleep(10)
            val diagnostic = progress.diagnostic()
            indicator.cancel()
            println("CONTROL real_candidate_setup_wait diagnostic=$diagnostic cancelled_worker_still_alive=${worker.isAlive}")
        } finally {
            release.countDown()
            worker.join(5000)
            check(!worker.isAlive)
            check(failure.get() is com.intellij.openapi.progress.ProcessCanceledException)
            println("CONTROL real_candidate_setup_unwound failure=${failure.get()?.javaClass?.name}")
        }
    }

}
