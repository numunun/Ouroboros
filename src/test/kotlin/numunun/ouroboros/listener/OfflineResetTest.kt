package numunun.ouroboros.listener

import numunun.ouroboros.game.RoleService
import numunun.ouroboros.support.TestServices
import numunun.ouroboros.support.testSettings
import org.bukkit.attribute.Attribute
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineResetTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: Plugin

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        server.addSimpleWorld("world")
        plugin = MockBukkit.createMockPlugin()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `a slave who was offline when the game ended is restored on the next join`() {
        val settings = testSettings("start.scatter" to "false")
        val services = TestServices(plugin, settings)
        server.pluginManager.registerEvents(PresenceListener(services.game, services.roles, services.holds), plugin)
        services.game.start(List(3) { server.addPlayer() })

        val session = services.game.session!!
        val hunterColor = session.aliveColors[0]
        val preyColor = session.targetOf(hunterColor)!!
        val prey = server.getPlayer(session.team(preyColor)!!.ownerId) as PlayerMock

        services.game.capture(hunterColor, preyColor)
        val maxHealth = prey.getAttribute(Attribute.MAX_HEALTH)!!
        assertEquals(settings[numunun.ouroboros.config.Keys.SLAVE_MAX_HEARTS] * 2.0, maxHealth.baseValue)
        assertTrue(services.roles.isMarked(prey))

        prey.disconnect()
        services.game.stop()
        prey.reconnect()

        assertEquals(RoleService.DEFAULT_MAX_HEALTH, prey.getAttribute(Attribute.MAX_HEALTH)!!.baseValue)
        assertFalse(services.roles.isMarked(prey))
    }
}