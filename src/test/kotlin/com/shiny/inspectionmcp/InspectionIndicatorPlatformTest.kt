package com.shiny.inspectionmcp

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.runInEdtAndGet
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class InspectionIndicatorPlatformTest {
    @Test
    fun `default inspection progress is non-modal and cancellable`() {
        val indicator = runInEdtAndGet {
            InspectionHandler().inspectionIndicatorFactory(projectExtension.project)
        }

        assertFalse(indicator.isModal)
        indicator.cancel()
        assertThrows(ProcessCanceledException::class.java) { indicator.checkCanceled() }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()
    }
}
