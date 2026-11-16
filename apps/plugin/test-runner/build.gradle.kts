import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// `netherforge test`'s JVM half: the Rust runtime's test runner on the testkit's fake platform, a
// fresh one per test, running the project's `*_test.lua` files and reporting each test, as text,
// JSON lines or JUnit XML.
// One runnable jar, `NetherForgeTest-<version>.jar`, which the editor bundles and the netherforge
// command (apps/cli) launches with the Java the editor already provides.

plugins {
    id("netherforge.kotlin-jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
}

dependencies {
    // The fake server, and through it the Rust runtime (:plugin:ffi), its library in the jar.
    implementation(project(":plugin:testkit"))
    implementation(libs.serialization.json)

    testImplementation(kotlin("test"))
    // @Quarantined, shared with the runtime's tests.
    testImplementation(testFixtures(project(":plugin:platform")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val repoRoot: File by rootProject.extra

tasks.withType<Test>().configureEach {
    // RunnerJarTest runs the built jar the way `netherforge test` does.
    val built = files(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
    jvmArgumentProviders.add(SystemPropertyPath("netherforge.runner.jar", built))
    // The Rust runtime is called through the foreign function API; Java 25 warns unless that's allowed explicitly.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The fake server's start-up datapack is built by the runtime too: the library the tests load.
    val os = System.getProperty("os.name").lowercase()
    val file = when {
        os.contains("mac") -> "libnetherforge_ffi.dylib"
        os.contains("win") -> "netherforge_ffi.dll"
        else -> "libnetherforge_ffi.so"
    }
    dependsOn(":plugin:ffi:cargoBuild")
    systemProperty("netherforge.ffi.library", repoRoot.resolve("target/debug/$file").absolutePath)
    // The tests run examples/showcase's own tests.
    environment("NETHERFORGE_REPO", repoRoot.absolutePath)
    inputs.dir(repoRoot.resolve("examples")).withPathSensitivity(PathSensitivity.RELATIVE)
}

// A flaky test is quarantined (`@Quarantined(issue)`, JUnit's tag "quarantine") until it's fixed: out of `test`, run
// on its own by `quarantinedTest`.
tasks.test {
    useJUnitPlatform { excludeTags("quarantine") }
}

tasks.register<Test>("quarantinedTest") {
    description = "Runs only the quarantined tests (@Quarantined), which test leaves out"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("quarantine") }
    filter.isFailOnNoMatchingTests = false
}

tasks.named<Jar>("jar") {
    // Only the shadow jar runs; keep the thin one from looking like it.
    archiveClassifier.set("thin")
}

/** `NetherForgeTest-<version>.jar`: the runner with the Rust runtime's library, the fake platform, format and Kotlin inside. */
val runnerJar = tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("NetherForgeTest")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to "dev.netherforge.plugin.testrunner.MainKt",
            // The Rust runtime's library is called through the foreign function API: `--enable-native-access` for `-jar`.
            "Enable-Native-Access" to "ALL-UNNAMED",
            "Implementation-Version" to project.version.toString()
        )
    }
}

tasks.named("assemble") { dependsOn(runnerJar) }
