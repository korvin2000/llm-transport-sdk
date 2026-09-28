import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
}

group = "net.ai.gate"                                   // version: ../gradle.properties; artifact id: ai-gate-kotlin
description = "AI Gate for Kotlin: coroutine and Flow adapters over the ai-gate runtime"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(26) }
    withSourcesJar()
}

kotlin {
    jvmToolchain(26)
    compilerOptions { jvmTarget = JvmTarget.JVM_26 }
}

dependencies {
    api(project(":"))                                    // the core artifact, net.ai.gate:ai-gate
    api(libs.kotlinx.coroutines.core)                    // the only addition: suspend functions and Flow
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "net.ai.gate.kotlin") }
}

publishing {                                             // ./gradlew publishToMavenLocal publishes it next to ai-gate
    publications.create<MavenPublication>("ai-gate-kotlin") {
        from(components["java"])
        pom {
            name = "AI Gate for Kotlin"
            description = project.description
            licenses { license { name = "GPL-3.0-only"; url = "https://www.gnu.org/licenses/gpl-3.0.txt" } }
        }
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {   // reproducible artifacts
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    useJUnitPlatform()
}
