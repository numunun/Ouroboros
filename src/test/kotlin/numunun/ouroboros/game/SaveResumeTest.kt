package numunun.ouroboros.game

import numunun.ouroboros.support.TestServices
import numunun.ouroboros.support.testSettings
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveResumeTest {

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

    private val settings
        get() = testSettings(
            "start.scatter" to "false",
            "border.size" to "400",
            "save.auto-save-minutes" to "0",
            phases = listOf(mapOf("wait" to 600, "to" to 100, "over" to 60)),
        )

    @Test
    fun `resume saves the restored border and shrink progress, not a half initialized state`() {
        val world = server.getWorld("world")!!
        val originalSize = world.worldBorder.size

        // first server run: play 30 seconds, then shut down
        val first = TestServices(plugin, settings)
        assertEquals(StartResult.Started, first.game.start(List(3) { server.addPlayer() }))
        server.scheduler.performTicks(20L * 30)
        val progress = assertNotNull(first.shrink.snapshot())
        first.saves.suspend()
        assertEquals(originalSize, world.worldBorder.size, "shutdown restores the original border")

        // second server run
        val second = TestServices(plugin, settings)
        assertNull(second.saves.resume())
        assertEquals(400.0, world.worldBorder.size)

        val saved = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "session.yml"))
        assertEquals(400.0, saved.getDouble("border.size"))
        assertTrue(saved.contains("border.originals.${world.uid}"), "original border must survive the resume")
        assertEquals(progress.phase, saved.getInt("shrink.phase"))
        assertEquals(progress.elapsed, saved.getInt("shrink.elapsed"))
    }

    @Test
    fun `stopping keeps the save and only a victory deletes it`() {
        val services = TestServices(plugin, settings)
        services.game.start(List(2) { server.addPlayer() })
        val file = File(plugin.dataFolder, "session.yml")

        services.saves.save()
        services.game.stop()
        assertTrue(file.exists(), "/ouro stop must keep the save")

        assertNull(TestServices(plugin, settings).saves.resume())
    }

    @Test
    fun `an unsupported save version is refused and left untouched`() {
        val file = File(plugin.dataFolder, "session.yml")
        file.parentFile.mkdirs()
        file.writeText("version: 999\n")

        val reason = TestServices(plugin, settings).saves.resume()
        assertNotNull(reason)
        assertTrue("지원하지 않는 저장 형식" in reason)
        assertEquals("version: 999\n", file.readText())
    }
}