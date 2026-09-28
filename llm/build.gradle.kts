import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)          // Kotlin consumers are verified by src/test/kotlin; the library itself is Java
}

group = "net.ai.gate"                                   // version: gradle.properties; artifact id: rootProject.name (ai-gate)
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

publishing {                                             // ./gradlew publishToMavenLocal for joint development
    publications.create<MavenPublication>("ai-gate") {
        from(components["java"])                          // the jar with sources and javadoc; 0.x: experimental APIs may change in minor versions
        pom {
            name = "AI Gate"
            description = project.description
            licenses { license { name = "GPL-3.0-only"; url = "https://www.gnu.org/licenses/gpl-3.0.txt" } }
        }
    }
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

tasks.register<Test>("updateModelCatalog") {                // network: regenerates the bundled catalog from models.dev
    description = "Regenerates src/main/resources/net/ai/gate/catalog/models.json from https://models.dev/api.json"
    group = "build"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("net.ai.gate.catalog.ModelsDevFeedTest.regenerateBundledCatalog") }
    systemProperty("ai-gate.updateCatalog", file("src/main/resources/net/ai/gate/catalog/models.json").absolutePath)
    outputs.upToDateWhen { false }
}

tasks.register<Test>("liveTest") {                          // network, billable: smoke tests against the real endpoints
    description = "Runs LiveSmokeTest against real providers; keys come from the environment (see the test's Javadoc)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("net.ai.gate.LiveSmokeTest") }
    systemProperty("ai-gate.live", "true")
    outputs.upToDateWhen { false }
}
