package dev.netherforge.plugin.api

import dev.netherforge.format.advancement.AdvancementKind
import dev.netherforge.format.dialog.DialogKind
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.menu.MenuKind
import dev.netherforge.format.project.Names
import dev.netherforge.format.recipe.RecipeKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.format.sky.SkyKind
import dev.netherforge.plugin.binding.ActiveBuff
import dev.netherforge.plugin.binding.AdvancementProgress
import dev.netherforge.plugin.binding.BanOptions
import dev.netherforge.plugin.binding.BlockPosition
import dev.netherforge.plugin.binding.CONSOLE_SENDER
import dev.netherforge.plugin.binding.DialogOpenOptions
import dev.netherforge.plugin.binding.InventoryKeys
import dev.netherforge.plugin.binding.LocationOrVec3
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.binding.LuaPlace
import dev.netherforge.plugin.binding.MenuOpenOptions
import dev.netherforge.plugin.binding.ParticleOptions
import dev.netherforge.plugin.binding.PlayerApi
import dev.netherforge.plugin.binding.SenderApi
import dev.netherforge.plugin.binding.SkyOptions
import dev.netherforge.plugin.binding.SoundOptions
import dev.netherforge.plugin.binding.StringOrDialog
import dev.netherforge.plugin.binding.StringOrMenuTemplate
import dev.netherforge.plugin.binding.StringOrNumberOrBoolean
import dev.netherforge.plugin.binding.TitleOptions
import dev.netherforge.plugin.binding.entityHandle
import dev.netherforge.plugin.binding.notInProject
import dev.netherforge.plugin.binding.place
import dev.netherforge.plugin.data.ScriptData
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.AdvancementOps
import dev.netherforge.plugin.platform.BanSpec
import dev.netherforge.plugin.platform.BorderOwner
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.RecipeOps
import dev.netherforge.plugin.session.ProjectSession
import dev.netherforge.plugin.stats.StatSheets
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** A handle's UUID, or null for one that doesn't parse (which never names anyone). */
internal fun LuaHandle.Player.uuidOrNull(): UUID? = runCatching { UUID.fromString(id) }.getOrNull()

internal class PlayerImpl(private val session: ProjectSession) : PlayerApi {
    private val players get() = session.platform.players
    private val entities get() = session.platform.worldEntities

    /** Their UUID while they're online. */
    private fun online(self: LuaHandle.Player): UUID? = self.uuidOrNull()?.takeIf { players.get(it) != null }

    private fun flag(self: LuaHandle.Player, flag: EntityFlag): Boolean = online(self)?.let { entities.flag(it, flag) } == true

    private fun number(self: LuaHandle.Player, number: EntityNumber): Double? = online(self)?.let { entities.number(it, number) }

    /** Sets a number after checking it's within [range]; false when they're offline. */
    private fun setNumber(
        self: LuaHandle.Player,
        number: EntityNumber,
        value: Double,
        name: String,
        range: ClosedFloatingPointRange<Double>
    ): Boolean {
        if (value.isNaN() || value !in range) {
            throw LuaApiException("$name must be from ${text(range.start)} to ${text(range.endInclusive)}, not ${text(value)}")
        }
        return online(self)?.let { entities.setNumber(it, number, value) } == true
    }

    private fun text(value: Double) = if (value == Math.floor(value) && value.isFinite()) value.toLong().toString() else value.toString()

    /** Looked up every time: a player can come back with another name. */
    private val statCalls by lazy { StatCalls(session) }

    override fun stat(self: LuaHandle.Player, name: String): Double =
        statCalls.stat(self.uuidOrNull()?.let(StatSheets.Holder::Player), name)

    override fun stats(self: LuaHandle.Player): Map<String, Double> = statCalls.stats(self.uuidOrNull()?.let(StatSheets.Holder::Player))

    override fun modifiers(self: LuaHandle.Player, source: String): Map<String, Double>? =
        statCalls.modifiers(self.uuidOrNull()?.let(StatSheets.Holder::Player), source)

    override fun setModifiers(self: LuaHandle.Player, source: String, modifiers: Map<String, Double>?): Boolean =
        statCalls.setModifiers(self.uuidOrNull()?.let(StatSheets.Holder::Player), source, modifiers)

    override fun addBuff(self: LuaHandle.Player, buff: String, ticks: Long): Boolean =
        statCalls.addBuff(self.uuidOrNull()?.let(StatSheets.Holder::Player), buff, ticks)

    override fun removeBuff(self: LuaHandle.Player, buff: String): Boolean =
        statCalls.removeBuff(self.uuidOrNull()?.let(StatSheets.Holder::Player), buff)

