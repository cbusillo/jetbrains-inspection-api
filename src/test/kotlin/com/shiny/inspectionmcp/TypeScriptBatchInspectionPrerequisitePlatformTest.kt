package com.shiny.inspectionmcp

import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.InspectionProfile
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.lang.javascript.ecmascript6.TypeScriptAnnotatorCheckerProvider
import com.intellij.lang.typescript.inspections.TypeScriptUnresolvedReferenceInspection
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TypeScriptBatchInspectionPrerequisitePlatformTest {
    @Test
    fun `API withholds delegated batch findings and provides an exact file recovery`() {
        assertApiDelegation(initiallyDelegated = true)
    }

    @Test
    fun `API rechecks service delegation before publishing a completed native run`() {
        assertApiDelegation(initiallyDelegated = false, correctedSources = true)
    }

    @Test
    fun `API retains proven findings while withholding delegated diagnostic coverage`() {
        assertApiDelegation(initiallyDelegated = true, knownFinding = KnownFindingInspection())
    }

    @Test
    fun `API confirms clean nondelegated coverage even when an uninterrupted prerequisite read takes time`() {
        assertApiDelegation(initiallyDelegated = false, cleanControl = true, slowFirstCheck = true)
    }

    @Test
    fun `API resumes a slow prerequisite read after an IDE write`() {
        assertApiDelegation(initiallyDelegated = false, cleanControl = true, slowFirstCheck = true, preemptSlowRead = true)
    }

    @Test
    fun `API resumes slow prerequisite reads after separated IDE writes`() {
        assertApiDelegation(initiallyDelegated = false, cleanControl = true, slowFirstCheck = true, preemptSlowRead = true, writesToTrigger = 2)
    }

    @Test
    fun `API attributes unavailable prerequisite coverage to write preemption`() {
        assertApiDelegation(initiallyDelegated = false, cleanControl = true, writePreemptionFailure = true)
    }

    private fun assertApiDelegation(
        initiallyDelegated: Boolean,
        knownFinding: LocalInspectionTool? = null,
        cleanControl: Boolean = false,
        slowFirstCheck: Boolean = false,
        preemptSlowRead: Boolean = false,
        correctedSources: Boolean = false,
        writesToTrigger: Int = 1,
        writePreemptionFailure: Boolean = false,
    ) {
        val project = projectExtension.project
        val root = Files.createTempDirectory("typescript-delegation-api-")
        val path = root.resolve("broken.ts")
        Files.writeString(path, if (cleanControl || correctedSources) "export const value = 42;" else "export const value = missingBatchSymbol;")
        val otherPath = root.resolve("correct.ts")
        Files.writeString(otherPath, "export const other = 42;")
        runInEdtAndGet {
            val directory = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root))
            VfsUtil.markDirtyAndRefresh(false, true, true, directory)
            PsiTestUtil.addContentRoot(projectExtension.module, directory)
        }
        val tool = TypeScriptUnresolvedReferenceInspection()
        val delegated = AtomicBoolean(initiallyDelegated)
        delegationToActivate.set(delegated.takeUnless { cleanControl })
        val activating = DelegationActivatingInspection()
        val wrappers = listOf(tool, activating) + listOfNotNull(knownFinding)
        val profile = createProfile(*wrappers.map(::LocalInspectionToolWrapper).toTypedArray())
        val manager = ProjectInspectionProfileManager.getInstance(project)
        manager.addProfile(profile)
        val disposable = Disposer.newDisposable()
        val delayed = AtomicBoolean(false)
        val slowReadReady = LinkedBlockingQueue<Unit>()
        val slowReads = AtomicInteger(if (preemptSlowRead) writesToTrigger else 1)
        val provider = object : TypeScriptAnnotatorCheckerProvider() {
            override fun skipErrors(element: PsiElement?): Boolean {
                if (slowFirstCheck && slowReads.getAndDecrement() > 0) {
                    delayed.set(true)
                    Thread.sleep(2100)
                    if (preemptSlowRead) {
                        slowReadReady.add(Unit)
                        val preemptionDeadline = System.nanoTime() + 10_000_000_000L
                        while (ProgressManager.getInstance().progressIndicator?.isCanceled != true) {
                            assertThat(System.nanoTime()).describedAs("IDE write must preempt the active prerequisite read")
                                .isLessThan(preemptionDeadline)
                            Thread.sleep(10)
                        }
                    }
                }
                return delegated.get()
            }
        }
        ExtensionTestUtil.maskExtensions(
            TypeScriptAnnotatorCheckerProvider.EP, listOf(provider), disposable, areaInstance = project,
        )
        if (writePreemptionFailure) {
            val failingPrerequisite = object : BatchInspectionPrerequisite {
                override fun delegatedInspection(file: PsiFile, profile: InspectionProfile): String? {
                    throw NativeInspectionObservationUnavailable(BATCH_INSPECTION_WRITE_PREEMPTED_REASON)
                }
            }
            ExtensionTestUtil.maskExtensions(
                BatchInspectionPrerequisite.EP, listOf(failingPrerequisite), disposable, areaInstance = project,
            )
        }
        try {
            val handler = InspectionHandler()
            val selector = "project=${encode(project.name)}"
            val trigger = request(handler, "/api/inspection/trigger?scope=files&file=${encode(path.toString())}&file=${encode(otherPath.toString())}&profile=${encode(profile.name)}&$selector")
            assertThat(Json.parseToJsonElement(trigger).jsonObject["status"]?.jsonPrimitive?.content).isEqualTo("triggered")
            if (preemptSlowRead) {
                repeat(writesToTrigger) {
                    assertThat(slowReadReady.poll(10, TimeUnit.SECONDS)).isNotNull()
                    ApplicationManager.getApplication().invokeLater { WriteAction.run<RuntimeException> {} }
                }
            }
            val deadline = System.nanoTime() + 180_000_000_000L
            var status = ""
            while (System.nanoTime() < deadline) {
                status = request(handler, "/api/inspection/status?$selector")
                if (Json.parseToJsonElement(status).jsonObject["inspection_in_progress"]?.jsonPrimitive?.content == "false") break
                Thread.sleep(100)
            }
            val problems = request(handler, "/api/inspection/problems?scope=files&file=${encode(path.toString())}&file=${encode(otherPath.toString())}&$selector")
            val response = Json.parseToJsonElement(problems).jsonObject
            if (cleanControl && !writePreemptionFailure) {
                if (slowFirstCheck) assertThat(delayed.get()).isTrue()
                assertThat(response["inspection_verdict"]?.jsonPrimitive?.content).isEqualTo("GREEN")
                assertThat(response["inspection_proof"]?.jsonObject?.get("status")?.jsonPrimitive?.content).isEqualTo("complete")
                assertThat(response["problems"]?.jsonArray).isEmpty()
                return
            }
            if (writePreemptionFailure) {
                assertThat(response["inspection_verdict"]?.jsonPrimitive?.content).isEqualTo("UNKNOWN")
                assertThat(response["inspection_proof"]?.jsonObject?.get("status")?.jsonPrimitive?.content).isEqualTo("failed")
                assertThat(response["inspection_attribution"]?.jsonObject?.get("classification")?.jsonPrimitive?.content)
                    .isEqualTo("legitimate_fail_closed")
                assertThat(response["capture_diagnostic"]?.jsonObject?.get("batch_inspection_prerequisite_read_failure")?.jsonPrimitive?.content)
                    .isEqualTo(BATCH_INSPECTION_WRITE_PREEMPTED_REASON)
                return
            }
            if (knownFinding != null) {
                assertThat(response["inspection_verdict"]?.jsonPrimitive?.content).isEqualTo("RED")
                assertThat(response["problems"]?.jsonArray?.map { it.jsonObject["inspectionType"]?.jsonPrimitive?.content })
                    .contains(knownFinding.shortName)
            } else {
                assertThat(response["inspection_verdict"]?.jsonPrimitive?.content).isEqualTo("UNKNOWN")
                assertThat(response["capture_incomplete_reason"]?.jsonPrimitive?.content)
                    .isEqualTo(CaptureIncompleteReason.EXECUTION_NOT_PROVEN.apiValue)
                assertThat(response["inspection_attribution"]?.jsonObject?.get("classification")?.jsonPrimitive?.content)
                    .isEqualTo("configuration_blocked")
            }
            val diagnostic = requireNotNull(response["capture_diagnostic"]).jsonObject
            val pairs = requireNotNull(diagnostic["batch_inspection_delegations"]).jsonArray
            assertThat(pairs.map { it.jsonObject["tool"]?.jsonPrimitive?.content }).contains(tool.shortName)
            assertThat(pairs.map { it.jsonObject["file"]?.jsonPrimitive?.content }).contains(path.toString())
            assertThat(diagnostic["execution_proof_established"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(response["inspection_proof"]?.jsonObject?.get("status")?.jsonPrimitive?.content).isEqualTo("failed")
            assertThat(response["inspection_verdict"]?.jsonPrimitive?.content).isNotEqualTo("GREEN")

        } finally {
            Disposer.dispose(disposable)
            delegationToActivate.set(null)
            manager.deleteProfile(profile.name)
            runInEdtAndGet {
                val directory = requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(root))
                PsiTestUtil.removeContentEntry(projectExtension.module, directory)
            }
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

    private class KnownFindingInspection : LocalInspectionTool() {
        override fun getDisplayName(): String = shortName

        override fun getGroupDisplayName(): String = "TypeScript delegation controls"

        override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                holder.registerProblem(file, displayName)
            }
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
