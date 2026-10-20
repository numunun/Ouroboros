package numunun.ouroboros.feature

import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.game.GameLifecycleListener
import numunun.ouroboros.game.GameService
import org.bukkit.Bukkit
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team

/**
 * 게임 중 참가자 머리 위 이름을 숨긴다.
 *
 * 이름표는 "보는 사람의 스코어보드" 기준으로 숨겨진다. HUD 의 개인 스코어보드에도 [install] 로 같은 팀을 넣는다.
 * 명단은 부를 때마다 현재 게임에서 만들므로, 훅 등록 순서와 상관없이 동작한다.
 */
class NameTagHider(
    private val game: GameService,
    private val settings: SettingValues,
) : GameLifecycleListener {

    override fun onGameStart(session: GameSession) {
        install(Bukkit.getScoreboardManager().mainScoreboard)
    }

    override fun onGameEnd() {
        // 개인 스코어보드는 HUD 가 버리므로 메인 것만 지우면 된다.
        Bukkit.getScoreboardManager().mainScoreboard.getTeam(TEAM_NAME)?.unregister()
    }

    fun install(board: Scoreboard) {
        val session = game.session ?: return
        if (!settings[Keys.HIDE_NAMETAGS]) return

        board.getTeam(TEAM_NAME)?.unregister() // 비정상 종료로 남은 것 정리
        board.registerNewTeam(TEAM_NAME).apply {
            setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
            session.participants
                .mapNotNull { Bukkit.getOfflinePlayer(it).name }
                .forEach { addEntry(it) }
        }
    }

    companion object {
        private const val TEAM_NAME = "ouroboros_hidden"
    }
}