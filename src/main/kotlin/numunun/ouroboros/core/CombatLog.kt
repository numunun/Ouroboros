package numunun.ouroboros.core

import java.util.UUID

/**
 * 정당한 타격(공격자 → 자기 목표 팀) 기록과 킬 판정.
 *
 * Paper 의 `Player.killer` 는 "최근 몇 초 안에 마지막으로 때린 플레이어" 라서
 * 사망 원인과 무관하게 붙는다. 그래서 그 값 대신 여기서 직접 판단한다.
 * - 직접 죽인 플레이어가 있으면 그 사람
 * - 없으면(낙사, 용암, 몹 등) 정해진 시간 안의 마지막 정당한 타격자
 *
 * 시간은 밀리초를 받기만 한다. 테스트에서 시계를 마음대로 돌리기 위해서다.
 */
class CombatLog {

    private class Hit(val attacker: UUID, val at: Long)

    private val lastPreyHit = HashMap<UUID, Hit>()
    private val lastEngaged = HashMap<UUID, Long>()

    /** 피해가 실제로 들어간 정당한 타격 */
    fun recordHit(attacker: UUID, victim: UUID, now: Long) {
        lastPreyHit[victim] = Hit(attacker, now)
        recordEngagement(attacker, victim, now)
    }

    /** 피해는 없었지만 서로 붙은 것 (사냥감이 헌터를 친 경우 등). 교전 잠금에만 쓴다. */
    fun recordEngagement(a: UUID, b: UUID, now: Long) {
        lastEngaged[a] = now
        lastEngaged[b] = now
    }

    /**
     * 이 죽음을 누구의 킬로 볼 것인가.
     *
     * @param direct 사망 원인을 직접 만든 플레이어 (화살을 쏜 사람, TNT 를 붙인 사람 포함)
     * @param windowMillis 간접 킬 인정 시간. 0 이면 직접 킬만 인정한다.
     */
    fun killerOf(victim: UUID, direct: UUID?, now: Long, windowMillis: Long): UUID? {
        if (direct != null && direct != victim) return direct
        if (windowMillis <= 0L) return null
        val hit = lastPreyHit[victim] ?: return null
        return hit.attacker.takeIf { now - hit.at <= windowMillis }
    }

    fun inCombat(player: UUID, now: Long, windowMillis: Long): Boolean {
        val at = lastEngaged[player] ?: return false
        return now - at < windowMillis
    }

    /** 죽은 사람의 피격 기록을 지운다. 부활 뒤에 옛 타격이 킬로 잡히지 않게. */
    fun forget(victim: UUID) {
        lastPreyHit.remove(victim)
    }

    fun clear() {
        lastPreyHit.clear()
        lastEngaged.clear()
    }
}