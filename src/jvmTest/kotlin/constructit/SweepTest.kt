package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.Path3Ref
import constructit.dsl.Point3Ref
import constructit.dsl.solid
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Feature3
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Path3
import constructit.geom.Region
import constructit.geom.SweepProfile
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The moving frame and the sweep** (OP-26, step 2) at the level of the geometry itself: a profile carried
 * along a curve in space, on a frame that is parallel transport and never Frenet.
 *
 * The frame choice is what most of this asserts, because the frame is what the step is *for*. Three of the
 * cases below are exactly the ones a Frenet frame gets wrong and a real tube does not: a **straight** path,
 * where the Frenet normal is 0/0; a path with an **inflection**, where it jumps by π; and a straight–bend–
 * straight run, which is both at once. Each is asserted structurally — the frame never flips, the shell is
 * watertight — rather than by looking at a volume and hoping.
 *
 * Every solid built anywhere here goes through [assertManifold]: watertight or refused (OP-9), no exception
 * for the newest feature.
 */
class SweepTest {
    // ---- the fixtures ----

    /** A straight path from [a] to [b] — one [Curve3Element.Seg3], and the case with no Frenet normal. */
    private fun straight(
        a: Vec3,
        b: Vec3,
    ) = Path3(Curves3.straightThrough(listOf(a, b)))

    private fun polyline(vararg p: Vec3) = Path3(Curves3.straightThrough(p.toList()))

    private fun smooth(
        vararg p: Vec3,
        closed: Boolean = false,
    ) = Path3(Curves3.smoothThrough(p.toList(), closed), closed)

    /** The world XY plane's normal — what a path drawn in the plan starts its frame from. */
    private val up = Vec3.Z

    /**
     * A **right-angled triangle** profile with its corner on the path: `(0,0)`, `(w,0)`, `(0,h)`.
     *
     * Deliberately asymmetric under a half turn, which is what makes the twist assertions possible at all —
     * a rectangle turned 180° is the same set of points, so it could not tell a half turn from none.
     */
    private fun triangle(
        w: Double,
        h: Double,
    ): Region = regionOfPolygon(listOf(Vec2(0.0, 0.0), Vec2(w, 0.0), Vec2(0.0, h)))

    /** An axis-aligned rectangle centred on the path, [w] by [h]. */
    private fun rectangle(
        w: Double,
        h: Double,
    ): Region =
        regionOfPolygon(
            listOf(Vec2(-w / 2, -h / 2), Vec2(w / 2, -h / 2), Vec2(w / 2, h / 2), Vec2(-w / 2, h / 2)),
        )

    private fun regionOfPolygon(pts: List<Vec2>): Region =
        Region(
            constructit.geom.Loop(
                pts.indices.map {
                    constructit.geom.ProfileElement.Seg(
                        constructit.geom.Segment(pts[it], pts[(it + 1) % pts.size]),
                    )
                },
            ),
            emptyList(),
        )

    private fun sweptOrFail(
        path: Path3,
        profile: SweepProfile,
        roll: Double = 0.0,
        twist: Double = 0.0,
    ): constructit.geom.Solid3 {
        val (solid, why) = Geom3.sweep(path, up, profile, roll, twist)
        return assertNotNull(solid, "the sweep was refused: $why")
    }

    private fun refusal(
        path: Path3,
        profile: SweepProfile,
        roll: Double = 0.0,
        twist: Double = 0.0,
    ): String {
        val (solid, why) = Geom3.sweep(path, up, profile, roll, twist)
        assertNull(solid, "this sweep was expected to be refused")
        return assertNotNull(why, "a refusal says why")
    }

    /** The tessellated area of the profile the mesh is actually made of — what an exact volume is against. */
    private fun tessArea(profile: SweepProfile): Double {
        val (t, why) = Geom3.tessellateRegion(profile.region)
        return Geom3.tessArea(assertNotNull(t, why))
    }

    // ---- 1. a tube along a straight segment is a cylinder ----

