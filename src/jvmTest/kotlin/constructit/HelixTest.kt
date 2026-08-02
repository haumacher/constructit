package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.Path3Ref
import constructit.dsl.ScalarRef
import constructit.dsl.path3
import constructit.dsl.solid
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.Handedness
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.SweepProfile
import constructit.geom.Vec3
import constructit.geom.Xform3
import constructit.geom.movedBy
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The helix** (OP-26, step 3) at the level of the geometry: the first constructed curve in this kernel
 * that lies in **no** plane, and therefore the first honest test of step 2's moving frame.
 *
 * Two things are asserted here that nothing before could be. That the curve *is* the closed form it claims —
 * every point at its exact analytic place, for both handednesses, for a fractional turn count and about an
 * axis that lines up with nothing; and that its **curvature is the constant `r/(r² + b²)`**, checked against
 * a number differentiated out of the sampled points rather than against the formula that produced it. The
 * second is what the sweep's self-intersection refusal rests on, so it is checked against the geometry.
 *
 * Every solid built here goes through [assertManifold]: watertight or refused (OP-9).
 */
class HelixTest {
    // ---- the fixtures ----

    private fun helix(
        radius: Double,
        pitch: Double,
        turns: Double,
        hand: Handedness = Handedness.RIGHT,
        origin: Vec3 = Vec3.ZERO,
        axis: Vec3 = Vec3.Z,
        phase: Vec3 = Vec3.X,
    ): Curve3Element.Helix3 = Curve3Element.Helix3.about(origin, axis, phase, radius, pitch, turns, hand)

    private fun pathOf(h: Curve3Element.Helix3) = Path3(listOf(h))

    /** The world XY plane's normal, and — for the helices below — their own axis. */
    private val up = Vec3.Z

    private fun sweptOrFail(
        path: Path3,
        profile: SweepProfile,
        up: Vec3 = this.up,
    ): constructit.geom.Solid3 {
        val (solid, why) = Geom3.sweep(path, up, profile)
        return assertNotNull(solid, "the sweep was refused: $why")
    }

    private fun refusal(
        path: Path3,
        profile: SweepProfile,
        up: Vec3 = this.up,
    ): String {
        val (solid, why) = Geom3.sweep(path, up, profile)
        assertNull(solid, "this sweep was expected to be refused")
        return assertNotNull(why, "a refusal says why")
    }

    // ---- 1. the curve is the closed form it claims to be ----

    /**
     * **Every point lands where the analytic helix says it does** — the radius from the axis is exactly the
     * stated one, the rise is exactly proportional to the angle turned, and the angle is exactly the stated
     * number of turns times the parameter.
     *
     * Asked of four helices at once, because each says something the others cannot: a **right**-hand and a
     * **left**-hand coil (chirality is the discrete input, so both branches of it are exercised), a
     * **fractional** turn count (2.25 — a coil does not have to close), and an axis that lines up with **no**
     * world direction, which is the case where every coordinate of every point is a genuine combination.
     */
    @Test
    fun everyPointOfAHelixStandsAtItsExactAnalyticPlace() {
        val cases =
            listOf(
                helix(20.0, 12.0, 3.0),
                helix(20.0, 12.0, 3.0, Handedness.LEFT),
                helix(7.5, 4.0, 2.25, Handedness.RIGHT, origin = Vec3(11.0, -3.0, 5.0)),
                helix(
                    9.0,
                    6.0,
                    1.75,
                    Handedness.LEFT,
                    origin = Vec3(-4.0, 8.0, 2.0),
                    axis = Vec3(1.0, 2.0, 3.0),
                    phase = Vec3(1.0, 0.0, 0.0),
                ),
            )
        for (h in cases) {
            assertClose(h.axis.length(), 1.0, 1e-15, "the factory left the axis a unit vector")
            assertClose(h.u.length(), 1.0, 1e-15, "and the phase a unit vector")
            assertClose(h.axis.dot(h.u), 0.0, 1e-15, "perpendicular to the axis")
            for (i in 0..40) {
                val t = i / 40.0
                val p = h.at(t)
                val d = p - h.origin
                val alongAxis = d.dot(h.axis)
                val radial = d - h.axis * alongAxis
                assertClose(radial.length(), h.radius, 1e-12, "the point at t=$t stands its own radius off the axis")
                assertClose(alongAxis, h.pitch * h.turns * t, 1e-12, "and rises the pitch times the turns times t")
                val theta = atan2(radial.dot(h.bi), radial.dot(h.u))
                val wanted = Frames3.wrapAngle(h.hand.turnSign * 2.0 * PI * h.turns * t)
                assertClose(Frames3.wrapAngle(theta - wanted), 0.0, 1e-12, "and has turned exactly as far as it should")
            }
            assertVecClose(h.start, h.origin + h.u * h.radius, 1e-12, "it starts beside the axis point, along the phase")
            assertVecClose(h.end, h.origin + h.axis * h.rise + h.u * (h.radius * cos(h.sweepAngle)) + h.bi * (h.radius * sin(h.sweepAngle)), 1e-12, "and ends a full rise up")
        }
    }

