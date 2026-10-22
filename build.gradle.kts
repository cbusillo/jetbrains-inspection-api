import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.api.provider.Provider
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.time.Instant
import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    kotlin("plugin.serialization") version "2.4.10"
    id("jacoco")
}

// Configure JaCoCo for IntelliJ Platform plugins
jacoco {
    toolVersion = "0.8.12"
}

group = "com.jetbrains.inspection"
version = project.property("pluginVersion").toString()

// One codebase, two archives: "full" carries every proof feature; "stable" leaves out
// internal IntelliJ APIs that Marketplace has not exempted for this plugin.
val inspectionBuildEditions = setOf("full", "stable")
val inspectionBuild: String = providers.gradleProperty("inspectionBuild").getOrElse("full")
if (inspectionBuild !in inspectionBuildEditions) {
    throw GradleException("inspectionBuild must be one of ${inspectionBuildEditions.sorted()}, got '$inspectionBuild'.")
}
val internalApiAllowlist = layout.projectDirectory.file("config/plugin-verifier/$inspectionBuild-internal-api-allowlist.txt")

abstract class GenerateInspectionBuildInfoTask : DefaultTask() {
    @get:Internal
    abstract val gitDirectory: DirectoryProperty

    @get:Input
    abstract val pluginVersion: Property<String>

    @get:Input
    abstract val buildEdition: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }

    @TaskAction
    fun generate() {
        val commit = gitHeadCommit() ?: "unknown"
        val shortCommit = commit.takeIf { it != "unknown" }?.take(12) ?: "unknown"
        val dirty = when {
            commit == "unknown" -> "unknown"
            gitDiffIndexExitCode() == 0 && !hasUntrackedFiles() -> "false"
            else -> "true"
        }
        val state = when (dirty) {
            "true" -> "dirty"
            "false" -> "clean"
            else -> "unknown"
        }

        val properties = Properties().apply {
            setProperty("plugin.version", pluginVersion.get())
            setProperty("plugin.build.edition", buildEdition.get())
            setProperty("plugin.build.commit", commit)
            setProperty("plugin.build.short_commit", shortCommit)
            setProperty("plugin.build.dirty", dirty)
            setProperty("plugin.build.time", Instant.now().toString())
            setProperty("plugin.build.fingerprint", "$commit-$state")
        }

        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.outputStream().use { stream -> properties.store(stream, null) }
    }

    private fun gitHeadCommit(): String? {
        return runCatching {
            val process = gitProcess("rev-parse", "--verify", "HEAD")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            output.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
        }.getOrNull()
    }

    private fun gitDiffIndexExitCode(): Int? {
        return runCatching {
            gitProcess("diff-index", "--quiet", "HEAD", "--")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor()
        }.getOrNull()
    }

    private fun hasUntrackedFiles(): Boolean {
        return runCatching {
            val process = gitProcess("ls-files", "--others", "--exclude-standard")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor() == 0 && output.isNotEmpty()
        }.getOrDefault(true)
    }

    private fun gitProcess(vararg args: String): ProcessBuilder {
        return ProcessBuilder("git", *args)
            .directory(gitDirectory.get().asFile)
            .apply {
                environment().remove("GIT_DIR")
                environment().remove("GIT_WORK_TREE")
                environment().remove("GIT_INDEX_FILE")
                environment().remove("GIT_PREFIX")
            }
    }
}

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/resources/inspectionBuildInfo")
val generateInspectionBuildInfo = tasks.register<GenerateInspectionBuildInfoTask>("generateInspectionBuildInfo") {
    description = "Records the plugin version and source provenance in the packaged resources."
    gitDirectory.set(layout.projectDirectory)
    pluginVersion.set(project.property("pluginVersion").toString())
    buildEdition.set(inspectionBuild)
    outputFile.set(generatedBuildInfoDir.map {
        it.file("com/shiny/inspectionmcp/inspection-build.properties")
    })
}

sourceSets {
    main {
        resources.srcDir(generatedBuildInfoDir)
    }
}

repositories {
    mavenCentral()
    
    intellijPlatform {
        defaultRepositories()
    }
}

val targetIdeVersion = "2025.1.1"

