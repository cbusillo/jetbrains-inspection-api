package com.shiny.inspectionmcp

import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.progress.util.ProgressIndicatorWithDelayedPresentation

internal class InspectionProgressIndicator : ProgressIndicatorBase(), ProgressIndicatorWithDelayedPresentation {
    override fun setDelayInMillis(delayInMillis: Int) = Unit
}
