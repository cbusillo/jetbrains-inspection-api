package com.shiny.inspectionmcp

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager

internal class InspectionIdeMemoryStartupListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        runCatching { InspectionIdeMemory.ensureObserving() }
        runCatching {
            ApplicationManager.getApplication().getService(HelperProjectCacheRegistry::class.java).ensureObserving()
        }
    }
}
