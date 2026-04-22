package com.shiny.inspectionmcp.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FilePatternTest {
    @Test
    @DisplayName("compileFilePatternRegex: regex pattern compiles")
    fun testRegexPattern() {
        val rx = compileFilePatternRegex(".*\\.py$")
        assertNotNull(rx)
        assertTrue(rx!!.containsMatchIn("/tmp/a.py"))
        assertFalse(rx.containsMatchIn("/tmp/a.js"))
    }

    @Test
    @DisplayName("compileFilePatternRegex: glob pattern compiles")
    fun testGlobPattern() {
        val rx = compileFilePatternRegex("*.py")
        assertNotNull(rx)
        assertTrue(rx!!.containsMatchIn("/tmp/a.py"))
        assertFalse(rx.containsMatchIn("/tmp/a.js"))
    }

    @Test
    @DisplayName("compileFilePatternRegex: invalid regex glob still compiles as glob")
    fun testInvalidRegexGlobPattern() {
        val rx = compileFilePatternRegex("*[fixture].kt")
        assertNotNull(rx)
        assertTrue(rx!!.containsMatchIn("src/generated[fixture].kt"))
        assertFalse(rx.containsMatchIn("src/generated.kt"))
    }

    @Test
    @DisplayName("compileFilePatternRegex: empty returns null")
    fun testBlankPattern() {
        assertNull(compileFilePatternRegex(""))
        assertNull(compileFilePatternRegex("   "))
    }
}
