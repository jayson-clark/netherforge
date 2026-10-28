package dev.netherforge.plugin.testrunner

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.game.GameDataBundle
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * The game data a project's tests run on: the export the dev server wrote for the project's Minecraft version, which the
 * editor caches per user (`<data>/minecraft/<version>/server/game-data.json`) and `netherforge check` reads too. It's
 * never shipped or faked here: a project is validated against the real registries, tags and block states, or not run.
 */
internal object GameDataFile {
    /** The bundle in [file], or throws [ScriptTestRunner.Refused] saying how to get one. */
    fun read(file: Path): GameDataBundle {
        val text = try {
            Files.readString(file)
        } catch (e: NoSuchFileException) {
            throw ScriptTestRunner.Refused("there is no game data at $file. $HOW")
        }
        val bundle = try {
            Bridge.json.decodeFromString(GameDataBundle.serializer(), text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ScriptTestRunner.Refused("$file isn't game data this NetherForge reads (${e.message?.lineSequence()?.first()}). $HOW")
        }
        // A cache of another schema is as good as none: the dev server exports it again.
        if (bundle.schema != GameDataBundle.SCHEMA) {
            throw ScriptTestRunner.Refused(
                "$file is game data of schema ${bundle.schema}, but this NetherForge reads schema ${GameDataBundle.SCHEMA}. $HOW"
            )
        }
        return bundle
    }

    private const val HOW = "Start the dev server from the NetherForge editor once for the project's Minecraft version to export it."
}
