package com.shiny.inspectionmcp

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
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.runInEdtAndGet
import org.assertj.core.api.Assertions.assertThat
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
