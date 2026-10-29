package com.shiny.inspectionmcp

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException

internal fun rethrowIfCanceled(error: Throwable) {
    val cause = (error as? InvocationTargetException)?.targetException ?: error
    if (cause is CancellationException) throw cause
}

internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Throwable) {
    rethrowIfCanceled(error)
    Result.failure(error)
}

internal fun runAllCleanupActions(vararg actions: () -> Unit) {
    val results = actions.map { action ->
        runCatching {
            // Cancellation: rethrown below after every cleanup action has been attempted.
            action()
        }
    }
    results.forEach { result -> result.exceptionOrNull()?.let(::rethrowIfCanceled) }
    results.firstOrNull { it.isFailure }?.getOrThrow()
}