    /**
     * **It lies in no plane, and that is asserted rather than asserted-about.** Four points of the curve are
     * shown to be non-coplanar by their own volume, which is the property that makes this the first curve
     * step 2's frame is honestly tested by — every path a `Path3` could hold until now was either flat or
     * flat by accident.
     */
    @Test
    fun aHelixLiesInNoPlaneAtAll() {
        val h = helix(20.0, 12.0, 1.0)
        val a = h.at(0.0)
        val volume = abs((h.at(0.2) - a).cross(h.at(0.5) - a).dot(h.at(0.9) - a)) / 6.0
        assertTrue(volume > 100.0, "four of its points bound a real tetrahedron ($volume mm³), so no plane holds them")
        // …and its plan projection is *not* a closed circle either: the ends differ by the rise
        val plan = Curves3.projectedOnto(pathOf(h), Plane3(Vec3.ZERO, Vec3.X, Vec3.Z))
        assertTrue(plan.all { it is ProfileElement.Seg }, "a trochoid has no word in the 2D vocabulary, so it is drawn as chords")
        assertEquals(Curves3.drawSteps(h), plan.size, "one chord per drawing step, so the plan and the 3D view sample the same points")
    }

    /**
     * **Handedness is chirality, and chirality is what a negative pitch would say twice.**
     *
     * The discrete quantity is the sign of the **torsion**, read off four consecutive points as
     * `(a × b) · c` over their chords. It is *traversal-invariant* — reversing the curve leaves it alone —
     * which is exactly what makes it handedness rather than a direction of travel, and it is why the node
     * refuses a negative pitch by name: a right-hand coil that descends has the torsion of a **left**-hand
     * one, so allowing it would give the file two ways to state one shape.
     */
    @Test
    fun handednessIsTheTorsionSignAndANegativePitchWouldSayItTwice() {
        fun torsionSign(h: Curve3Element.Helix3): Double {
            val p = (0..3).map { h.at(0.4 + it * 0.01) }
            return (p[1] - p[0]).cross(p[2] - p[1]).dot(p[3] - p[2])
        }
        assertTrue(torsionSign(helix(20.0, 12.0, 2.0, Handedness.RIGHT)) > 0.0, "a right-hand coil has positive torsion")
        assertTrue(torsionSign(helix(20.0, 12.0, 2.0, Handedness.LEFT)) < 0.0, "and a left-hand one negative")

        // traced backwards it is the same coil: chirality does not depend on which way you walk it
        val right = helix(20.0, 12.0, 2.0, Handedness.RIGHT)
        val backwards = (0..3).map { right.at(0.6 - it * 0.01) }
        assertTrue(
            (backwards[1] - backwards[0]).cross(backwards[2] - backwards[1]).dot(backwards[3] - backwards[2]) > 0.0,
            "the same coil walked backwards is still right-handed",
        )
        // …and the shape a negative pitch would make is the *other* handedness, which is why it is refused
        val descending = Curve3Element.Helix3(Vec3.ZERO, Vec3.Z, Vec3.X, 20.0, -12.0, 2.0, Handedness.RIGHT)
        assertTrue(torsionSign(descending) < 0.0, "a right-hand coil with a negative pitch is a left-hand coil")
    }

