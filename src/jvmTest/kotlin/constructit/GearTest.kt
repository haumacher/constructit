package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.FLANK_SAMPLES
import constructit.dsl.ScalarRef
import constructit.dsl.SpurGear
import constructit.dsl.SpurGearArgs
import constructit.dsl.instance
import constructit.dsl.loop
import constructit.dsl.region
import constructit.dsl.resultOf
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.dsl.spurGear
import constructit.geom.Arc
import constructit.geom.Axis3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import constructit.svg.Drawable
import constructit.svg.Svg
import constructit.units.Dimension
import constructit.units.deg
import constructit.units.mm
import constructit.units.rad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Showcase 1 — the mechanical engineer's part: a parametric spur gear, by construction.**
 *
 * The spec this file works through: *"give me a gear by module and tooth count, with a bore, and let me
 * print it"*. Every step is an ordinary node of the same DAG the rest of the engine uses — there is no gear
 * code outside [spurGear], which is a macro over the existing algebra (OP-6), and none at all in the engine.
 *
 * The four things it is meant to prove, in the order they are asserted below:
 *
 * 1. **A sampled curve is a first-class curve** (OP-15). The tooth flank is the involute of the base
 *    circle, sampled into 12 chords at fixed parameter values. It is an approximation and says so — the
 *    load-bearing property is not closed form but **determinism** — and
 *    [theSampledFlankIsExactlyOnTheInvolute] pins both halves: every sample point lies on the exact
 *    involute to 1e-9, and the chords between them stay within 0.005 mm of it.
 * 2. **Sharing a node is equality.** Every tooth is the same tooth: the flank radii and angles are *one*
 *    set of nodes, read by all z teeth through a rotation offset. Nothing asserts that the teeth are
 *    identical, because nothing could make them differ.
 * 3. **The count is structural, the rest is not.** `teeth` decides how many nodes exist, so it is a macro
 *    argument (the array rule); module, pressure angle and bore are scalars, and editing them recomputes
 *    through the existing graph — [editingTheModuleRecomputesWithoutRebuildingTheGear] asserts that the
 *    graph does not grow by a single node.
 * 4. **It is a real part.** It extrudes to a watertight solid whose tip and root diameters are the standard
 *    ones exactly, and two copies of it mesh at centre distance m·z with the standard clearance.
 *
 * The gear used throughout is the textbook one: module 2 mm, 20 teeth, 20° pressure angle, 6 mm bore radius
 * — pitch Ø 40, tip Ø 44, root Ø 35, base Ø 37.588.
 */
class GearTest {
    private val alpha = 20.0 * PI / 180.0
    private val invAlpha = tan(alpha) - alpha

    private class Gear(val c: Construction, val g: SpurGear, val module: ScalarRef, val bore: ScalarRef)

    private fun gear(
        m: Double = 2.0,
        z: Int = 20,
        alphaDeg: Double = 20.0,
        boreR: Double = 6.0,
        tag: String = "g",
        c: Construction = Construction(),
        cx: Double = 0.0,
    ): Gear {
        val module = c.parameter("module", m.mm)
        val bore = c.parameter("boreR", boreR.mm)
        val g =
            c.instance(
                spurGear,
                tag,
                SpurGearArgs(c.freePoint("$tag.centre", cx.mm, 0.mm), module, z, c.parameter("alpha", alphaDeg.deg), bore),
            )
        return Gear(c, g, module, bore)
    }

    /** The distinct corners of the toothed boundary, relative to [centre]. */
    private fun corners(
        ev: Evaluator,
        g: SpurGear,
        centre: Vec2 = Vec2(0.0, 0.0),
    ): List<Vec2> =
        ev.loop(g.outer).elements.mapNotNull { (it as? ProfileElement.Seg)?.segment }
            .flatMap { listOf(it.a, it.b) }
            .distinct()
            .map { it - centre }

    private fun arcs(
        ev: Evaluator,
        g: SpurGear,
    ): List<Arc> = ev.loop(g.outer).elements.mapNotNull { (it as? ProfileElement.ArcE)?.arc }

