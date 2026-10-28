package dev.netherforge.format.world

import dev.netherforge.format.project.Contents
import dev.netherforge.format.project.FilesKind
import dev.netherforge.format.project.Layout

/*
 * Like structures and builds, a Minecraft binary file: format works from the file list alone and never reads
 * one (the editor and the CLI hand it null for them, and must not read `maps/` as text: a world has `.json`
 * stats files); only the server does.
 */

/** `maps/<id>/`: a map, a saved world folder scripts copy; its `level.dat` makes it one. Saved from the dev server. */
object MapKind : FilesKind<WorldMap>("map", "maps", Layout.Folder(LEVEL_FILE), Contents.BINARY) {
    override fun of(id: String, files: List<String>) = WorldMap(id)
}

/** The file that makes a folder a Minecraft world. */
private const val LEVEL_FILE = "level.dat"
