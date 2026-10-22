package com.shiny.inspectionmcp

import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.GlobalSimpleInspectionTool
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionProfileWrapper
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.reference.RefElement
import com.intellij.codeInspection.reference.RefEntity
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.WrappedProgressIndicator
import com.intellij.openapi.progress.util.AbstractProgressIndicatorExBase
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

internal data class FileInspectionProblem(
    val descriptor: ProblemDescriptor,
    val reportingWrapper: InspectionToolWrapper<*, *>,
)

internal data class FileInspectionResult(
    val problems: List<FileInspectionProblem>,
    val incompleteReason: String? = null,
)

internal fun runGlobalSimpleInspectionOnFile(
    file: PsiFile,
    wrapper: GlobalInspectionToolWrapper,
    context: GlobalInspectionContext,
    profile: InspectionProfileImpl,
    reportingWrappers: Map<String, LocalInspectionToolWrapper>?,
): FileInspectionResult {
    val tool = wrapper.tool
    check(tool.isGlobalSimpleInspectionTool)
    var completed = false
    var incompleteReason: String? = null
    val processorReportingWrappers = mutableListOf<InspectionToolWrapper<*, *>>()
    val holderDescriptors = mutableListOf<ProblemDescriptor>()
    val observedTool = object : GlobalSimpleInspectionTool() {
        override fun getShortName(): String = wrapper.shortName

        override fun checkFile(
            checkedFile: PsiFile,
            manager: InspectionManager,
            holder: ProblemsHolder,
            globalContext: GlobalInspectionContext,
            processor: ProblemDescriptionsProcessor,
        ) {
            fun observingProcessor(
                resolveReportingWrapper: (CommonProblemDescriptor) -> InspectionToolWrapper<*, *>?,
            ) = object : ProblemDescriptionsProcessor by processor {
                override fun addProblemElement(entity: RefEntity?, vararg descriptions: CommonProblemDescriptor) {
                    for (description in descriptions) {
                        val reportingWrapper = resolveReportingWrapper(description) ?: continue
                        if (entity !is RefElement) {
                            incompleteReason = "processor_reference_unmapped"
                            continue
                        }
                        processorReportingWrappers += reportingWrapper
                        processor.addProblemElement(entity, description)
                    }
                }
            }
            val routingProcessor = observingProcessor { description ->
                val descriptor = description as? ProblemDescriptor
                val group = descriptor?.problemGroup
                when {
                    descriptor?.psiElement?.let(tool::isSuppressedFor) == true -> null
                    group == null -> wrapper
                    reportingWrappers == null -> {
                        incompleteReason = "reporting_tools_unavailable"
                        null
                    }
                    else -> reportingWrappers[group.problemName]
                }
            }
            (tool as? GlobalSimpleInspectionTool)?.inspectionStarted(manager, globalContext, observingProcessor { wrapper })
            tool.checkFile(checkedFile, manager, holder, globalContext, routingProcessor)
            (tool as? GlobalSimpleInspectionTool)?.inspectionFinished(manager, globalContext, routingProcessor)
            holderDescriptors += holder.results
            completed = true
        }
    }
    val observedWrapper = object : GlobalInspectionToolWrapper(observedTool) {
        override fun initialize(globalContext: GlobalInspectionContext) = wrapper.initialize(globalContext)

        override fun cleanup(project: Project) = wrapper.cleanup(project)
    }
    var parentIndicator = requireNotNull(ProgressManager.getInstance().progressIndicator)
    while (parentIndicator is WrappedProgressIndicator) parentIndicator = parentIndicator.originalProgressIndicator
    val parent = requireNotNull(parentIndicator as? AbstractProgressIndicatorExBase) {
        "Global-simple inspection requires a cancellable parent progress delegate"
    }
    val daemon = DaemonProgressIndicator()
    val cancellationDelegate = object : AbstractProgressIndicatorExBase() {
        override fun cancel() {
            super.cancel()
            daemon.cancel()
        }
    }
    var processorDescriptors = emptyList<ProblemDescriptor>()
    parent.addStateDelegate(cancellationDelegate)
    try {
        parent.checkCanceled()
        ProgressManager.getInstance().runProcess(
            Runnable {
                parent.checkCanceled()
                InspectionProfileWrapper.runWithCustomInspectionWrapper(file, { InspectionProfileWrapper(profile) }) {
                    processorDescriptors = InspectionEngine.runInspectionOnFile(file, observedWrapper, context)
                }
            },
            daemon,
        )
        parent.checkCanceled()
    } finally {
        parent.removeStateDelegate(cancellationDelegate)
    }
    if (!completed) incompleteReason = "check_file_not_completed"
    val processorProblems = if (processorDescriptors.size == processorReportingWrappers.size) {
        processorDescriptors.zip(processorReportingWrappers, ::FileInspectionProblem)
    } else {
        incompleteReason = "processor_descriptors_unmapped"
        emptyList()
    }
    return FileInspectionResult(
        processorProblems + holderDescriptors.map { FileInspectionProblem(it, wrapper) },
        incompleteReason,
    )
}
