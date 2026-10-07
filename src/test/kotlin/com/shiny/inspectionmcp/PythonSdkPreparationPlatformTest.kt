package com.shiny.inspectionmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.runInEdtAndGet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Paths

class PythonSdkPreparationPlatformTest {
    @Test
    fun `new SDK remains assigned to real modules after registration and repeated preparation`() {
        val project = projectExtension.project
        val root = Paths.get(requireNotNull(project.basePath))
        VfsRootAccess.allowRootAccess(project, root.toString())
        val interpreter = root.resolve(".venv/bin/python")
        Files.createDirectories(interpreter.parent)
        Files.writeString(interpreter, "fixture")
        val sdkType = FixturePythonSdkType()
        val table = ProjectJdkTable.getInstance()
        val sdk = table.createSdk(helperSdkName(), sdkType)
        val previousProjectSdk = ProjectRootManager.getInstance(project).projectSdk
        val modules = runInEdtAndGet {
            ApplicationManager.getApplication().runWriteAction<List<com.intellij.openapi.module.Module>> {
                listOf("first", "second").map { name ->
                    ModuleManager.getInstance(project).newModule(root.resolve("$name.iml"), "PYTHON_MODULE")
                }.also {
                    sdk.sdkModificator.apply {
                        homePath = interpreter.toString()
                        versionString = "Python fixture"
                        addRoot(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(interpreter.parent)), OrderRootType.CLASSES)
                        commitChanges()
                    }
                }
            }
        }
        val platform = JetBrainsPythonSdkPreparationPlatform()
        try {
            modules.forEach { assertEquals("PYTHON_MODULE", ModuleType.get(it).id) }
            val result = platform.commit(project, modules, interpreter.toString(), sdk, { true }, EmptyProgressIndicator())

            assertTrue(result.succeeded, result.reason)
            assertEquals("created", result.operation)
            assertSame(sdk, table.allJdks.single { it.homePath == interpreter.toString() })
            assertSame(sdk, ProjectRootManager.getInstance(project).projectSdk)
            modules.forEach { assertSame(sdk, ModuleRootManager.getInstance(it).sdk) }
            val readback = platform.readback(project, modules, interpreter.toString())
            assertEquals(modules.size, readback.assignedPythonModuleCount)
            assertEquals(1, readback.registeredCount)
            assertEquals(1, readback.assignedLocalSdkCount)
            assertTrue(readback.currentModuleModelMatches)
            assertTrue(readback.projectSdkAssigned)

            val repeated = platform.commit(project, modules, interpreter.toString(), null, { true }, EmptyProgressIndicator())
            assertTrue(repeated.succeeded, repeated.reason)
            assertEquals("already_assigned", repeated.operation)
            assertEquals(readback, platform.readback(project, modules, interpreter.toString()))
        } finally {
            runInEdtAndGet {
                ApplicationManager.getApplication().runWriteAction {
                    ProjectRootManager.getInstance(project).projectSdk = previousProjectSdk
                    modules.forEach { ModuleManager.getInstance(project).disposeModule(it) }
                    if (table.allJdks.any { it === sdk }) table.removeJdk(sdk)
                }
            }
        }
    }

    private class FixturePythonSdkType : SdkType("Python SDK") {
        override fun suggestHomePath(): String? = null
        override fun isValidSdkHome(path: String): Boolean = true
        override fun suggestSdkName(currentSdkName: String?, sdkHome: String): String = "Python fixture"
        override fun createAdditionalDataConfigurable(sdkModel: SdkModel, sdkModificator: SdkModificator): AdditionalDataConfigurable? = null
        override fun getPresentableName(): String = "Python fixture"
        override fun saveAdditionalData(additionalData: SdkAdditionalData, additional: Element) = Unit
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()
    }
}