    override fun hasBuff(self: LuaHandle.Player, buff: String): Boolean =
        statCalls.hasBuff(self.uuidOrNull()?.let(StatSheets.Holder::Player), buff)

    override fun buffTicks(self: LuaHandle.Player, buff: String): Long? =
        statCalls.buffTicks(self.uuidOrNull()?.let(StatSheets.Holder::Player), buff)

    override fun buffs(self: LuaHandle.Player): List<ActiveBuff> = statCalls.buffs(self.uuidOrNull()?.let(StatSheets.Holder::Player))

    override fun name(self: LuaHandle.Player): String =
        self.uuidOrNull()?.let(players::get)?.name ?: players.known(self.id)?.name ?: self.id

    override fun displayName(self: LuaHandle.Player): String? = online(self)?.let(players::displayName)

    override fun setDisplayName(self: LuaHandle.Player, text: String): Boolean =
        online(self)?.let { players.setDisplayName(it, text) } == true

    override fun sendMessage(self: LuaHandle.Player, text: String): Boolean = self.uuidOrNull()?.let { players.message(it, text) } == true

    /** Shown with their HUD while they have one, which would otherwise draw over it within a second. */
    override fun sendActionbar(self: LuaHandle.Player, text: String): Boolean {
        val player = online(self) ?: return false
        return session.huds.message(player, text) || players.actionbar(player, text)
    }

    override fun hud(self: LuaHandle.Player): LuaHandle.Hud = LuaHandle.Hud(self.id)

    override fun sendTitle(self: LuaHandle.Player, title: String, subtitle: String?, options: TitleOptions?): Boolean {
        fun ticks(value: Long?, default: Int, name: String): Int {
            val it = value ?: return default
            if (it !in 0..Int.MAX_VALUE) throw LuaApiException("options.$name can't be negative")
            return it.toInt()
        }
        val fadeIn = ticks(options?.fadeIn, 10, "fade_in")
        val stay = ticks(options?.stay, 70, "stay")
        val fadeOut = ticks(options?.fadeOut, 20, "fade_out")
        return online(self)?.let { players.title(it, title, subtitle.orEmpty(), fadeIn, stay, fadeOut) } == true
    }

    override fun clearTitle(self: LuaHandle.Player): Boolean = online(self)?.let(players::clearTitle) == true

    override fun playSound(self: LuaHandle.Player, sound: String, options: SoundOptions?): Boolean {
        val play = session.effects.sound(sound, options)
        val uuid = online(self) ?: return false
        return session.platform.sounds.playTo(uuid, play)
    }

    override fun stopSound(self: LuaHandle.Player, sound: String?): Boolean {
        val id = sound?.let(session.effects::soundId)
        val uuid = online(self) ?: return false
        return session.platform.sounds.stop(uuid, id)
    }

    override fun spawnParticle(self: LuaHandle.Player, particle: String, position: Vec3, options: ParticleOptions?): Boolean {
        val player = self.uuidOrNull()?.let { players.get(it) }
        val world = player?.let { players.location(it.uuid) }?.world
        return session.effects.spawnParticleFor(player, world, particle, position, options)
    }

    override fun run(self: LuaHandle.Player, command: String): Boolean =
        self.uuidOrNull()?.let { players.runCommand(it, command.removePrefix("/")) } == true

    override fun hasPermission(self: LuaHandle.Player, permission: String): Boolean =
        self.uuidOrNull()?.let { players.hasPermission(it, permission) } == true

    override fun isOperator(self: LuaHandle.Player): Boolean = self.uuidOrNull()?.let(players::isOperator) == true

    override fun gameMode(self: LuaHandle.Player): String? = online(self)?.let(players::gameMode)

    override fun setGameMode(self: LuaHandle.Player, gameMode: String): Boolean =
        online(self)?.let { players.setGameMode(it, gameMode) } == true

    override fun food(self: LuaHandle.Player): Long? = number(self, EntityNumber.FOOD)?.toLong()

    override fun setFood(self: LuaHandle.Player, food: Long): Boolean = setNumber(
        self,
        EntityNumber.FOOD,
        food.toDouble(),
        "food",
        0.0..20.0
    )

    override fun saturation(self: LuaHandle.Player): Double? = number(self, EntityNumber.SATURATION)

    override fun setSaturation(self: LuaHandle.Player, saturation: Double): Boolean {
        val food = number(self, EntityNumber.FOOD) ?: 20.0
        return setNumber(self, EntityNumber.SATURATION, saturation, "saturation", 0.0..food)
    }

