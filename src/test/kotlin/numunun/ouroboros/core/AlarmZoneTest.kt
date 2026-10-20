package numunun.ouroboros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmZoneTest {

    private fun inBox(me: Triple<Double, Double, Double>, other: Triple<Double, Double, Double>, range: Double) =
        AlarmZone.boxDistance(other.first - me.first, other.second - me.second, other.third - me.third) <= range

    @Test
    fun `the example from the spec is inside on every axis`() {
        val me = Triple(10.0, 20.0, 10.0)
        assertTrue(inBox(me, Triple(-10.0, 0.0, 30.0), 20.0))
        assertTrue(inBox(me, Triple(30.0, 40.0, -10.0), 20.0))
    }

    @Test
    fun `being outside on a single axis is enough to be outside`() {
        val me = Triple(10.0, 20.0, 10.0)
        assertTrue(!inBox(me, Triple(10.0, 41.0, 10.0), 20.0)) // directly above, too high
        assertTrue(!inBox(me, Triple(31.0, 20.0, 10.0), 20.0))
    }

    @Test
    fun `heartbeat gets louder as the hunter comes closer`() {
        val far = AlarmZone.heartbeatVolume(35.0, 40.0)!!
        val near = AlarmZone.heartbeatVolume(5.0, 40.0)!!
        assertTrue(near > far)
        assertEquals(AlarmZone.MAX_VOLUME, AlarmZone.heartbeatVolume(0.0, 40.0))
        assertEquals(AlarmZone.MIN_VOLUME, AlarmZone.heartbeatVolume(40.0, 40.0))
    }

    @Test
    fun `no heartbeat beyond the range or when disabled`() {
        assertNull(AlarmZone.heartbeatVolume(40.1, 40.0))
        assertNull(AlarmZone.heartbeatVolume(1.0, 0.0))
    }
}