    private fun arcsAt(
        ev: Evaluator,
        g: SpurGear,
        radius: Double,
    ): List<Arc> = arcs(ev, g).filter { abs(it.radius - radius) < 1e-9 }

    // ---- 1. the solid ----

    /**
     * The blank: an outer boundary of z teeth plus the bore as a hole loop, extruded into a printable gear.
     *
     * The volume is asserted the way every prism's is in this suite — exactly against the polygons the mesh
     * is actually made of (a prism's volume *is* its cap area times its depth) — and then bracketed between
     * the root cylinder and the tip cylinder, which is the honest statement about a toothed area: more than
     * the root circle, less than the tip circle, and no closed form in between.
     */
    @Test
    fun aTwentyToothGearExtrudesToAWatertightBlank() {
        val gear = gear()
        val c = gear.c
        val depth = c.parameter("depth", 8.mm)
        val solid = c.extrude(c.sketchOn(c.planeXY(), gear.g.region), depth)
        val vol = c.measureVolume(solid)

        val ev = Evaluator()
        assertManifold(ev.solid(solid).mesh, "spur gear m2 z20")
        assertEquals(Dimension.VOLUME, ev.scalar(vol).dim)

        val tess = Geom3.tessellateRegion(ev.region(gear.g.region)).first!!
        assertClose(ev.scalar(vol).base, Geom3.tessArea(tess) * 8.0, tol = 1e-6, msg = "prism volume = cap area x depth")

        val boreArea = PI * 36.0
        assertTrue(ev.scalar(vol).base > (PI * 17.5 * 17.5 - boreArea) * 8.0, "a gear holds more than its root cylinder")
        assertTrue(ev.scalar(vol).base < (PI * 22.0 * 22.0 - boreArea) * 8.0, "...and less than its tip cylinder")

        // the tooth count is visible in the geometry: z tip arcs, z root lands, and nothing else round
        assertEquals(20, arcsAt(ev, gear.g, 22.0).size, "one tip arc per tooth")
        assertEquals(20, arcsAt(ev, gear.g, 17.5).size, "one root land per tooth")
        assertEquals(40, arcs(ev, gear.g).size, "and no other arcs")
        assertEquals(1, ev.region(gear.g.region).holes.size, "the bore is a hole loop, not a boolean")

        // the bore is exact: a circle reaches the *region* untessellated, so its radius is the number typed
        val hole = ev.region(gear.g.region).holes.single().elements.single() as ProfileElement.CircleE
        assertClose(hole.circle.radius, 6.0, tol = 1e-12, msg = "the bore is exactly the radius asked for")
    }

    /** The mesh is a pure function of the parameters — two passes, the identical triangle soup. */
    @Test
    fun theGearMeshIsDeterministic() {
        val gear = gear()
        val solid = gear.c.extrude(gear.c.sketchOn(gear.c.planeXY(), gear.g.region), gear.c.parameter("depth", 8.mm))
        assertEquals(Evaluator().solid(solid).mesh, Evaluator().solid(solid).mesh)
    }

    // ---- 2. the sampled involute, stated honestly ----

