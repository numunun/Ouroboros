package numunun.ouroboros.feature

import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.AlarmZone
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.game.BorderRescueService
import numunun.ouroboros.game.GameLifecycleListener
import numunun.ouroboros.game.GameService
import numunun.ouroboros.game.RespawnHoldService
import numunun.ouroboros.ui.Messages
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/**
 * 접근 경보. 주인에게만 보인다.
 *
 * - 헌터 팀 누군가가 각 축 ±box-range 상자 안: [ ❤ ] 표시, 심장 0.5초마다
 * - 각 축 ±sound-range 상자 안: 심장 1초마다, 가까울수록 크게
 *
 * 누가 왔는지는 알려 주지 않는다. 같은 월드에서만 잰다.
 */
class ProximityAlarm(
    private val plugin: Plugin,
    private val game: GameService,
    private val holds: RespawnHoldService,
    private val rescue: BorderRescueService,
    private val settings: SettingValues,
) : GameLifecycleListener {

    private var ticker: BukkitTask? = null
    private var beat = 0

    override fun onGameStart(session: GameSession) {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 10L, 10L)
    }

    override fun onGameEnd() {
        ticker?.cancel()
        ticker = null
    }

    private fun tick() {
        val session = game.session ?: return
        beat++
        val boxRange = settings[Keys.ALARM_BOX_RANGE]
        val soundRange = settings[Keys.ALARM_SOUND_RANGE]

        session.activeColors.forEach { color ->
            val owner = session.team(color)?.ownerId?.let { Bukkit.getPlayer(it) } ?: return@forEach
            // 유예 중이거나 구조 카운트다운 중이면 그 안내를 덮지 않는다.
            if (owner.isDead || holds.isHeld(owner.uniqueId) || rescue.isCounting(owner.uniqueId)) return@forEach

            val hunterColor = session.hunterOf(color) ?: return@forEach
            val here = owner.location
            val nearest = session.team(hunterColor)?.members.orEmpty()
                .mapNotNull { Bukkit.getPlayer(it) }
                .filter {
                    it.world == owner.world && !it.isDead &&
                            it.gameMode != GameMode.SPECTATOR && !holds.isHeld(it.uniqueId)
                }
                .minOfOrNull {
                    val there = it.location
                    AlarmZone.boxDistance(there.x - here.x, there.y - here.y, there.z - here.z)
                }
                ?: return@forEach

            // 소리를 못 들을 수도 있으니 경보 범위 안이면 무조건 표시한다.
            val alertRange = maxOf(boxRange, soundRange)
            if (nearest > alertRange) return@forEach
            owner.sendActionBar(Messages.alarm(settings[Keys.ALARM_SYMBOL]))

            // 가까운 상자 안에서는 0.5초마다, 밖에서는 1초마다 뛴다.
            val close = nearest <= boxRange
            if (!close && beat % 2 != 0) return@forEach
            if (soundRange <= 0.0) return@forEach
            val volume = AlarmZone.heartbeatVolume(nearest, alertRange) ?: return@forEach
            owner.playSound(here, Sound.ENTITY_WARDEN_HEARTBEAT, volume, 1.0f)
        }
    }
}