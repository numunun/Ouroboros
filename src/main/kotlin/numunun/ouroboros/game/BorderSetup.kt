package numunun.ouroboros.game

import numunun.ouroboros.core.BorderSnapshot
import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import org.bukkit.Bukkit
import org.bukkit.World
import java.util.UUID

/** 게임 중 보더 적용과 원복. 축소는 6단계에서 따로 맡는다. */
class BorderSetup(private val settings: SettingValues) {

    private class Saved(val centerX: Double, val centerZ: Double, val size: Double)

    private val saved = HashMap<UUID, Saved>()

    /** 오버월드 1 블록이 이 월드에서 몇 분의 1 인가. 네더(축소 켬)는 8. */
    fun scaleOf(world: World): Double =
        if (world.environment == World.Environment.NETHER && settings[Keys.BORDER_NETHER_SCALED]) 8.0 else 1.0

    /** 오버월드 기준 지름을 이 월드 기준으로 바꾼다. */
    fun sizeIn(world: World, overworldSize: Double): Double = overworldSize / scaleOf(world)

    fun apply() {
        if (!settings[Keys.BORDER_ENABLED]) return
        restore()

        Bukkit.getWorlds().forEach { world ->
            val border = world.worldBorder
            saved[world.uid] = Saved(border.center.x, border.center.z, border.size)

            // 크기만이 아니라 중심도 같은 비율로 옮겨야 네더 보더가 제자리에 생긴다.
            val scale = scaleOf(world)
            border.setCenter(settings[Keys.BORDER_CENTER_X] / scale, settings[Keys.BORDER_CENTER_Z] / scale)
            border.size = settings[Keys.BORDER_SIZE] / scale
        }
    }

    fun restore() {
        if (saved.isEmpty()) return
        Bukkit.getWorlds().forEach { world ->
            val previous = saved[world.uid] ?: return@forEach
            world.worldBorder.setCenter(previous.centerX, previous.centerZ)
            world.worldBorder.size = previous.size
        }
        saved.clear()
    }

    /** 게임 전 원래 보더. 저장용 */
    fun originals(): Map<UUID, BorderSnapshot> =
        saved.mapValues { (_, it) -> BorderSnapshot(it.centerX, it.centerZ, it.size) }

    /** 이어하기. 원래 보더 기억을 되살리고, 저장 시점 크기로 맞춘다. */
    fun resume(originals: Map<UUID, BorderSnapshot>, overworldSize: Double?) {
        if (!settings[Keys.BORDER_ENABLED]) return
        saved.clear()
        originals.forEach { (id, it) -> saved[id] = Saved(it.centerX, it.centerZ, it.size) }

        Bukkit.getWorlds().forEach { world ->
            val border = world.worldBorder
            saved.putIfAbsent(world.uid, Saved(border.center.x, border.center.z, border.size))
            val scale = scaleOf(world)
            border.setCenter(settings[Keys.BORDER_CENTER_X] / scale, settings[Keys.BORDER_CENTER_Z] / scale)
            border.size = (overworldSize ?: settings[Keys.BORDER_SIZE]) / scale
        }
    }
}