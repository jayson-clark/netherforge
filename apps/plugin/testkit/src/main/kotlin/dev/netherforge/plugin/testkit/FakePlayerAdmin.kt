package dev.netherforge.plugin.testkit

import dev.netherforge.plugin.platform.AdvancementOps
import dev.netherforge.plugin.platform.BanSpec
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PermissionOps
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.PlayerViewOps
import dev.netherforge.plugin.platform.ServerAdminOps
import java.util.UUID

/*
 * The fake server's per-player views, its settings and lists, the project's
 * permissions and advancements (`platform/PlayerAdmin.kt`), kept beside
 * FakePlatform so its players and entities are the ones these act on.
 */

/** What each player has been shown, as plain records a test reads. */
class FakePlayerViews(private val platform: FakePlatform) : PlayerViewOps {
    /** Each player's fake blocks: `"<world> x y z"` → state. */
    val blocks = HashMap<UUID, MutableMap<String, String>>()

    /** Each player's fake equipment: `"<entity> <slot>"` → item (null: shown empty). */
    val equipment = HashMap<UUID, MutableMap<String, ItemData?>>()
    val cameras = HashMap<UUID, UUID>()
    val compasses = HashMap<UUID, Location>()
    val books = HashMap<UUID, MutableList<List<String>>>()
    val viewDistances = HashMap<UUID, Int>()

    /** What the server sends when nothing changed it. */
    var serverViewDistance = 10

    private fun online(player: UUID) = player in platform.players.byId

    override fun sendBlock(player: UUID, world: String, x: Int, y: Int, z: Int, state: String): Boolean {
        if (!online(player)) return false
        blocks.getOrPut(player) { LinkedHashMap() }["$world $x $y $z"] = state
        return true
    }

    override fun resetBlock(player: UUID, world: String, x: Int, y: Int, z: Int): Boolean {
        if (!online(player) || world !in platform.worlds.worldNames) return false
        blocks[player]?.remove("$world $x $y $z")
        return true
    }

    override fun sendEquipment(player: UUID, entity: UUID, slot: String, item: ItemData?): Boolean {
        if (!online(player) || platform.worldEntities.info(entity) == null) return false
        equipment.getOrPut(player) { LinkedHashMap() }["$entity $slot"] = item
        return true
    }

    override fun camera(player: UUID): UUID? = cameras[player]

    override fun setCamera(player: UUID, entity: UUID?): Boolean {
        val who = platform.players.byId[player] ?: return false
        if (who.gameMode != "spectator") return false
        if (entity == null) {
            cameras.remove(player)
            return true
        }
        // Any entity there is, NetherForge's own displays included (a cutscene's camera is one).
        val at = platform.worldEntities.info(entity)?.location ?: platform.entities.all[entity]?.location ?: return false
        cameras[player] = entity
        // As on the server: the player goes to the entity, and with it from then on ([cameraMoved]).
        who.location = at
        return true
    }

    /** [entity] moved to [to]: whoever looks through it goes with it, as the server moves a spectator each tick. */
    fun cameraMoved(entity: UUID, to: Location) {
        for ((player, camera) in cameras) if (camera == entity) platform.players.byId[player]?.location = to
    }

    override fun compassTarget(player: UUID): Location? =
        if (online(player)) compasses[player] ?: platform.worlds.spawnLocation("world") else null

    override fun setCompassTarget(player: UUID, target: Location): Boolean {
        if (!online(player)) return false
        compasses[player] = target
        return true
    }

    override fun openBook(player: UUID, pages: List<String>): Boolean {
        if (!online(player)) return false
        books.getOrPut(player) { mutableListOf() } += pages
        return true
    }

    override fun viewDistance(player: UUID): Int? = if (online(player)) viewDistances[player] ?: serverViewDistance else null

    override fun setViewDistance(player: UUID, chunks: Int): Boolean {
        if (!online(player)) return false
        viewDistances[player] = chunks
        return true
    }
}

/** The server's settings and lists, kept as fields a test can read and set. */
class FakeServerAdmin(private val platform: FakePlatform) : ServerAdminOps {
    /** When each known player first joined and was last seen, Unix ms. */
    val played = HashMap<UUID, Pair<Long, Long>>()

    /** What "now" is: when someone online was last seen, and what a ban runs out against. */
    var now = System.currentTimeMillis()
    var limit = 20
    var messageOfTheDay = "A Minecraft Server"
    var whitelistOn = false
    val whitelist = LinkedHashSet<UUID>()
    val bans = LinkedHashMap<UUID, BanSpec>()

    private val players get() = platform.players

    /** Names the server's lists keep for people who never played here (a whitelist entry carries one). */
    val names = HashMap<UUID, String>()

