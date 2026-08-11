package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectListener
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiFile
import com.intellij.ui.content.ContentManager

@Suppress("UnstableApiUsage")
internal class GlobalInspectionContextBoundary private constructor(
    private val inspectionManager: InspectionManagerEx,
    private val context: GlobalInspectionContextImpl,
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

    fun initializeViewIfNeeded() = context.initializeViewIfNeeded()

    fun inspectionView(): InspectionResultsView? = context.view

    fun publicContext(): GlobalInspectionContext = context

    fun close(save: Boolean) {
        context.close(save)
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
        fun create(inspectionManager: InspectionManagerEx): GlobalInspectionContextBoundary {
            val context = inspectionManager.createNewGlobalContext()
            return GlobalInspectionContextBoundary(inspectionManager, context)
        }

        fun createForExactFile(inspectionManager: InspectionManagerEx): GlobalInspectionContextBoundary {
            val context = synchronized(inspectionManager) {
                inspectionManager.createNewGlobalContext()
            }
            return GlobalInspectionContextBoundary(inspectionManager, context)
        }

        fun createAttested(
            inspectionManager: InspectionManagerEx,
            project: Project,
            collector: NativeInspectionExecutionProofCollector,
        ): GlobalInspectionContextBoundary {
            val context = NativeAttestedGlobalInspectionContext(project, inspectionManager.contentManager, collector)
            inspectionManager.runningContexts.add(context)
            context.openSynchronousFileTraversalGate()
            return GlobalInspectionContextBoundary(inspectionManager, context)
        }
    }
}

@Suppress("UnstableApiUsage")
private class NativeAttestedGlobalInspectionContext(
    project: Project,
    contentManager: NotNullLazyValue<out ContentManager>,
    collector: NativeInspectionExecutionProofCollector,
) : GlobalInspectionContextImpl(project, contentManager) {
    private val platformPublisher = project.messageBus.syncPublisher(GlobalInspectionContextEx.INSPECT_TOPIC)
    private val attestedPublisher = object : InspectListener {
        override fun fileAnalyzed(file: PsiFile, eventProject: Project) {
            platformPublisher.fileAnalyzed(file, eventProject)
            collector.recordExactFileAnalyzed(file, eventProject)
        }

        override fun inspectionFinished(
            durationMillis: Long,
            threadId: Long,
            problemCount: Int,
            toolWrapper: InspectionToolWrapper<*, *>,
            inspectionKind: InspectListener.InspectionKind,
            file: PsiFile?,
            eventProject: Project,
        ) {
            platformPublisher.inspectionFinished(
                durationMillis,
                threadId,
                problemCount,
                toolWrapper,
                inspectionKind,
                file,
                eventProject,
            )
            collector.recordExactInspectionFinished(problemCount, toolWrapper, inspectionKind, eventProject)
        }

        override fun activityFinished(
            durationMillis: Long,
            threadId: Long,
            activityKind: String,
            eventProject: Project,
        ) {
            platformPublisher.activityFinished(durationMillis, threadId, activityKind, eventProject)
            collector.recordExactActivityFinished(eventProject)
        }

        override fun inspectionFailed(
            toolShortName: String,
            throwable: Throwable,
            file: PsiFile?,
            eventProject: Project,
        ) {
            platformPublisher.inspectionFailed(toolShortName, throwable, file, eventProject)
        }
    }

    override fun getInspectionEventPublisher(): InspectListener = attestedPublisher

    fun openSynchronousFileTraversalGate() {
        myViewClosed = false
    }
}
