package com.shiny.inspectionmcp

internal class PythonSdkPreparationProgress(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val startedAtMs = now()
    private var stage = "queued"
    private var stageStartedAtMs = startedAtMs
    private var worker: Thread? = null

    @Synchronized
    fun enterStage(value: String) {
        if (stage != value) {
            stage = value
            stageStartedAtMs = now()
        }
    }

    @Synchronized
    fun startWorker() {
        worker = Thread.currentThread()
        enterStage("validation")
    }

    @Synchronized
    fun finishWorker() {
        worker = null
    }

    @Synchronized
    fun diagnostic(): Map<String, Any?> {
        val observedAtMs = now()
        val thread = worker
        val sampledWorker = thread?.takeUnless { it === Thread.currentThread() }
        return mapOf(
            "stage" to stage,
            "stage_elapsed_ms" to (observedAtMs - stageStartedAtMs).coerceAtLeast(0),
            "elapsed_ms" to (observedAtMs - startedAtMs).coerceAtLeast(0),
            "worker_thread" to thread?.name?.take(160),
            "worker_state" to sampledWorker?.state?.name,
            "worker_stack" to runCatching {
                sampledWorker?.stackTrace?.take(64)?.map { it.toString().take(512) }.orEmpty()
            }.getOrDefault(emptyList()),
        ).filterValues { it != null }
    }
}