    /** Its **arc length is exact** — a multiplication, not the numeric integral a cubic's length is. */
    @Test
    fun theArcLengthOfAHelixIsClosedFormAndTheSampledSpineConvergesToIt() {
        val h = helix(20.0, 12.0, 3.0)
        val b = 12.0 / (2.0 * PI)
        assertClose(h.arcLength, 2.0 * PI * 3.0 * sqrt(400.0 + b * b), 1e-9, "|Δθ|·sqrt(r² + b²), written out")

        var len = 0.0
        val n = 200_000
        var prev = h.at(0.0)
        for (i in 1..n) {
            val p = h.at(i.toDouble() / n)
            len += (p - prev).length()
            prev = p
        }
        assertClose(len, h.arcLength, 1e-6, "and the curve really is that long")
    }

    // ---- 2. the curvature, checked against the geometry rather than against itself ----

    /**
     * **`κ = r / (r² + b²)`, asserted against a number differentiated out of the sampled points.**
     *
     * The estimate is the **Menger curvature** of three close samples — the reciprocal radius of the circle
     * through them, `4·area / (|ab|·|bc|·|ca|)` — which reads nothing but positions. So this compares the
     * closed form against the geometry, which is the point: the sweep's self-intersection refusal fires on
     * `1/κ`, and a wrong constant there would be a refusal at the wrong radius with no other symptom.
     *
     * Checked at several parameters, because the second claim is that it is **constant** — the property that
     * makes a helix the first piece whose radius of curvature is a fact rather than a sample.
     */
    @Test
    fun theAnalyticCurvatureIsTheCurvatureOfTheSampledCurve() {
        for (h in listOf(helix(20.0, 12.0, 3.0), helix(6.0, 40.0, 1.5, Handedness.LEFT), helix(50.0, 2.0, 0.75))) {
            val b = h.pitch / (2.0 * PI)
            assertClose(h.curvature, h.radius / (h.radius * h.radius + b * b), 1e-15, "the closed form, written out")
            for (t in listOf(0.05, 0.25, 0.5, 0.75, 0.95)) {
                // the estimate converges as the square of the step, so a small one keeps the reading a
                // statement about the curve rather than about the sampling
                val step = 1e-4
                val p0 = h.at(t - step)
                val p1 = h.at(t)
                val p2 = h.at(t + step)
                val a = (p1 - p0).length()
                val c = (p2 - p1).length()
                val e = (p2 - p0).length()
                val area = (p1 - p0).cross(p2 - p0).length() / 2.0
                val menger = 4.0 * area / (a * c * e)
                assertClose(
                    menger,
                    h.curvature,
                    1e-6 * h.curvature + 1e-12,
                    "the circle through three samples at t=$t has the curvature the closed form states",
                )
            }
        }
    }

    // ---- 3. a tube along it is a spring ----

