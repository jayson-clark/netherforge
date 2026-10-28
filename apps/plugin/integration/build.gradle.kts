import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.time.Duration

/*
 * The integration test: every scenario (src/test) against every Paper adapter,
 * on a real headless server of the adapter's own Minecraft version, driven
 * over the dev bridge like the editor drives it. `integrationTest-<minecraft>`
 * runs one adapter's, `integrationTest` (`pnpm test:integration`) them all.
 * Not part of `check`: it needs the network the first time (Paper, and the
 * Mojang server jar Paper patches) and takes minutes.
 *
 * The contract source set is a plugin of its own (`NetherForgeContract`) that
 * runs the Platform contract suites (the runtime's test fixtures) inside the
 * server against the adapter's PaperPlatform; `ContractScenario` boots it.
 */

plugins {
    id("netherforge.kotlin-jvm")
    id("com.gradleup.shadow")
}

val repoRoot: File by rootProject.extra

val contract: SourceSet by sourceSets.creating

dependencies {
    // On the server these come from the NetherForge plugin's own jar (the contract plugin joins its classpath).
    "contractCompileOnly"(project(":plugin:paper-common"))
    "contractCompileOnly"(libs.paper.api)
    "contractCompileOnly"(libs.serialization.json)
    "contractImplementation"(testFixtures(project(":plugin:runtime")))
    "contractImplementation"(platform(libs.junit.bom))
    "contractImplementation"(libs.junit.launcher)

    testImplementation(project(":format"))
    // Structure files read on the test's side, as the fake reads a project's (StructureFiles).
    testImplementation(project(":plugin:testkit"))
    // @Quarantined, the one quarantine annotation every plugin build shares.
    testImplementation(testFixtures(project(":plugin:runtime")))
    testImplementation(libs.serialization.json)
    // What the plugin keeps is read straight from its store.
    testImplementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

/** Every Paper adapter, by its Minecraft version: `apps/plugin/paper-<minecraft>`. */
val adapters: Map<String, Project> = project(":plugin").subprojects
    .filter { Regex("""paper-\d+(\.\d+){1,2}""").matches(it.name) }
    .associateBy { it.name.removePrefix("paper-") }

/** Minecraft versions in release order: `1.21.11` < `26.1.2` < `26.2` < `26.10`. */
val versionOrder = compareBy<String>({ it.split('.')[0].toInt() }, { it.split('.')[1].toInt() }, {
    it.split('.').getOrNull(2)?.toInt()
        ?: 0
})

/** The oldest supported version: what the contract plugin declares, so every server loads it. */
val oldest = adapters.keys.minWith(versionOrder)

/** The newest supported version: the one a pull request runs every scenario on. */
val newest = adapters.keys.maxWith(versionOrder)

/*
 * Which scenarios run (`-Pnetherforge.integration.scope=<scope>`), by their JUnit tags (`VersionIndependent` in src/test/.../support/, and the runtime test fixtures' `Quarantined`):
 * - `full` (the default): every scenario on every version, quarantined ones left out. Nightly, `main` and releases.
 * - `pr`: on the newest version the same; on the others the `version-independent` scenarios are left out too, since
 *   what they check doesn't change with the version (the contract suites hold every adapter to the calls they make).
 * - `quarantine`: only the quarantined (`quarantine`) tests, which nightly runs on their own without failing.
 */
val scope: String = providers.gradleProperty("netherforge.integration.scope").getOrElse("full")
require(scope in setOf("full", "pr", "quarantine")) {
    "netherforge.integration.scope is full, pr or quarantine, not \"$scope\""
}

tasks.named<Copy>("processContractResources") {
    // Locals, so the action holds strings rather than this script (which the configuration cache can't store).
    val version = project.version.toString()
    val minecraft = oldest
    inputs.property("version", version)
    inputs.property("minecraft", minecraft)
    filesMatching("paper-plugin.yml") { expand("version" to version, "minecraft" to minecraft) }
}

/**
 * `NetherForgeContract.jar`: the suites, JUnit and kotlin.test. Everything
 * else they use (the runtime, format, Kotlin) is the NetherForge plugin's,
 * whose classes it loads with, so it goes without them.
 */
val contractJar = tasks.register<ShadowJar>("contractJar") {
    description = "Builds the plugin that runs the Platform contract suites inside a Paper server"
    archiveBaseName.set("NetherForgeContract")
    archiveClassifier.set("")
    from(contract.output)
    // The suites (the runtime's test fixtures) and what runs them: picked by file, since the fixtures and the
    // runtime the plugin jar already has are one project's.
    val bundled = Regex("""(runtime-.*-test-fixtures|junit-.*|opentest4j-.*|apiguardian-.*|kotlin-test.*)\.jar""")
    val runtimeClasspath = project.configurations["contractRuntimeClasspath"]
    inputs.files(runtimeClasspath)
    configurations.set(emptyList())
    from(provider { runtimeClasspath.filter { bundled.matches(it.name) }.map(::zipTree) }) {
        exclude("META-INF/versions/**", "module-info.class")
    }
    // JUnit finds its engines, and kotlin.test its asserter, through service files.
    mergeServiceFiles()
}

// The scenarios run per adapter, below; plain `test` would run them against none.
tasks.named<Test>("test") { enabled = false }

/*
 * The versions run in parallel: with the configuration cache on (tools/gradle/gradle.properties), Gradle runs
 * one project's tasks at once, and nothing is shared between versions: each has its own server folder (its
 * Paperclip downloads, world and logs), its own reports, its own temp copies of the example, and free ports
 * picked per server. A version is one test JVM (1 GB) driving one Paper server at a time (2 GB, in
 * PaperServer), about 3.5 GB with what the JVMs take beside their heaps. So as many run at once as there are
 * 6 GB of memory, leaving the rest to Gradle and the machine (all four on 24 GB; one on a 7 GB CI runner);
 * `-Pnetherforge.integration.parallel=<n>` says otherwise.
 */
val parallel: Int = providers.gradleProperty("netherforge.integration.parallel").map(String::toInt).getOrElse(
    run {
        val memory = (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean)
            .totalMemorySize
        (memory / (6L shl 30)).toInt()
    }
).coerceIn(1, adapters.size)
val paperServers = gradle.sharedServices.registerIfAbsent("paperServers", PaperServerSlots::class) {
    maxParallelUsages.set(parallel)
}

val integrationTests = adapters.map { (minecraft, adapter) ->
    fun artifact(name: String) = configurations.create("$name-$minecraft") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }.also { dependencies.add(it.name, dependencies.project(mapOf("path" to adapter.path, "configuration" to name))) }

    val pluginJar = artifact("pluginJar")
    val paperServer = artifact("paperServer")

    tasks.register<Test>("integrationTest-$minecraft") {
        description = "Runs every scenario on a headless Paper $minecraft with its adapter, driven over the dev bridge"
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform {
            if (scope == "quarantine") {
                includeTags("quarantine")
            } else {
                excludeTags("quarantine")
                if (scope == "pr" && minecraft != newest) excludeTags("version-independent")
            }
        }
        // Nothing may be quarantined: that's a run with nothing to do, not a failure.
        failOnNoDiscoveredTests.set(scope != "quarantine")
        usesService(paperServers)
        // The scenarios only drive the server; reading the game data export is the most they hold.
        maxHeapSize = "1g"
        outputs.upToDateWhen { false }
        timeout.set(Duration.ofMinutes(30))
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
        // One report per version, so a run of every adapter keeps each.
        reports.html.outputLocation.set(layout.buildDirectory.dir("reports/integration/$minecraft"))
        reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/integration/$minecraft"))

        // What's built or downloaded: paths known once those tasks have run.
        val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
        jvmArgumentProviders.add(SystemPropertyPath("netherforge.it.java", files(launcher.map { it.executablePath })))
        jvmArgumentProviders.add(SystemPropertyPath("netherforge.it.plugin", pluginJar))
        jvmArgumentProviders.add(SystemPropertyPath("netherforge.it.paper", paperServer))
        jvmArgumentProviders.add(SystemPropertyPath("netherforge.it.contract", files(contractJar.flatMap { it.archiveFile })))
        systemProperty("netherforge.it.minecraft", minecraft)
        systemProperty("netherforge.it.example", repoRoot.resolve("examples/showcase").absolutePath)
        systemProperty("netherforge.it.fixtures", layout.projectDirectory.dir("src/test/fixtures").asFile.absolutePath)
        // Paperclip's downloads and the Mojang jar are kept here between runs; the world is not.
        systemProperty("netherforge.it.server", layout.buildDirectory.dir("integration/$minecraft/server").get().asFile.absolutePath)
    }
}

tasks.register("integrationTest") {
    description = "Runs every scenario against every Paper adapter, the versions in parallel"
    group = "verification"
    dependsOn(integrationTests)
}
