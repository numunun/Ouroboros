package numunun.ouroboros.listener

import numunun.ouroboros.core.CombatLog
import numunun.ouroboros.core.Role
import numunun.ouroboros.support.TestServices
import numunun.ouroboros.support.testSettings
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

class CaptureFlowTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: Plugin
    private lateinit var services: TestServices
    private val combat = CombatLog()

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        server.addSimpleWorld("world")
        plugin = MockBukkit.createMockPlugin()

        val settings = testSettings("start.scatter" to "false")
        services = TestServices(plugin, settings)
        server.pluginManager.registerEvents(
            DeathListener(plugin, services.game, services.roles, services.holds, services.ground, combat, settings),
            plugin,
        )
        server.pluginManager.registerEvents(services.holds, plugin)
        services.game.start(List(4) { server.addPlayer() })
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun ownerOf(index: Int): PlayerMock {
        val session = services.game.session!!
        return server.getPlayer(session.team(session.aliveColors[index])!!.ownerId) as PlayerMock
    }

    /** Kill [victim] with a recent valid hit from [killer], like a knock into lava. */
    private fun kill(victim: PlayerMock, killer: PlayerMock) {
        combat.recordHit(killer.uniqueId, victim.uniqueId, System.currentTimeMillis())
        victim.health = 0.0
    }

    @Test
    fun `a captured owner gets up on the spot as a slave`() {
        val session = services.game.session!!
        val hunter = ownerOf(0)
        val prey = ownerOf(1)
        val hunterColor = session.colorOf(hunter.uniqueId)

        kill(prey, hunter)

        assertFalse(prey.isDead, "captured owner should be revived like a totem, not die")
        assertEquals(Role.SLAVE, session.roleOf(prey.uniqueId))
        assertEquals(hunterColor, session.colorOf(prey.uniqueId))
    }

    @Test
    fun `a slave killed by a hunter really dies`() {
        val session = services.game.session!!
        val a = ownerOf(0)
        val slave = ownerOf(1)
        val d = ownerOf(3)

        kill(slave, a) // A captures B, so D now hunts A
        assertEquals(Role.SLAVE, session.roleOf(slave.uniqueId))

        kill(slave, d)

        assertTrue(slave.isDead, "a slave killed by a hunter must not be revived on the spot")
        assertTrue(services.holds.isPendingOrHeld(slave.uniqueId), "the slave should get a respawn delay")
    }
}