    override fun level(self: LuaHandle.Player): Long? = number(self, EntityNumber.LEVEL)?.toLong()

    override fun setLevel(self: LuaHandle.Player, level: Long): Boolean =
        setNumber(self, EntityNumber.LEVEL, level.toDouble(), "level", 0.0..Int.MAX_VALUE.toDouble())

    override fun experienceProgress(self: LuaHandle.Player): Double? = number(self, EntityNumber.EXPERIENCE_PROGRESS)

    override fun setExperienceProgress(self: LuaHandle.Player, progress: Double): Boolean =
        setNumber(self, EntityNumber.EXPERIENCE_PROGRESS, progress, "progress", 0.0..1.0)

    override fun giveExperience(self: LuaHandle.Player, points: Long): Boolean {
        if (points !in 0..Int.MAX_VALUE) throw LuaApiException("points must be at least 0, not $points")
        return online(self)?.let { players.giveExperience(it, points.toInt()) } == true
    }

    override fun isFlying(self: LuaHandle.Player): Boolean = flag(self, EntityFlag.FLYING)

    override fun setFlying(self: LuaHandle.Player, flying: Boolean): Boolean =
        online(self)?.let { entities.setFlag(it, EntityFlag.FLYING, flying) } == true

    override fun canFly(self: LuaHandle.Player): Boolean = flag(self, EntityFlag.CAN_FLY)

    override fun setCanFly(self: LuaHandle.Player, canFly: Boolean): Boolean =
        online(self)?.let { entities.setFlag(it, EntityFlag.CAN_FLY, canFly) } == true

    override fun walkSpeed(self: LuaHandle.Player): Double? = number(self, EntityNumber.WALK_SPEED)

    override fun setWalkSpeed(self: LuaHandle.Player, speed: Double): Boolean = setNumber(
        self,
        EntityNumber.WALK_SPEED,
        speed,
        "speed",
        -1.0..1.0
    )

    override fun flySpeed(self: LuaHandle.Player): Double? = number(self, EntityNumber.FLY_SPEED)

    override fun setFlySpeed(self: LuaHandle.Player, speed: Double): Boolean = setNumber(
        self,
        EntityNumber.FLY_SPEED,
        speed,
        "speed",
        -1.0..1.0
    )

    override fun isSneaking(self: LuaHandle.Player): Boolean = flag(self, EntityFlag.SNEAKING)

    override fun isSprinting(self: LuaHandle.Player): Boolean = flag(self, EntityFlag.SPRINTING)

    override fun locale(self: LuaHandle.Player): String? = online(self)?.let(players::locale)

    override fun ping(self: LuaHandle.Player): Long? = number(self, EntityNumber.PING)?.toLong()

    override fun kick(self: LuaHandle.Player, reason: String?): Boolean = online(self)?.let { players.kick(it, reason) } == true

    override fun enderChest(self: LuaHandle.Player): LuaHandle.Inventory? = online(self)?.let {
        InventoryKeys.of(InventoryRef.EnderChest(it))
    }

    override fun sky(self: LuaHandle.Player): String? = online(self)?.let(session.skies::ofPlayer)?.let(session.names::spell)

    override fun setSky(self: LuaHandle.Player, sky: String, options: SkyOptions?) {
        val name = session.names.resource(SkyKind, sky)
        val fade = fadeTicks(options)
        session.skies.check(name)
        session.skies.setPlayer(online(self) ?: return, name, fade)
    }

    override fun clearSky(self: LuaHandle.Player, options: SkyOptions?) {
        val fade = fadeTicks(options)
        session.skies.clearPlayer(online(self) ?: return, fade)
    }

    override fun storage(self: LuaHandle.Player, name: String, size: Long): LuaHandle.Inventory? {
        if (!Names.isId(name)) throw LuaApiException("\"$name\" isn't a storage name (${Names.ID_RULE})")
        if (size !in 1..InventoryRef.Storage.MAX_SIZE) {
            throw LuaApiException("a storage has 1 to ${InventoryRef.Storage.MAX_SIZE} slots, not $size")
        }
        val uuid = online(self) ?: return null
        // The calling script's namespace: a package's storages are its own.
        return InventoryKeys.of(InventoryRef.Storage(uuid, "${session.names.calling()}:$name", size.toInt()))
    }

    override fun heldItem(self: LuaHandle.Player): ItemData? = online(self)?.let { entities.equipment(it, "main_hand") }

