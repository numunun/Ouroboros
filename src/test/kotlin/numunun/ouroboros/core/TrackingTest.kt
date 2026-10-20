package numunun.ouroboros.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingTest {

    private val overworld = UUID.randomUUID()
    private val nether = UUID.randomUUID()
    private val end = UUID.randomUUID()
    private val target = UUID.randomUUID()

    private fun over(x: Double, z: Double, y: Double = 64.0) = Spot(overworld, Dimension.OVERWORLD, x, y, z)
    private fun neth(x: Double, z: Double, y: Double = 64.0) = Spot(nether, Dimension.NETHER, x, y, z)

    @Test
    fun `same world points at the target itself`() {
        val t = over(100.0, -50.0)
        assertEquals(Pointer.Exact(t), Tracking.point(over(0.0, 0.0), t, target, PortalMemory()))
    }

    @Test
    fun `bug 1 - a nether target is scaled up instead of pointing near the origin`() {
        val pointer = Tracking.point(over(0.0, 0.0, y = 70.0), neth(150.0, 80.0), target, PortalMemory())
        assertEquals(Pointer.Estimated(over(1200.0, 640.0, y = 70.0)), pointer)
    }

    @Test
    fun `an overworld target is scaled down for a hunter in the nether`() {
        val pointer = Tracking.point(neth(0.0, 0.0, y = 40.0), over(800.0, -160.0), target, PortalMemory())
        assertEquals(Pointer.Estimated(neth(100.0, -20.0, y = 40.0)), pointer)
    }

    @Test
    fun `the portal the target left through wins over the estimate`() {
        val memory = PortalMemory()
        val portal = over(300.0, 300.0)
        memory.recordExit(target, portal)
        assertEquals(Pointer.Portal(portal), Tracking.point(over(0.0, 0.0), neth(10.0, 10.0), target, memory))
    }

    @Test
    fun `a portal from another world is not used`() {
        val memory = PortalMemory()
        memory.recordExit(target, neth(5.0, 5.0)) // left the nether, not the overworld
        val pointer = Tracking.point(over(0.0, 0.0), neth(10.0, 10.0), target, memory)
        assertEquals(Pointer.Estimated(over(80.0, 80.0)), pointer)
    }

    @Test
    fun `the latest exit replaces an older one`() {
        val memory = PortalMemory()
        memory.recordExit(target, over(1.0, 1.0))
        memory.recordExit(target, over(2.0, 2.0))
        assertEquals(Pointer.Portal(over(2.0, 2.0)), Tracking.point(over(0.0, 0.0), neth(0.0, 0.0), target, memory))
    }

    @Test
    fun `the end cannot be estimated`() {
        val inEnd = Spot(end, Dimension.OTHER, 0.0, 64.0, 0.0)
        assertEquals(Pointer.Unknown, Tracking.point(over(0.0, 0.0), inEnd, target, PortalMemory()))
    }
}