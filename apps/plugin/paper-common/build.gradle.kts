// The Paper adapter, all but what differs between Minecraft versions: the
// runtime's Platform on Paper's API, compiled against the oldest supported
// version's API so every adapter (apps/plugin/paper-<minecraft>) can carry it,
// and the Rust runtime it runs (:plugin:ffi).
// What a version does differently, and what reaches into the server itself, is
// that version's `PaperVersion` (see the minecraft-versions skill).

plugins {
    id("netherforge.kotlin-jvm")
}

dependencies {
    compileOnly(libs.paper.api)
    // Soft dependencies the server may or may not have (nf.economy, nf.placeholders); only PaperInterop names them.
    compileOnly(libs.vault.api)
    compileOnly(libs.placeholderapi)
    api(project(":plugin:platform"))
    api(project(":plugin:ffi"))
    implementation(libs.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.paper.api)
}

tasks.test { useJUnitPlatform() }
