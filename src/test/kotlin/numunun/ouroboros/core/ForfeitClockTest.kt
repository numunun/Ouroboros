package numunun.ouroboros.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForfeitClockTest {

    private val red = UUID.randomUUID()
    private val gold = UUID.randomUUID()
    private val yellow = UUID.randomUUID()

    private fun session(): GameSession {
        val teams = listOf(
            ColorTeam(ChainColor.RED, red),
            ColorTeam(ChainColor.GOLD, gold),
            ColorTeam(ChainColor.YELLOW, yellow),
        )
        return GameSession(setOf(red, gold, yellow), teams)
    }

    private fun online(vararg ids: UUID): (UUID) -> Boolean = { it in ids }

    @Test
    fun `counts only dormant colors`() {
        val s = session()
        val clock = ForfeitClock()
        s.updatePresence(online(red, yellow))

        repeat(3) { clock.tick(s, 1000) }
        assertEquals(3000, clock.dormantMillis(ChainColor.GOLD))
        assertNull(clock.dormantMillis(ChainColor.RED))
    }

    @Test
    fun `pauses while nobody is active`() {
        val s = session()
        val clock = ForfeitClock()
        s.updatePresence(online(red))
        clock.tick(s, 1000)

        s.updatePresence(online()) // server is empty
        repeat(10) { clock.tick(s, 1000) }
        assertEquals(1000, clock.dormantMillis(ChainColor.GOLD))
    }

    @Test
    fun `resets when the owner comes back`() {
        val s = session()
        val clock = ForfeitClock()
        s.updatePresence(online(red, yellow))
        clock.tick(s, 5000)

        s.updatePresence(online(red, gold, yellow))
        clock.tick(s, 1000)
        assertNull(clock.dormantMillis(ChainColor.GOLD))
    }

    @Test
    fun `expired returns the longest dormant color over the limit`() {
        val s = session()
        val clock = ForfeitClock()
        s.updatePresence(online(red, yellow))
        clock.tick(s, 4000)
        s.updatePresence(online(red))
        clock.tick(s, 4000)

        assertEquals(8000, clock.dormantMillis(ChainColor.GOLD))
        assertEquals(4000, clock.dormantMillis(ChainColor.YELLOW))
        assertEquals(ChainColor.GOLD, clock.expired(4000))
        assertNull(clock.expired(9000))
    }

    @Test
    fun `zero limit never expires`() {
        val s = session()
        val clock = ForfeitClock()
        s.updatePresence(online(red))
        clock.tick(s, 1_000_000)
        assertNull(clock.expired(0))
    }

    @Test
    fun `absorbed colors are dropped`() {
        val s = session()
        val clock = ForfeitClock()
        s.updatePresence(online(red, yellow))
        clock.tick(s, 1000)

        s.absorb(ChainColor.RED, ChainColor.GOLD)
        clock.tick(s, 1000)
        assertNull(clock.dormantMillis(ChainColor.GOLD))
    }

    @Test
    fun `tick reports only colors whose time actually advanced`() {
        val s = session()
        val clock = ForfeitClock()
        s.updatePresence(online(red, yellow))
        assertEquals(setOf(ChainColor.GOLD), clock.tick(s, 1000))

        s.updatePresence(online()) // nobody waiting, clock paused
        assertEquals(emptySet(), clock.tick(s, 1000))
    }
}