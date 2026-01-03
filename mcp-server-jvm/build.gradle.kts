import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
    id("jacoco")
}

group = "com.jetbrains.inspection"
version = rootProject.property("pluginVersion").toString()

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

kotlin {
    jvmToolchain(21)
}

jacoco {
    toolVersion = "0.8.12"
}

application {
    mainClass.set("com.shiny.inspectionmcp.mcpserver.MainKt")
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

    test {
        useJUnitPlatform()
        jvmArgs("--add-modules", "jdk.httpserver")
        finalizedBy(jacocoTestReport)
    }

    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(true)
        }
    }

    jacocoTestCoverageVerification {
        dependsOn(jacocoTestReport)
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = 0.85.toBigDecimal()
                }
            }
        }
    }
}

val mcpServerJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds a standalone MCP server jar with dependencies."
    archiveFileName.set("jetbrains-inspection-mcp.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    dependsOn(tasks.named("classes"))

    manifest {
        attributes[
            "Main-Class"
        ] = application.mainClass.get()
        attributes[
            "Implementation-Title"
        ] = "jetbrains-inspection-mcp"
        attributes[
            "Implementation-Version"
        ] = project.version.toString()
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}
