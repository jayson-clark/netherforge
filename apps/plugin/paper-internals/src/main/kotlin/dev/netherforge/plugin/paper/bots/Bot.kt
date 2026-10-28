package dev.netherforge.plugin.paper.bots

import com.mojang.authlib.GameProfile
import dev.netherforge.format.bridge.BotActResult
import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotButton
import dev.netherforge.format.bridge.BotClick
import dev.netherforge.format.bridge.BotEntity
import dev.netherforge.format.bridge.BotEvent
import dev.netherforge.format.bridge.BotHand
import dev.netherforge.format.bridge.BotInfo
import dev.netherforge.format.bridge.BotPack
import dev.netherforge.format.bridge.BotPackAnswer
import dev.netherforge.format.bridge.BotState
import dev.netherforge.format.bridge.BotTarget
import dev.netherforge.format.bridge.BotTargetKind
import dev.netherforge.plugin.platform.BotAim
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket
import net.minecraft.network.protocol.common.ServerCommonPacketListener
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket
import net.minecraft.network.protocol.common.ServerboundPongPacket
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket
import net.minecraft.network.protocol.common.custom.BrandPayload
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks
import net.minecraft.network.protocol.configuration.ConfigurationProtocols
import net.minecraft.network.protocol.configuration.ServerboundAcceptCodeOfConductPacket
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.dialog.DialogAction
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.player.Abilities
import net.minecraft.world.entity.player.Input
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * One bot: a vanilla client's half of a connection, played in the server.
 *
 * It joins the way a client does (the configuration phase, known packs, the
 * code of conduct, the resource pack, then the world), answers what a client
 * must (keep-alives, teleports, chunk batches), and does what a player does
 * by sending the packets their client would. Movement is the client's job in
 * Minecraft (the server only checks it), so the bot steps its own position
 * like a client (gravity, collisions, jumping up a block), and the server's
 * movement checks and events see an ordinary player.
 *
 * What the server sends is read as the client would ([BotScreen]) and
 * recorded as [BotEvent]s. What its Minecraft version says its own way
 * (packets, records that were classes) goes through that adapter's [Protocol].
 *
 * Main thread only, except [connection]'s packets arriving and pack
 * downloads, which hand over through queues.
 */
