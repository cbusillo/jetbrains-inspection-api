import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.1.0"
    kotlin("plugin.serialization") version "1.9.24"
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
        create("IC", "2024.3.1.1")
        instrumentationTools()
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
        kotlinOptions.jvmTarget = "17"
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
        name = "JetBrains Inspection API"
        description = "Exposes JetBrains IDE inspection results via HTTP API for automated tools and AI assistants"
        version = project.property("pluginVersion").toString()
        
        vendor {
            name = "Shiny Computers"
            email = "info@shinycomputers.com"
            url = "https://github.com/cbusillo/jetbrains-inspection-api"
        }
        
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "253.*"
        }
    }
    
    pluginVerification {
        ides {
            recommended()
        }
    }
}