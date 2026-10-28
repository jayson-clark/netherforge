package dev.netherforge.plugin.paper

import com.destroystokyo.paper.profile.PlayerProfile
import dev.netherforge.plugin.platform.AdvancementOps
import dev.netherforge.plugin.platform.BanSpec
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PermissionOps
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.PlayerViewOps
import dev.netherforge.plugin.platform.ServerAdminOps
import io.papermc.paper.ban.BanListType
import net.kyori.adventure.inventory.Book
import net.kyori.adventure.text.Component
import org.bukkit.BanEntry
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.advancement.Advancement
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.UUID
import org.bukkit.Location as BukkitLocation

/** What one player is shown that isn't so, and their own camera, compass and view distance. */
class PaperPlayerViews(private val entities: PaperWorldEntities) : PlayerViewOps {
    private fun player(uuid: UUID): Player? = Bukkit.getPlayer(uuid)

    private fun block(world: String, x: Int, y: Int, z: Int): BukkitLocation? =
        Bukkit.getWorld(world)?.let { BukkitLocation(it, x.toDouble(), y.toDouble(), z.toDouble()) }

    override fun sendBlock(player: UUID, world: String, x: Int, y: Int, z: Int, state: String): Boolean {
        val who = player(player) ?: return false
        val data = runCatching { Bukkit.createBlockData(state) }.getOrNull() ?: return false
        // A world that isn't loaded still has a name the client knows nothing of: nothing to draw it in.
        val at = block(world, x, y, z) ?: return false
        who.sendBlockChange(at, data)
        return true
    }

    override fun resetBlock(player: UUID, world: String, x: Int, y: Int, z: Int): Boolean {
        val who = player(player) ?: return false
        val at = block(world, x, y, z) ?: return false
        // Reading the real block mustn't load its chunk: one that isn't loaded is redrawn when the client loads it.
        if (!at.world.isChunkLoaded(x shr 4, z shr 4)) return true
        who.sendBlockChange(at, at.block.blockData)
        return true
    }

    override fun sendEquipment(player: UUID, entity: UUID, slot: String, item: ItemData?): Boolean {
        val who = player(player) ?: return false
        val other = entities.entity(entity) as? LivingEntity ?: return false
        val which = PaperWorldEntities.slot(slot) ?: return false
        val stack = item?.let(PaperItems::toStack) ?: ItemStack.empty()
        who.sendEquipmentChange(other, which, stack)
        return true
    }

    override fun camera(player: UUID): UUID? = player(player)?.spectatorTarget?.uniqueId

    override fun setCamera(player: UUID, entity: UUID?): Boolean {
        val who = player(player) ?: return false
        // Paper refuses a spectator target outside spectator mode.
        if (who.gameMode != GameMode.SPECTATOR) return false
        // Any entity the server has, NetherForge's own displays included (a cutscene's camera is one).
        val target = entity?.let { Bukkit.getEntity(it) ?: return false }
        who.spectatorTarget = target
        return true
    }

    override fun compassTarget(player: UUID): Location? = player(player)?.compassTarget?.let(PaperPlatform::location)

    override fun setCompassTarget(player: UUID, target: Location): Boolean {
        val who = player(player) ?: return false
        val world = Bukkit.getWorld(target.world) ?: return false
        who.compassTarget = BukkitLocation(world, target.x, target.y, target.z)
        return true
    }

    override fun openBook(player: UUID, pages: List<String>): Boolean {
        val who = player(player) ?: return false
        who.openBook(Book.book(Component.empty(), Component.empty(), pages.map(PaperText.mini::deserialize)))
        return true
    }

    override fun viewDistance(player: UUID): Int? = player(player)?.viewDistance

    override fun setViewDistance(player: UUID, chunks: Int): Boolean {
        val who = player(player) ?: return false
        who.viewDistance = chunks
        return true
    }
}

/**
 * The server's settings and lists through the Paper API. Only the server's
 * own records are read: nothing here asks Mojang for a profile, which would
 * block the main thread.
 */
class PaperServerAdmin : ServerAdminOps {
    private val bans get() = Bukkit.getBanList(BanListType.PROFILE)

    private fun offline(uuid: UUID): OfflinePlayer = Bukkit.getOfflinePlayer(uuid)

    private fun ref(player: OfflinePlayer): PlayerRef? = player.name?.let { PlayerRef(player.uniqueId, it) }

    override fun known(): List<PlayerRef> = Bukkit.getOfflinePlayers().mapNotNull(::ref)

    override fun firstPlayed(player: UUID): Long? = offline(player).firstPlayed.takeIf { it > 0 }

    override fun lastSeen(player: UUID): Long? {
        val who = offline(player)
        if (who.isOnline) return System.currentTimeMillis()
        return who.lastSeen.takeIf { it > 0 }
    }

