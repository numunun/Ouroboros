package numunun.ouroboros.game

import kotlin.math.abs
import kotlin.math.ceil
import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.BorderGeometry
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.core.ShrinkEvent
import numunun.ouroboros.core.ShrinkSchedule
import numunun.ouroboros.core.ShrinkSnapshot
import numunun.ouroboros.ui.Messages
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/** 축소 일정에 따라 모든 월드의 보더를 움직인다. 네더는 [BorderSetup.sizeIn] 으로 환산한다. */
class BorderShrinkService(
    private val plugin: Plugin,
    private val settings: SettingValues,
    private val border: BorderSetup,
) : GameLifecycleListener {

    var schedule: ShrinkSchedule? = null
        private set

    private var ticker: BukkitTask? = null

    val isShrinking: Boolean get() = schedule?.isShrinking == true

    /** 진행 중인 축소가 끝났을 때 이 월드의 보더 지름. 축소 중이 아니면 null */
    fun targetSizeIn(world: World): Double? =
        schedule?.shrinkingTarget?.let { border.sizeIn(world, it) }

    override fun onGameStart(session: GameSession) {
        if (!settings[Keys.BORDER_ENABLED] || !settings[Keys.SHRINK_ENABLED]) return
        schedule = ShrinkSchedule(settings.shrinkPhases)
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
    }

    override fun onGameEnd() {
        ticker?.cancel()
        ticker = null
        schedule = null
    }

    private fun tick() {
        val current = schedule ?: return
        current.tick().forEach { handle(it) }
        val target = current.shrinkingTarget
        if (target != null && current.elapsed % 30 == 0) warnOutsiders(target)
    }

    private fun handle(event: ShrinkEvent) {
        when (event) {
            is ShrinkEvent.Warning -> {
                Bukkit.broadcast(Messages.shrinkWarning(event.secondsLeft, event.targetSize))
                warnOutsiders(event.targetSize)
            }


            is ShrinkEvent.Started -> {
                Bukkit.getWorlds().forEach { world ->
                    world.worldBorder.changeSize(border.sizeIn(world, event.targetSize), event.overSeconds * 20L)
                }
                Bukkit.broadcast(Messages.shrinkStarted(event.targetSize, event.overSeconds))
                Bukkit.getOnlinePlayers().forEach {
                    it.playSound(it.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.6f)
                }
                plugin.logger.info("보더 축소 ${event.phase + 1}단계 시작 → ${event.targetSize}")
                warnOutsiders(event.targetSize)
            }

            is ShrinkEvent.Finished -> plugin.logger.info("보더 축소 ${event.phase + 1}단계 완료")

            ShrinkEvent.AllDone -> Bukkit.broadcast(Messages.SHRINK_DONE)
        }
    }

    fun snapshot(): ShrinkSnapshot? = schedule?.let { ShrinkSnapshot(it.phaseIndex, it.stage, it.elapsed) }

    /** 이어하기. 줄어드는 중이었다면 남은 시간 동안 마저 줄인다. */
    fun resume(state: ShrinkSnapshot?) {
        val current = schedule ?: return
        if (state == null) return
        current.restore(state.phase, state.stage, state.elapsed)

        val target = current.shrinkingTarget ?: return
        val phase = settings.shrinkPhases.getOrNull(state.phase) ?: return
        val remainingSeconds = (phase.overSeconds - state.elapsed).coerceAtLeast(1)
        Bukkit.getWorlds().forEach { world ->
            world.worldBorder.changeSize(border.sizeIn(world, target), remainingSeconds * 20L)
        }
    }

    /** 이번 축소가 끝나면 보더 밖이 될 사람에게 미리 알린다. 스스로 옮길 시간을 준다. */
    private fun warnOutsiders(targetSize: Double) {
        Bukkit.getOnlinePlayers()
            .filter { it.gameMode != GameMode.SPECTATOR && it.world.environment != World.Environment.THE_END }
            .forEach { player ->
                val world = player.world
                val here = player.location
                val center = world.worldBorder.center
                val half = border.sizeIn(world, targetSize) / 2.0
                val edge = BorderGeometry.edgeDistance(here.x, here.z, center.x, center.z, half)
                if (edge >= 0) return@forEach

                // +z 가 남쪽, +x 가 동쪽
                val direction = buildString {
                    if (abs(here.z - center.z) > half) append(if (here.z > center.z) "북" else "남")
                    if (abs(here.x - center.x) > half) append(if (here.x > center.x) "서" else "동")
                }
                player.sendMessage(Messages.willBeOutside(ceil(-edge).toInt(), direction))
                player.playSound(here, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f)
            }
    }
}