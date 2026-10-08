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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Paths

class PythonSdkPreparationPlatformTest {
    @Test
    fun `incomplete or duplicate exact-home registrations cannot assign a detached replacement`() {
        val project = projectExtension.project
        val root = Paths.get(requireNotNull(project.basePath))
        val home = root.resolve(".venv/bin/python").toString()
        val table = ProjectJdkTable.getInstance()
        val type = FixturePythonSdkType()
        val existing = table.createSdk(helperSdkName(), type)
        val replacement = table.createSdk(helperSdkName(), type)
        val previousProjectSdk = ProjectRootManager.getInstance(project).projectSdk
        val module = runInEdtAndGet {
            ApplicationManager.getApplication().runWriteAction<com.intellij.openapi.module.Module> {
                listOf(existing, replacement).forEach { sdk ->
                    sdk.sdkModificator.apply {
                        homePath = home
                        versionString = "Python fixture"
                        commitChanges()
                    }
                }
                table.addJdk(existing)
                ProjectRootManager.getInstance(project).projectSdk = null
                ModuleManager.getInstance(project).newModule(root.resolve("incomplete.iml"), "PYTHON_MODULE")
            }
        }
        val platform = JetBrainsPythonSdkPreparationPlatform()
        try {
            val incomplete = platform.commit(project, listOf(module), home, replacement, { true }, EmptyProgressIndicator())
            assertFalse(incomplete.succeeded)
            assertEquals("python_sdk_preparation_existing_sdk_incomplete", incomplete.reason)
            assertEquals("SDK CLASSES roots are missing after path setup.", platform.setupIncompleteDetail(existing, home))
            assertEquals(platform.setupIncompleteDetail(existing, home), incomplete.detail)
            assertSame(existing, table.allJdks.single { it.homePath == home })
            assertNull(ProjectRootManager.getInstance(project).projectSdk)
            assertNull(ModuleRootManager.getInstance(module).sdk)

            runInEdtAndGet { ApplicationManager.getApplication().runWriteAction { table.addJdk(replacement) } }
            val duplicate = platform.commit(project, listOf(module), home, null, { true }, EmptyProgressIndicator())
            assertFalse(duplicate.succeeded)
            assertEquals("python_sdk_preparation_ambiguous_registered_sdk", duplicate.reason)
            assertEquals(setOf(existing, replacement), table.allJdks.filter { it.homePath == home }.toSet())
            assertNull(ProjectRootManager.getInstance(project).projectSdk)
            assertNull(ModuleRootManager.getInstance(module).sdk)
        } finally {
            runInEdtAndGet {
                ApplicationManager.getApplication().runWriteAction {
                    ProjectRootManager.getInstance(project).projectSdk = previousProjectSdk
                    ModuleManager.getInstance(project).disposeModule(module)
                    listOf(existing, replacement).forEach { sdk -> if (table.allJdks.any { it === sdk }) table.removeJdk(sdk) }
                }
            }
        }
    }

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