    /** Someone by the name the server has for them; null when it has none. */
    private fun ref(uuid: UUID): PlayerRef? = players.known[uuid] ?: names[uuid]?.let { PlayerRef(uuid, it) }

    override fun known(): List<PlayerRef> = players.known.values.toList()

    override fun firstPlayed(player: UUID): Long? = if (player in players.known) played[player]?.first ?: now else null

    override fun lastSeen(player: UUID): Long? = when (player) {
        in players.byId -> now
        in players.known -> played[player]?.second ?: now
        else -> null
    }

    override fun maxPlayers() = limit

    override fun setMaxPlayers(count: Int) {
        limit = count
    }

    override fun motd() = messageOfTheDay

    override fun setMotd(miniMessage: String) {
        messageOfTheDay = miniMessage
    }

    override fun isWhitelistEnabled() = whitelistOn

    override fun setWhitelistEnabled(enabled: Boolean) {
        whitelistOn = enabled
    }

    override fun whitelisted(): List<PlayerRef> = whitelist.mapNotNull(::ref)

    override fun isWhitelisted(player: UUID) = player in whitelist

    override fun setWhitelisted(player: UUID, whitelisted: Boolean) {
        if (whitelisted) whitelist += player else whitelist -= player
    }

    private fun expired(ban: BanSpec) = ban.expires?.let { it <= now } == true

    override fun banned(): List<PlayerRef> = bans.filterValues { !expired(it) }.keys.mapNotNull(::ref)

    override fun isBanned(player: UUID) = bans[player]?.let { !expired(it) } == true

    override fun ban(player: UUID, ban: BanSpec) {
        bans[player] = ban
        players.kick(player, ban.reason ?: "You are banned from this server.")
    }

    override fun unban(player: UUID): Boolean = bans.remove(player) != null
}

/** The nodes the project holds on each online player, as an attachment would. */
class FakePermissions(private val platform: FakePlatform) : PermissionOps {
    override fun apply(player: UUID, nodes: Map<String, Boolean>) {
        platform.players.byId[player]?.granted = nodes
    }
}

/**
 * A few of the game's advancements by key, each with its criteria's names,
 * the project's from the start-up datapack the fake server started with
 * ([FakeDatapacks.advancements]), and what each player has met.
 * Completing one (every requirement group met) raises the server's event, as
 * Paper does.
 */
class FakeAdvancements(private val platform: FakePlatform) : AdvancementOps {
    val criteria = linkedMapOf(
        "minecraft:story/mine_stone" to listOf("get_stone"),
        "minecraft:story/smelt_iron" to listOf("iron"),
        "minecraft:story/mine_diamond" to listOf("diamond"),
        "minecraft:nether/root" to listOf("entered_nether"),
        "minecraft:adventure/adventuring_time" to listOf("minecraft:plains", "minecraft:desert", "minecraft:forest")
    )

    /** By player, then advancement: the criteria they've met. */
    val met = HashMap<UUID, MutableMap<String, MutableSet<String>>>()

    private fun online(player: UUID) = player in platform.players.byId

    private fun metBy(player: UUID, key: String) = met.getOrPut(player) { HashMap() }.getOrPut(key) { LinkedHashSet() }

    /** Each group needs one of its criteria met: the game's own each its own group, the project's as their files say. */
    private fun requirements(key: String): List<List<String>> =
        platform.datapacks.advancements[key]?.second ?: criteria.getValue(key).map { listOf(it) }

    private fun done(mine: Set<String>, key: String) = requirements(key).all { group -> group.any { it in mine } }

    override fun criteria(key: String): List<String>? = criteria[key] ?: platform.datapacks.advancements[key]?.first

    override fun grant(player: UUID, key: String, criterion: String?): Boolean {
        if (!online(player)) return false
        val mine = metBy(player, key)
        val before = done(mine, key)
        if (!mine.addAll(criterion?.let { listOf(it) } ?: criteria(key)!!)) return false
        if (!before && done(mine, key)) {
            val who = platform.players.byId.getValue(player).ref
            platform.raise.playerCompleteAdvancement(GameEvent.PlayerAdvancement(who, key, "${who.name} has made the advancement $key"))
        }
        return true
    }

    override fun revoke(player: UUID, key: String, criterion: String?): Boolean {
        if (!online(player)) return false
        val mine = metBy(player, key)
        if (criterion != null) return mine.remove(criterion)
        if (mine.isEmpty()) return false
        mine.clear()
        return true
    }

    override fun has(player: UUID, key: String) = online(player) && done(metBy(player, key), key)

    override fun progress(player: UUID, key: String): Pair<List<String>, List<String>>? {
        if (!online(player)) return null
        val mine = metBy(player, key)
        val (done, remaining) = criteria(key)!!.partition { it in mine }
        return done to remaining
    }
}
