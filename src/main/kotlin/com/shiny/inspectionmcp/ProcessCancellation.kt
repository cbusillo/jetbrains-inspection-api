package com.shiny.inspectionmcp

import com.intellij.openapi.progress.ProcessCanceledException

internal fun rethrowIfCanceled(error: Throwable) {
    if (error is ProcessCanceledException) throw error
}