    /**
     * The sampling scheme, pinned from both sides.
     *
     * **The points are exact.** Every corner of the boundary at or above the base circle satisfies the
     * involute relation `θ = ±(inv β − ψ) + k·2π/z` with `β = acos(rb/r)`, `inv β = tan β − β` and
     * `ψ = inv α + π/(2z)`, to 1e-9 rad. That ψ *is* the zero-backlash condition: at the pitch circle
     * `β = α`, so the flank sits at exactly −π/(2z) from the tooth's own axis — half the circular pitch,
     * which is what makes a tooth's thickness at the pitch circle equal the space's width.
     *
     * **The chords are approximate, by a stated amount.** Between two samples the boundary is a straight
     * line, which departs from the exact involute by at most ~0.005 mm here: a quarter of
     * `GeomMath.TESS_TOL_MM`, hence below the tessellation the mesh applies anyway. Determinism, not closed
     * form, is what the paradigm needs (OP-15).
     */
    @Test
    fun theSampledFlankIsExactlyOnTheInvolute() {
        val gear = gear()
        val ev = Evaluator()
        val rb = ev.scalar(gear.g.baseRadius).mm
        val ra = ev.scalar(gear.g.tipRadius).mm
        assertClose(rb, 20.0 * cos(alpha), tol = 1e-12)
        val psi = invAlpha + PI / 40.0

        var onFlank = 0
        for (p in corners(ev, gear.g)) {
            val r = p.length()
            if (r < rb - 1e-9) continue
            val beta = acos((rb / r).coerceIn(-1.0, 1.0))
            val expected = tan(beta) - beta - psi
            // which tooth, and which of the two mirrored flanks: a discrete choice, so try them all
            val err =
                (0 until 20).flatMap { k ->
                    listOf(1.0, -1.0).map { s -> abs(wrap(p.angle() - (s * expected + 2 * PI * k / 20))) }
                }.min()
            assertTrue(err < 1e-9, "a boundary corner at r = $r mm is $err rad off the involute")
            onFlank++
        }
        assertEquals(20 * 2 * (FLANK_SAMPLES + 1), onFlank, "every flank sample of every tooth, and nothing else")

        // ...and the chords: the exact involute between two samples stays within the stated tolerance
        val betaHi = acos(rb / ra)
        var worst = 0.0
        for (i in 0 until FLANK_SAMPLES) {
            val b0 = betaHi * i / FLANK_SAMPLES
            val b1 = betaHi * (i + 1) / FLANK_SAMPLES
            val a = involute(rb, b0, psi)
            val b = involute(rb, b1, psi)
            val len = (b - a).length()
            for (s in 1 until 100) {
                val q = involute(rb, b0 + (b1 - b0) * s / 100.0, psi)
                worst = maxOf(worst, abs((b - a).cross(q - a)) / len)
            }
        }
        assertTrue(worst < 0.006, "the sampled flank is $worst mm off the exact involute")
        assertTrue(worst < GeomMath.TESS_TOL_MM / 3.0, "the sampling must not dominate the tessellation ($worst mm)")
    }

    private fun involute(
        rb: Double,
        beta: Double,
        psi: Double,
    ): Vec2 {
        val r = rb / cos(beta)
        val th = tan(beta) - beta - psi
        return Vec2(r * cos(th), r * sin(th))
    }

    /** An angle folded into (−π, π]. */
    private fun wrap(a: Double): Double {
        var x = a
        while (x > PI) x -= 2 * PI
        while (x <= -PI) x += 2 * PI
        return x
    }

    // ---- 3. the standard proportions, exactly ----

