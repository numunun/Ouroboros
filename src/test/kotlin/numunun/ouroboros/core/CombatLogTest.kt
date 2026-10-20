package numunun.ouroboros.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CombatLogTest {

    private val hunter = UUID.randomUUID()
    private val prey = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private val window = 8_000L

    @Test
    fun `direct killer always wins`() {
        val log = CombatLog()
        log.recordHit(other, prey, now = 0)
        assertEquals(hunter, log.killerOf(prey, direct = hunter, now = 1_000, windowMillis = window))
    }

    @Test
    fun `indirect death within the window is credited to the last valid hitter`() {
        val log = CombatLog()
        log.recordHit(hunter, prey, now = 0)
        assertEquals(hunter, log.killerOf(prey, direct = null, now = 7_999, windowMillis = window))
    }

    @Test
    fun `indirect death after the window is not credited`() {
        val log = CombatLog()
        log.recordHit(hunter, prey, now = 0)
        assertNull(log.killerOf(prey, direct = null, now = 8_001, windowMillis = window))
    }

    @Test
    fun `zero window only accepts direct kills`() {
        val log = CombatLog()
        log.recordHit(hunter, prey, now = 0)
        assertNull(log.killerOf(prey, direct = null, now = 1, windowMillis = 0))
    }

    @Test
    fun `self inflicted death falls back to the last valid hitter`() {
        val log = CombatLog()
        log.recordHit(hunter, prey, now = 0)
        assertEquals(hunter, log.killerOf(prey, direct = prey, now = 3_000, windowMillis = window))
    }

    @Test
    fun `the latest hit replaces the earlier one`() {
        val log = CombatLog()
        log.recordHit(other, prey, now = 0)
        log.recordHit(hunter, prey, now = 1_000)
        assertEquals(hunter, log.killerOf(prey, direct = null, now = 2_000, windowMillis = window))
    }

    @Test
    fun `old hits do not carry over to the next life`() {
        val log = CombatLog()
        log.recordHit(hunter, prey, now = 0)
        log.forget(prey) // died and respawned
        assertNull(log.killerOf(prey, direct = null, now = 1_000, windowMillis = window))
    }

    @Test
    fun `combat lock covers both sides including blocked hits`() {
        val log = CombatLog()
        log.recordEngagement(prey, hunter, now = 0)
        assertTrue(log.inCombat(hunter, now = 9_999, windowMillis = 10_000))
        assertTrue(log.inCombat(prey, now = 9_999, windowMillis = 10_000))
        assertFalse(log.inCombat(prey, now = 10_000, windowMillis = 10_000))
        assertFalse(log.inCombat(other, now = 0, windowMillis = 10_000))
    }

    /**
     * Bug 2, end to end: owner2 (RED) hits owner1 (GOLD) in passing, kills slave1,
     * and owner1 later dies to a mob after the credit window. Nobody gets captured.
     */
    @Test
    fun `bug 2 scenario does not capture owner1`() {
        val owner2 = UUID.randomUUID()
        val owner1 = UUID.randomUUID()
        val slave1 = UUID.randomUUID()
        val teams = listOf(
            ColorTeam(ChainColor.RED, owner2),
            ColorTeam(ChainColor.GOLD, owner1).apply { slaves += slave1 },
            ColorTeam(ChainColor.YELLOW, UUID.randomUUID()),
        )
        val session = GameSession(teams.flatMap { it.members }.toSet(), teams)
        val log = CombatLog()

        log.recordHit(owner2, owner1, now = 0)
        log.recordHit(owner2, slave1, now = 500)

        val slaveKiller = log.killerOf(slave1, direct = owner2, now = 1_000, windowMillis = window)
        assertEquals(DeathVerdict.HUNTED, session.judgeDeath(slave1, slaveKiller))
        log.forget(slave1)

        val ownerKiller = log.killerOf(owner1, direct = null, now = 20_000, windowMillis = window)
        assertEquals(DeathVerdict.NORMAL, session.judgeDeath(owner1, ownerKiller))
    }
}