package com.shiny.inspectionmcp.core

import java.nio.file.Path
import java.nio.file.Paths

const val INSPECTION_REGISTRY_DIR_ENV = "JETBRAINS_INSPECTION_REGISTRY_DIR"

fun inspectionRegistryInstancesDir(
    environment: (String) -> String? = System::getenv,
    systemProperty: (String) -> String? = System::getProperty,
): Path {
    environment(INSPECTION_REGISTRY_DIR_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let { return Paths.get(it) }

    val userHome = systemProperty("user.home").orEmpty()
    val osName = systemProperty("os.name").orEmpty().lowercase()
    val cacheBase = when {
        osName.startsWith("windows") -> environment("LOCALAPPDATA")?.let { Paths.get(it) }
            ?: Paths.get(userHome, "AppData", "Local")
        osName.startsWith("mac") -> Paths.get(userHome, "Library", "Caches")
        else -> environment("XDG_CACHE_HOME")?.let { Paths.get(it) }
            ?: Paths.get(userHome, ".cache")
    }
    return cacheBase.resolve("jetbrains-inspection-api").resolve("instances")
}
