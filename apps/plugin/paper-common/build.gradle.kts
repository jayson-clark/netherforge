// The Paper adapter, all but what differs between Minecraft versions: the
// runtime's Platform on Paper's API, compiled against the oldest supported
// version's API so every adapter (apps/plugin/paper-<minecraft>) can carry it.
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
    api(project(":plugin:runtime"))
    implementation(libs.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    // The loader reads config.yml with Bukkit's YAML, which the server has.
    testImplementation(libs.paper.api)
    // What the loader finds on a server that bundles the driver, as every supported Paper does.
    testImplementation(libs.sqlite.jdbc)
    // ... and the pool and driver the loader would otherwise fetch for remote databases.
    testImplementation(libs.hikari)
    testImplementation(libs.postgresql)
}

tasks.test { useJUnitPlatform() }

// The coordinates of what NetherForgeLoader fetches: SQLite's driver on a server that doesn't bundle it, remote databases' pool and driver.
tasks.processResources {
    val versions = mapOf(
        "sqliteJdbc" to libs.versions.sqlite.jdbc.get(),
        "hikari" to libs.versions.hikari.get(),
        "postgresql" to libs.versions.postgresql.get()
    )
    inputs.property("versions", versions)
    filesMatching("netherforge-libraries.properties") { expand(versions) }
}
