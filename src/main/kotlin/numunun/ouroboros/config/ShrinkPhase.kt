package numunun.ouroboros.config

/**
 * 보더 축소 한 단계.
 *
 * @param waitSeconds 이전 단계가 끝난 뒤 대기
 * @param targetSize  오버월드 기준 목표 지름 (네더는 서버 쪽에서 1/8)
 * @param overSeconds 줄어드는 데 걸리는 시간
 */
data class ShrinkPhase(
    val waitSeconds: Int,
    val targetSize: Double,
    val overSeconds: Int,
) {
    fun toFile(): Map<String, Any> = linkedMapOf("wait" to waitSeconds, "to" to targetSize, "over" to overSeconds)

    companion object {
        const val PATH = "border.shrink.phases"

        val DEFAULTS: List<ShrinkPhase> = listOf(
            ShrinkPhase(1800, 8000.0, 3600),
            ShrinkPhase(600, 3000.0, 2400),
            ShrinkPhase(600, 800.0, 1800),
            ShrinkPhase(300, 200.0, 900),
        )

        const val COMMENT = "보더 축소 단계. wait: 앞 단계 종료 후 대기(초) / to: 목표 지름 / over: 축소에 걸리는 시간(초)"

        /**
         * 파일 값을 해석한다. 잘못된 단계는 건너뛰고 경고에 담는다.
         * 목표 지름은 앞 단계보다 작아야 한다. 커지는 단계는 의도와 다르므로 버린다.
         */
        fun parse(raw: Any?, warnings: MutableList<String>): List<ShrinkPhase>? {
            val list = raw as? List<*> ?: return null
            val phases = ArrayList<ShrinkPhase>()
            list.forEachIndexed { index, entry ->
                val label = "$PATH[${index + 1}]"
                val map = entry as? Map<*, *>
                if (map == null) {
                    warnings += "$label: { wait, to, over } 형식이 아니어서 건너뜁니다."
                    return@forEachIndexed
                }
                val wait = (map["wait"] as? Number)?.toInt()
                val to = (map["to"] as? Number)?.toDouble()
                val over = (map["over"] as? Number)?.toInt()
                if (wait == null || to == null || over == null || wait < 0 || over < 1 || to < 16.0) {
                    warnings += "$label: 값이 잘못되어 건너뜁니다. (wait ≥ 0, over ≥ 1, to ≥ 16)"
                    return@forEachIndexed
                }
                val previous = phases.lastOrNull()?.targetSize
                if (previous != null && to >= previous) {
                    warnings += "$label: 목표 지름($to)이 앞 단계($previous)보다 작지 않아 건너뜁니다."
                    return@forEachIndexed
                }
                phases += ShrinkPhase(wait, to, over)
            }
            return phases
        }
    }
}