    /**
     * **A tube swept along a helix is a watertight spring** — the claim OP-26 put the helix at step 3 for.
     *
     * Three assertions, and the middle one is the one a planar path could never make. It is
     * [assertManifold] — the shell closes, with no crack anywhere along three turns of a curve that leaves
     * every plane. Its **volume is exactly the tessellated section's area times the spine's own length**,
     * which is Pappus and is exact for this mesh: every band is a prism cut by two mitre planes and the
     * profile's centroid sits on the path, so the two mitres cancel. And it is within a per-cent of
     * `π·r²·L` for the *analytic* arc length, which is the honest tessellation gap and nothing else.
     *
     * Finally the frame's own defining property is asserted station by station along it
     * ([assertTransportNeverFlips]): the reference turns no faster than the tangent. That claim was made in
     * step 2 against paths that all lay in a plane, where a planar path's reference is the rotation axis at
     * every step and the transport is very nearly free. A helix has a real, constant torsion, so the
     * transport actually does something at every one of its two hundred stations.
     */
    @Test
    fun aTubeAlongAHelixIsAWatertightSpring() {
        val h = helix(20.0, 12.0, 3.0)
        val path = pathOf(h)
        assertTransportNeverFlips(path, up, 3.0)

        val profile = SweepProfile.Round(3.0)
        val solid = sweptOrFail(path, profile)
        assertManifold(solid.mesh, "the spring")

        val volume = Geom3.volume(solid.mesh)
        assertTrue(volume > 0.0, "and it encloses material")

        val (frame, why) = Frames3.along(path, up, reach = 3.0)
        val f = assertNotNull(frame, why)
        val (tess, noTess) = Geom3.tessellateRegion(profile.region)
        val area = Geom3.tessArea(assertNotNull(tess, noTess))
        assertClose(volume, area * f.length, 1e-6 * volume, "Pappus, exactly: the section's area along the spine it rides")
        assertClose(volume, PI * 9.0 * h.arcLength, 0.015 * volume, "and within the tessellation's own gap of π·r²·L")

        // it is a *spring*, not a torus: it climbs by the pitch every turn
        val zs = solid.mesh.vertices.map { it.z }
        assertClose(zs.min(), -3.0, 0.02, "its lowest point is one tube radius below the first ring")
        assertClose(zs.max(), 36.0 + 3.0, 0.02, "and its highest one tube radius above the last, three pitches up")
    }

    /**
     * **A left-hand spring is the mirror image of the right-hand one, and the sweep does not care.** The two
     * shells have the identical volume and the identical triangle count — the handedness is in the geometry
     * and in nothing the mesher does.
     */
    @Test
    fun aLeftHandSpringIsTheSameShellTheOtherWayRound() {
        val right = sweptOrFail(pathOf(helix(20.0, 12.0, 3.0)), SweepProfile.Round(3.0))
        val left = sweptOrFail(pathOf(helix(20.0, 12.0, 3.0, Handedness.LEFT)), SweepProfile.Round(3.0))
        assertManifold(left.mesh, "the left-hand spring")
        assertEquals(right.mesh.triangles.size, left.mesh.triangles.size, "the same shell, the other way round")
        assertClose(Geom3.volume(right.mesh), Geom3.volume(left.mesh), 1e-6, "and the same amount of wire")
    }

    /**
     * **A spring about a tilted axis is still a spring**, and this is where the start reference earns its
     * keep: the frame is started from the space's normal projected perpendicular to the first tangent, and on
     * a helix that first tangent is neither along the axis nor perpendicular to it.
     */
    @Test
    fun aSpringAboutAnAxisAlignedWithNothingIsStillWatertight() {
        val axis = Vec3(1.0, 2.0, 3.0).normalized()
        val h = helix(15.0, 9.0, 2.5, Handedness.LEFT, origin = Vec3(30.0, -12.0, 4.0), axis = axis, phase = Vec3.Y)
        val path = pathOf(h)
        assertTransportNeverFlips(path, axis, 2.5)
        val solid = sweptOrFail(path, SweepProfile.Round(2.5), up = axis)
        assertManifold(solid.mesh, "the tilted spring")
        val (frame, why) = Frames3.along(path, axis, reach = 2.5)
        val f = assertNotNull(frame, why)
        val (tess, noTess) = Geom3.tessellateRegion(SweepProfile.Round(2.5).region)
        assertClose(
            Geom3.volume(solid.mesh),
            Geom3.tessArea(assertNotNull(tess, noTess)) * f.length,
            1e-6 * Geom3.volume(solid.mesh),
            "the same Pappus reading, about an axis that lines up with nothing",
        )
    }

