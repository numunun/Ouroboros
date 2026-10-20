package numunun.ouroboros.game

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import io.papermc.paper.datacomponent.item.ResolvableProfile
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.ui.Messages
import numunun.ouroboros.world.SafeGround
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Mannequin
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.time.Duration
import java.util.UUID

/**
 * 부활 유예.
 *
 * - 이동은 속성(이동 속도·점프 0)으로 막는다. 시선을 서버가 되돌리지 않으므로 마우스가 느려지지 않는다.
 * - 진짜 플레이어는 숨기고, 그 자리에 스킨을 입힌 마네킹을 눕혀 시체로 쓴다. 고개를 돌려도 시체는 그대로다.
 * - 1:1(활동 팀 2개 이하)에서는 유예 없이 바로 부활한다. 죽은 자리는 그대로 쓴다.
 */
class RespawnHoldService(
    private val plugin: Plugin,
    private val game: GameService,
    private val roles: RoleService,
    private val ground: SafeGround,
    private val settings: SettingValues,
) : Listener, GameLifecycleListener {

    private class Hold(var remaining: Int, val anchor: Location, val corpse: Mannequin?)

    private class Pending(val deathLocation: Location, val seconds: Int)

    private val active = HashMap<UUID, Hold>()
    private val pending = HashMap<UUID, Pending>()
    private val parked = HashMap<UUID, Int>()
    private val corpseIds = HashSet<UUID>()

    private var ticker: BukkitTask? = null

    fun isHeld(uuid: UUID): Boolean = uuid in active

    fun isPendingOrHeld(uuid: UUID): Boolean = uuid in pending || uuid in active

    /** 활동 팀이 2개 이하면 1:1 */
    private val isDuel: Boolean get() = (game.session?.activeColors?.size ?: 0) <= 2

    /** 일반 사망. 죽은 자리 근처에서 부활하고, 1:1 이 아니면 유예를 건다. */
    fun reserve(player: Player) {
        val seconds = if (isDuel) 0 else settings[Keys.RESPAWN_DELAY]
        pending[player.uniqueId] = Pending(player.location.clone(), seconds)
    }

    /** 재접속. 나가기 전 남은 유예와 재접속 패널티 중 긴 쪽. 1:1 이면 없음 */
    fun onRejoin(player: Player) {
        val left = parked.remove(player.uniqueId) ?: 0
        val seconds = if (isDuel) 0 else maxOf(left, settings[Keys.REJOIN_PENALTY])
        if (seconds <= 0 || player.isDead) return

        // 나간 자리 그대로. 정말 위험할 때만 옮긴다.
        val here = player.location
        val safe = ground.resolve(here, forecast = false, margin = 0.0)
        if (safe !== here) player.teleport(safe)
        start(player, seconds)
    }

    // ── 게임 흐름 ─────────────────────────────────────────

    override fun onGameStart(session: GameSession) {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
    }

    override fun onGameEnd() {
        ticker?.cancel()
        ticker = null
        active.keys.toList().mapNotNull { Bukkit.getPlayer(it) }.forEach { endHold(it) }
        active.values.forEach { it.corpse?.remove() } // 오프라인이라 위에서 못 지운 시체
        active.clear()
        pending.clear()
        parked.clear()
        corpseIds.clear()
    }

    // ── 부활 ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    fun onRespawn(event: PlayerRespawnEvent) {
        val session = game.session ?: return
        val id = event.player.uniqueId
        if (!session.isMember(id)) return

        val base = pending[id]?.deathLocation ?: event.respawnLocation
        event.respawnLocation = ground.resolve(base)
    }

    @EventHandler
    fun onPostRespawn(event: PlayerPostRespawnEvent) {
        val reserved = pending.remove(event.player.uniqueId) ?: return
        if (reserved.seconds > 0) start(event.player, reserved.seconds)
    }

    // ── 유예 ──────────────────────────────────────────────

    private fun start(player: Player, seconds: Int) {
        val corpse = if (settings[Keys.CORPSE]) spawnCorpse(player) else null
        active[player.uniqueId] = Hold(seconds, player.location.clone(), corpse)

        roles.freeze(player)
        player.isInvulnerable = true
        player.velocity = Vector()
        Bukkit.getOnlinePlayers().filter { it != player }.forEach { it.hidePlayer(plugin, player) }

        player.showTitle(
            Title.title(
                Component.text("사망", NamedTextColor.RED),
                Messages.holdCountdown(seconds),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500)),
            )
        )
        player.sendActionBar(Messages.holdCountdown(seconds))
    }

    private fun spawnCorpse(player: Player): Mannequin? = runCatching {
        player.world.spawn(player.location, Mannequin::class.java) { corpse ->
            corpse.profile = ResolvableProfile.resolvableProfile(player.playerProfile)
            corpse.description = null
            corpse.isImmovable = true
            corpse.isInvulnerable = true
            corpse.isSilent = true
            corpse.isPersistent = false
            corpse.equipment?.let { gear ->
                gear.setHelmet(player.inventory.helmet?.clone())
                gear.setChestplate(player.inventory.chestplate?.clone())
                gear.setLeggings(player.inventory.leggings?.clone())
                gear.setBoots(player.inventory.boots?.clone())
            }
            corpse.setPose(Pose.SLEEPING, true)
        }.also { corpseIds += it.uniqueId }
    }.onFailure {
        plugin.logger.warning("시신을 만들지 못했습니다: ${it.message}")
    }.getOrNull()

    private fun tick() {
        active.entries.toList().forEach { (id, hold) ->
            val player = Bukkit.getPlayer(id) ?: return@forEach
            hold.remaining--
            if (hold.remaining <= 0) release(player) else player.sendActionBar(Messages.holdCountdown(hold.remaining))
        }
    }

    private fun release(player: Player) {
        // 유예 동안 보더가 줄었을 수 있다. 지금 보더 기준으로 위험하면 안쪽으로.
        val here = player.location
        val safe = ground.resolve(here, forecast = false)
        if (safe !== here) player.teleport(safe)

        endHold(player)
        roles.protect(player)
        player.sendMessage(Messages.RESPAWNED)
    }

    private fun endHold(player: Player) {
        val hold = active.remove(player.uniqueId) ?: return
        hold.corpse?.let {
            corpseIds -= it.uniqueId
            it.remove()
        }
        roles.unfreeze(player)
        player.isInvulnerable = false
        Bukkit.getOnlinePlayers().filter { it != player }.forEach { it.showPlayer(plugin, player) }
        player.clearTitle()
    }

    // ── 접속 ──────────────────────────────────────────────

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        active.keys.mapNotNull { Bukkit.getPlayer(it) }.forEach { event.player.hidePlayer(plugin, it) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val hold = active[event.player.uniqueId] ?: return
        parked[event.player.uniqueId] = hold.remaining
        endHold(event.player)
    }

    // ── 동결 ──────────────────────────────────────────────

    /**
     * 속성으로 이미 못 움직이므로 평소엔 아무것도 안 한다.
     * 밀려나는 등으로 옆으로 벗어났을 때만 수평 위치를 되돌린다. 떨어지는 건 막지 않는다.
     */
    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val hold = active[event.player.uniqueId] ?: return
        val to = event.to
        val dx = to.x - hold.anchor.x
        val dz = to.z - hold.anchor.z
        if (dx * dx + dz * dz <= 0.25) return
        event.setTo(to.clone().apply {
            x = hold.anchor.x
            z = hold.anchor.z
        })
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victimHeld = isHeld(event.entity.uniqueId)
        val attackerHeld = event.damageSource.causingEntity?.let { isHeld(it.uniqueId) } == true
        val corpse = event.entity.uniqueId in corpseIds
        if (victimHeld || attackerHeld || corpse) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (isHeld(event.player.uniqueId) || event.rightClicked.uniqueId in corpseIds) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onFood(event: FoodLevelChangeEvent) = cancelIfHeld(event.entity.uniqueId, event)

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) = cancelIfHeld(event.player.uniqueId, event)

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) = cancelIfHeld(event.player.uniqueId, event)

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) = cancelIfHeld(event.player.uniqueId, event)

    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) = cancelIfHeld(event.player.uniqueId, event)

    private fun cancelIfHeld(id: UUID, event: Cancellable) {
        if (isHeld(id)) event.isCancelled = true
    }

    /** 남은 유예(초). 유예 중이 아니면 null. HUD 용 */
    fun remainingSeconds(uuid: UUID): Int? = active[uuid]?.remaining
}