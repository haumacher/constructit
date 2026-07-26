package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.BoltCircleArgs
import constructit.dsl.Construction
import constructit.dsl.RoundedRectArgs
import constructit.dsl.arc
import constructit.dsl.boltCircle
import constructit.dsl.instance
import constructit.dsl.loop
import constructit.dsl.region
import constructit.dsl.resultOf
import constructit.dsl.roundedRect
import constructit.dsl.scalar
import constructit.geom.GeomMath
import constructit.geom.ProfileElement
import constructit.svg.Drawable
import constructit.svg.Svg
import constructit.units.Dimension
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OP-14 — the result layer: trimming turns construction scaffolding into a closed, oriented
 * boundary, and a `Region` is what the 2D→3D seam will consume (OP-17).
 */
class RegionTest {
    /**
     * The core move of the result layer: four *infinite* construction lines carry no area at all,
     * but trimmed between their intersections they bound one exactly.
     */
    @Test
    fun infiniteLinesTrimmedIntoARectangle() {
        val c = Construction()
        val o = c.freePoint("o", 0.mm, 0.mm)
        val x = c.freePoint("x", 1.mm, 0.mm)
        val y = c.freePoint("y", 0.mm, 1.mm)
        val far = c.freePoint("far", 60.mm, 40.mm)
        val bottom = c.lineThrough(o, x)
        val left = c.lineThrough(o, y)
        val top = c.lineThrough(far, c.freePoint("t2", 59.mm, 40.mm))
        val right = c.lineThrough(far, c.freePoint("r2", 60.mm, 39.mm))

        val bl = c.select(c.intersectLL(bottom, left), 1)
        val br = c.select(c.intersectLL(bottom, right), 1)
        val tr = c.select(c.intersectLL(top, right), 1)
        val tl = c.select(c.intersectLL(top, left), 1)

        val outline =
            c.loop(
                c.segmentBetween(bottom, bl, br),
                c.segmentBetween(right, br, tr),
                c.segmentBetween(top, tr, tl),
                c.segmentBetween(left, tl, bl),
            )
        val area = c.loopArea(outline)

        val ev = Evaluator()
        assertEquals(4, ev.loop(outline).elements.size)
        assertEquals(Dimension.AREA, ev.scalar(area).dim)
        assertClose(ev.scalar(area).base, 60.0 * 40.0)
        assertTrue(GeomMath.signedArea(ev.loop(outline)) > 0.0, "normalised to counter-clockwise")
    }

    /** A loop named in clockwise order comes back counter-clockwise; the pieces are not reordered. */
    @Test
    fun orientationIsNormalisedWithoutReordering() {
        val c = Construction()
        val a = c.freePoint("A", 0.mm, 0.mm)
        val b = c.freePoint("B", 30.mm, 0.mm)
        val d = c.freePoint("C", 0.mm, 40.mm)
        val cw = c.loop(c.segment(a, d), c.segment(d, b), c.segment(b, a))
        val ev = Evaluator()
        val l = ev.loop(cw)
        assertEquals(3, l.elements.size)
        assertTrue(GeomMath.signedArea(l) > 0.0, "clockwise input must be normalised to CCW")
        assertClose(ev.scalar(c.loopArea(cw)).base, 0.5 * 30.0 * 40.0)
    }

    /**
     * The rounded-rect macro emits its four edges all running the *same* way round, so a boundary
     * walk needs half of them reversed — which [Construction.loop] does, because a piece's stored
     * direction is an accident of how its inputs were picked, not a statement about the boundary.
     */
    @Test
    fun roundedRectTrimsIntoAClosedLoop() {
        val c = Construction()
        val w = c.parameter("w", 80.mm)
        val h = c.parameter("h", 50.mm)
        val r = c.parameter("r", 8.mm)
        val rr = c.instance(roundedRect, "plate", RoundedRectArgs(c.freePoint("centre", 0.mm, 0.mm), w, h, r))

        // The boundary walk, starting down the right edge: edge, corner, edge, corner, ...
        val outline =
            c.loop(
                rr.segments[1],
                rr.arcs[3],
                rr.segments[2],
                rr.arcs[2],
                rr.segments[3],
                rr.arcs[1],
                rr.segments[0],
                rr.arcs[0],
            )
        val area = c.loopArea(outline)

        val ev = Evaluator()
        val l = ev.loop(outline)
        assertEquals(8, l.elements.size)
        assertEquals(4, l.elements.count { it is ProfileElement.ArcE })
        // Each consecutive pair meets, and the last meets the first.
        for (k in l.elements.indices) {
            val gap = (GeomMath.endOf(l.elements[k]) - GeomMath.startOf(l.elements[(k + 1) % l.elements.size])).length()
            assertTrue(gap < GeomMath.JOIN_TOL, "chain break after piece $k (gap $gap mm)")
        }
        // A rounded rectangle loses (4 - pi)r^2 relative to the sharp-cornered one.
        assertClose(ev.scalar(area).base, 80.0 * 50.0 - (4.0 - PI) * 64.0, tol = 1e-9)
    }

