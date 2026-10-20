package numunun.ouroboros.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BorderGeometryTest {

    @Test
    fun `edge distance is positive inside and negative outside`() {
        assertEquals(40.0, BorderGeometry.edgeDistance(60.0, 0.0, 0.0, 0.0, 100.0))
        assertEquals(-10.0, BorderGeometry.edgeDistance(0.0, -110.0, 0.0, 0.0, 100.0))
    }

    @Test
    fun `edge distance uses the nearest side`() {
        assertEquals(5.0, BorderGeometry.edgeDistance(10.0, 95.0, 0.0, 0.0, 100.0))
    }

    @Test
    fun `edge distance respects a moved center`() {
        assertEquals(100.0, BorderGeometry.edgeDistance(1000.0, 1000.0, 1000.0, 1000.0, 100.0))
    }

    @Test
    fun `points well inside are not moved`() {
        assertEquals(30.0 to -40.0, BorderGeometry.clampInside(30.0, -40.0, 0.0, 0.0, 100.0, 16.0))
    }

    @Test
    fun `points outside are pulled in by the margin and keep the other axis`() {
        assertEquals(84.0 to 10.0, BorderGeometry.clampInside(250.0, 10.0, 0.0, 0.0, 100.0, 16.0))
        assertEquals(-84.0 to -84.0, BorderGeometry.clampInside(-500.0, -500.0, 0.0, 0.0, 100.0, 16.0))
    }

    @Test
    fun `a border smaller than the margin pulls to the center`() {
        assertEquals(5.0 to 5.0, BorderGeometry.clampInside(50.0, -50.0, 5.0, 5.0, 8.0, 16.0))
    }

    @Test
    fun `a clamped point is always at least margin away from the edge`() {
        val (x, z) = BorderGeometry.clampInside(999.0, 3.0, 0.0, 0.0, 100.0, 16.0)
        assertTrue(BorderGeometry.edgeDistance(x, z, 0.0, 0.0, 100.0) >= 16.0)
    }
}