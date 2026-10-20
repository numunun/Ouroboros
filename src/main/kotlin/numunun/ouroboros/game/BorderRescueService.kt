package numunun.ouroboros.game

import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.BorderGeometry
import numunun.ouroboros.core.CombatLog
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.core.SpotPicker
import numunun.ouroboros.ui.Messages
import numunun.ouroboros.world.SafeGround
import numunun.ouroboros.world.Surface
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * 보더에 갇힌 사람을 자동으로 옮긴다 (찐빠 4).
 *
 * | 상황                                  | 판정         | 이동                         |
 * |--------------------------------------|-------------|------------------------------|
 * | 보더 밖                               | OUTSIDE     | 지금 보더 안쪽으로             |
 * | 축소 중 + 끝 근처 + 오버월드 지하       | BURIED      | 같은 X,Z 에서 Y 만 지표면으로  |
 * | 축소 중 + 끝 근처 + 네더               | NETHER_EDGE | 안쪽으로 (네더는 지표면이 없다) |
 * | 그 외 (축소 대기 중, 끝에서 멂, 지상)    | 없음         | 아무것도 안 함                |
 *
 * 묻지 않고 카운트다운 뒤 옮긴다. 그 사이 스스로 벗어나면 취소, 교전 직후면 보류.
 */
