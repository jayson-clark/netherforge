/*
 * The FFM glue's Kotlin half (see the plugin-runtime skill, "The FFM boundary"): the Paper adapter
 * calls the Rust runtime (crates/ffi's cdylib) through Java's foreign function API. Generated from
 * packages/platform and packages/api by `pnpm generate` (src/generated), on the wire, the object
 * registry and the loader written here. Java 25, for the foreign function API.
 */

plugins {
    id("netherforge.kotlin-jvm")
}

dependencies {
    // The Platform's Kotlin interfaces and the server's events, and format's types they carry.
    api(project(":plugin:platform"))
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

/*
 * The library in the jar, at `META-INF/netherforge/native/<os>-<arch>/`: this machine's, a release build, unless the
 * build says otherwise (`-Pnetherforge.native=none`: a server then names one, -Dnetherforge.ffi.library). A release
 * jar carries every OS's: the release workflow builds each and puts them in (`-Pnetherforge.native=prebuilt`, from
 * `-Pnetherforge.native.dir=<dir>/<os>-<arch>/<file>`).
 */
val native = providers.gradleProperty("netherforge.native").getOrElse("host")
if (native == "prebuilt") {
    val dir = providers.gradleProperty("netherforge.native.dir").map { File(it) }
    tasks.named<Copy>("processResources") {
        from(dir) { into("META-INF/netherforge/native") }
    }
}
if (native == "host") {
    val mac = System.getProperty("os.name").lowercase().contains("mac")
    val cargoRelease = tasks.register<Exec>("cargoRelease") {
        description = "Builds crates/ffi's library, release, for the jar"
        workingDir = repoRoot
        commandLine("cargo", "build", "--release", "-p", "netherforge-ffi")
        // macOS's `strip` (as of ld-27106) leaves a dylib that won't load ("mis-aligned LINKEDIT string pool"), so
        // cargo doesn't strip there; `strip -x` after does, safely (58 MB to 43 MB). Elsewhere cargo strips its symbols.
        environment("CARGO_PROFILE_RELEASE_STRIP", if (mac) "none" else "symbols")
    }
    val stripRelease = tasks.register<Exec>("stripRelease") {
        description = "Strips the library's local symbols on macOS, where cargo's own strip breaks it"
        dependsOn(cargoRelease)
        onlyIf { mac }
        commandLine("strip", "-x", repoRoot.resolve("target/release/libnetherforge_ffi.dylib"))
    }
    val os = System.getProperty("os.name").lowercase()
    val (folder, file) = when {
        os.contains("mac") -> "macos" to "libnetherforge_ffi.dylib"
        os.contains("win") -> "windows" to "netherforge_ffi.dll"
        else -> "linux" to "libnetherforge_ffi.so"
    }
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> a
    }
    tasks.named<Copy>("processResources") {
        dependsOn(stripRelease)
        from(repoRoot.resolve("target/release/$file")) { into("META-INF/netherforge/native/$folder-$arch") }
    }
}

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
