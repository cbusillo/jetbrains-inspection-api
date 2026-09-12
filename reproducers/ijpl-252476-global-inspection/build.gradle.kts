import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.shiny.ijpl252476"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localIdePath = providers.gradleProperty("localIdePath")
        if (localIdePath.isPresent) {
            local(localIdePath)
        } else {
            pycharmProfessional("2026.2.1")
        }
    }
}
kotlin {
    jvmToolchain(21)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "IJPL-252476 Global Inspection Reproducer"
        description = "Minimal reproducer for synchronous global inspection XML export from a live IDE plugin"
        version = project.version.toString()

        vendor {
            name = "Shiny Computers"
            email = "info@shinycomputers.com"
        }

        ideaVersion {
            sinceBuild = "251"
            untilBuild = "262.*"
        }
    }
}
