package numunun.ouroboros.core

import kotlin.math.abs

/**
 * 접근 경보 범위 계산.
 *
 * 구가 아니라 상자로 잰다. 내가 (10, 20, 10) 이고 범위가 20 이면
 * X -10~30, Y 0~40, Z -10~30 안에 들어온 헌터를 잡는다.
 */
object AlarmZone {

    const val MIN_VOLUME: Float = 0.15f
    const val MAX_VOLUME: Float = 1.0f

    /** 축별 거리 중 가장 큰 값. 이 값이 r 이하면 각 축 ±r 상자 안이다. */
    fun boxDistance(dx: Double, dy: Double, dz: Double): Double = maxOf(abs(dx), abs(dy), abs(dz))

    /** 심장 소리 크기. 범위 끝에서 [MIN_VOLUME], 바로 옆에서 [MAX_VOLUME]. 범위 밖이면 null */
    fun heartbeatVolume(distance: Double, soundRange: Double): Float? {
        if (soundRange <= 0.0 || distance > soundRange) return null
        val closeness = 1.0 - distance / soundRange
        return (MIN_VOLUME + (MAX_VOLUME - MIN_VOLUME) * closeness).toFloat()
    }
}