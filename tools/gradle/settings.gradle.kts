pluginManagement {
    // The convention plugins every Kotlin project and Paper adapter is built with.
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Provisions the Java 25 toolchain on machines (and CI runners) that don't have it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // PREFER_SETTINGS rather than FAIL_ON_PROJECT_REPOS: Kotlin/JS registers its own
    // repository for the Node distribution it runs tests with.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        // The APIs of the plugins nf.economy and nf.placeholders wrap, each from the one repository that has it.
        maven("https://jitpack.io") { content { includeGroup("com.github.MilkBowl") } }
        maven("https://repo.extendedclip.com/releases/") { content { includeGroup("me.clip") } }
        ivy("https://nodejs.org/dist/") {
            name = "Node Distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
    }
}

rootProject.name = "netherforge"

include(":format")
include(":network")
include(":plugin:runtime")
include(":plugin:testkit")
include(":plugin:test-runner")
include(":plugin:paper-common")
include(":plugin:integration")
include(":proxy:velocity")

// This folder is only the build's root, kept out of the repo root along with
// Gradle's caches and outputs. The projects live with the rest of their kind:
// packages/ for libraries, apps/ for what we ship.
val repoRoot = rootDir.resolve("../..")
project(":format").projectDir = repoRoot.resolve("packages/format")
project(":network").projectDir = repoRoot.resolve("packages/network")
project(":plugin").projectDir = repoRoot.resolve("apps/plugin")
project(":plugin:runtime").projectDir = repoRoot.resolve("apps/plugin/runtime")
project(":plugin:testkit").projectDir = repoRoot.resolve("apps/plugin/testkit")
project(":plugin:test-runner").projectDir = repoRoot.resolve("apps/plugin/test-runner")
project(":plugin:paper-common").projectDir = repoRoot.resolve("apps/plugin/paper-common")
project(":plugin:integration").projectDir = repoRoot.resolve("apps/plugin/integration")
project(":proxy").projectDir = repoRoot.resolve("apps/proxy")
project(":proxy:velocity").projectDir = repoRoot.resolve("apps/proxy/velocity")

// One Paper adapter per folder, `apps/plugin/paper-<minecraft>`: adding a
// version is adding a folder (see the minecraft-versions skill).
val adapterFolder = Regex("""paper-\d+(\.\d+){1,2}""")
repoRoot.resolve("apps/plugin").listFiles { file -> file.isDirectory && adapterFolder.matches(file.name) }
    .orEmpty()
    .sortedBy { it.name }
    .forEach { folder ->
        include(":plugin:${folder.name}")
        project(":plugin:${folder.name}").projectDir = folder
    }
