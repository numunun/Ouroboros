package numunun.ouroboros.listener

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import numunun.ouroboros.support.TestServices
import numunun.ouroboros.support.testSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.bukkit.plugin.Plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresenceFlowTest {

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

    private fun PlayerMock.allMessages(): List<String> =
        generateSequence { nextComponentMessage() }
            .map { PlainTextComponentSerializer.plainText().serialize(it) }
            .toList()

    @Test
    fun `when the target owner quits, the hunter moves on to the next color`() {
        val services = TestServices(plugin, testSettings("start.scatter" to "false"))
        server.pluginManager.registerEvents(PresenceListener(services.game, services.roles, services.holds), plugin)
        services.game.start(List(3) { server.addPlayer() })

        val session = services.game.session!!
        val hunterColor = session.aliveColors[0]
        val preyColor = session.targetOf(hunterColor)!!
        val nextColor = session.targetOf(preyColor)!!
        val hunter = server.getPlayer(session.team(hunterColor)!!.ownerId) as PlayerMock
        val prey = server.getPlayer(session.team(preyColor)!!.ownerId) as PlayerMock
        hunter.allMessages() // drop the start messages

        prey.disconnect()

        assertTrue(session.isDormant(preyColor))
        assertEquals(nextColor, session.targetOf(hunterColor))
        assertTrue(hunter.allMessages().any { "목표가 바뀌었습니다" in it }, "the hunter must be told")
    }
}