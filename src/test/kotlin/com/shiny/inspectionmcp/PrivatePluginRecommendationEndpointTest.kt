package com.shiny.inspectionmcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PrivatePluginRecommendationEndpointTest {
    @Test
    fun `files outside selected project content are rejected`() {
        val project = mockk<Project>()
        val inside = mockk<VirtualFile>()
        val outside = mockk<VirtualFile>()
        val projectFileIndex = mockk<ProjectFileIndex>()
        every { projectFileIndex.isInContent(inside) } returns true
        every { projectFileIndex.isInContent(outside) } returns false
        every { outside.path } returns "/outside/main.go"

        mockkStatic(ProjectFileIndex::class)
        every { ProjectFileIndex.getInstance(project) } returns projectFileIndex

        try {
            assertDoesNotThrow {
                requirePrivateRecommendationProjectContent(project, listOf(inside))
            }
            val error = assertThrows(BadRequestException::class.java) {
                requirePrivateRecommendationProjectContent(project, listOf(inside, outside))
            }

            assertEquals("files", error.parameter)
            assertEquals(
                "File '/outside/main.go' must be inside the selected project content.",
                error.message,
            )
        } finally {
            unmockkStatic(ProjectFileIndex::class)
        }
    }
}
