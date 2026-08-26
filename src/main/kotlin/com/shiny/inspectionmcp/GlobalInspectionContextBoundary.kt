package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

internal data class StandardGlobalInspectionExecutionResult(
    val exportedResultPathCount: Int,
    val exportedResultPathCountTruncated: Boolean,
    val exportDirectoryDeleted: Boolean,
)

@Suppress("UnstableApiUsage")
internal class GlobalInspectionContextBoundary private constructor(
    private val inspectionManager: InspectionManager,
    private val context: GlobalInspectionContextEx,
    private val synchronizeLifecycle: Boolean,
) {
    fun configure(profile: InspectionProfileImpl, scope: AnalysisScope) {
        context.setExternalProfile(profile)
        context.currentScope = scope
    }

    fun toolGroups() = context.tools.values

    fun presentation(toolWrapper: InspectionToolWrapper<*, *>) = context.getPresentation(toolWrapper)

    fun performInspectionsWithProgressAndExportResults(scope: AnalysisScope): StandardGlobalInspectionExecutionResult {
        val exportDirectory = Files.createTempDirectory("jetbrains-inspection-export-")
        val exportedResultPaths = mutableListOf<Path>()
        var exportedResultPathCount = 0
        var exportedResultPathCountTruncated = false
        var exportDirectoryDeleted = false

        try {
            context.performInspectionsWithProgressAndExportResults(
                scope,
                false,
                false,
                exportDirectory,
                exportedResultPaths,
            )
            exportedResultPathCount = exportedResultPaths.size.coerceAtMost(MAX_EXPORTED_RESULT_PATHS)
            exportedResultPathCountTruncated = exportedResultPaths.size > MAX_EXPORTED_RESULT_PATHS
        } finally {
            exportDirectoryDeleted = deleteRecursively(exportDirectory)
        }

        return StandardGlobalInspectionExecutionResult(
            exportedResultPathCount = exportedResultPathCount,
            exportedResultPathCountTruncated = exportedResultPathCountTruncated,
            exportDirectoryDeleted = exportDirectoryDeleted,
        )
    }

    fun publicContext(): GlobalInspectionContext = context

    fun close(noSuspiciousCodeFound: Boolean) {
        if (synchronizeLifecycle) {
            synchronized(inspectionManager) {
                context.close(noSuspiciousCodeFound)
            }
        } else {
            context.close(noSuspiciousCodeFound)
        }
    }

    fun cleanup() {
        context.cleanup()
    }

    companion object {
        fun createStandard(inspectionManager: InspectionManager): GlobalInspectionContextBoundary {
            val context = inspectionManager.createNewGlobalContext() as? GlobalInspectionContextEx
                ?: error("InspectionManager.createNewGlobalContext() did not return GlobalInspectionContextEx")
            return GlobalInspectionContextBoundary(
                inspectionManager = inspectionManager,
                context = context,
                synchronizeLifecycle = false,
            )
        }

        fun createForExactFile(inspectionManager: InspectionManager): GlobalInspectionContextBoundary {
            val context = synchronized(inspectionManager) {
                inspectionManager.createNewGlobalContext() as? GlobalInspectionContextEx
                    ?: error("InspectionManager.createNewGlobalContext() did not return GlobalInspectionContextEx")
            }
            return GlobalInspectionContextBoundary(
                inspectionManager = inspectionManager,
                context = context,
                synchronizeLifecycle = true,
            )
        }

        private fun deleteRecursively(path: Path): Boolean = runCatching {
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }.isSuccess

        private const val MAX_EXPORTED_RESULT_PATHS = 100
    }
}
