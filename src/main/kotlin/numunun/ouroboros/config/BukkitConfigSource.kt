package numunun.ouroboros.config

import org.bukkit.plugin.java.JavaPlugin

/** 실제 config.yml 과의 연결 */
class BukkitConfigSource(private val plugin: JavaPlugin) : ConfigSource {

    private val config get() = plugin.config

    override fun contains(path: String): Boolean = config.contains(path, true)

    override fun get(path: String): Any? = config.get(path)

    override fun set(path: String, value: Any?) = config.set(path, value)

    override fun setComments(path: String, comments: List<String>) = config.setComments(path, comments)
}