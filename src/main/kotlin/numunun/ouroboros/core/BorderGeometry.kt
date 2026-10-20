package numunun.ouroboros.core

import kotlin.math.abs
import kotlin.math.min

/** 정사각형 월드보더 계산. 보더 크기는 전부 "절반 크기"(중심에서 끝까지)로 받는다. */
object BorderGeometry {

    /** 가장 가까운 보더 끝까지의 거리. 음수면 밖이다. */
    fun edgeDistance(x: Double, z: Double, centerX: Double, centerZ: Double, halfSize: Double): Double =
        min(halfSize - abs(x - centerX), halfSize - abs(z - centerZ))

    /**
     * 끝에서 [margin] 만큼 안쪽 영역으로 끌어당긴다. 이미 그 안이면 그대로.
     * 보더가 margin 보다 작으면 중심으로.
     */
    fun clampInside(
        x: Double,
        z: Double,
        centerX: Double,
        centerZ: Double,
        halfSize: Double,
        margin: Double,
    ): Pair<Double, Double> {
        val limit = (halfSize - margin).coerceAtLeast(0.0)
        return x.coerceIn(centerX - limit, centerX + limit) to z.coerceIn(centerZ - limit, centerZ + limit)
    }
}