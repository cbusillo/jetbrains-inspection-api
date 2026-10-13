package com.shiny.inspectionmcp

import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.lang.javascript.ecmascript6.TypeScriptAnnotatorCheckerProvider
import com.intellij.lang.typescript.inspections.TypeScriptUnresolvedReferenceInspection
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.profile.codeInspection.BaseInspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.ProjectExtension
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.runInEdtAndGet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.extension.RegisterExtension
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TypeScriptBatchInspectionPrerequisitePlatformTest {
    @Test
    fun `API withholds delegated batch findings and provides an exact file recovery`() {
        assertApiDelegation(initiallyDelegated = true)
    }

    @Test
    fun `API rechecks service delegation before publishing a completed native run`() {
        assertApiDelegation(initiallyDelegated = false)
    }

    private fun assertApiDelegation(initiallyDelegated: Boolean) {
        val project = projectExtension.project
        val root = Files.createTempDirectory("typescript-delegation-api-")
        val path = root.resolve("broken.ts")
        Files.writeString(path, "export const value = missingBatchSymbol;")
        runInEdtAndGet {
            val directory = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root))
            VfsUtil.markDirtyAndRefresh(false, true, true, directory)
            PsiTestUtil.addContentRoot(projectExtension.module, directory)
        }
        val tool = TypeScriptUnresolvedReferenceInspection()
        val delegated = AtomicBoolean(initiallyDelegated)
        delegationToActivate.set(delegated)
        val activating = DelegationActivatingInspection()
        val profile = createProfile(LocalInspectionToolWrapper(tool), LocalInspectionToolWrapper(activating))
        val manager = ProjectInspectionProfileManager.getInstance(project)
        manager.addProfile(profile)
        val disposable = Disposer.newDisposable()
        val provider = object : TypeScriptAnnotatorCheckerProvider() {
            override fun skipErrors(element: PsiElement?): Boolean = delegated.get()
        }
        ExtensionTestUtil.maskExtensions(
            TypeScriptAnnotatorCheckerProvider.EP, listOf(provider), disposable, areaInstance = project,
        )
        try {
            val handler = InspectionHandler()
            val selector = "project=${encode(project.name)}"
            val trigger = request(handler, "/api/inspection/trigger?scope=files&file=${encode(path.toString())}&profile=${encode(profile.name)}&$selector")
            assertThat(trigger).contains("\"status\": \"triggered\"")
            val deadline = System.nanoTime() + 180_000_000_000L
            var status = ""
            while (System.nanoTime() < deadline) {
                status = request(handler, "/api/inspection/status?$selector")
                if (status.contains("\"inspection_in_progress\": false")) break
                Thread.sleep(100)
            }
            val problems = request(handler, "/api/inspection/problems?scope=files&file=${encode(path.toString())}&$selector")
            assertThat(status).describedAs(status).contains("\"inspection_in_progress\": false")
            assertThat(problems).describedAs(problems).contains("\"inspection_verdict\": \"UNKNOWN\"")
                .contains("\"classification\": \"configuration_blocked\"")
                .contains("\"capture_incomplete_reason\": \"${CaptureIncompleteReason.EXECUTION_NOT_PROVEN.apiValue}\"")
                .contains(tool.shortName)
                .contains(path.toString())
            assertThat(problems).doesNotContain("\"inspection_verdict\": \"GREEN\"")
        } finally {
            Disposer.dispose(disposable)
            delegationToActivate.set(null)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ts", "tsx"])
    fun `delegated unresolved reference cannot establish batch coverage and native recovery retains the finding`(extension: String) {
        val project = projectExtension.project
        val brokenFile = createFile("broken.$extension", "export const value = missingBatchSymbol;").virtualFile
        val correctFile = createFile("correct.$extension", "export const value = 42;").virtualFile
        val tool = TypeScriptUnresolvedReferenceInspection()
        val wrapper = LocalInspectionToolWrapper(tool)
        val profile = createProfile(wrapper)
        profile.setToolEnabled(tool.shortName, true, project)
        val indicator = ProgressIndicatorBase()
        for (delegated in listOf(true, false)) {
            val disposable = Disposer.newDisposable()
            val provider = object : TypeScriptAnnotatorCheckerProvider() {
                override fun skipErrors(element: PsiElement?): Boolean = delegated
            }
            ExtensionTestUtil.maskExtensions(
                TypeScriptAnnotatorCheckerProvider.EP, listOf(provider), disposable, areaInstance = project,
            )
            try {
                ProgressManager.getInstance().runProcess(Computable {
                    ReadAction.run<RuntimeException> {
                        val broken = requireNotNull(PsiManager.getInstance(project).findFile(brokenFile))
                        val correct = requireNotNull(PsiManager.getInstance(project).findFile(correctFile))
                        val findings = SupportedInspectionExecutor().executePreparedFile(broken, listOf(wrapper), indicator)
                            .returnedDescriptorsByToolShortName[tool.shortName].orEmpty()
                        val blockers = findBatchInspectionDelegations(profile, listOf(broken, correct))
                        if (delegated) {
                            assertThat(findings).isEmpty()
                            assertThat(blockers).containsExactlyInAnyOrder(
                                mapOf("tool" to tool.shortName, "file" to brokenFile.path),
                                mapOf("tool" to tool.shortName, "file" to correctFile.path),
                            )
                            profile.setToolEnabled(tool.shortName, false, project)
                            assertThat(findBatchInspectionDelegations(profile, listOf(broken, correct))).isEmpty()
                            profile.setToolEnabled(tool.shortName, true, project)
                        } else {
                            assertThat(blockers).isEmpty()
                            assertThat(findings).anySatisfy { finding ->
                                assertThat(finding.psiElement.text).isEqualTo("missingBatchSymbol")
                            }
                        }
                        assertThat(
                            SupportedInspectionExecutor().executePreparedFile(correct, listOf(wrapper), indicator)
                                .returnedDescriptorsByToolShortName[tool.shortName].orEmpty(),
                        ).isEmpty()
                    }
                }, indicator)
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

    private fun createProfile(vararg wrappers: LocalInspectionToolWrapper): InspectionProfileImpl {
        val project = projectExtension.project
        wrappers.forEach { HighlightDisplayKey.findOrRegister(it.shortName, it.displayName) }
        val profile = InspectionProfileImpl(
            "TypeScript batch control",
            InspectionToolsSupplier.Simple(wrappers.toList()),
            InspectionProfileManager.getInstance() as BaseInspectionProfileManager,
        )
        val previousInitInspections = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true
        try {
            profile.initInspectionTools(project)
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = previousInitInspections
        }
        wrappers.forEach { profile.setToolEnabled(it.shortName, true, project) }
        return profile
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun request(handler: InspectionHandler, uri: String): String {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri)
            assertThat(handler.process(QueryStringDecoder(uri), request, channel.pipeline().firstContext())).isTrue()
            val deadline = System.nanoTime() + 10_000_000_000L
            var response = channel.readOutbound<Any>()
            while (response == null && System.nanoTime() < deadline) {
                channel.runPendingTasks()
                Thread.sleep(10)
                response = channel.readOutbound<Any>()
            }
            assertThat(response).describedAs("response for $uri").isNotNull()
            val content = (response as? FullHttpResponse)?.content() ?: response as ByteBuf
            return content.toString(Charsets.UTF_8)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun createFile(name: String, text: String): PsiFile = runInEdtAndGet {
        WriteAction.compute<PsiFile, RuntimeException> {
            val root = ModuleRootManager.getInstance(projectExtension.module).contentRoots.first()
            val file = root.createChildData(this, name)
            VfsUtil.saveText(file, text)
            requireNotNull(PsiManager.getInstance(projectExtension.project).findFile(file))
        }
    }

    private class DelegationActivatingInspection : LocalInspectionTool() {
        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "TypeScript delegation controls"

        override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) = Unit
        }

        override fun inspectionFinished(session: LocalInspectionToolSession, holder: ProblemsHolder) {
            delegationToActivate.get()?.set(true)
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val projectExtension = ProjectExtension()

        private val delegationToActivate = AtomicReference<AtomicBoolean?>()
    }
}
