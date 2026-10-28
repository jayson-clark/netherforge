package dev.netherforge.plugin.paper.version

import dev.netherforge.format.game.RegistryKey
import dev.netherforge.plugin.paper.ServerRegistries
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.Registry
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.TagKey
import kotlin.jvm.optionals.getOrNull

/**
 * Every registry the running server has, and their tags, by the game's own
 * registry names. Paper's registry API covers a chosen few (and not worldgen
 * features or loot tables), and has no way to list them, so this reaches into
 * the server (Mojang-named, through paperweight-userdev, as the bots and
 * [goalPriorities] do).
 *
 * It reads the server's full lookup, the one loot tables are looked up in:
 * static, worldgen and dimension registries and the reloadable ones (loot
 * tables, predicates, item modifiers), with the tags the last datapack reload
 * gave them. A reload replaces it, so nothing here is kept.
 *
 * The integration test's game data export says whether a new version still
 * holds (see the minecraft-versions skill).
 */
internal object MojangRegistries : ServerRegistries {
    private val lookup: HolderLookup.Provider get() = MinecraftServer.getServer().reloadableRegistries().lookup()

    /** One registry, found by name, with its key typed for making element and tag keys. */
    private class Found(val key: ResourceKey<Registry<Any>>, val registry: HolderLookup.RegistryLookup<Any>)

    private fun find(key: RegistryKey): Found? {
        val name = Identifier.tryParse(key.id) ?: return null
        val registryKey = ResourceKey.createRegistryKey<Any>(name)
        return lookup.lookup(registryKey).getOrNull()?.let { Found(registryKey, it) }
    }

    /**
     * The ids in [key] as a live view: asking whether it holds an id is one
     * lookup in the server's registry, so callers don't pay for a copy.
     */
    override fun ids(key: RegistryKey): Set<String>? {
        val found = find(key) ?: return null
        return object : AbstractSet<String>() {
            override val size: Int get() = found.registry.listElementIds().count().toInt()

            override fun iterator(): Iterator<String> = found.registry.listElementIds().map { it.identifier().toString() }.iterator()

            override fun contains(element: String): Boolean {
                val id = Identifier.tryParse(element) ?: return false
                return found.registry.get(ResourceKey.create(found.key, id)).isPresent
            }
        }
    }

    override fun tag(key: RegistryKey, id: String): Set<String>? {
        val found = find(key) ?: return null
        val tag = Identifier.tryParse(id) ?: return null
        return found.registry.get(TagKey.create(found.key, tag)).getOrNull()?.let(::idsOf)?.toSet()
    }

    override fun allIds(): Map<String, List<String>> = registries().associate { registry ->
        registry.key().identifier().toString() to registry.listElementIds().map { it.identifier().toString() }.toList().sorted()
    }

    override fun allTags(): Map<String, Map<String, List<String>>> = registries().associate { registry ->
        registry.key().identifier().toString() to registry.listTags().toList()
            .associate { it.key().location().toString() to idsOf(it).sorted() }
            .toSortedMap()
    }.filterValues { it.isNotEmpty() }

    private fun registries(): List<HolderLookup.RegistryLookup<*>> =
        lookup.listRegistries().toList().sortedBy { it.key().identifier().toString() }

    private fun idsOf(holders: Iterable<Holder<*>>): List<String> =
        holders.mapNotNull { it.unwrapKey().getOrNull()?.identifier()?.toString() }
}
