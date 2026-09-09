package com.shiny.inspectionmcp

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun <T> runWritePriorityInspectionRead(
    indicator: ProgressIndicator,
    onPreempt: () -> Unit,
    action: () -> T,
): T {
    indicator.checkCanceled()
    val preempted = AtomicBoolean(false)
    val completed = AtomicBoolean(false)
    val result = AtomicReference<T>()
    val actionCancellation = AtomicReference<ProcessCanceledException?>()
    val writePriorityIndicator = object : ProgressIndicator by indicator {
        override fun cancel() {
            if (preempted.compareAndSet(false, true)) {
                try {
                    // This callback runs before an IDE write and must not acquire IDE read/write access.
                    onPreempt()
                } catch (_: Throwable) {
                } finally {
                    indicator.cancel()
                }
            } else {
                indicator.cancel()
            }
        }
    }

    try {
        val readActionCompleted = ProgressIndicatorUtils.runInReadActionWithWriteActionPriority(
            Runnable {
                indicator.checkCanceled()
                try {
                    result.set(action())
                    completed.set(true)
                } catch (error: ProcessCanceledException) {
                    actionCancellation.set(error)
                    throw error
                }
            },
            writePriorityIndicator,
        )
        if (!readActionCompleted && !preempted.get()) indicator.checkCanceled()
    } catch (error: ProcessCanceledException) {
        if (preempted.get()) throw ExactFileProofWritePreemptedException()
        throw error
    }

    if (preempted.get()) {
        throw ExactFileProofWritePreemptedException()
    }
    actionCancellation.get()?.let { throw it }
    if (!completed.get()) throw ExactFileProofWritePreemptedException()

    return result.get()
}
