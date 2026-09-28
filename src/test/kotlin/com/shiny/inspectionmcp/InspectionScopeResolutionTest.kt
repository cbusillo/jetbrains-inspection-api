package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import io.mockk.*
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.ThrowableComputable
import java.lang.reflect.InvocationTargetException

class InspectionScopeResolutionTest {

    private lateinit var application: Application

    @BeforeEach
    fun setUp() {
        application = mockk()
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.runReadAction(any<ThrowableComputable<Any, Exception>>()) } answers {
            firstArg<ThrowableComputable<Any, Exception>>().compute()
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun mockProject(name: String = "TestProject"): Project {
        val p = mockk<Project>(relaxed = true)
        every { p.isDefault } returns false
        every { p.isDisposed } returns false
        every { p.isInitialized } returns true
        every { p.name } returns name
        return p
    }

    @Test
    @DisplayName("buildAnalysisScope current_file rejects a missing editor file")
    fun testCurrentFileRejectsMissingEditorFile() {
        val project = mockProject()

        mockkStatic(FileEditorManager::class)
        val fem = mockk<FileEditorManager>(relaxed = true)
        every { FileEditorManager.getInstance(project) } returns fem
        every { fem.selectedFiles } returns emptyArray()
        every { fem.openFiles } returns emptyArray()

        mockkStatic(ProjectFileIndex::class)
        val index = mockk<ProjectFileIndex>(relaxed = true)
        every { ProjectFileIndex.getInstance(project) } returns index

        val handler = InspectionHandler()
        val method = InspectionHandler::class.java.getDeclaredMethod(
            "buildAnalysisScope",
            Project::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaObjectType,
            List::class.java,
        )
        method.isAccessible = true
        val error = assertThrows(InvocationTargetException::class.java) {
            method.invoke(
                handler,
                project,
                "current_file",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
            )
        }
        assertTrue(error.cause is BadRequestException)
    }
}
