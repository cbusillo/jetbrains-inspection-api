package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectionPluginBuildInfoTest {

    @Test
    fun generatedBuildInfoResourceIsReadable() {
        val buildInfo = loadInspectionPluginBuildInfo()

        assertNotNull(buildInfo.version)
        assertNotNull(buildInfo.commit)
        assertNotNull(buildInfo.shortCommit)
        assertNotNull(buildInfo.dirty)
        assertNotNull(buildInfo.time)
        assertNotNull(buildInfo.fingerprint)
        val shortCommit = requireNotNull(buildInfo.shortCommit)
        val fingerprint = requireNotNull(buildInfo.fingerprint)
        assertTrue(requireNotNull(buildInfo.version).isNotBlank())
        assertTrue(shortCommit.isNotBlank())
        assertTrue(fingerprint.startsWith(requireNotNull(buildInfo.commit)))
    }

    @Test
    fun ideChannelUsesStableSelectorVocabulary() {
        assertTrue(inspectionIdeChannel(isEap = true) == "eap")
        assertTrue(inspectionIdeChannel(isEap = false) == "stable")
    }
}
