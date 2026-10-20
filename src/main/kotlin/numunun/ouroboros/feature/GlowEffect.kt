package numunun.ouroboros.feature

import org.bukkit.entity.Player

/**
 * [viewer] 의 화면에서만 [target] 을 빛나게 한다.
 * 패킷이 필요해서 8단계에서 구현을 끼운다. 그 전에는 아무것도 안 한다.
 */
fun interface GlowEffect {
    fun show(viewer: Player, target: Player, ticks: Int)

    companion object {
        val NONE = GlowEffect { _, _, _ -> }
    }
}