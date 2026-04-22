package com.shiny.inspectionmcp.core

fun compileFilePatternRegex(patternRaw: String): Regex? {
    val pattern = patternRaw.trim()
    if (pattern.isBlank()) {
        return null
    }

    if (looksLikeGlobPattern(pattern)) {
        return compileGlobPattern(pattern)
    }

    if (!looksLikeRegexPattern(pattern)) {
        return Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
    }

    return try {
        Regex(pattern, RegexOption.IGNORE_CASE)
    } catch (_: Exception) {
        Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
    }
}

fun normalizedPathMatchesCurrentFile(problemFilePath: String, currentFilePath: String): Boolean {
    val problemPath = normalizeFilePathForMatching(problemFilePath)
    val currentPath = normalizeFilePathForMatching(currentFilePath)
    if (problemPath.isBlank() || currentPath.isBlank()) {
        return false
    }

    return problemPath.equals(currentPath, ignoreCase = true) ||
        problemPath.endsWithPathSegment(currentPath) ||
        currentPath.endsWithPathSegment(problemPath)
}

private fun looksLikeRegexPattern(pattern: String): Boolean {
    return pattern.any { it in setOf('^', '$', '+', '(', ')', '[', ']', '{', '}', '|', '\\') } ||
        pattern.contains(".*") ||
        pattern.contains(".+") ||
        pattern.contains(".?")
}

private fun looksLikeGlobPattern(pattern: String): Boolean {
    if (!pattern.contains('*') && !pattern.contains('?')) {
        return false
    }
    return !looksLikeRegexPattern(pattern)
}

private fun compileGlobPattern(pattern: String): Regex? {
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

private fun normalizeFilePathForMatching(path: String): String {
    return path.trim()
        .removePrefix("file://")
        .replace('\\', '/')
        .replace(Regex("/+"), "/")
        .trimEnd('/')
}

private fun String.endsWithPathSegment(suffix: String): Boolean {
    if (suffix.isBlank()) {
        return false
    }
    if (!endsWith(suffix, ignoreCase = true)) {
        return false
    }
    val boundary = length - suffix.length - 1
    return boundary < 0 || this[boundary] == '/'
}