    override fun setHeldItem(self: LuaHandle.Player, item: ItemData?): Boolean =
        online(self)?.let { entities.setEquipment(it, "main_hand", item) } == true

    override fun offHandItem(self: LuaHandle.Player): ItemData? = online(self)?.let { entities.equipment(it, "off_hand") }

    override fun setOffHandItem(self: LuaHandle.Player, item: ItemData?): Boolean =
        online(self)?.let { entities.setEquipment(it, "off_hand", item) } == true

    override fun cursorItem(self: LuaHandle.Player): ItemData? = online(self)?.let { players.cursor(it) }

    override fun setCursorItem(self: LuaHandle.Player, item: ItemData?): Boolean = online(self)?.let { players.setCursor(it, item) } == true

    override fun heldSlot(self: LuaHandle.Player): Long? = number(self, EntityNumber.HELD_SLOT)?.toLong()

    override fun setHeldSlot(self: LuaHandle.Player, index: Long): Boolean = setNumber(
        self,
        EntityNumber.HELD_SLOT,
        index.toDouble(),
        "index",
        0.0..8.0
    )

    override fun giveItem(self: LuaHandle.Player, item: ItemData): Long? {
        val uuid = online(self) ?: return null
        val left = session.platform.inventories.add(InventoryRef.Player(uuid), item) ?: return null
        if (left > 0) {
            val at = players.location(uuid) ?: return left.toLong()
            entities.spawnItem(at.world, Vec3(at.x, at.y, at.z), item.copy(def = item.def.copy(count = left)))
        }
        return left.toLong()
    }

    /** A cooldown group's key (an item kind is its own group), namespaced. */
    private fun cooldownKey(key: String): String {
        if (!GameIds.isValid(key)) throw LuaApiException("\"$key\" isn't an item kind or a cooldown group (<namespace>:<name>)")
        return GameIds.normalize(key)
    }

    override fun discoverRecipe(self: LuaHandle.Player, recipe: String): Boolean = recipeBook(self, recipe) { ops, uuid, id ->
        ops.discover(uuid, id)
    }

    override fun undiscoverRecipe(self: LuaHandle.Player, recipe: String): Boolean = recipeBook(self, recipe) { ops, uuid, id ->
        ops.undiscover(uuid, id)
    }

    override fun hasDiscoveredRecipe(self: LuaHandle.Player, recipe: String): Boolean = recipeBook(self, recipe) { ops, uuid, id ->
        ops.hasDiscovered(uuid, id)
    }

    /**
     * [act] on their recipe book. A bare id names a project recipe, which must
     * exist (a typo is an error, even for someone offline); a namespaced one
     * (`minecraft:bread`) is any the server has, which the server checks.
     * A bare id is the calling package's own; [act] gets the id as the
     * server knows it.
     */
    private fun recipeBook(self: LuaHandle.Player, recipe: String, act: (RecipeOps, UUID, String) -> Boolean): Boolean {
        val id = if (':' in recipe) recipe else session.names.resource(RecipeKind, recipe)
        if (':' !in recipe && !session.recipes.has(id)) {
            val known = session.recipes.ids().map(session.names::spell)
            throw LuaApiException(
                "the project has no recipe \"$recipe\"" + if (known.isEmpty()) "" else " (it has: ${known.joinToString()})"
            )
        }
        val uuid = online(self) ?: return false
        return act(session.platform.recipes, uuid, id)
    }

    override fun itemCooldown(self: LuaHandle.Player, key: String): Long? {
        val group = cooldownKey(key)
        return online(self)?.let { players.cooldown(it, group) }?.toLong()
    }

    override fun setItemCooldown(self: LuaHandle.Player, key: String, ticks: Long): Boolean {
        val group = cooldownKey(key)
        if (ticks !in 0..Int.MAX_VALUE) throw LuaApiException("ticks can't be negative")
        return online(self)?.let { players.setCooldown(it, group, ticks.toInt()) } == true
    }

    override fun openMenu(self: LuaHandle.Player, menu: StringOrMenuTemplate, options: MenuOpenOptions?): LuaHandle.Menu? {
        val template = when (menu) {
            is StringOrMenuTemplate.String -> return openMenuFile(self, menu.value, options)
            is StringOrMenuTemplate.MenuTemplate -> menu.value
        }
        // A template's windows are never shared, so a context is always fine; a template that's gone opens nothing.
        val player = self.uuidOrNull()?.let { players.get(it) } ?: return null
        if (session.menus.template(template.id) == null) return null
        return session.menus.openTemplate(template.id, player, options?.context?.keep())?.let { LuaHandle.Menu(it.id) }
    }

