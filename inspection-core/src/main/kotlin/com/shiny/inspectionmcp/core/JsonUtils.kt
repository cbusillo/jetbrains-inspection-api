package com.shiny.inspectionmcp.core

fun formatJsonManually(data: Any?): String {
    return when (data) {
        is String -> "\"${data.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
        is Number -> data.toString()
        is Boolean -> data.toString()
        is List<*> -> data.joinToString(",", "[", "]") { formatJsonManually(it) }
        is Map<*, *> -> data.entries.joinToString(",\n  ", "{\n  ", "\n}") { (k, v) ->
            "\"$k\": ${formatJsonManually(v)}"
        }
        null -> "null"
        else -> "\"$data\""
    }
}
