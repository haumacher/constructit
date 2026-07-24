package constructit

import constructit.core.Evaluator
import constructit.dsl.*
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tier 3: Profile — an ordered closed chain that will feed extrude/revolve in phase 2. */
class ProfileTest {

    private fun endpoints(e: ProfileElement): Pair<Vec2, Vec2> = when (e) {
        is ProfileElement.Seg -> e.segment.a to e.segment.b
        is ProfileElement.ArcE -> {
            val a = e.arc; val s = Vec2(a.center.x + a.radius * Math.cos(a.startAngle), a.center.y + a.radius * Math.sin(a.startAngle))
            val t = Vec2(a.center.x + a.radius * Math.cos(a.endAngle), a.center.y + a.radius * Math.sin(a.endAngle))
            s to t
        }
    }

    private fun assertClosedChain(elements: List<ProfileElement>) {
        for (k in elements.indices) {
            val end = endpoints(elements[k]).second
            val nextStart = endpoints(elements[(k + 1) % elements.size]).first
            assertTrue(hypot(end.x - nextStart.x, end.y - nextStart.y) < 1e-6, "chain break at element $k")
        }
    }

    @Test
    fun triangleProfileIsClosed() {
        val c = Construction()
        val a = c.freePoint("A", 0.mm, 0.mm)
        val b = c.freePoint("B", 40.mm, 0.mm)
        val d = c.freePoint("C", 20.mm, 30.mm)
        val prof = c.profile(c.segment(a, b), c.segment(b, d), c.segment(d, a))
        val p = Evaluator().profile(prof)
        assertEquals(3, p.elements.size)
        assertTrue(p.elements.all { it is ProfileElement.Seg })
        assertClosedChain(p.elements)
    }

    @Test
    fun mirroredProfileStaysClosed() {
        val c = Construction()
        val a = c.freePoint("A", 0.mm, 0.mm)
        val b = c.freePoint("B", 40.mm, 0.mm)
        val d = c.freePoint("C", 20.mm, 30.mm)
        val prof = c.profile(c.segment(a, b), c.segment(b, d), c.segment(d, a))
        val yAxis = c.lineThrough(c.freePoint("o", 0.mm, 0.mm), c.freePoint("y", 0.mm, 1.mm))
        val mirrored = Evaluator().profile(c.mirror(prof, yAxis))
        assertEquals(3, mirrored.elements.size)
        assertClosedChain(mirrored.elements)
    }
}
