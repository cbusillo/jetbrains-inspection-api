import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.6.0"
    kotlin("plugin.serialization") version "2.1.21"
    id("jacoco")
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
    
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.assertj:assertj-core:3.24.2")
    
    intellijPlatform {
        intellijIdeaCommunity("2025.1.1")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
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
    
    buildPlugin {
        archiveFileName.set("jetbrains-inspection-api-${project.property("pluginVersion")}.zip")
    }
    
    test {
        useJUnitPlatform()
        systemProperty("java.awt.headless", "true")
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
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
        violationRules {
            rule {
                limit {
                    minimum = 0.80.toBigDecimal()
                }
            }
        }
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