    private fun openMenuFile(self: LuaHandle.Player, name: String, options: MenuOpenOptions?): LuaHandle.Menu? {
        val menu = session.names.resource(MenuKind, name)
        val menus = session.menus
        val definition = menus.definition(menu) ?: throw LuaApiException(session.notInProject("menu", name, menus.ids()))
        if (options?.context != null && definition.shared) {
            throw LuaApiException("menu $name is shared: everyone sees its one window, so it can't be opened with a context")
        }
        val player = self.uuidOrNull()?.let { players.get(it) } ?: return null
        return menus.open(menu, player, options?.context?.keep())?.let { LuaHandle.Menu(it.id) }
    }

    /** The window of ours [self] is looking at, or null. */
    private fun window(self: LuaHandle.Player) =
        self.uuidOrNull()?.let { session.platform.menus.viewing(it) }?.let { session.menus.windowOf(it) }

    override fun menu(self: LuaHandle.Player): LuaHandle.Menu? = window(self)?.let { LuaHandle.Menu(it.id) }

    override fun refreshItems(self: LuaHandle.Player): Boolean {
        val uuid = online(self) ?: return false
        session.items.refreshView(uuid)
        return true
    }

    override fun closeMenu(self: LuaHandle.Player): Boolean {
        val window = window(self) ?: return false
        return session.menus.close(window, self.uuidOrNull())
    }

    override fun openInventory(self: LuaHandle.Player, inventory: LuaHandle.Inventory): Boolean {
        val ref = InventoryKeys.ref(inventory) ?: return false
        val uuid = online(self) ?: return false
        return session.platform.inventories.open(ref, uuid)
    }

    override fun closeInventory(self: LuaHandle.Player): Boolean = online(self)?.let(players::closeInventory) == true

    override fun openDialog(self: LuaHandle.Player, dialog: StringOrDialog, options: DialogOpenOptions?): Boolean {
        val dialogs = session.dialogs
        // A handle may be to one a script made that's gone by now: then nothing opens, as for `dialog:open_for`.
        val id = when (dialog) {
            is StringOrDialog.Dialog -> dialog.value.id
            is StringOrDialog.String -> session.names.resource(DialogKind, dialog.value).also {
                if (it !in dialogs.ids()) throw LuaApiException(session.notInProject("dialog", dialog.value, dialogs.ids()))
            }
        }
        return session.openDialog(id, self, options)
    }

    override fun closeDialog(self: LuaHandle.Player): Boolean =
        self.uuidOrNull()?.let { players.get(it) }?.let { session.dialogs.close(it) } == true

    override fun sidebar(self: LuaHandle.Player): LuaHandle.Sidebar = LuaHandle.Sidebar(self.id)

    override fun tabName(self: LuaHandle.Player): String? = online(self)?.let { session.platform.playerList.name(it) }

    override fun setTabName(self: LuaHandle.Player, text: String?): Boolean =
        online(self)?.let { session.platform.playerList.setName(it, text) } == true

    override fun tabOrder(self: LuaHandle.Player): Long? = online(self)?.let { session.platform.playerList.order(it) }?.toLong()

    override fun setTabOrder(self: LuaHandle.Player, order: Long): Boolean {
        if (order !in Int.MIN_VALUE..Int.MAX_VALUE) throw LuaApiException("order must fit in 32 bits, not $order")
        return online(self)?.let { session.platform.playerList.setOrder(it, order.toInt()) } == true
    }

    override fun isListedFor(self: LuaHandle.Player, viewer: LuaHandle.Player): Boolean {
        val player = online(self) ?: return false
        val other = online(viewer) ?: return false
        return session.playerListings.isListed(other, player)
    }

    override fun setListedFor(self: LuaHandle.Player, viewer: LuaHandle.Player, listed: Boolean): Boolean {
        val player = online(self) ?: return false
        val other = online(viewer) ?: return false
        return session.playerListings.setListed(other, player, listed)
    }

    override fun belowName(self: LuaHandle.Player): String? = online(self)?.let(session.teams::belowName)

    override fun setBelowName(self: LuaHandle.Player, text: String?): Boolean {
        val player = online(self)?.let(players::get) ?: return false
        session.teams.setBelowName(player, text)
        return true
    }

    override fun border(self: LuaHandle.Player): LuaHandle.WorldBorder? {
        val uuid = online(self) ?: return null
        if (!session.platform.borders.personal(uuid)) return null
        return LuaHandle.WorldBorder(WorldBorderImpl.PLAYER, uuid.toString())
    }

