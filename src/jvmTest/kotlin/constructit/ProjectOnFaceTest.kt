package constructit

import constructit.editor.HitTest
import constructit.geom.Arc
import constructit.geom.Bezier
import constructit.geom.BoolOp
import constructit.geom.Circle
import constructit.geom.Conics
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Intersect3
import constructit.geom.LoftSection
import constructit.geom.Loop
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Project3
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Projection onto a face** (OP-26, step 8) — the geometry half: the affine map, where it is exact, where it
 * is fitted and what it says about a run that hangs over the edge.
 *
 * The claim under test is one sentence: *a curve drawn in a space, thrown along that space's normal onto a
 * face of a solid, is the affine image of the drawing in that face's plane*. Everything else here follows
 * from it — a segment stays a segment and a cubic stays a cubic at **zero** tolerance, a circle becomes the
 * ellipse it really is (and is then fitted, because `Curve3Element` has no conic case), and the shadow of the
 * result back in the drawing space **is** the drawing.
 *
 * The gestures, the persisted face index and the composition are in [ProjectOnFaceToolTest].
 */
class ProjectOnFaceTest {
    // ---- fixtures: the same bodies the section tests use, so the face indices can be read ----

    private fun rect(
        w: Double,
        h: Double,
        x0: Double = 0.0,
        y0: Double = 0.0,
    ): Region {
        val pts = listOf(Vec2(x0, y0), Vec2(x0 + w, y0), Vec2(x0 + w, y0 + h), Vec2(x0, y0 + h))
        return Region(Loop(pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }), emptyList())
    }

    /** A 100 × 100 plate, [h] mm thick, standing on the plan. Faces: sides 0..3, bottom 4, top 5. */
    private fun plate(h: Double = 20.0): Feature3.Extrusion =
        Feature3.Extrusion(Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(rect(100.0, 100.0))), h)

    /** A plate with a 30 mm round hole through the middle of it — the cap outline with a ring in it. */
    private fun boredPlate(): Feature3.Extrusion =
        Feature3.Extrusion(
            Sketch3(
                Plane3(Vec3.ZERO, Vec3.X, Vec3.Y),
                listOf(
                    Region(
                        rect(100.0, 100.0).outer,
                        listOf(Loop(listOf(ProfileElement.CircleE(Circle(Vec2(50.0, 50.0), 15.0), ccw = false)))),
                    ),
                ),
            ),
            20.0,
        )

    /** The acceptance pyramid: 100 × 100 at z = 0, apex 90 mm over its centre. */
    private fun pyramid(): Feature3.Loft =
        Feature3.Loft(
            listOf(
                LoftSection.Area(Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(rect(100.0, 100.0)))),
                LoftSection.Apex(Vec3(50.0, 50.0, 90.0)),
            ),
            listOf(0, 0),
            emptyList(),
        )

    private val plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)

    private fun faceOf(
        f: Feature3,
        i: Int,
    ) = assertNotNull(Section3.faces(f).first)[i]

    private fun seg(
        a: Vec2,
        b: Vec2,
    ): ProfileElement = ProfileElement.Seg(Segment(a, b))

    private fun assertVec3(
        actual: Vec3,
        expected: Vec3,
        tol: Double = 1e-9,
        msg: String = "",
    ) {
        assertClose(actual.x, expected.x, tol, "$msg (x)")
        assertClose(actual.y, expected.y, tol, "$msg (y)")
        assertClose(actual.z, expected.z, tol, "$msg (z)")
    }

    /** Dense samples of a curve in space, for the defining property. */
    private fun samples(elements: List<Curve3Element>): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (el in elements) {
            for (i in 0..32) {
                val t = i / 32.0
                out.add(
                    when (el) {
                        is Curve3Element.Seg3 -> el.start + (el.end - el.start) * t
                        is Curve3Element.Bezier3 -> Curves3.bezierPointAt(el, t)
                        is Curve3Element.Helix3 -> el.at(t)
                    },
                )
            }
        }
        return out
    }

    // ---- 1. the defining property ----

    /**
     * **Every point of the result lies in the face's plane, and its shadow back in the drawing space is the
     * drawing** — both at zero tolerance, because both statements are about an affine map and not about a fit.
     *
     * Asserted on an *inclined* face (a pyramid's flank), which is where the two statements are not the same
     * statement: the projection genuinely stretches the drawing, so a result that merely sat above the
     * drawing would fail the first and one that lay flat on the face would fail the second.
     */
    @Test
    fun everyPointLiesInTheFaceAndCastsTheDrawingBackIntoTheSpaceItWasDrawnIn() {
        val face = faceOf(pyramid(), 0)
        val plane = assertNotNull(face.plane, "a pyramid's flank is flat")
        val drawn = listOf(seg(Vec2(30.0, 10.0), Vec2(70.0, 20.0)), seg(Vec2(70.0, 20.0), Vec2(50.0, 30.0)))
        val (made, why) = Project3.projectedOnto(drawn, plan, face)
        assertNull(why)
        val path = assertNotNull(made).path
        for (p in samples(path.elements)) {
            assertClose(plane.distanceTo(p), 0.0, 1e-12, "the run lies in the face's plane")
            // …and it is the drawing seen from above: the shadow's (x, y) is the drawn (x, y)
            val shadow = plan.toLocal(p)
            val nearest = drawn.minOf { HitTest.distanceToPiece(shadow, it) }
            assertClose(nearest, 0.0, 1e-12, "its shadow in the plan is the curve that was drawn there")
        }
        // and the flank really is inclined, so the two assertions above are two assertions
        assertTrue(abs(plane.normal.normalized().z) > 0.4, "the flank rises: ${plane.normal.normalized()}")
    }

    // ---- 2. exactness, case by case ----

    /** A segment projects to a `Seg3` — one piece, its two ends the images of the drawn ends, exactly. */
    @Test
    fun aSegmentProjectsToAnExactSegmentInSpace() {
        val face = faceOf(plate(), 5)
        val (made, _) = Project3.projectedOnto(listOf(seg(Vec2(20.0, 20.0), Vec2(80.0, 60.0))), plan, face)
        val path = assertNotNull(made)
        assertTrue(!path.fitted, "nothing is fitted here")
        assertEquals(1, path.path.elements.size)
        val e = assertNotNull(path.path.elements[0] as? Curve3Element.Seg3, "a segment stays a segment")
        assertVec3(e.start, Vec3(20.0, 20.0, 20.0), 0.0, "exact, to the last bit")
        assertVec3(e.end, Vec3(80.0, 60.0, 20.0), 0.0, "and at the far end")
    }

    /**
     * A cubic projects to a `Bezier3` — **control point for control point**, at zero tolerance, which is
     * affine invariance and is the whole reason this step needs no tolerance for the cases the vocabulary has.
     *
     * Asserted on the pyramid's flank, where the map is a genuine stretch: the four control points are the
     * images of the four drawn ones under the same map that carries a single point, so the assertion is made
     * against `Project3.mapOnto` applied by hand rather than against a copy of the implementation.
     */
    @Test
    fun aCubicProjectsToAnExactCubicControlPointForControlPoint() {
        val face = faceOf(pyramid(), 0)
        val plane = assertNotNull(face.plane)
        val b = Bezier(Vec2(20.0, 5.0), Vec2(40.0, 25.0), Vec2(60.0, 5.0), Vec2(80.0, 20.0))
        val (made, _) = Project3.projectedOnto(listOf(ProfileElement.BezierE(b)), plan, face)
        val path = assertNotNull(made)
        assertTrue(!path.fitted, "a cubic is not fitted, it is carried")
        assertEquals(1, path.path.elements.size)
        val e = assertNotNull(path.path.elements[0] as? Curve3Element.Bezier3, "a cubic stays a cubic")
        val map = assertNotNull(Project3.mapOnto(plan, plane))
        for ((i, pair) in listOf(b.p0 to e.p0, b.p1 to e.p1, b.p2 to e.p2, b.p3 to e.p3).withIndex()) {
            assertVec3(pair.second, plane.toWorld(map.apply(pair.first)), 0.0, "control point $i is the image of the drawn one")
        }
        // and the map is a real stretch: the middle control point moved in the plane's own coordinates
        assertTrue((map.apply(b.p1) - b.p1).length() > 1.0, "the flank stretches the drawing")
    }

    /**
     * **A circle thrown at an inclined face lands as the ellipse it really is** — and is then fitted, because
     * `Curve3Element` has no conic case (steps 5 and 6's own cut, kept).
     *
     * Two assertions, and the second is what makes the first mean something: every sampled point of the run
     * stands within the stated tolerance of the **exact** ellipse the projection defines (measured against
     * that ellipse's own equation, not against the way the run was built), *and* the run is a chain of cubics
     * rather than a single one, so it really is a fit and not an accident of a degenerate case.
     */
    @Test
    fun aCircleLandsAsTheExactEllipseAndIsFittedToTheStatedTolerance() {
        val face = faceOf(pyramid(), 0)
        val plane = assertNotNull(face.plane)
        val circle = Circle(Vec2(50.0, 15.0), 12.0)
        val (made, _) = Project3.projectedOnto(listOf(ProfileElement.CircleE(circle, ccw = true)), plan, face)
        val path = assertNotNull(made)
        assertTrue(path.fitted, "a conic has no case in space, so it is fitted")
        assertTrue(path.path.closed, "a circle comes back to itself, and so does its image")
        assertTrue(path.path.elements.size >= 4, "a genuine chain of cubics: ${path.path.elements.size}")
        assertTrue(path.exactnessWord.contains("fitted"), path.exactnessWord)

        val map = assertNotNull(Project3.mapOnto(plan, plane))
        val exact = Conics.transform(Conics.ofCircle(circle), map)
        assertTrue(exact.major - exact.minor > 1.0, "the image really is an ellipse: ${exact.a} × ${exact.b}")
        for (p in samples(path.path.elements)) {
            assertClose(plane.distanceTo(p), 0.0, 1e-12, "in the face's plane")
            val q = plane.toLocal(p)
            val d = (Conics.pointAt(exact, Conics.paramOf(exact, q)) - q).length()
            assertTrue(d <= Intersect3.FIT_TOL_MM, "within the stated ${Intersect3.FIT_TOL_MM} mm of the exact ellipse: $d")
        }
    }

    /**
     * The one case where a circle stays a circle is the one where the map is a **translation**: thrown at a
     * face parallel to the drawing, the image is the drawing moved, so the fitted chain is a fit of the
     * original circle and every sample stands at its exact radius.
     */
    @Test
    fun aCircleThrownAtAParallelFaceKeepsItsRadius() {
        val face = faceOf(plate(), 5)
        val (made, _) = Project3.projectedOnto(listOf(ProfileElement.CircleE(Circle(Vec2(50.0, 50.0), 20.0), ccw = true)), plan, face)
        val path = assertNotNull(made).path
        for (p in samples(path.elements)) {
            assertClose(p.z, 20.0, 0.0, "on the top face, exactly")
            assertClose(sqrt((p.x - 50.0) * (p.x - 50.0) + (p.y - 50.0) * (p.y - 50.0)), 20.0, Intersect3.FIT_TOL_MM, "at the drawn radius")
        }
    }

    // ---- 3. running off the face is reported, never clipped and never refused ----

    /**
     * **A run that hangs over the edge is still a run** — it lands in the face's *plane*, whole, and the
     * drawing says it went off ([ProjectedCurve.whollyOn]).
     *
     * The three-way decision recorded under OP-26 step 8: clipping is *trimming* (to-be-discussed item 4) and
     * would make a solution set whose cardinality is a value; refusing would make validity depend on a
     * tessellated containment test. So the carrier answer, exactly as a `LINE` slot works on a segment's
     * carrier — and the piece count is unchanged, which is what "not clipped" means concretely.
     */
    @Test
    fun aRunThatLeavesTheFaceLandsInItsPlaneAndSaysSo() {
        val face = faceOf(plate(), 5)
        assertTrue(Project3.whollyOnFace(listOf(seg(Vec2(20.0, 20.0), Vec2(80.0, 80.0))), plan, face), "this one is on the face")

        val off = listOf(seg(Vec2(20.0, 20.0), Vec2(180.0, 80.0)))
        val over = assertNotNull(Project3.projectedOnto(off, plan, face).first)
        assertTrue(!Project3.whollyOnFace(off, plan, face), "and this one runs off it")
        assertEquals(1, over.path.elements.size, "…and is *not* clipped: one drawn piece is one piece of run")
        val e = assertNotNull(over.path.elements[0] as? Curve3Element.Seg3)
        assertVec3(e.end, Vec3(180.0, 80.0, 20.0), 0.0, "the far end is where the drawing said, in the face's plane")
    }

    /** A hole in the face is off the face, by the same nonzero rule the outside is. */
    @Test
    fun aRunOverAHoleIsOffTheFace() {
        val face = faceOf(boredPlate(), Section3.faces(boredPlate()).first!!.indexOfFirst { it.name == FaceName.Cap(constructit.geom.SolidFace.TOP) })
        assertTrue(Project3.whollyOnFace(listOf(seg(Vec2(10.0, 10.0), Vec2(30.0, 20.0))), plan, face), "clear of the bore")
        assertTrue(!Project3.whollyOnFace(listOf(seg(Vec2(50.0, 10.0), Vec2(50.0, 90.0))), plan, face), "straight across the bore, so not wholly on the face")
    }

    // ---- 4. the refusals, all of them values that heal ----

    /** A face standing **edge-on** to the drawing is invalidity with a reason — the degenerate direction. */
    @Test
    fun aFaceEdgeOnToTheDrawingIsRefusedByName() {
        val side = faceOf(plate(), 0)
        val (made, why) = Project3.projectedOnto(listOf(seg(Vec2(20.0, 20.0), Vec2(80.0, 60.0))), plan, side)
        assertNull(made)
        assertTrue(assertNotNull(why).contains("edge-on"), why!!)

        // …and it heals as soon as the space the curve is drawn in stops looking along the face
        val tilted = Plane3(Vec3.ZERO, Vec3.X, Vec3(0.0, sqrt(0.5), sqrt(0.5)))
        assertNotNull(Project3.projectedOnto(listOf(seg(Vec2(20.0, 20.0), Vec2(80.0, 60.0))), tilted, side).first, "tilt the space and it comes back")
    }

    /** A face that is **not a plane** refuses with the patch's own sentence — there is no curved operand. */
    @Test
    fun aCurvedFaceRefusesWithTheSentenceTheFaceItselfWrites() {
        val bore = faceOf(boredPlate(), 4)
        assertNull(bore.plane, "the bore's wall is a cylinder")
        val (made, why) = Project3.projectedOnto(listOf(seg(Vec2(20.0, 20.0), Vec2(80.0, 60.0))), plan, bore)
        assertNull(made)
        assertEquals(bore.reason, why, "the refusal is the face's own, not a second sentence about it")
    }

    /** A **mesh body** has no face to name, and the score says so with the sentence the section machinery has. */
    @Test
    fun aMeshBodyOffersNoFaceToLandOn() {
        val (index, why) = Project3.landingFace(Feature3.MeshBoolean(BoolOp.SUBTRACT), listOf(seg(Vec2(0.0, 0.0), Vec2(10.0, 0.0))), plan)
        assertNull(index)
        assertEquals(Section3.faces(Feature3.MeshBoolean(BoolOp.SUBTRACT)).second, why, "the existing sentence, unchanged")
        assertTrue(assertNotNull(why).contains("mesh-only"), why!!)

        val (i2, why2) = Project3.landingFace(Feature3.Imported("part.jt"), listOf(seg(Vec2(0.0, 0.0), Vec2(10.0, 0.0))), plan)
        assertNull(i2)
        assertTrue(assertNotNull(why2).contains("imported"), why2!!)
    }

    // ---- 5. which face the drawing lands on ----

    /**
     * **A plan curve over a plate lands on its top face**, and it does so without the rule knowing what a top
     * is: the four upright sides are edge-on to the plan and drop out on their own, and of the two caps the
     * one nearest the eye wins — a space is always seen from its own `+normal`.
     *
     * Asserted for a plate standing *on* the plan and for one floating above it, because the two would differ
     * under a "nearest the drawing" rule and must not.
     */
    @Test
    fun aPlanCurveOverAPlateLandsOnItsTopFace() {
        val drawn = listOf(seg(Vec2(20.0, 20.0), Vec2(80.0, 60.0)))
        assertEquals(5, Project3.landingFace(plate(), drawn, plan).first, "the top of a plate standing on the plan")
        val floating =
            Feature3.Extrusion(Sketch3(Plane3(Vec3(0.0, 0.0, 40.0), Vec3.X, Vec3.Y), listOf(rect(100.0, 100.0))), 20.0)
        assertEquals(5, Project3.landingFace(floating, drawn, plan).first, "…and of one floating above it")
    }

    /**
     * **On a pyramid the containment half does the work.** A curve drawn over the south flank lands on the
     * south flank, though the *plane* of the north flank stands higher over that ground — which is exactly
     * why the score asks where the projection falls and not only how far it travels.
     */
    @Test
    fun aCurveOverAPyramidsFlankLandsOnThatFlank() {
        val f = pyramid()
        val south = listOf(seg(Vec2(40.0, 10.0), Vec2(60.0, 15.0)))
        val i = assertNotNull(Project3.landingFace(f, south, plan).first)
        val patch = faceOf(f, i)
        val n = assertNotNull(patch.plane).normal.normalized()
        assertTrue(n.y < -0.3, "the face that looks south: $n")

        val north = listOf(seg(Vec2(40.0, 90.0), Vec2(60.0, 85.0)))
        val j = assertNotNull(Project3.landingFace(f, north, plan).first)
        assertTrue(j != i, "the other side of the roof is a different face")
        assertTrue(assertNotNull(faceOf(f, j).plane).normal.normalized().y > 0.3, "and it looks north")
    }

    // ---- 6. the map itself ----

    /**
     * The map is **affine**, which is asserted rather than assumed: the image of a midpoint is the midpoint of
     * the images, on the inclined face where a projective map would differ.
     */
    @Test
    fun theMapIsAffineSoAMidpointStaysAMidpoint() {
        val map = assertNotNull(Project3.mapOnto(plan, assertNotNull(faceOf(pyramid(), 0).plane)))
        val a = Vec2(10.0, 5.0)
        val b = Vec2(90.0, 40.0)
        val mid = map.apply((a + b) * 0.5)
        val avg = (map.apply(a) + map.apply(b)) * 0.5
        assertClose((mid - avg).length(), 0.0, 1e-12, "affine: the midpoint of the image is the image of the midpoint")
    }

    /** The whole answer is a pure function of its inputs — the same drawing gives the same run, bit for bit. */
    @Test
    fun theSameDrawingGivesTheSameRunBitForBit() {
        val drawn = listOf(seg(Vec2(20.0, 20.0), Vec2(80.0, 60.0)), ProfileElement.ArcE(Arc(Vec2(80.0, 75.0), 15.0, -PI / 2.0, PI / 2.0, ccw = true)))
        val face = faceOf(pyramid(), 0)
        val a = assertNotNull(Project3.projectedOnto(drawn, plan, face).first)
        val b = assertNotNull(Project3.projectedOnto(drawn, plan, face).first)
        assertEquals(a, b, "deterministic")
    }

    // ---- 7. the outline the report is measured against ----

    /** A face's outline is read as the rings it is, so a cap with a bore gives two of them. */
    @Test
    fun aBoredCapsOutlineReadsAsTwoRings() {
        val faces = assertNotNull(Section3.faces(boredPlate()).first)
        val top = faces.first { it.name == FaceName.Cap(constructit.geom.SolidFace.TOP) }
        assertEquals(2, Project3.loopsOf(top.outline).size, "the boundary and the bore")
        val rings = Project3.ringsOf(top.outline)
        assertEquals(2, rings.size)
        assertTrue(constructit.geom.RegionBool.contains(rings, Vec2(10.0, 10.0)), "material near the corner")
        assertTrue(!constructit.geom.RegionBool.contains(rings, Vec2(50.0, 50.0)), "and none in the bore")
    }

    /** A body the projection travels **away** from is still a body it lands on — the face behind the drawing. */
    @Test
    fun aPlateBehindTheDrawingIsStillReached() {
        val below =
            Feature3.Extrusion(Sketch3(Plane3(Vec3(0.0, 0.0, -60.0), Vec3.X, Vec3.Y), listOf(rect(100.0, 100.0))), 20.0)
        val i = assertNotNull(Project3.landingFace(below, listOf(seg(Vec2(20.0, 20.0), Vec2(80.0, 60.0))), plan).first)
        assertEquals(5, i, "still the face nearest the eye — the top of the plate, seen from above")
        val made = assertNotNull(Project3.projectedOnto(listOf(seg(Vec2(20.0, 20.0), Vec2(80.0, 60.0))), plan, faceOf(below, i)).first)
        assertVec3(made.path.elements[0].start, Vec3(20.0, 20.0, -40.0), 0.0, "and the run is down there with it")
    }

    /** The solid really is a solid: the fixtures used above build watertight meshes (OP-2). */
    @Test
    fun theFixturesAreWatertight() {
        assertManifold(assertNotNull(Geom3.extrude(plate().sketch, plate().depth).first).mesh, "plate")
        assertManifold(assertNotNull(Geom3.loft(pyramid().sections, pyramid().seams).first).mesh, "pyramid")
    }
}
