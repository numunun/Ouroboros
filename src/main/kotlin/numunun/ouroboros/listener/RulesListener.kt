package numunun.ouroboros.listener

import io.papermc.paper.event.player.AsyncChatEvent
import numunun.ouroboros.command.OuroCommand
import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.game.GameService
import numunun.ouroboros.ui.Messages
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * 게임 중 규칙.
 * - 채팅 금지 (관리자는 공지를 위해 예외)
 * - 엔드 차단
 * - 도전과제 알림 숨김 (네더 진입 같은 정보가 새지 않게)
 */
class RulesListener(
    private val game: GameService,
    private val settings: SettingValues,
) : Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!game.isRunning || !settings[Keys.DISABLE_CHAT]) return
        if (event.player.hasPermission(OuroCommand.PERM_ADMIN)) return
        event.isCancelled = true
        event.player.sendMessage(Messages.CHAT_DISABLED)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) {
        if (!game.isRunning || !settings[Keys.BLOCK_THE_END]) return
        if (event.cause != PlayerTeleportEvent.TeleportCause.END_PORTAL) return
        event.isCancelled = true
        event.player.sendMessage(Messages.END_BLOCKED)
    }

    @EventHandler
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        if (game.isRunning) event.message(null)
    }
}