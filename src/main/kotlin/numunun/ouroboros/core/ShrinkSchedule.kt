package numunun.ouroboros.core

import numunun.ouroboros.config.ShrinkPhase

/** 축소 진행 중에 생기는 일 */
sealed interface ShrinkEvent {
    /** 축소 [secondsLeft] 초 전 */
    data class Warning(val phase: Int, val secondsLeft: Int, val targetSize: Double) : ShrinkEvent

    data class Started(val phase: Int, val targetSize: Double, val overSeconds: Int) : ShrinkEvent

    data class Finished(val phase: Int, val targetSize: Double) : ShrinkEvent

    data object AllDone : ShrinkEvent
}

/**
 * 보더 축소 일정. 1초마다 [tick] 을 부르면 그때 일어난 일을 돌려준다.
 * 실제 보더를 움직이는 건 서버 쪽이 한다.
 */
class ShrinkSchedule(private val phases: List<ShrinkPhase>) {

    enum class Stage { WAITING, SHRINKING, DONE }

    var phaseIndex: Int = 0
        private set

    var stage: Stage = if (phases.isEmpty()) Stage.DONE else Stage.WAITING
        private set

    /** 현재 단계(대기 또는 축소)에서 흐른 초 */
    var elapsed: Int = 0
        private set

    val isShrinking: Boolean get() = stage == Stage.SHRINKING

    private val current: ShrinkPhase? get() = phases.getOrNull(phaseIndex)

    /** 지금 줄어드는 중이면 그 목표 지름. 대기 중이면 null */
    val shrinkingTarget: Double? get() = if (isShrinking) current?.targetSize else null

    /** 지금 단계의 목표 지름 (대기 중이면 다음에 줄어들 크기). 다 끝났으면 null */
    val currentTarget: Double? get() = if (stage == Stage.DONE) null else current?.targetSize

    /** 지금 단계(대기 또는 축소)의 전체 길이(초). 다 끝났으면 null */
    val stageLength: Int?
        get() = when (stage) {
            Stage.WAITING -> current?.waitSeconds
            Stage.SHRINKING -> current?.overSeconds
            Stage.DONE -> null
        }

    /** 지금 단계가 끝나기까지 남은 초. 다 끝났으면 null */
    val stageSecondsLeft: Int? get() = stageLength?.let { (it - elapsed).coerceAtLeast(0) }

    /** 다음 축소까지 남은 초. 대기 중이 아니면 null */
    val secondsUntilShrink: Int? get() = if (stage == Stage.WAITING) current?.let { it.waitSeconds - elapsed } else null

    fun tick(): List<ShrinkEvent> {
        val phase = current ?: return emptyList()
        elapsed++

        return when (stage) {
            Stage.WAITING -> {
                val left = phase.waitSeconds - elapsed
                when {
                    left <= 0 -> {
                        stage = Stage.SHRINKING
                        elapsed = 0
                        listOf(ShrinkEvent.Started(phaseIndex, phase.targetSize, phase.overSeconds))
                    }

                    left in WARN_AT -> listOf(ShrinkEvent.Warning(phaseIndex, left, phase.targetSize))
                    else -> emptyList()
                }
            }

            Stage.SHRINKING -> {
                if (elapsed < phase.overSeconds) return emptyList()
                val finished = ShrinkEvent.Finished(phaseIndex, phase.targetSize)
                phaseIndex++
                elapsed = 0
                if (phaseIndex >= phases.size) {
                    stage = Stage.DONE
                    listOf(finished, ShrinkEvent.AllDone)
                } else {
                    stage = Stage.WAITING
                    listOf(finished)
                }
            }

            Stage.DONE -> emptyList()
        }
    }

    /** 저장했던 진행 상황으로 되돌린다. (9단계 이어하기) */
    fun restore(phaseIndex: Int, stage: Stage, elapsed: Int) {
        this.phaseIndex = phaseIndex.coerceIn(0, phases.size)
        this.stage = if (this.phaseIndex >= phases.size) Stage.DONE else stage
        this.elapsed = elapsed.coerceAtLeast(0)
    }

    companion object {
        /** 이만큼 남았을 때 경고한다(초) */
        val WARN_AT: Set<Int> = setOf(300, 60, 10)
    }
}