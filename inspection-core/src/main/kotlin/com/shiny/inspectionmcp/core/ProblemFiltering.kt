package com.shiny.inspectionmcp.core

data class ProblemPage(
    val total: Int,
    val shown: Int,
    val problems: List<Map<String, Any>>,
    val hasMore: Boolean,
    val nextOffset: Int?
)

fun normalizeProblemsScope(scopeRaw: String): String {
    val trimmed = scopeRaw.trim()
    if (trimmed.isBlank()) {
        return "whole_project"
    }

    return when (trimmed.lowercase()) {
        "whole_project" -> "whole_project"
        "current_file" -> "current_file"
        "files", "directory", "changed_files" -> "whole_project"
        else -> trimmed
    }
}

fun filterProblems(
    problems: List<Map<String, Any>>,
    severity: String,
    scope: String,
    currentFilePath: String?,
    problemType: String?,
    filePattern: String?
): List<Map<String, Any>> {
    val severityFiltered = if (severity == "all") {
        problems
    } else {
        problems.filter {
            val problemSeverity = it["severity"] as? String ?: ""
            severity == problemSeverity ||
                (severity == "warning" && (problemSeverity == "grammar" || problemSeverity == "typo"))
        }
    }

    val scopeFiltered = when (scope) {
        "whole_project" -> severityFiltered
        "current_file" -> {
            if (currentFilePath.isNullOrBlank()) {
                emptyList()
            } else {
                severityFiltered.filter { problem ->
                    val filePath = problem["file"] as? String ?: ""
                    filePath == currentFilePath || filePath.endsWith(currentFilePath)
                }
            }
        }
        else -> severityFiltered.filter { problem ->
            val filePath = problem["file"] as? String ?: ""
            filePath.contains(scope, ignoreCase = true)
        }
    }

    val problemTypeFiltered = if (problemType != null) {
        scopeFiltered.filter { problem ->
            val inspectionType = problem["inspectionType"] as? String ?: ""
            val category = problem["category"] as? String ?: ""
            inspectionType.contains(problemType, ignoreCase = true) ||
                category.contains(problemType, ignoreCase = true)
        }
    } else {
        scopeFiltered
    }

    val filePatternFiltered = if (filePattern != null) {
        val pattern = filePattern.trim()
        val regex = compileFilePatternRegex(pattern)
        if (regex != null) {
            problemTypeFiltered.filter { problem ->
                val filePath = problem["file"] as? String ?: ""
                regex.containsMatchIn(filePath)
            }
        } else {
            problemTypeFiltered.filter { problem ->
                val filePath = problem["file"] as? String ?: ""
                filePath.contains(pattern, ignoreCase = true)
            }
        }
    } else {
        problemTypeFiltered
    }

    return filePatternFiltered
}

fun paginateProblems(
    problems: List<Map<String, Any>>,
    limit: Int,
    offset: Int
): ProblemPage {
    val total = problems.size
    val paginated = problems.drop(offset).take(limit)
    val hasMore = offset + paginated.size < total
    val nextOffset = if (hasMore) offset + limit else null
    return ProblemPage(
        total = total,
        shown = paginated.size,
        problems = paginated,
        hasMore = hasMore,
        nextOffset = nextOffset
    )
}
