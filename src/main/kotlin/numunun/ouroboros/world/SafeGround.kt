package numunun.ouroboros.world

import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.BorderGeometry
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import kotlin.math.floor

/**
 * "여기 있어도 죽지 않는가" 를 판단하고, 아니면 가까운 안전한 자리를 준다.
 *
 * 옛 버전은 "설 수 있는 자리" 를 너무 엄격하게 봐서, 반 블록이나 눈 쌓인 칸 위에 서 있어도
 * 실패로 보고 지표면으로 옮겨 버렸다. 이제는 정말 위험할 때만 옮긴다.
 */
class SafeGround(private val settings: SettingValues) {

    /** 보더 절반 크기 예보. 진행 중인 축소가 끝났을 때 크기 */
    var safeHalfSize: (World) -> Double = { it.worldBorder.size / 2.0 }

    /** 그대로 둬도 되는가. 공허, 보더 밖, 용암, 머리가 블록에 낀 경우만 false */
    fun isSafeEnough(location: Location): Boolean {
        val world = location.world
        if (location.y < world.minHeight) return false

        val border = world.worldBorder
        val edge = BorderGeometry.edgeDistance(location.x, location.z, border.center.x, border.center.z, border.size / 2.0)
        if (edge < 0.5) return false

        val feet = location.block
        val head = feet.getRelative(BlockFace.UP)
        if (feet.type == Material.LAVA || head.type == Material.LAVA) return false
        return !head.type.isOccluding
    }

    /**
     * @param forecast true 면 축소가 끝났을 때 크기 기준 (부활 위치), false 면 지금 크기 기준
     * @param margin   보더 끝에서 띄울 거리. 0 이면 보더 안이기만 하면 된다
     */
    fun resolve(
        location: Location,
        forecast: Boolean = true,
        margin: Double = settings[Keys.RESCUE_MARGIN],
    ): Location {
        val world = location.world
        val center = world.worldBorder.center
        val half = if (forecast) safeHalfSize(world) else world.worldBorder.size / 2.0
        val usableMargin = margin.coerceAtMost(half / 2.0)

        val inside = BorderGeometry.edgeDistance(location.x, location.z, center.x, center.z, half) >= usableMargin
        if (inside && isSafeEnough(location)) return location

        val (x, z) = if (inside) {
            location.x to location.z
        } else {
            BorderGeometry.clampInside(location.x, location.z, center.x, center.z, half, usableMargin)
        }
        if (inside) {
            Surface.nearestStandable(world, location.blockX, location.blockY, location.blockZ)?.let {
                it.yaw = location.yaw
                it.pitch = location.pitch
                return it
            }
        }
        return standNear(world, x, z, location) ?: fallback(world, location)
    }

    /** (x, z) 근처의 설 자리. 시선은 [facing] 을 따른다. */
    fun standNear(world: World, x: Double, z: Double, facing: Location): Location? =
        Surface.findNear(world, floor(x).toInt(), floor(z).toInt(), facing.blockY)?.apply {
            yaw = facing.yaw
            pitch = facing.pitch
        }

    private fun fallback(world: World, facing: Location): Location {
        val center = world.worldBorder.center
        return standNear(world, center.x, center.z, facing) ?: world.spawnLocation
    }
}