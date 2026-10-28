package dev.netherforge.format.loot

import dev.netherforge.format.Location
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.ref.RefKind

/** `loot/<id>.json`: a loot table, rolled by the runtime. One file, nothing beside it. */
object LootTableKind : DocumentResourceKind<LootTableFile, LootTableFile>(
    "loot_table",
    "loot",
    Layout.SingleFile(".json"),
    LootTableFile.serializer(),
    LootTableFile.SCHEMA
) {
    // Entries and conditions keep their order: a pick walks the entries in it, and conditions are asked in it.
    override fun canonical(value: LootTableFile) = value.copy(schema = schemaRef)

    override fun validate(value: LootTableFile, ctx: ResourceContext) = LootValidator.validate(value, ctx.sink, ctx.game)

    override fun crossCheck(value: LootTableFile, ctx: KindContext) {
        for ((at, entry) in LootValidator.entries(value)) {
            when (entry) {
                is ItemEntry -> ItemKind.checkStack(entry.item, "$at.item", ctx)
                is TableEntry -> cycle(entry, at, ctx)
                else -> Unit
            }
        }
    }

    /**
     * A table entry naming a table of the project's own that leads back to
     * this one, through its entries or theirs, would roll forever. (A
     * package's tables can't name the project's: packages don't depend on
     * the projects that use them.)
     */
    private fun cycle(entry: TableEntry, at: String, ctx: KindContext) {
        val home = ctx.references.home
        val tables = ctx.models(LootTableKind)
        fun own(table: TableEntry) = ctx.references.resolve(RefKind.LOOT_TABLE, table.table.text)?.takeIf { it.namespace == home }?.path
        val start = own(entry) ?: return
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == ctx.id) {
                ctx.sink.report(
                    ProblemCodes.LOOT_CYCLE,
                    "\"${entry.table}\" includes this table again${if (start == ctx.id) "" else " (through its entries)"}, so a roll would never end",
                    "$at.table",
                    listOf(Location(ctx.placeOf(this, start).path))
                )
                return
            }
            if (!seen.add(id)) continue
            val next = tables[id] ?: continue
            for ((_, inner) in LootValidator.entries(next)) if (inner is TableEntry) own(inner)?.let(queue::addLast)
        }
    }

    override fun compile(id: String, value: LootTableFile, ctx: ResourceContext) = value

    /** One pool that gives a stick: starter content the user replaces in the loot editor. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(
            LootTableFile(pools = mapOf("main" to LootPool(entries = listOf(ItemEntry(ItemDef(kind = "minecraft:stick"))))))
        )
    )
}
