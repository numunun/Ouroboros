package numunun.ouroboros.listener

import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.CombatLog
import numunun.ouroboros.core.DeathVerdict
import numunun.ouroboros.game.GameService
import numunun.ouroboros.game.RespawnHoldService
import numunun.ouroboros.game.RoleService
import numunun.ouroboros.world.SafeGround
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * 참가자의 사망 처리.
 *
 * - CAPTURE / HUNTED: 헌터에게 잡힘. 죽지 않고 그 자리에서 불사의 토템처럼 일어난다.
 *   소리와 파티클은 토템과 같고, 화면 가운데 토템 애니메이션은 띄우지 않는다.
 * - NORMAL: 실제로 죽고, 죽은 자리 근처에서 부활 유예
 */
class DeathListener(
    private val plugin: Plugin,
    private val game: GameService,
    private val roles: RoleService,
    private val holds: RespawnHoldService,
    private val ground: SafeGround,
    private val combat: CombatLog,
    private val settings: SettingValues,
) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        val session = game.session ?: return
        val victim = event.player
        val victimId = victim.uniqueId
        if (!session.isMember(victimId)) return

        val direct = (event.damageSource.causingEntity as? Player)?.uniqueId
        val windowMillis = settings[Keys.KILL_CREDIT_SECONDS] * 1000L
        val killer = combat.killerOf(victimId, direct, System.currentTimeMillis(), windowMillis)
        val verdict = session.judgeDeath(victimId, killer)

        // 복속 전에 색을 잡아 둔다. 복속하면 바뀐다.
        val victimColor = session.colorOf(victimId)
        val killerColor = killer?.let { session.colorOf(it) }

        plugin.logger.info(
            "[death] ${victim.name}(${victimColor?.name}) ← ${nameOf(killer)}(${killerColor?.name})" +
                    " ${if (direct != null && direct != victimId) "직접" else "간접"} : $verdict"
        )
        combat.forget(victimId)

        // 토템 부활은 주인이 잡혀 복속될 때만. 노예가 헌터에게 죽으면 일반 사망처럼 유예를 받는다.
        if (verdict != DeathVerdict.CAPTURE) {
            if (settings[Keys.FORCE_KEEP_INVENTORY]) {
                event.keepInventory = true
                event.keepLevel = true
                event.drops.clear()
                event.droppedExp = 0
                event.deathMessage(null)
            }
            holds.reserve(victim)
            respawnNextTick(victim)
            return
        }

        if (killerColor != null && victimColor != null) {
            game.capture(killerColor, victimColor)
        }

        // 죽음 자체를 취소한다. 복속으로 최대 체력이 바뀌었을 수 있으니 그 뒤에 계산한다.
        event.setShouldPlayDeathSound(false)
        event.isCancelled = true
        event.reviveHealth = victim.getAttribute(Attribute.MAX_HEALTH)?.value ?: RoleService.DEFAULT_MAX_HEALTH
        Bukkit.getScheduler().runTask(plugin, Runnable { reviveLikeTotem(victim) })
    }

    private fun reviveLikeTotem(player: Player) {
        if (!player.isOnline || player.isDead) return
        player.fireTicks = 0
        player.fallDistance = 0f

        // 공허나 보더 밖에서 잡혔으면 그 자리는 못 쓴다.
        val here = player.location
        val safe = ground.resolve(here, forecast = false, margin = 0.0)
        if (safe !== here) player.teleport(safe)

        val at = player.location.add(0.0, 1.0, 0.0)
        player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 60, 0.4, 0.8, 0.4, 0.5)
        player.world.playSound(player.location, Sound.ITEM_TOTEM_USE, 1.0f, 1.0f)
        if (game.isRunning) roles.protect(player)
    }

    /** 바닐라는 부활할 때 속성을 기본값으로 되돌리므로 역할을 다시 입힌다. */
    @EventHandler
    fun onPostRespawn(event: PlayerPostRespawnEvent) {
        val session = game.session ?: return
        val player = event.player
        if (!session.isMember(player.uniqueId)) return

        roles.apply(player, session.roleOf(player.uniqueId))
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: RoleService.DEFAULT_MAX_HEALTH
        if (!holds.isPendingOrHeld(player.uniqueId)) roles.protect(player)
    }

    private fun respawnNextTick(player: Player) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (player.isOnline && player.isDead) player.spigot().respawn()
        })
    }

    private fun nameOf(uuid: UUID?): String =
        uuid?.let { Bukkit.getOfflinePlayer(it).name } ?: "없음"
}