    /**
     * The OP-17 slice-1 sketch, minus the 3D: a flanged plate as a `Region`, exercising an outer
     * boundary of mixed segments and arcs plus circular holes. The area is a known answer, so this
     * pins the loop maths exactly.
     */
    @Test
    fun flangedPlateRegionArea() {
        val c = Construction()
        val centre = c.freePoint("centre", 0.mm, 0.mm)
        val w = c.parameter("width", 80.mm)
        val h = c.parameter("height", 50.mm)
        val r = c.parameter("cornerR", 8.mm)
        val rr = c.instance(roundedRect, "plate", RoundedRectArgs(centre, w, h, r))
        val bc =
            c.instance(
                boltCircle,
                "bolts",
                BoltCircleArgs(centre, c.parameter("pitchDia", 60.mm), 4, c.parameter("phase", 45.0.deg), c.parameter("holeDia", 6.mm)),
            )

        val outer =
            c.loop(
                rr.segments[1],
                rr.arcs[3],
                rr.segments[2],
                rr.arcs[2],
                rr.segments[3],
                rr.arcs[1],
                rr.segments[0],
                rr.arcs[0],
            )
        val plate = c.region(outer, *bc.holes.map { c.loop(it) }.toTypedArray())
        val area = c.regionArea(plate)

        val ev = Evaluator()
        val reg = ev.region(plate)
        assertEquals(4, reg.holes.size)
        assertTrue(GeomMath.signedArea(reg.outer) > 0.0, "outer boundary is CCW by convention")
        assertTrue(reg.holes.all { GeomMath.signedArea(it) < 0.0 }, "holes are CW by convention")
        assertEquals(Dimension.AREA, ev.scalar(area).dim)
        assertClose(ev.scalar(area).base, 80.0 * 50.0 - (4.0 - PI) * 64.0 - 4.0 * PI * 9.0, tol = 1e-9)

        Golden.check(
            "region_flanged_plate",
            Svg.render(Evaluator(), listOf(Drawable(plate))),
        )
    }

    /** The result stays parametric: editing the driving parameter moves the area with it. */
    @Test
    fun regionAreaFollowsItsParameters() {
        val c = Construction()
        val w = c.parameter("w", 40.mm)
        val h = c.parameter("h", 20.mm)
        val rr = c.instance(roundedRect, "plate", RoundedRectArgs(c.freePoint("centre", 0.mm, 0.mm), w, h, c.parameter("r", 4.mm)))
        val outline =
            c.loop(rr.segments[1], rr.arcs[3], rr.segments[2], rr.arcs[2], rr.segments[3], rr.arcs[1], rr.segments[0], rr.arcs[0])
        val area = c.loopArea(outline)

        assertClose(Evaluator().scalar(area).base, 40.0 * 20.0 - (4.0 - PI) * 16.0, tol = 1e-9)
        c.set(w, 100.mm)
        assertClose(Evaluator().scalar(area).base, 100.0 * 20.0 - (4.0 - PI) * 16.0, tol = 1e-9)
    }

    /** A chain that does not meet up is invalid (OP-3) and says why — and heals when it closes. */
    @Test
    fun openChainIsInvalidAndHeals() {
        val c = Construction()
        val a = c.freePoint("A", 0.mm, 0.mm)
        val b = c.freePoint("B", 30.mm, 0.mm)
        val d = c.freePoint("C", 30.mm, 40.mm)
        val gap = c.freePoint("D", 5.mm, 5.mm)
        val broken = c.loop(c.segment(a, b), c.segment(b, d), c.segment(d, gap))

        val bad = Evaluator().resultOf(broken)
        assertTrue(bad is EvalResult.Invalid, "an open chain must be invalid")
        assertTrue(bad.reason.contains("does not close"), "reason was: ${bad.reason}")

        c.set(gap, 0.mm, 0.mm)
        assertTrue(Evaluator().isValidLoop(broken), "closing the gap must heal the loop")
    }