    override fun maxPlayers(): Int = Bukkit.getMaxPlayers()

    override fun setMaxPlayers(count: Int) = Bukkit.setMaxPlayers(count)

    override fun motd(): String = PaperText.mini.serialize(Bukkit.motd())

    override fun setMotd(miniMessage: String) = Bukkit.motd(PaperText.mini.deserialize(miniMessage))

    override fun isWhitelistEnabled(): Boolean = Bukkit.hasWhitelist()

    override fun setWhitelistEnabled(enabled: Boolean) = Bukkit.setWhitelist(enabled)

    override fun whitelisted(): List<PlayerRef> = Bukkit.getWhitelistedPlayers().mapNotNull(::ref)

    override fun isWhitelisted(player: UUID): Boolean = offline(player).isWhitelisted

    override fun setWhitelisted(player: UUID, whitelisted: Boolean) {
        offline(player).isWhitelisted = whitelisted
    }

    override fun banned(): List<PlayerRef> {
        val now = System.currentTimeMillis()
        // The list keeps a ban that ran out until someone it names tries to join.
        return bans.getEntries<BanEntry<PlayerProfile>>()
            .filter { (it.expiration?.time ?: Long.MAX_VALUE) > now }
            .mapNotNull { entry ->
                val profile = entry.banTarget
                val id = profile.id ?: return@mapNotNull null
                PlayerRef(id, profile.name ?: offline(id).name ?: return@mapNotNull null)
            }
    }

    override fun isBanned(player: UUID): Boolean = offline(player).isBanned

    override fun ban(player: UUID, ban: BanSpec) {
        val expires = ban.expires?.let(Instant::ofEpochMilli)
        // Online, the server kicks them itself, with the ban's own screen.
        val online = Bukkit.getPlayer(player)
        if (online != null) {
            online.ban<BanEntry<PlayerProfile>>(ban.reason, expires, ban.source, true)
        } else {
            offline(player).ban<BanEntry<PlayerProfile>>(ban.reason, expires, ban.source)
        }
    }

    override fun unban(player: UUID): Boolean {
        val profile = offline(player).playerProfile
        if (!bans.isBanned(profile)) return false
        bans.pardon(profile)
        return true
    }
}

/**
 * The project's permission nodes on each online player: one attachment of
 * this plugin's per player, replaced whole on every change. The server drops
 * a player's attachments with the player when they leave, so one held for a
 * player object that's gone is only forgotten.
 */
class PaperPermissions(private val plugin: JavaPlugin) : PermissionOps {
    private val attachments = HashMap<UUID, PermissionAttachment>()

    override fun apply(player: UUID, nodes: Map<String, Boolean>) {
        val who = Bukkit.getPlayer(player)
        val old = attachments.remove(player)
        if (who == null) return
        if (old != null && old.permissible === who) who.removeAttachment(old)
        if (nodes.isNotEmpty()) {
            val attachment = who.addAttachment(plugin)
            for ((node, value) in nodes) attachment.setPermission(node, value)
            attachments[player] = attachment
        }
        // Their game's command tree has only what they may run: send it again, so completion matches.
        who.updateCommands()
    }
}

/** Advancements by namespaced key, through each player's progress. */
class PaperAdvancements : AdvancementOps {
    private fun advancement(key: String): Advancement? = NamespacedKey.fromString(key)?.let(Bukkit::getAdvancement)

    private fun progressOf(player: UUID, key: String) =
        Bukkit.getPlayer(player)?.let { who -> advancement(key)?.let(who::getAdvancementProgress) }

    override fun criteria(key: String): List<String>? = advancement(key)?.criteria?.sorted()

    override fun grant(player: UUID, key: String, criterion: String?): Boolean {
        val progress = progressOf(player, key) ?: return false
        if (criterion != null) return progress.awardCriteria(criterion)
        var changed = false
        for (left in progress.remainingCriteria.toList()) changed = progress.awardCriteria(left) || changed
        return changed
    }

    override fun revoke(player: UUID, key: String, criterion: String?): Boolean {
        val progress = progressOf(player, key) ?: return false
        if (criterion != null) return progress.revokeCriteria(criterion)
        var changed = false
        for (had in progress.awardedCriteria.toList()) changed = progress.revokeCriteria(had) || changed
        return changed
    }

    override fun has(player: UUID, key: String): Boolean = progressOf(player, key)?.isDone == true

    override fun progress(player: UUID, key: String): Pair<List<String>, List<String>>? {
        val progress = progressOf(player, key) ?: return null
        return progress.awardedCriteria.sorted() to progress.remainingCriteria.sorted()
    }
}
