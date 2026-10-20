package numunun.ouroboros.core

/** HUD 에 쓰는 표기. 서버 없이 테스트할 수 있게 따로 둔다. */
object HudFormat {

    /** 272 → "4:32", 3723 → "1:02:03" */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = s % 3600 / 60
        val secs = s % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
    }

    /** 게이지 비율. 남은 시간 / 전체. 전체가 0 이하면 가득 */
    fun ratio(left: Int, total: Int): Float =
        if (total <= 0) 1f else (left.toFloat() / total).coerceIn(0f, 1f)

    /**
     * 사슬을 [start] 부터 돌려 세운다. 내 색이 맨 위에 오고, 그 아래가 내가 쫓는 방향이다.
     * [start] 가 없으면(관전자 등) 원래 순서 그대로.
     */
    fun <T> rotateFrom(chain: List<T>, start: T?): List<T> {
        val index = if (start == null) -1 else chain.indexOf(start)
        if (index <= 0) return chain
        return chain.drop(index) + chain.take(index)
    }

    private val ARROWS = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")

    /**
     * 내가 바라보는 방향 기준으로 대상이 어느 쪽인지 화살표로.
     * 마인크래프트 yaw: 0 = 남(+z), 90 = 서(-x). 오른쪽으로 돌수록 커진다.
     */
    fun arrow(dx: Double, dz: Double, viewerYaw: Float): String {
        val targetYaw = Math.toDegrees(Math.atan2(-dx, dz))
        val relative = Math.floorMod(Math.round((targetYaw - viewerYaw) / 45.0).toInt(), 8)
        return ARROWS[relative]
    }

    /** 132.4 → "132m", 1530 → "1.5km" */
    fun distance(meters: Double): String =
        if (meters < 1000) "${meters.toInt()}m" else "%.1fkm".format(meters / 1000)
}