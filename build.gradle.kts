import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.6.0"
    kotlin("plugin.serialization") version "2.1.21"
}

group = "com.jetbrains.inspection"
version = project.property("pluginVersion").toString()

repositories {
    mavenCentral()
    
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    
    intellijPlatform {
        intellijIdeaCommunity("2025.1.1")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(17)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    
    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
    buildPlugin {
        archiveFileName.set("jetbrains-inspection-api-${project.property("pluginVersion")}.zip")
    }
    
    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }
    
    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
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
            untilBuild = "261.*"
        }
    }
    
    pluginVerification {
        ides {
            recommended()
        }
    }
}