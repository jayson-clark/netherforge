// A network's hub and how servers talk to it: the protocol both ends speak, and the hub itself (presence, channels,
// requests, shared maps, sending players), with nothing of a proxy or a server in it. The Paper plugin speaks the
// protocol (and runs the hub in-process for script tests); the proxy plugins run the hub with their proxy's facts.
// See the plugin-capabilities skill's "Networks".

plugins {
    id("netherforge.kotlin-jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(libs.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.timeout.default", "1 m")
}
