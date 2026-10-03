package com.shiny.inspectionmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.LowMemoryWatcher
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

internal class InspectionIdeMemoryState(
    private val logPath: Path,
    private val processStartedAtMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lowMemoryAtMs = AtomicLong(0)
    private val exhaustedAtMs = AtomicLong(0)

    fun lowMemorySignal() {
        lowMemoryAtMs.set(now())
    }

    fun snapshot(): Map<String, Any?> {
        if (exhaustedAtMs.get() == 0L) {
            runCatching {
                FileChannel.open(logPath, StandardOpenOption.READ).use { channel ->
                    val size = channel.size()
                    val offset = (size - MAX_LOG_BYTES).coerceAtLeast(0)
                    channel.position(offset)
                    val buffer = ByteBuffer.allocate((size - offset).toInt())
                    while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
                    buffer.flip()
                    observeLog(Charsets.UTF_8.decode(buffer).toString())
                }
            }
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
            "heap_used_bytes" to ManagementFactory.getMemoryMXBean().heapMemoryUsage.used,
            "heap_max_bytes" to ManagementFactory.getMemoryMXBean().heapMemoryUsage.max,
            "next_action" to when (status) {
                "exhausted" -> "Check for the Java heap space project-opening dialog, dismiss it, and restart the IDE before inspecting again."
                "low_memory" -> "Wait for IDE memory pressure to settle before opening another project; restart the IDE if it persists."
                else -> null
            },
        )
    }

    private fun observeLog(text: String) {
        var timestamp: Long? = null
        text.lineSequence().forEach { line ->
            LOG_TIMESTAMP.find(line)?.let { match ->
                timestamp = runCatching {
                    LocalDateTime.parse(match.value, LOG_DATE_FORMAT)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            }
            val observedAt = timestamp
            if (observedAt != null && observedAt >= processStartedAtMs && observedAt <= now() &&
                line.contains("java.lang.OutOfMemoryError")
            ) {
                exhaustedAtMs.compareAndSet(0, observedAt)
            }
        }
    }

    companion object {
        private const val MAX_LOG_BYTES = 262_144L
        private const val LOW_MEMORY_WINDOW_MS = 30_000L
        private val LOG_TIMESTAMP = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3}")
        private val LOG_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS")
    }
}

internal object InspectionIdeMemory {
    private val state by lazy {
        InspectionIdeMemoryState(
            Path.of(PathManager.getLogPath(), "idea.log"),
            ManagementFactory.getRuntimeMXBean().startTime,
        ).also { memory ->
            LowMemoryWatcher.register(
                memory::lowMemorySignal,
                LowMemoryWatcher.LowMemoryWatcherType.ONLY_AFTER_GC,
                ApplicationManager.getApplication(),
            )
        }
    }

    fun snapshot(): Map<String, Any?> = state.snapshot()
}
