plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"   // provisions JDK 26 when it is missing
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "ai-gate"