    /**
     * Tip and root diameters are the standard ones **exactly** — `ra = m(z/2 + 1)`, `rf = m(z/2 − 1.25)` —
     * and they are read off the geometry, not off the parameters that produced it.
     *
     * The bounding box is asserted separately, with the tessellation stated: the mesh inscribes the tip arc,
     * which at ±0.032 rad and r = 22 has a sagitta of 0.011 mm — under `TESS_TOL_MM`, so it becomes a single
     * chord and the box comes out short by exactly that. For an even tooth count the extremes are teeth,
     * which is why the box is the tip diameter at all.
     */
    @Test
    fun theTipAndRootDiametersAreExact() {
        val gear = gear()
        val c = gear.c
        val solid = c.extrude(c.sketchOn(c.planeXY(), gear.g.region), c.parameter("depth", 8.mm))
        val ev = Evaluator()

        assertClose(ev.scalar(gear.g.pitchRadius).mm, 20.0, tol = 1e-12)
        assertClose(ev.scalar(gear.g.tipRadius).mm, 22.0, tol = 1e-12)
        assertClose(ev.scalar(gear.g.rootRadius).mm, 17.5, tol = 1e-12)

        // no corner of the boundary escapes the tip circle or dips below the root circle
        val radii = corners(ev, gear.g).map { it.length() }
        assertTrue(radii.max() <= 22.0 + 1e-9, "nothing sticks out past the tip circle")
        assertTrue(radii.min() >= 17.5 - 1e-9, "nothing dips below the root circle")
        assertClose(radii.max(), 22.0, tol = 1e-9, msg = "the flank's last sample is on the tip circle")
        assertClose(radii.min(), 17.5, tol = 1e-9, msg = "the radial root reaches the root circle")

        // z = 20 puts a tooth on both the x and the y axis, so the box *is* the tip diameter
        for (axis in listOf(Axis3.X, Axis3.Y)) {
            assertClose(
                ev.scalar(c.measureBBoxExtent(solid, axis)).base,
                44.0,
                tol = 2.0 * GeomMath.TESS_TOL_MM,
                msg = "tip diameter along $axis, less the tip arcs' inscribed sagitta",
            )
        }
        assertClose(ev.scalar(c.measureBBoxExtent(solid, Axis3.Z)).base, 8.0, tol = 1e-9)

        // the teeth are evenly spaced: every tip arc's mid-angle is a multiple of the pitch angle
        val pitch = 2 * PI / 20
        val mids = arcsAt(ev, gear.g, 22.0).map { midAngle(it) }
        for (a in mids) assertClose(a, (a / pitch).roundToInt() * pitch, tol = 1e-12, msg = "a tooth is off its pitch position")
        assertEquals(20, mids.map { (it / pitch).roundToInt() }.distinct().size, "z distinct tooth positions")
    }

    /**
     * A parameter edit **computes**; only the tooth count rebuilds (the array rule, OP-21). Changing the
     * module rescales the whole gear through the existing graph — the same nodes, new numbers — while the
     * bore stays where it is, which makes "a 3 mm-module gear on the same shaft" a typed number.
     */
    @Test
    fun editingTheModuleRecomputesWithoutRebuildingTheGear() {
        val gear = gear()
        val c = gear.c
        val solid = c.extrude(c.sketchOn(c.planeXY(), gear.g.region), c.parameter("depth", 8.mm))
        assertManifold(Evaluator().solid(solid).mesh, "gear before the edit")

        val nodesBefore = c.nodesCreated
        c.set(gear.module, 3.mm)
        val ev = Evaluator()
        assertManifold(ev.solid(solid).mesh, "m = 3 gear")
        assertClose(ev.scalar(gear.g.pitchRadius).mm, 30.0, tol = 1e-12)
        assertClose(ev.scalar(gear.g.tipRadius).mm, 33.0, tol = 1e-12)
        assertClose(ev.scalar(gear.g.rootRadius).mm, 26.25, tol = 1e-12)
        assertClose(
            (ev.region(gear.g.region).holes.single().elements.single() as ProfileElement.CircleE).circle.radius,
            6.0,
            tol = 1e-12,
            msg = "the bore does not scale with the module",
        )

        c.set(gear.bore, 8.mm)
        val ev2 = Evaluator()
        assertManifold(ev2.solid(solid).mesh, "wider bore")
        assertClose(
            (ev2.region(gear.g.region).holes.single().elements.single() as ProfileElement.CircleE).circle.radius,
            8.0,
            tol = 1e-12,
        )
        assertEquals(nodesBefore, c.nodesCreated, "a parameter edit must not create nodes")

        // the tooth count *is* structural: a different z is a different instance, exactly as a different
        // array count is — and it is one line, which is what the macro is for
        val c2 = Construction()
        val other = gear(z = 31, tag = "z31", c = c2)
        val s2 = c2.extrude(c2.sketchOn(c2.planeXY(), other.g.region), c2.parameter("d", 5.mm))
        val ev3 = Evaluator()
        assertManifold(ev3.solid(s2).mesh, "31-tooth gear")
        assertEquals(31, arcsAt(ev3, other.g, 33.0).size, "31 tip arcs at m(z/2 + 1) = 33 mm")
        assertClose(ev3.scalar(other.g.pitchRadius).mm, 31.0, tol = 1e-12)
    }

