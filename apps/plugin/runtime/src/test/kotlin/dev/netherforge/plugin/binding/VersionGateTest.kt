package dev.netherforge.plugin.binding
import dev.netherforge.format.game.MinecraftVersion
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Version gating: what the spec marks `since` a feature that arrived in a
 * Minecraft version newer than the server's is an error naming the version.
 * Nothing in the spec is gated today (format's feature table is empty), so
 * these gates are made up, against the fake server's 26.3.
 */
class VersionGateTest {
    private val gates = VersionGates(
        functions = mapOf(
            "Living.health" to "26.4",
            "World.location" to "27.1",
            "nf.worlds.all" to "26.3.1",
            "nf.after" to "27.0",
            "World.time_of_day" to "26.3"
        ),
        events = mapOf("nf.player_join" to "26.4"),
        options = mapOf("EntitySpawnOptions.custom_name" to "26.4", "CommandDefinition.aliases" to "26.4")
    )

    @Test
    fun `using something newer than the server is an error naming the version`() {
        val script = """
            ${LuaChecks.HELPERS}
            local world = nf.worlds.default()
            local pig = world:spawn_entity("pig", vec3(0, 64, 0))
            local alex = nf.players.get("Alex")
            fails("inherited", function() pig:health() end, "Mob:health needs Minecraft 26.4 (this server runs 26.3)")
            fails("inherited by another", function() alex:health() end, "Player:health needs Minecraft 26.4")
            fails("hand-written method", function() world:location(vec3(0, 64, 0)) end, "World:location needs Minecraft 27.1")
            fails("namespace", function() nf.worlds.all() end, "nf.worlds.all needs Minecraft 26.3.1")
            fails("hand-written nf", function() nf.after(1, function() end) end, "nf.after needs Minecraft 27.0")
            fails("event", function() nf.on("player_join", function() end) end, 'the event "player_join" needs Minecraft 26.4')
            fails("option", function() world:spawn_entity("pig", vec3(0, 64, 0), { custom_name = "x" }) end,
              "'options.custom_name' needs Minecraft 26.4")
            fails("hand-written option", function() nf.commands.register("x", { aliases = { "y" } }, function() end) end,
              "'definition.aliases' needs Minecraft 26.4")
            -- Not gated on this server: as old as it, or not named at all.
            log(type(world:time_of_day()) .. " " .. tostring(world:spawn_entity("pig", vec3(0, 64, 0), { tags = { "a" } }) ~= nil))
            -- The error is at the script's line.
            local _, err = pcall(function() pig:health() end)
            log(tostring(err):match("^modules/t/init%.lua:%d+:") ~= nil and "at the line" or tostring(err))
        """.trimIndent()
        TestServer(mapOf("modules/t/init.lua" to script), start = false, versionGates = gates).use { server ->
            server.player("Alex")
            server.start()
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("number true", "at the line"), server.logs)
        }
    }

    @Test
    fun `the spec's gates are all versions`() {
        val all = VersionGates.SPEC.functions + VersionGates.SPEC.events + VersionGates.SPEC.options
        for ((key, since) in all) assertNotNull(MinecraftVersion.parse(since), key)
    }
}
