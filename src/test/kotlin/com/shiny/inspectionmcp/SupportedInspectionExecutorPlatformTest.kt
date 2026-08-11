package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.runInEdtAndGet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SupportedInspectionExecutorPlatformTest {
    @Test
    fun `records findings and the submitted scope file`() {
        val tool = FindingInspection()
        resetVisits(tool)

        val result = execute(listOf(tool))

        assertThat(result.scopeFileCount).isEqualTo(1)
        assertThat(result.problemDescriptorCount).isEqualTo(1)
        assertThat(result.fileResults.single().suppliedToolShortNames).containsExactly(tool.shortName)
        assertThat(result.fileResults.single().returnedDescriptorsByToolShortName[tool.shortName])
            .singleElement()
            .extracting<String> { it.descriptionTemplate }
            .isEqualTo("supported finding")
        assertThat(visitCount(tool)).isEqualTo(1)
    }

    @Test
    fun `normal clean execution is distinguishable only by submitted tool evidence`() {
        val tool = CleanInspection()
        resetVisits(tool)

        val result = execute(listOf(tool))

        val fileResult = result.fileResults.single()
        assertThat(fileResult.suppliedToolShortNames).containsExactly(tool.shortName)
        assertThat(fileResult.returnedDescriptorsByToolShortName).isEmpty()
        assertThat(visitCount(tool)).isEqualTo(1)
    }

    @Test
    fun `sparse results retain findings while omitting clean tools`() {
        val findingTool = FindingInspection()
        val cleanTool = CleanInspection()

        val result = execute(listOf(findingTool, cleanTool))

        val fileResult = result.fileResults.single()
        assertThat(fileResult.suppliedToolShortNames)
            .containsExactlyInAnyOrder(findingTool.shortName, cleanTool.shortName)
        assertThat(fileResult.returnedDescriptorsByToolShortName.keys)
            .containsExactly(findingTool.shortName)
    }

    @Test
    fun `language inapplicable tools are omitted before visiting`() {
        val tool = JavaOnlyInspection()
        resetVisits(tool)

        val result = execute(listOf(tool))

        assertThat(result.fileResults.single().suppliedToolShortNames).containsExactly(tool.shortName)
        assertThat(result.fileResults.single().returnedDescriptorsByToolShortName).isEmpty()
        assertThat(visitCount(tool)).isZero()
    }

    @Test
    fun `suppressed findings are omitted when suppression filtering is enabled`() {
        val tool = SuppressedFindingInspection()

        val filtered = execute(listOf(tool), ignoreSuppressedElements = true)
        val unfiltered = execute(listOf(tool), ignoreSuppressedElements = false)

        assertThat(filtered.problemDescriptorCount).isZero()
        assertThat(unfiltered.problemDescriptorCount).isEqualTo(1)
    }

    @Test
    fun `tool failures escape instead of becoming clean results`() {
        assertThatThrownBy { execute(listOf(ThrowingInspection())) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("supported inspection failure")
    }

    @Test
    fun `pre-cancelled indicators escape before inspection execution`() {
        val indicator = EmptyProgressIndicator().apply { cancel() }

        assertThatThrownBy { execute(listOf(CleanInspection()), indicator = indicator) }
            .isInstanceOf(ProcessCanceledException::class.java)
    }

    @Test
    fun `an empty caller-selected wrapper set records no submitted tools`() {
        val tool = FindingInspection()
        resetVisits(tool)

        val result = execute(emptyList())

        assertThat(result.fileResults.single().suppliedToolShortNames).isEmpty()
        assertThat(result.problemDescriptorCount).isZero()
        assertThat(visitCount(tool)).isZero()
    }

    @Test
    fun `explicit analysis scope returns every distinct physical file`() {
        val project = projectExtension.project
        val firstFile = createPhysicalFile()
        val secondFile = createPhysicalFile()
        val scope = AnalysisScope(project, setOf(firstFile.virtualFile, secondFile.virtualFile))

        val result = execute(scope, listOf(CleanInspection()))

        assertThat(result.fileResults.map { it.filePath })
            .containsExactlyInAnyOrder(firstFile.virtualFile.path, secondFile.virtualFile.path)
    }

    @Test
    fun `inspectEx routing accepts local wrappers and preserves global fallback`() {
        assertThat(canExecuteWithInspectEx(LocalInspectionToolWrapper(CleanInspection()))).isTrue()
        assertThat(canExecuteWithInspectEx(GlobalInspectionToolWrapper(TestGlobalInspection()))).isFalse()
    }

    private fun execute(
        tools: List<LocalInspectionTool>,
        indicator: EmptyProgressIndicator = EmptyProgressIndicator(),
        ignoreSuppressedElements: Boolean = true,
    ): SupportedInspectionExecutionResult {
        return execute(
            AnalysisScope(createPhysicalFile()),
            tools,
            indicator,
            ignoreSuppressedElements,
        )
    }

    private fun execute(
        scope: AnalysisScope,
        tools: List<LocalInspectionTool>,
        indicator: EmptyProgressIndicator = EmptyProgressIndicator(),
        ignoreSuppressedElements: Boolean = true,
    ): SupportedInspectionExecutionResult {
        return ReadAction.compute<SupportedInspectionExecutionResult, RuntimeException> {
            SupportedInspectionExecutor().execute(
                scope,
                tools.map(::LocalInspectionToolWrapper),
                indicator,
                ignoreSuppressedElements,
            )
        }
    }

    private fun createPhysicalFile(): PsiFile {
        val project = projectExtension.project
        val module = projectExtension.module
        return runInEdtAndGet {
            WriteAction.compute<PsiFile, RuntimeException> {
                val contentRoot = ModuleRootManager.getInstance(module).contentRoots.single()
                val virtualFile = contentRoot.createChildData(this, "supported-inspection-${fileCounter.incrementAndGet()}.txt")
                VfsUtil.saveText(virtualFile, "supported inspection fixture")
                requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
            }
        }
    }

    private open class RecordingInspection : LocalInspectionTool() {
        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Supported Inspection Tests"

        override fun buildVisitor(
            holder: ProblemsHolder,
            isOnTheFly: Boolean,
            session: LocalInspectionToolSession,
        ): PsiElementVisitor = object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                visitCounts.computeIfAbsent(shortName) { AtomicInteger() }.incrementAndGet()
                inspect(holder, file)
            }
        }

        open fun inspect(holder: ProblemsHolder, file: PsiFile) = Unit
    }

    private class CleanInspection : RecordingInspection()

    private open class FindingInspection : RecordingInspection() {
        override fun inspect(holder: ProblemsHolder, file: PsiFile) {
            holder.registerProblem(file, "supported finding")
        }
    }

    private class JavaOnlyInspection : RecordingInspection() {
        override fun getLanguage(): String = "JAVA"
    }

    private class SuppressedFindingInspection : FindingInspection() {
        override fun isSuppressedFor(element: PsiElement): Boolean = true
    }

    private class ThrowingInspection : RecordingInspection() {
        override fun inspect(holder: ProblemsHolder, file: PsiFile) {
            throw IllegalStateException("supported inspection failure")
        }
    }

    private class TestGlobalInspection : GlobalInspectionTool() {
        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "Supported Inspection Tests"
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()

        private val fileCounter = AtomicInteger()
        private val visitCounts = ConcurrentHashMap<String, AtomicInteger>()

        private fun resetVisits(tool: LocalInspectionTool) {
            visitCounts.remove(tool.shortName)
        }

        private fun visitCount(tool: LocalInspectionTool): Int = visitCounts[tool.shortName]?.get() ?: 0
    }
}
