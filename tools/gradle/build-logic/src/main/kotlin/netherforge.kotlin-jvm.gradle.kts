import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Every Kotlin JVM project. Compiled with Java 25. The plugin's modules are for Java 25 (every supported Minecraft,
 * 26.x, runs on it, and the Rust runtime needs its foreign function API); the rest (the proxy, the hub) for Java 21,
 * which Velocity runs on.
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val java = if (project.path.startsWith(":plugin")) 25 else 21

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(java.toString()))
        freeCompilerArgs.add("-Xjdk-release=$java")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(java)
}

// A failed test prints its message and trace in the build's output: CI keeps no unit-test reports,
// so without it a failure there says only `AssertionFailedError at File.kt:52`.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
