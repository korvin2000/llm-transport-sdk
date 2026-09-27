import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)          // Kotlin consumers are verified by src/test/kotlin; the library itself is Java
}

group = "net.ai.gate"
version = "0.1.0-SNAPSHOT"
description = "AI Gate: one portable Java API over LLM providers and gateways"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(26) }
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain(26)
    compilerOptions { jvmTarget = JvmTarget.JVM_26 }
}

dependencies {
    compileOnlyApi(libs.jspecify)                        // nullness on the API: visible to consumers' compilers, not at runtime
    compileOnlyApi(libs.jetbrains.annotations)           // @ApiStatus markers
    testImplementation(kotlin("stdlib"))                  // Kotlin is test-only: the main runtime stays JDK-only
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 26
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {                // /// Markdown comments (JEP 467) render natively
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

tasks.withType<AbstractArchiveTask>().configureEach {   // reproducible artifacts
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)                                      // ModuleBoundaryTest compiles and runs an external consumer against the jar
    systemProperty("ai-gate.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("ai-gate.compileClasspath", sourceSets.main.get().compileClasspath.asPath)   // the annotation modules a consumer needs
}
