package constructit

import constructit.geom.Circle
import constructit.geom.Conics
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.DrawnPiece
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Intersect3
import constructit.geom.IntersectionCurve
import constructit.geom.LoftSection
import constructit.geom.Loop
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.PlaneSection
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.l10n.Msg
import constructit.l10n.contains
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Intersection curves** (OP-26, step 6) — the geometry half: plane ∩ solid, promoted from the existing
 * section machinery into a first-class curve in space.
 *
 * The test that matters is the **defining property**, and it is asserted the way step 5's was: sample the
 * resulting curve densely and check every point twice over, against arithmetic that knows nothing about how
 * the curve was built — it must lie **on the plane** (to zero, because the lift is `plane.toWorld` of a 2D
 * point) and **on the solid's boundary** (to the stated tolerance, measured from the mesh the solid actually
 * has). Everything else here is the branch doctrine: an ordered set, the order stated and stable, and an
 * index that is a choice rather than a measurement.
 *
 * The gestures are in [IntersectionCurveToolTest].
 */
class IntersectionCurveTest {
    // ---- fixtures ----

    private fun rect(
        w: Double,
        h: Double,
        x0: Double = 0.0,
        y0: Double = 0.0,
    ): Region {
        val pts = listOf(Vec2(x0, y0), Vec2(x0 + w, y0), Vec2(x0 + w, y0 + h), Vec2(x0, y0 + h))
        return Region(Loop(pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }), emptyList())
    }

    private fun disc(
        r: Double,
        at: Vec2 = Vec2(0.0, 0.0),
    ): Region = Region(Loop(listOf(ProfileElement.CircleE(Circle(at, r), ccw = true))), emptyList())

    /** A plate: 100 × 60 at z = 0, 20 mm thick. */
    private fun plate(): Solid3 =
        assertNotNull(Geom3.extrude(Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(rect(100.0, 60.0))), 20.0).first)

    /** A round bar standing on z = 0: radius 30, 80 tall. */
    private fun bar(
        r: Double = 30.0,
        h: Double = 80.0,
        at: Vec2 = Vec2(0.0, 0.0),
    ): Solid3 = assertNotNull(Geom3.extrude(Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(disc(r, at))), h).first)

    /** Two separate posts, 40 mm apart — one extrusion, two regions, so one plane cuts it in two places. */
    private fun twoPosts(): Solid3 =
        assertNotNull(
            Geom3.extrude(
                Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(rect(20.0, 20.0, 0.0, 0.0), rect(20.0, 20.0, 0.0, 60.0))),
                30.0,
            ).first,
        )

    private fun pyramid(apex: Vec3 = Vec3(50.0, 50.0, 90.0)): Solid3 =
        assertNotNull(
            Geom3.loft(
                listOf(
                    LoftSection.Area(Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(rect(100.0, 100.0)))),
                    LoftSection.Apex(apex),
                ),
                listOf(0, 0),
            ).first,
        )

    /** A horizontal cutting plane at world height [z], with the world x/y as its own u/v. */
    private fun atHeight(z: Double): Plane3 = Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y)

    private fun curvesOf(
        solid: Solid3,
        plane: Plane3,
    ): List<IntersectionCurve> = Intersect3.curvesOf(Section3.sectionOf(solid, plane), plane).curves

    // ---- the defining property ----

    /** How far [p] stands from [solid]'s own triangle mesh — arithmetic that knows nothing of the section. */
    private fun distanceToBoundary(
        solid: Solid3,
        p: Vec3,
    ): Double {
        var best = Double.MAX_VALUE
        val m = solid.mesh
        for (t in m.triangles) best = minOf(best, distanceToTriangle(p, m.vertices[t.a], m.vertices[t.b], m.vertices[t.c]))
        return best
    }

    private fun distanceToTriangle(
        p: Vec3,
        a: Vec3,
        b: Vec3,
        c: Vec3,
    ): Double {
        // the classical clamped barycentric projection, written out — no library, so the check is independent
        val ab = b - a
        val ac = c - a
        val ap = p - a
        val d1 = ab.dot(ap)
        val d2 = ac.dot(ap)
        if (d1 <= 0.0 && d2 <= 0.0) return (p - a).length()
        val bp = p - b
        val d3 = ab.dot(bp)
        val d4 = ac.dot(bp)
        if (d3 >= 0.0 && d4 <= d3) return (p - b).length()
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) return (p - (a + ab * (d1 / (d1 - d3)))).length()
        val cp = p - c
        val d5 = ab.dot(cp)
        val d6 = ac.dot(cp)
        if (d6 >= 0.0 && d5 <= d6) return (p - c).length()
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) return (p - (a + ac * (d2 / (d2 - d6)))).length()
        val va = d3 * d6 - d5 * d4
        if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) {
            return (p - (b + (c - b) * ((d4 - d3) / ((d4 - d3) + (d5 - d6))))).length()
        }
        val denom = 1.0 / (va + vb + vc)
        return (p - (a + ab * (vb * denom) + ac * (vc * denom))).length()
    }

    private fun samplesOf(
        path: Path3,
        per: Int = 64,
    ): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (el in path.elements) {
            for (i in 0..per) {
                val t = i.toDouble() / per
                out.add(
                    when (el) {
                        is Curve3Element.Seg3 -> el.start + (el.end - el.start) * t
                        is Curve3Element.Bezier3 -> Curves3.bezierPointAt(el, t)
                        is Curve3Element.Arc3 -> el.at(t)
                        is Curve3Element.Helix3 -> el.at(t)
                    },
                )
            }
        }
        return out
    }

    /**
     * **The defining property, on a prism.** Every point of the curve lies *on* the cutting plane to zero —
     * the lift is `plane.toWorld` of a 2D point, so there is nothing to be wrong by — and on the plate's own
     * boundary to well inside a micron.
     */
    @Test
    fun everyPointOfTheCurveIsOnThePlaneAndOnTheSolid() {
        val solid = plate()
        val plane = atHeight(8.0)
        val curves = curvesOf(solid, plane)
        assertEquals(1, curves.size, "a plate cut across is one loop")
        val path = curves[0].path
        assertTrue(path.closed, "and it closes")
        for (p in samplesOf(path)) {
            assertClose(plane.distanceTo(p), 0.0, 1e-12, "on the plane")
            assertClose(distanceToBoundary(solid, p), 0.0, 1e-9, "on the boundary at $p")
        }
    }

    /**
     * The same property on the curve that used to be **fitted**: a plane across a round bar cuts a circle, and
     * a circle now has a case in the space vocabulary ([Curve3Element.Arc3], added with the lift — OP-26's
     * step 1 completed). So the section of a round bar is **exact**, and this asserts the reversal at the
     * number it turns on: every sample stands on the bar's analytic radius to the last bits of a double,
     * where the cubic fit stood within a tenth of a micron of it.
     *
     * Measured against the analytic radius, not against the mesh, so the tessellation cannot flatter it.
     */
    @Test
    fun aCircularSectionIsExactRatherThanFitted() {
        val solid = bar()
        val plane = atHeight(40.0)
        val curves = curvesOf(solid, plane)
        assertEquals(1, curves.size)
        assertTrue(!curves[0].fitted, "a circle has a case in the space vocabulary now, so nothing is fitted")
        assertTrue(!curves[0].sampled, "and it is not chords of anything")
        assertEquals("exact", curves[0].exactnessWord.render(), "and it says so")
        assertTrue(
            curves[0].path.elements.all { it is Curve3Element.Arc3 },
            "the piece is the arc it is: ${curves[0].path.elements}",
        )
        var worst = 0.0
        for (p in samplesOf(curves[0].path, per = 32)) {
            assertClose(plane.distanceTo(p), 0.0, 1e-12, "on the plane")
            worst = maxOf(worst, abs(hypot(p.x, p.y) - 30.0))
        }
        assertTrue(worst <= 1e-12, "the radius is the bar's own, to the last bit: $worst")
    }

    /** The stated number is what it says: 1e-4 mm, one tenth of a micron, and 200× finer than the mesh's. */
    @Test
    fun theStatedFitToleranceIsTheOneAsserted() {
        assertClose(Intersect3.FIT_TOL_MM, 1e-4, 0.0, "the stated fit tolerance")
    }

    // ---- exactness, case by case ----

    /** A prism's cut is straight lines, and straight lines have a case: **exact**, at zero tolerance. */
    @Test
    fun aPrismaticCutIsExactSegments() {
        val curves = curvesOf(plate(), atHeight(5.0))
        val path = curves[0].path
        assertTrue(!curves[0].fitted && !curves[0].sampled, "nothing fitted, nothing sampled")
        assertEquals(4, path.elements.size, "four sides")
        assertTrue(path.elements.all { it is Curve3Element.Seg3 }, "every piece is a segment: ${path.elements}")
        val corners = path.elements.map { it.start }.sortedWith(compareBy({ it.y }, { it.x }))
        assertEquals(Vec3(0.0, 0.0, 5.0), corners[0], "exact, to the last bit")
        assertEquals(Vec3(100.0, 0.0, 5.0), corners[1])
        assertEquals(Vec3(0.0, 60.0, 5.0), corners[2])
        assertEquals(Vec3(100.0, 60.0, 5.0), corners[3])
    }

    /**
     * A pyramid cut at half height is the exact 50 × 50 square about its centre — the very number
     * `PlaneSectionTest` asserts one dimension down, now standing in space at z = 45.
     */
    @Test
    fun aPyramidCutAtHalfHeightIsTheExactSquare() {
        val curves = curvesOf(pyramid(), atHeight(45.0))
        assertEquals(1, curves.size)
        assertTrue(!curves[0].fitted && !curves[0].sampled)
        val corners = curves[0].path.elements.map { it.start }.sortedWith(compareBy({ it.y }, { it.x }))
        assertEquals(4, corners.size)
        for (c in corners) assertClose(c.z, 45.0, 0.0, "exactly at the plane")
        assertClose(corners[0].x, 25.0, 1e-9)
        assertClose(corners[0].y, 25.0, 1e-9)
        assertClose(corners[3].x, 75.0, 1e-9)
        assertClose(corners[3].y, 75.0, 1e-9)
    }

    /**
     * An **inclined** cut of a cylinder is a true ellipse (OP-24), and it comes back fitted to the same
     * stated tolerance — asserted against the ellipse's own equation, so the fit is measured rather than
     * assumed.
     */
    @Test
    fun anInclinedCutOfACylinderIsTheEllipseFittedToTheStatedTolerance() {
        val solid = bar(r = 30.0, h = 200.0)
        val theta = PI / 6.0
        val plane = Plane3(Vec3(0.0, 0.0, 100.0), Vec3.X, Vec3(0.0, cos(theta), sin(theta)))
        val curves = curvesOf(solid, plane)
        assertEquals(1, curves.size, "one loop")
        assertTrue(curves[0].fitted, "an ellipse in space has no case, so it is fitted")
        // the true section: semi-axes 30 and 30 / cos θ, about (0, 0, 100)
        val a = 30.0
        val b = 30.0 / cos(theta)
        var worst = 0.0
        for (p in samplesOf(curves[0].path, per = 24)) {
            assertClose(plane.distanceTo(p), 0.0, 1e-12, "on the plane")
            val q = plane.toLocal(p)
            // |q| on the ellipse: the implicit form, converted to a distance by the gradient
            val f = (q.x / a) * (q.x / a) + (q.y / b) * (q.y / b) - 1.0
            val grad = 2.0 * hypot(q.x / (a * a), q.y / (b * b))
            worst = maxOf(worst, abs(f) / grad)
        }
        assertTrue(worst <= Intersect3.FIT_TOL_MM, "within the stated fit: $worst mm")
    }

    /**
     * The **mesh route**: an imported body has no faces to name, so its section draws in chords — and the
     * curve says so rather than claiming the tessellation's error as its own.
     */
    @Test
    fun aMeshBodysCurveSaysItIsChords() {
        val src = bar(r = 30.0, h = 60.0)
        val imported = Solid3.of(Feature3.Imported("bar.jt"), src.mesh)
        val curves = curvesOf(imported, atHeight(30.0))
        assertEquals(1, curves.size, "one ring of chords: ${curves.size}")
        assertTrue(curves[0].sampled, "the mesh route draws chords, and the curve says so")
        assertTrue(!curves[0].fitted, "nothing was fitted — the chords were already there")
        assertTrue(curves[0].path.closed, "and they close into a ring")
        assertTrue(
            curves[0].exactnessWord.contains("chords"),
            "it names its class: ${curves[0].exactnessWord}",
        )
        for (p in samplesOf(curves[0].path, per = 4)) {
            assertClose(plane0.distanceTo(p), 0.0, 1e-12, "still exactly on the plane")
            assertTrue(abs(hypot(p.x, p.y) - 30.0) <= 0.05, "and within the tessellation of the true bar")
        }
    }

    private val plane0 = atHeight(30.0)

    // ---- the ordered set, and the doctrine on it ----

    /**
     * **Several curves, ordered.** A plane through two posts cuts two loops, and they come back ordered by
     * their lowest point in the plane's own coordinates — the near post first.
     */
    @Test
    fun aPlaneThroughTwoPostsGivesTwoCurvesOrderedByTheirLowestPoint() {
        val curves = curvesOf(twoPosts(), atHeight(15.0))
        assertEquals(2, curves.size, "two posts, two loops")
        val low = curves.map { c -> samplesOf(c.path, per = 4).minOf { it.y } }
        assertClose(low[0], 0.0, 1e-9, "the near post is first")
        assertClose(low[1], 60.0, 1e-9, "the far one second")
        for ((i, c) in curves.withIndex()) {
            assertTrue(c.path.closed, "loop $i closes")
            assertEquals(4, c.path.elements.size, "and is a square")
        }
    }

    /**
     * **The order is stable under drift, and it is stable for the reason claimed**: the lowest point is a
     * continuous function of the geometry, so sliding the plane — which moves nothing in the plane's own
     * (u, v) here, and moving the posts, which does — never permutes the two while their lowest points stay
     * apart.
     */
    @Test
    fun theOrderDoesNotPermuteWhileTheLowestPointsStayApart() {
        for (z in listOf(1.0, 7.5, 15.0, 22.0, 29.0)) {
            val curves = curvesOf(twoPosts(), atHeight(z))
            assertEquals(2, curves.size, "at z = $z")
            val low = curves.map { c -> samplesOf(c.path, per = 4).minOf { it.y } }
            assertTrue(low[0] < low[1], "the near post stays first at z = $z")
        }
        // …and the same when the far post moves towards the near one, right up to where they touch
        for (gap in listOf(60.0, 40.0, 25.0, 21.0)) {
            val solid =
                assertNotNull(
                    Geom3.extrude(
                        Sketch3(
                            Plane3(Vec3.ZERO, Vec3.X, Vec3.Y),
                            listOf(rect(20.0, 20.0, 0.0, 0.0), rect(20.0, 20.0, 0.0, gap)),
                        ),
                        30.0,
                    ).first,
                )
            val curves = curvesOf(solid, atHeight(15.0))
            assertEquals(2, curves.size, "still two at gap $gap")
            val low = curves.map { c -> samplesOf(c.path, per = 4).minOf { it.y } }
            assertClose(low[0], 0.0, 1e-9, "the near post is still first at gap $gap")
            assertClose(low[1], gap, 1e-9)
        }
    }

    /** The same section gives the same curves, bit for bit — nothing here depends on iteration order. */
    @Test
    fun theSameSectionGivesTheSameCurvesBitForBit() {
        val solid = twoPosts()
        val a = curvesOf(solid, atHeight(15.0))
        val b = curvesOf(solid, atHeight(15.0))
        assertEquals(a.map { it.path }, b.map { it.path }, "deterministic")
    }

    /**
     * A **closed** run is counter-clockwise in the plane's own coordinates and starts at its lowest corner —
     * the canonical form, so the same geometry gives the same curve whichever face the section listed first.
     */
    @Test
    fun aClosedRunIsCounterClockwiseAndStartsAtItsLowestCorner() {
        val curves = curvesOf(plate(), atHeight(10.0))
        val plane = atHeight(10.0)
        val path = curves[0].path
        val pts = path.elements.map { plane.toLocal(it.start) }
        assertEquals(Vec2(0.0, 0.0), pts[0], "the lowest corner comes first")
        var area = 0.0
        for (i in pts.indices) area += pts[i].cross(pts[(i + 1) % pts.size])
        assertTrue(area > 0.0, "counter-clockwise in the plane's own frame")
    }

    /** An **open** run — a plane clipping only part of a body — runs from its lower end. */
    @Test
    fun anOpenRunGoesFromItsLowerEnd() {
        // a plane standing upright through the plate, so the cut is the rectangle's own section: closed.
        // An open run needs a face that is cut without the cut closing — an *open shell*, which is what an
        // imported open body is (session 34).
        val src = plate()
        val open = Solid3.of(Feature3.Imported("lid.jt", openShell = Msg.text("the lid is one face")), openTop(src))
        val plane = Plane3(Vec3(0.0, 30.0, 0.0), Vec3.X, Vec3.Z)
        val curves = curvesOf(open, plane)
        assertTrue(curves.isNotEmpty(), "the plane cuts the lid")
        for (c in curves) {
            if (c.path.closed) continue
            val a = plane.toLocal(assertNotNull(c.path.start))
            val b = plane.toLocal(assertNotNull(c.path.end))
            assertTrue(a.y < b.y || (a.y == b.y && a.x <= b.x), "runs from the lower end: $a -> $b")
        }
    }

    /** Just the top cap of a solid, as a mesh — an open shell, for the open-run case above. */
    private fun openTop(s: Solid3): constructit.geom.Mesh3 {
        val keep =
            s.mesh.triangles.filter { t ->
                listOf(s.mesh.vertices[t.a], s.mesh.vertices[t.b], s.mesh.vertices[t.c]).all { abs(it.z - 20.0) < 1e-9 }
            }
        return constructit.geom.Mesh3(s.mesh.vertices, keep)
    }

    /**
     * **The chain is walked both ways from wherever it is seeded**, however the section listed its pieces.
     *
     * The section's order is its feature's structural order, so the piece a walk starts from can sit in the
     * middle of a run — and then the run is only whole if the backward half keeps stepping. Asserted on a
     * hand-made four-piece open chain whose *third* piece is listed first, which is exactly the case a walk
     * that lost its place after one backward step would truncate.
     */
    @Test
    fun aRunIsWalkedBothWaysFromWhereverItIsSeeded() {
        val pts = listOf(Vec2(0.0, 0.0), Vec2(10.0, 0.0), Vec2(20.0, 0.0), Vec2(30.0, 0.0), Vec2(40.0, 0.0))
        val segs = (0 until 4).map { ProfileElement.Seg(Segment(pts[it], pts[it + 1])) }
        // listed third-first, and one of them stored backwards for good measure
        val order = listOf(segs[2], GeomMath.reverse(segs[0]), segs[3], segs[1])
        val section =
            PlaneSection(emptyList(), emptyList(), null, null, order.map { DrawnPiece(it, false) }, false)
        val plane = atHeight(0.0)
        val curves = Intersect3.curvesOf(section, plane).curves
        assertEquals(1, curves.size, "one run, not several: ${curves.size}")
        val path = curves[0].path
        assertTrue(!path.closed, "and it is open")
        assertEquals(4, path.elements.size, "all four pieces are in it")
        assertVec3(assertNotNull(path.start), Vec3(0.0, 0.0, 0.0), "running from its lower end")
        assertVec3(assertNotNull(path.end), Vec3(40.0, 0.0, 0.0))
    }

    private fun assertVec3(
        actual: Vec3,
        expected: Vec3,
        msg: String = "",
    ) {
        assertClose(actual.x, expected.x, 1e-12, "$msg (x)")
        assertClose(actual.y, expected.y, 1e-12, "$msg (y)")
        assertClose(actual.z, expected.z, 1e-12, "$msg (z)")
    }

    // ---- the ordering rule's own arithmetic: exact, never sampled ----

    /** The extreme of an arc is its own geometry, taken in closed form — not the lowest of a tessellation. */
    @Test
    fun theLowestPointOfAPieceIsExact() {
        val arc = ProfileElement.ArcE(constructit.geom.Arc(Vec2(0.0, 0.0), 10.0, PI, 0.0, ccw = true))
        val bottom = Intersect3.lowestOf(arc)
        assertClose(bottom.x, 0.0, 1e-12, "the bottom of the arc, exactly (x)")
        assertClose(bottom.y, -10.0, 1e-12, "the bottom of the arc, exactly (y)")
        val part = ProfileElement.ArcE(constructit.geom.Arc(Vec2(0.0, 0.0), 10.0, 0.0, PI / 2.0, ccw = true))
        val lo = Intersect3.lowestOf(part)
        assertClose(lo.x, 10.0, 1e-12, "an arc that does not reach the bottom answers with its own end")
        assertClose(lo.y, 0.0, 1e-12)
        val e = constructit.geom.Ellipse(Vec2(0.0, 0.0), 20.0, 5.0, PI / 4.0)
        val low = Intersect3.lowestOf(ProfileElement.EllipseE(e))
        // the analytic extreme: y = a·cos t·sin r + b·sin t·cos r is stationary at tan t = b·cos r / (a·sin r)
        val t = kotlin.math.atan2(5.0 * cos(PI / 4.0), 20.0 * sin(PI / 4.0))
        val cand = listOf(Conics.pointAt(e, t), Conics.pointAt(e, t + PI)).minByOrNull { it.y }!!
        assertClose(low.y, cand.y, 1e-12, "a turned ellipse's lowest point, in closed form")
    }

    // ---- what happens when the answer has a different number of curves ----

    /** A plane that misses the body answers with an empty set — and nothing pretends otherwise. */
    @Test
    fun aPlaneThatMissesTheBodyGivesNoCurves() {
        assertEquals(0, curvesOf(plate(), atHeight(50.0)).size, "well above the plate")
    }

    /** Sliding one plane through the two posts and out again: 2, then 2, then 0, and back. */
    @Test
    fun theNumberOfCurvesIsAValueAndItChangesAsThePlaneMoves() {
        assertEquals(2, curvesOf(twoPosts(), atHeight(15.0)).size)
        assertEquals(0, curvesOf(twoPosts(), atHeight(45.0)).size)
        assertEquals(2, curvesOf(twoPosts(), atHeight(15.0)).size, "and it heals")
    }
}
