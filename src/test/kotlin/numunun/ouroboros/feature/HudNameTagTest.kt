package numunun.ouroboros.feature

import numunun.ouroboros.support.TestServices
import numunun.ouroboros.support.testSettings
import org.bukkit.plugin.Plugin
import org.bukkit.scoreboard.Team
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class HudNameTagTest {

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

    private fun start(hudFirst: Boolean): List<PlayerMock> {
        val settings = testSettings("start.scatter" to "false")
        val services = TestServices(plugin, settings)
        val nameTags = NameTagHider(services.game, settings)
        val hud = HudService(plugin, services.game, services.shrink, nameTags, settings)
        if (hudFirst) {
            services.game.registerHook(hud)
            services.game.registerHook(nameTags)
        } else {
            services.game.registerHook(nameTags)
            services.game.registerHook(hud)
        }

        val players = List(3) { server.addPlayer() }
        services.game.start(players)
        return players
    }

    private fun assertHidden(players: List<PlayerMock>) {
        val names = players.map { it.name }.toSet()
        players.forEach { viewer ->
            val board = viewer.scoreboard
            assertNotSame(server.scoreboardManager.mainScoreboard, board, "HUD should give ${viewer.name} a personal scoreboard")
            val team = assertNotNull(board.getTeam("ouroboros_hidden"), "hidden team missing on ${viewer.name}'s scoreboard")
            assertEquals(Team.OptionStatus.NEVER, team.getOption(Team.Option.NAME_TAG_VISIBILITY))
            assertEquals(names, team.entries)
        }
    }

    @Test
    fun `every personal scoreboard hides all name tags`() = assertHidden(start(hudFirst = false))

    @Test
    fun `name tags stay hidden even if the hud is registered first`() = assertHidden(start(hudFirst = true))
}