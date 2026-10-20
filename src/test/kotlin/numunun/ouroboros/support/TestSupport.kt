package numunun.ouroboros.support

import numunun.ouroboros.config.ConfigSource
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.config.ShrinkPhase
import numunun.ouroboros.game.BorderSetup
import numunun.ouroboros.game.BorderShrinkService
import numunun.ouroboros.game.GameService
import numunun.ouroboros.game.RespawnHoldService
import numunun.ouroboros.game.RoleService
import numunun.ouroboros.game.SaveService
import numunun.ouroboros.game.Scatter
import numunun.ouroboros.world.SafeGround
import org.bukkit.plugin.Plugin

/** In-memory config file. */
class MapConfigSource : ConfigSource {
    val data = LinkedHashMap<String, Any?>()
    override fun contains(path: String) = path in data
    override fun get(path: String) = data[path]
    override fun set(path: String, value: Any?) {
        data[path] = value
    }

    override fun setComments(path: String, comments: List<String>) = Unit
}

/** Settings with overrides applied through the same validation as /ouro set. */
fun testSettings(
    vararg overrides: Pair<String, String>,
    phases: List<Map<String, Any>>? = null,
): SettingValues {
    val source = MapConfigSource()
    phases?.let { source.set(ShrinkPhase.PATH, it) }
    val settings = SettingValues()
    settings.load(source)
    overrides.forEach { (key, value) -> settings.setFromInput(source, key, value).getOrThrow() }
    return settings
}

/**
 * The parts of the plugin wired the same way as Ouroboros.onEnable.
 * Creating a second instance simulates a server restart: fresh memory, same files and worlds.
 */
class TestServices(plugin: Plugin, settings: SettingValues) {
    val roles = RoleService(plugin, settings)
    val border = BorderSetup(settings)
    val game = GameService(plugin, settings, roles, border, Scatter(settings))
    val shrink = BorderShrinkService(plugin, settings, border).also { game.registerHook(it) }
    val ground = SafeGround(settings)
    val holds = RespawnHoldService(plugin, game, roles, ground, settings).also { game.registerHook(it) }

    // must stay last, like in Ouroboros.kt
    val saves = SaveService(plugin, game, border, shrink, settings).also { game.registerHook(it) }
}