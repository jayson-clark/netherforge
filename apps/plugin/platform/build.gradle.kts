/*
 * The Platform: what the NetherForge runtime (Rust, crates/runtime) asks of a server, as the Paper adapter implements
 * it in Kotlin, and what the server tells it. These interfaces are the Platform spec's source (`pnpm --filter
 * @netherforge/platform extract` writes packages/platform from them); the FFM glue (:plugin:ffi) is generated from
 * the spec. Its test fixtures are the Platform contract suites: run against the testkit's fake here, and against Paper
 * by each adapter's integration test.
 */

plugins {
    id("netherforge.kotlin-jvm")
    kotlin("plugin.serialization")
    `java-test-fixtures`
}

dependencies {
    api(project(":format"))
    implementation(libs.serialization.json)

    testFixturesApi(kotlin("test-junit5"))
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    // A bot's dialog inputs are JSON values (format's bridge types).
    testFixturesImplementation(libs.serialization.json)
    // ContractRun reports a run of the suites (the contract plugin's) as a launcher listener.
    testFixturesApi(libs.junit.launcher)
    // Test modules (WasmFixtures) are written as WebAssembly text and built by wabt's wat2wasm, itself WebAssembly on Chicory.
    testFixturesImplementation(libs.chicory.wabt)

    testImplementation(testFixtures(project(":plugin:platform")))
    testImplementation(project(":plugin:testkit"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val repoRoot: File by rootProject.extra

// The contract suites against the testkit's fake (src/test): its start-up datapack is the Rust runtime's, so the tests
// load the library cargo builds.
tasks.test {
    useJUnitPlatform { excludeTags("quarantine") }
    val os = System.getProperty("os.name").lowercase()
    val file = when {
        os.contains("mac") -> "libnetherforge_ffi.dylib"
        os.contains("win") -> "netherforge_ffi.dll"
        else -> "libnetherforge_ffi.so"
    }
    dependsOn(":plugin:ffi:cargoBuild")
    systemProperty("netherforge.ffi.library", repoRoot.resolve("target/debug/$file").absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The server's events (GameEvents) and the commands' argument types, generated from packages/api by `pnpm generate`
// and committed, so this build never needs Node.
sourceSets { main { kotlin.srcDir("src/generated/kotlin") } }

ktlint {
    filter { exclude { it.file.path.contains("${File.separator}src${File.separator}generated${File.separator}") } }
}