    /**
     * **The first claim the frame has to earn**: a circle carried along a straight run is a cylinder — the
     * right volume, watertight, and capped by two planes normal to the tangent.
     *
     * The volume is asserted **twice**, which is OP-15's honesty class written as a test: exactly the volume
     * of the polygon the mesh is made of (the tessellated circle times the length, to the last bit, because a
     * prism over a polygon is analytic), and πr²L to within the tessellation's own sagitta.
     */
    @Test
    fun aTubeAlongAStraightSegmentIsACylinder() {
        val r = 8.0
        val length = 120.0
        val solid = sweptOrFail(straight(Vec3(0.0, 0.0, 0.0), Vec3(length, 0.0, 0.0)), SweepProfile.Round(r))
        assertManifold(solid.mesh, "the tube")

        val exact = tessArea(SweepProfile.Round(r)) * length
        assertClose(Geom3.volume(solid.mesh), exact, 1e-6, "the tube is exactly the prism over its own tessellated circle")
        // …and pi r^2 L to within what the tessellation itself costs: the polygon falls short of the circle
        // by at most the perimeter times the chord tolerance, per unit of run
        assertClose(
            Geom3.volume(solid.mesh),
            PI * r * r * length,
            2.0 * PI * r * constructit.geom.GeomMath.TESS_TOL_MM * length,
            "and pi r^2 L to the tessellation",
        )

        // the caps: every vertex at each end lies in one plane whose normal is the tangent
        val axis = Vec3.X
        val lo = solid.mesh.vertices.filter { abs(it.dot(axis)) < 1e-9 }
        val hi = solid.mesh.vertices.filter { abs(it.dot(axis) - length) < 1e-9 }
        assertTrue(lo.size >= 3 && hi.size >= 3, "both ends carry a ring: ${lo.size} and ${hi.size}")
        assertEquals(solid.mesh.vertices.size, lo.size + hi.size, "a straight tube has exactly two rings of vertices")
        for (v in lo) assertClose(v.dot(axis), 0.0, 1e-12, "the start cap is planar and normal to the tangent")
        for (v in hi) assertClose(v.dot(axis), length, 1e-9, "the end cap is planar and normal to the tangent")
    }

    // ---- 2 & 3. the Frenet failure cases: a straight run, an inflection, and a bend between straights ----