internal class Bot(
    /** Who it is: its name, its UUID and any skin. */
    private val profile: GameProfile,
    private val server: MinecraftServer,
    private val plugin: Plugin,
    private val packAnswer: BotPackAnswer,
    /** Where to put it once it's in the world, if not where it last was. */
    private val joinAt: Location?,
    private var onJoined: ((Result<BotInfo>) -> Unit)?
) {
    val name: String get() = profile.name()
    val uuid: UUID get() = profile.id()

    private val inbox = ConcurrentLinkedQueue<Packet<*>>()
    private val connection = BotConnection { inbox += it }

    /** Work other threads hand back (a pack download finishing), run on the next tick. */
    private val handedBack = ConcurrentLinkedQueue<() -> Unit>()

    private val screen = BotScreen()
    private val events = BotEventLog()

    private var ticks = 0

    /** Gone: disconnected, kicked, or never made it in. */
    var gone = false
        private set
    private var goneReason = "it disconnected"

    // ---- the client's own idea of where it is ----------------------------------

    /** Null until the server has placed it (and again between dying and respawning). */
    private var position: Vec3? = null
    private var velocity = Vec3.ZERO
    private var yaw = 0f
    private var pitch = 0f
    private var onGround = false
    private var horizontalCollision = false
    private var dead = false

    private var sentPosition: Vec3? = null
    private var sentYaw = 0f
    private var sentPitch = 0f
    private var sentOnGround = false
    private var sentHorizontalCollision = false
    private var sinceSent = 0

    private var sneaking = false
    private var sprinting = false
    private var jump = false
    private var sentInput = Input.EMPTY

    /** This tick's walking, set by a [Walk]: blocks a tick along the ground. */
    private var walking: Vec3? = null

    /** The chunks the client has been sent, by [Protocol.chunk]. */
    private val chunks = HashSet<Long>()

    /**
     * The tick the chunk it stands in arrived (since joining, respawning or
     * changing worlds), or null while it's still loading: when the client says
     * it has loaded, and what a join waits for.
     */
    private var loadedAt: Int? = null

    /**
     * Actions asked for while it's loading another world, done once it has: a client
     * on the loading screen does nothing, and the server ignores what it would.
     */
    private val afterLoading = ArrayDeque<Triple<BotAction, BotAim?, (Result<BotActResult>) -> Unit>>()

    /** The client's counter for actions the server acknowledges (using items, breaking blocks). */
    private var sequence = 0

    /** What it's busy doing over several ticks (walking, mining, waiting for a teleport), if anything. */
    private var task: Task? = null

    // ---- joining ---------------------------------------------------------------

    /** Starts the connection where a client's login ends: the configuration phase. */
    fun start() {
        connection.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND)
        val listener = ServerConfigurationPacketListenerImpl(server, connection, CommonListenerCookie.createInitial(profile, false))
        connection.setupInboundProtocol(ConfigurationProtocols.SERVERBOUND, listener)
        // What a client says first, before the server asks anything.
        deliver { ServerboundCustomPayloadPacket(BrandPayload("vanilla")).handle(listener) }
        deliver {
            net.minecraft.network.protocol.common.ServerboundClientInformationPacket(ClientInformation.createDefault()).handle(listener)
        }
        listener.startConfiguration()
    }

    private val game: ServerGamePacketListenerImpl? get() = connection.packetListener as? ServerGamePacketListenerImpl

    private val player: ServerPlayer? get() = game?.player

    val joined: Boolean get() = onJoined == null && !gone

    // ---- the tick ------------------------------------------------------------------

    /**
     * One tick: the server ticks the connection (as it does every player's),
     * then the client reads what arrived, acts and moves. That order is a
     * real client's too: its answers reach a server that has ticked since it
     * sent what they answer.
     */
    fun tick() {
        ticks++
        connection.tick()
        connection.drain()
        if (!connection.isConnected && !gone) {
            // Refused while logging in (banned, not whitelisted, full) closes the connection with no packet for the bot.
            connection.disconnectionDetails?.reason()?.getString()?.takeIf { it.isNotEmpty() }?.let {
                if (goneReason == "it disconnected") goneReason = "the server disconnected it: $it"
            }
            disconnected()
        }
        if (onJoined != null && ticks > JOIN_TICKS) {
            goneReason = "it didn't get into the world within ${JOIN_TICKS / 20} seconds"
            connection.disconnect(Component.literal("Took too long to join"))
            connection.handleDisconnection()
            disconnected()
        }
        if (gone) return

        while (true) (handedBack.poll() ?: break).invoke()
        // Only what had arrived: what the server sends back while the bot answers reaches it a tick later, as over a network.
        repeat(inbox.size) { receive(inbox.poll() ?: return@repeat) }

        val game = game ?: return
        val at = position
        if (loadedAt == null && at != null && Protocol.chunk(BlockPos.containing(at)) in chunks) {
            // The terrain it stands in has arrived: the client stops showing "Loading terrain" and says so.
            loadedAt = ticks
            deliver { ServerboundPlayerLoadedPacket().handle(game) }
            while (true) {
                val (action, aim, done) = afterLoading.removeFirstOrNull() ?: break
                try {
                    act(action, aim, done)
                } catch (e: Exception) {
                    done(Result.failure(e))
                }
            }
        }
        val joining = onJoined
        // In once it's placed and its first terrain has had a tick to bring the entities standing in it.
        if (joining != null && position != null && loadedAt?.let { ticks - it >= ENTITIES_TICKS } == true) {
            onJoined = null
            joining(Result.success(info()))
        }
        if (position != null && !dead) {
            task?.let { running ->
                val outcome = running.tick() ?: return@let
                task = null
                running.finish(outcome)
            }
            step(game)
        }
        sendInput(game)
        deliver { ServerboundClientTickEndPacket.INSTANCE.handle(game) }
    }

    /** Quits, as a player closing the game does. */
    fun leave() {
        if (gone) return
        goneReason = "it left"
        connection.disconnect(Component.translatable("multiplayer.status.quitting"))
        connection.handleDisconnection()
        disconnected()
    }

    private fun disconnected() {
        gone = true
        onJoined?.let {
            onJoined = null
            it(Result.failure(IllegalStateException("$name couldn't join: $goneReason")))
        }
        task?.let {
            task = null
            it.finish(Result.failure(IllegalStateException("$name is gone: $goneReason")))
        }
        while (true) {
            val (_, _, done) = afterLoading.removeFirstOrNull() ?: break
            done(Result.failure(IllegalStateException("$name is gone: $goneReason")))
        }
    }

    // ---- what the server sends -------------------------------------------------------

    private fun receive(packet: Packet<*>) {
        when (packet) {
            is ClientboundKeepAlivePacket -> common { ServerboundKeepAlivePacket(packet.id).handle(it) }
            is ClientboundPingPacket -> common { ServerboundPongPacket(packet.id).handle(it) }
            is ClientboundCookieRequestPacket -> common { ServerboundCookieResponsePacket(packet.key(), null).handle(it) }
            is ClientboundDisconnectPacket -> {
                goneReason = "the server disconnected it: ${packet.reason().getString()}"
                events.add { BotEvent.Kicked(it, packet.reason().getString()) }
            }
            is ClientboundResourcePackPushPacket -> offered(packet)
            is ClientboundResourcePackPopPacket -> packet.id().ifPresentOrElse({ screen.packs.remove(it) }, { screen.packs.clear() })
            is ClientboundShowDialogPacket -> {
                screen.dialog = packet.dialog().value()
                events.add { BotEvent.Dialog(it, BotDialogs.summary(packet.dialog().value())) }
            }
            is ClientboundClearDialogPacket -> if (screen.dialog != null) {
                screen.dialog = null
                events.add { BotEvent.DialogClose(it) }
            }

            // Configuration: claim to know every pack (as the vanilla client does), agree, and go on.
            is ClientboundSelectKnownPacks -> configuration { ServerboundSelectKnownPacks(packet.knownPacks()).handle(it) }
            is ClientboundCodeOfConductPacket -> configuration { ServerboundAcceptCodeOfConductPacket().handle(it) }
            is ClientboundFinishConfigurationPacket -> configuration {
                ServerboundFinishConfigurationPacket.INSTANCE.handle(it)
                if (joinAt != null) player?.bukkitEntity?.teleport(joinAt)
            }
            is ClientboundStartConfigurationPacket -> gameOnly { ServerboundConfigurationAcknowledgedPacket.INSTANCE.handle(it) }

            is ClientboundPlayerPositionPacket -> teleported(packet)
            is ClientboundPlayerRotationPacket -> {
                yaw = if (packet.relativeY()) yaw + packet.yRot() else packet.yRot()
                pitch = if (packet.relativeX()) pitch + packet.xRot() else packet.xRot()
            }
            is ClientboundSetEntityMotionPacket -> Protocol.motion(packet).let { (id, movement) ->
                if (id ==
                    player?.id
                ) {
                    velocity = movement
                }
            }
            is ClientboundChunkBatchFinishedPacket -> gameOnly { ServerboundChunkBatchReceivedPacket(CHUNKS_PER_TICK).handle(it) }
            is ClientboundLevelChunkWithLightPacket -> chunks += Protocol.chunk(packet)
            is ClientboundForgetLevelChunkPacket -> chunks -= Protocol.chunk(packet)
            is ClientboundPlayerCombatKillPacket -> if (packet.playerId() == player?.id) {
                dead = true
                walking = null
                task?.let {
                    task = null
                    it.finish(Result.failure(IllegalStateException("$name died")))
                }
                events.add { BotEvent.Death(it, packet.message().getString()) }
            }
            is ClientboundRespawnPacket -> {
                dead = false
                // The client waits for the server to place it again.
                position = null
                velocity = Vec3.ZERO
                screen.entities.clear()
                screen.menu = null
                val world = player?.level()?.world?.name.orEmpty()
                events.add { BotEvent.Respawn(it, world) }
                // Loading again, as at joining.
                loadedAt = null
                chunks.clear()
            }

            is ClientboundSystemChatPacket -> if (packet.overlay()) actionBar(packet.content()) else chat(packet.content())
            is ClientboundPlayerChatPacket ->
                chat(packet.chatType().decorate(Protocol.unsignedContent(packet) ?: Component.literal(packet.body().content())))
            is ClientboundDisguisedChatPacket -> chat(packet.chatType().decorate(packet.message()))
            is ClientboundSetActionBarTextPacket -> actionBar(packet.text())
            is ClientboundSetTitleTextPacket -> {
                screen.showTitle(packet.text(), ticks)
                events.add { BotEvent.Title(it, packet.text().getString()) }
            }
            is ClientboundSetSubtitleTextPacket -> {
                screen.showSubtitle(packet.text(), ticks)
                events.add { BotEvent.Subtitle(it, packet.text().getString()) }
            }
            is ClientboundSetTitlesAnimationPacket -> screen.titleTimes(packet.fadeIn, packet.stay, packet.fadeOut)
            is ClientboundClearTitlesPacket -> screen.clearTitles(packet.shouldResetTimes())

            is ClientboundOpenScreenPacket -> {
                // A new screen replaces a dialog on screen.
                screen.dialog = null
                screen.menu = BotScreen.Container(packet.containerId, packet.type, packet.title.getString())
                events.add {
                    BotEvent.MenuOpen(it, menu = BuiltInRegistries.MENU.getKey(packet.type).toString(), title = packet.title.getString())
                }
            }
            is ClientboundContainerClosePacket -> if (screen.menu?.id == packet.containerId) {
                screen.menu = null
                screen.cursor = net.minecraft.world.item.ItemStack.EMPTY
                events.add { BotEvent.MenuClose(it) }
            }
            is ClientboundContainerSetContentPacket -> screen.container(packet.containerId())?.let {
                it.stateId = packet.stateId()
                it.items = packet.items().map { stack -> stack.copy() }
                screen.cursor = packet.carriedItem().copy()
                if (it === screen.menu) screen.shareInventory()
            }
            is ClientboundContainerSetSlotPacket -> screen.container(packet.containerId)?.let {
                it.stateId = packet.stateId
                if (packet.slot in it.items.indices) it.items = it.items.toMutableList().apply { set(packet.slot, packet.item.copy()) }
                if (it === screen.menu) screen.shareInventory()
            }
            is ClientboundSetCursorItemPacket -> screen.cursor = packet.contents().copy()
            // One slot of the player's own inventory, by its index there: the hotbar and the main inventory are where
            // the inventory screen draws them.
            is ClientboundSetPlayerInventoryPacket -> {
                val slot = when (packet.slot()) {
                    in 0..8 -> InventoryMenu.USE_ROW_SLOT_START + packet.slot()
                    in InventoryMenu.INV_SLOT_START until InventoryMenu.INV_SLOT_END -> packet.slot()
                    else -> null
                }
                val items = screen.inventory.items
                if (slot != null && slot in items.indices) {
                    screen.inventory.items = items.toMutableList().apply { set(slot, packet.contents().copy()) }
                }
            }

            is ClientboundBossEventPacket -> screen.bossBar(packet) { name, shown -> events.add { BotEvent.BossBar(it, name, shown) } }
            is ClientboundSetObjectivePacket -> screen.objective(packet)
            is ClientboundSetDisplayObjectivePacket -> screen.displayObjective(packet)
            is ClientboundSetScorePacket -> screen.score(packet)
            is ClientboundResetScorePacket -> screen.resetScore(packet)
            is ClientboundSetPlayerTeamPacket -> screen.team(packet)
            is ClientboundPlayerInfoUpdatePacket -> screen.playerInfo(packet)
            is ClientboundPlayerInfoRemovePacket -> screen.playerInfoRemove(packet)

            is ClientboundSoundPacket -> sound(
                packet.sound.value().location().toString(),
                Vec3(packet.x, packet.y, packet.z),
                packet.volume,
                packet.pitch
            )
            is ClientboundSoundEntityPacket -> player?.level()?.getEntity(packet.id)?.let {
                sound(packet.sound.value().location().toString(), it.position(), packet.volume, packet.pitch)
            }
            is ClientboundStopSoundPacket -> events.add { BotEvent.StopSound(it, packet.name?.toString()) }
            is ClientboundLevelParticlesPacket -> {
                val particle = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.particle.type).toString()
                val (speed, forced) = Protocol.particleMotion(packet)
                events.add {
                    BotEvent.Particle(
                        it,
                        particle,
                        packet.x,
                        packet.y,
                        packet.z,
                        packet.count,
                        packet.xDist.toDouble(),
                        packet.yDist.toDouble(),
                        packet.zDist.toDouble(),
                        speed.toDouble(),
                        forced
                    )
                }
            }
            is ClientboundAddEntityPacket -> screen.entities[packet.id] = packet.uuid to packet.type
            is ClientboundRemoveEntitiesPacket -> Protocol.removed(packet).forEach { screen.entities.remove(it) }

            is ClientboundBlockUpdatePacket -> blockChanged(packet.pos, packet.blockState)
            is ClientboundSectionBlocksUpdatePacket -> packet.runUpdates(::blockChanged)
            is ClientboundSetEquipmentPacket -> screen.entities[packet.entity]?.let { (uuid, _) ->
                for (slot in packet.slots) {
                    val item = slot.second.takeUnless { it.isEmpty }?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }
                    events.add { BotEvent.Equipment(it, uuid.toString(), slotName(slot.first), item) }
                }
            }
            is ClientboundOpenBookPacket -> bookOpened()
            is ClientboundSetCameraPacket -> {
                val self = player
                val target = self?.level()?.let(packet::getEntity)
                events.add { BotEvent.Camera(it, target?.takeIf { entity -> entity !== self }?.uuid?.toString()) }
            }
        }
    }

    private fun blockChanged(pos: BlockPos, state: BlockState) {
        val text = BlockStateParser.serialize(state)
        events.add { BotEvent.BlockChange(it, pos.x, pos.y, pos.z, text) }
    }

    /** An equipment slot as scripts name it. */
    private fun slotName(slot: EquipmentSlot) = when (slot) {
        EquipmentSlot.MAINHAND -> "main_hand"
        EquipmentSlot.OFFHAND -> "off_hand"
        else -> slot.serializedName
    }

    /**
     * The client opens the book in its hand, which the server has just put
     * there for it (and puts back what was there next): the held hotbar
     * slot of the inventory screen.
     */
    private fun bookOpened() {
        val held = player?.inventory?.selectedSlot ?: return
        val stack = screen.inventory.items.getOrNull(InventoryMenu.USE_ROW_SLOT_START + held)
        val pages = stack?.get(DataComponents.WRITTEN_BOOK_CONTENT)?.getPages(false).orEmpty().map { it.string }
        events.add { BotEvent.BookOpen(it, pages) }
    }

    private fun chat(text: Component) {
        events.add { BotEvent.Chat(it, text.getString()) }
    }

    private fun actionBar(text: Component) {
        screen.showActionBar(text, ticks)
        events.add { BotEvent.ActionBar(it, text.getString()) }
    }

    private fun sound(sound: String, at: Vec3, volume: Float, pitch: Float) {
        events.add { BotEvent.Sound(it, sound, at.x, at.y, at.z, volume.toDouble(), pitch.toDouble()) }
    }

    private fun teleported(packet: ClientboundPlayerPositionPacket) {
        val game = game ?: return
        val placed = position != null
        val next = PositionMoveRotation.calculateAbsolute(
            PositionMoveRotation(position ?: Vec3.ZERO, velocity, yaw, pitch),
            packet.change(),
            packet.relatives()
        )
        position = next.position()
        velocity = next.deltaMovement()
        yaw = next.yRot()
        pitch = next.xRot()
        onGround = false
        deliver { Protocol.acceptTeleport(packet.id(), next).handle(game) }
        sentPosition = position
        sentYaw = yaw
        sentPitch = pitch
        sentOnGround = false
        if (placed && joined) events.add { BotEvent.Teleport(it, next.position().x, next.position().y, next.position().z) }
    }

    /** Answers a resource pack offer the way [packAnswer] says, downloading and checking it when it accepts. */
    private fun offered(packet: ClientboundResourcePackPushPacket) {
        val id = packet.id()
        fun answer(action: ServerboundResourcePackPacket.Action) {
            screen.packs[id] = BotPack(id.toString(), packet.url(), packet.hash(), action.name.lowercase())
            common { ServerboundResourcePackPacket(id, action).handle(it) }
        }
        fun recorded() = events.add { BotEvent.ResourcePack(it, id.toString(), packet.url(), screen.packs[id]?.status ?: "offered") }
        screen.packs[id] = BotPack(id.toString(), packet.url(), packet.hash(), "offered")
        when (packAnswer) {
            BotPackAnswer.IGNORE -> {}
            BotPackAnswer.LOADED -> {
                answer(ServerboundResourcePackPacket.Action.ACCEPTED)
                answer(ServerboundResourcePackPacket.Action.DOWNLOADED)
                answer(ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED)
            }
            BotPackAnswer.DECLINE -> answer(ServerboundResourcePackPacket.Action.DECLINED)
            BotPackAnswer.FAIL -> {
                answer(ServerboundResourcePackPacket.Action.ACCEPTED)
                answer(ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD)
            }
            BotPackAnswer.ACCEPT -> {
                answer(ServerboundResourcePackPacket.Action.ACCEPTED)
                Bukkit.getScheduler().runTaskAsynchronously(
                    plugin,
                    Runnable {
                        val loaded = runCatching { download(packet.url(), packet.hash()) }
                        handedBack += {
                            loaded.exceptionOrNull()?.let {
                                plugin.logger.info("Bot $name couldn't load resource pack ${packet.url()}: ${it.message}")
                            }
                            if (!gone) {
                                if (loaded.isSuccess) {
                                    answer(ServerboundResourcePackPacket.Action.DOWNLOADED)
                                    answer(ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED)
                                } else {
                                    answer(ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD)
                                }
                                recorded()
                            }
                        }
                    }
                )
            }
        }
        recorded()
    }

    /** Fetches a pack as the client does, and checks it's the one offered. */
    private fun download(url: String, hash: String) {
        val response = HTTP.send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "NetherForge bot").build(),
            HttpResponse.BodyHandlers.ofByteArray()
        )
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
        if (hash.isNotEmpty()) {
            val actual = MessageDigest.getInstance("SHA-1").digest(response.body()).joinToString("") { "%02x".format(it) }
            check(actual.equals(hash, ignoreCase = true)) { "its SHA-1 is $actual, not the $hash offered" }
        }
    }

    // ---- moving, as a client does ----------------------------------------------------

    /**
     * One step of the client's movement: falling, walking ([walking]),
     * jumping (and jumping by itself when walking into a step, as auto-jump
     * does), collided with the world as the server will check it, then
     * reported the way a client reports it.
     */
    private fun step(game: ServerGamePacketListenerImpl) {
        val player = game.player
        val from = position ?: return
        val walk = walking
        walking = null
        val flying = player.abilities.flying
        val inLiquid = player.isInWater || player.isInLava
        val jumping = jump
        var vy = velocity.y
        when {
            flying -> vy = 0.0
            inLiquid -> vy = if (jumping || (walk != null && horizontalCollision)) SWIM_UP else vy * LIQUID_DRAG - LIQUID_SINK
            onGround && jumping -> vy = JUMP
            else -> vy = (vy - GRAVITY) * AIR_DRAG
        }
        if (onGround || inLiquid) jump = false
        val horizontal = when {
            walk != null -> if (inLiquid) walk.normalize().scale(min(SWIM_SPEED, walk.length())) else walk
            onGround -> Vec3.ZERO
            else -> Vec3(velocity.x * AIR_DRAG_HORIZONTAL, 0.0, velocity.z * AIR_DRAG_HORIZONTAL)
        }
        val movement = Vec3(horizontal.x, vy, horizontal.z)
        val box = player.getDimensions(player.pose).makeBoundingBox(from)
        val moved = Entity.collideBoundingBox(player, movement, box, player.level(), listOf())
        horizontalCollision = !same(moved.x, movement.x) || !same(moved.z, movement.z)
        val vertical = !same(moved.y, movement.y)
        onGround = vertical && movement.y < 0
        velocity = Vec3(moved.x, if (vertical) 0.0 else vy, moved.z)
        position = from.add(moved)
        if (walk != null && horizontalCollision && onGround) jump = true
        sendMove(game)
    }

    /** Reports position, facing and footing when they changed, and the position at least once a second, as the client does. */
    private fun sendMove(game: ServerGamePacketListenerImpl) {
        val at = position ?: return
        val moved = sentPosition?.let { at.distanceToSqr(it) > MOVE_EPSILON } ?: true || ++sinceSent >= POSITION_REMINDER
        val turned = yaw != sentYaw || pitch != sentPitch
        val packet = when {
            moved && turned -> ServerboundMovePlayerPacket.PosRot(at, yaw, pitch, onGround, horizontalCollision)
            moved -> ServerboundMovePlayerPacket.Pos(at, onGround, horizontalCollision)
            turned -> ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, horizontalCollision)
            onGround != sentOnGround || horizontalCollision != sentHorizontalCollision ->
                ServerboundMovePlayerPacket.StatusOnly(onGround, horizontalCollision)
            else -> return
        }
        if (moved) {
            sentPosition = at
            sinceSent = 0
        }
        sentYaw = yaw
        sentPitch = pitch
        sentOnGround = onGround
        sentHorizontalCollision = horizontalCollision
        deliver { packet.handle(game) }
    }

    /** Sends the keys held when they changed (walking, jumping, sneaking, sprinting). */
    private fun sendInput(game: ServerGamePacketListenerImpl) {
        val input = Input(task is Walk, false, false, false, jump, sneaking, sprinting)
        if (input == sentInput) return
        sentInput = input
        deliver { ServerboundPlayerInputPacket(input).handle(game) }
    }

    /** Turns to face [point] from the eyes, and tells the server now (a click right after must be aimed). */
    private fun lookAt(point: Vec3, game: ServerGamePacketListenerImpl) {
        val eye = eye(game.player)
        val dx = point.x - eye.x
        val dy = point.y - eye.y
        val dz = point.z - eye.z
        yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        pitch = Math.toDegrees(-atan2(dy, hypot(dx, dz))).toFloat()
        turned(game)
    }

    private fun turned(game: ServerGamePacketListenerImpl) {
        sentYaw = yaw
        sentPitch = pitch
        deliver { ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, horizontalCollision).handle(game) }
    }

    private fun eye(player: ServerPlayer): Vec3 = (position ?: player.position()).add(0.0, player.eyeHeight.toDouble(), 0.0)

    private fun facing(): Vec3 = Vec3.directionFromRotation(pitch, yaw)

    // ---- doing things ------------------------------------------------------------------

    /**
     * Does [action]; [done] is called on the main thread when it's done.
     * Throws (before calling [done]) when it can't be done at all.
     */
    fun act(action: BotAction, aim: BotAim?, done: (Result<BotActResult>) -> Unit) {
        val game = game?.takeIf { joined } ?: throw IllegalStateException("$name is still joining")
        if (loadedAt == null && action !is BotAction.Stop) {
            afterLoading += Triple(action, aim, done)
            return
        }
        val player = game.player
        val since = events.next
        fun finished() = done(Result.success(result(since)))
        fun alive() = check(!dead) { "$name is dead; respawn first" }
        when (action) {
            is BotAction.Teleport -> {
                val world = action.world?.let { Bukkit.getWorld(it) ?: throw IllegalArgumentException("no world \"$it\"") }
                    ?: player.level().world
                val to = Location(
                    world,
                    action.x,
                    action.y,
                    action.z,
                    (action.yaw ?: yaw.toDouble()).toFloat(),
                    (
                        action.pitch
                            ?: pitch.toDouble()
                        ).toFloat()
                )
                check(player.bukkitEntity.teleport(to)) { "the server refused to teleport $name" }
                start(Arrive(to, done, since))
            }
            is BotAction.Look -> {
                yaw = action.yaw.toFloat()
                pitch = action.pitch.toFloat().coerceIn(-90f, 90f)
                turned(game)
                finished()
            }
            is BotAction.LookAt -> {
                lookAt(Vec3(action.x, action.y, action.z), game)
                finished()
            }
            is BotAction.WalkTo -> {
                alive()
                require(action.timeoutTicks in 1..BotAction.MAX_TICKS) { "timeoutTicks is from 1 to ${BotAction.MAX_TICKS}" }
                start(Walk(action.x, action.z, action.sprint, action.timeoutTicks, done, since))
            }
            is BotAction.Jump -> {
                alive()
                jump = true
                finished()
            }
            is BotAction.Sneak -> {
                sneaking = action.on
                sendInput(game)
                finished()
            }
            is BotAction.Sprint -> {
                sprint(action.on, game)
                finished()
            }
            is BotAction.Chat -> {
                // As a client without a chat signing key sends it: unsigned, through Paper's chat (AsyncChatEvent and all).
                player.bukkitEntity.chat(action.message)
                finished()
            }
            is BotAction.Command -> {
                deliver { ServerboundChatCommandPacket(action.line.trim().removePrefix("/")).handle(game) }
                finished()
            }
            is BotAction.Interact -> {
                alive()
                val (target, at) = target(action.entity, aim, game)
                val hit = aimed(target, action.entity == null && aim == null, action.at?.let { Vec3(it.x, it.y, it.z) } ?: at, game)
                val hand = hand(action.hand)
                for (sent in Protocol.interact(target, hand, hit.subtract(target.position()), sneaking)) deliver { sent.handle(game) }
                finished()
            }
            is BotAction.Attack -> {
                alive()
                val (target, at) = target(action.entity, aim, game)
                aimed(target, action.entity == null && aim == null, action.at?.let { Vec3(it.x, it.y, it.z) } ?: at, game)
                deliver { Protocol.attack(target, sneaking).handle(game) }
                deliver { Protocol.swing().handle(game) }
                finished()
            }
            is BotAction.Swing -> {
                deliver { Protocol.swing().handle(game) }
                finished()
            }
            is BotAction.UseItem -> {
                alive()
                deliver { ServerboundUseItemPacket(hand(action.hand), ++sequence, yaw, pitch).handle(game) }
                finished()
            }
            is BotAction.ReleaseItem -> {
                alive()
                deliver {
                    ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN)
                        .handle(game)
                }
                finished()
            }
            is BotAction.Fly -> {
                alive()
                // The client says only whether it's flying; the server checks it may.
                val abilities = Abilities().apply { flying = action.on }
                deliver { ServerboundPlayerAbilitiesPacket(abilities).handle(game) }
                finished()
            }
            is BotAction.EditSign -> {
                alive()
                require(action.lines.size <= SIGN_LINES) { "a sign has $SIGN_LINES lines" }
                val lines = action.lines + List(SIGN_LINES - action.lines.size) { "" }
                deliver { Protocol.signUpdate(BlockPos(action.x, action.y, action.z), action.front, lines).handle(game) }
                finished()
            }
            is BotAction.ClickButton -> {
                val menu = screen.menu ?: throw IllegalStateException("$name has no menu open")
                deliver { ServerboundContainerButtonClickPacket(menu.id, action.button).handle(game) }
                finished()
            }
            is BotAction.UseBlock -> {
                alive()
                val (pos, face, hit) = face(action.x, action.y, action.z, action.face, game)
                deliver { ServerboundUseItemOnPacket(hand(action.hand), BlockHitResult(hit, face, pos, false), ++sequence).handle(game) }
                finished()
            }
            is BotAction.BreakBlock -> {
                alive()
                val (pos, face, _) = face(action.x, action.y, action.z, action.face, game)
                require(!player.level().getBlockState(pos).isAir) { "there's no block at ${pos.x} ${pos.y} ${pos.z} to break" }
                start(Mine(pos, face, done, since))
            }
            is BotAction.PickBlock -> {
                alive()
                val pos = BlockPos(action.x, action.y, action.z)
                deliver { ServerboundPickItemFromBlockPacket(pos, false).handle(game) }
                finished()
            }
            is BotAction.SelectSlot -> {
                require(action.slot in 0..8) { "a hotbar slot is 0 to 8" }
                deliver { ServerboundSetCarriedItemPacket(action.slot).handle(game) }
                finished()
            }
            is BotAction.SwapHands -> {
                alive()
                deliver {
                    ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                        BlockPos.ZERO,
                        Direction.DOWN
                    ).handle(game)
                }
                finished()
            }
            is BotAction.Drop -> {
                alive()
                val drop = if (action.all) {
                    ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
                } else {
                    ServerboundPlayerActionPacket.Action.DROP_ITEM
                }
                deliver { ServerboundPlayerActionPacket(drop, BlockPos.ZERO, Direction.DOWN).handle(game) }
                finished()
            }
            is BotAction.ClickSlot -> {
                val container = screen.clicked()
                require(action.slot in container.items.indices) {
                    "slot ${action.slot} isn't in ${if (screen.menu != null) "the open menu" else "the inventory"} (slots 0 to ${container.items.size - 1})"
                }
                val (button, input) = when (action.click) {
                    BotClick.LEFT -> 0 to ClickKind.PICKUP
                    BotClick.RIGHT -> 1 to ClickKind.PICKUP
                    BotClick.SHIFT_LEFT -> 0 to ClickKind.QUICK_MOVE
                    BotClick.SHIFT_RIGHT -> 1 to ClickKind.QUICK_MOVE
                    BotClick.MIDDLE -> 2 to ClickKind.CLONE
                    BotClick.DROP -> 0 to ClickKind.THROW
                    BotClick.DROP_ALL -> 1 to ClickKind.THROW
                    BotClick.DOUBLE -> 0 to ClickKind.PICKUP_ALL
                    BotClick.HOTBAR -> (
                        action.hotbar?.takeIf { it in 0..8 }
                            ?: throw IllegalArgumentException("a hotbar click needs `hotbar`, 0 to 8")
                        ) to
                        ClickKind.SWAP
                    BotClick.OFF_HAND -> OFF_HAND_BUTTON to ClickKind.SWAP
                }
                // A double click is a click first.
                if (action.click == BotClick.DOUBLE) click(container, action.slot, 0, ClickKind.PICKUP, game)
                click(container, action.slot, button, input, game)
                finished()
            }
            is BotAction.Drag -> {
                val container = screen.clicked()
                require(action.slots.isNotEmpty() && action.slots.all { it in container.items.indices }) {
                    "drag over slots of the open menu (0 to ${container.items.size - 1})"
                }
                // The client's three stages of a drag: start, each slot, end.
                val type = if (action.button == BotButton.LEFT) 0 else 1
                click(container, OUTSIDE, type shl 2, ClickKind.QUICK_CRAFT, game)
                for (slot in action.slots) click(container, slot, (type shl 2) or 1, ClickKind.QUICK_CRAFT, game)
                click(container, OUTSIDE, (type shl 2) or 2, ClickKind.QUICK_CRAFT, game)
                finished()
            }
            is BotAction.CloseMenu -> {
                val menu = screen.menu ?: throw IllegalStateException("$name has no menu open")
                deliver { ServerboundContainerClosePacket(menu.id).handle(game) }
                screen.menu = null
                screen.cursor = net.minecraft.world.item.ItemStack.EMPTY
                finished()
            }
            is BotAction.DialogButton -> {
                val dialog = screen.dialog ?: throw IllegalStateException("$name has no dialog open")
                val buttons = BotDialogs.buttons(dialog)
                val chosen = when {
                    action.index != null -> buttons.getOrNull(action.index!!)
                        ?: throw IllegalArgumentException("the dialog has ${buttons.size} buttons; there's no button ${action.index}")
                    action.button != null -> buttons.firstOrNull { it.first == action.button }
                        ?: buttons.firstOrNull { it.first.equals(action.button, ignoreCase = true) }
                        ?: throw IllegalArgumentException(
                            "the dialog has no button \"${action.button}\"; it has ${buttons.joinToString {
                                "\"${it.first}\""
                            }}"
                        )
                    else -> throw IllegalArgumentException("name the button by `button` (its label) or `index`")
                }
                when (val press = chosen.second) {
                    is BotDialogs.Press.Show -> {
                        screen.dialog = press.dialog
                        events.add { BotEvent.Dialog(it, BotDialogs.summary(press.dialog)) }
                    }
                    is BotDialogs.Press.Run -> {
                        val click = press.action?.let { BotDialogs.click(dialog, it, action.inputs) }
                        if (dialog.common().afterAction() == DialogAction.CLOSE && screen.dialog === dialog) screen.dialog = null
                        click?.let { clicked(it, game) }
                    }
                }
                finished()
            }
            is BotAction.DialogClose -> {
                val dialog = screen.dialog ?: throw IllegalStateException("$name has no dialog open")
                check(dialog.common().canCloseWithEscape()) { "this dialog can't be closed with Escape" }
                screen.dialog = null
                dialog.onCancel().orElse(null)?.let { BotDialogs.click(dialog, it, emptyMap()) }?.let { clicked(it, game) }
                finished()
            }
            is BotAction.Stop -> {
                task?.let {
                    task = null
                    it.finish(Result.failure(IllegalStateException("$name was stopped")))
                }
                finished()
            }
            is BotAction.Respawn -> {
                check(dead) { "$name isn't dead" }
                deliver { ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN).handle(game) }
                finished()
            }
        }
    }

    /** What the client does with a dialog's click event. */
    private fun clicked(click: ClickEvent, game: ServerGamePacketListenerImpl) {
        when (click) {
            is ClickEvent.Custom -> deliver { ServerboundCustomClickActionPacket(click.id(), click.payload()).handle(game) }
            is ClickEvent.RunCommand -> deliver { ServerboundChatCommandPacket(click.command().removePrefix("/")).handle(game) }
            is ClickEvent.ShowDialog -> {
                screen.dialog = click.dialog().value()
                events.add { BotEvent.Dialog(it, BotDialogs.summary(click.dialog().value())) }
            }
            // Opening a link, copying, suggesting a command: nothing the server hears about.
            else -> {}
        }
    }

    private fun click(container: BotScreen.Container, slot: Int, button: Int, kind: ClickKind, game: ServerGamePacketListenerImpl) {
        deliver { Protocol.click(container.id, container.stateId, slot, button, kind).handle(game) }
    }

    private fun sprint(on: Boolean, game: ServerGamePacketListenerImpl) {
        if (sprinting == on) return
        sprinting = on
        val command = if (on) {
            ServerboundPlayerCommandPacket.Action.START_SPRINTING
        } else {
            ServerboundPlayerCommandPacket.Action.STOP_SPRINTING
        }
        deliver { ServerboundPlayerCommandPacket(game.player, command).handle(game) }
        sendInput(game)
    }

    /**
     * The entity to click and the point to aim at: the runtime's [aim] (a
     * centity's node), an entity by UUID, or what the bot is looking at. A
     * client can only click what it has been sent.
     */
    private fun target(uuid: String?, aim: BotAim?, game: ServerGamePacketListenerImpl): Pair<Entity, Vec3?> {
        val level = game.player.level()
        val id =
            aim?.entity
                ?: uuid?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw IllegalArgumentException("\"$it\" isn't a UUID") }
        if (id == null) return inSight(game) to null
        val entity = level.getEntity(id) ?: throw IllegalArgumentException("there's no entity $id in ${level.world.name}")
        check(screen.entities.values.any { it.first == id }) {
            "$name's client doesn't know entity $id (it's hidden from them, or too far away to be sent)"
        }
        return entity to aim?.at?.let { Vec3(it.x, it.y, it.z) }
    }

    /** What the bot is looking at, within reach, as the client picks it: the nearest entity in front of any block. */
    private fun inSight(game: ServerGamePacketListenerImpl): Entity {
        val player = game.player
        val reach = player.entityInteractionRange()
        val eye = eye(player)
        val end = eye.add(facing().scale(reach))
        val block = player.level().clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        val blockDistance = if (block.type == HitResult.Type.MISS) reach else block.location.distanceTo(eye)
        val known = screen.entities.keys
        return known.mapNotNull { player.level().getEntity(it) }
            // What the client's crosshair can land on: not displays, not spectators.
            .filter { it.isPickable && !it.isSpectator }
            .mapNotNull { entity ->
                entity.boundingBox.inflate(entity.pickRadius.toDouble()).clip(eye, end).orElse(null)?.let { entity to it.distanceTo(eye) }
            }
            .filter { it.second <= blockDistance }
            .minByOrNull { it.second }?.first
            ?: throw IllegalStateException("$name isn't looking at anything it could click within reach (${"%.1f".format(reach)} blocks)")
    }

    /**
     * Where a click on [target] lands: with [at] (a point given, or the
     * runtime's aim at a node), turned to it; one picked from the
     * crosshair ([inSight]) with no point, where the line of sight already
     * meets it, without turning (the bot clicks exactly where it looks);
     * otherwise turned to its middle.
     */
    private fun aimed(target: Entity, inSight: Boolean, at: Vec3?, game: ServerGamePacketListenerImpl): Vec3 {
        if (!inSight || at != null) return point(target, at, game)
        val eye = eye(game.player)
        val reach = game.player.entityInteractionRange()
        return target.boundingBox.inflate(
            target.pickRadius.toDouble()
        ).clip(eye, eye.add(facing().scale(reach + 1))).orElse(target.boundingBox.center)
    }

    /** Looks at [target] ([at], or its middle), checks it's in reach, and gives the point on it the click lands. */
    private fun point(target: Entity, at: Vec3?, game: ServerGamePacketListenerImpl): Vec3 {
        val player = game.player
        val box = target.boundingBox
        val aimAt = at ?: box.center
        lookAt(aimAt, game)
        val eye = eye(player)
        val nearest = Vec3(eye.x.coerceIn(box.minX, box.maxX), eye.y.coerceIn(box.minY, box.maxY), eye.z.coerceIn(box.minZ, box.maxZ))
        val distance = nearest.distanceTo(eye)
        val reach = player.entityInteractionRange()
        require(distance <= reach) { "it is ${"%.1f".format(distance)} blocks away; $name reaches ${"%.1f".format(reach)}" }
        return box.clip(eye, eye.add(facing().scale(reach + 1))).orElse(aimAt)
    }

    /** A block face to use or mine: in reach, looked at; its position, direction and middle. */
    private fun face(x: Int, y: Int, z: Int, side: String, game: ServerGamePacketListenerImpl): Triple<BlockPos, Direction, Vec3> {
        val face =
            Direction.byName(side)
                ?: throw IllegalArgumentException("\"$side\" isn't a face; one of ${Direction.entries.joinToString { it.serializedName }}")
        val pos = BlockPos(x, y, z)
        val hit = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.unitVec3i).scale(0.5))
        val player = game.player
        val distance = hit.distanceTo(eye(player))
        val reach = player.blockInteractionRange()
        require(distance <= reach) { "that block is ${"%.1f".format(distance)} blocks away; $name reaches ${"%.1f".format(reach)}" }
        lookAt(hit, game)
        return Triple(pos, face, hit)
    }

    private fun hand(hand: BotHand) = if (hand == BotHand.MAIN) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND

    private fun start(next: Task) {
        task?.finish(Result.failure(IllegalStateException("$name was given something else to do")))
        task = next
    }

    private fun result(since: Int): BotActResult {
        val at = position ?: player?.position() ?: Vec3.ZERO
        return BotActResult(player?.level()?.world?.name.orEmpty(), at.x, at.y, at.z, since)
    }

    /** Something that takes ticks. [tick] runs once a tick before the bot moves: null to go on, else how it ended. */
    private abstract inner class Task(private val done: (Result<BotActResult>) -> Unit, private val since: Int) {
        abstract fun tick(): Result<Unit>?

        open fun ended() {}

        fun finish(outcome: Result<Unit>) {
            ended()
            done(outcome.map { result(since) })
        }
    }

    /** Waits until a teleport has reached the client (its answer then says where it is). */
    private inner class Arrive(private val to: Location, done: (Result<BotActResult>) -> Unit, since: Int) : Task(done, since) {
        private var waited = 0

        override fun tick(): Result<Unit>? {
            val at = position ?: return null
            if (at.distanceToSqr(to.x, to.y, to.z) < ARRIVED * ARRIVED || ++waited > TELEPORT_TICKS) return Result.success(Unit)
            return null
        }
    }

    private inner class Walk(
        private val x: Double,
        private val z: Double,
        private val sprint: Boolean,
        private val limit: Int,
        done: (Result<BotActResult>) -> Unit,
        since: Int
    ) : Task(done, since) {
        private var walked = 0
        private var nearest = Double.MAX_VALUE
        private var nearestAt = 0
        private val wasSprinting = sprinting

        override fun tick(): Result<Unit>? {
            val game = game ?: return null
            val at = position ?: return null
            val dx = x - at.x
            val dz = z - at.z
            val distance = hypot(dx, dz)
            if (distance < ARRIVED) return Result.success(Unit)
            walked++
            if (distance < nearest - PROGRESS) {
                nearest = distance
                nearestAt = walked
            }
            val stuck = walked - nearestAt > STUCK_TICKS
            if (walked > limit || stuck) {
                val why = if (stuck) "stuck" else "out of time after $limit ticks"
                return Result.failure(
                    IllegalStateException(
                        "$name stopped ${"%.1f".format(distance)} blocks short of ${"%.1f".format(x)}, ${"%.1f".format(z)} " +
                            "at ${"%.1f".format(at.x)}, ${"%.1f".format(at.y)}, ${"%.1f".format(at.z)} ($why)"
                    )
                )
            }
            if (walked == 1 && sprint) sprint(true, game)
            yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
            val speed = min(
                if (sprinting) {
                    SPRINT_SPEED
                } else if (sneaking) {
                    SNEAK_SPEED
                } else {
                    WALK_SPEED
                },
                distance
            )
            walking = Vec3(dx / distance * speed, 0.0, dz / distance * speed)
            return null
        }

        override fun ended() {
            game?.let { sprint(wasSprinting, it) }
        }
    }

    /** Mines a block as the client does: start, keep at it for as long as the block takes, stop (or let go of one it can't break). */
    private inner class Mine(private val pos: BlockPos, private val face: Direction, done: (Result<BotActResult>) -> Unit, since: Int) :
        Task(done, since) {
        private var progress = 0f
        private var started = false
        private var stopped = 0

        override fun tick(): Result<Unit>? {
            val game = game ?: return null
            val player = game.player
            val state = player.level().getBlockState(pos)
            if (state.isAir) return Result.success(Unit)
            if (stopped > 0) {
                return if (++stopped > BREAK_GRACE) {
                    Result.failure(IllegalStateException("the server didn't let $name break the block at ${pos.x} ${pos.y} ${pos.z}"))
                } else {
                    null
                }
            }
            if (!started) {
                started = true
                deliver {
                    ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        pos,
                        face,
                        ++sequence
                    ).handle(game)
                }
                // Creative breaks on the first hit; so does a block soft enough for the tool.
                if (player.isCreative || state.getDestroyProgress(player, player.level(), pos) >= 1f) stopped = 1
                return null
            }
            val step = state.getDestroyProgress(player, player.level(), pos)
            if (step <= 0f) {
                // It gives up, and lets go as a player does.
                deliver {
                    ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                        pos,
                        face,
                        ++sequence
                    ).handle(game)
                }
                return Result.failure(IllegalStateException("$name can't break the block at ${pos.x} ${pos.y} ${pos.z}"))
            }
            progress += step
            if (progress >= 1f) {
                deliver {
                    ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        pos,
                        face,
                        ++sequence
                    ).handle(game)
                }
                stopped = 1
            }
            return null
        }
    }

    // ---- what it shows ---------------------------------------------------------------------

    fun info(): BotInfo {
        val at = position ?: player?.position() ?: Vec3.ZERO
        return BotInfo(name, uuid.toString(), player?.level()?.world?.name.orEmpty(), at.x, at.y, at.z, events.next)
    }

    fun state(): BotState {
        val player = player?.takeIf { joined } ?: throw IllegalStateException("$name is still joining")
        val at = position ?: player.position()
        val inventory = player.inventory
        val level = player.level()
        return BotState(
            name = name,
            uuid = uuid.toString(),
            world = level.world.name,
            x = at.x,
            y = at.y,
            z = at.z,
            yaw = yaw.toDouble(),
            pitch = pitch.toDouble(),
            onGround = onGround,
            health = player.health.toDouble(),
            food = player.foodData.foodLevel,
            gameMode = player.gameMode.gameModeForPlayer.serializedName,
            dead = dead,
            sneaking = player.isShiftKeyDown,
            sprinting = player.isSprinting,
            selectedSlot = inventory.selectedSlot,
            inventory = (0 until inventory.containerSize).mapNotNull { BotScreen.item(it, screen.shown(it) ?: inventory.getItem(it)) },
            menu = screen.menu(),
            dialog = screen.dialog?.let(BotDialogs::summary),
            bossBars = screen.bossBars.values.toList(),
            sidebar = screen.sidebar(),
            title = screen.title(ticks),
            subtitle = screen.subtitle(ticks),
            actionBar = screen.actionBar(ticks),
            resourcePacks = screen.packs.values.toList(),
            teams = screen.teams(),
            playerList = screen.playerList(),
            entities = screen.entities.entries
                .mapNotNull { (id, known) -> level.getEntity(id)?.let { Triple(known, it, it.position().distanceTo(at)) } }
                .filter { it.third <= NEARBY }
                .sortedBy { it.third }
                .take(MAX_NEARBY)
                .map { (known, entity, distance) ->
                    BotEntity(
                        known.first.toString(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(known.second).toString(),
                        entity.x,
                        entity.y,
                        entity.z,
                        distance
                    )
                },
            target = sight(player),
            events = events.next
        )
    }

    /**
     * What the crosshair is on, as the client picks it (and [inSight] does for a click): the nearest
     * entity it could click within entity reach, in front of the nearest block within block reach.
     */
    private fun sight(player: ServerPlayer): BotTarget? {
        val level = player.level()
        val eye = eye(player)
        val look = facing()
        val blockReach = player.blockInteractionRange()
        val block = level.clip(ClipContext(eye, eye.add(look.scale(blockReach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        val blockDistance = if (block.type == HitResult.Type.MISS) Double.MAX_VALUE else block.location.distanceTo(eye)
        val entityEnd = eye.add(look.scale(player.entityInteractionRange()))
        val entity = screen.entities.keys.mapNotNull { level.getEntity(it) }
            .filter { it.isPickable && !it.isSpectator }
            .mapNotNull { e ->
                e.boundingBox.inflate(e.pickRadius.toDouble()).clip(eye, entityEnd).orElse(null)?.let { Triple(e, it, it.distanceTo(eye)) }
            }
            .filter { it.third <= blockDistance }
            .minByOrNull { it.third }
        if (entity != null) {
            val (e, at, distance) = entity
            return BotTarget(
                BotTargetKind.ENTITY,
                at.x,
                at.y,
                at.z,
                distance,
                entity = e.uuid.toString(),
                type = BuiltInRegistries.ENTITY_TYPE.getKey(e.type).toString()
            )
        }
        if (block.type == HitResult.Type.MISS) return null
        val pos = block.blockPos
        return BotTarget(
            BotTargetKind.BLOCK, block.location.x, block.location.y, block.location.z, blockDistance,
            blockX = pos.x,
            blockY = pos.y,
            blockZ = pos.z,
            face = block.direction.serializedName,
            state = level.world.getBlockAt(pos.x, pos.y, pos.z).blockData.asString
        )
    }

    fun events(since: Int) = events.since(since)

    // ---- sending -----------------------------------------------------------------------------

    /**
     * Hands one packet to the server's listener, as the connection would
     * after decoding it. A handler that fails is the server's problem with
     * this packet; it's logged, and the bot goes on (a real connection would
     * be dropped, which helps nobody testing).
     */
    private inline fun deliver(handle: () -> Unit) {
        try {
            handle()
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "The server failed handling a packet from bot $name", e)
        }
    }

    private inline fun configuration(handle: (ServerConfigurationPacketListenerImpl) -> Unit) {
        val listener = connection.packetListener as? ServerConfigurationPacketListenerImpl ?: return
        deliver { handle(listener) }
    }

    private inline fun gameOnly(handle: (ServerGamePacketListenerImpl) -> Unit) {
        val listener = game ?: return
        deliver { handle(listener) }
    }

    private inline fun common(handle: (ServerCommonPacketListener) -> Unit) {
        val listener = connection.packetListener as? ServerCommonPacketListener ?: return
        deliver { handle(listener) }
    }

    private fun same(a: Double, b: Double) = abs(a - b) < 1.0E-7

    private companion object {
        val HTTP: HttpClient = HttpClient.newBuilder().followRedirects(
            HttpClient.Redirect.NORMAL
        ).connectTimeout(Duration.ofSeconds(10)).build()

        const val JOIN_TICKS = 400
        const val TELEPORT_TICKS = 40
        const val CHUNKS_PER_TICK = 25f
        const val ENTITIES_TICKS = 2

        // A client's movement, in blocks and ticks.
        const val GRAVITY = 0.08
        const val AIR_DRAG = 0.98
        const val AIR_DRAG_HORIZONTAL = 0.91
        const val JUMP = 0.42
        const val WALK_SPEED = 0.2158
        const val SPRINT_SPEED = 0.2806
        const val SNEAK_SPEED = 0.0647
        const val SWIM_SPEED = 0.1
        const val SWIM_UP = 0.04
        const val LIQUID_DRAG = 0.8
        const val LIQUID_SINK = 0.02
        const val MOVE_EPSILON = 4.0E-8
        const val POSITION_REMINDER = 20

        /** Close enough to where it walked to. */
        const val ARRIVED = 0.3
        const val PROGRESS = 0.01
        const val STUCK_TICKS = 40
        const val BREAK_GRACE = 10

        /** The button a container click uses for "swap with the off hand". */
        const val OFF_HAND_BUTTON = 40

        /** The slot number of a click outside the window. */
        const val OUTSIDE = -999

        const val SIGN_LINES = 4

        const val NEARBY = 16.0
        const val MAX_NEARBY = 50
    }
}