    /**
     * **A square section swept along a helix comes out watertight too** — the tube is what proves the frame,
     * but a section with corners is what proves the frame is not being hidden by a circle's symmetry.
     */
    @Test
    fun anAsymmetricSectionAlongAHelixIsWatertightAndCarriesItsOwnOrientation() {
        val h = helix(24.0, 10.0, 2.0)
        val region = rectangle(6.0, 3.0)
        val solid = sweptOrFail(pathOf(h), SweepProfile.Section(region))
        assertManifold(solid.mesh, "the coiled bar")
        val (frame, why) = Frames3.along(pathOf(h), up, reach = sqrt(9.0 + 2.25))
        val f = assertNotNull(frame, why)
        val (tess, noTess) = Geom3.tessellateRegion(region)
        assertClose(
            Geom3.volume(solid.mesh),
            Geom3.tessArea(assertNotNull(tess, noTess)) * f.length,
            1e-6 * Geom3.volume(solid.mesh),
            "a rectangular wire, coiled",
        )
    }

    // ---- 5. the self-intersection refusal, at the radius the closed form states ----

    /**
     * **The sweep's refusal fires at exactly the radius of curvature the helix states**, and heals when the
     * profile shrinks.
     *
     * This is the closed form paying out. On a coil whose radius is large against its pitch the radius of
     * curvature is `(r² + b²)/r` — a hair *more* than `r`, because the rise straightens the bend slightly —
     * and the criterion is `reach ≥ 1/κ`. So a tube just under that number is a solid and one just over it is
     * a refusal that names the station, with nothing between them but the arithmetic.
     */
    @Test
    fun aTubeFatterThanTheCoilsOwnBendIsRefusedAtTheStatedRadiusAndHeals() {
        val h = helix(10.0, 8.0, 2.0)
        val rho = 1.0 / h.curvature
        assertClose(rho, (100.0 + (8.0 / (2.0 * PI)) * (8.0 / (2.0 * PI))) / 10.0, 1e-12, "the coil's own radius of curvature")
        assertTrue(rho > 10.0, "the rise straightens the bend a little: $rho mm against a 10 mm coil")

        // just inside it: a solid
        val ok = sweptOrFail(pathOf(h), SweepProfile.Round(rho - 0.01))
        assertTrue(Geom3.volume(ok.mesh) > 0.0, "a section that fits round the bend is a solid")

        // just outside it: refused, by station
        val why = refusal(pathOf(h), SweepProfile.Round(rho + 0.01))
        assertTrue(why.contains("mm along the path"), "the refusal names the station: $why")
        assertTrue(why.contains(Frames3.mm(rho)), "and states the bend it measured against: $why")

        // …and it heals as soon as the profile shrinks
        val healed = sweptOrFail(pathOf(h), SweepProfile.Round(3.0))
        assertManifold(healed.mesh, "the healed coil")
    }

    // ---- 4. the degenerate cases, and 7. the parameters ----

