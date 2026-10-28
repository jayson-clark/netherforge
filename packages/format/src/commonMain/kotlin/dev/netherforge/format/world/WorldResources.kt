package dev.netherforge.format.world

/**
 * A map: `maps/<id>/`, a Minecraft world folder (a singleplayer
 * save works as it is) that a script copies to make a live world
 * (`nf.worlds.copy`). It's binary and only the server reads it, so format
 * checks where it is and that it's a world, never what's in it.
 */
data class WorldMap(val id: String)

/**
 * A structure: `structures/<id>.nbt`, a saved region of blocks (and maybe
 * entities) in Minecraft's structure format, which scripts place in a world
 * (`world:place_structure`). Binary, read only by the server: format checks
 * its name and place. [generation] is `structures/<id>.json` beside it, when
 * there is one: where the world generates it by itself.
 */
data class StructureFile(val id: String, val generation: StructureGeneration? = null)