    /**
     * **A straight path has no Frenet normal at all** — the case that would divide by zero — and the frame
     * along it is not merely defined but *constant*: every station carries the identical reference direction,
     * which is what "introducing no rotation about the tangent" means when there is no rotation to introduce.
     */
    @Test
    fun aStraightPathHasNoCurvatureAndACompletelyConstantFrame() {
        val path = straight(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 90.0))
        // …and it runs straight **up the space's own normal**, so the start reference is the degenerate case
        // too: the projection of +Z perpendicular to +Z is nothing, and the fallback has to answer
        val (frame, why) = Frames3.along(path, up, reach = 5.0)
        val f = assertNotNull(frame, why)
        assertTrue(f.stations.size >= 2, "a straight run needs two stations and no more")
        for (st in f.stations) {
            assertEquals(0.0, st.curvature, "a straight run has exactly zero curvature — not nearly zero")
            assertClose(st.ref.dot(st.tangent), 0.0, 1e-12, "the reference stays perpendicular to the tangent")
        }
        for (i in 1 until f.stations.size) {
            assertClose((f.stations[i].ref - f.stations[0].ref).length(), 0.0, 1e-12, "the frame never turns on a straight run")
        }
        // and the sweep along it is a perfectly ordinary solid
        val solid = sweptOrFail(path, SweepProfile.Round(5.0))
        assertManifold(solid.mesh, "the vertical tube")
        assertClose(Geom3.volume(solid.mesh), tessArea(SweepProfile.Round(5.0)) * 90.0, 1e-6, "a vertical riser is still a cylinder")
    }

    /**
     * **The whole point of the frame choice**: an **S-curve** — a path with an inflection, where the Frenet
     * normal jumps by π — sweeps watertight, and the frame does not flip anywhere along it.
     *
     * Asserted structurally rather than by eyeballing a volume: consecutive reference directions keep a
     * **positive** dot product, station by station, so nothing about the section is ever turned inside out.
     * A Frenet frame would fail this exactly once, at the inflection.
     */
    @Test
    fun aSweepThroughAnInflectionIsWatertightAndTheFrameNeverFlips() {
        val path =
            smooth(
                Vec3(0.0, 0.0, 0.0),
                Vec3(60.0, 40.0, 0.0),
                Vec3(120.0, -40.0, 0.0),
                Vec3(180.0, 0.0, 0.0),
            )
        assertNeverFlips(path, 6.0)
        val solid = sweptOrFail(path, SweepProfile.Round(6.0))
        assertManifold(solid.mesh, "the S-bend tube")
        assertTrue(Geom3.volume(solid.mesh) > 0.0, "and it encloses material")
    }

    /**
     * **A straight–bend–straight run** — the shape OP-26 names as the one a Frenet sweep tears on, because
     * the normal is undefined on both straights and defined in between. Watertight, and no flip.
     */
    @Test
    fun aSweepAlongAStraightBendStraightRunIsWatertightAndTheFrameNeverFlips() {
        // five points: a straight lead-in, a bend that also climbs, and a straight run out
        val path =
            smooth(
                Vec3(0.0, 0.0, 0.0),
                Vec3(50.0, 0.0, 0.0),
                Vec3(100.0, 30.0, 20.0),
                Vec3(150.0, 60.0, 40.0),
                Vec3(200.0, 60.0, 40.0),
            )
        assertNeverFlips(path, 5.0)
        val solid = sweptOrFail(path, SweepProfile.Round(5.0))
        assertManifold(solid.mesh, "the line-bend-line tube")
    }

    /** …and the same run drawn as a **polyline**, where the corners are genuine kinks that must be mitred. */
    @Test
    fun aTubeAlongAPolylineIsMitredAtItsCornersAndStaysWatertight() {
        val path =
            polyline(
                Vec3(0.0, 0.0, 0.0),
                Vec3(80.0, 0.0, 0.0),
                Vec3(80.0, 80.0, 0.0),
                Vec3(80.0, 80.0, 60.0),
            )
        assertNeverFlips(path, 6.0)
        val solid = sweptOrFail(path, SweepProfile.Round(6.0))
        assertManifold(solid.mesh, "the mitred tube")
        // a mitre neither loses nor adds material at the joint: the volume is the three runs' own, plus the
        // corner wedges, so it is bounded below by the runs minus one section-length per corner
        val area = tessArea(SweepProfile.Round(6.0))
        assertTrue(Geom3.volume(solid.mesh) > area * (80.0 + 80.0 + 60.0 - 2.0 * 12.0), "the joints did not eat the run")
    }

    /**
     * **The frame introduces no rotation about the tangent, station by station** — the defining property of
     * parallel transport, asserted directly: the reference direction turns by *exactly* as much as the
     * tangent does between two stations and never more.
     *
     * That subsumes the flip test and is stronger than it. A Frenet frame at an inflection turns its normal
     * by π while the tangent barely moves, so it fails this at the one station that matters; and where the
     * tangent itself turns less than a right angle (which is everywhere on a sampled smooth curve) the
     * consecutive references are additionally asserted to keep a **positive** dot product, so nothing about
     * the section is ever turned inside out.
     */
    private fun assertNeverFlips(
        path: Path3,
        reach: Double,
    ) {
        val (frame, why) = Frames3.along(path, up, reach = reach)
        val f = assertNotNull(frame, why)
        assertTrue(f.stations.size >= 4, "the path is sampled into stations to look at: ${f.stations.size}")
        for (i in 1 until f.stations.size) {
            val a = f.stations[i - 1]
            val b = f.stations[i]
            val turnedTangent = angleBetween(a.tangent, b.tangent)
            val turnedRef = angleBetween(a.ref, b.ref)
            assertTrue(
                turnedRef <= turnedTangent + 1e-9,
                "the frame turned $turnedRef rad between stations ${i - 1} and $i while the tangent turned " +
                    "$turnedTangent — that is a rotation about the tangent, which transport must not introduce",
            )
            if (turnedTangent < PI / 2.0 - 1e-9) {
                val d = a.ref.dot(b.ref)
                assertTrue(d > 0.0, "the frame flipped between stations ${i - 1} and $i (dot $d) — that is the Frenet defect")
            }
            assertClose(b.ref.dot(b.tangent), 0.0, 1e-9, "and it stays perpendicular to the tangent")
        }
    }

    private fun angleBetween(
        a: Vec3,
        b: Vec3,
    ): Double = kotlin.math.atan2(a.cross(b).length(), a.dot(b))

    // ---- 4. the twist ----

    /**
     * **A full turn of twist along a straight path brings the section back to where it started; half a turn
     * puts it opposite.** Asserted on the *mesh* — the direction of the section's farthest corner at each
     * end — rather than on any internal, so it is a claim about the solid that comes out.
     *
     * The straight path is deliberate twice over: it is the case with no curvature to confuse the reading,
     * and it is the case a naive sampler gets wrong, since a straight piece would be two stations and the
     * whole turn would vanish between them. The station count is refined by the twist for exactly that
     * reason (see [Frames3.along]).
     */
    @Test
    fun aFullTurnOfTwistComesBackAndAHalfTurnIsOpposite() {
        val profile = SweepProfile.Section(triangle(12.0, 4.0))
        val plain = sweptOrFail(straight(Vec3.ZERO, Vec3(100.0, 0.0, 0.0)), profile)
        assertManifold(plain.mesh, "the untwisted sweep")
        val at0 = cornerDirection(plain.mesh, 0.0)
        assertVecClose(at0, Vec3.Z, 1e-9, "with no twist the section's far corner stands along the space's normal")
        assertVecClose(cornerDirection(plain.mesh, 100.0), Vec3.Z, 1e-9, "and it is still there at the far end")

        val full = sweptOrFail(straight(Vec3.ZERO, Vec3(100.0, 0.0, 0.0)), profile, twist = 2.0 * PI)
        assertManifold(full.mesh, "the fully twisted sweep")
        assertVecClose(cornerDirection(full.mesh, 100.0), Vec3.Z, 1e-9, "a full turn returns the section to its start orientation")

        val half = sweptOrFail(straight(Vec3.ZERO, Vec3(100.0, 0.0, 0.0)), profile, twist = PI)
        assertManifold(half.mesh, "the half-twisted sweep")
        assertVecClose(cornerDirection(half.mesh, 100.0), -Vec3.Z, 1e-9, "and half a turn puts it exactly opposite")

        // …and the twist really did happen in between rather than only at the ends
        // half way along a half turn is a quarter turn: +90° about +X takes the space's normal (+Z) to −Y
        assertVecClose(cornerDirection(half.mesh, 50.0), -Vec3.Y, 1e-6, "the twist is spread along the run, linearly in arc length")
        assertTrue(full.mesh.vertices.size > 3 * 8, "a turn along a straight run is sampled finely enough to be a shape: ${full.mesh.vertices.size}")
    }

    /**
     * Which way the section's **farthest corner** points at the station whose axis coordinate is [x] — the
     * mesh-level reading of "how the profile is turned there".
     */
    private fun cornerDirection(
        mesh: Mesh3,
        x: Double,
    ): Vec3 {
        val ring = mesh.vertices.filter { abs(it.x - x) < 1e-6 }
        assertTrue(ring.isNotEmpty(), "there is a ring at x = $x")
        val far = ring.maxByOrNull { Vec3(0.0, it.y, it.z).length() }!!
        return Vec3(0.0, far.y, far.z).normalized()
    }

    // ---- 5. the roll ----

    /**
     * **The roll turns the whole tube about its path and changes nothing else.** Same volume, same
     * watertightness, same triangle count — the section is simply standing somewhere else, which is what a
     * *stated* start frame means (OP-26: explicit anchors beat compensation).
     */
    @Test
    fun theRollTurnsTheSectionAboutTheRunAndChangesNothingElse() {
        val profile = SweepProfile.Section(triangle(12.0, 4.0))
        val path = straight(Vec3.ZERO, Vec3(100.0, 0.0, 0.0))
        val plain = sweptOrFail(path, profile)
        val rolled = sweptOrFail(path, profile, roll = PI / 2.0)
        assertManifold(rolled.mesh, "the rolled sweep")

        assertClose(Geom3.volume(rolled.mesh), Geom3.volume(plain.mesh), 1e-9, "a roll moves no material")
        assertEquals(plain.mesh.triangleCount, rolled.mesh.triangleCount, "and it is the same shell, turned")
        // +90° about +X takes the space's normal (+Z) to −Y, which is where the far corner now stands
        assertVecClose(cornerDirection(rolled.mesh, 0.0), -Vec3.Y, 1e-9, "the section is rolled at the start")
        assertVecClose(cornerDirection(rolled.mesh, 100.0), -Vec3.Y, 1e-9, "and all the way along, since a roll is not a twist")
    }

    // ---- 6. the self-intersection refusal ----

    /**
     * **The profile outgrowing the path's bend is refused, and the refusal names the station** — how far
     * along the path the bend is and how tight it is. Then it **heals**: bring the radius back down and the
     * very same construction is a solid again, which is why this is node invalidity (OP-3) and not a
     * permanent no.
     */
    @Test
    fun aProfileLargerThanTheBendIsRefusedByStationAndHeals() {
        // a hairpin: three points close together, so the interpolating cubic bends hard in the middle
        val path = smooth(Vec3(0.0, 0.0, 0.0), Vec3(20.0, 14.0, 0.0), Vec3(40.0, 0.0, 0.0))
        val tight = assertNotNull(Frames3.along(path, up, reach = 1.0).first).stations.maxOf { it.curvature }
        assertTrue(tight > 0.0, "the fixture really does bend")
        val radius = 1.0 / tight

        val why = refusal(path, SweepProfile.Round(radius * 1.5))
        assertTrue(why.contains("the tube's radius"), "the refusal names what is too big: $why")
        assertTrue(why.contains("mm along the path"), "and where the bend is: $why")
        assertTrue(why.contains("pass through itself"), "and what would go wrong: $why")

        // …and the same path with a section that fits comes out watertight
        val ok = sweptOrFail(path, SweepProfile.Round(radius * 0.5))
        assertManifold(ok.mesh, "the tube that fits round the bend")
    }

    /** An arbitrary profile is judged by its **reach** from the path, and the refusal says so in its words. */
    @Test
    fun anArbitraryProfileIsJudgedByItsReachFromThePath() {
        val path = smooth(Vec3(0.0, 0.0, 0.0), Vec3(20.0, 14.0, 0.0), Vec3(40.0, 0.0, 0.0))
        val why = refusal(path, SweepProfile.Section(triangle(60.0, 8.0)))
        assertTrue(why.contains("the profile's reach from the path"), "an area is named by its reach: $why")
        assertTrue(why.contains("mm along the path"), "and the station is named: $why")
    }

    // ---- the closed path, and its seam ----

    /**
     * **A planar closed path closes exactly**, with no residual at all and no twist to state — because the
     * reference direction *is* the rotation axis at every step of the transport, so it is carried through
     * unchanged. So the everyday closed run (a ring, a loop of conduit drawn in one space) simply works.
     */
    @Test
    fun aPlanarClosedPathClosesItsFrameExactly() {
        val path = smooth(Vec3(0.0, 0.0, 0.0), Vec3(80.0, 0.0, 0.0), Vec3(80.0, 60.0, 0.0), Vec3(0.0, 60.0, 0.0), closed = true)
        val f = assertNotNull(Frames3.along(path, up, reach = 5.0).first)
        assertTrue(f.closed, "the frame knows the path is closed")
        assertClose(f.seam, 0.0, 1e-12, "a planar loop's frame comes back to itself exactly")
        val solid = sweptOrFail(path, SweepProfile.Round(5.0))
        assertManifold(solid.mesh, "the closed ring of tube")
        // a closed run has no caps: every station is an interior one
        assertTrue(Geom3.volume(solid.mesh) > 0.0, "and it still encloses material")
    }

    /**
     * **A closed path whose frame does not come back to itself is reported, not smeared.** A non-planar loop
     * generically carries a residual, and quietly absorbing it into the last band would put a twist in the
     * drawing that nothing in the drawing states.
     *
     * The refusal **names the cure**, and the cure is this feature's own parameter: state that twist and the
     * total comes back to zero. So the choice between "refusal" and "note" is settled by what the user can
     * do about it — a note the user cannot act on is a defect described rather than avoided, and a refusal
     * that names one number to type is a statement the drawing then *contains*. It heals, like every other
     * value condition (OP-3).
     */
    @Test
    fun aClosedPathWhoseFrameDoesNotCloseIsRefusedAndTheStatedTwistClosesIt() {
        // a loop that leaves its plane: the fourth point is lifted, so the transport picks up a real holonomy
        val path =
            smooth(
                Vec3(0.0, 0.0, 0.0),
                Vec3(80.0, 0.0, 0.0),
                Vec3(80.0, 60.0, 40.0),
                Vec3(0.0, 60.0, 0.0),
                closed = true,
            )
        val f = assertNotNull(Frames3.along(path, up, reach = 4.0).first)
        assertTrue(abs(f.seam) > 1e-3, "this loop really does carry a residual: ${f.seam} rad")

        val why = refusal(path, SweepProfile.Round(4.0))
        assertTrue(why.contains("does not come back to itself"), "the refusal says what is wrong: $why")
        assertTrue(why.contains("state a twist of"), "and names the cure: $why")

        // …and stating it closes the frame and builds the solid
        val cure = -f.seam
        val closed = sweptOrFail(path, SweepProfile.Round(4.0), twist = cure)
        assertManifold(closed.mesh, "the closed tube whose twist was stated")
        val after = assertNotNull(Frames3.along(path, up, twistRad = cure, reach = 4.0).first)
        assertClose(after.seam, 0.0, 1e-9, "and the seam is closed by construction, not by tolerance")
    }

    // ---- 8. an arbitrary profile ----

    /**
     * **A rectangular profile swept along a straight run has exactly the right volume** — a prism over its
     * own area, to the last bit, because nothing about a straight sweep of a polygon is approximated — and
     * the same profile round a bend is watertight.
     */
    @Test
    fun aRectangularProfileSweepsToAPrismOnAStraightRunAndClosesOnABentOne() {
        val profile = SweepProfile.Section(rectangle(20.0, 6.0))
        val straightRun = sweptOrFail(straight(Vec3.ZERO, Vec3(0.0, 150.0, 0.0)), profile)
        assertManifold(straightRun.mesh, "the swept bar")
        assertClose(Geom3.volume(straightRun.mesh), 20.0 * 6.0 * 150.0, 1e-6, "a rectangle carried straight is exactly a bar")

        val bent =
            sweptOrFail(
                smooth(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 60.0, 0.0), Vec3(200.0, 60.0, 50.0)),
                profile,
            )
        assertManifold(bent.mesh, "the swept bar round a bend")
        assertTrue(Geom3.volume(bent.mesh) > 0.0, "and it encloses material")
    }

    /** **A profile with a hole sweeps a pipe** — one code path, because a profile is an ordinary region. */
    @Test
    fun aProfileWithAHoleSweepsAPipe() {
        val outer = rectangle(20.0, 20.0)
        val hole = rectangle(10.0, 10.0)
        val pipe = Region(outer.outer, listOf(constructit.geom.Loop(hole.outer.elements.reversed().map { constructit.geom.GeomMath.reverse(it) })))
        val solid = sweptOrFail(straight(Vec3.ZERO, Vec3(0.0, 0.0, 100.0)), SweepProfile.Section(pipe))
        assertManifold(solid.mesh, "the swept pipe")
        assertClose(Geom3.volume(solid.mesh), (20.0 * 20.0 - 10.0 * 10.0) * 100.0, 1e-6, "the hole runs the whole way through")
    }

    // ---- the degenerate inputs, each refused by name ----

    @Test
    fun everyDegenerateInputIsRefusedByName() {
        val ok = straight(Vec3.ZERO, Vec3(50.0, 0.0, 0.0))
        assertTrue(refusal(ok, SweepProfile.Round(0.0)).contains("positive radius"), "a zero radius says so")
        assertTrue(
            refusal(Path3(emptyList()), SweepProfile.Round(3.0)).contains("no pieces"),
            "a path with no pieces says so",
        )
        assertTrue(
            refusal(straight(Vec3.ZERO, Vec3.ZERO), SweepProfile.Round(3.0)).contains("no length"),
            "a path with no length says so",
        )
        val open =
            Region(
                constructit.geom.Loop(
                    listOf(
                        constructit.geom.ProfileElement.Seg(constructit.geom.Segment(Vec2(0.0, 0.0), Vec2(10.0, 0.0))),
                        constructit.geom.ProfileElement.Seg(constructit.geom.Segment(Vec2(10.0, 0.0), Vec2(10.0, 10.0))),
                        constructit.geom.ProfileElement.Seg(constructit.geom.Segment(Vec2(10.0, 10.0), Vec2(3.0, 7.0))),
                    ),
                ),
                emptyList(),
            )
        assertTrue(refusal(ok, SweepProfile.Section(open)).contains("does not close"), "an open outline says so")
        val flat = regionOfPolygon(listOf(Vec2(0.0, 0.0), Vec2(10.0, 0.0), Vec2(20.0, 0.0)))
        assertTrue(refusal(ok, SweepProfile.Section(flat)).contains("no area"), "a profile with no area says so")
    }

    /** A path that folds back on itself has no section there, and the refusal says where. */
    @Test
    fun aPathThatDoublesBackIsRefusedByName() {
        val path = polyline(Vec3(0.0, 0.0, 0.0), Vec3(60.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0))
        val why = refusal(path, SweepProfile.Round(3.0))
        assertTrue(why.contains("doubles back"), "the refusal says what is wrong: $why")
        assertTrue(why.contains("mm along"), "and where: $why")
    }

    // ---- the start reference, and its degenerate fallback ----

    /**
     * **The start reference is derived by construction**: the space's own normal, projected perpendicular to
     * the first tangent — so a path drawn flat in its space starts with the section standing exactly up out
     * of it, and tilting the space rolls the sweep.
     */
    @Test
    fun theStartReferenceIsTheSpaceNormalProjectedPerpendicularToTheTangent() {
        assertVecClose(Frames3.startReference(Vec3.X, Vec3.Z), Vec3.Z, 1e-12, "a flat run keeps the space's normal")
        val tilted = Vec3(1.0, 0.0, 1.0).normalized()
        val r = Frames3.startReference(tilted, Vec3.Z)
        assertClose(r.dot(tilted), 0.0, 1e-12, "a climbing run's reference is still perpendicular to it")
        assertTrue(r.z > 0.0, "and still points the way the space's normal did")
    }

    /**
     * **…and when it degenerates it says what it does instead.** A run straight along the space's own normal
     * leaves nothing to project, and the fallback is the world axis *least* parallel to the tangent, taken in
     * the fixed order X, Y, Z — deterministic, so a reload gets the identical frame, and never degenerate in
     * turn, since the least parallel of three orthogonal axes is at most `1/sqrt(3)` along the tangent.
     */
    @Test
    fun aRunAlongTheSpaceNormalFallsBackToTheLeastParallelWorldAxis() {
        val r = Frames3.startReference(Vec3.Z, Vec3.Z)
        assertVecClose(r, Vec3.X, 1e-12, "X and Y are equally unparallel to +Z, and the tie is broken by the fixed order")
        assertClose(r.dot(Vec3.Z), 0.0, 1e-12, "and it is perpendicular to the tangent, which is all it has to be")
        // a tangent that leans towards X picks Y instead, which is what "least parallel" means
        val leaning = Vec3(0.6, 0.0, 0.8)
        assertVecClose(Frames3.startReference(leaning, leaning), Vec3.Y, 1e-12, "the least parallel axis is chosen, not the first")
    }

    // ---- the node: a sweep is a function of its inputs, and it heals ----

    /**
     * **The whole feature is one node over live inputs.** Retype the radius and the same node becomes
     * invalid, with the station named, and comes back the moment the number is sane — no rebuild, no second
     * construction, exactly what OP-3 promises.
     */
    @Test
    fun theSweepNodeRefusesAndHealsAsItsRadiusMoves() {
        val cx = Construction()
        val path = cx.hairpin()
        val radius = cx.parameter("radius", 2.0.mm)
        val tube = cx.tube(path, cx.planeXY(), radius, cx.const(0.0.deg), cx.const(0.0.deg))
        assertTrue(Evaluator().eval(tube.node) is EvalResult.Ok, "a section that fits is a solid")
        assertManifold(Evaluator().solid(tube).mesh, "the tube")

        cx.set(radius, 40.0.mm)
        val bad = Evaluator().eval(tube.node)
        assertTrue(bad is EvalResult.Invalid, "a section that does not fit round the bend is invalid: $bad")
        assertTrue((bad as EvalResult.Invalid).reason.contains("mm along the path"), "and the station is named: ${bad.reason}")

        cx.set(radius, 2.0.mm)
        assertTrue(Evaluator().eval(tube.node) is EvalResult.Ok, "and it heals")
    }

    /** **The path's own points drive the sweep**: move one and the solid follows, in one recompute. */
    @Test
    fun movingAPointThePathRunsThroughMovesTheSolid() {
        val cx = Construction()
        val a = cx.point3(0.0, 0.0, 0.0)
        val b = cx.point3(80.0, 0.0, 0.0)
        val end = cx.freePoint("end", 160.0.mm, 0.0.mm)
        val c = cx.heightPoint(cx.planeXY(), end, cx.const(0.0.mm))
        val path = cx.pathThrough(listOf(a, b, c))
        val tube = cx.tube(path, cx.planeXY(), cx.const(4.0.mm), cx.const(0.0.deg), cx.const(0.0.deg))
        val before = Evaluator().solid(tube).mesh
        assertManifold(before, "the tube")

        cx.set(end, 220.0.mm, 0.0.mm)
        val after = Evaluator().solid(tube).mesh
        assertManifold(after, "the tube after the drag")
        assertTrue(
            Geom3.volume(after) > Geom3.volume(before) * 1.3,
            "the run got longer because the point it runs through did: ${Geom3.volume(before)} -> ${Geom3.volume(after)}",
        )
    }

    /** **The mesh is a pure function of the parameters**: the identical inputs give the identical triangles. */
    @Test
    fun theSameInputsGiveTheIdenticalMeshEveryTime() {
        val path = smooth(Vec3(0.0, 0.0, 0.0), Vec3(70.0, 30.0, 10.0), Vec3(140.0, 0.0, 40.0))
        val a = sweptOrFail(path, SweepProfile.Round(5.0), roll = 0.3, twist = 0.7)
        val b = sweptOrFail(path, SweepProfile.Round(5.0), roll = 0.3, twist = 0.7)
        assertEquals(a.mesh.vertices, b.mesh.vertices, "the vertices are the same, in the same order")
        assertEquals(a.mesh.triangles, b.mesh.triangles, "and so are the triangles")
        assertEquals(a.feature, b.feature, "and the feature is the same value")
    }

    /** The feature keeps the parameters it was built from, so a tube stays analytically a tube (OP-9). */
    @Test
    fun theFeatureKeepsTheSweepsOwnParameters() {
        val solid = sweptOrFail(straight(Vec3.ZERO, Vec3(60.0, 0.0, 0.0)), SweepProfile.Round(7.5), twist = 0.25)
        val f = assertNotNull(solid.feature as? Feature3.Sweep, "the feature says what it is")
        assertEquals(SweepProfile.Round(7.5), f.profile, "with the radius kept as a number, not as chords")
        assertEquals(0.25, f.twist, "and the frame's own parameters")
        assertEquals(1, f.path.elements.size, "and the path it rode")
    }

    // ---- helpers over the construction ----

    private fun Construction.point3(
        x: Double,
        y: Double,
        z: Double,
    ): Point3Ref = heightPoint(planeXY(), freePoint("p", x.mm, y.mm), const(z.mm))

    /** A tight interpolated hairpin, as a graph node — the fixture the radius refusal is asked of. */
    private fun Construction.hairpin(): Path3Ref =
        pathThrough(
            listOf(point3(0.0, 0.0, 0.0), point3(20.0, 14.0, 0.0), point3(40.0, 0.0, 0.0)),
            smooth = true,
        )

    private fun assertVecClose(
        actual: Vec3,
        expected: Vec3,
        tol: Double,
        msg: String,
    ) {
        assertTrue((actual - expected).length() <= tol, "$msg (was $actual, wanted $expected)")
    }
}
