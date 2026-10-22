package com.shiny.inspectionmcp

import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectListener
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiFile
import com.intellij.ui.content.ContentFactory
import com.intellij.util.messages.MessageBusConnection

// The full build: native attestation through IntelliJ internals that Marketplace has not exempted for Stable.
@Suppress("UnstableApiUsage")
internal object InspectionBuildEdition {
    const val NAME = "full"

    val nativeAttestationUnavailableReason: String? = null

    val nativeFilePassUnavailableReason: String? = null

    fun createNativeContext(
        inspectionManager: InspectionManagerEx,
        project: Project,
        collector: NativeInspectionExecutionProofCollector?,
    ): GlobalInspectionContextEx {
        val context = NativeAttestedGlobalInspectionContext(project, collector)
        inspectionManager.runningContexts.add(context)
        context.openSynchronousFileTraversalGate()
        return context
    }

    fun subscribeNativeProofEvents(
        project: Project,
        collector: NativeInspectionExecutionProofCollector,
    ): MessageBusConnection = project.messageBus.connect().apply {
        subscribe(GlobalInspectionContextEx.INSPECT_TOPIC, NativeProofEventListener(collector))
    }
}

@Suppress("UnstableApiUsage")
internal fun InspectListener.InspectionKind.nativePass(): NativeInspectionPass =
    if (this == InspectListener.InspectionKind.LOCAL || this == InspectListener.InspectionKind.LOCAL_PRIORITY) {
        NativeInspectionPass.LOCAL
    } else {
        NativeInspectionPass.GLOBAL
    }

@Suppress("UnstableApiUsage")
private class NativeProofEventListener(
    private val collector: NativeInspectionExecutionProofCollector,
) : InspectListener {
    override fun inspectionFinished(
        durationMillis: Long,
        threadId: Long,
        problemCount: Int,
        toolWrapper: InspectionToolWrapper<*, *>,
        inspectionKind: InspectListener.InspectionKind,
        file: PsiFile?,
        eventProject: Project,
    ) {
        collector.inspectionFinished(problemCount, toolWrapper, inspectionKind.nativePass(), file, eventProject)
    }

    override fun inspectionFailed(
        toolShortName: String,
        throwable: Throwable,
        file: PsiFile?,
        eventProject: Project,
    ) {
        collector.inspectionFailed(toolShortName, throwable, file, eventProject)
    }
}

@Suppress("UnstableApiUsage")
private class NativeAttestedGlobalInspectionContext(
    project: Project,
    collector: NativeInspectionExecutionProofCollector?,
) : GlobalInspectionContextImpl(
    project,
    NotNullLazyValue.createValue { ContentFactory.getInstance().createContentManager(true, project) },
) {
    private val platformPublisher = project.messageBus.syncPublisher(INSPECT_TOPIC)
    private val attestedPublisher = object : InspectListener {
        override fun fileAnalyzed(file: PsiFile, eventProject: Project) {
            platformPublisher.fileAnalyzed(file, eventProject)
            collector?.recordExactFileAnalyzed(file, eventProject)
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
            collector?.observeExactToolCompletion(problemCount, toolWrapper, file, eventProject)
            collector?.recordExactInspectionFinished(problemCount, toolWrapper, inspectionKind.nativePass(), eventProject)
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
