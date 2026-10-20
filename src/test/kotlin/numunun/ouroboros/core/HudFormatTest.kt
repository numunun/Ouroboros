package numunun.ouroboros.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HudFormatTest {

    @Test
    fun `clock formats minutes and hours`() {
        assertEquals("0:00", HudFormat.clock(0))
        assertEquals("4:32", HudFormat.clock(272))
        assertEquals("1:02:03", HudFormat.clock(3723))
        assertEquals("0:00", HudFormat.clock(-5))
    }

    @Test
    fun `ratio is clamped and safe for zero length`() {
        assertEquals(0.5f, HudFormat.ratio(30, 60))
        assertEquals(1f, HudFormat.ratio(10, 0))
        assertEquals(0f, HudFormat.ratio(-1, 60))
    }

    @Test
    fun `the chain is rotated so my color comes first`() {
        val chain = listOf("red", "gold", "yellow", "green")
        assertEquals(listOf("yellow", "green", "red", "gold"), HudFormat.rotateFrom(chain, "yellow"))
        assertEquals(chain, HudFormat.rotateFrom(chain, "red"))
    }

    @Test
    fun `an unknown start keeps the original order`() {
        val chain = listOf("red", "gold")
        assertEquals(chain, HudFormat.rotateFrom(chain, null))
        assertEquals(chain, HudFormat.rotateFrom(chain, "blue"))
    }

    @Test
    fun `arrow is relative to where the viewer is facing`() {
        // facing south (yaw 0)
        assertEquals("↑", HudFormat.arrow(0.0, 10.0, 0f))
        assertEquals("↓", HudFormat.arrow(0.0, -10.0, 0f))
        assertEquals("→", HudFormat.arrow(-10.0, 0.0, 0f)) // west is on the right when facing south
        assertEquals("←", HudFormat.arrow(10.0, 0.0, 0f))
        assertEquals("↗", HudFormat.arrow(-10.0, 10.0, 0f))
        // facing west (yaw 90): the same west target is straight ahead
        assertEquals("↑", HudFormat.arrow(-10.0, 0.0, 90f))
        // yaw outside 0..360 is fine
        assertEquals("↑", HudFormat.arrow(-10.0, 0.0, -270f))
    }

    @Test
    fun `distance switches to kilometers`() {
        assertEquals("132m", HudFormat.distance(132.4))
        assertEquals("1.5km", HudFormat.distance(1530.0))
    }
}