    override fun hasOwnBorder(self: LuaHandle.Player): Boolean {
        val uuid = online(self) ?: return false
        return session.platform.borders.get(BorderOwner.Player(uuid)) != null
    }

    override fun resetBorder(self: LuaHandle.Player): Boolean {
        val uuid = online(self) ?: return false
        return session.platform.borders.reset(uuid)
    }

    override fun tabHeader(self: LuaHandle.Player): String? = online(self)?.let { players.tabText(it, footer = false) }

    override fun setTabHeader(self: LuaHandle.Player, text: String): Boolean =
        online(self)?.let { players.setTabText(it, false, text) } == true

    override fun tabFooter(self: LuaHandle.Player): String? = online(self)?.let { players.tabText(it, footer = true) }

    override fun setTabFooter(self: LuaHandle.Player, text: String): Boolean =
        online(self)?.let { players.setTabText(it, true, text) } == true

    override fun bossbars(self: LuaHandle.Player): List<LuaHandle.BossBar> =
        self.uuidOrNull()?.let(session.bossBars::of).orEmpty().map { LuaHandle.BossBar(it.id.toLong()) }

    override fun resourcePackStatus(self: LuaHandle.Player, pack: String?): String? {
        val name = pack?.let(::onDemandPack)
        return online(self)?.let { session.packs.status(it, name) }
    }

    override fun pushResourcePack(self: LuaHandle.Player, pack: String): Boolean {
        val name = onDemandPack(pack)
        return online(self)?.let { session.packs.push(it, name) } == true
    }

    override fun popResourcePack(self: LuaHandle.Player, pack: String): Boolean {
        val name = onDemandPack(pack)
        return online(self)?.let { session.packs.pop(it, name) } == true
    }

    /** [pack], an `onDemand` pack the calling script named, as the server names it; an error when it isn't one. */
    private fun onDemandPack(pack: String): String {
        val name = session.names.resource(ResourcePackKind, pack)
        val loaded = session.snapshot.everywhere(ResourcePackKind)[name]
            ?: throw LuaApiException("there's no resource pack \"$pack\"")
        if (loaded.value.onDemand != true) {
            throw LuaApiException("resource pack \"$pack\" isn't onDemand: give it \"onDemand\": true in its pack.json to push it")
        }
        return name
    }

    // ---- permissions ----------------------------------------------------------

    /** Their UUID, for what works whether they're online or not; a handle that doesn't name anyone is an error. */
    private fun anyone(self: LuaHandle.Player): UUID = self.uuidOrNull() ?: throw LuaApiException("${self.id} isn't a player's id")

    private fun permissionNode(node: String): String {
        if (!Names.isPermissionNode(node)) throw LuaApiException("\"$node\" isn't a permission node (${Names.PERMISSION_NODE_RULE})")
        return node
    }

    override fun setPermission(self: LuaHandle.Player, permission: String, value: Boolean) {
        val node = permissionNode(permission)
        session.requirements.checkPermission(node, "Player:set_permission")
        session.permissions.set(anyone(self), node, value)
    }

    override fun unsetPermission(self: LuaHandle.Player, permission: String): Boolean =
        session.permissions.unset(anyone(self), permissionNode(permission))

    override fun permissions(self: LuaHandle.Player): Map<String, Boolean> = session.permissions.of(anyone(self))

    // ---- played times, bans and the whitelist ----------------------------------

    private val admin get() = session.platform.serverAdmin

    override fun firstPlayed(self: LuaHandle.Player): Long? = admin.firstPlayed(anyone(self))

    override fun lastSeen(self: LuaHandle.Player): Long? = admin.lastSeen(anyone(self))

    override fun isBanned(self: LuaHandle.Player): Boolean = admin.isBanned(anyone(self))

    override fun ban(self: LuaHandle.Player, options: BanOptions?) {
        val expires = options?.expires
        if (expires != null && expires <= System.currentTimeMillis()) {
            throw LuaApiException("options.expires is in the past: it's Unix time in milliseconds, like nf.server.unix_time()")
        }
        admin.ban(anyone(self), BanSpec(options?.reason, expires, options?.source ?: BAN_SOURCE))
    }

    override fun unban(self: LuaHandle.Player): Boolean = admin.unban(anyone(self))

    override fun isWhitelisted(self: LuaHandle.Player): Boolean = admin.isWhitelisted(anyone(self))

    override fun setWhitelisted(self: LuaHandle.Player, whitelisted: Boolean) {
        admin.setWhitelisted(anyone(self), whitelisted)
    }