dependencies {
    implementation(project(":inspection-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testRuntimeOnly("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.24.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    testImplementation("io.mockk:mockk:1.14.11") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    testImplementation("org.assertj:assertj-core:3.27.7")
    
    intellijPlatform {
        intellijIdea(targetIdeVersion)
        bundledPlugin("JavaScript")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets["main"].kotlin.srcDir("src/$inspectionBuild/kotlin")
    if (inspectionBuild == "stable") {
        sourceSets["test"].kotlin.srcDir("src/testStable/kotlin")
    }
}

val platformTest by intellijPlatformTesting.testIde.registering {
    task {
        useJUnitPlatform()
        filter { includeTestsMatching("*PlatformTest") }
        systemProperty("java.awt.headless", "true")
        systemProperty("idea.is.unit.test", "true")
        systemProperty("idea.test.cyclic.buffer.size", "1048576")
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

val pythonSdkTestIdePath: Provider<String> = providers.gradleProperty("pythonSdkTestIdePath")
if (pythonSdkTestIdePath.isPresent) {
    intellijPlatformTesting.testIde.register("pythonUvSdkTest") {
        localPath.set(file(pythonSdkTestIdePath.get()))
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
        task {
            extensions.configure<JacocoTaskExtension> { isEnabled = false }
            useJUnitPlatform()
            filter { includeTestsMatching("*PythonUvSdkRecoveryTest") }
            systemProperty("java.awt.headless", "true")
            systemProperty("idea.is.unit.test", "true")
            systemProperty("jia.control.root", layout.buildDirectory.dir("python-uv-controls").get().asFile.absolutePath)
            testLogging {
                events("passed", "failed")
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }
    tasks.named("jacocoTestReport") { mustRunAfter("pythonUvSdkTest") }
}

tasks {
    processResources {
        dependsOn(generateInspectionBuildInfo)
    }

    // Tests create hundreds of temp directories per run. Give each checkout and
    // test task its own temp root and empty it before and after each run, so
    // runs do not pile up entries in the shared per-user temp directory.
    withType<Test>().configureEach {
        // Bundled Kotlin indexing reads dependencies from Gradle's cache, which
        // can live outside the test framework's default allowed home directory.
        systemProperty("vfs.additional-allowed-roots", gradle.gradleUserHomeDir.absolutePath)
        val checkoutKey = Integer.toHexString(rootDir.absolutePath.hashCode())
        val testTempDir = File(
            System.getProperty("java.io.tmpdir"),
            "jetbrains-inspection-api-tests-$checkoutKey-$name",
        )
        systemProperty("java.io.tmpdir", testTempDir.absolutePath)
        doFirst {
            testTempDir.deleteRecursively()
            testTempDir.mkdirs()
        }
        doLast {
            testTempDir.deleteRecursively()
        }
    }

    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    
    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    withType<VerifyPluginTask> {
        val verifierPluginVersion = project.property("pluginVersion").toString()
        val externalVerificationArchive = providers.gradleProperty("pluginVerificationArchive")
        if (externalVerificationArchive.isPresent) {
            archiveFile.set(layout.file(externalVerificationArchive.map { file(it) }))
        }
        val allowlistScriptPath = project.layout.projectDirectory
            .file("scripts/verify-internal-api-allowlist.py").asFile.absolutePath
        val allowlistManifestPath = internalApiAllowlist.asFile.absolutePath
        val workingDirectoryPath = project.layout.projectDirectory.asFile.absolutePath
        val reportsDirectory = verificationReportsDirectory
        inputs.file(allowlistScriptPath).withPropertyName("internalApiAllowlistScript")
        inputs.file(allowlistManifestPath).withPropertyName("internalApiAllowlistManifest")
        doFirst {
            // Stale reports from another edition or version must never satisfy this gate.
            reportsDirectory.get().asFile.deleteRecursively()
        }
        doLast {
            val pluginReportDirectories = reportsDirectory.get().asFile.walkTopDown()
                .filter { candidate ->
                    candidate.isDirectory &&
                        candidate.name == verifierPluginVersion &&
                        candidate.parentFile?.name == "com.shiny.inspection.api" &&
                        candidate.parentFile?.parentFile?.name == "plugins"
                }
                .sortedBy { it.absolutePath }
                .toList()
            if (pluginReportDirectories.isEmpty()) {
                throw GradleException("Plugin Verifier produced no report for com.shiny.inspection.api $verifierPluginVersion.")
            }
            val command = mutableListOf(
                "python3",
                allowlistScriptPath,
                "--manifest",
                allowlistManifestPath,
            )
            pluginReportDirectories.forEach { directory ->
                command.add("--report-dir")
                command.add(directory.absolutePath)
            }
            val process = ProcessBuilder(command)
                .directory(File(workingDirectoryPath))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor() != 0) {
                throw GradleException(output.ifEmpty { "Internal API allowlist validation failed." })
            }
            if (output.isNotEmpty()) {
                println(output)
            }
        }
    }
    
    buildPlugin {
        archiveFileName.set("jetbrains-inspection-api-${project.property("pluginVersion")}.zip")
    }

    prepareSandbox {
        dependsOn(":mcp-server-jvm:mcpServerJar")
        from(project(":mcp-server-jvm").layout.buildDirectory.file("libs/jetbrains-inspection-mcp.jar")) {
            into("lib")
        }
    }

    buildPlugin {
        dependsOn(":mcp-server-jvm:mcpServerJar")
        from(project(":mcp-server-jvm").layout.buildDirectory.file("libs/jetbrains-inspection-mcp.jar")) {
            into("lib")
        }
    }
    
    test {
        useJUnitPlatform()
        maxParallelForks = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        filter { excludeTestsMatching("*PlatformTest") }
        filter { excludeTestsMatching("*PythonUvSdkRecoveryTest") }
        dependsOn(platformTest)
        systemProperty("java.awt.headless", "true")
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
        
        // Add system properties to help with coverage in the plugin environment
        systemProperty("idea.is.unit.test", "true")
        systemProperty("idea.test.cyclic.buffer.size", "1048576")
        
        finalizedBy(jacocoTestReport)
    }
    
    jacocoTestReport {
        dependsOn(test)
        
        executionData.setFrom(fileTree(layout.buildDirectory).include("jacoco/*.exec"))
        
        sourceSets(sourceSets.main.get())
        
        classDirectories.setFrom(
            files(classDirectories.files.map {
                fileTree(it) {
                    exclude("**/META-INF/**")
                    exclude($$"**/*$WhenMappings.*")
                    include("com/shiny/inspectionmcp/**")
                }
            })
        )
        
        reports {
            xml.required.set(true)
            html.required.set(true) 
            csv.required.set(true)
        }
    }
    
    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }
    
    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
        val marketplaceChannel = providers.gradleProperty("marketplaceChannel").orNull
        val publishingVersion = project.property("pluginVersion").toString()
        val publicationPolicyScriptPath = project.layout.projectDirectory
            .file("scripts/validate-marketplace-publication.sh").asFile.absolutePath
        val workingDirectoryPath = project.layout.projectDirectory.asFile.absolutePath
        if (marketplaceChannel != null) {
            channels.set(listOf(marketplaceChannel))
        }
        doFirst {
            val command = mutableListOf(
                "bash",
                publicationPolicyScriptPath,
                "--version",
                publishingVersion,
                "--edition",
                inspectionBuild,
            )
            if (marketplaceChannel != null) {
                command.add("--channel")
                command.add(marketplaceChannel)
            }
            val process = ProcessBuilder(command)
                .directory(File(workingDirectoryPath))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor() != 0) {
                throw GradleException(output.removePrefix("ERROR: "))
            }
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Inspection API"
        description = "Exposes JetBrains IDE inspection results via HTTP API for automated tools and AI assistants"
        version = project.property("pluginVersion").toString()
        
        vendor {
            name = "Shiny Computers"
            email = "info@shinycomputers.com"
            url = "https://github.com/cbusillo/jetbrains-inspection-api"
        }
        
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "262.*"
        }
    }
    
    pluginVerification {
        ides {
            // "target" checks only the compile IDE, which is already downloaded; releases check the whole range.
            if (providers.gradleProperty("pluginVerifierIdes").orNull == "target") {
                create(IntelliJPlatformType.IntellijIdea, targetIdeVersion)
            } else {
                recommended()
            }
        }
        failureLevel.set(listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        ))
    }
}
