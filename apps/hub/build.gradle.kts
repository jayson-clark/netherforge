import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

/*
 * The network's hub on its own: `java -jar NetherForge-<version>-hub.jar`, with the same hub.properties, credentials
 * and admin API as the proxy plugin's hub, and the proxy joining it as a client. `:network` does the work; this is its
 * process: settings, files watched for changes, the maps' store (a file or PostgreSQL), and a command line for
 * credentials and admin clients. See the plugin-capabilities skill's "Networks".
 */

plugins {
    id("netherforge.kotlin-jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":network"))
    implementation(libs.serialization.json)
    runtimeOnly(libs.postgresql)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }

tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")
}

/** `NetherForge-<version>-hub.jar`. */
val hubJar = tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("NetherForge")
    archiveClassifier.set("hub")
    mergeServiceFiles()
    manifest { attributes("Main-Class" to "dev.netherforge.hub.MainKt", "Implementation-Version" to project.version.toString()) }
}

tasks.named("assemble") { dependsOn(hubJar) }
