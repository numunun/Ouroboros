package numunun.ouroboros.world

import numunun.ouroboros.core.Dimension
import numunun.ouroboros.core.Spot
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World

val World.dimension: Dimension
    get() = when (environment) {
        World.Environment.NORMAL -> Dimension.OVERWORLD
        World.Environment.NETHER -> Dimension.NETHER
        else -> Dimension.OTHER
    }

fun Location.toSpot(): Spot = Spot(world.uid, world.dimension, x, y, z)

fun Spot.toLocation(): Location? = Bukkit.getWorld(worldId)?.let { Location(it, x, y, z) }