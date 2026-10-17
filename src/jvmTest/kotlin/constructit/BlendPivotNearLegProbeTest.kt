package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The orchestrator's probe of OP-31 (5u): rings the delivery never saw — a shallow sweep with a small ball, a
 * wide ring with a ball near the step's own size, and a deep sweep past 180° — each near leg clears its wall,
 * the tool is closed, and under the from-source engine each body builds.
 */
class BlendPivotNearLegProbeTest {
    private var ids = 0
    private val tol = 1e-7

    private class Ring(val ring: Double, val step: Double, val sweep: Double, val r: Double)

    /** The ring the from-source engine refused, the radius it built, and a ring of another size, ball and sweep. */
    private val rings = listOf(Ring(30.0, 4.0, 60.0, 0.75), Ring(40.0, 10.0, 45.0, 4.5), Ring(16.0, 8.0, 200.0, 3.0))

    private fun polygon(
        cx: Construction,
        pts: List<Vec2>,
    ): RegionRef {
        val ps = pts.map { cx.freePoint("N${ids++}", it.x.mm, it.y.mm) }
        return cx.region(cx.loop(*ps.indices.map { cx.segment(ps[it], ps[(it + 1) % ps.size]) }.toTypedArray()))
    }

    /** An L meridian turned about `x` — its start cap's reflex corner stands on a ring of radius `ring` at `x = step`. */
    private fun turned(
        cx: Construction,
        g: Ring,
    ): SolidRef {
        val inner = g.ring / 2.0
        val prof =
            listOf(Vec2(0.0, inner), Vec2(0.0, g.ring + 10.0), Vec2(g.step, g.ring + 10.0), Vec2(g.step, g.ring), Vec2(2.0 * g.step, g.ring), Vec2(2.0 * g.step, inner))
        val o = cx.freePoint("No${ids++}", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("Nx${ids++}", 1.mm, 0.mm))
        return cx.revolve(cx.sketchOn(cx.planeXY(), polygon(cx, prof)), o, axis, cx.const(Quantity(g.sweep * PI / 180.0, Dimension.ANGLE)))
    }

    /** The two edges meeting at [at] on the face [face] names, rounded at [r] in one gesture. */
    private fun pivot(
        cx: Construction,
        base: SolidRef,
        at: Vec3,
        face: String,
        r: Double,
    ): Solid3 {
        val body = Evaluator().solid(base)
        val es = assertNotNull(Section3.edges(body.feature).first, "the body names its edges")
        val pair =
            es.indices.filter { i ->
                val e = es[i]
                if (!e.between.a.label.render().contains(face) && !e.between.b.label.render().contains(face)) return@filter false
                val path = Blend3.edgePath(e).first ?: return@filter false
                val s = path.start ?: return@filter false
                val t = path.end ?: return@filter false
                (s - at).length() < 1e-6 || (t - at).length() < 1e-6
            }
        assertEquals(2, pair.size, "the reflex pair at $at")
        val (choices, why) = Blend3.choicesFor(body, pair, BlendSection(BlendKind.FILLET, r))
        val cs = assertNotNull(choices, "the pair at $at is scored: ${why?.render()}")
        val ref = cx.blendAll(base, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(r.mm), null, pair, cs)))
        val res = Evaluator().eval(ref.node)
        assertTrue(res is EvalResult.Ok, "the pivot at $at of r = $r builds: ${(res as? EvalResult.Invalid)?.why?.render()}")
        val b = Evaluator().solid(ref)
        assertManifold(b.mesh, "the pivot at $at of r = $r")
        assertEquals(1, assertNotNull(Section3.faces(b.feature).first).count { it.name is FaceName.BlendCorner }, "one corner face")
        return b
    }

    private fun ringPivot(g: Ring): Solid3 {
        ids = 0
        val cx = Construction()
        return pivot(cx, turned(cx, g), Vec3(g.step, g.ring, 0.0), "cap at the start", g.r)
    }

    /** Every ring of [body]'s one pivot, its near leg asserted against the step it states; the steepest `|n·t|`. */
    private fun assertClears(
        body: Solid3,
        what: String,
    ): Double {
        val turns = Blend3.cornerNearLegClearances(body.feature as Feature3.Blend)
        assertEquals(1, turns.size, "$what: one pivot")
        val walk = turns[0]
        assertTrue(walk.size >= 16, "$what: a walk of stations, not ${walk.size}")
        for (c in walk) {
            assertTrue(
                c.least >= c.stated - tol,
                "$what, ring ${c.station} (|n·t| = ${c.slant}): the near leg stands ${c.least} mm off the wall it walks, not the ${c.stated} mm it states",
            )
        }
        val steepest = walk.maxOf { it.slant }
        println("near leg | $what | ${walk.size} rings | least ${walk.minOf { it.least }} of ${walk[0].stated} | steepest |n·t| $steepest")
        return steepest
    }

    @Test
    fun unseenRingsClearTheirWalls() {
        for (g in rings) assertClears(ringPivot(g), "probe ring R = ${g.ring}, step ${g.step}, sweep ${g.sweep}, r = ${g.r}")
    }

    @Test
    fun unseenRingsBuildUnderEveryEngine() {
        for (g in rings) {
            val b = ringPivot(g)
            assertTrue(Geom3.volume(b.mesh) > 0.0, "R = ${g.ring}: a body")
        }
    }
}
