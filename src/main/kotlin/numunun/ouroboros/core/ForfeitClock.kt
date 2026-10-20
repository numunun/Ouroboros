package numunun.ouroboros.core

/**
 * 색별 휴면 시간 누적.
 *
 * 활동 중인 팀이 하나도 없으면(전원 자리 비움) 시계를 멈춘다.
 * 밤새 서버가 비어 있다가 한 명이 먼저 들어왔을 때, 나머지 색이 줄줄이 넘어가는 걸 막기 위해서다.
 * 시각이 아니라 누적 시간으로 세므로 서버를 껐다 켜도 이어서 셀 수 있다.
 */
class ForfeitClock {

    private val dormantMillis = LinkedHashMap<ChainColor, Long>()

    /**
     * 주기적으로 부른다. [elapsedMillis] 는 지난 호출 이후 흐른 시간.
     *
     * @return 이번에 시간이 실제로 흐른 색들. 남은 시간 안내에 쓴다.
     */
    fun tick(session: GameSession, elapsedMillis: Long): Set<ChainColor> {
        val alive = session.aliveColors.toSet()
        dormantMillis.keys.retainAll(alive)

        val someoneWaiting = session.activeColors.isNotEmpty()
        val advanced = LinkedHashSet<ChainColor>()
        alive.forEach { color ->
            if (!session.isDormant(color)) {
                dormantMillis.remove(color)
                return@forEach
            }
            val current = dormantMillis[color] ?: 0L
            if (someoneWaiting) {
                dormantMillis[color] = current + elapsedMillis
                advanced += color
            } else {
                dormantMillis[color] = current
            }
        }
        return advanced
    }

    fun dormantMillis(color: ChainColor): Long? = dormantMillis[color]

    /** 한도를 넘긴 색 중 가장 오래 비운 색. 한도가 0 이하면 넘기지 않는다. */
    fun expired(limitMillis: Long): ChainColor? {
        if (limitMillis <= 0L) return null
        return dormantMillis.entries
            .filter { it.value >= limitMillis }
            .maxByOrNull { it.value }
            ?.key
    }

    fun reset(color: ChainColor) {
        dormantMillis.remove(color)
    }

    fun clear() = dormantMillis.clear()

    fun snapshot(): Map<ChainColor, Long> = LinkedHashMap(dormantMillis)

    fun restore(saved: Map<ChainColor, Long>) {
        dormantMillis.clear()
        dormantMillis.putAll(saved)
    }
}