class BorderRescueService(
    private val plugin: Plugin,
    private val game: GameService,
    private val shrink: BorderShrinkService,
    private val ground: SafeGround,
    private val holds: RespawnHoldService,
    private val combat: CombatLog,
    private val settings: SettingValues,
) : GameLifecycleListener {

    private enum class Danger(val label: String) {
        OUTSIDE("보더 밖입니다"),
        BURIED("자기장이 다가옵니다 · 지상으로"),
        NETHER_EDGE("자기장이 다가옵니다 · 안쪽으로"),
    }

    private class Countdown(var remaining: Int)

    private val countdowns = HashMap<UUID, Countdown>()
    private var ticker: BukkitTask? = null

    override fun onGameStart(session: GameSession) {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
    }

    override fun onGameEnd() {
        ticker?.cancel()
        ticker = null
        countdowns.clear()
    }

    private fun tick() {
        val session = game.session ?: return
        if (!settings[Keys.RESCUE_ENABLED]) {
            countdowns.clear()
            return
        }
        val now = System.currentTimeMillis()
        val online = session.participants.mapNotNull { Bukkit.getPlayer(it) }
        countdowns.keys.retainAll(online.map { it.uniqueId }.toSet())
        online.forEach { check(it, now) }
    }

    private fun check(player: Player, now: Long) {
        val id = player.uniqueId
        if (player.isDead || player.gameMode == GameMode.SPECTATOR || holds.isHeld(id)) {
            countdowns.remove(id)
            return
        }

        val danger = dangerOf(player)
        if (danger == null) {
            if (countdowns.remove(id) != null) player.sendActionBar(Messages.RESCUE_CLEARED)
            return
        }

        if (combat.inCombat(id, now, settings[Keys.RESCUE_COMBAT_LOCK] * 1000L)) {
            player.sendActionBar(Messages.RESCUE_ON_HOLD)
            return
        }

        val countdown = countdowns.getOrPut(id) { Countdown(settings[Keys.RESCUE_DELAY]) }
        if (countdown.remaining > 0) {
            player.sendActionBar(Messages.rescueCountdown(danger.label, countdown.remaining))
            countdown.remaining--
            return
        }

        countdowns.remove(id)
        rescue(player, danger)
    }

    private fun dangerOf(player: Player): Danger? {
        val world = player.world
        val border = world.worldBorder
        val here = player.location

        // 월드마다 그 월드의 실제 보더로 잰다. 네더도 네더 보더로. (옛 버전은 오버월드 크기를 그대로 썼다)
        val edge = BorderGeometry.edgeDistance(here.x, here.z, border.center.x, border.center.z, border.size / 2.0)
        if (edge < 0) return Danger.OUTSIDE
        if (!shrink.isShrinking || edge > settings[Keys.RESCUE_TRIGGER_DISTANCE]) return null

        return when (world.environment) {
            World.Environment.NORMAL -> if (isBuried(player)) Danger.BURIED else null
            World.Environment.NETHER -> Danger.NETHER_EDGE
            else -> null
        }
    }

    /** 머리 위로 땅이 있는가. 나뭇잎은 무시한다. 오버월드에서만 쓴다. */
    private fun isBuried(player: Player): Boolean {
        val here = player.location
        val top = player.world.getHighestBlockYAt(here.blockX, here.blockZ, HeightMap.MOTION_BLOCKING_NO_LEAVES)
        return top > here.blockY + 1
    }

    private fun rescue(player: Player, danger: Danger) {
        val here = player.location
        val destination = when (danger) {
            Danger.BURIED -> liftUp(here)
            // 옮긴 자리에서 다시 구조 대상이 되지 않도록 반응 거리보다 더 안쪽으로.
            Danger.OUTSIDE, Danger.NETHER_EDGE -> quietSpotInside(player)
        }

        if (destination == null || (destination.world == here.world && destination.distanceSquared(here) < 0.25)) {
            player.sendMessage(Messages.RESCUE_FAILED)
            plugin.logger.info("[rescue] ${player.name} $danger 실패 @ ${here.blockX},${here.blockY},${here.blockZ}")
            return
        }

        destination.yaw = here.yaw
        destination.pitch = here.pitch
        player.teleport(destination)
        player.fallDistance = 0f
        player.playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f)
        player.sendMessage(if (danger == Danger.BURIED) Messages.RESCUED_UP else Messages.RESCUED_IN)

        plugin.logger.info(
            "[rescue] ${player.name} $danger ${here.blockX},${here.blockY},${here.blockZ}" +
                    " → ${destination.blockX},${destination.blockY},${destination.blockZ}"
        )
    }

    /**
     * 같은 X,Z 의 지표면 (Y 만 올린다).
     * 바로 위가 용암 등이라 못 서면 반경 4칸 안에서 가까운 기둥부터 찾는다.
     */
    private fun liftUp(here: Location): Location? {
        val world = here.world
        val columns = (-RADIUS..RADIUS).flatMap { dx -> (-RADIUS..RADIUS).map { dz -> dx to dz } }
            .sortedBy { (dx, dz) -> dx * dx + dz * dz }

        for ((dx, dz) in columns) {
            val x = here.blockX + dx
            val z = here.blockZ + dz
            val spot = Surface.standOn(world, x, z) ?: Surface.waterSurface(world, x, z)
            if (spot != null) return spot
        }
        return null
    }

    companion object {
        private const val RADIUS = 4
        private const val MAX_TRIES = 8
    }

    /** 구조 카운트다운 중인가. 다른 액션바 안내가 덮지 않게 할 때 쓴다. */
    fun isCounting(player: UUID): Boolean = player in countdowns

    /** 보더 안쪽에서 다른 플레이어와 최대한 떨어진 설 자리 */
    private fun quietSpotInside(player: Player): Location? {
        val here = player.location
        val world = here.world
        val border = world.worldBorder
        val inset = maxOf(settings[Keys.RESCUE_MARGIN], settings[Keys.RESCUE_TRIGGER_DISTANCE] + 8.0)
            .coerceAtMost(border.size / 4.0)

        val candidates = SpotPicker.candidates(
            here.x, here.z, border.center.x, border.center.z, border.size / 2.0, inset,
        )
        val others = Bukkit.getOnlinePlayers()
            .filter { it != player && it.world == world && it.gameMode != GameMode.SPECTATOR }
            .map { it.location.x to it.location.z }

        return SpotPicker.rank(candidates, others, here.x to here.z)
            .asSequence()
            .take(MAX_TRIES)
            .mapNotNull { (x, z) -> ground.standNear(world, x, z, here) }
            .firstOrNull()
    }
}