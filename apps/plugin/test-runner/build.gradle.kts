import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// `netherforge test`'s JVM half: loads a project the way a server would, on the testkit's fake
// platform, runs its `*_test.lua` files and reports each test, as text, JSON lines or JUnit XML.
// One runnable jar, `NetherForgeTest-<version>.jar`, which the editor bundles and the netherforge
// command (apps/cli) launches with the Java the editor already provides.

plugins {
    id("netherforge.kotlin-jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":plugin:testkit"))
    implementation(libs.serialization.json)
    // The runtime's store is SQLite: a server brings its driver, so the jar carries one.
    runtimeOnly(libs.sqlite.jdbc)
    // The cron library's SLF4J has no provider here; this one keeps it quiet.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    // @Quarantined, shared with the runtime's tests.
    testImplementation(testFixtures(project(":plugin:runtime")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val repoRoot: File by rootProject.extra

tasks.withType<Test>().configureEach {
    // RunnerJarTest runs the built jar the way `netherforge test` does.
    val built = files(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
    jvmArgumentProviders.add(SystemPropertyPath("netherforge.runner.jar", built))
    // luajava loads Lua's native library; Java 25 warns unless that's allowed explicitly.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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

/** `NetherForgeTest-<version>.jar`: the runner with the runtime, the fake platform, format and Kotlin inside. */
val runnerJar = tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("NetherForgeTest")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to "dev.netherforge.plugin.testrunner.MainKt",
            // luajava loads Lua's native library; this is `--enable-native-access` for a jar that is run with `-jar`.
            "Enable-Native-Access" to "ALL-UNNAMED",
            "Implementation-Version" to project.version.toString()
        )
    }
}

tasks.named("assemble") { dependsOn(runnerJar) }
