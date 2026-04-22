package com.shiny.inspectionmcp.core

fun compileFilePatternRegex(patternRaw: String): Regex? {
    val pattern = patternRaw.trim()
    if (pattern.isBlank()) {
        return null
    }

    return try {
        Regex(pattern, RegexOption.IGNORE_CASE)
    } catch (_: Exception) {
        val looksLikeGlob = pattern.contains('*') || pattern.contains('?')
        if (looksLikeGlob) {
            compileGlobPattern(pattern)
        } else {
            null
        }
    }
}

fun compileLiteralFilePatternRegex(patternRaw: String): Regex? {
    val pattern = patternRaw.trim()
    if (pattern.isBlank()) {
        return null
    }
    return Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
}

fun usesPatternFileSyntax(patternRaw: String): Boolean {
    val pattern = patternRaw.trim()
    return pattern.contains('*') ||
        pattern.contains('?') ||
        pattern.contains('\\') ||
        pattern.startsWith('^') ||
        pattern.endsWith('$') ||
        pattern.contains('(') ||
        pattern.contains(')') ||
        pattern.contains('{') ||
        pattern.contains('}') ||
        pattern.contains(".*") ||
        pattern.contains(".+") ||
        pattern.contains(".?") ||
        pattern.contains("\\d") ||
        pattern.contains("\\w") ||
        pattern.contains("\\s") ||
        pattern.contains('|') ||
        pattern.contains("(?")
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

private fun normalizeFilePathForMatching(path: String): String {
    if (path.isBlank()) {
        return ""
    }
    return path
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

    return try {
        Regex(sb.toString(), RegexOption.IGNORE_CASE)
    } catch (_: Exception) {
        null
    }
}