    /**
     * A gear with enough teeth has its **root circle outside its base circle** — the involute then starts on
     * the root circle and the radial root line collapses to nothing. There is no case for it in the macro: a
     * zero-length piece chains and tessellates away, which is why one construction covers both families.
     */
    @Test
    fun aGearWhoseRootCircleIsOutsideItsBaseCircleNeedsNoRadialRoot() {
        val gear = gear(m = 2.0, z = 60, boreR = 20.0, tag = "big")
        val ev = Evaluator()
        val rb = ev.scalar(gear.g.baseRadius).mm
        val rf = ev.scalar(gear.g.rootRadius).mm
        assertTrue(rf > rb, "with 60 teeth the root circle ($rf) is outside the base circle ($rb)")

        val solid = gear.c.extrude(gear.c.sketchOn(gear.c.planeXY(), gear.g.region), gear.c.parameter("d", 6.mm))
        assertManifold(Evaluator().solid(solid).mesh, "60-tooth gear")
        assertEquals(60, arcsAt(ev, gear.g, 62.0).size)
        // the involute's foot *is* on the root circle, so nothing sits between the two circles
        assertTrue(corners(ev, gear.g).none { it.length() < rf - 1e-9 }, "nothing below the root circle")
    }

    /**
     * The macro's own **domain is a node** (OP-3). At a 45° pressure angle a 20-tooth gear's tooth is wider
     * at its foot than half the pitch, so the flanks would meet before the root land begins — the gear
     * refuses *with a reason* instead of emitting a boundary folded through itself, and heals when the
     * pressure angle comes back down.
     *
     * Worth recording what the guard does *not* fire on: with the flank's foot clamped to the root circle,
     * every pressure angle up to 30° stays inside the domain at every tooth count, so no standard gear ever
     * meets it. A limit that only exotic input reaches is still better stated as a node than as a comment.
     */
    @Test
    fun anImpossibleToothFormIsRefusedWithAReasonAndHeals() {
        val c = Construction()
        val alphaP = c.parameter("alpha", 45.0.deg)
        val g =
            c.instance(
                spurGear,
                "steep",
                SpurGearArgs(c.freePoint("o", 0.mm, 0.mm), c.parameter("m", 2.mm), 20, alphaP, c.parameter("bore", 6.mm)),
            )
        val bad = Evaluator().resultOf(g.region)
        assertTrue(bad is EvalResult.Invalid, "a 45 degree pressure angle leaves no root land at 20 teeth")
        assertTrue(bad.reason.contains("meet before the root land"), "reason was: ${bad.reason}")

        c.set(alphaP, 20.0.deg)
        val ev = Evaluator()
        assertTrue(ev.resultOf(g.region) is EvalResult.Ok, "a smaller pressure angle heals it: ${ev.resultOf(g.region)}")
        assertEquals(20, arcsAt(ev, g, 22.0).size, "20 tip arcs at m(z/2 + 1) = 22 mm")
        val area = ev.scalar(c.regionArea(g.region)).base
        val bore = PI * 36.0
        assertTrue(area > PI * 17.5 * 17.5 - bore && area < PI * 22.0 * 22.0 - bore, "the area is between the two rings")

        // a 30 degree gear is still fine at any tooth count the domain is asked about
        for (z in listOf(9, 20, 60, 130)) {
            val fine = gear(m = 1.0, z = z, alphaDeg = 30.0, boreR = 1.0, tag = "a30z$z")
            assertTrue(Evaluator().resultOf(fine.g.region) is EvalResult.Ok, "30 degrees, $z teeth")
        }
    }

