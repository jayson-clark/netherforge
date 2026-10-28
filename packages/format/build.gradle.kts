import java.time.Duration

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

/*
 * Lua that format runs itself (`src/commonMain/lua/<name>.lua`), kept as Lua files so stylua formats them and
 * lua-language-server checks them, and handed to the Kotlin as constants: `terrain-glue.lua` is
 * `TERRAIN_GLUE_LUA` in `dev.netherforge.format.terrain`. Generated, so commonMain reads it on JVM and JS alike.
 */
val luaSources = layout.projectDirectory.dir("src/commonMain/lua")
val generatedLua = layout.buildDirectory.dir("generated/lua-sources")

val generateLuaSources by tasks.registering {
    group = "build"
    description = "Turns src/commonMain/lua/*.lua into Kotlin string constants"
    val sources = luaSources
    val out = generatedLua
    inputs.dir(sources)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("dev/netherforge/format/terrain")
        dir.deleteRecursively()
        dir.mkdirs()
        val constants = sources.asFile.listFiles { it: File -> it.extension == "lua" }!!.sortedBy { it.name }.joinToString("\n") {
            val name = it.nameWithoutExtension.uppercase().replace('-', '_') + "_LUA"
            // A raw string can't hold `$` or three quotes as written; neither is likely in Lua, but both stay exact.
            val text = it.readText().replace("$", "\${'$'}").replace("\"\"\"", "\"\"\${'\"'}")
            "/** `src/commonMain/lua/${it.name}`. */\ninternal val $name: String = \"\"\"$text\"\"\"\n"
        }
        dir.resolve(
            "LuaSources.kt"
        ).writeText(
            "// Generated from src/commonMain/lua by generateLuaSources. Don't edit.\npackage dev.netherforge.format.terrain\n\n$constants"
        )
    }
}

val repoRoot: File by rootProject.extra

/** wasmoon's version: `catalog:` in pnpm-workspace.yaml, the one place it's written. */
val wasmoonVersion: String = run {
    val workspace = repoRoot.resolve("pnpm-workspace.yaml").readText()
    requireNotNull(Regex("""^\s+wasmoon:\s*(\S+)\s*$""", RegexOption.MULTILINE).find(workspace)) {
        "pnpm-workspace.yaml's catalog has no wasmoon"
    }.groupValues[1]
}

kotlin {
    jvmToolchain(25)

    // The plugin carries the JVM artifact, and its oldest Minecraft (1.21.x) runs on Java 21.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjdk-release=21")
        }
    }

    js(IR) {
        // Nothing here touches browser or Node APIs; nodejs() is what runs the tests.
        nodejs {
            testTask {
                useMocha {
                    // Mocha's default of 2 s per test is too tight for the terrain tests (they generate whole chunks)
                    // and the Lua ones (wasmoon loads its WebAssembly first) on a shared CI runner, which is several
                    // times slower than a laptop. A test that hangs still fails, well before the JVM's 5-minute task limit.
                    timeout = "30s"
                }
            }
        }
        binaries.library()
        generateTypeScriptDefinitions()
        useEsModules()
        outputModuleName.set("netherforge-format")
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateLuaSources)
        }
        commonMain.dependencies {
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Terrain scripts' Lua (`format/lua/`): luajava's Lua 5.4 on the JVM, the same as the plugin's own
        // state (whose jar brings the natives), and wasmoon's Lua 5.4 in WebAssembly in JS, at the version the
        // workspace's catalog gives everything else ([wasmoonVersion]).
        jvmMain.dependencies {
            implementation(libs.luajava)
            implementation(libs.luajava.lua54)
        }
        jsMain.dependencies {
            implementation(npm("wasmoon", wasmoonVersion))
        }
    }

    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
        optIn.add("kotlin.js.ExperimentalJsExport")
    }
}

// Lua's native library for the JVM tests (the plugin's jar brings its own).
dependencies {
    "jvmTestRuntimeOnly"(variantOf(libs.luajava.natives) { classifier("natives-desktop") })
}

/*
 * Golden tests read the repo's examples/ and packages/format/testdata/ directly. The
 * paths are handed to the test JVM and the test Node process through the
 * environment so commonTest can stay free of path logic.
 */

val updateGolden = System.getenv("UPDATE_GOLDEN") ?: ""
val goldenInputs = listOf(
    repoRoot.resolve("examples"),
    layout.projectDirectory.dir("testdata").asFile
)

// TerrainScriptApiTest holds the terrain scripts' Lua to the API spec's JSON.
val terrainApi = repoRoot.resolve("packages/api/generated/terrain.json")

tasks.withType<Test>().configureEach {
    // luajava (terrain scripts) loads Lua's native library; Java 25 warns unless that's allowed explicitly.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // A Lua budget bug shows up as a hang; fail instead of waiting forever.
    timeout.set(Duration.ofMinutes(5))
    environment("NETHERFORGE_REPO", repoRoot.absolutePath)
    environment("UPDATE_GOLDEN", updateGolden)
    goldenInputs.forEach { inputs.dir(it) }
    inputs.file(terrainApi)
    inputs.property("updateGolden", updateGolden)
    // NETHERFORGE_BENCH=1 runs the benchmarks (TerrainDensityTimingTest), so a run with it isn't up to date from one without.
    inputs.property("bench", System.getenv("NETHERFORGE_BENCH") ?: "")
    // Rewriting goldens must always run, even if nothing changed since the last pass.
    if (updateGolden == "1") outputs.upToDateWhen { false }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    environment("NETHERFORGE_REPO", repoRoot.absolutePath)
    environment("UPDATE_GOLDEN", updateGolden)
    goldenInputs.forEach { inputs.dir(it) }
    inputs.file(terrainApi)
    inputs.property("updateGolden", updateGolden)
    if (updateGolden == "1") outputs.upToDateWhen { false }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>().configureEach {
    compilerOptions { sourceMap.set(false) }
}

/*
 * JSON Schemas for project files and the editor's TypeScript types, generated
 * from the serializers so the Kotlin model stays the one authority. Not
 * committed: the editor can't build without this project's JS output anyway.
 */
val generatedDir = layout.buildDirectory.dir("generated-contract")

val generateContract by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Writes the JSON Schemas and TypeScript types for project files"
    val main = kotlin.jvm().compilations.getByName("main")
    classpath = main.runtimeDependencyFiles!! + files(main.output.allOutputs)
    mainClass.set("dev.netherforge.format.codegen.ContractKt")
    args(generatedDir.get().asFile.absolutePath)
    dependsOn(tasks.named("jvmMainClasses"))
    inputs.files(main.output.allOutputs)
    outputs.dir(generatedDir)
}

val jsLibrary by tasks.registering {
    group = "build"
    description = "Builds the JS library and the generated contract the editor imports"
    dependsOn("jsNodeProductionLibraryDistribution", generateContract)
}

tasks.named("assemble") { dependsOn(generateContract) }
