package numunun.ouroboros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpotPickerTest {

    @Test
    fun `every candidate is inside the border by the inset`() {
        val spots = SpotPicker.candidates(150.0, 0.0, 0.0, 0.0, halfSize = 100.0, inset = 24.0)
        assertTrue(spots.isNotEmpty())
        assertTrue(spots.all { (x, z) -> BorderGeometry.edgeDistance(x, z, 0.0, 0.0, 100.0) >= 24.0 })
    }

    @Test
    fun `with nobody around the nearest candidate wins`() {
        val origin = 150.0 to 0.0
        val spots = SpotPicker.candidates(origin.first, origin.second, 0.0, 0.0, 100.0, 24.0)
        val best = SpotPicker.rank(spots, others = emptyList(), origin = origin).first()
        assertEquals(76.0 to 0.0, best)
    }

    @Test
    fun `a candidate away from other players is preferred`() {
        val origin = 150.0 to 0.0
        val camper = 76.0 to 0.0 // someone waiting right where the plain clamp would land
        val spots = SpotPicker.candidates(origin.first, origin.second, 0.0, 0.0, 100.0, 24.0)
        val (x, z) = SpotPicker.rank(spots, listOf(camper), origin).first()
        val distance = Math.hypot(x - camper.first, z - camper.second)
        assertTrue(distance >= 40.0, "picked spot is only $distance blocks from the camper")
    }

    @Test
    fun `beyond the enough distance closer spots are preferred`() {
        val origin = 0.0 to 0.0
        val far = 1000.0 to 1000.0
        val ranked = SpotPicker.rank(listOf(10.0 to 0.0, 50.0 to 0.0), listOf(far), origin)
        assertEquals(10.0 to 0.0, ranked.first())
    }
}