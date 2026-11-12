package com.shiny.inspectionmcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.Collections
import java.util.WeakHashMap

private val backgroundStartupReachedProjects: MutableSet<Project> =
    Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

// The platform runs background post-startup activities, such as PyCharm's project-model sync,
// after a delay once a project opens. This one runs after that same delay, so its mark tells the
// quiescence gate that those activities have launched and their changes can now be observed.
class BackgroundStartupReached : ProjectActivity {
    override suspend fun execute(project: Project) {
        markBackgroundStartupReached(project)
    }
}

internal fun markBackgroundStartupReached(project: Project) {
    backgroundStartupReachedProjects.add(project)
}

internal fun backgroundStartupReached(project: Project): Boolean = project in backgroundStartupReachedProjects
