package numunun.ouroboros.feature

import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.AlarmZone
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.core.Pointer
import numunun.ouroboros.core.PortalMemory
import numunun.ouroboros.core.Tracking
import numunun.ouroboros.game.GameLifecycleListener
import numunun.ouroboros.game.GameService
import numunun.ouroboros.game.RespawnHoldService
import numunun.ouroboros.ui.Messages
import numunun.ouroboros.world.toLocation
import numunun.ouroboros.world.toSpot
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.min

/**
 * 추적기. 설정한 아이템을 우클릭하면 하나를 소모하고, 목표 색의 주인 쪽으로
 * 사용자에게만 보이는 파티클 줄을 그린다. 줄은 매번 다시 계산하므로 목표가 움직여도 따라간다.
 *
 * 다른 차원의 목표는 [Tracking.point] 가 포탈이나 환산 위치로 바꿔 준다. (찐빠 1)
 */
class TrackerService(
    private val plugin: Plugin,
    private val game: GameService,
    private val holds: RespawnHoldService,
    private val settings: SettingValues,
) : Listener, GameLifecycleListener {

    private val memory = PortalMemory()
    private val trails = HashMap<UUID, BukkitTask>()

    /** 8단계에서 패킷 구현으로 바꿔 끼운다. */
    var glow: GlowEffect = GlowEffect.NONE

    override fun onGameEnd() {
        trails.values.forEach { it.cancel() }
        trails.clear()
        memory.clear()
    }

    // ── 포탈 기억 ─────────────────────────────────────────

    // PlayerPortalEvent 는 HandlerList 가 따로라서 둘 다 들어야 한다.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) = remember(event.player, event.from, event.to)

    /** 진짜 포탈 이동만 기록한다. /tp 같은 이동을 "포탈" 이라고 안내하면 안 되니까. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (event.cause !in PORTAL_CAUSES) return
        remember(event.player, event.from, event.to)
    }

    private fun remember(player: Player, from: Location, to: Location?) {
        if (game.session?.isMember(player.uniqueId) != true) return
        if (to == null || from.world == to.world) return
        memory.recordExit(player.uniqueId, from.toSpot())
    }

    // ── 사용 ──────────────────────────────────────────────

    // 허공 우클릭은 처음부터 취소된 채로 오므로 ignoreCancelled 를 쓰지 않는다.
    @EventHandler(priority = EventPriority.HIGH)
    fun onUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return
        if (item.type.key.asString() != settings[Keys.TRACKER_ITEM]) return

        val session = game.session ?: return
        val player = event.player
        if (!session.isMember(player.uniqueId)) return

        event.isCancelled = true
        if (holds.isHeld(player.uniqueId)) return
        use(player, session)
    }

    private fun use(player: Player, session: GameSession) {
        val target = currentTargetOwner(player, session) ?: run {
            player.sendMessage(Messages.TRACKER_NO_TARGET)
            return
        }

        val pointer = Tracking.point(player.location.toSpot(), target.location.toSpot(), target.uniqueId, memory)
        if (pointer == Pointer.Unknown) {
            player.sendMessage(Messages.TRACKER_UNREACHABLE)
            return
        }

        val hand = player.inventory.itemInMainHand
        hand.amount = hand.amount - 1

        player.sendMessage(Messages.trackerStarted(pointer))
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.4f)
        startTrail(player)

        val glowTicks = settings[Keys.TRACKER_GLOW_SECONDS] * 20
        val a = player.location
        val b = target.location
        val inBox = AlarmZone.boxDistance(b.x - a.x, b.y - a.y, b.z - a.z) <= settings[Keys.ALARM_BOX_RANGE]
        if (pointer is Pointer.Exact && glowTicks > 0 && inBox) {
            glow.show(player, target, glowTicks)
        }
    }

    /** 내 팀의 현재 목표 색의 주인 (휴면이 아니면 접속 중이다) */
    private fun currentTargetOwner(player: Player, session: GameSession): Player? {
        val color = session.colorOf(player.uniqueId) ?: return null
        val targetColor = session.targetOf(color) ?: return null
        return session.team(targetColor)?.ownerId?.let { Bukkit.getPlayer(it) }
    }

    // ── 파티클 줄 ─────────────────────────────────────────

    private fun startTrail(hunter: Player) {
        trails.remove(hunter.uniqueId)?.cancel()
        val particle = resolveParticle()
        var runsLeft = settings[Keys.TRACKER_TRAIL_SECONDS] * 2

        trails[hunter.uniqueId] = object : BukkitRunnable() {
            override fun run() {
                val session = game.session
                val target = session?.let { currentTargetOwner(hunter, it) }
                if (session == null || target == null || !hunter.isOnline || runsLeft-- <= 0) {
                    cancel()
                    trails.remove(hunter.uniqueId)
                    return
                }

                val destination = when (val pointer = Tracking.point(hunter.location.toSpot(), target.location.toSpot(), target.uniqueId, memory)) {
                    is Pointer.Exact -> pointer.spot
                    is Pointer.Portal -> pointer.spot
                    is Pointer.Estimated -> pointer.spot
                    Pointer.Unknown -> null
                }?.toLocation() ?: return

                draw(hunter, destination, particle)
            }
        }.runTaskTimer(plugin, 0L, 10L)
    }

    private fun draw(viewer: Player, destination: Location, particle: Particle) {
        val eye = viewer.eyeLocation
        val direction = destination.toVector().subtract(eye.toVector())
        val distance = direction.length()
        if (distance < 1.0) return
        direction.normalize()

        var step = TRAIL_START
        val end = min(distance, TRAIL_LENGTH)
        while (step <= end) {
            viewer.spawnParticle(particle, eye.clone().add(direction.clone().multiply(step)), 1, 0.0, 0.0, 0.0, 0.0)
            step += TRAIL_SPACING
        }
    }

    /** 설정한 파티클. 없거나 추가 데이터가 필요한 파티클이면 기본값 */
    private fun resolveParticle(): Particle {
        val particle = NamespacedKey.fromString(settings[Keys.TRACKER_PARTICLE])?.let { Registry.PARTICLE_TYPE.get(it) }
        if (particle != null && particle.dataType == Void::class.java) return particle
        plugin.logger.warning("tracker.particle '${settings[Keys.TRACKER_PARTICLE]}' 을(를) 쓸 수 없어 electric_spark 로 대신합니다.")
        return Particle.ELECTRIC_SPARK
    }

    companion object {
        private const val TRAIL_START = 1.5
        private const val TRAIL_LENGTH = 24.0
        private const val TRAIL_SPACING = 0.6
        private val PORTAL_CAUSES = setOf(
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_GATEWAY,
        )
    }
}