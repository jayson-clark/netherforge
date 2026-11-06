/*
 * The FFM glue's Kotlin half (see the rust-runtime skill, "The FFM boundary"): the Paper adapter
 * calls the Rust runtime (crates/ffi's cdylib) through Java's foreign function API. Generated from
 * packages/platform and packages/api by `pnpm generate` (src/generated), on the wire, the object
 * registry and the loader written here. Java 25: the foreign function API isn't in Java 21, so
 * only the 26.x adapters can carry it.
 */

plugins {
    id("netherforge.kotlin-jvm")
}

kotlin.compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    freeCompilerArgs.set(freeCompilerArgs.get().filterNot { it.startsWith("-Xjdk-release=") } + "-Xjdk-release=25")
}
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

dependencies {
    // The Platform's Kotlin interfaces and the server's events, and format's types they carry.
    api(project(":plugin:runtime"))
    implementation(libs.serialization.json)

    testImplementation(project(":plugin:testkit"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

sourceSets { main { kotlin.srcDir("src/generated/kotlin") } }

ktlint {
    filter { exclude { it.file.path.contains("${File.separator}src${File.separator}generated${File.separator}") } }
}

val repoRoot: File by rootProject.extra

/** The Rust runtime's library, built by cargo for the tests (debug: the release build is the plugin jar's). */
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    description = "Builds crates/ffi's library for the tests"
    workingDir = repoRoot
    commandLine("cargo", "build", "-p", "netherforge-ffi")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(cargoBuild)
    // The library's file, by the OS's naming.
    val os = System.getProperty("os.name").lowercase()
    val file = when {
        os.contains("mac") -> "libnetherforge_ffi.dylib"
        os.contains("win") -> "netherforge_ffi.dll"
        else -> "libnetherforge_ffi.so"
    }
    systemProperty("netherforge.ffi.library", repoRoot.resolve("target/debug/$file").absolutePath)
    environment("NETHERFORGE_REPO", repoRoot.absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
