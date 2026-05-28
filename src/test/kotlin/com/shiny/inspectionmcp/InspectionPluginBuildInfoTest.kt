package com.shiny.inspectionmcp

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectionPluginBuildInfoTest {

    @Test
    fun generatedBuildInfoResourceIsReadable() {
        val buildInfo = loadInspectionPluginBuildInfo()

        assertNotNull(buildInfo.commit)
        assertNotNull(buildInfo.shortCommit)
        assertNotNull(buildInfo.dirty)
        assertNotNull(buildInfo.time)
        assertNotNull(buildInfo.fingerprint)
        val shortCommit = requireNotNull(buildInfo.shortCommit)
        val fingerprint = requireNotNull(buildInfo.fingerprint)
        assertTrue(shortCommit.isNotBlank())
        assertTrue(fingerprint.contains(shortCommit))
    }
}
