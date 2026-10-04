package com.shiny.inspectionmcp

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.profile.codeInspection.BaseInspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.runInEdtAndGet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class InjectedProblemLocationPlatformTest {
    @Test
    fun `finding inside an injected fragment is reported at its host file position`() {
        val project = projectExtension.project
        val hostText = "<root>\n  <item value=\"needle\"/>\n</root>\n"
        val injectorRegistration = Disposer.newDisposable("injected-problem-location")
        try {
            InjectedLanguageManager.getInstance(project)
                .registerMultiHostInjector(AttributeValuePlainTextInjector, injectorRegistration)
            val hostFile = createPhysicalXmlFile(hostText)
            IndexingTestUtil.waitUntilIndexesAreReady(project)

            val location = ReadAction.compute<ProblemLocation?, RuntimeException> {
                val injectedElement = requireNotNull(
                    InjectedLanguageManager.getInstance(project)
                        .findInjectedElementAt(hostFile, hostText.indexOf("needle")),
                ) { "fixture did not produce an injected fragment" }
                check(injectedElement.containingFile.virtualFile is VirtualFileWindow) {
                    "fixture element is not inside an injected file"
                }
                val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(
                    injectedElement,
                    "injected finding",
                    null as LocalQuickFix?,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    false,
                )
                resolveProblemLocation(descriptor, project)
            }

            val needleLine = hostText.lines()[1]
            assertThat(location).isEqualTo(
                ProblemLocation(
                    filePath = hostFile.virtualFile.path,
                    line = 2,
                    column = needleLine.indexOf("needle"),
                ),
            )
        } finally {
            Disposer.dispose(injectorRegistration)
        }
    }

    @Test
    fun `bounded proof reports each injected finding at its host location`() {
        withInjectedHost { hostFile ->
            val tool = InjectedFindingInspection()
            val proof = runProof(hostFile, tool)
            assertThat(proof.proofEstablished).describedAs(proof.toString()).isTrue()
            assertThat(proof.proofClean).isFalse()
            assertThat(proof.languageApplicableObligationCount).isEqualTo(1)
            assertThat(proof.proofProblems).extracting("file").containsOnly(hostFile.virtualFile.path)
            assertThat(proof.proofProblems).extracting("line").containsExactlyInAnyOrder(2, 3)
            assertThat(proof.proofProblems).extracting("description").containsOnly("injected finding")
        }
    }

    @Test
    fun `injected proof respects selected profile disablement and host suppression`() {
        withInjectedHost { hostFile ->
            val disabled = runProof(hostFile, InjectedFindingInspection(), enabled = false)
            assertThat(disabled.profileDisabledObligationCount).isEqualTo(1)
            assertThat(disabled.executedToolCount).isZero()
            assertThat(disabled.proofEstablished).isFalse()
            assertThat(disabled.proofProblems).isEmpty()

            val suppressed = runProof(hostFile, HostSuppressedInjectedInspection())
            assertThat(suppressed.proofEstablished).describedAs(suppressed.toString()).isTrue()
            assertThat(suppressed.proofProblems).isEmpty()
        }
    }

    @Test
    fun `host checkFile batch annotators do not claim execution of injected-only languages`() {
        withInjectedHost { hostFile ->
            val proof = runProof(hostFile, InjectedBatchAnnotatorInspection())
            assertThat(proof.languageNonApplicableObligationCount).isEqualTo(1)
            assertThat(proof.executedToolCount).isZero()
            assertThat(proof.proofEstablished).isFalse()
            assertThat(proof.proofProblems).isEmpty()
        }
    }

    @Test
    fun `injected execution observes the caller indicator cancellation`() {
        withInjectedHost { hostFile ->
            val indicator = EmptyProgressIndicator()
            assertThatThrownBy {
                ReadAction.run<RuntimeException> {
                    ProgressManager.getInstance().runProcess(
                        {
                            SupportedInspectionExecutor().executePreparedFile(
                                hostFile, listOf(LocalInspectionToolWrapper(InjectedCancellingInspection(indicator))), indicator,
                            )
                        },
                        indicator,
                    )
                }
            }.isInstanceOf(ProcessCanceledException::class.java)
            assertThat(indicator.isCanceled).isTrue()
        }
    }

    private fun withInjectedHost(action: (PsiFile) -> Unit) {
        val registration = Disposer.newDisposable("injected-proof")
        try {
            InjectedLanguageManager.getInstance(projectExtension.project)
                .registerMultiHostInjector(AttributeValuePlainTextInjector, registration)
            val hostFile = createPhysicalXmlFile("<root>\n  <item value=\"first\"/>\n  <item value=\"second\"/>\n</root>\n")
            IndexingTestUtil.waitUntilIndexesAreReady(projectExtension.project)
            ReadAction.run<RuntimeException> {
                assertThat(InjectedLanguageManager.getInstance(projectExtension.project)
                    .findInjectedElementAt(hostFile, hostFile.text.indexOf("first"))).isNotNull()
            }
            action(hostFile)
        } finally {
            Disposer.dispose(registration)
        }
    }

    private fun runProof(hostFile: PsiFile, tool: LocalInspectionTool, enabled: Boolean = true): BoundedExecutionProofResult {
        val project = projectExtension.project
        val wrapper = LocalInspectionToolWrapper(tool)
        HighlightDisplayKey.findOrRegister(wrapper.shortName, wrapper.displayName)
        val supplier = InspectionToolsSupplier.Simple(listOf(wrapper as InspectionToolWrapper<*, *>))
        val manager = InspectionProfileManager.getInstance() as BaseInspectionProfileManager
        val profile = InspectionProfileImpl("injected-proof-${System.nanoTime()}", supplier, manager)
        val previousInit = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true
        try {
            profile.initInspectionTools(project)
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = previousInit
        }
        profile.setToolEnabled(tool.shortName, enabled, project)
        return InspectionHandler().runBoundedExecutionProof(
            enabledTools = InspectionHandler.EnabledLocalToolEnumeration(setOf(tool.shortName), 0, emptyList()),
            profile = profile,
            project = project,
            scopeFiles = listOf(hostFile),
            analysisDumbModeCount = DumbService.getInstance(project).modificationTracker.modificationCount,
            cancellationCheck = {},
        )
    }

    private open class InjectedFindingInspection : LocalInspectionTool() {
        override fun getDisplayName(): String = shortName
        override fun getGroupDisplayName(): String = "Injected Proof Tests"
        override fun getLanguage(): String = PlainTextLanguage.INSTANCE.id
        override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor =
            object : PsiElementVisitor() {
                override fun visitFile(file: PsiFile) {
                    holder.registerProblem(file, "injected finding")
                }
            }
    }

    private class InjectedBatchAnnotatorInspection : InjectedFindingInspection(), ExternalAnnotatorBatchInspection

    private class HostSuppressedInjectedInspection : InjectedFindingInspection() {
        override fun isSuppressedFor(element: PsiElement): Boolean = element is XmlAttributeValue
    }

    private class InjectedCancellingInspection(private val callerIndicator: ProgressIndicator) : InjectedFindingInspection() {
        override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor =
            object : PsiElementVisitor() {
                override fun visitFile(file: PsiFile) {
                    callerIndicator.cancel()
                    ProgressManager.checkCanceled()
                }
            }
    }

    private fun createPhysicalXmlFile(text: String): PsiFile {
        val project = projectExtension.project
        val module = projectExtension.module
        return runInEdtAndGet {
            WriteAction.compute<PsiFile, RuntimeException> {
                val contentRoot = ModuleRootManager.getInstance(module).contentRoots.single()
                val virtualFile = contentRoot.createChildData(this, "injected-problem-location-${System.nanoTime()}.xml")
                VfsUtil.saveText(virtualFile, text)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
            }
        }
    }

    private object AttributeValuePlainTextInjector : MultiHostInjector, DumbAware {
        override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
            val host = context as? PsiLanguageInjectionHost ?: return
            if (host !is XmlAttributeValue || host.textLength < 2) return
            registrar.startInjecting(PlainTextLanguage.INSTANCE)
                .addPlace(null, null, host, TextRange(1, host.textLength - 1))
                .doneInjecting()
        }

        override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(XmlAttributeValue::class.java)
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()
    }
}
