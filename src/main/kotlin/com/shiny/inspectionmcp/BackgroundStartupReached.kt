package com.shiny.inspectionmcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key

private val BACKGROUND_STARTUP_REACHED = Key.create<Boolean>("inspection.api.background.startup.reached")

// The platform runs background post-startup activities, such as PyCharm's project-model sync,
// after a delay once a project opens. This one runs after that same delay, so its mark tells the
// quiescence gate that those activities have launched and their changes can now be observed.
class BackgroundStartupReached : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.putUserData(BACKGROUND_STARTUP_REACHED, true)
    }
}

internal fun backgroundStartupReached(project: Project): Boolean = project.getUserData(BACKGROUND_STARTUP_REACHED) == true