    // ---- advancements ----------------------------------------------------------

    /**
     * [act] on an advancement the server has, by its key; one it doesn't have
     * (or a [criterion] it doesn't) is an error, even for someone offline.
     */
    private fun <T> advancement(
        self: LuaHandle.Player,
        name: String,
        offline: T,
        criterion: String? = null,
        act: (AdvancementOps, UUID, String) -> T
    ): T {
        val ops = session.platform.advancements
        val key = advancementKey(name)
        val criteria = ops.criteria(key) ?: throw LuaApiException(
            if (session.advancements.has(ResourceRef(key).nameIn(session.namespace) ?: key)) {
                "the server has no advancement \"$key\" yet: it learns the project's advancements as it starts, so restart it"
            } else {
                "the server has no advancement \"$key\""
            }
        )
        if (criterion != null && criterion !in criteria) {
            throw LuaApiException(
                "advancement \"$key\" has no criterion \"$criterion\" (it has ${criteria.joinToString(", ") {
                    "\"$it\""
                }})"
            )
        }
        val uuid = online(self) ?: return offline
        return act(ops, uuid, key)
    }

    /**
     * The server's key for an advancement a script names: a namespace that's
     * neither the project's nor a package's is the game's (`minecraft:…`, a
     * datapack's) as written; anything else is the project's or a package's,
     * named as every resource a script names (a bare id is the calling
     * package's own).
     */
    private fun advancementKey(name: String): String {
        val namespace = ResourceRef(name).namespace
        if (namespace != null && namespace != session.namespace && namespace !in session.snapshot.packages) {
            if (!GameIds.isValid(name)) throw LuaApiException("\"$name\" isn't an advancement's key (<namespace>:<path>)")
            return name
        }
        if (ResourceRef(name).resolve(session.namespace)?.path?.let(Names::isId) != true) {
            throw LuaApiException(
                "\"$name\" isn't an advancement: a project advancement's id, or a key like \"minecraft:story/mine_diamond\""
            )
        }
        return session.advancements.keyOf(session.names.resource(AdvancementKind, name))
    }

    override fun grantAdvancement(self: LuaHandle.Player, advancement: String, criterion: String?): Boolean =
        advancement(self, advancement, false, criterion) { ops, uuid, key -> ops.grant(uuid, key, criterion) }

    override fun revokeAdvancement(self: LuaHandle.Player, advancement: String, criterion: String?): Boolean =
        advancement(self, advancement, false, criterion) { ops, uuid, key -> ops.revoke(uuid, key, criterion) }

    override fun hasAdvancement(self: LuaHandle.Player, advancement: String): Boolean =
        advancement(self, advancement, false) { ops, uuid, id -> ops.has(uuid, id) }

    override fun advancementProgress(self: LuaHandle.Player, advancement: String): AdvancementProgress? =
        advancement(self, advancement, null) { ops, uuid, id ->
            ops.progress(uuid, id)?.let { (done, remaining) -> AdvancementProgress(done, remaining) }
        }

    // ---- what only they see ----------------------------------------------------

    private val views get() = session.platform.playerViews

    /** The block [place] is in, in [uuid]'s world when it names none; null when they're offline. */
    private fun blockFor(uuid: UUID, place: LuaPlace): Pair<String, BlockPosition>? {
        val at = BlockPosition.of(place.position)
        val world = place.world?.name ?: players.location(uuid)?.world ?: return null
        return world to at
    }

    override fun sendBlockChange(self: LuaHandle.Player, locationOrPosition: LocationOrVec3, state: String): Boolean {
        val canonical = session.effects.blockState(state, "state")
        val uuid = online(self) ?: return false
        val (world, at) = blockFor(uuid, locationOrPosition.place) ?: return false
        return views.sendBlock(uuid, world, at.x, at.y, at.z, canonical) == true
    }

    override fun resetBlock(self: LuaHandle.Player, locationOrPosition: LocationOrVec3): Boolean {
        val uuid = online(self) ?: return false
        val (world, at) = blockFor(uuid, locationOrPosition.place) ?: return false
        return views.resetBlock(uuid, world, at.x, at.y, at.z) == true
    }

    override fun sendEquipmentChange(self: LuaHandle.Player, entity: LuaHandle.Entity, slot: String, item: ItemData?): Boolean {
        val uuid = online(self) ?: return false
        val other = entity.uuidOrNull() ?: return false
        return views.sendEquipment(uuid, other, slot, item) == true
    }

    override fun camera(self: LuaHandle.Player): LuaHandle.Entity? = online(self)?.let { views.camera(it) }?.let(session::entityHandle)

    override fun setCamera(self: LuaHandle.Player, entity: LuaHandle.Entity?): Boolean {
        val uuid = online(self) ?: return false
        val target = entity?.let { it.uuidOrNull() ?: return false }
        return views.setCamera(uuid, target) == true
    }

    override fun compassTarget(self: LuaHandle.Player): LuaLocation? = online(self)?.let { views.compassTarget(it) }?.let(LuaLocation::of)

    override fun setCompassTarget(self: LuaHandle.Player, location: LuaLocation): Boolean {
        val uuid = online(self) ?: return false
        val target = Location(location.world.name, location.position.x, location.position.y, location.position.z)
        return views.setCompassTarget(uuid, target) == true
    }

    override fun openBook(self: LuaHandle.Player, pages: List<String>): Boolean {
        if (pages.size > BOOK_PAGES) throw LuaApiException("a book has at most $BOOK_PAGES pages, not ${pages.size}")
        val uuid = online(self) ?: return false
        return views.openBook(uuid, pages) == true
    }

    override fun viewDistance(self: LuaHandle.Player): Long? = online(self)?.let { views.viewDistance(it) }?.toLong()

    override fun setViewDistance(self: LuaHandle.Player, distance: Long): Boolean {
        if (distance !in
            VIEW_DISTANCE
        ) {
            throw LuaApiException("distance must be from ${VIEW_DISTANCE.first} to ${VIEW_DISTANCE.last} chunks, not $distance")
        }
        val uuid = online(self) ?: return false
        return views.setViewDistance(uuid, distance.toInt()) == true
    }

    override fun data(self: LuaHandle.Player): Any? = session.data.table(ScriptData.Owner.Player(anyone(self)), "player ${name(self)}")

    // A table made before they left is still theirs until their `player_quit` handlers are done.
    override fun session(self: LuaHandle.Player): Any? {
        val uuid = self.uuidOrNull() ?: return null
        return session.sessionTables.existing(uuid) ?: online(self)?.let(session.sessionTables::table)
    }

    private companion object {
        /** Who a ban says made it when the script doesn't. */
        const val BAN_SOURCE = "NetherForge"

        /** The most pages a written book has. */
        const val BOOK_PAGES = 100

        /** The view distances a server sends, in chunks. */
        val VIEW_DISTANCE = 2L..32L
    }
}

