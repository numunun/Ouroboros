package numunun.ouroboros.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionSnapshotTest {

    @Test
    fun `a session survives a round trip after captures`() {
        val red = UUID.randomUUID()
        val gold = UUID.randomUUID()
        val yellow = UUID.randomUUID()
        val green = UUID.randomUUID()
        val watcher = UUID.randomUUID()
        val teams = listOf(
            ColorTeam(ChainColor.RED, red),
            ColorTeam(ChainColor.GOLD, gold),
            ColorTeam(ChainColor.YELLOW, yellow),
            ColorTeam(ChainColor.GREEN, green),
        )
        val original = GameSession(setOf(red, gold, yellow, green), teams)
        original.addSpectator(watcher)
        original.absorb(ChainColor.RED, ChainColor.GOLD)

        val snapshot = SessionSnapshot(
            participants = original.participants,
            spectators = original.spectators,
            teams = original.teamSnapshots(),
            dormantMillis = emptyMap(),
            shrink = null,
            borderSize = null,
            borderOriginals = emptyMap(),
        )
        val restored = snapshot.toSession()

        assertEquals(original.aliveColors, restored.aliveColors)
        assertEquals(ChainColor.YELLOW, restored.targetOf(ChainColor.RED))
        assertEquals(Role.SLAVE, restored.roleOf(gold))
        assertEquals(Role.SPECTATOR, restored.roleOf(watcher))
        assertEquals(DeathVerdict.CAPTURE, restored.judgeDeath(yellow, gold))
    }
}