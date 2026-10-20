package numunun.ouroboros.feature

import io.papermc.paper.scoreboard.numbers.NumberFormat
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.BorderGeometry
import numunun.ouroboros.core.ChainColor
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.core.HudFormat
import numunun.ouroboros.game.BorderShrinkService
import numunun.ouroboros.game.GameLifecycleListener
import numunun.ouroboros.game.GameService
import numunun.ouroboros.ui.label
import numunun.ouroboros.ui.swatch
import numunun.ouroboros.ui.textColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID

/**
 * 바닐라 HUD.
 *
 * - 사이드바: 내 팀, 목표 팀, 전체 팀 현황 (내 색부터 사슬 순서)
 * - 보스바: 이탈 상속이 걸린 팀이면 그 카운트다운, 아니면 보더 축소 일정
 *
 * 사람마다 내용이 달라 1인 1스코어보드를 쓴다. 바뀐 줄만 다시 보낸다.
 */
class HudService(
    private val plugin: Plugin,
    private val game: GameService,
    private val shrink: BorderShrinkService,
    private val nameTags: NameTagHider,
    private val settings: SettingValues,
) : Listener, GameLifecycleListener {

    private class View(
        val board: Scoreboard,
        val objective: Objective,
        val bar: BossBar,
        val lines: Array<Component?> = arrayOfNulls(MAX_LINES),
        var barShown: Boolean = false,
    )

    private class BarState(val title: Component, val progress: Float, val color: BossBar.Color)

    private val views = HashMap<UUID, View>()
    private var ticker: BukkitTask? = null

    // NameTagHider 보다 뒤에 등록해야 한다. 이름표 팀 명단이 먼저 준비돼 있어야 해서.
    override fun onGameStart(session: GameSession) {
        if (!settings[Keys.HUD]) return
        Bukkit.getOnlinePlayers().forEach { attach(it) }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { render() }, 1L, 5L)
    }

    override fun onGameEnd() {
        ticker?.cancel()
        ticker = null
        views.keys.toList().forEach { detach(it) }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (ticker != null) attach(event.player)
    }

    /** 스코어보드 지정은 접속이 끊기면 사라지므로, 다시 들어오면 새로 붙인다. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        views.remove(event.player.uniqueId)
    }

    // ── 붙이기 / 떼기 ─────────────────────────────────────

    private fun attach(player: Player) {
        if (player.uniqueId in views) return
        val board = Bukkit.getScoreboardManager().newScoreboard
        nameTags.install(board)

        val objective = board.registerNewObjective(
            "ouroboros", Criteria.DUMMY,
            Component.text("꼬리잡기", NamedTextColor.GOLD, TextDecoration.BOLD),
        )
        objective.displaySlot = DisplaySlot.SIDEBAR
        // 사이드바 오른쪽 숫자 숨기기. 겉모습일 뿐이라 실패해도 HUD 는 계속 띄운다.
        runCatching { objective.numberFormat(NumberFormat.blank()) }
        player.scoreboard = board

        val bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)
        views[player.uniqueId] = View(board, objective, bar)
    }

    private fun detach(id: UUID) {
        val view = views.remove(id) ?: return
        val player = Bukkit.getPlayer(id) ?: return
        player.hideBossBar(view.bar)
        if (player.scoreboard == view.board) player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
    }

    // ── 그리기 ────────────────────────────────────────────

    private fun render() {
        val session = game.session ?: return
        views.forEach { (id, view) ->
            val player = Bukkit.getPlayer(id) ?: return@forEach
            setLines(view, sidebarFor(session, player))
            updateBar(player, view, forfeitBar(session, player) ?: borderBar(player))
        }
    }

    /** 바뀐 줄만 보낸다. 줄이 줄어들면 남는 줄을 지운다. */
    private fun setLines(view: View, lines: List<Component>) {
        for (i in 0 until MAX_LINES) {
            val entry = "l$i"
            val line = lines.getOrNull(i)
            if (line == null) {
                if (view.lines[i] != null) {
                    view.board.resetScores(entry)
                    view.lines[i] = null
                }
                continue
            }
            if (line == view.lines[i]) continue
            val score = view.objective.getScore(entry)
            score.score = MAX_LINES - i
            score.customName(line)
            view.lines[i] = line
        }
    }

    private fun updateBar(player: Player, view: View, state: BarState?) {
        if (state == null) {
            if (view.barShown) {
                player.hideBossBar(view.bar)
                view.barShown = false
            }
            return
        }
        view.bar.name(state.title)
        view.bar.progress(state.progress)
        view.bar.color(state.color)
        if (!view.barShown) {
            player.showBossBar(view.bar)
            view.barShown = true
        }
    }

    // ── 사이드바 ──────────────────────────────────────────

    private fun sidebarFor(session: GameSession, viewer: Player): List<Component> {
        val mine = session.colorOf(viewer.uniqueId)
        val target = mine?.let { session.targetOf(it) }
        val lines = ArrayList<Component>()

        if (mine != null) {
            val role = if (session.team(mine)?.ownerId == viewer.uniqueId) "주인" else "노예"
            lines += heading("내 팀")
            lines += teamLine(session, mine, mine, suffix = " · $role")
            lines += partnerLines(session, viewer, mine)
            lines += heading("목표 팀")
            lines += when {
                session.isDormant(mine) -> Component.text(" 주인 부재로 휴면 중", NamedTextColor.DARK_GRAY)
                target != null -> teamLine(session, target, mine)
                else -> Component.text(" 없음", NamedTextColor.GRAY)
            }
        } else {
            lines += Component.text("관전 중", NamedTextColor.GRAY)
        }
        lines += Component.empty()
        lines += heading("전체 현황 · ${session.aliveTeamCount}팀")

        // 내 색부터 사슬 순서. 아래로 내려가는 방향이 내가 쫓는 방향이다.
        val chain = HudFormat.rotateFrom(session.aliveColors, mine)
        val room = MAX_LINES - lines.size
        val shown = if (chain.size > room) chain.take(room - 1) else chain
        shown.forEach { color ->
            val tag = when {
                color == mine -> Component.text("  나", NamedTextColor.WHITE)
                color == target -> Component.text("  목표", NamedTextColor.RED)
                session.isDormant(color) -> Component.text("  휴면", NamedTextColor.DARK_GRAY)
                else -> null
            }
            lines += teamLine(session, color, mine, tag = tag)
        }
        if (shown.size < chain.size) {
            lines += Component.text(" 외 ${chain.size - shown.size}팀", NamedTextColor.DARK_GRAY)
        }
        return lines
    }

    /** ■ 노랑 (+ 내 팀이면 인원수) + 꼬리말 */
    private fun teamLine(
        session: GameSession,
        color: ChainColor,
        mine: ChainColor?,
        suffix: String = "",
        tag: Component? = null,
    ): Component {
        // 다른 팀 인원수는 "누가 누구를 먹었는지" 단서가 되므로 내 팀만 보여 준다.
        val size = if (color == mine) " ${session.team(color)?.members?.size ?: 0}명" else ""
        val builder = Component.text()
            .append(Component.text(" "))
            .append(color.swatch)
            .append(Component.text(" "))
            .append(color.label)
            .append(Component.text("$size$suffix", NamedTextColor.GRAY))
        tag?.let { builder.append(it) }
        return builder.build()
    }

    private fun heading(text: String): Component = Component.text(text, NamedTextColor.GOLD)

    // ── 보스바 ────────────────────────────────────────────

    /** 이탈한 팀과 넘겨받을 팀에게만 */
    private fun forfeitBar(session: GameSession, viewer: Player): BarState? {
        val limit = settings[Keys.FORFEIT_MINUTES] * 60
        if (limit <= 0) return null
        val mine = session.colorOf(viewer.uniqueId) ?: return null

        for (absent in session.aliveColors.filter { session.isDormant(it) }) {
            val heir = session.heirOf(absent) ?: continue
            if (mine != absent && mine != heir) continue
            val left = game.forfeitSecondsLeft(absent) ?: continue

            val title = Component.text()
                .append(absent.label)
                .append(Component.text(" 주인 부재 · ${HudFormat.clock(left)} 후 ", NamedTextColor.WHITE))
                .append(heir.label)
                .append(Component.text(" 팀으로", NamedTextColor.WHITE))
                .build()
            return BarState(title, HudFormat.ratio(left, limit), BossBar.Color.YELLOW)
        }
        return null
    }

    private fun borderBar(viewer: Player): BarState? {
        val schedule = shrink.schedule ?: return null
        val left = schedule.stageSecondsLeft
        val length = schedule.stageLength
        val target = schedule.currentTarget
        if (left == null || length == null || target == null) {
            return BarState(Component.text("보더 최종 크기", NamedTextColor.GRAY), 1f, BossBar.Color.WHITE)
        }

        if (!schedule.isShrinking) {
            return BarState(
                Component.text("다음 축소까지 ${HudFormat.clock(left)} · 지름 ${target.toInt()}", NamedTextColor.WHITE),
                HudFormat.ratio(left, length),
                BossBar.Color.WHITE,
            )
        }

        // 축소가 끝났을 때 안쪽이면 초록, 밖이면 빨강
        val safe = willBeInside(viewer)
        val text = "보더 축소 중 → 지름 ${target.toInt()} · ${HudFormat.clock(left)} · ${if (safe) "안전" else "밖으로 이동!"}"
        return BarState(
            Component.text(text, if (safe) NamedTextColor.GREEN else NamedTextColor.RED),
            HudFormat.ratio(left, length),
            if (safe) BossBar.Color.GREEN else BossBar.Color.RED,
        )
    }

    private fun willBeInside(viewer: Player): Boolean {
        val world = viewer.world
        val size = shrink.targetSizeIn(world) ?: return true
        val center = world.worldBorder.center
        val here = viewer.location
        return BorderGeometry.edgeDistance(here.x, here.z, center.x, center.z, size / 2.0) >= 0
    }

    companion object {
        private const val MAX_LINES = 15
        private const val MAX_SLAVE_LINES = 3
    }

    /** 노예는 주인 쪽, 주인은 가까운 노예 쪽. 내가 보는 방향 기준 화살표와 거리 */
    private fun partnerLines(session: GameSession, viewer: Player, color: ChainColor): List<Component> {
        val team = session.team(color) ?: return emptyList()
        if (viewer.uniqueId != team.ownerId) {
            return listOf(directionLine("주인", Bukkit.getPlayer(team.ownerId), viewer, color.textColor))
        }
        return team.slaves
            .mapNotNull { Bukkit.getPlayer(it) }
            .sortedBy { if (it.world == viewer.world) it.location.distanceSquared(viewer.location) else Double.MAX_VALUE }
            .take(MAX_SLAVE_LINES)
            .map { directionLine(it.name, it, viewer, NamedTextColor.WHITE) }
    }

    private fun directionLine(label: String, target: Player?, viewer: Player, labelColor: TextColor): Component {
        val status = when {
            target == null -> Component.text("끊김", NamedTextColor.DARK_GRAY)
            target.world != viewer.world -> Component.text("다른 차원", NamedTextColor.GRAY)
            else -> {
                val dx = target.location.x - viewer.location.x
                val dz = target.location.z - viewer.location.z
                Component.text(
                    "${HudFormat.arrow(dx, dz, viewer.location.yaw)} ${HudFormat.distance(Math.hypot(dx, dz))}",
                    NamedTextColor.AQUA,
                )
            }
        }
        return Component.text()
            .append(Component.text("  "))
            .append(Component.text(label, labelColor))
            .append(Component.text(" "))
            .append(status)
            .build()
    }
}