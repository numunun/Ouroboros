package numunun.ouroboros.game

import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.world.Surface
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

/**
 * 시작할 때 참가자를 보더 안에 흩어 놓는다.
 * 청크를 비동기로 불러오므로 보더가 커도 서버가 멈추지 않는다.
 */
class Scatter(private val settings: SettingValues) {

    fun scatter(players: List<Player>) {
        val world = Bukkit.getWorlds().firstOrNull { it.environment == World.Environment.NORMAL } ?: return
        val radius = scatterRadius()
        val centerX = settings[Keys.BORDER_CENTER_X]
        val centerZ = settings[Keys.BORDER_CENTER_Z]

        players.forEach { player ->
            findSpot(world, centerX, centerZ, radius, ATTEMPTS).thenAccept { spot ->
                if (!player.isOnline) return@thenAccept
                player.teleportAsync(spot ?: world.spawnLocation).thenRun { player.fallDistance = 0f }
            }
        }
    }

    private fun scatterRadius(): Double {
        val half = if (settings[Keys.BORDER_ENABLED]) settings[Keys.BORDER_SIZE] / 2.0 else DEFAULT_RADIUS
        return (half * 0.9 - EDGE_MARGIN).coerceAtLeast(0.0)
    }

    private fun findSpot(
        world: World,
        centerX: Double,
        centerZ: Double,
        radius: Double,
        attemptsLeft: Int,
    ): CompletableFuture<Location?> {
        if (attemptsLeft <= 0) return CompletableFuture.completedFuture(null)

        val x = (centerX + offset(radius)).toInt()
        val z = (centerZ + offset(radius)).toInt()

        return world.getChunkAtAsync(x shr 4, z shr 4).thenCompose { _ ->
            val spot = Surface.standOn(world, x, z)
            if (spot != null) CompletableFuture.completedFuture<Location?>(spot)
            else findSpot(world, centerX, centerZ, radius, attemptsLeft - 1)
        }
    }

    private fun offset(radius: Double): Double =
        if (radius <= 0.0) 0.0 else Random.nextDouble(-radius, radius)

    companion object {
        private const val ATTEMPTS = 24
        private const val DEFAULT_RADIUS = 500.0
        private const val EDGE_MARGIN = 32.0
    }
}