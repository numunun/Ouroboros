package numunun.ouroboros.game

import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.Role
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * 역할에 맞는 실제 플레이어 상태를 적용한다.
 *
 * 우리가 상태를 바꾼 플레이어에게는 표시를 남긴다. 게임이 끝날 때 접속해 있지 않았던 사람도
 * 다음 접속 때 이 표시를 보고 원래대로 되돌린다.
 */
class RoleService(
    plugin: Plugin,
    private val settings: SettingValues,
) {

    private val attackKey = NamespacedKey(plugin, "slave_attack")
    private val freezeKey = NamespacedKey(plugin, "hold_freeze")
    private val markerKey = NamespacedKey(plugin, "touched")

    fun apply(player: Player, role: Role) {
        when (role) {
            Role.OWNER -> {
                setMaxHealth(player, DEFAULT_MAX_HEALTH)
                removeModifier(player, Attribute.ATTACK_DAMAGE, attackKey)
                leaveSpectator(player)
                hideFromLocatorBar(player)
                mark(player)
            }

            Role.SLAVE -> {
                setMaxHealth(player, settings[Keys.SLAVE_MAX_HEARTS] * 2.0)
                val amount = settings[Keys.SLAVE_ATTACK_MULTIPLIER] - 1.0
                if (amount < 0.0) setModifier(player, Attribute.ATTACK_DAMAGE, attackKey, amount)
                else removeModifier(player, Attribute.ATTACK_DAMAGE, attackKey)
                leaveSpectator(player)
                hideFromLocatorBar(player)
                mark(player)
            }

            // 지난 판 노예였던 사람이 관전자로 들어와도 능력치가 남지 않게 먼저 지운다.
            Role.SPECTATOR -> {
                setMaxHealth(player, DEFAULT_MAX_HEALTH)
                removeModifier(player, Attribute.ATTACK_DAMAGE, attackKey)
                unfreeze(player)
                player.gameMode = GameMode.SPECTATOR
                hideFromLocatorBar(player)
                mark(player)
            }

            Role.NONE -> reset(player)
        }
    }

    /** 우리가 바꾼 것 전부 원래대로 */
    fun reset(player: Player) {
        setMaxHealth(player, DEFAULT_MAX_HEALTH)
        removeModifier(player, Attribute.ATTACK_DAMAGE, attackKey)
        unfreeze(player)
        leaveSpectator(player)
        player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE)?.let { it.baseValue = it.defaultValue }
        player.persistentDataContainer.remove(markerKey)
    }

    fun isMarked(player: Player): Boolean =
        player.persistentDataContainer.has(markerKey, PersistentDataType.BYTE)

    /** 부활 유예 동안 못 움직이게. 시선은 건드리지 않는다. */
    fun freeze(player: Player) {
        setModifier(player, Attribute.MOVEMENT_SPEED, freezeKey, -1.0)
        setModifier(player, Attribute.JUMP_STRENGTH, freezeKey, -1.0)
    }

    fun unfreeze(player: Player) {
        removeModifier(player, Attribute.MOVEMENT_SPEED, freezeKey)
        removeModifier(player, Attribute.JUMP_STRENGTH, freezeKey)
    }

    /** 부활 직후 보호. 수중 호흡 + 화염 저항 */
    fun protect(player: Player) {
        val ticks = settings[Keys.RESPAWN_PROTECTION] * 20
        if (ticks <= 0) return
        player.addPotionEffect(PotionEffect(PotionEffectType.WATER_BREATHING, ticks, 0, false, false, true))
        player.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, ticks, 0, false, false, true))
    }

    private fun mark(player: Player) {
        player.persistentDataContainer.set(markerKey, PersistentDataType.BYTE, 1.toByte())
    }

    private fun leaveSpectator(player: Player) {
        if (player.gameMode == GameMode.SPECTATOR) player.gameMode = GameMode.SURVIVAL
    }

    private fun setMaxHealth(player: Player, value: Double) {
        val attribute = player.getAttribute(Attribute.MAX_HEALTH) ?: return
        attribute.baseValue = value
        if (player.health > attribute.value) player.health = attribute.value
    }

    /** MULTIPLY_SCALAR_1: 총합에 (1 + amount) 를 곱한다. -1 이면 0 */
    private fun setModifier(player: Player, attribute: Attribute, key: NamespacedKey, amount: Double) {
        val instance = player.getAttribute(attribute) ?: return
        removeFrom(instance, key)
        instance.addModifier(AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY))
    }

    private fun removeModifier(player: Player, attribute: Attribute, key: NamespacedKey) {
        player.getAttribute(attribute)?.let { removeFrom(it, key) }
    }

    private fun removeFrom(instance: AttributeInstance, key: NamespacedKey) {
        instance.modifiers.filter { it.key == key }.forEach { instance.removeModifier(it) }
    }

    companion object {
        const val DEFAULT_MAX_HEALTH: Double = 20.0
    }

    /** 위치 표시줄에서 이 사람을 안 보이게 한다. 송신 범위 0 */
    private fun hideFromLocatorBar(player: Player) {
        if (!settings[Keys.HIDE_LOCATOR_BAR]) return
        player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE)?.baseValue = 0.0
    }
}