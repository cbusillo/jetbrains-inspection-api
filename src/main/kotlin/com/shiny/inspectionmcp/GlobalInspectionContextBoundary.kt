package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.project.Project

@Suppress("UnstableApiUsage")
internal class GlobalInspectionContextBoundary private constructor(
    private val inspectionManager: InspectionManagerEx,
    private val context: GlobalInspectionContextEx,
) {
    fun configure(profile: InspectionProfileImpl, scope: AnalysisScope) {
        context.setExternalProfile(profile)
        context.currentScope = scope
    }

    fun toolGroups() = context.tools.values

    fun presentation(toolWrapper: InspectionToolWrapper<*, *>) = context.getPresentation(toolWrapper)

    fun performInspectionsWithProgress(scope: AnalysisScope) {
        context.performInspectionsWithProgress(scope, false, false)
    }

    fun publicContext(): GlobalInspectionContext = context

    fun close() {
        context.close(true)
    }

    fun cleanup() {
        context.cleanup()
    }

    fun removeFromRunningContexts() {
        inspectionManager.runningContexts.remove(context)
    }

    fun removeFromRunningContextsSynchronously() {
        synchronized(inspectionManager) {
            inspectionManager.runningContexts.remove(context)
        }
    }

    companion object {
        fun create(inspectionManager: InspectionManagerEx): GlobalInspectionContextBoundary =
            GlobalInspectionContextBoundary(
                inspectionManager,
                InspectionBuildEdition.createNativeContext(inspectionManager, inspectionManager.project, null),
            )

        fun createForExactFile(inspectionManager: InspectionManagerEx): GlobalInspectionContextBoundary {
            val context = synchronized(inspectionManager) {
                (inspectionManager as InspectionManager).createNewGlobalContext() as GlobalInspectionContextEx
            }
            return GlobalInspectionContextBoundary(inspectionManager, context)
        }

        fun createAttested(
            inspectionManager: InspectionManagerEx,
            project: Project,
            collector: NativeInspectionExecutionProofCollector,
        ): GlobalInspectionContextBoundary =
            GlobalInspectionContextBoundary(
                inspectionManager,
                InspectionBuildEdition.createNativeContext(inspectionManager, project, collector),
            )
    }
}
