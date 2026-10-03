package com.shiny.inspectionmcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.LowMemoryWatcher
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

internal class InspectionIdeMemoryState(
    private val processStartedAtMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) : Handler() {
    private val lowMemoryAtMs = AtomicLong(0)
    private val exhaustedAtMs = AtomicLong(0)

    fun lowMemorySignal() {
        lowMemoryAtMs.set(now())
    }

    override fun publish(record: LogRecord) {
        if (record.level.intValue() >= Level.SEVERE.intValue() &&
            record.thrown is OutOfMemoryError && record.millis >= processStartedAtMs && record.millis <= now()
        ) {
            exhaustedAtMs.compareAndSet(0, record.millis)
        }
    }

    override fun flush() = Unit
    override fun close() = Unit

    fun snapshot(): Map<String, Any?> {
        val heap = try {
            ManagementFactory.getMemoryMXBean().heapMemoryUsage
        } catch (error: OutOfMemoryError) {
            exhaustedAtMs.compareAndSet(0, now())
            throw error
        }
        val exhausted = exhaustedAtMs.get()
        val signal = lowMemoryAtMs.get()
        val pressure = signal > 0 && now() - signal in 0..LOW_MEMORY_WINDOW_MS
        val status = when {
            exhausted > 0 -> "exhausted"
            pressure -> "low_memory"
            else -> "normal"
        }
        return mapOf(
            "status" to status,
            "out_of_memory_at_ms" to exhausted.takeIf { it > 0 },
            "low_memory_signal_at_ms" to signal.takeIf { it > 0 },
            "heap_used_bytes" to heap.used,
            "heap_max_bytes" to heap.max,
            "next_action" to when (status) {
                "exhausted" -> "Ask the IDE owner to check for a memory-error project-opening dialog (such as Java heap space), dismiss it, and restart the IDE before inspecting again."
                "low_memory" -> "Recent IDE memory pressure is diagnostic context; it does not prove heap exhaustion."
                else -> null
            },
        )
    }

    companion object {
        private const val LOW_MEMORY_WINDOW_MS = 30_000L
    }
}

internal class InspectionIdeMemory : Disposable {
    private val state = InspectionIdeMemoryState(ManagementFactory.getRuntimeMXBean().startTime)
    private val rootLogger = Logger.getLogger("")

    init {
        rootLogger.addHandler(state)
        LowMemoryWatcher.register(
            state::lowMemorySignal,
            LowMemoryWatcher.LowMemoryWatcherType.ONLY_AFTER_GC,
            this,
        )
    }

    override fun dispose() {
        rootLogger.removeHandler(state)
    }

    companion object {
        fun snapshot(): Map<String, Any?> = ApplicationManager.getApplication()
            .getService(InspectionIdeMemory::class.java).state.snapshot()
    }
}
