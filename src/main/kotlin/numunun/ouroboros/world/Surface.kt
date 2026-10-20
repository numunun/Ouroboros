package numunun.ouroboros.world

import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace

/** 설 수 있는 자리 찾기 */
object Surface {

    /** 밟으면 다치는 바닥 */
    private val HAZARDS = setOf(
        Material.MAGMA_BLOCK,
        Material.CACTUS,
        Material.CAMPFIRE,
        Material.SOUL_CAMPFIRE,
    )

    /** 네더 기반암 천장 위로 올라가지 않게 */
    private const val NETHER_CEILING = 125

    /**
     * (x, z) 의 지표면 위. 물 위, 용암 위, 위험한 바닥이면 null.
     * 나뭇잎은 무시하고 땅을 찾는다. 하늘이 없는 네더에서는 천장을 잡으므로 쓰면 안 된다.
     */
    fun standOn(world: World, x: Int, z: Int): Location? {
        val ground = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
        if (!isFloor(ground)) return null

        val feet = ground.getRelative(BlockFace.UP)
        if (!isAir(feet) || !isAir(feet.getRelative(BlockFace.UP))) return null

        return ground.location.add(0.5, 1.0, 0.5)
    }

    /** 지금 이 자리에 그대로 서 있어도 되는가 */
    fun canStandAt(location: Location): Boolean {
        val feet = location.block
        return isFloor(feet.getRelative(BlockFace.DOWN)) && isAir(feet) && isAir(feet.getRelative(BlockFace.UP))
    }

    /** (x, z) 기둥에서 [preferY] 와 가까운 높이부터 위아래로 설 자리를 찾는다. */
    fun scanNear(world: World, x: Int, z: Int, preferY: Int, range: Int = 64): Location? {
        val bottom = world.minHeight + 1
        val top = if (world.environment == World.Environment.NETHER) NETHER_CEILING else world.maxHeight - 2
        val start = preferY.coerceIn(bottom, top)

        for (offset in 0..range) {
            for (y in intArrayOf(start - offset, start + offset)) {
                if (y !in bottom..top) continue
                val feet = world.getBlockAt(x, y, z)
                if (isStandable(feet)) return feet.location.add(0.5, 0.0, 0.5)
            }
        }
        return null
    }

    /** 반경 6칸, 위아래 6칸 안의 칸을 가까운 순으로. 한 번만 만든다. */
    private val NEARBY: List<Triple<Int, Int, Int>> by lazy {
        val r = 6
        buildList {
            for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) add(Triple(dx, dy, dz))
        }.sortedBy { (dx, dy, dz) -> dx * dx + dy * dy + dz * dz }
    }

    /** (x, y, z) 바로 근처의 설 자리. 용암에 빠진 자리 등에서 지표면까지 올리지 않고 옆으로만 비킨다. */
    fun nearestStandable(world: World, x: Int, y: Int, z: Int): Location? {
        val bottom = world.minHeight + 1
        val top = if (world.environment == World.Environment.NETHER) NETHER_CEILING else world.maxHeight - 2
        for ((dx, dy, dz) in NEARBY) {
            val feetY = y + dy
            if (feetY !in bottom..top) continue
            val feet = world.getBlockAt(x + dx, feetY, z + dz)
            if (isStandable(feet)) return feet.location.add(0.5, 0.0, 0.5)
        }
        return null
    }

    private fun isStandable(feet: Block): Boolean =
        isFloor(feet.getRelative(BlockFace.DOWN)) && isAir(feet) && isAir(feet.getRelative(BlockFace.UP))

    /** 오버월드는 지표면 우선, 그 외 차원은 높이 근처에서 찾는다. */
    fun findNear(world: World, x: Int, z: Int, preferY: Int): Location? =
        if (world.environment == World.Environment.NORMAL) {
            standOn(world, x, z) ?: scanNear(world, x, z, preferY)
        } else {
            scanNear(world, x, z, preferY)
        }

    private fun isFloor(block: Block): Boolean =
        block.type.isSolid && !block.isLiquid && block.type !in HAZARDS

    private fun isAir(block: Block): Boolean = block.isPassable && !block.isLiquid

    /** (x, z) 의 맨 위가 물이면 그 수면 위. 묻힌 사람을 올릴 때 물이라도 없는 것보단 낫다. */
    fun waterSurface(world: World, x: Int, z: Int): Location? {
        val top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
        if (top.type != Material.WATER) return null
        return top.location.add(0.5, 1.0, 0.5)
    }
}