package com.shiny.inspectionmcp

import com.intellij.openapi.progress.ProcessCanceledException
import java.lang.reflect.InvocationTargetException

internal fun rethrowIfCanceled(error: Throwable) {
    val cause = (error as? InvocationTargetException)?.targetException ?: error
    if (cause is ProcessCanceledException) throw cause
}
