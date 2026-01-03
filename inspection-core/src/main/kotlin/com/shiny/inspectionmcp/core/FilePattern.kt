package com.shiny.inspectionmcp.core

fun compileFilePatternRegex(patternRaw: String): Regex? {
    val pattern = patternRaw.trim()
    if (pattern.isBlank()) {
        return null
    }

    try {
        return Regex(pattern, RegexOption.IGNORE_CASE)
    } catch (_: Exception) {
        val looksLikeGlob = pattern.contains('*') || pattern.contains('?')
        if (!looksLikeGlob) {
            return null
        }

        val special = setOf('.', '^', '$', '+', '(', ')', '[', ']', '{', '}', '|', '\\')
        val sb = StringBuilder(pattern.length * 2)
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> {
                    if (ch in special) sb.append('\\')
                    sb.append(ch)
                }
            }
        }
        val regexPattern = sb.toString()

        return try {
            Regex(regexPattern, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            null
        }
    }
}