    /** Trimming an arc is a stored discrete choice of branch (OP-1), not a tracked one. */
    @Test
    fun arcBetweenTakesTheStoredBranch() {
        val c = Construction()
        val centre = c.freePoint("O", 0.mm, 0.mm)
        val circle = c.circleCR(centre, c.parameter("r", 10.mm))
        val east = c.freePoint("E", 10.mm, 0.mm)
        val north = c.freePoint("N", 0.mm, 10.mm)
        val minor = c.arcBetween(circle, east, north, ccw = true)
        val major = c.arcBetween(circle, east, north, ccw = false)

        val ev = Evaluator()
        assertClose(GeomMath.sweep(ev.arc(minor)), PI / 2)
        assertClose(GeomMath.sweep(ev.arc(major)), -3 * PI / 2)
        // The two branches together are the whole circle.
        assertClose(GeomMath.sweep(ev.arc(minor)) - GeomMath.sweep(ev.arc(major)), 2 * PI)
    }

    /** A whole circle is one closed piece, and cannot be chained with others. */
    @Test
    fun circleIsItsOwnLoop() {
        val c = Construction()
        val hole = c.circleCR(c.freePoint("O", 0.mm, 0.mm), c.parameter("r", 5.mm))
        val ev = Evaluator()
        assertClose(ev.scalar(c.loopArea(c.loop(hole))).base, PI * 25.0, tol = 1e-9)

        val bogus = c.loop(hole, c.segment(c.freePoint("A", 0.mm, 0.mm), c.freePoint("B", 1.mm, 0.mm)))
        val r = Evaluator().resultOf(bogus)
        assertTrue(r is EvalResult.Invalid && r.reason.contains("already closes"), "reason was: $r")
    }

    /**
     * Holes that would eat the whole body are rejected rather than reported as negative area — and the
     * rejection is on the **region**, not merely on its area measurement, so a caller that never asks for
     * the area (an extrude) meets the fault rather than a triangulation symptom. It heals (OP-3).
     */
    @Test
    fun oversizedHoleIsInvalid() {
        val c = Construction()
        val o = c.freePoint("O", 0.mm, 0.mm)
        val hole = c.parameter("hole", 9.mm)
        val small = c.loop(c.circleCR(o, c.parameter("outer", 5.mm)))
        val big = c.loop(c.circleCR(o, hole))
        val region = c.region(small, big)
        val r = Evaluator().resultOf(region)
        assertTrue(r is EvalResult.Invalid, "a hole larger than the body is not an area")
        assertTrue(r.reason.contains("remove more area"), "reason was: ${r.reason}")
        assertTrue(Evaluator().resultOf(c.regionArea(region)) is EvalResult.Invalid, "and its area goes with it")
        assertTrue(Evaluator().resultOf(c.extrude(c.sketchOn(c.planeXY(), region), c.parameter("d", 2.mm))) is EvalResult.Invalid)

        c.set(hole, 3.mm)
        assertTrue(Evaluator().resultOf(region) is EvalResult.Ok, "shrinking the hole heals it")
    }

    /**
     * The honest limit of that check, asserted so it cannot be mistaken for more: it is a **degeneracy**
     * check, not a **containment** check. A hole that pokes out through the outer boundary while staying
     * *smaller* than it removes less area than the boundary encloses, so it is accepted — and the area that
     * comes out is arithmetically right and geometrically meaningless.
     *
     * That is deliberate (OP-14: containment testing belongs with a point-in-region predicate) and it is why
     * a construction able to produce such a shape must state its own domain — `dsl.spurGear` does, for
     * exactly this case (a bore just outside the root circle).
     */
    @Test
    fun aHoleReachingOutsideTheBoundaryIsAcceptedBecauseContainmentIsNotVerified() {
        val c = Construction()
        val body = c.loop(c.circleCR(c.freePoint("O", 0.mm, 0.mm), c.parameter("outer", 10.mm)))
        val offCentre = c.loop(c.circleCR(c.freePoint("H", 8.mm, 0.mm), c.parameter("hole", 6.mm)))
        val region = c.region(body, offCentre)
        assertTrue(Evaluator().resultOf(region) is EvalResult.Ok, "less area is removed than enclosed, so it passes")
        assertClose(Evaluator().scalar(c.regionArea(region)).base, PI * (100.0 - 36.0), tol = 1e-9)
    }
}

private fun Evaluator.isValidLoop(ref: constructit.dsl.LoopRef): Boolean = resultOf(ref) is EvalResult.Ok
