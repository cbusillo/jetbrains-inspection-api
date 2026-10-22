package com.shiny.inspectionmcp

import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.codeInspection.GlobalSimpleInspectionTool
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import com.intellij.codeInspection.reference.RefEntity
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

internal fun runGlobalSimpleInspectionOnFile(
    file: PsiFile,
    wrapper: GlobalInspectionToolWrapper,
    context: GlobalInspectionContext,
): List<ProblemDescriptor> {
    val tool = wrapper.tool
    check(tool.isGlobalSimpleInspectionTool)
    var completed = false
    var processorDescriptorCount = 0
    val holderDescriptors = mutableListOf<ProblemDescriptor>()
    val observedTool = object : GlobalInspectionTool() {
        override fun getShortName(): String = wrapper.shortName

        override fun isGlobalSimpleInspectionTool(): Boolean = true

        override fun checkFile(
            checkedFile: PsiFile,
            manager: InspectionManager,
            holder: ProblemsHolder,
            globalContext: GlobalInspectionContext,
            processor: ProblemDescriptionsProcessor,
        ) {
            val countingProcessor = object : ProblemDescriptionsProcessor by processor {
                override fun addProblemElement(entity: RefEntity?, vararg descriptions: CommonProblemDescriptor) {
                    processorDescriptorCount += descriptions.size
                    processor.addProblemElement(entity, *descriptions)
                }
            }
            (tool as? GlobalSimpleInspectionTool)?.inspectionStarted(manager, globalContext, countingProcessor)
            tool.checkFile(checkedFile, manager, holder, globalContext, countingProcessor)
            (tool as? GlobalSimpleInspectionTool)?.inspectionFinished(manager, globalContext, countingProcessor)
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
    check(processorDescriptors.size == processorDescriptorCount) {
        "Global-simple inspection returned unmapped descriptors: ${wrapper.shortName}"
    }
    return processorDescriptors + holderDescriptors
}
