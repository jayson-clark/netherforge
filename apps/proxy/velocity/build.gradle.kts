import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

/*
 * NetherForge for Velocity: the network's hub (`:network`'s `Hub` on a TCP port) run by the proxy, which tells it
 * who's online where and moves players for it. `NetherForge-<version>-velocity.jar`, with `:network`,
 * kotlinx.serialization and Kotlin inside (a Velocity plugin has a class loader of its own, so nothing clashes).
 * See the plugin-capabilities skill's "Networks".
 */

plugins {
    id("netherforge.kotlin-jvm")
    id("com.gradleup.shadow")
}

// Velocity 4 runs on Java 25, and its API says so: the plugin targets it, as the adapters for Minecraft 26 do.
kotlin.compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    freeCompilerArgs.set(freeCompilerArgs.get().filterNot { it.startsWith("-Xjdk-release=") } + "-Xjdk-release=25")
}
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

dependencies {
    implementation(project(":network"))
    implementation(libs.serialization.json)
    // The proxy brings its API.
    compileOnly(libs.velocity.api)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }

/**
 * `META-INF/netherforge/velocity.properties`: the Velocity release and Fill build this jar is made for, which the
 * editor runs a dev network's proxy on rather than whatever build is newest.
 */
val velocityBuildProperties = tasks.register<WriteProperties>("velocityBuildProperties") {
    destinationFile.set(layout.buildDirectory.file("generated/velocity-build/velocity.properties"))
    property("version", libs.versions.velocity.api.get())
    property("build", libs.versions.velocity.build.get())
}

tasks.named<Copy>("processResources") {
    from(velocityBuildProperties) { into("META-INF/netherforge") }
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("velocity-plugin.json") { expand("version" to version) }
}

tasks.named<Jar>("jar") {
    // Only the shadow jar is loadable; keep the thin one from looking like it.
    archiveClassifier.set("thin")
}

/** `NetherForge-<version>-velocity.jar`. */
val pluginJar = tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("NetherForge")
    archiveClassifier.set("velocity")
    mergeServiceFiles()
}

tasks.named("assemble") { dependsOn(pluginJar) }
