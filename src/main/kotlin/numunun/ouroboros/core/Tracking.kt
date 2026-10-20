package numunun.ouroboros.core

import java.util.UUID

/** 차원. [scale] 은 오버월드 1 블록이 이 차원에서 몇 분의 1 인가. 환산할 수 없으면 null */
enum class Dimension(val scale: Double?) {
    OVERWORLD(1.0),
    NETHER(8.0),
    OTHER(null),
}

/** 한 월드 안의 좌표 */
data class Spot(
    val worldId: UUID,
    val dimension: Dimension,
    val x: Double,
    val y: Double,
    val z: Double,
)

/** 추적기가 가리킬 곳. 항상 사냥꾼과 같은 월드의 좌표다. */
sealed interface Pointer {
    /** 같은 월드. 목표의 실제 위치 */
    data class Exact(val spot: Spot) : Pointer

    /** 목표가 이 월드를 떠날 때 지나간 자리 (포탈) */
    data class Portal(val spot: Spot) : Pointer

    /** 다른 차원의 좌표를 이 차원 배율로 환산한 위치 */
    data class Estimated(val spot: Spot) : Pointer

    /** 가리킬 방법이 없음 (엔드 등) */
    data object Unknown : Pointer
}

/** 플레이어가 각 월드를 마지막으로 떠난 자리 */
class PortalMemory {

    private val exits = HashMap<UUID, HashMap<UUID, Spot>>()

    /** 월드가 바뀌는 이동이 일어날 때마다 떠난 자리를 기록한다. */
    fun recordExit(player: UUID, from: Spot) {
        exits.getOrPut(player) { HashMap() }[from.worldId] = from
    }

    fun exitOf(player: UUID, worldId: UUID): Spot? = exits[player]?.get(worldId)

    fun clear() = exits.clear()
}

object Tracking {

    /**
     * 사냥꾼 기준으로 목표를 가리킬 곳을 정한다.
     *
     * 찐빠 1: 옛 버전은 다른 월드의 좌표를 그대로 써서, 네더 좌표(1/8)가 오버월드 원점 근처를 가리켰다.
     * 여기서는 다른 월드의 좌표를 절대 그대로 돌려주지 않는다.
     */
    fun point(hunter: Spot, target: Spot, targetId: UUID, memory: PortalMemory): Pointer {
        if (hunter.worldId == target.worldId) return Pointer.Exact(target)

        memory.exitOf(targetId, hunter.worldId)?.let { return Pointer.Portal(it) }

        val from = target.dimension.scale ?: return Pointer.Unknown
        val to = hunter.dimension.scale ?: return Pointer.Unknown
        val factor = from / to
        return Pointer.Estimated(Spot(hunter.worldId, hunter.dimension, target.x * factor, hunter.y, target.z * factor))
    }
}