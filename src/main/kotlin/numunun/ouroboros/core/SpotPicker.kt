package numunun.ouroboros.core

import kotlin.math.min

/** 보더 안쪽에서 다른 사람과 최대한 떨어진 자리 고르기 */
object SpotPicker {

    /**
     * 기준점 주변 격자를 보더 안쪽 [inset] 이내로 끌어당긴 후보들.
     * 보더 밖에 있던 사람을 옮길 때, 한 점만 보지 않고 주변을 넓게 본다.
     */
    fun candidates(
        x: Double,
        z: Double,
        centerX: Double,
        centerZ: Double,
        halfSize: Double,
        inset: Double,
        spread: Double = 48.0,
        step: Double = 16.0,
    ): List<Pair<Double, Double>> {
        val result = LinkedHashSet<Pair<Double, Double>>()
        var dx = -spread
        while (dx <= spread) {
            var dz = -spread
            while (dz <= spread) {
                result += BorderGeometry.clampInside(x + dx, z + dz, centerX, centerZ, halfSize, inset)
                dz += step
            }
            dx += step
        }
        return result.toList()
    }

    /**
     * 좋은 순서로 정렬한다.
     * 1. 가장 가까운 다른 사람과의 거리가 먼 순 (단, [enough] 이상은 모두 같은 점수)
     * 2. 같으면 원래 자리에서 가까운 순 (괜히 멀리 보내지 않게)
     */
    fun rank(
        candidates: List<Pair<Double, Double>>,
        others: List<Pair<Double, Double>>,
        origin: Pair<Double, Double>,
        enough: Double = 64.0,
    ): List<Pair<Double, Double>> =
        candidates.sortedWith(
            compareByDescending<Pair<Double, Double>> { spot ->
                val nearest = others.minOfOrNull { distance(spot, it) } ?: Double.MAX_VALUE
                min(nearest, enough)
            }.thenBy { distance(it, origin) }
        )

    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = a.first - b.first
        val dz = a.second - b.second
        return Math.sqrt(dx * dx + dz * dz)
    }
}