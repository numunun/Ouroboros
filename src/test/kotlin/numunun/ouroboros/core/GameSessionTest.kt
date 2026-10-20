package numunun.ouroboros.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chain: RED -> GOLD -> YELLOW -> GREEN -> (RED)
 *
 * Names match the original bug report:
 * - owner2 / slave2 = RED  (hunts GOLD)
 * - owner1 / slave1 = GOLD
 */
class GameSessionTest {

    private val owner2 = UUID.randomUUID()
    private val slave2 = UUID.randomUUID()
    private val owner1 = UUID.randomUUID()
    private val slave1 = UUID.randomUUID()
    private val ownerY = UUID.randomUUID()
    private val ownerG = UUID.randomUUID()

    private fun session(): GameSession {
        val teams = listOf(
            ColorTeam(ChainColor.RED, owner2).apply { slaves += slave2 },
            ColorTeam(ChainColor.GOLD, owner1).apply { slaves += slave1 },
            ColorTeam(ChainColor.YELLOW, ownerY),
            ColorTeam(ChainColor.GREEN, ownerG),
        )
        return GameSession(teams.flatMap { it.members }.toSet(), teams)
    }

    /** Only these players are online. */
    private fun online(vararg ids: UUID): (UUID) -> Boolean = { it in ids }

    // ── Bug 2: killing a slave absorbed the owner's team ──

    @Test
    fun `owner2 killing slave1 is not a capture`() {
        assertEquals(DeathVerdict.HUNTED, session().judgeDeath(slave1, owner2))
    }

    @Test
    fun `owner2 killing owner1 is a capture`() {
        assertEquals(DeathVerdict.CAPTURE, session().judgeDeath(owner1, owner2))
    }

    @Test
    fun `slave2 killing owner1 is also a capture`() {
        assertEquals(DeathVerdict.CAPTURE, session().judgeDeath(owner1, slave2))
    }

    @Test
    fun `prey killing its hunter is a normal death`() {
        assertEquals(DeathVerdict.NORMAL, session().judgeDeath(owner2, owner1))
    }

    @Test
    fun `death by an unrelated team is a normal death`() {
        // RED and YELLOW are neither target nor hunter of each other.
        assertEquals(DeathVerdict.NORMAL, session().judgeDeath(ownerY, owner2))
    }

    @Test
    fun `unknown killer or suicide is a normal death`() {
        val s = session()
        assertEquals(DeathVerdict.NORMAL, s.judgeDeath(owner1, null))
        assertEquals(DeathVerdict.NORMAL, s.judgeDeath(owner1, owner1))
    }

    @Test
    fun `killing a former owner who became a slave is not a capture`() {
        val s = session()
        s.absorb(ChainColor.GOLD, ChainColor.YELLOW) // ownerY is now a slave of GOLD
        assertEquals(Role.SLAVE, s.roleOf(ownerY))
        assertEquals(DeathVerdict.HUNTED, s.judgeDeath(ownerY, owner2))
    }

    // ── Bug 3: skip to the next color when someone leaves ──

    @Test
    fun `when the target owner leaves the hunter moves to the next color immediately`() {
        val s = session()
        val changes = s.updatePresence(online(owner2, ownerY, ownerG)) // owner1 left

        assertEquals(ChainColor.YELLOW, s.targetOf(ChainColor.RED))
        assertEquals(ChainColor.RED, s.hunterOf(ChainColor.YELLOW))
        assertTrue(TargetChange(ChainColor.RED, ChainColor.GOLD, ChainColor.YELLOW) in changes)
    }

    @Test
    fun `a dormant team can neither attack nor be attacked`() {
        val s = session()
        s.updatePresence(online(owner2, ownerY, ownerG))

        assertEquals(Relation.UNRELATED, s.relation(owner2, slave1))
        assertEquals(Relation.UNRELATED, s.relation(slave1, ownerY))
        assertEquals(DeathVerdict.NORMAL, s.judgeDeath(slave1, owner2))
    }

    @Test
    fun `when the owner returns the chain is restored`() {
        val s = session()
        s.updatePresence(online(owner2, ownerY, ownerG))
        val changes = s.updatePresence(online(owner2, owner1, ownerY, ownerG))

        assertEquals(ChainColor.GOLD, s.targetOf(ChainColor.RED))
        assertTrue(TargetChange(ChainColor.RED, ChainColor.YELLOW, ChainColor.GOLD) in changes)
    }

    @Test
    fun `a team is dormant without its owner even if slaves are online`() {
        val s = session()
        s.updatePresence(online(owner2, slave1, ownerY, ownerG))
        assertTrue(s.isDormant(ChainColor.GOLD))
    }

    @Test
    fun `with two active teams they target each other`() {
        val s = session()
        s.updatePresence(online(owner2, owner1))

        assertEquals(Relation.PREY, s.relation(owner2, owner1))
        assertEquals(Relation.PREY, s.relation(owner1, owner2))
    }

    @Test
    fun `with one active team there is no target`() {
        val s = session()
        s.updatePresence(online(owner2))
        assertNull(s.targetOf(ChainColor.RED))
    }

    @Test
    fun `a long dormant color goes to the nearest active color before it`() {
        val s = session()
        s.updatePresence(online(ownerY, ownerG)) // RED and GOLD are both dormant
        assertEquals(ChainColor.GREEN, s.heirOf(ChainColor.GOLD))
    }

    @Test
    fun `forfeit absorption does not change other teams' targets`() {
        val s = session()
        s.updatePresence(online(owner2, ownerY, ownerG))
        val result = s.absorb(s.heirOf(ChainColor.GOLD)!!, ChainColor.GOLD)

        assertEquals(ChainColor.RED, result.hunter)
        assertTrue(result.changes.isEmpty())
    }

    // ── Absorption and victory ──────────────────────────

    @Test
    fun `absorption enslaves the whole prey team and moves the target forward`() {
        val s = session()
        val result = s.absorb(ChainColor.RED, ChainColor.GOLD)

        assertEquals(listOf(owner1, slave1), result.captured)
        assertEquals(ChainColor.RED, s.colorOf(owner1))
        assertEquals(Role.SLAVE, s.roleOf(owner1))
        assertEquals(ChainColor.YELLOW, s.targetOf(ChainColor.RED))
        assertEquals(listOf(TargetChange(ChainColor.RED, ChainColor.GOLD, ChainColor.YELLOW)), result.changes)
    }

    @Test
    fun `the last color standing wins`() {
        val s = session()
        s.absorb(ChainColor.RED, ChainColor.GOLD)
        s.absorb(ChainColor.RED, ChainColor.YELLOW)
        assertNull(s.winner)
        s.absorb(ChainColor.GREEN, ChainColor.RED)
        assertEquals(ChainColor.GREEN, s.winner)
        assertEquals(6, s.team(ChainColor.GREEN)!!.members.size)
    }

    @Test
    fun `cannot absorb a removed color or itself`() {
        val s = session()
        s.absorb(ChainColor.RED, ChainColor.GOLD)
        assertFailsWith<IllegalArgumentException> { s.absorb(ChainColor.RED, ChainColor.GOLD) }
        assertFailsWith<IllegalArgumentException> { s.absorb(ChainColor.RED, ChainColor.RED) }
    }

    @Test
    fun `a player cannot be on two teams`() {
        assertFailsWith<IllegalArgumentException> {
            GameSession(
                setOf(owner1),
                listOf(ColorTeam(ChainColor.RED, owner1), ColorTeam(ChainColor.GOLD, owner1)),
            )
        }
    }
}