/**
 * Opens [dialog] for [player] as [options] say: the options are checked
 * first (a mistake in them is an error even when the player is offline),
 * then the context is kept for the opening. False when they're offline.
 */
internal fun ProjectSession.openDialog(dialog: String, player: LuaHandle.Player, options: DialogOpenOptions?): Boolean {
    val values = options?.values?.mapValues { (_, value) ->
        when (value) {
            is StringOrNumberOrBoolean.String -> JsonPrimitive(value.value)
            is StringOrNumberOrBoolean.Number -> JsonPrimitive(value.value)
            is StringOrNumberOrBoolean.Boolean -> JsonPrimitive(value.value)
        }
    }
    val file = dialogs.opened(dialog, values, options?.title, options?.body) ?: return false
    val online = player.uuidOrNull()?.let { platform.players.get(it) } ?: return false
    return dialogs.show(dialog, online, file, options?.context?.keep())
}

/** What the console is called, as `Sender:name` says it. */
private const val CONSOLE_NAME = "CONSOLE"

internal class SenderImpl(private val session: ProjectSession) : SenderApi {
    private val players get() = session.platform.players

    override fun name(self: LuaHandle.Sender): String {
        if (self.id == CONSOLE_SENDER) return CONSOLE_NAME
        return players.known(self.id)?.name ?: self.id
    }

    override fun isPlayer(self: LuaHandle.Sender): Boolean = self.id != CONSOLE_SENDER

    override fun isConsole(self: LuaHandle.Sender): Boolean = self.id == CONSOLE_SENDER

    override fun sendMessage(self: LuaHandle.Sender, text: String): Boolean {
        if (self.id == CONSOLE_SENDER) {
            players.messageConsole(text)
            return true
        }
        val uuid = runCatching { UUID.fromString(self.id) }.getOrNull() ?: return false
        return players.message(uuid, text)
    }

    override fun hasPermission(self: LuaHandle.Sender, permission: String): Boolean {
        if (self.id == CONSOLE_SENDER) return true
        val uuid = runCatching { UUID.fromString(self.id) }.getOrNull() ?: return false
        return players.hasPermission(uuid, permission)
    }
}
