package numunun.ouroboros.listener

import numunun.ouroboros.core.Role
import numunun.ouroboros.game.GameService
import numunun.ouroboros.game.RespawnHoldService
import numunun.ouroboros.game.RoleService
import numunun.ouroboros.ui.Messages
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * 게임 도중 접속과 퇴장.
 *
 * - 참가자: 역할을 다시 입히고, 재접속 유예를 걸고, 휴면 상태를 갱신한다.
 * - 시작 때 없던 사람: 관전자
 */
class PresenceListener(
    private val game: GameService,
    private val roles: RoleService,
    private val holds: RespawnHoldService,
) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val session = game.session

        // 게임이 끝날 때 오프라인이었던 사람. 남은 능력치를 되돌린다.
        if (session == null) {
            if (roles.isMarked(player)) roles.reset(player)
            return
        }

        val id = player.uniqueId
        if (!session.isMember(id)) {
            session.addSpectator(id)
            roles.apply(player, Role.SPECTATOR)
            player.sendMessage(Messages.JOINED_MID_GAME)
            return
        }

        roles.apply(player, session.roleOf(id))
        game.refreshPresence(quiet = setOf(id))
        holds.onRejoin(player)

        val color = session.colorOf(id) ?: return
        player.sendMessage(Messages.yourColor(color))
        player.sendMessage(Messages.currentTarget(session.targetOf(color)))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        game.refreshPresence(leaving = event.player.uniqueId)
    }
}