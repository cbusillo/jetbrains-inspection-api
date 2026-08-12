package com.shiny.inspectionmcp

import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.PairedUnfairLocalInspectionTool

internal enum class MissingBatchWrapperPlatformClassification(val diagnosticValue: String) {
    INTENTIONALLY_NON_BATCH("intentionally_non_batch"),
    MISSING_BATCH_WRAPPER("missing_batch_wrapper"),
    METADATA_UNAVAILABLE("metadata_unavailable"),
}

internal data class MissingBatchWrapperPlatformMetadata(
    val classification: MissingBatchWrapperPlatformClassification,
    val sourceIsUnfair: Boolean? = null,
    val sourceIsPairedUnfair: Boolean? = null,
    val pairedBatchShortName: String? = null,
    val failureStage: String? = null,
) {
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "missing_batch_wrapper_platform_classification" to classification.diagnosticValue,
        "source_is_unfair" to sourceIsUnfair,
        "source_is_paired_unfair" to sourceIsPairedUnfair,
        "paired_batch_short_name" to pairedBatchShortName,
        "platform_metadata_failure_stage" to failureStage,
    ).filterValues { it != null }
}

internal fun classifyMissingBatchWrapperPlatformMetadata(
    sourceWrapper: LocalInspectionToolWrapper,
): MissingBatchWrapperPlatformMetadata {
    val sourceIsUnfair = try {
        sourceWrapper.isUnfair
    } catch (_: Exception) {
        return MissingBatchWrapperPlatformMetadata(
            classification = MissingBatchWrapperPlatformClassification.METADATA_UNAVAILABLE,
            failureStage = "source_is_unfair",
        )
    }
    if (!sourceIsUnfair) {
        return MissingBatchWrapperPlatformMetadata(
            classification = MissingBatchWrapperPlatformClassification.MISSING_BATCH_WRAPPER,
            sourceIsUnfair = false,
            sourceIsPairedUnfair = false,
        )
    }

    val sourceTool = try {
        sourceWrapper.tool
    } catch (_: Exception) {
        return MissingBatchWrapperPlatformMetadata(
            classification = MissingBatchWrapperPlatformClassification.METADATA_UNAVAILABLE,
            sourceIsUnfair = true,
            failureStage = "source_tool",
        )
    }
    val pairedTool = sourceTool as? PairedUnfairLocalInspectionTool
        ?: return MissingBatchWrapperPlatformMetadata(
            classification = MissingBatchWrapperPlatformClassification.INTENTIONALLY_NON_BATCH,
            sourceIsUnfair = true,
            sourceIsPairedUnfair = false,
        )

    val pairedBatchShortName = try {
        pairedTool.inspectionForBatchShortName
    } catch (_: Exception) {
        return MissingBatchWrapperPlatformMetadata(
            classification = MissingBatchWrapperPlatformClassification.METADATA_UNAVAILABLE,
            sourceIsUnfair = true,
            sourceIsPairedUnfair = true,
            failureStage = "paired_batch_short_name",
        )
    }
    return MissingBatchWrapperPlatformMetadata(
        classification = MissingBatchWrapperPlatformClassification.MISSING_BATCH_WRAPPER,
        sourceIsUnfair = true,
        sourceIsPairedUnfair = true,
        pairedBatchShortName = pairedBatchShortName,
    )
}

internal fun countMissingBatchWrapperPlatformClassificationExamples(
    examples: List<Map<String, Any?>>,
    classification: MissingBatchWrapperPlatformClassification,
): Int = examples.count {
    it["missing_batch_wrapper_platform_classification"] == classification.diagnosticValue
}
