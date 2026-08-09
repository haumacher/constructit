package constructit

import constructit.geom.Arc
import constructit.geom.Circle
import constructit.geom.Conics
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Loop
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Solid3
import constructit.geom.SolidFace
import constructit.geom.Turn3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Analytic faces for surfaces of revolution** — item 4 of the sphere queue (OP-17), and the record of the
 * dispatch it is built on.
 *
 * What is asserted here is the mechanism, not one part: a revolution's faces are read off its **profile's
 * own pieces** and named by their index (so an address survives every edit), and the section of each band is
 * exact where its family and the plane's relation to the axis have an exact answer and the honest sampled
 * one where they do not. The table [Revolve3.cutBand] states is pinned test by test, including **which path
 * was taken** and not merely that the shape came out right — because *exact paths never degrade silently to
 * mesh paths* is a statement about the dispatch and nothing else can check it.
 *
 * The editor-level half — a face space on a shaft's flat end, a section input on a ball's great circle — is
 * in `RevolveFaceSpaceTest`; the ball's own record stays in `SphereTest`.
 */
class RevolveFaceTest {
    // ---- fixtures: the profiles a lathe actually cuts ----

    private val plan = Plan3

    /** A profile region from a closed ring of corners, in the sketch's own coordinates. */
    private fun poly(vararg pts: Vec2): Region =
        Region(Loop(pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }), emptyList())

    /**
     * A plain shaft: radius [r], length [len], turned about the world X axis, the profile a rectangle whose
     * bottom edge lies **on** the axis.
     *
     * The four pieces in [Geom3.boundaryPieces] order are then: #1 the axis edge (sweeping nothing), #2 the
     * flat end at `x = len`, #3 the cylindrical barrel, #4 the flat end at `x = 0`.
     */
    private fun shaft(
        r: Double = 20.0,
        len: Double = 100.0,
        turn: Turn3 = Turn3.Full,
    ): Solid3 =
        assertNotNull(
            Geom3
                .revolve(
                    Sketch3(plan, listOf(poly(Vec2(0.0, 0.0), Vec2(len, 0.0), Vec2(len, r), Vec2(0.0, r)))),
                    Vec2(0.0, 0.0),
                    Vec2(1.0, 0.0),
                    turn,
                ).first,
        )

    /** A cone: a right triangle with its short leg on the axis, apex at `x = 0`, base radius [r] at `x = h`. */
    private fun cone(
        r: Double = 30.0,
        h: Double = 60.0,
    ): Solid3 =
        assertNotNull(
            Geom3
                .revolveFull(
                    Sketch3(plan, listOf(poly(Vec2(0.0, 0.0), Vec2(h, 0.0), Vec2(h, r)))),
                    Vec2(0.0, 0.0),
                    Vec2(1.0, 0.0),
                ).first,
        )

    /** A ball of radius [r] centred on the origin: a pole-to-pole meridian arc closed by its own diameter. */
    private fun ball(r: Double = 20.0): Solid3 {
        val south = Vec2(-r, 0.0)
        val north = Vec2(r, 0.0)
        val loop =
            Loop(
                listOf(
                    ProfileElement.ArcE(Arc(Vec2(0.0, 0.0), r, PI, 0.0, false)),
                    ProfileElement.Seg(Segment(north, south)),
                ),
            )
        return assertNotNull(
            Geom3.revolveFull(Sketch3(plan, listOf(Region(loop, emptyList()))), Vec2(0.0, 0.0), Vec2(1.0, 0.0)).first,
        )
    }

    /** A ring torus: a circular profile of radius [minor] whose centre stands [major] off the axis. */
    private fun torus(
        major: Double = 50.0,
        minor: Double = 10.0,
    ): Solid3 =
        assertNotNull(
            Geom3
                .revolveFull(
                    Sketch3(
                        plan,
                        listOf(Region(Loop(listOf(ProfileElement.CircleE(Circle(Vec2(0.0, major), minor), true))), emptyList())),
                    ),
                    Vec2(0.0, 0.0),
                    Vec2(1.0, 0.0),
                ).first,
        )

    private fun featureOf(s: Solid3): Feature3.Revolution = s.feature as Feature3.Revolution

    /** The pieces the section actually drew, and whether any of them is chords (OP-15). */
    private fun sectionOf(
        s: Solid3,
        p: Plane3,
    ) = Section3.sectionOf(s, p)

    // ---- the face family, and its identity ----

    /**
     * A shaft's four profile pieces give four faces — a nothing, two flat ends and a cylinder — in exactly
     * the order [Geom3.boundaryPieces] states, and the two flat ones are **planes one can sketch on**.
     */
    @Test
    fun aShaftsFacesAreItsProfilesOwnPiecesInOrder() {
        val f = featureOf(shaft())
        val (fs, why) = Section3.faces(f)
        assertNull(why)
        val faces = assertNotNull(fs)
        assertEquals(4, faces.size, "a complete turn has no caps, so only the profile's own four pieces")
        for (i in faces.indices) assertEquals(FaceName.Side(i), faces[i].name, "face #${i + 1} is named by its piece")
        assertTrue(faces[0].surface?.band is Revolve3.Band.Degenerate, "the edge on the axis sweeps nothing")
        assertNull(faces[0].plane)
        assertTrue(faces[1].surface?.band is Revolve3.Band.Planar, "the far end is a flat disc")
        assertNotNull(faces[1].plane, "…and therefore a plane one can sketch on")
        assertTrue(faces[2].surface?.band is Revolve3.Band.Cylinder, "the barrel is a cylinder")
        assertNull(faces[2].plane)
        assertTrue(faces[3].surface?.band is Revolve3.Band.Planar, "the near end is a flat disc too")
    }

    /** The exact parameters are **retained** on the patch: the barrel knows its own radius and its span. */
    @Test
    fun aBandKeepsTheParametersItWasBuiltFrom() {
        val faces = assertNotNull(Section3.faces(featureOf(shaft(r = 17.5, len = 64.0))).first)
        val barrel = assertNotNull(faces[2].surface?.band as? Revolve3.Band.Cylinder)
        assertClose(barrel.r, 17.5, tol = 1e-12, msg = "the barrel's radius is the profile's own")
        assertClose(barrel.s0, 0.0, tol = 1e-12, msg = "…and its band starts at the near end")
        assertClose(barrel.s1, 64.0, tol = 1e-12, msg = "…and ends at the far one")
        val coneBand = assertNotNull(Section3.faces(featureOf(cone(r = 30.0, h = 60.0))).first!![2].surface?.band as? Revolve3.Band.Cone)
        assertClose(coneBand.sApex, 0.0, tol = 1e-9, msg = "the cone's apex is where its profile edge meets the axis")
        assertClose(coneBand.tanHalf, 0.5, tol = 1e-12, msg = "…and its half-angle is the edge's own slope")
    }

    /** A ball is one spherical band closed on the axis, and the band knows the radius it was drawn with. */
    @Test
    fun aBallIsOneSphericalBandAndOneEdgeOnTheAxis() {
        val faces = assertNotNull(Section3.faces(featureOf(ball(r = 20.0))).first)
        assertEquals(2, faces.size)
        val sphere = assertNotNull(faces[0].surface?.band as? Revolve3.Band.Sphere)
        assertClose(sphere.radius, 20.0, tol = 1e-12, msg = "the sphere's radius is the meridian arc's own")
        assertClose(sphere.sc, 0.0, tol = 1e-12, msg = "…and its centre is on the axis")
        assertTrue(faces[1].surface?.band is Revolve3.Band.Degenerate, "the closing diameter lies on the axis")
    }

    /** An arc whose centre is **off** the axis sweeps a torus, and both radii come off the drawing. */
    @Test
    fun anArcOffTheAxisSweepsATorus() {
        val band = assertNotNull(Section3.faces(featureOf(torus(major = 50.0, minor = 10.0))).first!![0].surface?.band as? Revolve3.Band.Torus)
        assertClose(band.rc, 50.0, tol = 1e-12, msg = "the major radius is how far the arc's centre stands off the axis")
        assertClose(band.minor, 10.0, tol = 1e-12, msg = "the minor radius is the arc's own")
    }

    /**
     * **The identity rule**: a face's name is the index of the profile piece that sweeps it, so retyping a
     * radius or dragging the profile leaves face #3 face #3 — with the *same family* and a *new* number.
     */
    @Test
    fun aFaceKeepsItsNameThroughEveryEditOfTheProfile() {
        val before = assertNotNull(Section3.faces(featureOf(shaft(r = 20.0, len = 100.0))).first)
        val after = assertNotNull(Section3.faces(featureOf(shaft(r = 31.0, len = 45.0))).first)
        assertEquals(before.map { it.name }, after.map { it.name }, "the names are the construction's, not the geometry's")
        assertEquals(4, after.size)
        assertClose(
            (after[2].surface?.band as Revolve3.Band.Cylinder).r,
            31.0,
            tol = 1e-12,
            msg = "the same face, the new radius",
        )
        // and the sweep changing does not renumber anything either
        val partial = assertNotNull(Section3.faces(featureOf(shaft(turn = Turn3.Arc.of(0.0, PI / 2)))).first)
        assertEquals(before.map { it.name }, partial.take(4).map { it.name })
        assertEquals(FaceName.RevolveCap(SolidFace.BOTTOM), partial[4].name, "the caps come after, low angle first")
        assertEquals(FaceName.RevolveCap(SolidFace.TOP), partial[5].name)
    }

    /** A partial turn's two caps are planes — the profile itself, standing at each end of the interval. */
    @Test
    fun aPartialTurnAddsTwoFlatCaps() {
        val f = featureOf(shaft(r = 20.0, len = 100.0, turn = Turn3.Arc.of(0.0, PI / 2)))
        val faces = assertNotNull(Section3.faces(f).first)
        assertEquals(6, faces.size, "four bands and two caps")
        val low = assertNotNull(faces[4].plane)
        val high = assertNotNull(faces[5].plane)
        // the low cap stands in the sketch plane (the interval starts there) and the high one a quarter round
        assertClose(abs(low.normal.normalized().dot(plan.normal.normalized())), 1.0, tol = 1e-12, msg = "the start cap is the sketch plane")
        assertClose(high.normal.normalized().dot(plan.normal.normalized()), 0.0, tol = 1e-9, msg = "the end cap stands a quarter turn round")
        // …and each one's outline encloses the profile's own area, 100 x 20
        val ext = faces[5].outline.flatMap { listOf(constructit.geom.GeomMath.startOf(it), constructit.geom.GeomMath.endOf(it)) }
        assertClose(ext.maxOf { it.x } - ext.minOf { it.x }, 100.0, tol = 1e-9, msg = "the cap is the profile, a shaft long")
        assertClose(ext.maxOf { it.y } - ext.minOf { it.y }, 20.0, tol = 1e-9, msg = "…and a radius tall")
    }

    /** The structural edges are the rings the profile's corners trace — and a corner on the axis is a point. */
    @Test
    fun theEdgesAreTheRingsTheProfileCornersTrace() {
        val (es, why) = Section3.edges(featureOf(shaft()))
        assertNull(why)
        val edges = assertNotNull(es)
        assertEquals(4, edges.size)
        for (i in edges.indices) assertEquals(EdgeName.RevolveRing(i), edges[i].name)
        assertTrue(edges[0].geom is constructit.geom.EdgeGeom.Straight, "the corner on the axis traces a point")
        assertTrue(edges[2].geom is constructit.geom.EdgeGeom.OnPlane, "a corner off the axis traces a ring")
    }

    // ---- the dispatch table, pinned path by path ----

    /** A plane through a ball's centre: **one exact circle**, of the radius the drawing states. */
    @Test
    fun aPlaneThroughABallsCentreIsAnExactCircle() {
        val s = ball(r = 20.0)
        // a plane containing the axis: the family answer (a sphere's section is a circle for every plane)
        val cut = Plane3(Vec3.ZERO, Vec3.X, Vec3.Z)
        val sec = sectionOf(s, cut)
        assertNull(sec.inputsRefusal, "a revolution names its faces now")
        assertTrue(!sec.approximated, "not chords: an exact circle")
        val circles = sec.drawn.filterIsInstance<ProfileElement.CircleE>()
        assertEquals(1, circles.size, "one great circle and nothing else")
        assertClose(circles[0].circle.radius, 20.0, tol = 1e-9, msg = "exact to the last micron and beyond")
        assertClose(circles[0].circle.center.x, 0.0, tol = 1e-9, msg = "centred where the ball is")
        assertClose(circles[0].circle.center.y, 0.0, tol = 1e-9, msg = "…in both directions")
    }

    /** An **off-centre** plane cuts a ball in the exact small circle its own Pythagoras gives. */
    @Test
    fun anOffCentrePlaneCutsABallInTheExactSmallCircle() {
        val s = ball(r = 20.0)
        for (d in listOf(5.0, 12.0, 19.5)) {
            val cut = Plane3(Vec3(0.0, 0.0, d), Vec3.X, Vec3.Y)
            val sec = sectionOf(s, cut)
            assertTrue(!sec.approximated, "exact at $d away too")
            val c = sec.drawn.filterIsInstance<ProfileElement.CircleE>().single()
            assertClose(c.circle.radius, sqrt(400.0 - d * d), tol = 1e-9, msg = "a small circle of the stated radius at $d")
        }
        // and a plane past the surface cuts nothing at all
        assertTrue(sectionOf(s, Plane3(Vec3(0.0, 0.0, 25.0), Vec3.X, Vec3.Y)).isEmpty, "a plane clear of the ball cuts nothing")
    }

    /** A cylinder cut **through its axis** is two exact rulings — one per side, each a whole barrel long. */
    @Test
    fun aCylinderThroughItsAxisIsTwoExactLines() {
        val s = shaft(r = 20.0, len = 100.0)
        val sec = sectionOf(s, Plane3(Vec3.ZERO, Vec3.X, Vec3.Z))
        assertTrue(!sec.approximated, "rulings are exact, not chords")
        val barrel = assertNotNull(sec.edges.getOrNull(2))
        assertNotNull(barrel.reason, "two pieces cannot be one input, and the refusal says so")
        assertTrue("2 separate pieces" in barrel.reason!!, "…naming how many: ${barrel.reason}")
        val segs = sec.pieces.filter { !it.approximated }.map { it.piece }.filterIsInstance<ProfileElement.Seg>()
        val long = segs.filter { (it.segment.b - it.segment.a).length() > 50.0 }
        assertEquals(2, long.size, "one ruling on each side of the axis")
        for (r in long) {
            assertClose((r.segment.b - r.segment.a).length(), 100.0, tol = 1e-9, msg = "each ruling is the whole barrel")
            assertClose(abs(r.segment.a.y), 20.0, tol = 1e-9, msg = "…standing a radius off the axis")
        }
    }

    /** A cylinder cut **obliquely** is the exact ellipse, with `r` and `r / cos θ` for its semi-axes. */
    @Test
    fun anObliqueCutOfACylinderIsTheExactEllipse() {
        val s = shaft(r = 20.0, len = 200.0)
        // θ is the tilt away from the axis-normal plane, so the plane's normal makes θ with the axis
        val th = PI / 6
        val cut = Plane3(Vec3(100.0, 0.0, 0.0), Vec3.Y, Vec3(-sin(th), 0.0, cos(th)))
        val sec = sectionOf(s, cut)
        assertTrue(!sec.approximated, "the conics package names this one (OP-24)")
        val e = sec.drawn.filterIsInstance<ProfileElement.EllipseE>().single().ellipse
        assertClose(e.major, 20.0 / cos(th), tol = 1e-9, msg = "the major semi-axis is r / cos θ")
        assertClose(e.minor, 20.0, tol = 1e-9, msg = "the minor one is the radius itself")
    }

    /**
     * A cone's **oblique** section is the exact conic — and it is exact only where the conic *is* an
     * ellipse: steeper than the half-angle. The predicate is what is pinned, not just the shape.
     */
    @Test
    fun aConesObliqueSectionIsTheExactEllipseWhereOneExists() {
        val s = cone(r = 30.0, h = 60.0)
        // half-angle: tan α = 1/2, so sin α = 1/√5 ≈ 0.4472. A plane at 60° to the axis has |n·A| = 0.5 > sin α.
        val th = PI / 3
        val cut = Plane3(Vec3(40.0, 0.0, 0.0), Vec3(cos(th), 0.0, sin(th)), Vec3.Y)
        val sec = sectionOf(s, cut)
        val e = assertNotNull(sec.drawn.filterIsInstance<ProfileElement.EllipseE>().singleOrNull(), "one exact ellipse")
        assertTrue(!sec.edges[2].approximated, "the conical band's own edge is exact")
        // every point of the claimed ellipse must actually be on the cone: |radial| = tan α · x
        for (i in 0 until 64) {
            val p = cut.toWorld(Conics.pointAt(e.ellipse, 2.0 * PI * i / 64))
            assertClose(hypot(p.y, p.z), 0.5 * p.x, tol = 1e-9, msg = "sample $i lies on the cone")
        }
    }

    /**
     * A cone cut **shallower** than its own half-angle is a hyperbola, which this drawing has no name for —
     * so it comes back as the honest sampled answer, and the dispatch says which path it took.
     */
    @Test
    fun aConesHyperbolicSectionIsSampledAndSaysSo() {
        val s = cone(r = 30.0, h = 60.0)
        // parallel to the axis and off it: the classic hyperbola
        val cut = Plane3(Vec3(0.0, 0.0, 10.0), Vec3.X, Vec3.Y)
        val band = assertNotNull(Revolve3.cutBand(featureOf(s), 2, cut))
        assertNull(band.exact, "a hyperbola has no name here, so no exact answer is offered")
        assertTrue(assertNotNull(band.runs).isNotEmpty(), "…and the honest sampled one is")
        val sec = sectionOf(s, cut)
        assertTrue(sec.approximated, "the section says it is chords")
        assertTrue(sec.edges[2].approximated, "…and it says it about the conical band in particular")
    }

    /** A torus is exact in the two trivial plane families — and only there. The dispatch is what is asserted. */
    @Test
    fun aTorusIsExactInTheTwoTrivialFamiliesAndSampledElsewhere() {
        val s = torus(major = 50.0, minor = 10.0)
        val f = featureOf(s)
        // (1) perpendicular to the axis, through the tube's own centre plane: two exact circles
        val normal = Plane3(Vec3.ZERO, Vec3.Y, Vec3.Z)
        val a = assertNotNull(Revolve3.cutBand(f, 0, normal))
        val exactA = assertNotNull(a.exact, "an axis-normal cut of a torus is exact")
        assertEquals(2, exactA.size, "the two concentric circles it is")
        val radii = exactA.filterIsInstance<ProfileElement.CircleE>().map { it.circle.radius }.sorted()
        assertClose(radii[0], 40.0, tol = 1e-9, msg = "the inner circle is major − minor")
        assertClose(radii[1], 60.0, tol = 1e-9, msg = "…and the outer one major + minor")
        // (2) containing the axis: the profile circle, twice
        val axial = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val b = assertNotNull(Revolve3.cutBand(f, 0, axial))
        val exactB = assertNotNull(b.exact, "a through-axis cut of a torus is exact")
        assertEquals(2, exactB.size, "one profile circle on each side of the axis")
        for (c in exactB.filterIsInstance<ProfileElement.CircleE>()) {
            assertClose(c.circle.radius, 10.0, tol = 1e-9, msg = "each is the profile's own circle")
            assertClose(abs(c.circle.center.y), 50.0, tol = 1e-9, msg = "…standing the major radius off the axis")
        }
        // (3) anywhere else: a quartic, which has no name — the honest sampled answer, and it says so
        val oblique = Plane3(Vec3(0.0, 0.0, 3.0), Vec3(1.0, 0.0, 0.3).normalized(), Vec3.Y)
        val c = assertNotNull(Revolve3.cutBand(f, 0, oblique))
        assertNull(c.exact, "a torus's oblique section is a quartic, and this drawing has no name for one")
        assertTrue(assertNotNull(c.runs).isNotEmpty(), "…so it comes back sampled")
        assertTrue(sectionOf(s, oblique).approximated, "and the section says it is chords")
    }

    /** A plane perpendicular to the axis is exact for **every** family, because it reads the profile itself. */
    @Test
    fun anAxisNormalCutIsExactWhateverTheProfileIs() {
        val cut = Plane3(Vec3(50.0, 0.0, 0.0), Vec3.Y, Vec3.Z)
        val sec = sectionOf(shaft(r = 20.0, len = 100.0), cut)
        assertTrue(!sec.approximated, "a lathe's own measurement is exact")
        val c = sec.drawn.filterIsInstance<ProfileElement.CircleE>().single()
        assertClose(c.circle.radius, 20.0, tol = 1e-12, msg = "the barrel's own radius")
        val coneSec = sectionOf(cone(r = 30.0, h = 60.0), Plane3(Vec3(40.0, 0.0, 0.0), Vec3.Y, Vec3.Z))
        assertTrue(!coneSec.approximated)
        assertClose(
            coneSec.drawn.filterIsInstance<ProfileElement.CircleE>().single().circle.radius,
            20.0,
            tol = 1e-9,
            msg = "a cone at two thirds of its height is two thirds of its base radius",
        )
    }

    /** A partial turn's caps and its band arcs are exact too, and the arcs stop where the sweep does. */
    @Test
    fun aPartialTurnsSectionIsExactAndStopsWhereTheSweepDoes() {
        val s = shaft(r = 20.0, len = 100.0, turn = Turn3.Arc.of(0.0, PI / 2))
        val sec = sectionOf(s, Plane3(Vec3(50.0, 0.0, 0.0), Vec3.Y, Vec3.Z))
        assertTrue(!sec.approximated, "a quarter of a shaft sections exactly")
        val arcs = sec.drawn.filterIsInstance<ProfileElement.ArcE>()
        assertEquals(1, arcs.size, "one quarter-arc of the barrel")
        assertClose(arcs[0].arc.radius, 20.0, tol = 1e-12, msg = "at the barrel's radius")
        assertClose(abs(constructit.geom.GeomMath.sweep(arcs[0].arc)), PI / 2, tol = 1e-9, msg = "…and a quarter turn long")
        // …and the two cap faces are cut as the exact radial segments they are
        val segs = sec.drawn.filterIsInstance<ProfileElement.Seg>()
        assertTrue(segs.size >= 2, "the two caps each contribute a radial segment: ${segs.size}")
        for (g in segs) assertClose((g.segment.b - g.segment.a).length(), 20.0, tol = 1e-9, msg = "each is a radius long")
    }

    /**
     * A band the exact curve **leaves** falls back rather than lying: a great circle of the sphere a
     * hemisphere is only half of runs off the band, so the cut comes back the honest sampled way.
     */
    @Test
    fun anExactCurveThatLeavesItsBandFallsBackHonestly() {
        // a hemisphere: a quarter-circle meridian from the pole to the equator, closed on the axis
        val r = 20.0
        val loop =
            Loop(
                listOf(
                    ProfileElement.ArcE(Arc(Vec2(0.0, 0.0), r, PI / 2, 0.0, false)),
                    ProfileElement.Seg(Segment(Vec2(r, 0.0), Vec2(0.0, 0.0))),
                    ProfileElement.Seg(Segment(Vec2(0.0, 0.0), Vec2(0.0, r))),
                ),
            )
        val s =
            assertNotNull(
                Geom3.revolveFull(Sketch3(plan, listOf(Region(loop, emptyList()))), Vec2(0.0, 0.0), Vec2(1.0, 0.0)).first,
            )
        val f = featureOf(s)
        // an oblique plane through the middle: the sphere's circle is only partly on the hemisphere's band
        val cut = Plane3(Vec3(5.0, 0.0, 0.0), Vec3(cos(PI / 4), 0.0, sin(PI / 4)), Vec3.Y)
        val band = assertNotNull(Revolve3.cutBand(f, 0, cut))
        assertNull(band.exact, "the circle leaves the band, so no exact curve is claimed for it")
        assertTrue(assertNotNull(band.runs).isNotEmpty(), "…and the sampled answer stands in")
        // but a plane the circle stays wholly inside is still exact
        val inside = Plane3(Vec3(12.0, 0.0, 0.0), Vec3.Y, Vec3.Z)
        assertTrue(!sectionOf(s, inside).approximated, "a cut wholly on the band is exact as ever")
    }

    /** A profile drawn with a spline sweeps a surface with no name, and every route says so honestly. */
    @Test
    fun aSplineProfileIsNamedButNeverClaimedExact() {
        val b =
            constructit.geom.Bezier(Vec2(0.0, 20.0), Vec2(30.0, 35.0), Vec2(70.0, 5.0), Vec2(100.0, 20.0))
        val loop =
            Loop(
                listOf(
                    ProfileElement.Seg(Segment(Vec2(0.0, 0.0), Vec2(100.0, 0.0))),
                    ProfileElement.Seg(Segment(Vec2(100.0, 0.0), Vec2(100.0, 20.0))),
                    ProfileElement.BezierE(constructit.geom.Bezier(b.p3, b.p2, b.p1, b.p0)),
                    ProfileElement.Seg(Segment(Vec2(0.0, 20.0), Vec2(0.0, 0.0))),
                ),
            )
        val s =
            assertNotNull(
                Geom3.revolveFull(Sketch3(plan, listOf(Region(loop, emptyList()))), Vec2(0.0, 0.0), Vec2(1.0, 0.0)).first,
            )
        val f = featureOf(s)
        val faces = assertNotNull(Section3.faces(f).first)
        assertTrue(faces[2].surface?.band is Revolve3.Band.Unnamed, "a spline sweeps a surface this drawing cannot name")
        // …and yet an axis-normal cut of it is still exact, because that column reads the profile itself
        val sec = sectionOf(s, Plane3(Vec3(50.0, 0.0, 0.0), Vec3.Y, Vec3.Z))
        assertTrue(!sec.edges[2].approximated, "the profile's own crossing is analytic whatever kind it is")
        // an oblique one is not, and says so
        val oblique = Plane3(Vec3(50.0, 0.0, 0.0), Vec3(cos(PI / 4), 0.0, sin(PI / 4)), Vec3.Y)
        assertNull(assertNotNull(Revolve3.cutBand(f, 2, oblique)).exact, "no name, no exact claim")
    }

    /** Nothing built here is a cracked shell — the doctrine's first half, on every fixture in this file. */
    @Test
    fun everyBodyInThisSuiteIsWatertight() {
        for (s in listOf(shaft(), shaft(turn = Turn3.Arc.of(0.4, PI / 2)), cone(), ball(), torus())) {
            assertManifold(s.mesh)
        }
    }

    companion object {
        /** The world XY plane, which every fixture here is drawn on. */
        private val Plan3 = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
    }
}
