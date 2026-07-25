package constructit

import constructit.dsl.Construction
import constructit.dsl.point
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.geom.Geom3
import constructit.geom.Justification
import constructit.geom.SolidFace
import constructit.units.mm
import kotlin.test.Test

/**
 * Probes composing the 3D core with features it was not written against: an OP-21 wall footprint
 * ring extruded to a hollow storey, a two-level face-on-face chain, and an extrude depth *measured*
 * from 2D geometry. The seam consumes ordinary Region/Scalar nodes, so everything that produces one
 * must feed it unchanged.
 */
class SolidProbeTest {
    /** A closed rectangular wall ring (OP-21) extrudes to a hollow storey — the architect's first hop. */
    @Test
    fun aThickPathRingExtrudesToAHollowStorey() {
        val cx = Construction()
        val ev = { constructit.core.Evaluator() }
        val v1 = cx.freePoint("v1", 0.mm, 0.mm)
        val v2 = cx.freePoint("v2", 4000.mm, 0.mm)
        val v3 = cx.freePoint("v3", 4000.mm, 3000.mm)
        val v4 = cx.freePoint("v4", 0.mm, 3000.mm)
        val t = cx.parameter("t", 200.mm)
        val footprint = cx.thickFootprint(listOf(v1, v2, v3, v4), t, closed = true, justification = Justification.CENTER)
        val storey = cx.extrude(cx.sketchOn(cx.planeXY(), footprint), cx.parameter("h", 2600.mm))

        val s = ev().solid(storey)
        assertManifold(s.mesh)
        // ring area: outer 4200×3200 − inner 3800×2800 = 2 800 000 mm²; × 2600 mm height
        val vol = Geom3.volume(s.mesh)
        assertClose(vol / 1e9, 2_800_000.0 * 2600.0 / 1e9, tol = 1e-6, msg = "hollow storey volume in litres-ish scale")

        // the walls stay parametric through the seam: thicken them and the volume follows exactly
        cx.set(t, 300.mm)
        val vol2 = Geom3.volume(ev().solid(storey).mesh)
        assertClose(vol2 / 1e9, (4300.0 * 3300.0 - 3700.0 * 2700.0) * 2600.0 / 1e9, tol = 1e-6)
    }

    /** OP-8 accessors chain: a boss on a boss — and the whole tower rides a base edit. */
    @Test
    fun aTowerOfFaceSketchesRidesItsBaseParameter() {
        val cx = Construction()
        val ev = { constructit.core.Evaluator() }

        fun square(
            cxs: Double,
            half: Double,
        ) =
            cx.region(
                cx.loop(
                    cx.segment(cx.freePoint("a$half", (cxs - half).mm, (-half).mm), cx.freePoint("b$half", (cxs + half).mm, (-half).mm)),
                    cx.segment(cx.freePoint("b2$half", (cxs + half).mm, (-half).mm), cx.freePoint("c$half", (cxs + half).mm, half.mm)),
                    cx.segment(cx.freePoint("c2$half", (cxs + half).mm, half.mm), cx.freePoint("d$half", (cxs - half).mm, half.mm)),
                    cx.segment(cx.freePoint("d2$half", (cxs - half).mm, half.mm), cx.freePoint("a2$half", (cxs - half).mm, (-half).mm)),
                ),
            )

        val baseDepth = cx.parameter("d0", 30.mm)
        val base = cx.extrude(cx.sketchOn(cx.planeXY(), square(0.0, 50.0)), baseDepth)
        val mid = cx.extrude(cx.sketchOn(cx.facePlane(base, SolidFace.TOP), square(0.0, 30.0)), cx.parameter("d1", 20.mm))
        val top = cx.extrude(cx.sketchOn(cx.facePlane(mid, SolidFace.TOP), square(0.0, 10.0)), cx.parameter("d2", 10.mm))

        assertManifold(ev().solid(top).mesh)
        var zMax = Geom3.bounds(ev().solid(top).mesh)!!.second.z
        assertClose(zMax, 60.0, msg = "30+20+10 stacked")

        cx.set(baseDepth, 55.mm)
        zMax = Geom3.bounds(ev().solid(top).mesh)!!.second.z
        assertClose(zMax, 85.0, msg = "the whole tower rides the base depth")
    }

    /** OP-4 both ways round the seam: a 2D distance drives depth; the volume measures back out. */
    @Test
    fun aMeasured2DDistanceDrivesAnExtrudeDepth() {
        val cx = Construction()
        val ev = { constructit.core.Evaluator() }
        val p1 = cx.freePoint("p1", 0.mm, 0.mm)
        val p2 = cx.freePoint("p2", 40.mm, 0.mm)
        val depth = cx.measureDistance(p1, p2)

        val a = cx.freePoint("a", 0.mm, 0.mm)
        val b = cx.freePoint("b", 10.mm, 0.mm)
        val c = cx.freePoint("c", 10.mm, 10.mm)
        val d = cx.freePoint("d", 0.mm, 10.mm)
        val sq =
            cx.region(
                cx.loop(cx.segment(a, b), cx.segment(b, c), cx.segment(c, d), cx.segment(d, a)),
            )
        val solid = cx.extrude(cx.sketchOn(cx.planeXY(), sq), depth)
        val vol = cx.measureVolume(solid)

        assertClose(ev().scalar(vol).value, 100.0 * 40.0, msg = "10×10 × measured 40")

        cx.set(p2, 65.mm, 0.mm)
        assertClose(ev().scalar(vol).value, 100.0 * 65.0, msg = "moving the measured point re-extrudes")
        assertClose(ev().point(p2).x, 65.0)
    }
}
