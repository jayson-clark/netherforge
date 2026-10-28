plugins {
    `kotlin-dsl`
}

/** A plugin from the version catalog as the dependency that carries it (its marker artifact). */
fun plugin(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" }

dependencies {
    implementation(plugin(libs.plugins.kotlin.jvm))
    implementation(plugin(libs.plugins.kotlin.multiplatform))
    implementation(plugin(libs.plugins.kotlin.serialization))
    implementation(plugin(libs.plugins.shadow))
    implementation(plugin(libs.plugins.run.paper))
    implementation(plugin(libs.plugins.paperweight.userdev))
}

// Locked and verified like the rest of the build (see the testing skill).
dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

/** Resolves every configuration, so `--write-locks` records them all. tools/gradle-lock.mjs runs it. */
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Resolves configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "$path must be run with --write-locks" }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}
