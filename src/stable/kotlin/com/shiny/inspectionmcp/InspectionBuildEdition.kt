package com.shiny.inspectionmcp

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection

// The Marketplace Stable build: public IntelliJ APIs only. In a normal IDE the public native
// run skips its per-file pass, so Stable cannot attest broad scopes.
internal object InspectionBuildEdition {
    const val NAME = "stable"

    val nativeAttestationUnavailableReason: String? = STABLE_BUILD_BROAD_SCOPE_UNPROVEN_REASON

    fun createNativeContext(
        inspectionManager: InspectionManagerEx,
        project: Project,
        collector: NativeInspectionExecutionProofCollector?,
    ): GlobalInspectionContextEx {
        check(collector == null) { "The Stable build has no native attestation context." }
        check(project === inspectionManager.project) { "The native context must belong to the inspection manager's project." }
        return synchronized(inspectionManager) {
            (inspectionManager as InspectionManager).createNewGlobalContext() as GlobalInspectionContextEx
        }
    }

    fun subscribeNativeProofEvents(
        project: Project,
        collector: NativeInspectionExecutionProofCollector,
    ): MessageBusConnection = error("The Stable build has no native proof event subscription.")
}
