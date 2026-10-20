package numunun.ouroboros.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory config file. Dotted paths are used as plain keys. */
private class MapSource(initial: Map<String, Any?> = emptyMap()) : ConfigSource {
    val data = LinkedHashMap<String, Any?>(initial)
    val comments = HashMap<String, List<String>>()

    override fun contains(path: String) = path in data
    override fun get(path: String) = data[path]
    override fun set(path: String, value: Any?) {
        data[path] = value
    }

    override fun setComments(path: String, comments: List<String>) {
        this.comments[path] = comments
    }
}

class SettingValuesTest {

    @Test
    fun `empty file is filled with defaults and comments`() {
        val source = MapSource()
        val report = SettingValues().load(source)

        assertTrue(report.dirty)
        assertEquals(Keys.ALL.size + 1, report.added.size) // + shrink phases
        assertEquals(15, source.data["respawn.delay-seconds"])
        assertEquals("minecraft:diamond", source.data["tracker.item"])
        assertEquals(listOf(Keys.RESPAWN_DELAY.description), source.comments["respawn.delay-seconds"])
    }

    @Test
    fun `complete file is left untouched`() {
        val source = MapSource()
        SettingValues().load(source)
        val second = SettingValues().load(source)
        assertFalse(second.dirty)
        assertTrue(second.warnings.isEmpty())
    }

    @Test
    fun `file values are read with their types`() {
        val source = MapSource()
        SettingValues().load(source)
        source.data["respawn.delay-seconds"] = 30
        source.data["border.size"] = 5000 // an integer is still read as Double
        source.data["tracker.item"] = "EMERALD"

        val settings = SettingValues()
        settings.load(source)
        assertEquals(30, settings[Keys.RESPAWN_DELAY])
        assertEquals(5000.0, settings[Keys.BORDER_SIZE])
        assertEquals("minecraft:emerald", settings[Keys.TRACKER_ITEM])
    }

    @Test
    fun `invalid values fall back to defaults and the file is kept as is`() {
        val source = MapSource()
        SettingValues().load(source)
        source.data["respawn.delay-seconds"] = 9999
        source.data["slave.attack-multiplier"] = "a lot"

        val settings = SettingValues()
        val report = settings.load(source)
        assertEquals(15, settings[Keys.RESPAWN_DELAY])
        assertEquals(0.6, settings[Keys.SLAVE_ATTACK_MULTIPLIER])
        assertEquals(2, report.warnings.size)
        assertEquals(9999, source.data["respawn.delay-seconds"])
    }

    @Test
    fun `command input is validated then applied and written to the file`() {
        val source = MapSource()
        val settings = SettingValues()
        settings.load(source)
        var changed: Setting<*>? = null
        settings.onChange { changed = it }

        assertEquals("25", settings.setFromInput(source, "respawn.delay-seconds", "25").getOrThrow())
        assertEquals(25, settings[Keys.RESPAWN_DELAY])
        assertEquals(25, source.data["respawn.delay-seconds"])
        assertEquals(Keys.RESPAWN_DELAY, changed)

        assertTrue(settings.setFromInput(source, "respawn.delay-seconds", "1.5").isFailure)
        assertTrue(settings.setFromInput(source, "respawn.delay-seconds", "-1").isFailure)
        assertTrue(settings.setFromInput(source, "no.such.key", "1").isFailure)
        assertEquals(25, settings[Keys.RESPAWN_DELAY])
    }

    @Test
    fun `booleans accept Korean input`() {
        val source = MapSource()
        val settings = SettingValues()
        settings.load(source)
        settings.setFromInput(source, "display.corpse", "끄기").getOrThrow()
        assertFalse(settings[Keys.CORPSE])
    }

    @Test
    fun `resource keys are normalized`() {
        assertEquals("minecraft:end_rod", SettingType.KeyType.normalize("END_ROD"))
        assertEquals("custom:thing", SettingType.KeyType.normalize("custom:thing"))
        assertNull(SettingType.KeyType.normalize("has a space"))
    }

    @Test
    fun `shrink phases drop invalid and growing entries`() {
        val warnings = ArrayList<String>()
        val parsed = ShrinkPhase.parse(
            listOf(
                mapOf("wait" to 10, "to" to 1000, "over" to 60),
                mapOf("wait" to 10, "to" to 2000, "over" to 60), // grows
                mapOf("wait" to -1, "to" to 500, "over" to 60), // negative wait
                "not a map",
                mapOf("wait" to 0, "to" to 500.0, "over" to 30),
            ),
            warnings,
        )
        assertEquals(listOf(ShrinkPhase(10, 1000.0, 60), ShrinkPhase(0, 500.0, 30)), parsed)
        assertEquals(3, warnings.size)
    }
}