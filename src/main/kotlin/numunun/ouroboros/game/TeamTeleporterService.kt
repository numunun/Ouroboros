package numunun.ouroboros.game

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.ChainColor
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.ui.Messages
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * 같은 팀(주인-노예)끼리 이동 요청.
 *
 * 1. A: /ouro tpa B  →  B 에게 [수락] [거절]
 * 2. B 가 수락하면 A 는 정해진 시간 동안 가만히 있어야 한다. 움직이거나 맞으면 취소.
 * 3. 이동 직전 확인: A 근처에 헌터가 있으면(도주 방지), B 근처에 사냥감이 있으면(기습 방지) 취소.
 */
class TeamTeleportService(
    private val plugin: Plugin,
    private val game: GameService,
    private val holds: RespawnHoldService,
    private val settings: SettingValues,
) : Listener, GameLifecycleListener {

    private class Request(val from: UUID, val expiresAt: Long)

    private class Travel(val destination: UUID, val origin: Location, var remaining: Int)

    /** 받는 사람 → 요청 */
    private val requests = HashMap<UUID, Request>()

    /** 이동하는 사람 → 진행 */
    private val travels = HashMap<UUID, Travel>()

    private var ticker: BukkitTask? = null

    override fun onGameStart(session: GameSession) {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
    }

    override fun onGameEnd() {
        ticker?.cancel()
        ticker = null
        requests.clear()
        travels.clear()
    }

    /** 탭 완성용 */
    fun teammateNames(player: Player): List<String> {
        val session = game.session ?: return emptyList()
        val color = session.colorOf(player.uniqueId) ?: return emptyList()
        if (session.team(color)?.ownerId == player.uniqueId) return emptyList()
        return game.onlineMembers(color).filter { it != player }.map { it.name }
    }

    fun request(from: Player, targetName: String) {
        val session = game.session ?: return from.sendMessage(Messages.warn("진행 중인 게임이 없습니다."))
        val color = session.colorOf(from.uniqueId)
        val target = Bukkit.getPlayerExact(targetName)
        if (color == null || target == null || target == from || session.colorOf(target.uniqueId) != color) {
            from.sendMessage(Messages.warn("같은 팀의 접속 중인 팀원에게만 요청할 수 있습니다."))
            return
        }
        if (session.team(color)?.ownerId == from.uniqueId) {
            from.sendMessage(Messages.warn("주인은 이동 요청을 할 수 없습니다. 노예가 주인에게 가는 기능입니다."))
            return
        }
        if (holds.isHeld(from.uniqueId)) {
            from.sendMessage(Messages.warn("부활 유예 중에는 요청할 수 없습니다."))
            return
        }

        requests[target.uniqueId] = Request(from.uniqueId, System.currentTimeMillis() + REQUEST_TTL_MILLIS)
        from.sendMessage(Messages.info("${target.name} 님에게 이동 요청을 보냈습니다."))
        target.sendMessage(
            Messages.line(
                Messages.white("${from.name} 님이 당신에게 오려고 합니다. "),
                button("[수락]", NamedTextColor.GREEN, "/tpaccept"),
                Messages.white(" "),
                button("[거절]", NamedTextColor.RED, "/tpdeny"),
            )
        )
    }

    fun accept(target: Player) {
        val request = requests.remove(target.uniqueId)
        val traveler = request
            ?.takeIf { it.expiresAt >= System.currentTimeMillis() }
            ?.let { Bukkit.getPlayer(it.from) }
        if (traveler == null) {
            target.sendMessage(Messages.warn("받은 이동 요청이 없습니다."))
            return
        }
        if (game.colorOf(traveler) != game.colorOf(target)) {
            target.sendMessage(Messages.warn("이제 같은 팀이 아닙니다."))
            return
        }

        val delay = settings[Keys.TPA_DELAY]
        travels[traveler.uniqueId] = Travel(target.uniqueId, traveler.location.clone(), delay)
        target.sendMessage(Messages.info("요청을 수락했습니다."))
        traveler.sendMessage(Messages.info("${target.name} 님이 수락했습니다. ${delay}초 동안 움직이지 마세요."))
    }

    fun deny(target: Player) {
        val request = requests.remove(target.uniqueId) ?: run {
            target.sendMessage(Messages.warn("받은 이동 요청이 없습니다."))
            return
        }
        Bukkit.getPlayer(request.from)?.sendMessage(Messages.warn("${target.name} 님이 이동 요청을 거절했습니다."))
        target.sendMessage(Messages.info("거절했습니다."))
    }

    /** 대기 중에 맞으면 취소 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (travels.remove(player.uniqueId) != null) {
            player.sendMessage(Messages.warn("공격받아 이동이 취소되었습니다."))
        }
    }

    private fun tick() {
        val session = game.session ?: return
        val now = System.currentTimeMillis()
        requests.values.removeIf { it.expiresAt < now }

        travels.entries.toList().forEach { (id, travel) ->
            val traveler = Bukkit.getPlayer(id)
            val destination = Bukkit.getPlayer(travel.destination)
            if (traveler == null || destination == null) {
                travels.remove(id)
                traveler?.sendMessage(Messages.warn("상대가 접속해 있지 않아 이동이 취소되었습니다."))
                return@forEach
            }

            val problem = when {
                traveler.isDead || destination.isDead || holds.isHeld(id) || holds.isHeld(travel.destination) ->
                    "둘 중 한 명이 사망해 이동이 취소되었습니다."
                session.colorOf(id) != session.colorOf(travel.destination) ->
                    "이제 같은 팀이 아니라 이동이 취소되었습니다."
                traveler.world != travel.origin.world ||
                        traveler.location.distanceSquared(travel.origin) > MOVE_TOLERANCE_SQUARED ->
                    "움직여서 이동이 취소되었습니다."
                else -> null
            }
            if (problem != null) {
                travels.remove(id)
                traveler.sendMessage(Messages.warn(problem))
                return@forEach
            }

            if (travel.remaining > 0) {
                traveler.sendActionBar(Component.text("이동까지 ${travel.remaining}초", NamedTextColor.GRAY))
                travel.remaining--
                return@forEach
            }

            travels.remove(id)
            val unsafe = unsafeReason(session, traveler, destination)
            if (unsafe != null) {
                traveler.sendMessage(Messages.warn(unsafe))
                return@forEach
            }

            traveler.teleport(destination.location)
            traveler.fallDistance = 0f
            traveler.playSound(traveler.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f)
            traveler.sendMessage(Messages.good("${destination.name} 님에게 이동했습니다."))
        }
    }

    private fun unsafeReason(session: GameSession, traveler: Player, destination: Player): String? {
        val radius = settings[Keys.TPA_SAFE_RADIUS]
        if (radius <= 0.0) return null
        val color = session.colorOf(traveler.uniqueId) ?: return "게임 참가자가 아닙니다."

        session.hunterOf(color)?.let { hunter ->
            if (anyNear(hunter, traveler.location, radius)) return "근처에 헌터가 있어 이동할 수 없습니다."
        }
        session.targetOf(color)?.let { prey ->
            if (anyNear(prey, destination.location, radius)) return "도착 지점 근처에 사냥감이 있어 이동할 수 없습니다."
        }
        return null
    }

    private fun anyNear(color: ChainColor, at: Location, radius: Double): Boolean =
        game.onlineMembers(color).any { it.world == at.world && it.location.distanceSquared(at) <= radius * radius }

    private fun button(text: String, color: NamedTextColor, command: String): Component =
        Component.text(text, color)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)))

    companion object {
        private const val REQUEST_TTL_MILLIS = 60_000L
        private const val MOVE_TOLERANCE_SQUARED = 1.0
    }
}