    /**
     * The other half of the macro's domain: **the bore has to leave material.** A bore at or beyond the root
     * circle is refused with a reason and heals (OP-3).
     *
     * The second case is the one that earns this guard. A bore of 18 mm on a gear whose root circle is 17.5
     * is *smaller* than the toothed boundary, so it removes less area than the boundary encloses and the
     * region-level degeneracy check has nothing to say about it — the resulting shape is a ring of 20
     * detached teeth, which `region(...)` cannot detect because containment is deliberately not verified
     * (OP-14). Only the construction that made the shape knows it is nonsense, which is precisely why a macro
     * states its own domain instead of relying on the type below it.
     */
    @Test
    fun aBoreThatLeavesNoMaterialIsRefusedAndHeals() {
        val gear = gear()
        val c = gear.c
        assertTrue(Evaluator().resultOf(gear.g.region) is EvalResult.Ok, "a 6 mm bore is fine")

        // a bore right through the teeth: bigger than the whole blank
        c.set(gear.bore, 30.mm)
        val wild = Evaluator().resultOf(gear.g.region)
        assertTrue(wild is EvalResult.Invalid, "a bore beyond the tip circle cannot be a gear")
        assertTrue(wild.reason.contains("no material is left"), "reason was: ${wild.reason}")

        // ...and the subtle one: outside the root circle (17.5) but well inside the tip circle, so the area
        // check cannot see it — 20 teeth attached to nothing
        c.set(gear.bore, 18.mm)
        val detached = Evaluator().resultOf(gear.g.region)
        assertTrue(detached is EvalResult.Invalid, "a bore outside the root circle detaches every tooth")
        assertTrue(detached.reason.contains("no material is left"), "reason was: ${detached.reason}")

        // exactly at the root circle is refused too: a hole touching the boundary is not a hole
        c.set(gear.bore, 17.5.mm)
        assertTrue(Evaluator().resultOf(gear.g.region) is EvalResult.Invalid, "a bore *at* the root circle leaves nothing")

        c.set(gear.bore, 17.4.mm)
        val ev = Evaluator()
        assertTrue(ev.resultOf(gear.g.region) is EvalResult.Ok, "a thin rim is legal: ${ev.resultOf(gear.g.region)}")
        val solid = c.extrude(c.sketchOn(c.planeXY(), gear.g.region), c.parameter("d", 4.mm))
        assertManifold(Evaluator().solid(solid).mesh, "gear with a 0.1 mm rim at the root")

        c.set(gear.bore, 6.mm)
        assertTrue(Evaluator().resultOf(gear.g.region) is EvalResult.Ok, "and it heals")
    }

    // ---- 4. two of them, meshing ----

