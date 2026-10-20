package numunun.ouroboros.core

import numunun.ouroboros.config.ShrinkPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShrinkScheduleTest {

    private fun ShrinkSchedule.run(seconds: Int): List<ShrinkEvent> = (1..seconds).flatMap { tick() }

    @Test
    fun `waits then starts shrinking`() {
        val schedule = ShrinkSchedule(listOf(ShrinkPhase(5, 1000.0, 10)))
        assertTrue(schedule.run(4).isEmpty())
        assertEquals(listOf<ShrinkEvent>(ShrinkEvent.Started(0, 1000.0, 10)), schedule.run(1))
        assertTrue(schedule.isShrinking)
        assertEquals(1000.0, schedule.shrinkingTarget)
    }

    @Test
    fun `warns before shrinking`() {
        val schedule = ShrinkSchedule(listOf(ShrinkPhase(400, 1000.0, 10)))
        val warnings = schedule.run(399).filterIsInstance<ShrinkEvent.Warning>().map { it.secondsLeft }
        assertEquals(listOf(300, 60, 10), warnings)
    }

    @Test
    fun `moves through every phase and finishes`() {
        val schedule = ShrinkSchedule(listOf(ShrinkPhase(1, 1000.0, 2), ShrinkPhase(1, 500.0, 2)))
        val events = schedule.run(6)
        assertEquals(
            listOf(
                ShrinkEvent.Started(0, 1000.0, 2),
                ShrinkEvent.Finished(0, 1000.0),
                ShrinkEvent.Started(1, 500.0, 2),
                ShrinkEvent.Finished(1, 500.0),
                ShrinkEvent.AllDone,
            ),
            events,
        )
        assertEquals(ShrinkSchedule.Stage.DONE, schedule.stage)
        assertTrue(schedule.run(100).isEmpty())
    }

    @Test
    fun `no target is forecast while waiting`() {
        val schedule = ShrinkSchedule(listOf(ShrinkPhase(10, 1000.0, 10)))
        schedule.run(3)
        assertFalse(schedule.isShrinking)
        assertNull(schedule.shrinkingTarget)
        assertEquals(7, schedule.secondsUntilShrink)
    }

    @Test
    fun `empty schedule is already done`() {
        val schedule = ShrinkSchedule(emptyList())
        assertEquals(ShrinkSchedule.Stage.DONE, schedule.stage)
        assertTrue(schedule.run(5).isEmpty())
    }

    @Test
    fun `zero wait starts on the first tick`() {
        val schedule = ShrinkSchedule(listOf(ShrinkPhase(0, 1000.0, 10)))
        assertEquals(listOf<ShrinkEvent>(ShrinkEvent.Started(0, 1000.0, 10)), schedule.run(1))
    }

    @Test
    fun `restore continues from the saved point`() {
        val schedule = ShrinkSchedule(listOf(ShrinkPhase(1, 1000.0, 5), ShrinkPhase(1, 500.0, 5)))
        schedule.restore(phaseIndex = 1, stage = ShrinkSchedule.Stage.SHRINKING, elapsed = 4)
        assertEquals(500.0, schedule.shrinkingTarget)
        assertEquals(listOf(ShrinkEvent.Finished(1, 500.0), ShrinkEvent.AllDone), schedule.run(1))
    }

    @Test
    fun `stage progress is reported for the hud`() {
        val schedule = ShrinkSchedule(listOf(ShrinkPhase(10, 1000.0, 20)))
        schedule.run(4)
        assertEquals(10, schedule.stageLength)
        assertEquals(6, schedule.stageSecondsLeft)
        assertEquals(1000.0, schedule.currentTarget)

        schedule.run(6 + 5)
        assertEquals(20, schedule.stageLength)
        assertEquals(15, schedule.stageSecondsLeft)

        schedule.run(15)
        assertNull(schedule.stageLength)
        assertNull(schedule.currentTarget)
    }
}