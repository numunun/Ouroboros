package numunun.ouroboros.listener

import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent
import net.kyori.adventure.text.Component
import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.CombatLog
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.core.Relation
import numunun.ouroboros.game.GameService
import numunun.ouroboros.ui.Messages
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * 참가자끼리의 공격 규칙.
 *
 * | 관계       | 결과                                   |
 * |-----------|----------------------------------------|
 * | PREY      | 허용, 정당한 타격으로 기록               |
 * | HUNTER    | 피해·바닐라 넉백 없음, 헌터만 밀쳐냄      |
 * | SAME_TEAM | 피해·넉백 모두 차단                      |
 * | UNRELATED | 피해·넉백 모두 차단 (휴면 팀 포함)        |
 *
 * 26.x 에서는 데미지를 취소해도 넉백이 따로 들어간다.
 * 그래서 넉백 이벤트도 같은 규칙으로 따로 막는다.
 */
class CombatListener(
    private val plugin: Plugin,
    private val game: GameService,
    private val combat: CombatLog,
    private val settings: SettingValues,
) : Listener {

    /** 헌터별 마지막으로 밀쳐낸 시각. 연타로 계속 밀리는 것 방지 */
    private val lastPushed = HashMap<UUID, Long>()
    /** 안내 문구 도배 방지 */
    private val lastNotice = HashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val session = game.session ?: return
        val victim = event.entity as? Player ?: return
        val attacker = event.damageSource.causingEntity as? Player ?: return
        if (attacker.uniqueId == victim.uniqueId) return

        val relation = session.relation(attacker.uniqueId, victim.uniqueId)
        if (!blocks(session, relation, attacker, victim)) return

        event.isCancelled = true
        if (relation == Relation.SAME_TEAM) {
            val hitOwner = session.teamOf(victim.uniqueId)?.ownerId == victim.uniqueId
            notice(attacker, if (hitOwner) Messages.CANNOT_HIT_OWNER else Messages.CANNOT_HIT_TEAMMATE)
        }
        if (relation == Relation.HUNTER) {
            if (event.damager !is Projectile) {
                pushAway(victim, attacker.location.x - victim.location.x, attacker.location.z - victim.location.z, attacker)
            }
            combat.recordEngagement(attacker.uniqueId, victim.uniqueId, System.currentTimeMillis())
        }
    }

    /** 데미지를 막은 공격의 바닐라 넉백도 막는다. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onKnockback(event: EntityKnockbackByEntityEvent) {
        val session = game.session ?: return
        val victim = event.entity as? Player ?: return
        val attacker = playerBehind(event.hitBy) ?: return
        if (attacker.uniqueId == victim.uniqueId) return

        val relation = session.relation(attacker.uniqueId, victim.uniqueId)
        if (blocks(session, relation, attacker, victim)) event.isCancelled = true
    }

    /** 다른 플러그인까지 다 거친 뒤에도 피해가 들어간 경우만 정당한 타격으로 기록한다. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamageConfirmed(event: EntityDamageByEntityEvent) {
        val session = game.session ?: return
        val victim = event.entity as? Player ?: return
        val attacker = event.damageSource.causingEntity as? Player ?: return
        if (session.relation(attacker.uniqueId, victim.uniqueId) != Relation.PREY) return

        combat.recordHit(attacker.uniqueId, victim.uniqueId, System.currentTimeMillis())
    }

    /** 화살 등은 데미지가 취소되면 넉백이 안 들어가므로, 맞는 순간 날아온 방향으로 직접 민다. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val session = game.session ?: return
        val hunter = event.hitEntity as? Player ?: return
        val prey = event.entity.shooter as? Player ?: return
        if (session.relation(prey.uniqueId, hunter.uniqueId) != Relation.HUNTER) return

        // knockback() 은 "밀어내는 쪽이 있는 방향" 을 받으므로 날아온 방향의 반대를 넘긴다.
        val velocity = event.entity.velocity
        pushAway(hunter, -velocity.x, -velocity.z, prey)
    }

    /** 이 공격을 막아야 하는가. 데미지와 넉백이 같은 규칙을 쓴다. */
    private fun blocks(session: GameSession, relation: Relation, attacker: Player, victim: Player): Boolean =
        when (relation) {
            Relation.PREY -> false
            Relation.HUNTER, Relation.SAME_TEAM, Relation.UNRELATED -> true
            // 한쪽만 참가자면 게임 밖에서 끼어든 것이므로 막는다.
            Relation.OUT_OF_GAME -> session.isMember(attacker.uniqueId) || session.isMember(victim.uniqueId)
        }

    /**
     * 사냥감에게 맞은 헌터를 밀쳐낸다. 바닐라 공식이라 설정값 1.0 이 일반 공격과 같은 세기다.
     * [sourceDx], [sourceDz] 는 헌터 기준 "밀어내는 쪽이 있는 방향".
     */
    private fun pushAway(hunter: Player, sourceDx: Double, sourceDz: Double, prey: Player) {
        val multiplier = settings[Keys.HUNTER_KNOCKBACK]
        if (multiplier <= 0.0) return

        val now = System.currentTimeMillis()
        val last = lastPushed[hunter.uniqueId]
        if (last != null && now - last < PUSH_COOLDOWN_MILLIS) return
        lastPushed[hunter.uniqueId] = now

        var dx = sourceDx
        var dz = sourceDz
        if (dx * dx + dz * dz < 1.0E-4) {
            val facing = prey.location.direction
            dx = -facing.x
            dz = -facing.z
        }
        // 같은 틱에 바닐라 처리가 속도를 덮어쓸 수 있어 다음 틱에 민다.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (hunter.isOnline) hunter.knockback(VANILLA_STRENGTH * multiplier, dx, dz)
        })
    }

    /** 넉백을 일으킨 엔티티 뒤의 플레이어 (근접은 본인, 투사체는 쏜 사람, TNT 는 붙인 사람) */
    private fun playerBehind(entity: Entity): Player? = when (entity) {
        is Player -> entity
        is Projectile -> entity.shooter as? Player
        is TNTPrimed -> entity.source as? Player
        else -> null
    }

    companion object {
        /** 바닐라 근접 공격의 기본 넉백 세기 */
        private const val VANILLA_STRENGTH = 0.4

        /** 바닐라 무적 시간(10틱)과 같다 */
        private const val PUSH_COOLDOWN_MILLIS = 500L
    }

    private fun notice(player: Player, message: Component) {
        val now = System.currentTimeMillis()
        if (now - (lastNotice[player.uniqueId] ?: 0L) < 1000L) return
        lastNotice[player.uniqueId] = now
        player.sendActionBar(message)
    }
}