    /**
     * **The four degenerate helices are refused by name, and each refusal names the other way of saying the
     * same thing** — which is the reason each is refused rather than normalized: a stored model with two
     * spellings of one shape is a stored model without a normal form.
     *
     * All four are conditions on **values**, so they are node invalidity (OP-3) and they heal: retype the
     * number and the curve comes back, with everything swept along it.
     */
    @Test
    fun theDegenerateHelicesAreRefusedByNameAndHeal() {
        val cx = Construction()
        val radius = cx.parameter("r", 20.0.mm)
        val pitch = cx.parameter("p", 12.0.mm)
        val turns = cx.parameter("n", Quantity.number(3.0))
        val h = cx.helix(cx.planeXY(), cx.heightPoint(cx.planeXY(), cx.freePoint("c", 0.0.mm, 0.0.mm), cx.const(0.0.mm)), radius, pitch, turns, Handedness.RIGHT)

        assertTrue(Evaluator().eval(h.node) is EvalResult.Ok, "the coil as stated is a curve")

        assertTrue(why(cx, h, pitch, 0.0.mm).contains("circle"), "no pitch is a circle: ${why(cx, h, pitch, 0.0.mm)}")
        assertTrue(why(cx, h, pitch, (-12.0).mm).contains("left-hand"), "a negative pitch is the other handedness")
        cx.set(pitch, 12.0.mm)
        assertTrue(Evaluator().eval(h.node) is EvalResult.Ok, "and it heals when the pitch comes back")

        assertTrue(why(cx, h, turns, Quantity.number(0.0)).contains("positive number of turns"), "no turns is a point")
        assertTrue(why(cx, h, turns, Quantity.number(-3.0)).contains("other side of its axis point"), "a negative count is the axis turned round")
        cx.set(turns, Quantity.number(3.0))

        assertTrue(why(cx, h, radius, 0.0.mm).contains("straight line"), "no radius is a straight line")
        cx.set(radius, 20.0.mm)
        assertTrue(Evaluator().eval(h.node) is EvalResult.Ok, "and every one of them heals")
    }

    /**
     * **The three numbers are ordinary parameters, and everything downstream follows them** (OP-7/OP-21):
     * retype the radius, the pitch or the turn count and the curve is a different curve — and the spring
     * swept along it is a different spring, in one recompute, with no rebuild anywhere.
     */
    @Test
    fun retypingTheRadiusThePitchOrTheTurnsMovesTheCurveAndEverythingSweptAlongIt() {
        val cx = Construction()
        val radius = cx.parameter("r", 20.0.mm)
        val pitch = cx.parameter("p", 12.0.mm)
        val turns = cx.parameter("n", Quantity.number(3.0))
        val path = cx.helix(cx.planeXY(), cx.heightPoint(cx.planeXY(), cx.freePoint("c", 0.0.mm, 0.0.mm), cx.const(0.0.mm)), radius, pitch, turns, Handedness.RIGHT)
        val spring = cx.tube(path, cx.planeXY(), cx.const(3.0.mm), cx.const(Quantity.deg(0.0)), cx.const(Quantity.deg(0.0)))

        fun helixNow(): Curve3Element.Helix3 = Evaluator().path3(path).elements.single() as Curve3Element.Helix3

        fun volumeNow(): Double = Geom3.volume(Evaluator().solid(spring).mesh)

        val v0 = volumeNow()
        assertClose(helixNow().radius, 20.0, 1e-12)
        assertManifold(Evaluator().solid(spring).mesh, "the spring")

        cx.set(radius, 30.0.mm)
        assertClose(helixNow().radius, 30.0, 1e-12, "the radius is the parameter")
        assertTrue(volumeNow() > v0 * 1.4, "a wider coil is more wire: ${volumeNow()} against $v0")

        cx.set(radius, 20.0.mm)
        cx.set(turns, Quantity.number(6.0))
        assertClose(helixNow().turns, 6.0, 1e-12, "and so is the turn count")
        assertClose(volumeNow(), v0 * 2.0, 0.01 * v0, "twice the turns is twice the wire")

        cx.set(turns, Quantity.number(3.0))
        cx.set(pitch, 40.0.mm)
        assertClose(helixNow().pitch, 40.0, 1e-12, "and the pitch")
        assertTrue(volumeNow() > v0, "a steeper coil is a longer run: ${volumeNow()} against $v0")
        assertManifold(Evaluator().solid(spring).mesh, "and every one of them is still watertight")
    }

