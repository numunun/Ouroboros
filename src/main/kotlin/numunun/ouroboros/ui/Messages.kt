package numunun.ouroboros.ui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import numunun.ouroboros.core.ChainColor
import numunun.ouroboros.core.Pointer

/** 플레이어에게 보이는 문구 */
object Messages {

    private val PREFIX: Component = Component.text("[꼬리잡기] ", NamedTextColor.GOLD)

    fun line(vararg parts: Component): Component =
        Component.text().append(PREFIX).append(*parts).build()

    fun white(text: String): Component = Component.text(text, NamedTextColor.WHITE)
    fun gray(text: String): Component = Component.text(text, NamedTextColor.GRAY)

    fun info(text: String): Component = line(white(text))
    fun warn(text: String): Component = line(Component.text(text, NamedTextColor.RED))
    fun good(text: String): Component = line(Component.text(text, NamedTextColor.GREEN))

    // ── 개인 안내 ─────────────────────────────────────────

    fun yourColor(color: ChainColor): Component =
        line(white("당신의 색: "), color.swatch, white(" "), color.label)

    fun currentTarget(target: ChainColor?): Component =
        if (target == null) line(gray("지금은 쫓을 목표가 없습니다."))
        else line(white("목표: "), target.label)

    fun targetChanged(after: ChainColor?): Component =
        if (after == null) line(gray("쫓을 수 있는 팀이 없습니다. 다른 팀이 돌아올 때까지 기다리세요."))
        else line(white("목표가 바뀌었습니다 → "), after.label)

    val TEAM_DORMANT: Component =
        line(gray("주인이 자리를 비워 팀이 휴면 상태입니다. 돌아올 때까지 공격할 수도, 공격받을 수도 없습니다."))

    val JOINED_MID_GAME: Component =
        warn("이미 게임이 진행 중입니다. 끝날 때까지 관전합니다.")

    // ── 진행 ──────────────────────────────────────────────

    fun gameStarted(count: Int): Component = good("꼬리잡기를 시작합니다! 참가자 ${count}명")

    val GAME_STOPPED: Component = info("꼬리잡기가 종료되었습니다.")

    /** 누가 누구를 잡았는지는 밝히지 않는다 */
    val CHAIN_SHIFTED: Component = line(gray("먹이사슬에 변화가 생겼습니다."))

    fun huntSucceeded(prey: ChainColor): Component =
        line(white("사냥 성공! "), prey.label, white(" 팀을 복속시켰습니다."))

    fun forfeitGranted(prey: ChainColor): Component =
        line(prey.label, white(" 팀의 주인이 오래 자리를 비워 당신의 팀으로 넘어왔습니다."))

    fun enslaved(master: ChainColor): Component =
        line(white("당신의 팀은 "), master.label, white(" 팀에 복속되었습니다. 이제 노예입니다."))

    fun victory(winner: ChainColor): Component =
        line(winner.label, white(" 팀이 최후까지 살아남았습니다!"))

    /** 빨강 ▶ 주황 ▶ ... ↻ */
    fun chain(colors: List<ChainColor>): Component {
        val builder = Component.text().append(PREFIX).append(white("먹이사슬: "))
        colors.forEachIndexed { index, color ->
            if (index > 0) builder.append(Component.text(" ▶ ", NamedTextColor.DARK_GRAY))
            builder.append(color.label)
        }
        return builder.append(Component.text(" ↻", NamedTextColor.DARK_GRAY)).build()
    }

    fun holdCountdown(seconds: Int): Component =
        Component.text("부활까지 ${seconds}초", NamedTextColor.GRAY)

    val RESPAWNED: Component = good("부활했습니다.")

    // ── 보더 ──────────────────────────────────────────────

    fun shrinkWarning(secondsLeft: Int, target: Double): Component =
        info("${timeText(secondsLeft)} 후 보더가 줄어듭니다. (지름 ${target.toInt()})")

    fun shrinkStarted(target: Double, overSeconds: Int): Component =
        warn("보더가 줄어들기 시작합니다. ${timeText(overSeconds)}에 걸쳐 지름 ${target.toInt()}까지")

    val SHRINK_DONE: Component = info("보더가 최종 크기에 도달했습니다.")

    fun rescueCountdown(what: String, seconds: Int): Component =
        Component.text("$what · ${seconds}초 후 이동", NamedTextColor.RED)

    val RESCUE_ON_HOLD: Component = Component.text("교전 중이라 이동을 보류합니다", NamedTextColor.GRAY)
    val RESCUE_CLEARED: Component = Component.text("위험에서 벗어났습니다", NamedTextColor.GREEN)
    val RESCUED_UP: Component = good("자기장을 피해 지상으로 올려 보냈습니다.")
    val RESCUED_IN: Component = good("보더 안쪽으로 옮겼습니다.")
    val RESCUE_FAILED: Component = warn("옮길 자리를 찾지 못했습니다. 직접 벗어나세요.")
    val CHAT_DISABLED: Component = warn("게임 중에는 채팅을 쓸 수 없습니다.")
    val END_BLOCKED: Component = warn("이번 게임에서는 엔드에 갈 수 없습니다.")
    val GAME_RESUMED: Component = good("저장된 게임을 이어서 진행합니다.")
    val CANNOT_HIT_OWNER: Component = Component.text("자신의 주인은 공격할 수 없습니다", NamedTextColor.RED)
    val CANNOT_HIT_TEAMMATE: Component = Component.text("같은 팀은 공격할 수 없습니다", NamedTextColor.RED)

    fun willBeOutside(blocks: Int, direction: String): Component =
        warn("지금 위치는 이번 축소가 끝나면 보더 밖입니다. ${direction}쪽으로 ${blocks}블록 이상 옮기세요.")

    fun timeText(seconds: Int): String = when {
        seconds < 60 -> "${seconds}초"
        seconds % 60 == 0 -> "${seconds / 60}분"
        else -> "${seconds / 60}분 ${seconds % 60}초"
    }

    // ── 추적기 / 경보 ─────────────────────────────────────

    val TRACKER_NO_TARGET: Component = warn("지금은 추적할 목표가 없습니다.")

    val TRACKER_UNREACHABLE: Component = warn("목표가 추적할 수 없는 차원에 있습니다. 아이템은 그대로입니다.")

    fun trackerStarted(pointer: Pointer): Component = when (pointer) {
        is Pointer.Exact -> info("목표 방향을 가리킵니다.")
        is Pointer.Portal -> info("목표가 이 차원을 떠난 포탈을 가리킵니다.")
        is Pointer.Estimated -> info("목표는 다른 차원에 있습니다. 환산한 위치를 가리킵니다.")
        Pointer.Unknown -> TRACKER_UNREACHABLE
    }

    fun alarm(symbol: String): Component =
        Component.text()
            .append(Component.text("[ ", NamedTextColor.DARK_GRAY))
            .append(Component.text(symbol, NamedTextColor.RED))
            .append(Component.text(" ]", NamedTextColor.DARK_GRAY))
            .build()

    fun forfeitCountdown(absent: ChainColor, heir: ChainColor, seconds: Int): Component =
        line(
            absent.label,
            white(" 팀 주인이 자리를 비웠습니다. ${timeText(seconds)} 안에 돌아오지 않으면 "),
            heir.label,
            white(" 팀으로 넘어갑니다."),
        )
}