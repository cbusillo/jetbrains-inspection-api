package com.shiny.inspectionmcp

import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.GlobalSimpleInspectionTool
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.reference.RefEntity
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

internal data class FileInspectionProblem(
    val descriptor: ProblemDescriptor,
    val reportingWrapper: InspectionToolWrapper<*, *>,
)

internal fun runGlobalSimpleInspectionOnFile(
    file: PsiFile,
    wrapper: GlobalInspectionToolWrapper,
    context: GlobalInspectionContext,
    reportingWrappers: Map<String, LocalInspectionToolWrapper>?,
): List<FileInspectionProblem> {
    val tool = wrapper.tool
    check(tool.isGlobalSimpleInspectionTool)
    var completed = false
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
                    else -> requireNotNull(reportingWrappers) { "Global-simple reporting tools are unavailable" }[group.problemName]
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
    val processorDescriptors = InspectionEngine.runInspectionOnFile(file, observedWrapper, context)
    check(completed) { "Global-simple inspection did not complete: ${wrapper.shortName}" }
    check(processorDescriptors.size == processorReportingWrappers.size) {
        "Global-simple inspection returned unmapped descriptors: ${wrapper.shortName}"
    }
    return processorDescriptors.zip(processorReportingWrappers, ::FileInspectionProblem) +
        holderDescriptors.map { FileInspectionProblem(it, wrapper) }
}