    /** **Two helices sharing one pitch node is what "same pitch" means here** — no constraint, one input. */
    @Test
    fun twoCoilsSharingOnePitchNodeMoveTogether() {
        val cx = Construction()
        val pitch = cx.parameter("pitch", 10.0.mm)
        val a = coil(cx, 0.0, pitch)
        val b = coil(cx, 60.0, pitch)
        assertClose(helixOf(a).pitch, helixOf(b).pitch, 1e-15, "one node, two coils")
        cx.set(pitch, 18.0.mm)
        assertClose(helixOf(a).pitch, 18.0, 1e-12, "retyped once…")
        assertClose(helixOf(b).pitch, 18.0, 1e-12, "…and both followed")
    }

    // ---- a helix moved is a helix ----

    /**
     * **A placement moves a helix's frame and touches none of its four numbers.** Only rigid maps ever reach
     * here ([Xform3]), and a rigid map preserves lengths and handedness — so a placed spring is the same
     * spring somewhere else, exactly as a placed extrusion is the same extrusion.
     */
    @Test
    fun aHelixMovedByAPlacementIsTheSameHelixSomewhereElse() {
        val h = helix(20.0, 12.0, 2.5, Handedness.LEFT, origin = Vec3(3.0, 4.0, 5.0))
        val angle = 0.7
        val x =
            Xform3(
                doubleArrayOf(
                    cos(angle), -sin(angle), 0.0,
                    sin(angle), cos(angle), 0.0,
                    0.0, 0.0, 1.0,
                    10.0, -20.0, 30.0,
                ),
            )
        assertTrue(x.isRigid(), "the placement is rigid, which is the only kind a solid moves by")
        val moved = assertNotNull(pathOf(h).movedBy(x).elements.single() as? Curve3Element.Helix3, "a helix stays a helix")
        assertEquals(h.radius, moved.radius, "the radius is untouched")
        assertEquals(h.pitch, moved.pitch, "and the pitch")
        assertEquals(h.turns, moved.turns, "and the turn count")
        assertEquals(h.hand, moved.hand, "and the handedness, because a rigid map does not mirror")
        for (i in 0..20) {
            val t = i / 20.0
            assertVecClose(moved.at(t), x.apply(h.at(t)), 1e-9, "and every point is its own image at t=$t")
        }
    }

    // ---- helpers ----

    private fun coil(
        cx: Construction,
        x: Double,
        pitch: ScalarRef,
    ): Path3Ref =
        cx.helix(
            cx.planeXY(),
            cx.heightPoint(cx.planeXY(), cx.freePoint("c", x.mm, 0.0.mm), cx.const(0.0.mm)),
            cx.const(8.0.mm),
            pitch,
            cx.const(Quantity.number(2.0)),
            Handedness.RIGHT,
        )

    private fun helixOf(p: Path3Ref): Curve3Element.Helix3 = Evaluator().path3(p).elements.single() as Curve3Element.Helix3

    /** [ref] set to [q], and the reason the helix [h] is then invalid — the refusal, in the user's words. */
    private fun why(
        cx: Construction,
        h: Path3Ref,
        ref: ScalarRef,
        q: Quantity,
    ): String {
        cx.set(ref, q)
        val r = Evaluator().eval(h.node)
        assertTrue(r is EvalResult.Invalid, "expected the helix to be invalid, got $r")
        return (r as EvalResult.Invalid).reason
    }

    private fun rectangle(
        w: Double,
        h: Double,
    ): constructit.geom.Region {
        val pts =
            listOf(
                constructit.geom.Vec2(-w / 2, -h / 2),
                constructit.geom.Vec2(w / 2, -h / 2),
                constructit.geom.Vec2(w / 2, h / 2),
                constructit.geom.Vec2(-w / 2, h / 2),
            )
        return constructit.geom.Region(
            constructit.geom.Loop(
                pts.indices.map {
                    ProfileElement.Seg(constructit.geom.Segment(pts[it], pts[(it + 1) % pts.size]))
                },
            ),
            emptyList(),
        )
    }

    private fun assertVecClose(
        actual: Vec3,
        expected: Vec3,
        tol: Double,
        msg: String,
    ) {
        assertTrue((actual - expected).length() <= tol, "$msg (was $actual, wanted $expected)")
    }
}
