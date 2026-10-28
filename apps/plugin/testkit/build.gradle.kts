// The fake server: an in-memory Platform that records what the runtime does
// and raises what a server would. Main sources, so the runtime's tests and a
// project's own script tests can both run a NetherForgeRuntime on it.
// Checked against Paper by the Platform contract suites (the runtime's test
// fixtures, run here in the runtime's tests and against Paper in the
// adapter's integration test).

plugins {
    id("netherforge.kotlin-jvm")
}

dependencies {
    api(project(":plugin:runtime"))
    // A bot's dialog inputs arrive as JSON values (format's bridge types).
    implementation(libs.serialization.json)
}
