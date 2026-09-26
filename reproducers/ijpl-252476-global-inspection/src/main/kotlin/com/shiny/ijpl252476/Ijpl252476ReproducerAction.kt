package com.shiny.ijpl252476

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

class RunExportReproducerAction : Ijpl252476ReproducerAction(ReproducerMode.XML_EXPORT)

class RunOfflineReproducerAction : Ijpl252476ReproducerAction(ReproducerMode.OFFLINE_EXPORT)

abstract class Ijpl252476ReproducerAction(
    private val mode: ReproducerMode,
) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabled = project != null && !DumbService.isDumb(project)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ReproducerTask(project, mode).queue()
    }
}

enum class ReproducerMode(
    val reportName: String,
    val taskTitle: String,
) {
    XML_EXPORT(
        reportName = "xml-export",
        taskTitle = "IJPL-252476 XML Export Reproducer",
    ),
    OFFLINE_EXPORT(
        reportName = "offline-export",
        taskTitle = "IJPL-252476 Offline Export Reproducer",
    ),
}

private class ReproducerTask(
    private val targetProject: Project,
    private val mode: ReproducerMode,
) : Task.Modal(targetProject, mode.taskTitle, true) {
    private var result: ReproducerResult? = null

    override fun run(indicator: ProgressIndicator) {
        result = runReproducer(targetProject, mode, indicator)
    }

    override fun onSuccess() {
        showResult(targetProject, result)
    }

    override fun onCancel() {
        showResult(targetProject, result)
    }

    override fun onThrowable(error: Throwable) {
        val message = buildString {
            append("The reproducer task failed before it could finish its report.")
            result?.let { append("\n\nPartial report: ${it.reportDirectory}") }
            append("\n\n${error.javaClass.name}: ${error.message.orEmpty()}")
        }
        Messages.showErrorDialog(targetProject, message, mode.taskTitle)
    }
}

private data class ReproducerResult(
    val reportDirectory: Path,
    val outcome: String,
)

@Suppress("UnstableApiUsage")
private fun runReproducer(
    project: Project,
    mode: ReproducerMode,
    indicator: ProgressIndicator,
): ReproducerResult {
    val runDirectory = createRunDirectory(mode)
    val exportDirectory = runDirectory.resolve("xml")
    Files.createDirectories(exportDirectory)
    val report = ReproducerReport(runDirectory.resolve("report.txt"))
    val exportedPaths = mutableListOf<Path>()
    var context: GlobalInspectionContextEx? = null
    var outcome = "failed_before_execution"

    report.event(
        "run.start",
        environmentDetails(project, indicator) + mapOf(
            "mode" to mode.reportName,
            "run_directory" to runDirectory,
            "export_directory" to exportDirectory,
            "run_global_tools_only" to false,
            "offline_inspections" to (mode == ReproducerMode.OFFLINE_EXPORT),
        ),
    )

    try {
        val scope = AnalysisScope(project)
        val scopeFileCountStartedAt = System.nanoTime()
        val scopeFileCount = scope.fileCount
        report.event(
            "scope.ready",
            mapOf(
                "display_name" to scope.displayName,
                "file_count" to scopeFileCount,
                "file_count_elapsed_ms" to elapsedMillis(scopeFileCountStartedAt),
            ),
        )

        val inspectionManager = InspectionManager.getInstance(project)
        val createdContext = inspectionManager.createNewGlobalContext()
        context = createdContext as? GlobalInspectionContextEx
            ?: error("createNewGlobalContext() returned ${createdContext.javaClass.name}")
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        context.setExternalProfile(profile)
        context.currentScope = scope

        report.event(
            "context.ready",
            mapOf(
                "context_class" to context.javaClass.name,
                "profile_name" to profile.name,
            ),
        )

        report.event("inspection.call.start", progressDetails(indicator))
        val inspectionStartedAt = System.nanoTime()
        when (mode) {
            ReproducerMode.XML_EXPORT -> context.performInspectionsWithProgressAndExportResults(
                scope,
                false,
                false,
                exportDirectory,
                exportedPaths,
            )
            ReproducerMode.OFFLINE_EXPORT -> context.launchInspectionsOffline(
                scope,
                exportDirectory,
                false,
                exportedPaths,
            )
        }
        report.event(
            "inspection.call.returned",
            progressDetails(indicator) + mapOf(
                "elapsed_ms" to elapsedMillis(inspectionStartedAt),
                "used_tool_count" to context.usedTools.size,
            ),
        )
        outcome = "returned_normally"
    } catch (canceled: ProcessCanceledException) {
        outcome = "process_canceled_exception"
        report.event(
            "inspection.call.canceled",
            progressDetails(indicator) + throwableDetails(canceled),
        )
    } catch (error: Throwable) {
        outcome = "exception"
        report.event(
            "inspection.call.failed",
            progressDetails(indicator) + throwableDetails(error),
        )
    } finally {
        val exportedFileCount = reportExportSummary(report, exportDirectory, exportedPaths)
        if (outcome == "returned_normally") {
            outcome = if (exportedFileCount == 0) {
                "returned_normally_without_exported_results"
            } else {
                "returned_normally_with_exported_results"
            }
        }
        context?.let { closeContext(it, report) }
        report.event(
            "run.finished",
            mapOf(
                "outcome" to outcome,
                "report_directory" to runDirectory,
            ),
        )
    }

    return ReproducerResult(runDirectory, outcome)
}

