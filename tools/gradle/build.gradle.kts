plugins {
    // build-logic carries the Kotlin, shadow, paperweight and run-paper plugins at the
    // catalog's versions: loading it here puts them on every project's classpath once,
    // so projects apply them by id, without a version.
    id("netherforge.kotlin-jvm") apply false
    alias(libs.plugins.ktlint)
}

/** The repository root. This build's root is tools/gradle, so never resolve repo files against rootProject. */
val repoRoot: File = rootDir.resolve("../..").canonicalFile

/*
 * Dependency locking and verification: every configuration's resolved versions are
 * pinned in each project's gradle.lockfile (the plugins in buildscript-gradle.lockfile),
 * and gradle/verification-metadata.xml holds every artifact's SHA-256. Both are
 * rewritten by `node tools/gradle-lock.mjs` (see the testing skill).
 */
buildscript {
    configurations.classpath { resolutionStrategy.activateDependencyLocking() }
}

allprojects {
    extra["repoRoot"] = repoRoot
    group = "dev.netherforge"
    version = repoRoot.resolve("VERSION").readText().trim()

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter { exclude { it.file.path.contains("${File.separator}build${File.separator}") } }
    }

    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }

    /** Resolves every configuration, so `--write-locks` records them all. tools/gradle-lock.mjs runs it. */
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("Resolves configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "$path must be run with --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}

/*
 * Kotlin/JS downloads Node for the machine it runs on, and only when it isn't installed
 * yet, so a lock run on one machine would never record the checksums the others need.
 * Resolve the distribution for every OS we build on so verification-metadata.xml has them.
 */
tasks.named("resolveAndLockAll") {
    dependsOn(gradle.includedBuild("build-logic").task(":resolveAndLockAll"))
    doLast {
        val node = project.the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().version.get()
        val distributions = listOf(
            "darwin-arm64@tar.gz",
            "darwin-x64@tar.gz",
            "linux-x64@tar.gz",
            "linux-arm64@tar.gz",
            "win-x64@zip",
            "win-arm64@zip"
        )
        configurations
            .detachedConfiguration(*distributions.map { dependencies.create("org.nodejs:node:$node:$it") }.toTypedArray())
            .resolve()
    }
}
