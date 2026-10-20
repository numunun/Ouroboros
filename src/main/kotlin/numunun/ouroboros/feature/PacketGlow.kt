package numunun.ouroboros.feature

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import com.comphenix.protocol.wrappers.WrappedDataValue
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import numunun.ouroboros.game.GameLifecycleListener
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 보는 사람의 화면에서만 대상을 빛나게 한다 (ProtocolLib).
 *
 * 한 번 보내고 끝내면, 대상이 웅크리거나 달릴 때 서버가 보내는 메타데이터에 덮여 꺼진다.
 * 그래서 나가는 메타데이터 패킷을 가로채 발광 비트를 계속 덧붙인다.
 *
 * 패킷 리스너는 네트워크 스레드에서 돌므로 상태는 동시성 맵에 둔다.
 * 이 클래스는 ProtocolLib 이 있을 때만 만들어야 한다.
 */
class PacketGlow(private val plugin: Plugin) : GlowEffect, GameLifecycleListener, AutoCloseable {

    private val manager = ProtocolLibrary.getProtocolManager()
    private val byteSerializer = WrappedDataWatcher.Registry.get(java.lang.Byte::class.java)

    /** 보는 사람 → (빛나는 엔티티 id → 만료 시각 ms) */
    private val glowing = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, Long>>()

    private val listener = object : PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.ENTITY_METADATA) {
        override fun onPacketSending(event: PacketEvent) {
            if (event.isPlayerTemporary) return
            val targets = glowing[event.player.uniqueId] ?: return
            val until = targets[event.packet.integers.read(0)] ?: return
            if (System.currentTimeMillis() > until) return

            val values = event.packet.dataValueCollectionModifier.read(0) ?: return
            val index = values.indexOfFirst { it.index == FLAGS_INDEX }
            if (index < 0) return

            // 같은 패킷 객체가 여러 사람에게 가므로 복사해서 고친다.
            val copy = values.toMutableList()
            val old = (copy[index].value as Byte).toInt()
            copy[index] = WrappedDataValue(FLAGS_INDEX, byteSerializer, (old or GLOW_BIT).toByte())

            val packet = event.packet.deepClone()
            packet.dataValueCollectionModifier.write(0, copy)
            event.packet = packet
        }
    }

    init {
        manager.addPacketListener(listener)
    }

    override fun show(viewer: Player, target: Player, ticks: Int) {
        val until = System.currentTimeMillis() + ticks * 50L
        glowing.computeIfAbsent(viewer.uniqueId) { ConcurrentHashMap() }[target.entityId] = until
        send(viewer, target, glow = true)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val targets = glowing[viewer.uniqueId] ?: return@Runnable
            val current = targets[target.entityId] ?: return@Runnable
            // 그 사이 다시 써서 연장됐으면 그 예약에 맡긴다.
            if (current - System.currentTimeMillis() > EXTEND_TOLERANCE_MILLIS) return@Runnable
            targets.remove(target.entityId)
            if (viewer.isOnline && target.isOnline) send(viewer, target, glow = false)
        }, ticks.toLong())
    }

    override fun onGameEnd() {
        glowing.forEach { (viewerId, targets) ->
            val viewer = Bukkit.getPlayer(viewerId) ?: return@forEach
            targets.keys.forEach { entityId ->
                val target = Bukkit.getOnlinePlayers().firstOrNull { it.entityId == entityId } ?: return@forEach
                targets.remove(entityId)
                send(viewer, target, glow = false)
            }
        }
        glowing.clear()
    }

    override fun close() {
        manager.removePacketListener(listener)
        glowing.clear()
    }

    /** 대상의 현재 상태 그대로, 발광 비트만 켜거나 끈 메타데이터를 보낸다. */
    private fun send(viewer: Player, target: Player, glow: Boolean) {
        val packet = manager.createPacket(PacketType.Play.Server.ENTITY_METADATA)
        packet.integers.write(0, target.entityId)
        packet.dataValueCollectionModifier.write(
            0,
            listOf(WrappedDataValue(FLAGS_INDEX, byteSerializer, flagsOf(target, glow))),
        )
        manager.sendServerPacket(viewer, packet)
    }

    private fun flagsOf(target: Player, glow: Boolean): Byte {
        var flags = 0
        if (target.fireTicks > 0) flags = flags or 0x01
        if (target.isSneaking) flags = flags or 0x02
        if (target.isSprinting) flags = flags or 0x08
        if (target.isSwimming) flags = flags or 0x10
        if (target.isInvisible) flags = flags or 0x20
        if (glow || target.isGlowing) flags = flags or GLOW_BIT
        if (target.isGliding) flags = flags or 0x80
        return flags.toByte()
    }

    companion object {
        /** 엔티티 공용 플래그의 메타데이터 번호 */
        private const val FLAGS_INDEX = 0
        private const val GLOW_BIT = 0x40
        private const val EXTEND_TOLERANCE_MILLIS = 250L
    }
}