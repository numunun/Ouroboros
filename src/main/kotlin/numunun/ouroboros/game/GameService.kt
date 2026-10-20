package numunun.ouroboros.game

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.ChainColor
import numunun.ouroboros.core.ColorTeam
import numunun.ouroboros.core.ForfeitClock
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.core.Relation
import numunun.ouroboros.core.Role
import numunun.ouroboros.core.SessionSnapshot
import numunun.ouroboros.core.TargetChange
import numunun.ouroboros.ui.Messages
import numunun.ouroboros.ui.label
import numunun.ouroboros.ui.swatch
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.util.UUID
import java.util.logging.Level

sealed interface StartResult {
    data object Started : StartResult
    data class Rejected(val reason: String) : StartResult
}

/** 게임이 끝난 이유. 저장 파일을 지울지 정할 때 쓴다. */
enum class EndReason { VICTORY, STOPPED, SHUTDOWN }

/**
 * 게임 흐름의 지휘부.
 *
 * 규칙 판단은 전부 [GameSession] 이 하고, 여기서는 그 결과에 따른 부작용
 * (메시지, 역할 적용, 훅 호출)만 처리한다.
 */
class GameService(
    private val plugin: Plugin,
    private val settings: SettingValues,
    private val roles: RoleService,
    private val border: BorderSetup,
    private val scatter: Scatter,
) {

    var session: GameSession? = null
        private set

    val isRunning: Boolean get() = session != null

    var lastEndReason: EndReason? = null
        private set

    private val hooks = ArrayList<GameLifecycleListener>()
    private val forfeitClock = ForfeitClock()
    private var ticker: BukkitTask? = null

    init {
        settings.onChange { changed ->
            if (changed == Keys.SLAVE_MAX_HEARTS || changed == Keys.SLAVE_ATTACK_MULTIPLIER) reapplyRoles()
        }
    }

    fun registerHook(hook: GameLifecycleListener) {
        hooks += hook
    }

    // ── 시작 / 종료 ───────────────────────────────────────

    fun start(candidates: Collection<Player>): StartResult {
        if (isRunning) return StartResult.Rejected("이미 게임이 진행 중입니다.")

        val players = candidates.distinctBy { it.uniqueId }.shuffled()
        if (players.size < 2) {
            return StartResult.Rejected("최소 2명이 필요합니다. (현재 ${players.size}명)")
        }
        if (players.size > ChainColor.MAX_PLAYERS) {
            return StartResult.Rejected("최대 ${ChainColor.MAX_PLAYERS}명까지 참여할 수 있습니다. (현재 ${players.size}명)")
        }

        val colors = ChainColor.take(players.size)
        val teams = players.mapIndexed { index, player -> ColorTeam(colors[index], player.uniqueId) }
        val created = GameSession(players.map { it.uniqueId }.toSet(), teams)
        created.updatePresence { isOnline(it) }

        session = created
        forfeitClock.clear()

        border.apply()
        players.forEach { prepare(it) }
        if (settings[Keys.SCATTER_ON_START]) scatter.scatter(players)

        eachHook("onGameStart") { it.onGameStart(created) }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickSecond() }, 20L, 20L)

        Bukkit.broadcast(Messages.gameStarted(players.size))
        Bukkit.broadcast(Messages.chain(created.aliveColors))
        players.forEachIndexed { index, player -> announceColor(player, colors[index], created) }
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (session !== created) return@Runnable
            players.forEachIndexed { index, player ->
                if (player.isOnline) announceColor(player, colors[index], created)
            }
        }, 40L)

        plugin.logger.info("게임 시작: ${players.size}명")
        return StartResult.Started
    }

    fun stop(announce: Boolean = true, reason: EndReason = EndReason.STOPPED) {
        val current = session ?: return
        lastEndReason = reason

        ticker?.cancel()

        ticker = null
        eachHook("onGameEnd", hooks.asReversed()) { it.onGameEnd() }

        (current.participants + current.spectators)
            .mapNotNull { Bukkit.getPlayer(it) }
            .forEach { roles.reset(it) }

        border.restore()
        forfeitClock.clear()
        session = null

        if (announce) {
            Bukkit.broadcast(Messages.GAME_STOPPED)
        }
        plugin.logger.info("게임 종료")
    }

    // ── 복속 ──────────────────────────────────────────────

    /** 사망 판정이 CAPTURE 일 때 부른다. (4단계) */
    fun capture(hunter: ChainColor, prey: ChainColor) = absorb(hunter, prey, byForfeit = false)

    private fun absorb(hunter: ChainColor, prey: ChainColor, byForfeit: Boolean) {
        val current = session ?: return
        val result = current.absorb(hunter, prey)
        forfeitClock.reset(prey)
        val captured = result.captured.toSet()

        // 전체에는 "무언가 바뀌었다"는 것만 알린다.
        Bukkit.broadcast(Messages.CHAIN_SHIFTED)
        Bukkit.getOnlinePlayers().forEach {
            it.playSound(it.location, Sound.ENTITY_WITHER_SPAWN, 0.35f, 1.4f)
        }
        result.captured.mapNotNull { Bukkit.getPlayer(it) }.forEach { slave ->
            roles.apply(slave, Role.SLAVE)
            slave.sendMessage(Messages.enslaved(hunter))
        }

        // 마지막 한 팀만 남았으면 "쫓을 팀이 없다" 같은 안내 없이 바로 승리로 간다.
        if (current.winner != null) {
            checkVictory(current)
            return
        }

        val newTarget = current.targetOf(hunter)
        result.captured.mapNotNull { Bukkit.getPlayer(it) }.forEach { it.sendMessage(Messages.currentTarget(newTarget)) }
        onlineMembers(hunter)
            .filter { it.uniqueId !in captured }
            .forEach { it.sendMessage(if (byForfeit) Messages.forfeitGranted(prey) else Messages.huntSucceeded(prey)) }

        // 새 노예들은 위에서 현재 목표를 따로 받았으므로 뺀다.
        notifyTargetChanges(current, result.changes, exclude = captured)
        eachHook("onSessionChanged") { it.onSessionChanged(current) }
    }

    private fun checkVictory(current: GameSession) {
        val winner = current.winner ?: return

        val title = Title.title(
            Component.text("승리!", NamedTextColor.GOLD),
            winner.label,
            Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1)),
        )
        Bukkit.getOnlinePlayers().forEach {
            it.showTitle(title)
            it.playSound(it.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
        }
        stop(announce = false, reason = EndReason.VICTORY)
    }

    // ── 접속 상태 (찐빠 3) ────────────────────────────────

    /**
     * 접속 상태를 다시 계산하고 목표가 바뀐 팀에게 알린다.
     *
     * @param leaving 지금 나가는 중인 사람. 퇴장 이벤트 시점에는 아직 온라인으로 잡히므로 직접 뺀다.
     * @param quiet   알림을 따로 받을 사람 (방금 들어와서 안내를 받는 사람 등)
     */
    fun refreshPresence(leaving: UUID? = null, quiet: Set<UUID> = emptySet()) {
        val current = session ?: return
        val changes = current.updatePresence { it != leaving && isOnline(it) }
        if (changes.isEmpty()) return

        notifyTargetChanges(current, changes, exclude = quiet)
        eachHook("onSessionChanged") { it.onSessionChanged(current) }
    }

    private fun notifyTargetChanges(current: GameSession, changes: List<TargetChange>, exclude: Set<UUID>) {
        changes.forEach { change ->
            val message = if (current.isDormant(change.hunter)) {
                Messages.TEAM_DORMANT
            } else {
                Messages.targetChanged(change.after)
            }
            onlineMembers(change.hunter)
                .filter { it.uniqueId !in exclude }
                .forEach { member ->
                    member.sendMessage(message)
                    member.playSound(member.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f)
                }
        }
    }

    /** 1초마다. 휴면이 길어진 색을 앞 팀에 넘기고, 남은 시간을 알린다. */
    private fun tickSecond() {
        val current = session ?: return
        val advanced = forfeitClock.tick(current, 1000L)

        val minutes = settings[Keys.FORFEIT_MINUTES]
        val limitMillis = minutes * 60_000L
        if (limitMillis <= 0L) return
        advanced.forEach { announceForfeitCountdown(current, it, limitMillis) }

        val expired = forfeitClock.expired(limitMillis) ?: return
        val heir = current.heirOf(expired) ?: return

        plugin.logger.info("${expired.name} 주인 ${minutes}분 미접속 → ${heir.name} 에 넘김")
        absorb(heir, expired, byForfeit = true)
    }

    /** 이탈한 팀과 넘겨받을 팀에게만. 처음 시계가 돈 순간과 정해진 남은 시간에 */
    private fun announceForfeitCountdown(current: GameSession, color: ChainColor, limitMillis: Long) {
        val millis = forfeitClock.dormantMillis(color) ?: return
        val first = millis == 1000L
        val left = ((limitMillis - millis) / 1000L).toInt()
        if (left <= 0 || (!first && left !in FORFEIT_NOTICE_AT)) return

        val heir = current.heirOf(color) ?: return
        val shown = if (first) (limitMillis / 1000L).toInt() else left
        val message = Messages.forfeitCountdown(color, heir, shown)
        (onlineMembers(heir) + onlineMembers(color)).forEach {
            it.sendMessage(message)
            it.playSound(it.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.0f)
        }
    }

    // ── 조회 ──────────────────────────────────────────────

    fun roleOf(player: Player): Role = session?.roleOf(player.uniqueId) ?: Role.NONE

    fun colorOf(player: Player): ChainColor? = session?.colorOf(player.uniqueId)

    fun relation(attacker: Player, victim: Player): Relation =
        session?.relation(attacker.uniqueId, victim.uniqueId) ?: Relation.OUT_OF_GAME

    fun targetOf(player: Player): ChainColor? {
        val current = session ?: return null
        return current.colorOf(player.uniqueId)?.let { current.targetOf(it) }
    }

    fun onlineMembers(color: ChainColor): List<Player> =
        session?.team(color)?.members?.mapNotNull { Bukkit.getPlayer(it) }.orEmpty()

    /** 휴면 누적 시간(분). 상태 확인용 */
    fun dormantMinutes(color: ChainColor): Long? = forfeitClock.dormantMillis(color)?.let { it / 60_000L }

    // ── 내부 ──────────────────────────────────────────────

    private fun isOnline(uuid: UUID): Boolean = Bukkit.getPlayer(uuid) != null

    private fun prepare(player: Player) {
        roles.apply(player, Role.OWNER)
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: RoleService.DEFAULT_MAX_HEALTH
        player.foodLevel = 20
        player.saturation = 5f
        player.fireTicks = 0
    }

    private fun announceColor(player: Player, color: ChainColor, current: GameSession) {
        player.sendMessage(Messages.yourColor(color))
        player.sendMessage(Messages.currentTarget(current.targetOf(color)))
        player.showTitle(
            Title.title(
                color.swatch,
                Component.text("당신의 색", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(600)),
            )
        )
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f)
    }

    fun reapplyRoles() {
        val current = session ?: return
        current.participants.mapNotNull { Bukkit.getPlayer(it) }.forEach { roles.apply(it, current.roleOf(it.uniqueId)) }
    }

    /** 저장용 */
    fun forfeitSnapshot(): Map<ChainColor, Long> = forfeitClock.snapshot()

    /**
     * 저장된 게임을 이어 간다. 흩어 놓기와 보더 초기화는 하지 않는다.
     * 보더와 축소 진행은 SaveService 가 이어서 맞춘다.
     */
    fun resume(snapshot: SessionSnapshot): StartResult {
        if (isRunning) return StartResult.Rejected("이미 게임이 진행 중입니다.")

        val restored = runCatching { snapshot.toSession() }
            .getOrElse { return StartResult.Rejected("저장된 게임을 읽을 수 없습니다: ${it.message}") }
        if (restored.aliveTeamCount < 2) return StartResult.Rejected("이미 승부가 난 게임입니다.")

        session = restored
        forfeitClock.restore(snapshot.dormantMillis)
        restored.updatePresence { isOnline(it) }

        Bukkit.getOnlinePlayers().forEach { player ->
            val id = player.uniqueId
            if (!restored.isMember(id)) restored.addSpectator(id)
            roles.apply(player, restored.roleOf(id))
        }

        eachHook("onGameStart") { it.onGameStart(restored) }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickSecond() }, 20L, 20L)

        Bukkit.broadcast(Messages.GAME_RESUMED)
        Bukkit.broadcast(Messages.chain(restored.aliveColors))
        restored.participants.mapNotNull { Bukkit.getPlayer(it) }.forEach { player ->
            val color = restored.colorOf(player.uniqueId) ?: return@forEach
            player.sendMessage(Messages.yourColor(color))
            player.sendMessage(Messages.currentTarget(restored.targetOf(color)))
        }

        plugin.logger.info("게임 이어하기: ${restored.aliveTeamCount}색 생존")
        return StartResult.Started
    }

    companion object {
        /** 이탈 상속까지 이만큼 남았을 때 다시 알린다(초) */
        private val FORFEIT_NOTICE_AT = setOf(300, 60, 30, 10)
    }

    /** 이탈 상속까지 남은 초. 휴면이 아니거나 상속을 안 쓰면 null. HUD 용 */
    fun forfeitSecondsLeft(color: ChainColor): Int? {
        val limitMillis = settings[Keys.FORFEIT_MINUTES] * 60_000L
        if (limitMillis <= 0L) return null
        val millis = forfeitClock.dormantMillis(color) ?: return null
        return ((limitMillis - millis) / 1000L).toInt().coerceAtLeast(0)
    }

    /**
     * 훅 하나가 예외를 던져도 나머지 훅은 계속 부른다.
     * 그렇지 않으면 게임이 반쯤만 시작되거나 반쯤만 정리된 채로 남는다.
     */
    private fun eachHook(
        phase: String,
        targets: List<GameLifecycleListener> = hooks,
        action: (GameLifecycleListener) -> Unit,
    ) {
        targets.forEach { hook ->
            runCatching { action(hook) }.onFailure {
                plugin.logger.log(Level.SEVERE, "${hook.javaClass.simpleName}.$phase 실패", it)
            }
        }
    }
}