    /**
     * A gear **meshes its own copy** at centre distance `m·z` — asserted as geometry, not simulated.
     *
     * Three exact statements, which together are what "these two mesh" means for identical gears: the pitch
     * circles are tangent (centre distance = 2·rp = m·z, so the pitch point lies on both); the second gear's
     * teeth are half a pitch out of phase with the first's, so a tooth faces a space; and on the line of
     * centres the tip of one clears the root of the other by exactly the standard clearance `0.25 m` — the
     * difference between a 1·m addendum and a 1.25·m dedendum.
     *
     * Plus the property that lets the pair run either way: the gear is **mirror-symmetric** about the axis
     * through a tooth's centre, so each of a tooth's two flanks is the other reflected.
     */
    @Test
    fun aGearMeshesItsOwnCopyAtCentreDistanceModuleTimesTeeth() {
        val c = Construction()
        val centreDistanceMm = 40.0
        val a = gear(tag = "A", c = c)
        val bCentre = c.freePoint("B.centre", centreDistanceMm.mm, 0.mm)
        val b =
            c.instance(
                spurGear,
                "B",
                SpurGearArgs(bCentre, c.parameter("mB", 2.mm), 20, c.parameter("alphaB", 20.0.deg), c.parameter("boreB", 6.mm)),
            )
        // half a pitch out of phase, so a tooth of A faces a space of B: an ordinary rotation of the region
        val meshed = c.rotate(b.region, bCentre, c.const((PI / 20.0).rad))

        val ev = Evaluator()
        val distance = ev.scalar(c.measureDistance(c.freePoint("A.o", 0.mm, 0.mm), bCentre)).mm
        assertClose(distance, 40.0, tol = 1e-12, msg = "centre distance = m*z")
        assertClose(ev.scalar(a.g.pitchRadius).mm + ev.scalar(b.pitchRadius).mm, distance, tol = 1e-12, msg = "= rp + rp")

        // A has a tooth pointing at B (a tip arc covering angle 0), B a tooth space pointing back at A
        val tipTowardsB = arcsAt(ev, a.g, 22.0).single { covers(it, 0.0) }
        val bArcs = ev.region(meshed).outer.elements.mapNotNull { (it as? ProfileElement.ArcE)?.arc }
        val rootTowardsA = bArcs.single { abs(it.radius - 17.5) < 1e-9 && covers(it, PI) }
        assertClose(
            distance - tipTowardsB.radius - rootTowardsA.radius,
            0.25 * 2.0,
            tol = 1e-12,
            msg = "tip-to-root clearance on the line of centres is the standard 0.25 m",
        )

        // ...and every tooth of B is half a pitch off every tooth of A
        val pitch = 2 * PI / 20
        val aMids = arcsAt(ev, a.g, 22.0).map { midAngle(it) }
        val bMids = bArcs.filter { abs(it.radius - 22.0) < 1e-9 }.map { midAngle(it) }
        assertEquals(20, bMids.size)
        for (m in bMids) {
            val off = wrap(m - pitch / 2.0)
            assertClose(off - (off / pitch).roundToInt() * pitch, 0.0, tol = 1e-9, msg = "B is not half a pitch out of phase")
        }
        assertTrue(aMids.none { am -> bMids.any { abs(wrap(it - am)) < 1e-6 } }, "no tooth of B lines up with one of A")

        // both flanks of a tooth are one flank mirrored: the whole gear is symmetric about y = 0
        val cs = corners(ev, a.g)
        for (p in cs) {
            assertTrue(
                cs.any { hypot(it.x - p.x, it.y + p.y) < 1e-9 },
                "the boundary corner $p has no mirror image, so the tooth is not symmetric",
            )
        }
    }

    /**
     * Does [arc] cover the ray at [angle] from its own centre? Asked through the arc's signed **sweep**
     * rather than through min/max of its two angles, because a transformed arc's angles come back from
     * `atan2` and may straddle ±π — which is exactly the case here, the tooth space facing the other gear.
     */
    private fun covers(
        arc: Arc,
        angle: Double,
    ): Boolean {
        val sweep = GeomMath.sweep(arc)
        var t = (angle - arc.startAngle) % (2 * PI)
        if (t < 0) t += 2 * PI
        return if (sweep >= 0) t <= sweep + 1e-12 else t >= 2 * PI + sweep - 1e-12
    }

    /** An arc's mid-angle, via its sweep so that a ±π straddle is handled. */
    private fun midAngle(arc: Arc): Double = wrap(arc.startAngle + GeomMath.sweep(arc) / 2.0)

    // ---- the golden: the review *is* the picture ----

    /**
     * The 2D gear as SVG. This golden is the review: the teeth have to look like gear teeth — a curved flank
     * rising to a narrow tip land, a rounded root between them — and not like sawteeth.
     */
    @Test
    fun theGearDrawingIsAGolden() {
        val gear = gear()
        Golden.check("gear_m2_z20", Svg.render(Evaluator(), listOf(Drawable(gear.g.region))))
    }

    /** A second golden at the other end of the family: coarse teeth, few of them, and a big bore. */
    @Test
    fun aCoarseGearIsAGoldenToo() {
        val gear = gear(m = 4.0, z = 9, boreR = 5.0, tag = "coarse")
        assertTrue(Evaluator().resultOf(gear.g.region) is EvalResult.Ok)
        Golden.check("gear_m4_z9", Svg.render(Evaluator(), listOf(Drawable(gear.g.region))))
    }
}