private fun createRunDirectory(mode: ReproducerMode): Path {
    val timestamp = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())
    val runName = "$timestamp-${mode.reportName}-${UUID.randomUUID().toString().take(8)}"
    return Path.of(PathManager.getLogPath(), "ijpl-252476", runName).also(Files::createDirectories)
}

private fun environmentDetails(
    project: Project,
    indicator: ProgressIndicator,
): Map<String, Any?> {
    val applicationInfo = ApplicationInfo.getInstance()
    return mapOf(
        "ide_name" to applicationInfo.fullApplicationName,
        "ide_version" to applicationInfo.fullVersion,
        "ide_build" to applicationInfo.build.asString(),
        "headless" to ApplicationManager.getApplication().isHeadlessEnvironment,
        "project_name" to project.name,
        "project_path" to project.basePath,
        "project_is_dumb" to DumbService.isDumb(project),
        "idea_log_path" to Path.of(PathManager.getLogPath(), "idea.log"),
        "java_version" to System.getProperty("java.version"),
        "os_name" to System.getProperty("os.name"),
        "os_version" to System.getProperty("os.version"),
    ) + progressDetails(indicator)
}

private fun progressDetails(indicator: ProgressIndicator): Map<String, Any?> {
    val currentIndicator = ProgressManager.getInstance().progressIndicator
    return mapOf(
        "thread_name" to Thread.currentThread().name,
        "thread_id" to Thread.currentThread().threadId(),
        "is_dispatch_thread" to ApplicationManager.getApplication().isDispatchThread,
        "task_indicator_class" to indicator.javaClass.name,
        "task_indicator_canceled" to indicator.isCanceled,
        "current_indicator_class" to currentIndicator?.javaClass?.name,
        "current_indicator_canceled" to currentIndicator?.isCanceled,
    )
}

private fun reportExportSummary(
    report: ReproducerReport,
    exportDirectory: Path,
    exportedPaths: List<Path>,
): Int {
    val files = runCatching {
        Files.walk(exportDirectory).use { paths ->
            paths.filter(Files::isRegularFile).sorted().toList()
        }
    }.getOrElse { error ->
        report.event("export.scan.failed", throwableDetails(error))
        emptyList()
    }

    report.event(
        "export.summary",
        mapOf(
            "reported_path_count" to exportedPaths.size,
            "reported_paths" to exportedPaths.joinToString("\n"),
            "actual_file_count" to files.size,
            "actual_files" to files.joinToString("\n") { path ->
                "${exportDirectory.relativize(path)} (${Files.size(path)} bytes)"
            },
        ),
    )
    return files.size
}

private fun closeContext(
    context: GlobalInspectionContextEx,
    report: ReproducerReport,
) {
    val closeResult = runCatching {
        val closeAction = Runnable { context.close(false) }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            closeAction.run()
        } else {
            application.invokeAndWait(closeAction)
        }
    }

    if (closeResult.isSuccess) {
        report.event("context.closed")
        return
    }

    val closeError = closeResult.exceptionOrNull() ?: return
    report.event("context.close.failed", throwableDetails(closeError))
    runCatching { context.cleanup() }
        .onSuccess { report.event("context.cleanup.completed") }
        .onFailure { cleanupError -> report.event("context.cleanup.failed", throwableDetails(cleanupError)) }
}

private fun throwableDetails(error: Throwable): Map<String, Any?> {
    val stackTrace = StringWriter().also { writer ->
        error.printStackTrace(PrintWriter(writer))
    }.toString()
    return mapOf(
        "exception_class" to error.javaClass.name,
        "exception_message" to error.message,
        "stack_trace" to stackTrace,
    )
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis()

private fun showResult(
    project: Project,
    result: ReproducerResult?,
) {
    if (result == null) {
        Messages.showErrorDialog(
            project,
            "The reproducer did not create a report.",
            "IJPL-252476 Reproducer",
        )
        return
    }

    Messages.showInfoMessage(
        project,
        "Outcome: ${result.outcome}\n\nReport and XML files:\n${result.reportDirectory}",
        "IJPL-252476 Reproducer",
    )
}

private class ReproducerReport(
    private val reportPath: Path,
) {
    private val startedAt = Instant.now()

    @Synchronized
    fun event(
        name: String,
        values: Map<String, Any?> = emptyMap(),
    ) {
        val now = Instant.now()
        val text = buildString {
            append("=== ")
            append(now)
            append(" | +")
            append(Duration.between(startedAt, now).toMillis())
            append(" ms | ")
            append(name)
            append(" ===\n")
            values.forEach { (key, value) ->
                append(key)
                append('=')
                append(value?.toString()?.replace("\n", "\n  ") ?: "null")
                append('\n')
            }
            append('\n')
        }
        Files.writeString(
            reportPath,
            text,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
}
