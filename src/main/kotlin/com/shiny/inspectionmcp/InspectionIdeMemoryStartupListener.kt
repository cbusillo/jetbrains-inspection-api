package com.shiny.inspectionmcp

import com.intellij.ide.AppLifecycleListener

internal class InspectionIdeMemoryStartupListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        runCatching { InspectionIdeMemory.ensureObserving() }
    }
}
