package numunun.ouroboros.feature

import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.game.GameLifecycleListener
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin

/** 게임 중 탭 목록에서 서로를 숨긴다. 몸은 그대로 보인다. */
class TabHider(
    private val plugin: Plugin,
    private val settings: SettingValues,
) : Listener, GameLifecycleListener {

    private var active = false

    override fun onGameStart(session: GameSession) {
        if (!settings[Keys.HIDE_TAB_LIST]) return
        active = true
        val online = Bukkit.getOnlinePlayers()
        online.forEach { viewer -> online.filter { it != viewer }.forEach { viewer.unlistPlayer(it) } }
    }

    override fun onGameEnd() {
        if (!active) return
        active = false
        val online = Bukkit.getOnlinePlayers()
        online.forEach { viewer -> online.filter { it != viewer }.forEach { viewer.listPlayer(it) } }
    }

    /** 접속 처리가 끝난 다음 틱에 숨긴다. 접속 이벤트 시점에는 아직 목록에 안 올라가 있을 수 있다. */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!active) return
        val joined = event.player
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!joined.isOnline) return@Runnable
            Bukkit.getOnlinePlayers().filter { it != joined }.forEach { other ->
                joined.unlistPlayer(other)
                other.unlistPlayer(joined)
            }
        })
    }
}